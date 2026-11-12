import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const rootDir = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
export const changelogsDir = join(rootDir, 'changelogs')

export type Track = 'beta' | 'release'
export type Lang = 'uk' | 'en'
export type Group = 'new' | 'fixed' | 'removed'

export const GROUPS: Group[] = ['new', 'fixed', 'removed']
export const GROUP_TITLE: Record<Lang, Record<Group, string>> = {
  uk: { new: 'Нове', fixed: 'Виправлено', removed: 'Прибрано' },
  en: { new: 'New', fixed: 'Fixed', removed: 'Removed' },
}

export type Sections = Record<Lang, Record<Group, string[]>>

export const emptySections = (): Sections => ({
  uk: { new: [], fixed: [], removed: [] },
  en: { new: [], fixed: [], removed: [] },
})

export const flat = (s: Record<Group, string[]>) => GROUPS.flatMap(g => s[g])

/** `## uk` / `## en` with bullets; an optional `### new|fixed|removed` splits a language, bullets without one count as new. */
export function parseChangelog(raw: string): Sections {
  const out = emptySections()
  let lang: Lang | null = null
  let group: Group = 'new'
  for (const line of raw.replace(/<!--[\s\S]*?-->/g, '').split(/\r?\n/)) {
    const h = line.match(/^##\s+(uk|en)\s*$/i)
    if (h) {
      lang = h[1].toLowerCase() as Lang
      group = 'new'
      continue
    }
    const g = line.match(/^###\s+(new|fixed|removed)\s*$/i)
    if (g) {
      group = g[1].toLowerCase() as Group
      continue
    }
    const b = line.match(/^\s*[-*•–]\s+(\S.*)$/)
    if (lang && b) out[lang][group].push(b[1].trim())
  }
  return out
}

export function renderChangelog(s: Sections): string {
  const lang = (l: Lang) => {
    const groups = GROUPS.filter(g => s[l][g].length)
    if (groups.length === 1 && groups[0] === 'new') return s[l].new.map(b => `- ${b}`).join('\n')
    return groups.map(g => `### ${g}\n${s[l][g].map(b => `- ${b}`).join('\n')}`).join('\n\n')
  }
  return `## uk\n${lang('uk')}\n\n## en\n${lang('en')}\n`
}

export const stripLinks = (t: string) => t.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
export const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

export interface TrackState {
  base: string
  beta: number
  release: number
}

/** State is `<base> beta:<n> release:<n>`, stored in the INU_TRACK_STATE repo variable. */
export function parseTrackState(raw: string): TrackState | null {
  const m = raw.trim().match(/^(\S+)\s+beta:(\d+)\s+release:(\d+)$/)
  return m ? { base: m[1], beta: Number(m[2]), release: Number(m[3]) } : null
}

export const formatTrackState = (s: TrackState) => `${s.base} beta:${s.beta} release:${s.release}`

/** A new base resets both counters; a release leaves the beta counter alone and the other way round. */
export function nextTrack(prev: TrackState | null, base: string, track: Track): TrackState {
  const start: TrackState = prev && prev.base === base ? prev : { base, beta: 0, release: 0 }
  return { ...start, [track]: start[track] + 1 }
}

/** Service areas say nothing to a reader of the beta list. */
const SERVICE_AREAS = new Set(['docs', 'ci', 'build', 'merge', 'rebase', 'release'])

export interface Commit { sha: string, email?: string, message: string }

export function commitLine(c: Commit): string | null {
  const first = c.message.split('\n')[0].trim()
  const m = first.match(/^\[([a-z-]+)\]\s*(.+)$/i)
  if (m && SERVICE_AREAS.has(m[1].toLowerCase())) return null
  const subject = (m ? m[2] : first).trim()
  if (!subject || /^(?:crowdin|export patches|export stgit|update series|temp(?:orary)? debug|debug)/i.test(subject)) return null
  return subject.charAt(0).toUpperCase() + subject.slice(1)
}

export const commitLines = (commits: Commit[]) => [...commits].reverse().map(commitLine).filter((l): l is string => !!l)
