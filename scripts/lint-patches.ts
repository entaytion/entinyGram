import fs from 'node:fs/promises'
import { join } from 'node:path'
import { chalk } from 'zx'
import { patchesDir } from './config.js'
import {
  patchNameFromSeriesEntry,
  readSeries,
  success,
  warn,
} from './lib.js'

interface ChangedLine {
  file: string
  line: number
  content: string
}

interface DiffHunk {
  oldStart: number
  oldCount: number
  newCount: number
  changes: Array<{
    type: 'context' | 'add' | 'delete'
    oldLine?: number
  }>
}

interface PatchDelta {
  patch: string
  added: ChangedLine[]
  deleted: ChangedLine[]
}

interface Overwrite {
  earlier: string
  later: string
  locations: Map<string, Set<number>>
}

interface Reversion {
  earlier: string
  later: string
  files: string[]
  lines: number
}

interface LineCounts {
  added: Map<string, number>
  deleted: Map<string, number>
}

function normalizePatchPath(raw: string) {
  const value = raw.split('\t')[0].trim()
  if (!value || value === '/dev/null') return null
  if (value.startsWith('"a/') || value.startsWith('"b/')) return value.slice(3, -1)
  return value.replace(/^[ab]\//, '')
}

function parsePatchFiles(patch: string) {
  const headers = [...patch.matchAll(/^diff --git .+$/gm)]
  const files: Array<{ file: string, diff: string }> = []

  for (let i = 0; i < headers.length; i++) {
    const start = headers[i].index ?? 0
    const end = headers[i + 1]?.index ?? patch.length
    const diff = patch.slice(start, end)
    const oldPath = diff.match(/^--- (.+)$/m)
    const newPath = diff.match(/^\+\+\+ (.+)$/m)
    const file = normalizePatchPath(newPath?.[1] ?? '') ?? normalizePatchPath(oldPath?.[1] ?? '')
    if (file) files.push({ file, diff })
  }

  return files
}

function parseDiff(file: string, diff: string) {
  const added: ChangedLine[] = []
  const deleted: ChangedLine[] = []
  const hunks: DiffHunk[] = []
  let oldLine = 0
  let newLine = 0
  let inHunk = false
  let currentHunk: DiffHunk | null = null

  for (const line of diff.split(/\r?\n/)) {
    const hunk = line.match(/^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/)
    if (hunk) {
      oldLine = Number.parseInt(hunk[1], 10)
      newLine = Number.parseInt(hunk[3], 10)
      currentHunk = {
        oldStart: oldLine,
        oldCount: hunk[2] === undefined ? 1 : Number.parseInt(hunk[2], 10),
        newCount: hunk[4] === undefined ? 1 : Number.parseInt(hunk[4], 10),
        changes: [],
      }
      hunks.push(currentHunk)
      inHunk = true
      continue
    }
    if (!inHunk || line.startsWith('\\')) continue

    if (line.startsWith('-')) {
      currentHunk?.changes.push({ type: 'delete', oldLine })
      deleted.push({ file, line: oldLine++, content: line.slice(1) })
    } else if (line.startsWith('+')) {
      currentHunk?.changes.push({ type: 'add' })
      added.push({ file, line: newLine++, content: line.slice(1) })
    } else if (line.startsWith(' ')) {
      currentHunk?.changes.push({ type: 'context' })
      oldLine++
      newLine++
    }
  }

  return { added, deleted, hunks }
}

function countLines(lines: ChangedLine[]) {
  const counts = new Map<string, number>()
  for (const line of lines) {
    const key = `${line.file}\0${line.content}`
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  return counts
}

function getNetLineCounts(delta: PatchDelta): LineCounts {
  const added = countLines(delta.added)
  const deleted = countLines(delta.deleted)

  for (const [key, addedCount] of added) {
    const deletedCount = deleted.get(key) ?? 0
    const shared = Math.min(addedCount, deletedCount)
    if (shared === 0) continue

    if (addedCount === shared) added.delete(key)
    else added.set(key, addedCount - shared)

    if (deletedCount === shared) deleted.delete(key)
    else deleted.set(key, deletedCount - shared)
  }

  return { added, deleted }
}

function containsCounts(actual: Map<string, number>, expected: Map<string, number>) {
  for (const [key, count] of expected) {
    if ((actual.get(key) ?? 0) < count) return false
  }
  return true
}

function countValues(counts: Map<string, number>) {
  let total = 0
  for (const count of counts.values()) total += count
  return total
}

function getRanges(lines: number[]) {
  const sorted = [...new Set(lines)].sort((a, b) => a - b)
  const ranges: Array<[number, number]> = []

  for (const line of sorted) {
    const last = ranges.at(-1)
    if (last && line === last[1] + 1) {
      last[1] = line
    } else {
      ranges.push([line, line])
    }
  }

  return ranges
}

function formatRanges(lines: number[]) {
  return getRanges(lines)
    .map(([start, end]) => start === end ? `${start}` : `${start}-${end}`)
    .join(',')
}

const args = new Set(process.argv.slice(2))
if (args.delete('--help')) {
  console.log('Usage: pnpm run lint-patches [--check]')
  console.log('  --check  exit non-zero when findings exist')
  process.exit(0)
}
const check = args.delete('--check')
if (args.size > 0) {
  throw new Error(`Unknown argument: ${[...args][0]}`)
}

const seriesEntries = await readSeries()
const patches = seriesEntries.map(patchNameFromSeriesEntry)
const patchContents = await Promise.all(
  seriesEntries.map(entry => fs.readFile(join(patchesDir, entry), 'utf8')),
)
const patchIndexes = new Map(patches.map((patch, index) => [patch, index]))

const deltas: PatchDelta[] = []
const overwrites = new Map<string, Overwrite>()
const fileOwners = new Map<string, Array<string | null>>()

for (let patchIndex = 0; patchIndex < patches.length; patchIndex++) {
  const patch = patches[patchIndex]
  const content = patchContents[patchIndex]
  const delta: PatchDelta = { patch, added: [], deleted: [] }

  for (const { file, diff } of parsePatchFiles(content)) {
    const fileDelta = parseDiff(file, diff)
    delta.added.push(...fileDelta.added)
    delta.deleted.push(...fileDelta.deleted)

    let owners = fileOwners.get(file)
    if (!owners) {
      owners = []
      fileOwners.set(file, owners)
    }
    let offset = 0

    for (const hunk of fileDelta.hunks) {
      let index = hunk.oldCount === 0
        ? hunk.oldStart + offset
        : Math.max(0, hunk.oldStart - 1 + offset)

      for (const change of hunk.changes) {
        if (change.type === 'context') {
          while (owners.length <= index) owners.push(null)
          index++
          continue
        }
        if (change.type === 'add') {
          while (owners.length < index) owners.push(null)
          owners.splice(index++, 0, patch)
          continue
        }

        while (owners.length <= index) owners.push(null)
        const owner = owners.splice(index, 1)[0]
        if (!owner || owner === patch) continue
        const key = `${owner}\0${patch}`
        let overwrite = overwrites.get(key)
        if (!overwrite) {
          overwrite = { earlier: owner, later: patch, locations: new Map() }
          overwrites.set(key, overwrite)
        }

        let locations = overwrite.locations.get(file)
        if (!locations) {
          locations = new Set()
          overwrite.locations.set(file, locations)
        }
        locations.add(change.oldLine ?? hunk.oldStart)
      }

      offset += hunk.newCount - hunk.oldCount
    }
  }

  deltas.push(delta)
}

const netCounts = deltas.map(getNetLineCounts)
const reversions: Reversion[] = []
const reversionPairs = new Set<string>()

for (let laterIndex = 1; laterIndex < deltas.length; laterIndex++) {
  const later = deltas[laterIndex]
  const laterCounts = netCounts[laterIndex]

  for (let earlierIndex = 0; earlierIndex < laterIndex; earlierIndex++) {
    const earlier = deltas[earlierIndex]
    const earlierCounts = netCounts[earlierIndex]
    const changedLines = countValues(earlierCounts.added) + countValues(earlierCounts.deleted)
    if (changedLines === 0) continue
    if (!containsCounts(laterCounts.deleted, earlierCounts.added)) continue
    if (!containsCounts(laterCounts.added, earlierCounts.deleted)) continue

    reversions.push({
      earlier: earlier.patch,
      later: later.patch,
      files: [...new Set([...earlier.added, ...earlier.deleted].map(line => line.file))].sort(),
      lines: changedLines,
    })
    reversionPairs.add(`${earlier.patch}\0${later.patch}`)
  }
}

const partialOverwrites = [...overwrites.values()]
  .filter(overwrite => !reversionPairs.has(`${overwrite.earlier}\0${overwrite.later}`))
  .sort((a, b) => {
    const later = (patchIndexes.get(a.later) ?? 0) - (patchIndexes.get(b.later) ?? 0)
    return later || (patchIndexes.get(a.earlier) ?? 0) - (patchIndexes.get(b.earlier) ?? 0)
  })

if (reversions.length === 0 && partialOverwrites.length === 0) {
  success(`No patch overwrites found across ${patches.length} patches`)
  process.exit(0)
}

if (reversions.length > 0) {
  console.log(chalk.red('Full reversions:'))
  for (const reversion of reversions) {
    console.log(`  ${reversion.later} reverts ${reversion.earlier} (${reversion.lines} changed lines)`)
    for (const file of reversion.files) console.log(`    ${file}`)
  }
}

if (partialOverwrites.length > 0) {
  if (reversions.length > 0) console.log()
  console.log(chalk.yellow('Earlier patch lines overwritten:'))
  for (const overwrite of partialOverwrites) {
    const count = [...overwrite.locations.values()].reduce((total, lines) => total + lines.size, 0)
    console.log(`  ${overwrite.later} overwrites ${count} ${count === 1 ? 'line' : 'lines'} from ${overwrite.earlier}`)
    for (const [file, lines] of overwrite.locations) {
      console.log(`    ${file}:${formatRanges([...lines])}`)
    }
  }
}

console.log()
warn(`Found ${reversions.length} full ${reversions.length === 1 ? 'reversion' : 'reversions'} and ${partialOverwrites.length} partial ${partialOverwrites.length === 1 ? 'overwrite' : 'overwrites'}`)
if (check) process.exitCode = 1
