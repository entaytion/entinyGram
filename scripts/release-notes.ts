import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'

/**
 * Generates bilingual (en + uk) release notes for a build.
 *
 * Writes `release-notes.json`:
 *   {
 *     "en":  "...full github markdown notes...",
 *     "uk":  "...повні нотатки для github...",
 *     "tg":  "...short bilingual telegram post (en + uk, [+]/[*]/[-]/[=] style)..."
 *   }
 *
 * Config:
 *   GEMINI_API_KEY  - required for AI mode.
 *   GEMINI_MODEL    - optional, defaults to `gemini-3.1-flash-lite`.
 *   GEMINI_BASE_URL - optional; OpenAI-compatible chat/completions endpoint.
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

const artifactDir = resolve(process.argv[2] ?? 'out')
const infoPath = join(artifactDir, 'build-info.json')

const baseUrl = (process.env.GEMINI_BASE_URL ?? 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions').replace(/\/+$/, '')

// Strongest first. This is an extraction task with hard constraints, and the -lite tier is the
// weakest at following them - it was the one inventing "accordion editor"-style detail and
// reshuffling features into the wrong section. It stays only as a fallback if the better model is
// unavailable on the key.
const MODELS = [
  process.env.GEMINI_MODEL,
  'gemini-3.5-flash',
  'gemini-3.1-flash-lite',
].filter((m): m is string => Boolean(m))

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

function buildPrompt(info: BuildInfo, commits: Commit[]): string {
  const list = commits.map(c => {
    const indented = c.message.split('\n').map(l => `  ${l}`).join('\n')
    const authorTag = c.author ? ` (Author: ${c.author})` : ''
    return `Commit ${c.sha.slice(0, 7)}${authorTag}:\n${indented}`
  }).join('\n\n')

  // Deliberately short. The previous version was a sixty-line rulebook that told the model both
  // "1 commit = 1 line" and "merge related commits" and "group minor fixes", then asked it to
  // judge which applied - so it mixed all three and drifted off the commits. One rule per idea,
  // no rule that contradicts another.
  return [
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
    'One bullet per user-visible change. Merge commits that touch the same change into one',
    'bullet. Group trivial tweaks as a single "Minor fixes and polish" bullet.',
    'An upstream sync (subject mentioning "sync with upstream inugram") is always exactly one',
    'bullet; never list its internal patches.',
    '',
    'Sections in "en"/"uk": "### New Features", "### Bug Fixes", "### Improvements & Polish".',
    'Anything the user could not do before goes under New Features, not Improvements.',
    'Omit a section that would be empty. Use "- " for bullets.',
    '',
    'Telegram lines in "tg_en"/"tg_uk": one line per bullet, prefixed',
    '"[+] " new capability, "[*] " fix or refinement, "[-] " removal, "[=] " upstream sync.',
    'No language headers inside them.',
    '',
    'Reply with JSON only - no code fences - with exactly these keys:',
    '{"en": "...", "uk": "...", "tg_uk": "...", "tg_en": "..."}',
    '"uk" and "tg_uk" are Ukrainian, "en" and "tg_en" are English.',
  ].join('\n')
}

async function callGemini(key: string, model: string, prompt: string): Promise<string> {
  const res = await fetch(baseUrl, {
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
    const isBullet = /^\s*(-|\[[+*\-=]\])\s/.test(line)
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

function parseNotes(raw: string): { en: string, uk: string, tg_uk: string, tg_en: string, tg: string } {
  const cleaned = raw.trim().replace(/^```(?:json)?/m, '').replace(/```$/m, '').trim()
  const parsed = JSON.parse(cleaned)
  const tgUk = dropMetaBullets(String(parsed.tg_uk ?? '').trim())
  const tgEn = dropMetaBullets(String(parsed.tg_en ?? '').trim())
  const tgCombined = tgUk && tgEn ? `🇺🇦 UK:\n${tgUk}\n\n🇺🇸 EN:\n${tgEn}` : String(parsed.tg ?? '').trim()
  return {
    en: dropMetaBullets(String(parsed.en ?? '').trim()),
    uk: dropMetaBullets(String(parsed.uk ?? '').trim()),
    tg_uk: tgUk,
    tg_en: tgEn,
    tg: tgCombined,
  }
}

async function aiNotes(key: string, info: BuildInfo, commits: Commit[]) {
  const prompt = buildPrompt(info, commits)
  let lastErr: unknown
  for (const model of MODELS) {
    try {
      console.log(`==> release-notes: calling ${model}`)
      const raw = await callGemini(key, model, prompt)
      const notes = parseNotes(raw)
      if (!notes.en && !notes.uk && !notes.tg_uk) throw new Error('empty ai notes')
      return notes
    } catch (e) {
      lastErr = e
      console.warn(`release-notes: ${model} failed: ${e}`)
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

function ruleFallback(commits: Commit[]): { en: string, uk: string, tg_uk: string, tg_en: string, tg: string } {
  if (commits.length === 0) return {
    en: 'No changes in this build.',
    uk: 'У цій збірці змін немає.',
    tg_uk: '[=] Змін немає',
    tg_en: '[=] No changes',
    tg: '🇺🇦 UK:\n[=] Змін немає\n\n🇺🇸 EN:\n[=] No changes',
  }

  const sections: Record<string, string[]> = { sync: [], feature: [], fix: [], other: [] }
  for (const c of commits) {
    const subject = c.message.split('\n')[0].trim()
    sections[categorize(subject)].push(subjectText(subject))
  }

  const en: string[] = []
  const uk: string[] = []
  if (sections.sync.length) en.push(`[=] Synced with upstream inugram`)
  if (sections.feature.length) en.push(...sections.feature.map(l => `[+] ${l}`))
  if (sections.fix.length) en.push(`[*] Bug fixes and UI optimizations`)
  if (sections.other.length) en.push(...sections.other.map(l => `[=] ${l}`))

  if (sections.sync.length) uk.push(`[=] Синхронізація з upstream inugram`)
  if (sections.feature.length) uk.push(...sections.feature.map(l => `[+] ${l}`))
  if (sections.fix.length) uk.push(`[*] Виправлено баги та оптимізовано інтерфейс`)
  if (sections.other.length) uk.push(...sections.other.map(l => `[=] ${l}`))

  const tgUk = uk.slice(0, 12).join('\n')
  const tgEn = en.slice(0, 12).join('\n')

  return {
    en: en.join('\n'),
    uk: uk.join('\n'),
    tg_uk: tgUk,
    tg_en: tgEn,
    tg: `🇺🇦 UK:\n${tgUk}\n\n🇺🇸 EN:\n${tgEn}`,
  }
}

const info: BuildInfo = JSON.parse(await fs.readFile(infoPath, 'utf8'))
const commits = cleanCommits(info.commits ?? [])

let notes: { en: string, uk: string, tg: string }
const key = process.env.GEMINI_API_KEY
if (key && commits.length > 0) {
  try {
    notes = await aiNotes(key, info, commits)
  } catch (e) {
    console.warn(`release-notes: AI failed (${e}); using rule-based fallback`)
    notes = ruleFallback(commits)
  }
} else {
  if (!key) console.warn('release-notes: GEMINI_API_KEY not set; using rule-based fallback')
  notes = ruleFallback(commits)
}

await fs.mkdir(artifactDir, { recursive: true })
await fs.writeFile(join(artifactDir, 'release-notes.json'), JSON.stringify(notes, null, 2))
console.log(JSON.stringify(notes, null, 2))
