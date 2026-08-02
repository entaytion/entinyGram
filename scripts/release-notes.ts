import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'

/**
 * Generates pretty bilingual (en + uk) release notes for a build.
 *
 * Reads `build-info.json` (produced by scripts/ci/version.ts + the apk.yml
 * "stage artifacts" step), asks Google Gemini to summarize the commit list into
 * concise user-facing notes, and writes `release-notes.json`:
 *   { "en": "...", "uk": "..." }
 *
 * Config:
 *   GEMINI_API_KEY  - required for AI mode (Google Generative Language API).
 *   GEMINI_MODEL    - optional, defaults to `gemini-3.1-flash-lite`.
 *   GEMINI_BASE_URL - optional; OpenAI-compatible chat/completions endpoint.
 *                     Defaults to the OpenAI-compatible Google endpoint.
 *   artifactDir     - argv[2], defaults to `out`.
 *
 * If no key is present — or the API call fails — a tiny rule-based fallback is
 * used so the CI pipeline (and local testing) never breaks.
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
  const list = commits.map(c => `- ${c.message} (${c.sha.slice(0, 7)})`).join('\n')
  return [
    'You are writing the release notes for entinyGram, a fork of Telegram for Android.',
    '',
    `Release: v${info.verName} (build ${info.buildNum}, repo ${info.repo})`,
    '',
    'Commits since the last release:',
    list || '(no commits)',
    '',
    'Write concise, polished, user-friendly release notes grouped into sections with ',
    'emoji headings (e.g. "✨ New", "🛠 Improvements", "🐛 Fixes", "⚙️ Other").',
    'Use one bullet per line starting with "• ". Keep each language version SHORT (under ~500 chars).',
    'Do NOT mention commit hashes or the word "commit". Do not mention the repo name.',
    '',
    'Write the notes in TWO languages (natural, idiomatic copy). Return ONLY valid JSON,',
    'no markdown fences, exactly this shape:',
    '{"en":"...english notes, \\n separated...","uk":"...українські нотатки, розділені \\n..."}',
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

function parseNotes(raw: string): { en: string, uk: string } {
  const cleaned = raw.trim().replace(/^```(?:json)?/m, '').replace(/```$/m, '').trim()
  const parsed = JSON.parse(cleaned)
  return {
    en: String(parsed.en ?? '').trim(),
    uk: String(parsed.uk ?? '').trim(),
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
      if (!notes.en && !notes.uk) throw new Error('empty ai notes')
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

function bulletize(lines: string[]): string {
  return lines.map(l => `• ${l}`).join('\n') || ''
}

function ruleFallback(commits: Commit[]): { en: string, uk: string } {
  if (commits.length === 0) return { en: 'No changes in this build.', uk: 'У цьому збірці змін немає.' }

  const sections: Record<string, string[]> = { feature: [], fix: [], other: [] }
  for (const c of commits) {
    sections[categorize(c.message)].push(c.message)
  }

  const en: string[] = []
  const uk: string[] = []
  if (sections.feature.length) {
    en.push('✨ New', bulletize(sections.feature))
    uk.push('✨ Нове', bulletize(sections.feature))
  }
  if (sections.fix.length) {
    en.push('🐛 Fixes & improvements', bulletize(sections.fix))
    uk.push('🐛 Виправлення та покращення', bulletize(sections.fix))
  }
  if (sections.other.length) {
    en.push('⚙️ Other', bulletize(sections.other))
    uk.push('⚙️ Інше', bulletize(sections.other))
  }
  return { en: en.join('\n\n'), uk: uk.join('\n\n') }
}

const info: BuildInfo = JSON.parse(await fs.readFile(infoPath, 'utf8'))
const commits = cleanCommits(info.commits ?? [])

let notes: { en: string, uk: string }
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

