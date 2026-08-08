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
  buildNum: number
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
    'You are writing the release notes for entinyGram, a fork of Telegram for Android.',
    '',
    `Release: v${info.verName} (build ${info.buildNum}, repo ${info.repo})`,
    '',
    'Commits since the last release (technical subjects + detailed bullet points of changes):',
    list || '(no commits)',
    '',
    '=== OUTPUT FORMAT ===',
    'Return ONLY valid JSON with exactly 3 keys: "en", "uk", "tg". No markdown fences.',
    '{',
    '  "en": "...full GitHub release notes in English...",',
    '  "uk": "...повні нотатки для GitHub українською...",',
    '  "tg": "...short Telegram post, bilingual (EN + UK), see format below..."',
    '}',
    '',
    '=== "en" and "uk" keys (GitHub release notes) ===',
    'Write detailed, well-structured Markdown release notes.',
    'Group changes into sections: ### New Features, ### Bug Fixes, ### Changes, ### Upstream Sync.',
    'Each item should be a Markdown list item starting with `-`.',
    'Explain each change clearly for developers and advanced users.',
    'Differentiate entinyGram-specific changes from upstream inugram syncs.',
    'Aim for 500-1200 chars per language.',
    '',
    '=== "tg" key (Telegram post) ===',
    'Write a SHORT bilingual post for a Telegram channel. Target ~600-900 chars total.',
    'Format:',
    '🇺🇸 EN:',
    '[+] Most important new feature',
    '[+] Second important feature',
    '[*] Bug fixes, UI improvements and optimizations',
    '',
    '🇺🇦 UK:',
    '[+] Найважливіша нова функція',
    '[+] Друга важлива функція',
    '[*] Виправлено баги, покращення UI та оптимізації',
    '',
    'Rules for "tg":',
    '- Use "[+] " for new features, "[-] " for removals, "[*] " for bug fixes/optimizations, "[=] " for neutral changes.',
    '- Keep 4-7 lines per language block. Group ALL bug fixes into exactly ONE [*] line.',
    '- Highlight the MOST IMPORTANT user-facing entinyGram changes only.',
    '- Separate EN and UK blocks with a blank line. Do NOT add @username mentions or extra decorations.',
    '- Upstream sync: one line max, e.g. "[=] Synced with upstream inugram" / "[=] Синхронізовано з inugram".',
    '- Do NOT copy commit subjects verbatim. Rewrite into human-readable benefits.',
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

function parseNotes(raw: string): { en: string, uk: string, tg: string } {
  const cleaned = raw.trim().replace(/^```(?:json)?/m, '').replace(/```$/m, '').trim()
  const parsed = JSON.parse(cleaned)
  return {
    en: String(parsed.en ?? '').trim(),
    uk: String(parsed.uk ?? '').trim(),
    tg: String(parsed.tg ?? '').trim(),
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
      if (!notes.en && !notes.uk && !notes.tg) throw new Error('empty ai notes')
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

function ruleFallback(commits: Commit[]): { en: string, uk: string, tg: string } {
  if (commits.length === 0) return {
    en: 'No changes in this build.',
    uk: 'У цьому збірці змін немає.',
    tg: '🇺🇸 EN:\n[=] No changes\n\n🇺🇦 UK:\n[=] Змін немає',
  }

  const sections: Record<string, string[]> = { feature: [], fix: [], other: [] }
  for (const c of commits) {
    const subject = c.message.split('\n')[0].trim()
    sections[categorize(subject)].push(subject)
  }

  const en: string[] = []
  const uk: string[] = []
  if (sections.feature.length) en.push(...sections.feature.map(l => `[+] ${l}`))
  if (sections.fix.length) en.push(`[*] Bug fixes and app optimizations`)
  if (sections.other.length) en.push(...sections.other.map(l => `[=] ${l}`))

  if (sections.feature.length) uk.push(...sections.feature.map(l => `[+] ${l}`))
  if (sections.fix.length) uk.push(`[*] Виправлено баги та оптимізовано роботу застосунку`)
  if (sections.other.length) uk.push(...sections.other.map(l => `[=] ${l}`))

  const tgEn = en.slice(0, 5).join('\n')
  const tgUk = uk.slice(0, 5).join('\n')
  const tg = `🇺🇸 EN:\n${tgEn}\n\n🇺🇦 UK:\n${tgUk}`

  const enFull = en.join('\n')
  const ukFull = uk.join('\n')
  return { en: enFull, uk: ukFull, tg }
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
