import fs from 'node:fs/promises'
import { join } from 'node:path'
import { rootDir } from './config'

/**
 * Turns the local draft `changelogs/unreleased.md` into the release file `changelogs/<YYYYMMDD>.md`
 * (UTC date, the same day `ci/version.ts` stamps on the build) and resets the draft.
 * A second release on the same day appends its bullets to the existing file.
 *
 * Does not touch git: commit and push the new file yourself before running the build workflow.
 *
 *   bun run changelog:release [changelogs dir]
 */

const POST_CHARS = 3700
const dir = process.argv[2] ?? join(rootDir, 'changelogs')
const draftPath = join(dir, 'unreleased.md')

const TEMPLATE = `<!-- One bullet per user-facing change in both languages. Link a setting in uk as [label](tg://entinySettings/<slug>); en stays plain. Delete a bullet if the change is reverted. At release run \`bun run changelog:release\`. -->

## uk

## en
`

interface Sections { uk: string[], en: string[] }

function parse(raw: string): Sections {
  const out: Sections = { uk: [], en: [] }
  let section: keyof Sections | null = null
  for (const line of raw.replace(/<!--[\s\S]*?-->/g, '').split(/\r?\n/)) {
    const h = line.match(/^##\s+(uk|en)\s*$/i)
    if (h) {
      section = h[1].toLowerCase() as keyof Sections
      continue
    }
    const b = line.match(/^\s*[-*•–]\s+(\S.*)$/)
    if (section && b) out[section].push(b[1].trim())
  }
  return out
}

const render = (s: Sections) => `## uk\n${s.uk.map(b => `- ${b}`).join('\n')}\n\n## en\n${s.en.map(b => `- ${b}`).join('\n')}\n`
const visible = (s: Sections) => [...s.uk, ...s.en].join('\n').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').length

const draft = parse(await fs.readFile(draftPath, 'utf8').catch(() => ''))
if (!draft.uk.length && !draft.en.length) {
  console.error('changelog-release: changelogs/unreleased.md has no bullets, nothing to release')
  process.exit(1)
}
if (draft.uk.length !== draft.en.length) {
  console.warn(`changelog-release: uk has ${draft.uk.length} bullets and en has ${draft.en.length}, they should match`)
}

const date = new Date().toISOString().slice(0, 10).replaceAll('-', '')
const target = join(dir, `${date}.md`)
const existing = parse(await fs.readFile(target, 'utf8').catch(() => ''))
const merged: Sections = {
  uk: [...existing.uk, ...draft.uk.filter(b => !existing.uk.includes(b))],
  en: [...existing.en, ...draft.en.filter(b => !existing.en.includes(b))],
}

await fs.writeFile(target, render(merged))
await fs.writeFile(draftPath, TEMPLATE)

console.log(`changelog-release: wrote changelogs/${date}.md (${merged.uk.length} uk, ${merged.en.length} en bullets) and reset the draft`)
if (visible(merged) > POST_CHARS) console.warn(`changelog-release: bullets are ${visible(merged)} characters, over the ${POST_CHARS} a single post can hold`)
console.log(`next: git add changelogs/${date}.md && git commit && git push, then run the Build APK workflow`)
