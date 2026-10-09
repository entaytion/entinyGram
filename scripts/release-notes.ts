import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'

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
 * Each model gets one attempt, the whole AI stage is capped at ~3 minutes, then the rule-based fallback.
 *   artifactDir     - argv[2], defaults to `out`.
 *
 * If no key is present — or the API call fails — a rule-based fallback is used.
 */

interface Commit { sha: string, author?: string, message: string }
interface BuildInfo {
  verName: string
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
interface RegistryEntry { slug: string; label: string }

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
  const perKey: Candidate[][] = []
  for (const key of keys) {
    try {
      const models = await geminiModels(key)
      console.log(`release-notes: gemini models (key ${perKey.length + 1}): ${models.join(', ')}`)
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

function buildPrompt(info: BuildInfo, commits: Commit[], registry: RegistryEntry[]): string {
  const list = commits.map(c => {
    // A "[*] ..." subject is already the finished bullet; the body only invites the model to embellish it.
    const msgLines = c.message.split('\n')
    const shown = /^\[[+*\-=]\]\s/.test(msgLines[0]) ? msgLines.slice(0, 1) : msgLines
    const indented = shown.map(l => `  ${l}`).join('\n')
    const authorTag = c.author ? ` (Author: ${c.author})` : ''
    return `Commit ${c.sha.slice(0, 7)}${authorTag}:\n${indented}`
  }).join('\n\n')

  // Deliberately short. The previous version was a sixty-line rulebook that told the model both
  // "1 commit = 1 line" and "merge related commits" and "group minor fixes", then asked it to
  // judge which applied - so it mixed all three and drifted off the commits. One rule per idea,
  // no rule that contradicts another.
  const lines = [
    'You write release notes for entinyGram, a fork of Telegram for Android.',
    `Release v${info.verName}, repo ${info.repo}.`,
    '',
    'COMMITS:',
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
    'inugram)" for an inugram sync that brings the new base. The "(ported by entinyGram)" / "(via inugram)"',
    'tag stays in English in every language. Never credit inugram for a base update we ported ourselves.',
    'NEVER state a Telegram version number unless a commit subject spells it out as X.Y.Z; a bare build number',
    'like "7112" is not a version, and a commit that only says "rebase onto ..." gets no version line at all.',
    '',
    'A commit subject that starts with "[+] ", "[*] ", "[-] " or "[=] " states its category (new capability,',
    'fix or improvement, removal, upstream sync). Use it to decide where the line belongs; never print the marker.',
    'Stay close to the subjects. Do not expand a subject into details from the commit body unless the',
    'subject alone is meaningless; a short subject stays a short line.',
    '',
    'Sections in "en"/"uk" (full GitHub release notes): "### New Features", "### Bug Fixes", "### Improvements & Polish".',
    'Anything the user could not do before goes under New Features, not Improvements.',
    'Omit a section that would be empty. Use "- " for bullets.',
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
  ]

  // ── Deep-link injection ─────────────────────────────────────────────────────
  // The AI already reads the commits and knows what changed. What it lacks is the
  // list of *valid* slugs — without it, any link it generates would be guessed and
  // potentially broken. So we give it the registry, but pre-filtered: only entries
  // whose label shares at least one meaningful keyword with the commit messages.
  // This typically shrinks the table from ~200 entries to <20, keeping the prompt
  // focused and the AI accurate.
  if (registry.length > 0) {
    // 1. Extract meaningful words from all commit messages (lower-case, 4+ chars,
    //    no stop-words, no conventional-commit prefixes).
    const STOP = new Set([
      'with', 'from', 'that', 'this', 'when', 'will', 'have', 'been', 'were',
      'into', 'more', 'some', 'also', 'only', 'after', 'before', 'their',
      'feat', 'feature', 'chore', 'sync', 'entiny', 'inugram', 'telegram',
      'upstream', 'patch', 'patches', 'build', 'release', 'update',
    ])
    const commitWords = new Set<string>()
    for (const c of commits) {
      for (const word of c.message.toLowerCase().split(/[\s\W]+/)) {
        if (word.length >= 4 && !STOP.has(word)) commitWords.add(word)
      }
    }

    // 2. Keep only registry entries whose label contains at least one commit keyword,
    //    and which have 2+ words (single-word labels like "Additional" are too generic).
    const relevant = registry.filter(e => {
      if (e.label.split(/\s+/).length < 2) return false
      const labelWords = e.label.toLowerCase().split(/\s+/)
      return labelWords.some(w => {
        // partial match: commit word starts with label word or vice versa (handles
        // "recording" matching "record", "fps" matching "fps", etc.)
        return commitWords.has(w) || [...commitWords].some(cw => cw.startsWith(w) || w.startsWith(cw))
      })
    })

    if (relevant.length > 0) {
      const table = relevant.map(e => `  ${e.slug} → "${e.label}"`).join('\n')
      lines.push(
        '',
        'SETTINGS DEEP LINKS (optional):',
        'entinyGram has in-app deep links of the form tg://entinySettings/<slug>.',
        'When a new-capability line in "tg_uk" or "tg_en" mentions a feature whose label closely matches one',
        'of the entries below, put the deep link on the feature name itself: "– [Wide posts](tg://entinySettings/<slug>) in',
        'channels and the feed, ...". Link the name phrase, never a verb. Use the same slug in every language.',
        'Linking is REQUIRED, not optional: every new-capability line in "tg_uk"/"tg_en" whose feature appears in the',
        'list below MUST carry its link, exactly like in "en"/"uk". A post without any tg:// link is wrong when matches exist.',
        'Never link inside fix lines or removals - links are only for new capabilities the reader might want to turn on.',
        'Plain text everywhere else, including "en"/"uk".',
        'Only link when the match is unambiguous. Never invent slugs not in this list.',
        '',
        'SLUG → LABEL:',
        table,
      )
    }
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

async function callChat(c: Candidate, prompt: string): Promise<string> {
  const { url, key, model } = c
  const res = await fetch(url, {
    signal: AbortSignal.timeout(60000),
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
      max_tokens: 16000,
    }),
  })
  if (!res.ok) {
    throw new Error(`${model} -> ${res.status}: ${(await res.text()).slice(0, 300)}`)
  }
  const data = await res.json() as { choices?: { message?: { content?: string } }[] }
  const text = data?.choices?.[0]?.message?.content
  if (!text) throw new Error(`${model}: empty response`)
  return text
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
    const isBullet = /^\s*(-|–|\[[+*\-=]\])\s/.test(line)
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
  return commits.map((c) => {
    const subject = c.message.split('\n')[0].trim().replace(/^\[[+*\-=]\]\s*/, '')
    return `[${c.sha.slice(0, 7)}](https://github.com/${repo}/commit/${c.sha}): ${subject}`
  }).join('\n')
}

function parseNotes(raw: string): { en: string, uk: string, tg_uk: string, tg_en: string, tg: string, site: SiteBundle } {
  const cleaned = raw.trim().replace(/^```(?:json)?/m, '').replace(/```$/m, '').trim()
  // Some models wrap the JSON in prose or reasoning; take the outermost object.
  const parsed = JSON.parse(cleaned.slice(cleaned.indexOf('{'), cleaned.lastIndexOf('}') + 1))
  const tgUk = dropMetaBullets(String(parsed.tg_uk ?? '').trim())
  const tgEn = dropMetaBullets(String(parsed.tg_en ?? '').trim())
  const tgCombined = tgUk && tgEn ? `🇺🇦 UK:\n${tgUk}\n\n🇺🇸 EN:\n${tgEn}` : String(parsed.tg ?? '').trim()
  return {
    en: dropMetaBullets(String(parsed.en ?? '').trim()),
    uk: dropMetaBullets(String(parsed.uk ?? '').trim()),
    tg_uk: tgUk,
    tg_en: tgEn,
    tg: tgCombined,
    site: { en: parseSite(parsed.site_en), uk: parseSite(parsed.site_uk) },
  }
}

async function aiNotes(candidates: Candidate[], info: BuildInfo, commits: Commit[], registry: RegistryEntry[]) {
  const prompt = buildPrompt(info, commits, registry)
  let lastErr: unknown = new Error('no AI providers configured')
  const deadline = Date.now() + 300_000
  for (const c of candidates) {
    if (Date.now() > deadline) break
    try {
      console.log(`==> release-notes: calling ${c.model}`)
      const notes = parseNotes(await callChat(c, prompt))
      if (!notes.en && !notes.uk && !notes.tg_uk) throw new Error('empty ai notes')
      // A weak model sometimes answers every language in English; reject it so the next model gets a turn.
      const cyrillic = (t: string) => (t.match(/[а-щьюяіїєґ]/gi) ?? []).length / Math.max(1, (t.match(/\p{L}/gu) ?? []).length)
      if (notes.tg_uk && cyrillic(notes.tg_uk) < 0.4) throw new Error('tg_uk is not Ukrainian')
      if (notes.uk && cyrillic(notes.uk) < 0.4) throw new Error('uk is not Ukrainian')
      return notes
    } catch (e) {
      lastErr = e
      console.warn(`release-notes: ${c.model} failed: ${e}`)
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

function ruleFallback(commits: Commit[]): { en: string, uk: string, tg_uk: string, tg_en: string, tg: string, site: SiteBundle } {
  if (commits.length === 0) return {
    en: 'No changes in this build.',
    uk: 'У цій збірці змін немає.',
    tg_uk: '– Змін немає',
    tg_en: '– No changes',
    tg: combineTg('– Змін немає', '– No changes'),
    site: ruleSite([]),
  }

  // Commits already carry a "[+] / [*] / [-] / [=]" marker: drop it and keep the wording instead of guessing.
  const lines = commits.map(c => {
    const subject = c.message.split('\n')[0].trim()
    const m = subject.match(/^\[([+*\-=])\]\s*(.*)$/)
    const text = m ? m[2] : subjectText(subject)
    const rebase = text.match(/^rebase to (\S+)/i)
    return rebase ? `– Rebase to ${rebase[1]} (ported by entinyGram)` : `– ${text}`
  }).join('\n')

  return { en: lines, uk: lines, tg_uk: lines, tg_en: lines, tg: combineTg(lines, lines), site: ruleSite(commits) }
}

const info: BuildInfo = JSON.parse(await fs.readFile(infoPath, 'utf8'))
const commits = cleanCommits(info.commits ?? [])

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

let notes: { en: string, uk: string, tg: string, tg_ci?: string, site: SiteBundle }
const candidates = commits.length > 0 ? await buildCandidates() : []
if (candidates.length > 0) {
  try {
    notes = await aiNotes(candidates, info, commits, registry)
  } catch (e) {
    console.warn(`release-notes: AI failed (${e}); using rule-based fallback`)
    notes = ruleFallback(commits)
  }
} else {
  if (commits.length > 0) console.warn('release-notes: GEMINI_API_KEY not set; using rule-based fallback')
  notes = ruleFallback(commits)
}

notes.tg_ci = ciList(commits, info.repo)

await fs.mkdir(artifactDir, { recursive: true })
await fs.writeFile(join(artifactDir, 'release-notes.json'), JSON.stringify(notes, null, 2))
console.log(JSON.stringify(notes, null, 2))
