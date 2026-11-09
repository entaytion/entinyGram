import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { forkAuthorEmails, rootDir } from './config'

/**
 * Builds the release notes for a build from the hand-written `changelogs/<buildDate>.md`
 * (`changelogs/unreleased.md` when the dated file is missing, which is the local draft).
 *
 * The file holds two sections, `## uk` and `## en`, each a bullet list. A bullet may link a setting as
 * `[label](tg://entinySettings/<slug>)`. The Ukrainian text keeps the links, English drops them and keeps
 * the label. No model is involved: what is written there is what ships.
 *
 * Writes `release-notes.json`:
 *   { en, uk, tg, tg_uk, tg_en, tg_ci, site: { en, uk } }
 *
 *   PRE_RELEASE   - "true" on beta builds: only the commit list for the CI channel is produced.
 *   artifactDir   - argv[2], defaults to `out`.
 *
 * With no file or no bullets on a stable build the notes fall back to the commit subjects, with a warning.
 */

interface Commit { sha: string, author?: string, email?: string, message: string }
interface BuildInfo {
  verName: string
  verCode?: number
  buildDate?: string
  repo: string
  commits: Commit[]
}

interface SiteNotes {
  headline: string
  highlights: { icon: string, title: string, text: string }[]
  fixes: string[]
}

const artifactDir = resolve(process.argv[2] ?? 'out')
const info: BuildInfo = JSON.parse(await fs.readFile(join(artifactDir, 'build-info.json'), 'utf8'))

/** Header, site link, donate line and tags take about 400 of the 4096 characters a Telegram text message may hold. */
const POST_CHARS = Number(process.env.RELEASE_NOTES_CHARS ?? 3600)

const META = /FEATURES\.md|AGENTS\.md|CLAUDE\.md|README|CHANGELOG|release[- ]notes|patches?\/|\bseries\b|\bstgit\b|stg (?:refresh|export|float)|lint-patches|entinychecker|\.github|\bworkflow\b|\bCI\b|maintainability/i

const forkEmails = new Set(
  [...forkAuthorEmails, ...(process.env.ENTINY_AUTHOR_EMAILS ?? '').split(/[\s,]+/)].map(e => e.toLowerCase()).filter(Boolean),
)

/** No email recorded means we cannot tell, so it stays ours - an unknown email really is inugram's. */
const isOurs = (c: Commit) => !c.email || forkEmails.has(c.email.toLowerCase())

const subjectOf = (c: Commit) => c.message.split('\n')[0].replace(/^\[[+*\-=]\]\s*/, '').replace(/^(\w+)(\([^)]*\))?!?:\s*/, '').trim()

/** Commits that say nothing to a reader: bookkeeping, syncs, translation uploads, debug-only. */
function cleanCommits(commits: Commit[]): Commit[] {
  return commits
    .filter((c) => {
      const first = c.message.split('\n')[0].trim()
      if (/^(?:infra|ci|debug|chore|docs|test|build|style)(?:\([^)]+\))?:/i.test(first)) return false
      if (/^(?:temp debug|debug logs|export stgit|update series)/i.test(first)) return false
      return !(META.test(first) && !c.message.split('\n').slice(1).some(l => l.trim().startsWith('-')))
    })
    .reverse()
}

/** CI channel post: one line per commit, short hash linked to the commit, marker stripped. */
function ciList(commits: Commit[], repo: string): string {
  const noise = /^(?:crowdin|docs:|export patches|merge |sync with|temporary debug|debug )/i
  const all = commits.flatMap((c) => {
    const first = c.message.split('\n')[0].trim()
    if (/^\[=\]/.test(first)) return []
    const subject = first.replace(/^\[[+*\-=]\]\s*/, '')
    return noise.test(subject) ? [] : [`[${c.sha.slice(0, 7)}](https://github.com/${repo}/commit/${c.sha}): ${subject}`]
  })
  return (all.length > 20 ? [...all.slice(0, 10), '...', ...all.slice(-10)] : all).join('\n')
}

/** Reads `## uk` / `## en` bullet lists; comments and anything that is not a bullet are ignored. */
function parseChangelog(raw: string): { uk: string[], en: string[] } {
  const out: { uk: string[], en: string[] } = { uk: [], en: [] }
  let section: 'uk' | 'en' | null = null
  for (const line of raw.replace(/<!--[\s\S]*?-->/g, '').split(/\r?\n/)) {
    const h = line.match(/^##\s+(uk|en)\s*$/i)
    if (h) {
      section = h[1].toLowerCase() as 'uk' | 'en'
      continue
    }
    const b = line.match(/^\s*[-*•–]\s+(\S.*)$/)
    if (section && b) out[section].push(b[1].trim())
  }
  return out
}

const stripLinks = (t: string) => t.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
const list = (items: string[], bullet: string) => items.map(i => `${bullet} ${i}`).join('\n')
const card = (bullets: string[]): SiteNotes => ({ headline: '', highlights: [], fixes: bullets.slice(0, 8).map(b => stripLinks(b).replace(/[*_`]/g, '').slice(0, 80)) })

async function readChangelog() {
  for (const name of [info.buildDate, 'unreleased']) {
    if (!name) continue
    try {
      const found = parseChangelog(await fs.readFile(join(rootDir, 'changelogs', `${name}.md`), 'utf8'))
      if (found.uk.length || found.en.length) {
        console.log(`release-notes: using changelogs/${name}.md`)
        return found
      }
    } catch {}
  }
  return null
}

/** Unknown settings slugs are reported, not fatal; the registry is produced by dump-registry.ts. */
async function warnUnknownSlugs(bullets: string[]) {
  try {
    const registry: { slug: string }[] = JSON.parse(await fs.readFile(join(artifactDir, 'settings-registry.json'), 'utf8'))
    const known = new Set(registry.map(e => e.slug))
    for (const m of bullets.join('\n').matchAll(/tg:\/\/entinySettings\/([\w-]+)/g)) {
      if (!known.has(m[1])) console.warn(`release-notes: unknown settings slug "${m[1]}"`)
    }
  } catch {}
}

const commits = cleanCommits(info.commits ?? [])
const preRelease = process.env.PRE_RELEASE === 'true'

let written = await readChangelog()
if (!written && !preRelease) {
  console.warn('release-notes: no changelog with bullets for this build, falling back to the commit subjects')
  const subjects = commits.filter(isOurs).map(subjectOf).filter(Boolean)
  written = { uk: subjects, en: subjects }
}
written ??= { uk: [], en: [] }

const uk = written.uk.length ? written.uk : written.en
const en = written.en.length ? written.en : written.uk
await warnUnknownSlugs(uk)

const tgUk = list(uk, '–')
const tgEn = stripLinks(list(en, '–'))
const visible = stripLinks(tgUk).length + stripLinks(tgEn).length
if (visible > POST_CHARS) console.warn(`release-notes: uk and en bullets are ${visible} characters, over the ${POST_CHARS} a single post can hold`)

const notes = {
  en: stripLinks(list(en, '•')),
  uk: stripLinks(list(uk, '•')),
  tg: tgUk && tgEn ? `🇺🇦 UK:\n${tgUk}\n\n🇺🇸 EN:\n${tgEn}` : '',
  tg_uk: tgUk,
  tg_en: tgEn,
  tg_ci: ciList(commits, info.repo),
  site: { en: card(en), uk: card(uk) },
}

await fs.mkdir(artifactDir, { recursive: true })
await fs.writeFile(join(artifactDir, 'release-notes.json'), JSON.stringify(notes, null, 2))
console.log(JSON.stringify(notes, null, 2))
