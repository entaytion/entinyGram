import fs from 'node:fs/promises'
import { join, relative } from 'node:path'
import { rootDir, worktreeDir } from './config.js'
import { success, warn } from './lib.js'

// Statically catches SearchRegistry.Entry/Page slug collisions before they blow up at
// runtime via the require() in SearchRegistry.targetBySlug (which only fires the first
// time settings search is opened).

// Unused stock app-variant modules (HockeyApp, Huawei, Standalone) and the instrumented
// test module aren't in settings.gradle's `include` list, so they never build — but each
// carries their own removal patch (misc/remove-unused-*), and a patch can only delete
// files that still exist at rebase time. If upstream re-adds one of these dirs (or a
// rebase conflict resolution restores it), the removal patch silently stops covering the
// new files until someone notices. Delete them again here so `bun run entinychecker`
// (or CI) catches drift instead of the removal quietly rotting.
const UNUSED_APP_VARIANTS = [
  'TMessagesProj_AppHockeyApp',
  'TMessagesProj_AppHuawei',
  'TMessagesProj_AppStandalone',
  'TMessagesProj_AppTests',
]

async function cleanUnusedAppVariants(): Promise<string[]> {
  const removed: string[] = []
  for (const name of UNUSED_APP_VARIANTS) {
    const dir = join(worktreeDir, name)
    const exists = await fs.stat(dir).then(() => true).catch(() => false)
    if (!exists) continue
    await fs.rm(dir, { recursive: true, force: true })
    removed.push(name)
  }
  return removed
}

interface SlugHit {
  slug: string
  file: string
  line: number
  kind: 'Entry' | 'Page'
}

const ENTRY_RE = /SearchRegistry\.Entry\(\s*"([^"]+)"/g
const PAGE_RE = /SearchRegistry\.Page\(\s*[\s\S]*?slug\s*=\s*"([^"]+)"/g

async function walk(dir: string, out: string[]) {
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      await walk(full, out)
    } else if (entry.name.endsWith('.kt')) {
      out.push(full)
    }
  }
}

async function main() {
  const removedVariants = await cleanUnusedAppVariants()
  if (removedVariants.length > 0) {
    warn(`Removed unused app variants that reappeared: ${removedVariants.join(', ')}`)
  } else {
    success('No unused app variants present')
  }

  const srcKotlin = join(rootDir, 'src/kotlin')
  const files: string[] = []
  await walk(srcKotlin, files)

  const hits: SlugHit[] = []

  for (const file of files) {
    const content = await fs.readFile(file, 'utf8')
    const relPath = relative(rootDir, file).replaceAll('\\', '/')

    for (const re of [ENTRY_RE, PAGE_RE]) {
      re.lastIndex = 0
      let match: RegExpExecArray | null
      while ((match = re.exec(content))) {
        const line = content.slice(0, match.index).split('\n').length
        hits.push({
          slug: match[1],
          file: relPath,
          line,
          kind: re === ENTRY_RE ? 'Entry' : 'Page',
        })
      }
    }
  }

  const bySlug = new Map<string, SlugHit[]>()
  for (const hit of hits) {
    const list = bySlug.get(hit.slug) ?? []
    list.push(hit)
    bySlug.set(hit.slug, list)
  }

  const duplicates = [...bySlug.entries()].filter(([, list]) => list.length > 1)

  if (duplicates.length === 0) {
    success(`No duplicate SearchRegistry slugs across ${hits.length} entries in ${files.length} files`)
    return
  }

  warn(`Found ${duplicates.length} duplicate SearchRegistry slug(s):`)
  for (const [slug, list] of duplicates) {
    console.log(`\n  "${slug}"`)
    for (const hit of list) {
      console.log(`    ${hit.kind}  ${hit.file}:${hit.line}`)
    }
  }
  process.exitCode = 1
}

main()
