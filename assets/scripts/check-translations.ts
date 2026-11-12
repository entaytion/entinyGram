import fs from 'node:fs/promises'
import { join } from 'node:path'
import { rootDir } from './config.js'

/**
 * Compares src/res/values/strings_inu.xml with every values-<iso>/strings_inu.xml and prints one line per problem.
 *   missing      key is in the base, not in the locale
 *   same as en   the value is a copy of the base text (skipped when every locale shares it, like brand names)
 *   extra        key is in the locale, not in the base
 *   placeholders %s / %1$d differ from the base
 *   broken       the file has unbalanced <string> tags
 *
 * Usage: bun run check-translations [iso ...]   exits 1 when anything is reported
 */

const resDir = join(rootDir, 'src/res')
const only = new Set(process.argv.slice(2))

const PLURAL = /_(zero|one|two|few|many|other)$/
const stem = (key: string) => key.replace(PLURAL, '')

interface Strings {
  values: Map<string, string>
  brand: Set<string>
  broken: boolean
}

async function parse(file: string): Promise<Strings> {
  const text = await fs.readFile(file, 'utf8')
  const values = new Map<string, string>()
  const brand = new Set<string>()
  for (const m of text.matchAll(/<string\s+name="([^"]+)"([^>]*)>([\s\S]*?)<\/string>/g)) {
    values.set(m[1], m[3])
    if (/translatable="false"/.test(m[2])) brand.add(m[1])
  }
  const opened = (text.match(/<string[\s>]/g) ?? []).length
  const closed = (text.match(/<\/string>/g) ?? []).length
  return { values, brand, broken: opened !== closed || !text.includes('</resources>') }
}

const placeholders = (v: string) => (v.match(/%(?:\d+\$)?[sdf]/g) ?? []).map(p => p.replace(/^%\d+\$/, '%')).sort().join(' ')

const base = await parse(join(resDir, 'values/strings_inu.xml'))
const locales = new Map<string, Strings>()
for (const e of await fs.readdir(resDir, { withFileTypes: true })) {
  const iso = e.name.match(/^values-(.+)$/)?.[1]
  if (!iso || !e.isDirectory() || (only.size && !only.has(iso))) continue
  try {
    locales.set(iso, await parse(join(resDir, e.name, 'strings_inu.xml')))
  } catch {}
}
if (!locales.size) {
  console.error('check-translations: no locales found')
  process.exit(1)
}

const isoList = [...locales.keys()].sort()
const keys = [...base.values.keys()].filter(k => !base.brand.has(k))
const baseStems = new Set(keys.map(stem))

// a value that no locale translates is a brand name or a code, not a missing translation
const sharedEverywhere = (key: string) => [...locales.values()].every(l => !l.values.has(key) || l.values.get(key) === base.values.get(key))

let problems = 0
for (const iso of isoList) {
  const l = locales.get(iso)!
  const lines: string[] = []
  if (l.broken) lines.push('broken       unbalanced <string> tags or no </resources>')

  const localeStems = new Set([...l.values.keys()].map(stem))
  for (const key of keys) {
    const value = l.values.get(key)
    if (value === undefined) {
      if (!(PLURAL.test(key) && localeStems.has(stem(key)))) lines.push(`missing      ${key}`)
      continue
    }
    const en = base.values.get(key)!
    if (value === en && !sharedEverywhere(key)) lines.push(`same as en   ${key}`)
    // entiny: plural forms other than `other` may spell the number out, so only `other` is compared
    const checkable = !PLURAL.test(key) || key.endsWith('_other')
    if (checkable && placeholders(value) !== placeholders(en)) lines.push(`placeholders ${key}: "${placeholders(en)}" in en, "${placeholders(value)}" here`)
  }
  for (const key of l.values.keys()) {
    if (!base.values.has(key) && !(PLURAL.test(key) && baseStems.has(stem(key)))) lines.push(`extra        ${key}`)
  }

  problems += lines.length
  console.log(`${iso}: ${lines.length ? `${lines.length} problem${lines.length === 1 ? '' : 's'}` : 'ok'}`)
  for (const line of lines) console.log(`  ${line}`)
}

console.log(`\n${isoList.length} locales, ${keys.length} keys, ${problems} problem${problems === 1 ? '' : 's'}`)
process.exit(problems ? 1 : 0)
