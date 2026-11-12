import fs from 'node:fs/promises'
import { join } from 'node:path'
import { glob } from 'tinyglobby'
import { changelogsDir, rootDir, flat, GROUPS, parseChangelog, renderChangelog, stripLinks, type Lang, type Sections } from './lib'

/**
 * Turns the local draft `changelogs/unreleased.md` into `changelogs/<YYYYMMDD>.md` (UTC, the date version.ts stamps)
 * and resets the draft. A second release the same day appends. Does not touch git.
 *
 * Usage: bun run changelog:release
 */

const POST_CHARS = 3600
const draftPath = join(changelogsDir, 'unreleased.md')

const TEMPLATE = `<!-- One bullet per user-facing change in both languages. Link a setting in uk as [label](tg://entinySettings/<slug>); en stays plain. Optional ### new, ### fixed, ### removed split a language. Delete a bullet if the change is reverted. -->

## uk

## en
`

const draft = parseChangelog(await fs.readFile(draftPath, 'utf8').catch(() => ''))
const count = (s: Sections, l: Lang) => flat(s[l]).length
if (!count(draft, 'uk') && !count(draft, 'en')) {
  console.error('changelog: changelogs/unreleased.md has no bullets, nothing to release')
  process.exit(1)
}
if (count(draft, 'uk') !== count(draft, 'en')) {
  console.warn(`changelog: uk has ${count(draft, 'uk')} bullets and en has ${count(draft, 'en')}, they should match`)
}

const slugs = new Set<string>()
for (const f of await glob('src/fork/ui/settings/**/*.kt', { cwd: rootDir, absolute: true })) {
  for (const m of (await fs.readFile(f, 'utf8')).matchAll(/(?:slug\s*=\s*|Entry\(\s*)"([\w-]+)"/g)) slugs.add(m[1])
}
for (const m of flat(draft.uk).join('\n').matchAll(/tg:\/\/entinySettings\/([\w-]+)/g)) {
  if (!slugs.has(m[1])) console.warn(`changelog: unknown settings slug "${m[1]}"`)
}

const date = new Date().toISOString().slice(0, 10).replaceAll('-', '')
const target = join(changelogsDir, `${date}.md`)
const merged = parseChangelog(await fs.readFile(target, 'utf8').catch(() => ''))
for (const l of ['uk', 'en'] as Lang[]) {
  for (const g of GROUPS) merged[l][g].push(...draft[l][g].filter(b => !merged[l][g].includes(b)))
}

await fs.writeFile(target, renderChangelog(merged))
await fs.writeFile(draftPath, TEMPLATE)

const visible = stripLinks([...flat(merged.uk), ...flat(merged.en)].join('\n')).length
console.log(`changelog: wrote changelogs/${date}.md (${count(merged, 'uk')} uk, ${count(merged, 'en')} en bullets) and reset the draft`)
if (visible > POST_CHARS) console.warn(`changelog: bullets are ${visible} characters, over the ${POST_CHARS} a single post can hold`)
console.log(`next: git add changelogs/${date}.md, commit and push, then run the Build APK workflow`)
