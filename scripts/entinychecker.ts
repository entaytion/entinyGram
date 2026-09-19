import fs from 'node:fs/promises'
import { join, relative } from 'node:path'
import { rootDir, worktreeDir } from './config.js'
import { success, warn } from './lib.js'


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
