import fs from 'node:fs/promises'
import { join, relative } from 'node:path'
import { rootDir } from './config.js'

// Scans values/strings_inu.xml against every values-<locale>/strings_inu.xml and
// reports, per locale:
//   - missing        : key present in base, absent in the locale
//   - untranslated   : key present, but the value is a byte-identical copy of the
//                      base English text (i.e. a placeholder, not a translation)
//   - extra          : key present only in the locale
//
// Strings whose value is identical in every locale that has them are assumed to be
// brand names / shared terms and are reported separately, not as "untranslated".
//
// Usage:
//   bun scripts/check-translations.ts          # all locales
//   bun scripts/check-translations.ts ru       # single locale

const onlyIso = process.argv[2]

const stringRe = /<string\s+name="([^"]+)">([\s\S]*?)<\/string>/g

async function parseStrings(file: string) {
  const text = await fs.readFile(file, 'utf8')
  const map = new Map<string, string>()
  for (const m of text.matchAll(stringRe)) {
    map.set(m[1], m[2])
  }
  return map
}

const baseFile = join(rootDir, 'src/res/values/strings_inu.xml')
const base = await parseStrings(baseFile)

// discover locales: src/res/values-<iso>/strings_inu.xml
const resDir = join(rootDir, 'src/res')
const entries = await fs.readdir(resDir, { withFileTypes: true })
const allLocales: Array<{ iso: string, file: string, strings: Map<string, string> }> = []
for (const e of entries) {
  const m = e.name.match(/^values-(.+)$/)
  if (!m || !e.isDirectory()) continue
  const iso = m[1]
  const file = join(resDir, e.name, 'strings_inu.xml')
  const strings = await parseStrings(file)
  allLocales.push({ iso, file, strings })
}
allLocales.sort((a, b) => a.iso.localeCompare(b.iso))

if (allLocales.length === 0) {
  console.error(`No locales found under ${relative(rootDir, resDir)}`)
  process.exit(1)
}

// all locales are always loaded so that "identical everywhere" is classified
// consistently regardless of the output filter
const locales = onlyIso ? allLocales.filter(l => l.iso === onlyIso) : allLocales

if (locales.length === 0) {
  console.error(`Locale "${onlyIso}" not found (available: ${allLocales.map(l => l.iso).join(', ')})`)
  process.exit(1)
}

const PLURAL_SUFFIX_RE = /_(zero|one|two|few|many|other)$/
function pluralStem(key: string) {
  const m = key.match(PLURAL_SUFFIX_RE)
  return m ? key.slice(0, -m[0].length) : null
}

function pluralStems(keys: Iterable<string>) {
  const out = new Set<string>()
  for (const k of keys) {
    const stem = pluralStem(k)
    if (stem) out.add(stem)
  }
  return out
}

const baseStems = pluralStems(base.keys())
const localeStems = new Map(allLocales.map(l => [l.iso, pluralStems(l.strings.keys())]))

// values identical across every map that contains the key (incl. base)
function identicalEverywhere(key: string) {
  const seen = new Set<string>([base.get(key)!])
  for (const l of allLocales) {
    const v = l.strings.get(key)
    if (v !== undefined) seen.add(v)
    if (seen.size > 1) return false
  }
  return true
}

interface LocaleReport {
  missing: string[]
  untranslated: string[]
  shared: string[]
  extra: string[]
}

const reports = new Map<string, LocaleReport>()
for (const l of locales) {
  const missing: string[] = []
  const untranslated: string[] = []
  const shared: string[] = []
  const extra: string[] = []
  const lStems = localeStems.get(l.iso)!

  for (const key of base.keys()) {
    if (l.strings.has(key)) continue
    const stem = pluralStem(key)
    if (stem && lStems.has(stem)) continue
    missing.push(key)
  }
  for (const key of base.keys()) {
    if (!l.strings.has(key)) continue
    if (l.strings.get(key) !== base.get(key)) continue
    if (identicalEverywhere(key)) {
      shared.push(key)
    } else {
      untranslated.push(key)
    }
  }
  for (const key of l.strings.keys()) {
    if (base.has(key)) continue
    const stem = pluralStem(key)
    if (stem && baseStems.has(stem)) continue
    extra.push(key)
  }

  for (const list of [missing, untranslated, shared, extra]) list.sort()
  reports.set(l.iso, { missing, untranslated, shared, extra })
}

// ---- output -------------------------------------------------------------

const fmt = (n: number) => String(n)
const pad = (s: string, w: number) => s.padEnd(w)

console.log(`# Inu translation check (${relative(rootDir, baseFile)})`)
console.log()
console.log(`Base strings: ${base.size}`)
console.log(`Locales: ${locales.map(l => l.iso).join(', ') || '(none)'}`)
console.log()

const header = `| locale | total | missing | untranslated | extra |`
const sep = `|--------|------:|--------:|-------------:|------:|`
console.log(header)
console.log(sep)
for (const l of locales) {
  const r = reports.get(l.iso)!
  console.log(
    `| ${pad(l.iso, 6)} | ${fmt(l.strings.size).padStart(5)} | ${fmt(r.missing.length).padStart(7)} | ${fmt(r.untranslated.length).padStart(12)} | ${fmt(r.extra.length).padStart(5)} |`,
  )
}
console.log()

const totalMissing = locales.reduce((n, l) => n + reports.get(l.iso)!.missing.length, 0)
const totalUntranslated = locales.reduce((n, l) => n + reports.get(l.iso)!.untranslated.length, 0)
console.log(`Totals across locales: ${totalMissing} missing, ${totalUntranslated} untranslated copies`)
console.log()

const dump = (title: string, keys: string[], get: (k: string) => string) => {
  if (keys.length === 0) {
    console.log(`## ${title} — (none)`)
    console.log()
    return
  }
  console.log(`## ${title} (${keys.length})`)
  console.log()
  for (const key of keys) {
    console.log(`<string name="${key}">${get(key)}</string>`)
  }
  console.log()
}

for (const l of locales) {
  const r = reports.get(l.iso)!
  dump(`${l.iso} — missing (present in values/, absent in values-${l.iso}/)`, r.missing, k => base.get(k)!)
  dump(`${l.iso} — untranslated copy (value equals base English text)`, r.untranslated, k => base.get(k)!)
  dump(`${l.iso} — identical everywhere (brand terms, usually fine)`, r.shared, k => base.get(k)!)
  dump(`${l.iso} — extra (present in values-${l.iso}/, absent in values/)`, r.extra, k => l.strings.get(k)!)
}
