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

async function walk(dir: string, out: string[], exts: string[] = ['.kt']) {
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) {
      await walk(full, out, exts)
    } else if (exts.some(ext => entry.name.endsWith(ext))) {
      out.push(full)
    }
  }
}

// Catches fork strings_inu.xml entries that no code path references any more - the
// leftover from a dropped patch, a removed settings row, or a feature that got rewired
// through a different string. A string can be "used" in four ways:
//   - a literal `R.string.InuXxx` reference (Java patch or Kotlin helper/UI)
//   - a quoted string-literal lookup, e.g. `getString("InuXxx", ...)` / `getLocalString("InuXxx")`
//   - a plural stem passed to `formatPluralString("InuXxx", n)` - covers every `_one`/`_few`/
//     `_many`/`_other`/`_zero`/`_two` sibling of that key, since strings_inu.xml stores plurals
//     as flat suffixed entries rather than <plurals> blocks.
//   - an `@string/InuXxx` reference from XML: AndroidManifest.xml or any resource file.
//
// That last one is not theoretical. `InuDisguiseName` is referenced only by the manifest
// (`android:label="@string/InuDisguiseName"`, added by misc/branding.patch). Scanning code
// alone reported it dead, it was deleted on that advice, and the build failed at
// processDebugResources with "resource string/InuDisguiseName not found" - long after
// javac had happily compiled, because no code ever touched it.
const STRING_KEY_RE = /<string\s+name="(Inu[A-Za-z0-9_]+)"/g
const RSTRING_REF_RE = /R\.string\.(Inu[A-Za-z0-9_]+)/g
const STRING_LITERAL_REF_RE = /["'](Inu[A-Za-z0-9_]+)["']/g
const XML_STRING_REF_RE = /@string\/(Inu[A-Za-z0-9_]+)/g
const PLURAL_SUFFIX_RE = /_(zero|one|two|few|many|other)$/

function pluralStem(key: string) {
  const m = key.match(PLURAL_SUFFIX_RE)
  return m ? key.slice(0, -m[0].length) : null
}

async function findDeadStrings(): Promise<{ dead: string[], total: number }> {
  const baseFile = join(rootDir, 'src/res/values/strings_inu.xml')
  const baseXml = await fs.readFile(baseFile, 'utf8')

  const declared = new Set<string>()
  for (const m of baseXml.matchAll(STRING_KEY_RE)) declared.add(m[1])

  const sourceFiles: string[] = []
  await walk(join(rootDir, 'src/kotlin'), sourceFiles, ['.kt'])
  await walk(worktreeDir, sourceFiles, ['.java'])

  // Kept separate from the code files on purpose: STRING_LITERAL_REF_RE matches any quoted
  // Inu* token, and strings_inu.xml is full of `name="InuXxx"` declarations, so running the
  // code regexes over XML would mark every string as a reference to itself and the check
  // would never find anything. XML only ever gets the @string/ regex.
  const xmlFiles: string[] = []
  await walk(worktreeDir, xmlFiles, ['.xml'])
  await walk(join(rootDir, 'src/res'), xmlFiles, ['.xml'])

  const used = new Set<string>()
  for (const file of sourceFiles) {
    const content = await fs.readFile(file, 'utf8')
    for (const m of content.matchAll(RSTRING_REF_RE)) used.add(m[1])
    for (const m of content.matchAll(STRING_LITERAL_REF_RE)) used.add(m[1])
  }
  for (const file of xmlFiles) {
    const content = await fs.readFile(file, 'utf8')
    for (const m of content.matchAll(XML_STRING_REF_RE)) used.add(m[1])
  }

  const usedStems = new Set<string>()
  for (const key of used) usedStems.add(key)

  const dead: string[] = []
  for (const key of declared) {
    if (used.has(key)) continue
    const stem = pluralStem(key)
    if (stem && usedStems.has(stem)) continue
    dead.push(key)
  }
  dead.sort()

  return { dead, total: declared.size }
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
  } else {
    warn(`Found ${duplicates.length} duplicate SearchRegistry slug(s):`)
    for (const [slug, list] of duplicates) {
      console.log(`\n  "${slug}"`)
      for (const hit of list) {
        console.log(`    ${hit.kind}  ${hit.file}:${hit.line}`)
      }
    }
    process.exitCode = 1
  }

  const { dead, total } = await findDeadStrings()
  if (dead.length === 0) {
    success(`No dead strings across ${total} entries in values/strings_inu.xml`)
  } else {
    warn(`Found ${dead.length} dead string(s) in values/strings_inu.xml (unreferenced by any R.string.*, literal lookup, @string/ in XML, or plural stem):`)
    for (const key of dead) {
      console.log(`  ${key}`)
    }
    console.log(`\n  Remove from values/strings_inu.xml and every values-<locale>/strings_inu.xml.`)
    process.exitCode = 1
  }
}

main()
