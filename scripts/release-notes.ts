import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { forkAuthorEmails } from './config'

/**
 * Generates bilingual (en + uk) release notes for a build.
 *
 * Writes `release-notes.json`:
 *   {
 *     "en":  "...full github markdown notes...",
 *     "uk":  "...повні нотатки для github...",
 *     "tg":  "...short bilingual telegram post (uk + en, plain prose lines)...",
 *     "tg_ci": "...CI channel list: linked short hash and subject per commit...",
 *     "site": { "en": SiteNotes, "uk": SiteNotes }   // structured card for the website
 *   }
 *
 * Config:
 *   GEMINI_API_KEY  - tried first; available models are fetched live, newest flash first.
 *   GEMINI_BASE_URL - optional; OpenAI-compatible chat/completions endpoint.
 *   PRE_RELEASE     - "true" on beta builds: no AI call at all, only the commit list for the CI channel.
 * Each model gets one attempt (429 waits out its minute on the same model), the whole AI stage is
 * capped at ~3 minutes, then the rule-based fallback. A post that misses the Telegram budget gets one
 * extra pass that rewrites only the two posts - without the commits, so it costs ~1k tokens.
 *   artifactDir     - argv[2], defaults to `out`.
 *
 * If no key is present — or the API call fails — a rule-based fallback is used.
 */

interface Commit { sha: string, author?: string, email?: string, message: string }
interface BuildInfo {
  verName: string
  verCode?: number
  repo: string
  commits: Commit[]
}

/** Structured release card for the website; layout is the site's job, not the model's. */
interface SiteNotes {
  headline: string
  highlights: { icon: string, title: string, text: string }[]
  fixes: string[]
}
interface SiteBundle { en: SiteNotes, uk: SiteNotes }

/** One entry from settings-registry.json (produced by dump-registry.ts). */
interface RegistryEntry { slug: string; label: string; labelUk?: string }

const artifactDir = resolve(process.argv[2] ?? 'out')
const infoPath = join(artifactDir, 'build-info.json')

const geminiUrl = (process.env.GEMINI_BASE_URL ?? 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions').replace(/\/+$/, '')

interface Candidate { url: string, key: string, model: string }

/** Text Gemini models the key can see, newest first; flash before lite (weakest at following constraints), pro last. */
async function geminiModels(key: string): Promise<string[]> {
  const res = await fetch(geminiUrl.replace(/\/chat\/completions$/, '/models'), {
    headers: { Authorization: `Bearer ${key}` },
    signal: AbortSignal.timeout(15000),
  })
  if (!res.ok) throw new Error(`gemini models -> ${res.status}`)
  const data = await res.json() as { data: { id: string }[] }
  const tier = { flash: 0, 'flash-lite': 1, pro: 2 } as Record<string, number>
  return data.data
    .map(m => m.id.replace(/^models\//, ''))
    .map(id => ({ id, m: id.match(/^gemini-(\d+(?:\.\d+)?)-(flash-lite|flash|pro)$/) }))
    .filter((x): x is { id: string, m: RegExpMatchArray } => x.m !== null)
    .sort((a, b) => Number(b.m[1]) - Number(a.m[1]) || tier[a.m[2]] - tier[b.m[2]])
    .map(x => x.id)
}

async function buildCandidates(): Promise<Candidate[]> {
  // GEMINI_API_KEYS may hold several keys (comma/space/newline separated); GEMINI_API_KEY is still honoured.
  const keys = [...new Set([...(process.env.GEMINI_API_KEYS ?? '').split(/[\s,]+/), process.env.GEMINI_API_KEY ?? ''].filter(Boolean))]
  const pin = (process.env.GEMINI_MODEL ?? '').trim()
  const perKey: Candidate[][] = []
  for (const key of keys) {
    try {
      const found = await geminiModels(key)
      // The live list is newest first, and the newest flashes are the flaky ones right now (45s timeouts,
      // 503 "high demand", truncated answers because they think). A pinned model is one we measured.
      const models = pin && found.includes(pin) ? [pin, ...found.filter(m => m !== pin)] : found
      console.log(`release-notes: gemini models (key ${perKey.length + 1}): ${models.slice(0, 6).join(', ')}${pin ? ` [pinned ${pin}]` : ''}`)
      perKey.push(models.slice(0, 6).map(model => ({ url: geminiUrl, key, model })))
    } catch (e) {
      console.warn(`release-notes: gemini model list failed for key ${perKey.length + 1}: ${e}`)
    }
  }
  // Interleave so one exhausted key never blocks the rest: best model on every key first, then the next model.
  const out: Candidate[] = []
  for (let i = 0; i < 6; i++) for (const list of perKey) if (list[i]) out.push(list[i])
  return out
}

/**
 * Repository bookkeeping: true statements about this repo that mean nothing to someone using the
 * app. Past releases shipped bullets like "updated FEATURES.md and established AGENTS.md for patch
 * naming conventions" and "reorganized internal patches for better maintainability" because such
 * lines sit in commit bodies right next to real fixes, and the model has no way to know one is
 * shippable and the other is not. Stripped from the input, and again from the output in case the
 * model paraphrases its way around the filter.
 */
const META = /(FEATURES\.md|AGENTS\.md|CLAUDE\.md|README|CHANGELOG|release[- ]notes|changelog generation|patches?\/|\bseries\b|\bstgit\b|stg (refresh|export|float)|lint-patches|entinychecker|\.github|\bworkflow\b|\bCI\b|maintainability|patch (structure|naming|reclassif)|reclassif|renamed? .* patch)/i

/** Drops bookkeeping bullets from a commit body, keeping the subject and the real changes. */
function stripMetaLines(message: string): string {
  const lines = message.split('\n')
  const subject = lines[0]
  const body = lines.slice(1).filter(l => !META.test(l))
  return [subject, ...body].join('\n').trimEnd()
}

function cleanCommits(commits: Commit[]): Commit[] {
  return commits
    .map(c => ({ ...c, message: stripMetaLines(c.message.trim()) }))
    .filter(c => {
      const msg = c.message.trim()
      // Filter out CI, infra, debug, chore, docs and internal maintenance noise
      if (/^(infra|ci|debug|chore|docs|test|build|style)(\([^)]+\))?:/i.test(msg)) return false
      if (/^(temp debug|debug logs|export stgit|update series)/i.test(msg)) return false
      // Only drop on a bookkeeping subject when nothing else survived. Real commits routinely
      // mix both - "fix burn/blocked-messages bugs, reclassify misplaced patches, add delete-my-
      // messages" is one subject naming two shippable fixes and one piece of bookkeeping, and
      // dropping it wholesale would lose the fixes.
      const lines = msg.split('\n')
      if (META.test(lines[0]) && !lines.slice(1).some(l => l.trim().startsWith('-'))) return false
      return true
    })
    .reverse()
}

const forkEmails = new Set(
  [...forkAuthorEmails, ...(process.env.ENTINY_AUTHOR_EMAILS ?? '').split(/[\s,]+/)]
    .map(e => e.toLowerCase())
    .filter(Boolean)
)

/** No email recorded means we cannot tell, so it stays ours - an unknown email really is inugram's. */
const isOurs = (c: Commit) => !c.email || forkEmails.has(c.email.toLowerCase())

const subject = (c: Commit) => c.message.split('\n')[0]

/** A sync commit already credits inugram for everything it carried in. */
const mentionsSync = (c: Commit) => /((sync with|merge) .*inugram|rebase (to|onto))/i.test(subject(c))

/** Upstream work gets one line, built here: a model listing cherry-picked inugram commits invents versions. */
function upstreamLine(theirs: Commit[]): { en: string, uk: string } | null {
  if (theirs.length === 0) return null
  const version = theirs.map(c => subject(c).match(/rebase to (\d+\.\d+\.\d+)/i)?.[1]).find(v => v)
  return version
    ? { en: `Updated to Telegram ${version} (via inugram)`, uk: `Оновлено до Telegram ${version} (через inugram)` }
    : { en: 'Synced with the latest inugram code', uk: 'Синхронізовано з останнім кодом inugram' }
}

const append = (text: string, bullet: string, line: string) =>
  text.trim() ? `${text.trimEnd()}\n${bullet} ${line}` : `${bullet} ${line}`

const REG_MAX = Number(process.env.RELEASE_NOTES_ROWS ?? 30)
const LINK_MAX = Number(process.env.RELEASE_NOTES_LINKS ?? 5)
const POST_CHARS = Number(process.env.RELEASE_NOTES_CHARS ?? 1000)
const POST_LINES = Number(process.env.RELEASE_NOTES_LINES ?? 12)

const norm = (s: string) => s.toLowerCase().replace(/[’‘`"]/g, '').replace(/\s+/g, ' ').trim()

// Only labels that are rare in the registry are good search keys: "show"/"hide" appear in a hundred
// entries, "clown" in one. Bigrams keep multi-word features from matching a single common word.
const GRAM_STOP = new Set('show hide use the and for with you your from this that are was will can get have has make makes more other about when without toggle option settings'.split(' '))
const grams = (s: string) => {
  const w = norm(s).split(' ').filter(x => x.length > 2 && !GRAM_STOP.has(x))
  return [...w, ...w.slice(0, -1).map((x, i) => `${x}_${w[i + 1]}`)]
}

function offerRegistry(registry: RegistryEntry[], commits: Commit[]): RegistryEntry[] {
  const docFreq = new Map<string, number>()
  for (const e of registry) for (const g of new Set(grams(e.label))) docFreq.set(g, (docFreq.get(g) ?? 0) + 1)
  const idf = (g: string) => Math.log((registry.length + 1) / ((docFreq.get(g) ?? 0) + 1))
  const hay = new Set(grams(commits.map(c => c.message).join('\n')))
  return registry
    .map(e => ({ e, score: [...new Set(grams(e.label))].reduce((s, g) => (hay.has(g) ? s + idf(g) : s), 0) }))
    .filter(x => x.score > 0 && x.e.labelUk)
    .sort((a, b) => b.score - a.score || a.e.slug.localeCompare(b.e.slug))
    .slice(0, REG_MAX)
    .map(x => x.e)
}

/** The model emits {{slug}}; the link text and the url are built here, so a bad slug is impossible. */
function expandAnchors(tg: string, entries: RegistryEntry[], field: 'label' | 'labelUk') {
  const bySlug = new Map(entries.map(e => [e.slug, e]))
  const slugs: string[] = []
  const text = tg.replace(/\{\{\s*([\w-]+)\s*\}\}/g, (_m, slug: string) => {
    const e = bySlug.get(slug)
    if (!e) return ''
    const label = (field === 'labelUk' ? e.labelUk : e.label) ?? e.label
    if (slugs.includes(slug) || slugs.length >= LINK_MAX) return label
    slugs.push(slug)
    return `[${label}](tg://entinySettings/${slug})`
  })
  return { text: text.replace(/[ \t]{2,}/g, ' ').replace(/\s+$/g, '').trim(), slugs }
}

/** Visible length: Telegram does not count the url inside a markdown link toward the caption budget. */
const visibleLength = (t: string) => t.replace(/\]\(tg:\/\/[^)]*\)/g, '](…)').length

function postProblems(name: string, text: string): string[] {
  const lines = text.split('\n').filter(l => l.trim())
  const problems: string[] = []
  if (visibleLength(text) > POST_CHARS) problems.push(`${name} is ${visibleLength(text)} characters, the limit is ${POST_CHARS}`)
  if (lines.length > POST_LINES) problems.push(`${name} has ${lines.length} lines, the limit is ${POST_LINES}`)
  if (lines.some(l => !/^\s*–\s/.test(l))) problems.push(`${name} has a line that does not start with "\u2013 "`)
  return problems
}

/** Runs only when a post misses the budget: no commits in the prompt, so a retry costs ~1k tokens. */
function fitPrompt(tgEn: string, tgUk: string, problems: string[]): string {
  return [
    'Tighten two entinyGram Telegram posts. Keep every fact, change nothing else.',
    ...problems.map(p => `- PROBLEM: ${p}.`),
    '',
    'Both posts: at most ' + POST_LINES + ' lines and ' + POST_CHARS + ' characters of visible text',
    '(the url inside a link does not count). Merge lines about the same area into one line that still',
    'says what the user gets; never drop a flagship feature. Every line starts with "\u2013 " (en dash and a space).',
    'Keep every {{slug}} token exactly as written, same token in the same line of both posts.',
    'Add no tokens, add no links, add no facts, no headers, no bold.',
    '',
    'TG_EN:',
    tgEn,
    '',
    'TG_UK:',
    tgUk,
    '',
    'Reply with JSON only: {"tg_en": "...", "tg_uk": "..."}',
  ].join('\n')
}

function buildPrompt(info: BuildInfo, commits: Commit[], offered: RegistryEntry[]): string {
  const list = commits.map(c => {
    // A "[*] ..." subject is already the finished bullet; the body only invites the model to embellish it.
    const msgLines = c.message.split('\n')
    const shown = /^\[[+*\-=]\]\s/.test(msgLines[0]) ? msgLines.slice(0, 1) : msgLines
    const indented = shown.map(l => `  ${l}`).join('\n')
    return `Commit ${c.sha.slice(0, 7)}:\n${indented}`
  }).join('\n\n')

  // Deliberately short. The previous version was a sixty-line rulebook that told the model both
  // "1 commit = 1 line" and "merge related commits" and "group minor fixes", then asked it to
  // judge which applied - so it mixed all three and drifted off the commits. One rule per idea,
  // no rule that contradicts another.
  const lines = [
    'You write release notes for entinyGram, a fork of Telegram for Android.',
    `Release v${info.verName}, repo ${info.repo}.`,
    '',
    'COMMITS (entinyGram\'s own work; anything merged from inugram arrives as a single sync commit):',
    list || '(no commits)',
    '',
    'Write ONLY what these commits say. If a detail is not in the commits, leave it out -',
    'no invented UI names, no guessed reasons, no marketing.',
    '',
    'Skip entirely: repository bookkeeping (documentation, patch files, the patch stack, CI,',
    'build scripts, refactors with no user-visible effect). A reader is a person using the app,',
    'not someone working on it.',
    '',
    'If a commit mentions "wip" (work in progress) anywhere, its bullet in every section and language',
    'must say the feature is untested and may be unstable and ask users to report bugs, written in',
    'the language of that section (e.g. "⚠️ WIP: untested, may be unstable - please report bugs").',
    '',
    'Merge commits that touch the same change into one bullet.',
    'An upstream sync (subject mentioning "sync with upstream inugram") is always exactly one',
    'bullet; never list its internal patches.',
    'A Telegram version bump is exactly one line: "Rebase to X.Y.Z (ported by entinyGram)" for our own',
    'rebase onto stock Telegram (a commit like "rebase to X.Y.Z"), or "Updated to Telegram X.Y.Z (via',
    'inugram)" for an inugram sync that brings the new base. In Ukrainian the tags are "(ported by',
    'entinyGram)" and "(через inugram)". Never credit inugram for a base update we ported ourselves.',
    'NEVER state a Telegram version number unless a commit subject spells it out as X.Y.Z; a bare build number',
    'like "7112" is not a version, and a commit that only says "rebase onto ..." gets no version line at all.',
    '',
    'A commit subject that starts with "[+] ", "[*] ", "[-] " or "[=] " states its category (new capability,',
    'fix or improvement, removal, upstream sync). Use it to decide where the line belongs; never print the marker.',
    'Stay close to the subjects. Do not expand a subject into details from the commit body unless the',
    'subject alone is meaningless; a short subject stays a short line.',
    '',
    'Sections in "en"/"uk" (structured changelog for GitHub & in-app updater):',
    '  "### Added:", "### Rewrited:", "### Fixed:", "### Deleted:".',
    '- "### Added:": new user-facing capabilities and features ("[+] " commits).',
    '- "### Rewrited:": redesigned, reworked, or changed existing features and upstream syncs ("[*] " changes/improvements or "[=] " syncs).',
    '- "### Fixed:": bug fixes ("[*] " fixes).',
    '- "### Deleted:": removed features, dead code, or legacy options ("[-] " commits).',
    'Omit any section that would be empty. Use "• " for bullets under each section.',
    '',
    'Telegram posts go in "tg_uk" (Ukrainian) and "tg_en" (English): the same content in each.',
    '- Plain prose lines, each starting with "– " (en dash and a space). NO "[+]", "[*]", "[-]" or "[=]" markers,',
    '  no headers, no language labels, no bold.',
    '- One line per feature or group of fixes, a short sentence saying what the user gets, in the style of:',
    '  "Wide posts in channels and the feed, plus reactions and a jump to the original post in the feed." /',
    '  "Широкі пости в каналах і стрічці, а в стрічці ще реакції та перехід до оригінального поста."',
    '- A flagship feature (a whole new screen or tab, a new settings section, anything a user would look for)',
    '  ALWAYS gets its own line, never folded into a grouped one. Smaller related additions may share one line.',
    '- Bug fixes are never listed one by one. Close with one line such as "Hundreds of fixes and optimizations:',
    '  ..." that names at most the 1-2 headline fixes a user would notice, then a natural phrase for the rest.',
    '- Removals: one plain line saying what went away and, if the commits say, why.',
    '- Upstream sync: only when a commit in COMMITS actually says so, one line. Never add it speculatively.',
    '- Each post is roughly 6 to 12 lines and MUST stay under 1000 characters (link URLs do not count).',
    '- Count the lines and characters before you answer; posts over the budget are thrown away.',
  ]

  // Deep links: the model only ever writes {{slug}} tokens it copied from this table, and the build
  // script turns each token into "[label](tg://entinySettings/slug)" per language. That keeps the
  // Ukrainian link text native, keeps both posts pointing at the same settings screen, and makes an
  // invented slug impossible. The table is the idf-scored top rows, not every label that shares a
  // word with a commit - the old filter sent 380 rows (~8k tokens) and the model picked wrong ones.
  const table = offered.map(e => `  ${e.slug} | ${e.label} | ${e.labelUk}`).join('\n')
  if (offered.length > 0) {
    lines.push(
      '',
      'SETTINGS DEEP LINKS:',
      'entinyGram has in-app deep links of the form tg://entinySettings/<slug>. In "en", "uk", "tg_en" and "tg_uk",',
      'when a line is exactly about one entry from the list below, put the token {{slug}} where its name',
      'belongs and say what the user gets without repeating that name again in the same line. The token',
      'stands for the name; the build script renders it as a link. Rules for tokens:',
      '- 2 to 5 tokens in the whole post; most lines need none',
      '- only when the entry IS that feature; a related or nearby setting is NOT a match',
      '- if no listed entry is the feature, write plain prose - never pick a slug by guesswork',
      '- use the SAME token in the corresponding line of both languages',
      '- never write markdown, brackets, a url or a tg:// link yourself',
      '',
      'Example shapes (placeholders, not real slugs):',
      '  \u2013 You can now search settings for paranoia, logs and {{slug-one}}.',
      '  \u2013 {{slug-two}} replaces the photo of users you blocked.',
      'Never write "Clown avatar for [Clown avatar for blocked users]" - the name appears once.',
      '',
      'SLUG | ENGLISH NAME | UKRAINIAN NAME:',
      table,
    )
  }

  lines.push(
    '',
    'Website card in "site_en" / "site_uk" (an object, same facts as the notes above, never more):',
    '{"headline": "one short sentence naming the main thing in this release",',
    ' "highlights": [{"icon": "one emoji", "title": "feature name, 1-3 words", "text": "one plain sentence"}],',
    ' "fixes": ["short fix, a few words"]}',
    '- 3 to 6 highlights, only new capabilities and the most noticeable improvements; fewer if the release is small.',
    '- "fixes" holds the remaining bug fixes, at most 8 short entries; [] when there are none.',
    '- No markdown, no links, no version numbers in any site field.',
    '',
    'Reply with JSON only - no code fences - with exactly these keys:',
    '{"en": "...", "uk": "...", "tg_uk": "...", "tg_en": "...", "site_en": {...}, "site_uk": {...}}',
    '"uk", "tg_uk" and "site_uk" are Ukrainian, "en", "tg_en" and "site_en" are English.',
  )

  return lines.join('\n')
}

async function callChat(c: Candidate, prompt: string, maxTokens = 3000, deadline = Infinity): Promise<string> {
  const { url, key, model } = c
  for (let attempt = 0; ; attempt++) {
    const res = await fetch(url, {
      signal: AbortSignal.timeout(45000),
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${key}`,
      },
      body: JSON.stringify({
        model,
        messages: [
          { role: 'system', content: 'You are a helpful assistant. Always reply with valid JSON only.' },
          { role: 'user', content: prompt },
        ],
        temperature: 0,
        max_tokens: maxTokens,
      }),
    })
    const text = await res.text()
    if (res.ok) {
      const data = JSON.parse(text) as { choices?: { message?: { content?: string } }[] }
      const out = data?.choices?.[0]?.message?.content
      if (!out) throw new Error(`${model}: empty response`)
      return out
    }
    // Free tier is 15 requests per minute per model and the body says how long to wait; that is a
    // per-minute window, so waiting it out beats burning the remaining candidates on the same prompt.
    const wait = res.status === 429 ? Number(/retry in ([\d.]+)/i.exec(text)?.[1] ?? '') : NaN
    if (!wait || attempt >= 2 || wait * 1000 > deadline - Date.now()) {
      throw new Error(`${model} -> ${res.status}: ${text.slice(0, 300)}`)
    }
    console.warn(`release-notes: ${model} is quota limited, retrying in ${Math.ceil(wait)}s`)
    await new Promise(r => setTimeout(r, Math.ceil(wait) * 1000))
  }
}

/**
 * Last line of defence: drops any bullet that still talks about the repository rather than the
 * app, and any section header left empty once those bullets are gone. Filtering the input is not
 * enough on its own - the model happily rephrases "moved patches into entiny/" as "refactored
 * internal structure for maintainability", which no input filter can catch.
 */
function dropMetaBullets(text: string): string {
  const kept: string[] = []
  for (const line of text.split('\n')) {
    const isBullet = /^\s*(-|–|•|\[[+*\-=]\])\s/.test(line)
    if (isBullet && META.test(line)) continue
    kept.push(line)
  }
  // Collapse headers that lost every bullet under them, plus the blank runs they leave behind.
  const out: string[] = []
  for (let i = 0; i < kept.length; i++) {
    const line = kept[i]
    if (/^#{1,6}\s/.test(line)) {
      const next = kept.slice(i + 1).find(l => l.trim() !== '')
      if (!next || /^#{1,6}\s/.test(next)) continue
    }
    out.push(line)
  }
  return out.join('\n').replace(/\n{3,}/g, '\n\n').trim()
}

const str = (v: unknown, max: number) => String(v ?? '').replace(/[*_`]|\[|\]\([^)]*\)/g, '').trim().slice(0, max)

function parseSite(v: unknown): SiteNotes {
  const o = (v && typeof v === 'object' ? v : {}) as Record<string, unknown>
  const highlights = (Array.isArray(o.highlights) ? o.highlights : []).slice(0, 6).map((h: any) => ({
    icon: str(h?.icon, 8),
    title: str(h?.title, 40),
    text: str(h?.text, 160),
  })).filter(h => h.title && h.text)
  const fixes = (Array.isArray(o.fixes) ? o.fixes : []).slice(0, 8).map(f => str(f, 80)).filter(Boolean)
  return { headline: str(o.headline, 120), highlights, fixes }
}

function combineTg(uk: string, en: string): string {
  return `🇺🇦 UK:\n${uk}\n\n🇬🇧 Eng:\n${en}`
}

/** CI channel post: one line per commit, short hash linked to the commit, marker stripped, no AI involved. */
function ciList(commits: Commit[], repo: string): string {
  // Syncs, merges, translation uploads and debug-only commits say nothing to someone reading the build list.
  const noise = /^(crowdin|docs:|export patches|merge |sync with|temporary debug|debug )/i
  const all = commits.flatMap((c) => {
    const first = c.message.split('\n')[0].trim()
    if (/^\[=\]/.test(first)) return []
    const subject = first.replace(/^\[[+*\-=]\]\s*/, '')
    if (noise.test(subject)) return []
    return [`[${c.sha.slice(0, 7)}](https://github.com/${repo}/commit/${c.sha}): ${subject}`]
  })
  if (all.length > 20) {
    return [...all.slice(0, 10), '...', ...all.slice(-10)].join('\n')
  }
  return all.join('\n')
}

const joinTg = (uk: string, en: string) => uk && en ? `🇺🇦 UK:\n${uk}\n\n🇺🇸 EN:\n${en}` : ''
const slugLinks = (t: string) => [...t.matchAll(/tg:\/\/entinySettings\/([\w-]+)/gi)].map(m => m[1]).sort().join(',')

function expandPlainLabels(text: string, entries: RegistryEntry[], field: 'label' | 'labelUk'): string {
  const bySlug = new Map(entries.map(e => [e.slug, e]))
  return text.replace(/\{\{\s*([\w-]+)\s*\}\}/g, (_m, slug: string) => {
    const e = bySlug.get(slug)
    if (!e) return ''
    return (field === 'labelUk' ? e.labelUk : e.label) ?? e.label
  }).replace(/[ \t]{2,}/g, ' ').trim()
}

function parseNotes(raw: string, offered: RegistryEntry[]) {
  const cleaned = raw.trim().replace(/^```(?:json)?/m, '').replace(/```$/m, '').trim()
  // Some models wrap the JSON in prose or reasoning; take the outermost object.
  const parsed = JSON.parse(cleaned.slice(cleaned.indexOf('{'), cleaned.lastIndexOf('}') + 1))
  const rawEn = String(parsed.en ?? '').trim()
  const rawUk = String(parsed.uk ?? '').trim()
  const anchored = {
    en: String(parsed.tg_en ?? '').trim(),
    uk: String(parsed.tg_uk ?? '').trim(),
  }
  const known = new Set(offered.map(e => e.slug))
  for (const t of [rawEn, rawUk, anchored.en, anchored.uk]) {
    for (const m of t.matchAll(/\{\{\s*([\w-]+)\s*\}\}/g)) {
      if (!known.has(m[1])) console.warn(`release-notes: unknown slug "${m[1]}" requested; token dropped`)
    }
  }
  const tgEn = dropMetaBullets(expandAnchors(anchored.en, offered, 'label').text)
  const tgUk = dropMetaBullets(expandAnchors(anchored.uk, offered, 'labelUk').text)
  const changelogEn = dropMetaBullets(expandAnchors(rawEn, offered, 'label').text)
  const changelogUa = dropMetaBullets(expandAnchors(rawUk, offered, 'labelUk').text)
  return {
    en: dropMetaBullets(expandPlainLabels(rawEn, offered, 'label')),
    uk: dropMetaBullets(expandPlainLabels(rawUk, offered, 'labelUk')),
    changelog_en: changelogEn,
    changelog_ua: changelogUa,
    tg_uk: tgUk,
    tg_en: tgEn,
    tg: joinTg(tgUk, tgEn) || String(parsed.tg ?? '').trim(),
    site: { en: parseSite(parsed.site_en), uk: parseSite(parsed.site_uk) },
    anchored,
  }
}

async function aiNotes(candidates: Candidate[], info: BuildInfo, commits: Commit[], registry: RegistryEntry[]) {
  const offered = offerRegistry(registry, commits)
  if (offered.length > 0) console.log(`release-notes: ${offered.length} of ${registry.length} registry entries offered for deep links`)
  const prompt = buildPrompt(info, commits, offered)
  let lastErr: unknown = new Error('no AI providers configured')
  const deadline = Date.now() + 300_000
  // A model that timed out or is overloaded is slow for every key, so it is skipped on the remaining keys;
  // a quota error (429) is a per-minute window and callChat waits it out on the same model instead of
  // paying the whole commit prompt again for the next candidate.
  const skipped = new Set<string>()
  for (const c of candidates) {
    if (Date.now() > deadline) break
    if (skipped.has(c.model)) continue
    try {
      console.log(`==> release-notes: calling ${c.model}`)
      const notes = parseNotes(await callChat(c, prompt, 3000, deadline), offered)
      if (!notes.en && !notes.uk && !notes.tg_uk) throw new Error('empty ai notes')
      // A weak model sometimes answers every language in English; reject it so the next model gets a turn.
      const cyrillic = (t: string) => (t.match(/[а-щьюяіїєґ]/gi) ?? []).length / Math.max(1, (t.match(/\p{L}/gu) ?? []).length)
      if (notes.tg_uk && cyrillic(notes.tg_uk) < 0.4) throw new Error('tg_uk is not Ukrainian')
      if (notes.uk && cyrillic(notes.uk) < 0.4) throw new Error('uk is not Ukrainian')
      const problems = [...postProblems('tg_en', notes.tg_en), ...postProblems('tg_uk', notes.tg_uk)]
      if (problems.length === 0) {
        console.log(`release-notes: links tg_en [${slugLinks(notes.tg_en)}] tg_uk [${slugLinks(notes.tg_uk)}], ${visibleLength(notes.tg_uk)} visible chars`)
        return notes
      }
      // Only the posts miss the budget, so re-run just them without the commits (~1k tokens instead of
      // the whole prompt). Any doubt at all - links moved, nothing shorter, call failed - keeps the draft.
      console.warn(`release-notes: over budget (${problems.join('; ')}); tightening the posts only`)
      try {
        const fit = parseNotes(await callChat(c, fitPrompt(notes.anchored.en, notes.anchored.uk, problems), 1500, deadline), offered)
        const stillBad = [...postProblems('tg_en', fit.tg_en), ...postProblems('tg_uk', fit.tg_uk)]
        const movedLinks = slugLinks(fit.tg_en) !== slugLinks(notes.tg_en) || slugLinks(fit.tg_uk) !== slugLinks(notes.tg_uk)
        const noBetter = visibleLength(fit.tg_uk) >= visibleLength(notes.tg_uk) && visibleLength(fit.tg_en) >= visibleLength(notes.tg_en)
        if (!fit.tg_en || !fit.tg_uk || movedLinks || (stillBad.length > 0 && noBetter)) {
          console.warn(`release-notes: keeping the draft (${movedLinks ? 'deep links moved' : stillBad.join('; ') || 'no shorter'})`)
        } else {
          notes.tg_en = fit.tg_en
          notes.tg_uk = fit.tg_uk
          notes.tg = joinTg(fit.tg_uk, fit.tg_en) || notes.tg
          console.log(`release-notes: tightened to ${visibleLength(fit.tg_en)}/${visibleLength(fit.tg_uk)} visible chars`)
        }
      } catch (e) {
        console.warn(`release-notes: tightening pass failed (${e}); keeping the draft`)
      }
      return notes
    } catch (e) {
      lastErr = e
      console.warn(`release-notes: ${c.model} failed: ${e}`)
      if (/timeout|timed out|aborted| 503 |UNAVAILABLE| 404 |NOT_FOUND/i.test(String(e))) skipped.add(c.model)
    }
  }
  throw lastErr
}

/** Strips a conventional-commit prefix so the fallback reads as a changelog, not as a git log. */
function subjectText(subject: string): string {
  return subject.replace(/^(\w+)(\([^)]*\))?!?:\s*/, '').trim()
}

function categorize(message: string): 'sync' | 'fix' | 'feature' | 'other' {
  const m = message.toLowerCase()
  if (m.includes('sync with upstream inugram') || m.startsWith('sync:')) return 'sync'
  // The conventional-commit prefix is the most reliable signal; the keyword sweep below only has
  // to cover subjects written without one.
  if (/^feat(\([^)]*\))?!?:/.test(m)) return 'feature'
  if (/^(fix|perf)(\([^)]*\))?!?:/.test(m)) return 'fix'
  if (/(fix|prevent|avoid|correct|bug|crash|regression|hang|improve|optimize|faster|perf)/.test(m)) return 'fix'
  if (/(add|allow|support|enable|new|option|config|toggle|feature|ability|introduce)/.test(m)) return 'feature'
  return 'other'
}

/** No AI: new features become highlights, everything else a fix line. */
function ruleSite(commits: Commit[]): SiteBundle {
  const highlights: SiteNotes['highlights'] = []
  const fixes: string[] = []
  for (const c of commits) {
    const subject = c.message.split('\n')[0].trim()
    const m = subject.match(/^\[([+*\-=])\]\s*(.*)$/)
    const text = m ? m[2] : subjectText(subject)
    if (m ? m[1] === '+' : categorize(subject) === 'feature') {
      if (highlights.length < 6) highlights.push({ icon: '✨', title: text.slice(0, 40), text })
    } else if (fixes.length < 8) fixes.push(text.slice(0, 80))
  }
  const card = { headline: '', highlights, fixes }
  return { en: card, uk: card }
}

function ruleFallback(commits: Commit[]): { en: string, uk: string, changelog_en: string, changelog_ua: string, tg_uk: string, tg_en: string, tg: string, site: SiteBundle } {
  if (commits.length === 0) return {
    en: 'No changes in this build.',
    uk: 'У цій збірці змін немає.',
    changelog_en: '### Fixed:\n• No changes in this build.',
    changelog_ua: '### Fixed:\n• У цій збірці змін немає.',
    tg_uk: '– Змін немає',
    tg_en: '– No changes',
    tg: combineTg('– Змін немає', '– No changes'),
    site: ruleSite([]),
  }

  const added: string[] = []
  const rewrited: string[] = []
  const fixed: string[] = []
  const deleted: string[] = []

  // Commits already carry a "[+] / [*] / [-] / [=]" marker: drop it and keep the wording instead of guessing.
  const lines = commits.map(c => {
    const subject = c.message.split('\n')[0].trim()
    const m = subject.match(/^\[([+*\-=])\]\s*(.*)$/)
    const marker = m ? m[1] : ''
    const text = m ? m[2] : subjectText(subject)
    const rebase = text.match(/^rebase to (\S+)/i)
    const formatted = rebase ? `Rebase to ${rebase[1]} (ported by entinyGram)` : text
    if (marker === '+') added.push(`• ${formatted}`)
    else if (marker === '-') deleted.push(`• ${formatted}`)
    else if (marker === '=' || /(rework|rewrite|redesign|move|restructure|update|sync|rebase)/i.test(formatted)) rewrited.push(`• ${formatted}`)
    else fixed.push(`• ${formatted}`)
    return `– ${formatted}`
  }).join('\n')

  const sections: string[] = []
  if (added.length) sections.push(`### Added:\n${added.join('\n')}`)
  if (rewrited.length) sections.push(`### Rewrited:\n${rewrited.join('\n')}`)
  if (fixed.length) sections.push(`### Fixed:\n${fixed.join('\n')}`)
  if (deleted.length) sections.push(`### Deleted:\n${deleted.join('\n')}`)
  const structured = sections.join('\n\n') || lines

  return {
    en: structured,
    uk: structured,
    changelog_en: structured,
    changelog_ua: structured,
    tg_uk: lines,
    tg_en: lines,
    tg: combineTg(lines, lines),
    site: ruleSite(commits),
  }
}

const info: BuildInfo = JSON.parse(await fs.readFile(infoPath, 'utf8'))
const commits = cleanCommits(info.commits ?? [])
const ours = commits.filter(isOurs)
const theirs = commits.filter(c => !isOurs(c))
if (theirs.length > 0) console.log(`release-notes: ${ours.length} ours / ${theirs.length} from inugram authors`)

// Load settings registry produced by dump-registry.ts (best-effort — absent in dev or if the
// dump step was skipped). When present, the registry gives the AI a list of known setting slugs
// so it can embed tg://entinySettings/<slug> deep links in the Telegram changelog lines.
let registry: RegistryEntry[] = []
try {
  registry = JSON.parse(await fs.readFile(join(artifactDir, 'settings-registry.json'), 'utf8'))
  console.log(`release-notes: loaded ${registry.length} registry entries`)
} catch {
  console.warn('release-notes: settings-registry.json not found; deep links disabled')
}

let notes: { en: string, uk: string, changelog_en: string, changelog_ua: string, tg_uk: string, tg_en: string, tg: string, tg_ci?: string, site: SiteBundle }
// On a pre-release nothing reads the AI text: the GitHub release step, the website publish and the
// main-channel post are all skipped, and the CI channel gets the commit list this script also writes.
const skipAi = process.env.PRE_RELEASE === 'true'
const candidates = ours.length > 0 && !skipAi ? await buildCandidates() : []
if (candidates.length > 0) {
  try {
    notes = await aiNotes(candidates, info, ours, registry)
  } catch (e) {
    console.warn(`release-notes: AI failed (${e}); using rule-based fallback`)
    notes = ruleFallback(ours)
  }
} else {
  if (ours.length > 0) console.warn(skipAi ? 'release-notes: pre-release build; AI skipped, commit list only' : 'release-notes: GEMINI_API_KEY not set; using rule-based fallback')
  notes = ruleFallback(ours)
}

// Upstream commits are deliberately kept away from the model, so their credit line is written here.
const sync = ours.some(mentionsSync) ? null : upstreamLine(theirs)
if (sync) {
  notes.en = append(notes.en, '•', sync.en)
  notes.uk = append(notes.uk, '•', sync.uk)
  notes.changelog_en = append(notes.changelog_en, '•', sync.en)
  notes.changelog_ua = append(notes.changelog_ua, '•', sync.uk)
  notes.tg_en = append(notes.tg_en, '–', sync.en)
  notes.tg_uk = append(notes.tg_uk, '–', sync.uk)
  console.log(`release-notes: upstream line -> ${sync.en}`)
}

notes.tg_ci = ciList(commits, info.repo)

if (!skipAi) {
  try {
    const assetsChangelogPath = resolve('assets/changelog.json')
    let existing: Record<string, { ua: string, en: string }> = {}
    try {
      existing = JSON.parse(await fs.readFile(assetsChangelogPath, 'utf8'))
    } catch { }
    const key = info.verCode ? `entinyGram ${info.verName} (${info.verCode})` : `entinyGram ${info.verName}`
    const updated: Record<string, { ua: string, en: string }> = {
      [key]: {
        ua: notes.changelog_ua || notes.uk,
        en: notes.changelog_en || notes.en,
      },
    }
    for (const [k, v] of Object.entries(existing)) {
      if (k !== key) updated[k] = v
    }
    const jsonText = JSON.stringify(updated, null, 2) + '\n'
    await fs.mkdir(resolve('assets'), { recursive: true })
    await fs.writeFile(assetsChangelogPath, jsonText, 'utf8')
    await fs.mkdir(artifactDir, { recursive: true })
    await fs.writeFile(join(artifactDir, 'changelog.json'), jsonText, 'utf8')
  } catch (e) {
    console.warn(`release-notes: failed to update assets/changelog.json: ${e}`)
  }
}

await fs.mkdir(artifactDir, { recursive: true })
await fs.writeFile(join(artifactDir, 'release-notes.json'), JSON.stringify(notes, null, 2))
console.log(JSON.stringify(notes, null, 2))
