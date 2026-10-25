import { createHash } from 'node:crypto'
import fs from 'node:fs/promises'
import { join, relative } from 'node:path'
import { parallelMap } from '@fuman/utils'
import { patchesDir, rootDir, worktreeDir } from './config.js'
import {
  cd,
  ensureDir,
  generateStablePatchFromCommit,
  getAllPatchCommitIds,
  getAppliedPatchNames,
  parsePatchName,
  resolveFromRoot,
  step,
  success,
  warn,
  writeSeries,
} from './lib.js'

interface CacheEntry {
  commitId: string
  hash: string
}

async function listFilesRecursive(dir: string): Promise<string[]> {
  const entries = await fs.readdir(dir, { withFileTypes: true }).catch(() => [])
  const files: string[] = []

  for (const entry of entries) {
    const fullPath = join(dir, entry.name)
    if (entry.isDirectory()) {
      files.push(...await listFilesRecursive(fullPath))
      continue
    }
    files.push(fullPath)
  }

  return files
}

function hashPatch(patch: string) {
  return createHash('sha1').update(patch).digest('hex')
}

const repoDir = resolveFromRoot(process.argv[2], worktreeDir)

const dirty = (await cd(repoDir)`git status --porcelain`).stdout.trim()
if (dirty) {
  warn(`Worktree has uncommitted changes:\n${dirty}`)
}

const cacheFile = (await cd(repoDir)`git rev-parse --path-format=absolute --git-path inu-export-cache.json`).stdout.trim()
const cache: Record<string, CacheEntry> = JSON.parse(await fs.readFile(cacheFile, 'utf8').catch(() => '{}'))
const nextCache: Record<string, CacheEntry> = {}

const [patchNames, commitIds] = await Promise.all([
  getAppliedPatchNames(repoDir),
  getAllPatchCommitIds(repoDir),
])

let regenerated = 0
const exported = await parallelMap(patchNames, async (patchName) => {
  const commitId = commitIds.get(patchName)
  if (!commitId) throw new Error(`No commit id for applied patch ${patchName}`)

  const parsed = parsePatchName(patchName)
  const targetFile = join(patchesDir, parsed.dir, `${parsed.name}.patch`)
  const current = await fs.readFile(targetFile, 'utf8').catch(() => null)
  const cached = cache[patchName]

  let patch = current
  if (patch === null || cached?.commitId !== commitId || cached.hash !== hashPatch(patch)) {
    step(`Exporting ${patchName} -> ${relative(rootDir, targetFile)}`)
    patch = await generateStablePatchFromCommit(repoDir, commitId)
    if (patch.includes('�')) {
      warn(`Detected corrupted output while exporting ${patchName}, retrying once`)
      patch = await generateStablePatchFromCommit(repoDir, commitId)
      if (patch.includes('�')) throw new Error(`Corrupted (U+FFFD) output persisted for ${patchName} after retry - aborting export`)
    }
    regenerated++
    if (patch !== current) {
      await ensureDir(join(patchesDir, parsed.dir))
      await fs.writeFile(targetFile, patch)
    }
  }

  if (!patch.includes('\ndiff --git ')) {
    warn(`Patch ${patchName} is empty`)
  }

  nextCache[patchName] = { commitId, hash: hashPatch(patch) }
  return { seriesEntry: parsed.seriesEntry, targetFile }
})

const targetFiles = new Set(exported.map(it => it.targetFile))
for (const file of await listFilesRecursive(patchesDir)) {
  if (file.endsWith('.patch') && !targetFiles.has(file)) {
    step(`Removing stale ${relative(rootDir, file)}`)
    await fs.unlink(file)
  }
}

await writeSeries(exported.map(it => it.seriesEntry))
await fs.writeFile(cacheFile, JSON.stringify(nextCache))

// warn again because it might get lost in logs
if (dirty) {
  warn(`Worktree has uncommitted changes:\n${dirty}`)
}

success(`Exported ${exported.length} ${exported.length === 1 ? 'patch' : 'patches'} (${regenerated} regenerated)`)
