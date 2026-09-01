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

const MODELS = [
  process.env.GEMINI_MODEL,
  'gemini-3.1-flash-lite',
  'gemini-2.0-flash',
].filter((m): m is string => Boolean(m))

function cleanCommits(commits: Commit[]): Commit[] {
  return commits
    .filter(c => {
      const msg = c.message.trim()
      // Filter out CI, infra, debug, chore, and internal maintenance noise
      if (/^(infra|ci|debug|chore)(\([^)]+\))?:/i.test(msg)) return false
      if (/^(temp debug|debug logs|export stgit|update series)/i.test(msg)) return false
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

  return [
    'You are writing release notes for entinyGram, a customized modern fork of Telegram for Android.',
    '',
    `Release: v${info.verName} (repo ${info.repo})`,
    '',
    'Commits since the last release (technical subjects + detailed bullet points):',
    list || '(no commits)',
    '',
    '=== OUTPUT FORMAT ===',
    'Return ONLY valid JSON with exactly 4 keys: "en", "uk", "tg_uk", "tg_en". No markdown code blocks, fences, or backticks.',
    '{',
    '  "en": "...GitHub release notes in English (Markdown)...",',
    '  "uk": "...нотатки для GitHub українською (Markdown)...",',
    '  "tg_uk": "...short Ukrainian Telegram changelog lines...",',
    '  "tg_en": "...short English Telegram changelog lines..."',
    '}',
    '',
    '=== CORE PRINCIPLES (STRICT ACCURACY & PROPORTIONALITY) ===',
    '1. STRICT FACTUAL GROUNDING: Describe ONLY what is explicitly stated in the commits above. NEVER hallucinate, invent, assume, or fabricate features, bug fixes, UI changes, or optimizations not present in the commits.',
    '2. PROPORTIONALITY & 1-TO-1 ESSENCE:',
    '   - 1 distinct change / commit -> exactly 1 concise bullet point.',
    '   - If several commits are just micro-edits/typos for the same single feature, merge them into 1 point for that feature.',
    '   - ZERO fluff, zero marketing buzzwords, zero fake explanations.',
    '3. NO EMPTY/FAKE SECTIONS: In GitHub notes ("en"/"uk"), only include section headers (### New Features, ### Bug Fixes, ### Improvements & Polish) if there are actual commits for them. Never invent entries just to fill a section.',
    '4. UPSTREAM SYNC & FORK OWNERSHIP:',
    '   - If a commit is an upstream sync (e.g. subject is "sync with upstream inugram" or represents an upstream merge):',
    '     Summarize the sync as ONE consolidated bullet point (e.g. "[=] Synced with upstream inugram (latest base updates and fixes)" / "[=] Синхронізація з upstream inugram (оновлення бази та виправлення Telegram)").',
    '     Do NOT explode the entire list of upstream internal patches into separate main features.',
    '   - For entinyGram-specific commits (features, bugfixes, refactors, UI additions):',
    '     Highlight each distinct entinyGram change with its own dedicated bullet point as usual.',
    '5. MINOR FIXES & POLISH GROUPING:',
    '   - Do NOT blow up tiny minor fixes (typos, string tweaks, internal variable adjustments, micro-polishing) into full separate bullet points or bloated paragraphs.',
    '   - If there are minor technical adjustments, group them together concisely (e.g. "[*] Minor bug fixes and UI polish" / "[*] Дрібні виправлення та покращення інтерфейсу") rather than listing each trivial tweak separately.',
    '   - If a release consists of only ONE fix (e.g. reverting a single patch), the entire changelog must be ONE concise bullet point describing that single fix. Do NOT invent multiple sections.',
    '',
    '=== "en" and "uk" keys (GitHub release notes) ===',
    '- Clean, straight-to-the-point Markdown.',
    '- Use `- ` for list items.',
    '- Clear, technical and user-friendly explanation of what actually changed.',
    '',
    '=== "tg_uk" and "tg_en" keys (Telegram post items) ===',
    'Prefixes:',
    '- "[+] " for new features and user-facing capabilities.',
    '- "[*] " for bug fixes, performance improvements, and UI refinements.',
    '- "[-] " for removals / deprecated behavior.',
    '- "[=] " for upstream sync or technical maintenance.',
    '',
    'Rules for Telegram lines:',
    '- Each distinct change gets 1 concise bullet line.',
    '- 1 commit = 1 line. 10 distinct commits = 10 lines (strictly by essence, no fluff).',
    '- Do NOT include headers like "🇺🇦 UK:" or "🇺🇸 EN:" inside tg_uk/tg_en — only the bullet lines separated by newlines.',
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
      temperature: 0.2,
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

function parseNotes(raw: string): { en: string, uk: string, tg_uk: string, tg_en: string, tg: string } {
  const cleaned = raw.trim().replace(/^```(?:json)?/m, '').replace(/```$/m, '').trim()
  const parsed = JSON.parse(cleaned)
  const tgUk = String(parsed.tg_uk ?? '').trim()
  const tgEn = String(parsed.tg_en ?? '').trim()
  const tgCombined = tgUk && tgEn ? `🇺🇦 UK:\n${tgUk}\n\n🇺🇸 EN:\n${tgEn}` : String(parsed.tg ?? '').trim()
  return {
    en: String(parsed.en ?? '').trim(),
    uk: String(parsed.uk ?? '').trim(),
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

function categorize(message: string): 'sync' | 'fix' | 'feature' | 'other' {
  const m = message.toLowerCase()
  if (m.includes('sync with upstream inugram') || m.startsWith('sync:')) return 'sync'
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
    sections[categorize(subject)].push(subject)
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
