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

interface Commit { sha: string, message: string }
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
    .filter(c => !c.message.startsWith('infra:'))
    .reverse()
}

function buildPrompt(info: BuildInfo, commits: Commit[]): string {
  const list = commits.map(c => {
    const indented = c.message.split('\n').map(l => `  ${l}`).join('\n')
    return `Commit ${c.sha.slice(0, 7)}:\n${indented}`
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
    '  "en": "...full GitHub release notes in English (Markdown)...",',
    '  "uk": "...повні нотатки для GitHub українською (Markdown)...",',
    '  "tg_uk": "...short Ukrainian Telegram changelog lines...",',
    '  "tg_en": "...short English Telegram changelog lines..."',
    '}',
    '',
    '=== "en" and "uk" keys (GitHub release notes) ===',
    'Write clean, well-structured Markdown release notes.',
    'Group changes into sections: ### New Features, ### Bug Fixes, ### Improvements & Polish.',
    'Only include an "### Upstream Sync" section IF commits explicitly mention "sync with upstream inugram" or an upstream rebase. DO NOT mention inugram otherwise.',
    'Each item should be a Markdown list item starting with `-`.',
    'Explain each change clearly for developers and power users.',
    'Aim for 500-1200 chars per language.',
    '',
    '=== "tg_uk" and "tg_en" keys (Telegram post items) ===',
    'Write concise bullet points for the Telegram channel release post using prefixes:',
    '- "[+] " for new features and user-facing capabilities.',
    '- "[*] " for bug fixes, performance improvements, and UI refinements.',
    '- "[-] " for removals / deprecated behavior.',
    '',
    'CRITICAL RULES FOR TELEGRAM CHANGELOGS ("tg_uk" & "tg_en"):',
    '1. NEVER mention "upstream inugram" or "sync with inugram" unless the commits explicitly state "sync with upstream inugram". 99% of releases are entinyGram\'s own features/fixes.',
    '2. ACCURATELY CAPTURE THE BUILD TYPE:',
    '   - If the commits are primarily bugfixes and polish (e.g. minor point builds like 1000403), describe the concrete fixes and UI polish clearly without hallucinating nonexistent features.',
    '   - If new features WERE added (even in a bugfix build), list those features first with "[+]" and group bug fixes into "[*]".',
    '3. CONCISE & USER-FRIENDLY: up to 8 to 12 bullet lines per language if there are many changes (or 3 to 6 for small builds). Rewrite technical commit jargon into human-friendly benefits.',
    '4. Group all minor bugfixes into 1 or 2 "[*]" lines instead of listing 10 tiny fixes.',
    '5. Do NOT include headers like "🇺🇦 UK:" or "🇺🇸 EN:" inside tg_uk/tg_en — only the bullet lines separated by newlines.',
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
      temperature: 0.6,
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

function categorize(message: string): 'fix' | 'feature' | 'other' {
  const m = message.toLowerCase()
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

  const sections: Record<string, string[]> = { feature: [], fix: [], other: [] }
  for (const c of commits) {
    const subject = c.message.split('\n')[0].trim()
    sections[categorize(subject)].push(subject)
  }

  const en: string[] = []
  const uk: string[] = []
  if (sections.feature.length) en.push(...sections.feature.map(l => `[+] ${l}`))
  if (sections.fix.length) en.push(`[*] Bug fixes and UI optimizations`)
  if (sections.other.length) en.push(...sections.other.map(l => `[=] ${l}`))

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
