import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { changelogsDir, commitLines, emptySections, flat, GROUP_TITLE, GROUPS, parseChangelog, stripLinks, type Commit, type Lang, type Sections, type Track } from './lib'

/**
 * Builds `release-notes.json` for a build.
 *   beta    - the commit list since the previous build, English
 *   release - hand-written `changelogs/<buildDate>.md` (the draft when the dated file is missing),
 *             commit list only as a warned fallback
 *
 * Usage: bun assets/release/notes.ts [out dir]   (reads build-info.json, PRERELEASE=true for beta)
 */

interface BuildInfo { buildDate?: string, commits?: Commit[] }

const outDir = resolve(process.argv[2] ?? 'out')
const info: BuildInfo = JSON.parse(await fs.readFile(join(outDir, 'build-info.json'), 'utf8'))
const track: Track = process.env.PRERELEASE === 'true' ? 'beta' : 'release'
const lines = commitLines(info.commits ?? [])

async function readChangelog(): Promise<Sections | null> {
  for (const name of [info.buildDate, 'unreleased']) {
    if (!name) continue
    try {
      const found = parseChangelog(await fs.readFile(join(changelogsDir, `${name}.md`), 'utf8'))
      if (flat(found.uk).length || flat(found.en).length) {
        console.log(`notes: using changelogs/${name}.md`)
        return found
      }
    } catch {}
  }
  return null
}

/** Markdown for GitHub and the site: bullets, with group headings only when a language uses more than new. */
function markdown(s: Sections[Lang], lang: Lang, links: boolean): string {
  const used = GROUPS.filter(g => s[g].length)
  const text = (b: string) => (links ? b : stripLinks(b))
  if (used.length <= 1 && used[0] !== 'fixed' && used[0] !== 'removed') return s.new.map(b => `- ${text(b)}`).join('\n')
  return used.map(g => `**${GROUP_TITLE[lang][g]}**\n${s[g].map(b => `- ${text(b)}`).join('\n')}`).join('\n\n')
}

const card = (bullets: string[]) => ({ headline: '', highlights: [], fixes: bullets.slice(0, 8).map(b => stripLinks(b).replace(/[*_`]/g, '').slice(0, 80)) })

let sections = track === 'release' ? await readChangelog() : null
if (track === 'release' && !sections) {
  console.warn('notes: no changelog with bullets for this build, falling back to the commit list')
  sections = emptySections()
  sections.uk.new = lines
  sections.en.new = lines
}
sections ??= emptySections()
if (!flat(sections.uk).length) sections.uk = sections.en
if (!flat(sections.en).length) sections.en = sections.uk

const notes = {
  track,
  commits: lines,
  uk: sections.uk,
  en: sections.en,
  md: { uk: markdown(sections.uk, 'uk', false), en: markdown(sections.en, 'en', false) },
  site: { en: card(flat(sections.en)), uk: card(flat(sections.uk)) },
}

await fs.mkdir(outDir, { recursive: true })
await fs.writeFile(join(outDir, 'release-notes.json'), JSON.stringify(notes, null, 2))
console.log(JSON.stringify(notes, null, 2))
