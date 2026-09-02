import fs from 'node:fs/promises'
import { join, relative } from 'node:path'
import { $ } from 'zx'
import { rootDir } from './config.js'

$.verbose = false

// Scans values/strings_inu.xml against every values-<locale>/strings_inu.xml and
// reports, per locale:
//   - missing        : key present in base, absent in the locale
//   - untranslated   : key present, but the value is a byte-identical copy of the
//                      base English text (i.e. a placeholder, not a translation)
//   - stale          : key where base English was modified after the translation (git blame)
//   - extra          : key present only in the locale
//
// Usage:
//   bun scripts/check-translations.ts                  # summary + breakdown for all locales
//   bun scripts/check-translations.ts ru               # single locale (includes stale check)
//   bun scripts/check-translations.ts --matrix         # matrix of which keys are missing where
//   bun scripts/check-translations.ts --missing [iso]  # only missing XML strings
//   bun scripts/check-translations.ts --untranslated   # only untranslated copy strings
//   bun scripts/check-translations.ts --stale [iso]    # only stale translations (git blame)
//   bun scripts/check-translations.ts --summary        # only summary table
//   bun scripts/check-translations.ts --fill [iso]     # append missing keys with base text into locale files

const args = process.argv.slice(2)
const showMatrix = args.includes('--matrix') || args.includes('-m')
const showSummaryOnly = args.includes('--summary') || args.includes('-s')
const fillMissing = args.includes('--fill') || args.includes('-f')
const onlyMissing = args.includes('--missing')
const onlyUntranslated = args.includes('--untranslated') || args.includes('-u')
const onlyStale = args.includes('--stale')
const onlyExtra = args.includes('--extra') || args.includes('-e')
const onlyIso = args.find(a => !a.startsWith('-'))

const stringRe = /<string\s+name="([^"]+)">([\s\S]*?)<\/string>/g
const stringNameRe = /<string\s+name="([^"]+)">/

async function parseStrings(file: string) {
  const text = await fs.readFile(file, 'utf8')
  const map = new Map<string, string>()
  for (const m of text.matchAll(stringRe)) {
    map.set(m[1], m[2])
  }
  return map
}

async function blameKeyTimes(file: string) {
  const rel = relative(rootDir, file).split('\\').join('/')
  try {
    const out = (await $({ cwd: rootDir })`git blame --line-porcelain -w -M -C -- ${rel}`).stdout
    const map = new Map<string, number>()
    let time = 0
    for (const line of out.split('\n')) {
      if (line.startsWith('author-time ')) {
        time = Number.parseInt(line.slice(12), 10)
      } else if (line.startsWith('\t')) {
        const m = line.slice(1).match(stringNameRe)
        if (m) map.set(m[1], time)
      }
    }
    return map
  } catch {
    return new Map<string, number>()
  }
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
  try {
    await fs.access(file)
  } catch {
    continue
  }
  const strings = await parseStrings(file)
  allLocales.push({ iso, file, strings })
}
allLocales.sort((a, b) => a.iso.localeCompare(b.iso))

if (allLocales.length === 0) {
  console.error(`No locales found under ${relative(rootDir, resDir)}`)
  process.exit(1)
}

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

// Stale detection via git blame if checking single locale or explicit --stale flag
const checkStale = onlyStale || (onlyIso !== undefined && !onlyMissing && !onlyUntranslated && !onlyExtra)
let baseTimes: Map<string, number> | null = null
if (checkStale) {
  baseTimes = await blameKeyTimes(baseFile)
}

interface LocaleReport {
  missing: string[]
  untranslated: string[]
  shared: string[]
  stale: string[]
  extra: string[]
}

const reports = new Map<string, LocaleReport>()
for (const l of allLocales) {
  const missing: string[] = []
  const untranslated: string[] = []
  const shared: string[] = []
  const stale: string[] = []
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

  if (checkStale && baseTimes) {
    const lTimes = await blameKeyTimes(l.file)
    for (const key of base.keys()) {
      if (!l.strings.has(key)) continue
      const bt = baseTimes.get(key)
      const lt = lTimes.get(key)
      if (bt !== undefined && lt !== undefined && bt > lt) {
        stale.push(key)
      }
    }
    stale.sort()
  }

  for (const list of [missing, untranslated, shared, extra]) list.sort()
  reports.set(l.iso, { missing, untranslated, shared, stale, extra })
}

// ---- output -------------------------------------------------------------

const fmt = (n: number) => String(n)
const pad = (s: string, w: number) => s.padEnd(w)

console.log(`# Inu translation check (${relative(rootDir, baseFile)})`)
console.log()
console.log(`Base strings: ${base.size}`)
console.log(`Locales: ${allLocales.map(l => l.iso).join(', ')}`)
console.log()

const header = `| locale | total | missing | untranslated | extra | coverage |`
const sep = `|--------|------:|--------:|-------------:|------:|---------:|`
console.log(header)
console.log(sep)
for (const l of locales) {
  const r = reports.get(l.iso)!
  const totalBase = base.size
  const translated = totalBase - r.missing.length - r.untranslated.length
  const pct = ((translated / totalBase) * 100).toFixed(1) + '%'
  console.log(
    `| ${pad(l.iso, 6)} | ${fmt(l.strings.size).padStart(5)} | ${fmt(r.missing.length).padStart(7)} | ${fmt(r.untranslated.length).padStart(12)} | ${fmt(r.extra.length).padStart(5)} | ${pct.padStart(8)} |`,
  )
}
console.log()

const totalMissing = locales.reduce((n, l) => n + reports.get(l.iso)!.missing.length, 0)
const totalUntranslated = locales.reduce((n, l) => n + reports.get(l.iso)!.untranslated.length, 0)
console.log(`Totals across inspected locales: ${totalMissing} missing, ${totalUntranslated} untranslated copies`)
console.log()

if (showSummaryOnly) {
  process.exit(0)
}

if (fillMissing) {
  for (const l of locales) {
    const r = reports.get(l.iso)!
    if (r.missing.length === 0) {
      console.log(`[${l.iso}] All keys already present, nothing to fill.`)
      continue
    }
    const linesToAdd = r.missing.map(k => `    <string name="${k}">${base.get(k)}</string>`)
    const fileContent = await fs.readFile(l.file, 'utf8')
    const updated = fileContent.replace('</resources>', linesToAdd.join('\n') + '\n</resources>')
    await fs.writeFile(l.file, updated, 'utf8')
    console.log(`[${l.iso}] Added ${r.missing.length} missing keys from base.`)
  }
  console.log()
  process.exit(0)
}

if (showMatrix) {
  console.log(`## Missing Keys Matrix (${allLocales.map(l => l.iso).join(', ')})`)
  console.log()
  const matrix: Array<{ key: string, missingIn: string[] }> = []
  for (const key of base.keys()) {
    const missingIn = allLocales.filter(l => reports.get(l.iso)!.missing.includes(key)).map(l => l.iso)
    if (missingIn.length > 0) {
      matrix.push({ key, missingIn })
    }
  }
  if (matrix.length === 0) {
    console.log('All locales have 100% keys translated!')
  } else {
    console.log(`| String Key | Missing in Locales (${matrix.length} keys) |`)
    console.log(`|------------|---------------------------------------------|`)
    for (const item of matrix) {
      console.log(`| \`${item.key}\` | ${item.missingIn.join(', ')} |`)
    }
  }
  console.log()
  process.exit(0)
}

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
  if (!onlyUntranslated && !onlyStale && !onlyExtra) {
    dump(`${l.iso} — missing (present in values/, absent in values-${l.iso}/)`, r.missing, k => base.get(k)!)
  }
  if (!onlyMissing && !onlyStale && !onlyExtra) {
    dump(`${l.iso} — untranslated copy (value equals base English text)`, r.untranslated, k => base.get(k)!)
  }
  if (!onlyMissing && !onlyUntranslated && !onlyStale && !onlyExtra && onlyIso) {
    dump(`${l.iso} — identical everywhere (brand terms, usually fine)`, r.shared, k => base.get(k)!)
  }
  if (checkStale && (!onlyMissing && !onlyUntranslated && !onlyExtra)) {
    if (r.stale.length === 0) {
      console.log(`## ${l.iso} — stale translations — (none)`)
      console.log()
    } else {
      console.log(`## ${l.iso} — stale translations (${r.stale.length}) — base line touched after translation, may need re-check`)
      console.log()
      for (const key of r.stale) {
        console.log(`<!-- ${key} -->`)
        console.log(`[en] <string name="${key}">${base.get(key)}</string>`)
        console.log(`[${l.iso}] <string name="${key}">${l.strings.get(key)}</string>`)
        console.log()
      }
    }
  }
  if (!onlyMissing && !onlyUntranslated && !onlyStale) {
    dump(`${l.iso} — extra (present in values-${l.iso}/, absent in values/)`, r.extra, k => l.strings.get(k)!)
  }
}

