import { html } from '@mtcute/node'
import { joinTextWithEntities } from '@mtcute/node/utils.js'
import { esc, GROUP_TITLE, GROUPS, stripLinks, type Lang, type Sections, type Track } from './lib'

type Rich = ReturnType<typeof html>

export interface PostInfo {
  base: string
  label: string
  track: Track
  repo: string
  prevTag?: string
  tag?: string
}

/** The updater (UpdateHelper.kt) searches for these exact tags and clips its dialog to the first blockquote. */
export const TAG = { beta: '#prerelease', release: '#release' } as const

const DONATE = html`Підтримати / Donate: <a href="https://t.me/sendgrams?to=entaytion&amp;amount=0.1&amp;text=thx+for+entiny">Telegram</a> · <a href="https://send.monobank.ua/jar/RGYa8zo8y">monobank</a> · <a href="https://entaytion.is-a.dev/donate">Website</a>`
const BETA_WARNING = html`<i>Pre-release build for testing. It is unstable and may crash or misbehave.</i>`
const SITE_URL = process.env.SITE_CHANGELOG_URL ?? 'https://entaytion.is-a.dev/entinygram/changelog'

const join = (parts: (Rich | null)[], sep: string) => joinTextWithEntities(parts.filter((p): p is Rich => p !== null), sep)

/** `[text](url)` becomes a link, `**text**` bold, everything else is escaped. */
export function rich(text: string): Rich {
  const lines = text.split('\n').map(l => l.trim()).filter(Boolean).map((line) => {
    const parts: Rich[] = []
    let last = 0
    for (const m of line.matchAll(/\[([^\]]+)\]\(([^)]+)\)|\*\*([^*]+)\*\*/g)) {
      if (m.index > last) parts.push(html`${esc(line.slice(last, m.index))}`)
      parts.push(m[1] !== undefined ? html`<a href="${m[2]}">${esc(m[1])}</a>` : html`<b>${esc(m[3])}</b>`)
      last = m.index + m[0].length
    }
    if (last < line.length) parts.push(html`${esc(line.slice(last))}`)
    return parts.length === 1 ? parts[0] : joinTextWithEntities(parts, '')
  })
  return joinTextWithEntities(lines, '\n')
}

/** Note lines for one language: bold group title only when the language uses more than `new`. */
export function noteLines(s: Sections[Lang], lang: Lang, links: boolean): string[] {
  const used = GROUPS.filter(g => s[g].length)
  const text = (b: string) => `– ${links ? b : stripLinks(b)}`
  if (used.length === 1 && used[0] === 'new') return s.new.map(text)
  return used.flatMap(g => [`**${GROUP_TITLE[lang][g]}**`, ...s[g].map(text)])
}

const title = (info: PostInfo) => html`<b>entinyGram ${info.base} · ${info.label}</b>`

function footer(info: PostInfo): Rich {
  const diff = info.tag && info.prevTag && info.tag !== info.prevTag
    ? html`<a href="https://github.com/${info.repo}/compare/${info.prevTag}...${info.tag}">Full diff on GitHub</a>`
    : null
  return join([diff, DONATE, html`${TAG[info.track]} · @entinyGram · @entinyGramChat`], '\n')
}

/** Caption under the APK. The notes sit in the first blockquote, which is what the updater shows. */
export function captionFor(info: PostInfo, notes: string[]): Rich {
  const build = (lines: string[]) => join([
    title(info),
    info.track === 'beta' ? BETA_WARNING : null,
    html`<blockquote expandable>${rich(lines.join('\n'))}</blockquote>`,
    footer(info),
  ], '\n\n')
  return build(notes)
}

/** Telegram caps a caption at 1024 characters: keep the first and last N lines until it fits. */
export function fitCaption(info: PostInfo, notes: string[], limit = 1000): Rich {
  const full = captionFor(info, notes)
  if (full.text.length <= limit) return full
  for (let n = Math.floor(notes.length / 2); n >= 1; n--) {
    const cut = captionFor(info, [...notes.slice(0, n), '...', ...notes.slice(-n)])
    if (cut.text.length <= limit) return cut
  }
  return captionFor(info, [`Update ${info.base}`])
}

export interface MainPost {
  first: Rich
  reply: Rich | null
}

/** Release post in the main channel: ukrainian text, english in a collapsed quote, one reply when it gets too long. */
export function mainPost(info: PostInfo, notes: Sections, downloadUrl: string, extra: string, maxChars = 3800): MainPost {
  const head = html`<b>entinyGram ${info.base} · ${info.label}</b> — <a href="${downloadUrl}">Завантажити / Download</a>`
  const site = html`<a href="${SITE_URL}">Усі нові функції на сайті / All new features on the website</a>`
  const tags = html`${TAG.release} · @entinyGram · @entinyGramChat`
  const uk = rich(noteLines(notes.uk, 'uk', true).join('\n'))
  const en = rich(noteLines(notes.en, 'en', false).join('\n'))
  const note = extra ? html`${esc(extra)}` : null

  const whole = join([head, note, uk, html`English:\n<blockquote expandable>${en}</blockquote>`, site, DONATE, tags], '\n\n')
  if (whole.text.length <= maxChars) return { first: whole, reply: null }
  return {
    first: join([head, note, uk, site, DONATE, tags], '\n\n'),
    reply: join([html`<b>entinyGram ${info.base} · ${info.label}</b> · English\n\n<blockquote expandable>${en}</blockquote>`, tags], '\n\n'),
  }
}
