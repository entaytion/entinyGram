import type { InputRichMessageMedia } from '@mtcute/node'
import { spawn } from 'node:child_process'
import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { html, InputMedia, MemoryStorage, TelegramClient } from '@mtcute/node'
import { joinTextWithEntities } from '@mtcute/node/utils.js'

interface ApkFile {
  file: string
}

interface BuildInfo {
  verName: string
  verCode: number
  appVerCode: number
  buildDate: string
  apkFiles: ApkFile[]
  pluginsApk?: ApkFile | null
  pluginsArm7Apk?: ApkFile | null
  commitSha: string
  repo: string
}

const artifactDir = resolve(process.argv[2] ?? 'out')

// --ci-only flag: upload APKs to CI channel only, skip main channel release post
const ciOnly = process.argv.includes('--ci-only')

const info: BuildInfo = JSON.parse(await fs.readFile(join(artifactDir, 'build-info.json'), 'utf8'))
for (const { file } of [...info.apkFiles, info.pluginsApk, info.pluginsArm7Apk].filter((f): f is ApkFile => !!f)) {
  await fs.access(join(artifactDir, file))
}

const apiId = Number(process.env.TELEGRAM_API_ID)
const apiHash = process.env.TELEGRAM_API_HASH
const botToken = process.env.TELEGRAM_BOT_TOKEN
const channelCI = process.env.TELEGRAM_CI_CHANNEL ?? process.env.TELEGRAM_CHANNEL ?? 'entinyGramCI'
const channelMain = process.env.TELEGRAM_MAIN_CHANNEL ?? 'entinyGram'

if (!apiId || !apiHash || !botToken) {
  throw new Error('TELEGRAM_API_ID, TELEGRAM_API_HASH and TELEGRAM_BOT_TOKEN must be set')
}

const cachedSession = process.env.MTPROTO_SESSION || undefined
const ghVarsToken = process.env.GH_VARS_TOKEN
const ghRepo = process.env.GITHUB_REPOSITORY

const tg = new TelegramClient({
  apiId,
  apiHash,
  storage: new MemoryStorage(),
})

if (cachedSession) {
  await tg.importSession(cachedSession, true)
  await tg.connect()
} else {
  await tg.start({ botToken })
}

async function persistSession(session: string) {
  if (!ghVarsToken || !ghRepo) {
    console.warn('GH_VARS_TOKEN or GITHUB_REPOSITORY missing, skipping session persist')
    return
  }
  await new Promise<void>((res, rej) => {
    const p = spawn('gh', ['secret', 'set', 'MTPROTO_SESSION', '-R', ghRepo], {
      env: { ...process.env, GH_TOKEN: ghVarsToken },
      stdio: ['pipe', 'inherit', 'inherit'],
    })
    p.stdin.end(session)
    p.on('error', rej)
    p.on('exit', code => code === 0 ? res() : rej(new Error(`gh exited ${code}`)))
  })
}


try {
  const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const postUrl = (id: number) => `https://t.me/${channelCI}/${id}`

  // On-device updater clips its dialog to the caption <blockquote>, so the real
  // changelog lives in the CI caption too — not only in the main-channel post.
  let tgUk = ''
  let tgEn = ''
  let enNotes = ''
  let tgCi = ''
  try {
    const notes = JSON.parse(await fs.readFile(join(artifactDir, 'release-notes.json'), 'utf8'))
    tgUk = String(notes.tg_uk ?? '').trim()
    tgEn = String(notes.tg_en ?? '').trim()
    enNotes = String(notes.en ?? '').trim()
    tgCi = String(notes.tg_ci ?? '').trim()

    if (!tgUk && !tgEn && notes.tg) {
      const rawTg = String(notes.tg)
      const ukMatch = rawTg.match(/🇺🇦\s*UK:\s*([\s\S]*?)(?=🇺🇸\s*EN:|🇬🇧\s*(?:EN|Eng):|$)/i)
      const enMatch = rawTg.match(/(?:🇺🇸\s*EN:|🇬🇧\s*(?:EN|Eng):)\s*([\s\S]*?)(?=🇺🇦\s*UK:|$)/i)
      if (ukMatch || enMatch) {
        tgUk = (ukMatch?.[1] ?? '').trim()
        tgEn = (enMatch?.[1] ?? '').trim()
      } else {
        tgUk = rawTg.trim()
      }
    }
  } catch { }

  const postUk = tgUk || '• Оновлення доступне'
  const postEn = tgEn || (enNotes ? enNotes.slice(0, 500) : '')

  /** [text](url) → link, **text** → bold. Everything else is escaped as-is. */
  function notesToEntities(text: string) {
    const lines = text.split('\n').map(l => l.trim()).filter(Boolean)
    const htmlLines = lines.map(line => {
      // Split on link / bold tokens, reassemble as HTML.
      const parts: ReturnType<typeof html>[] = []
      let last = 0
      const tokenRe = /\[([^\]]+)\]\(([^)]+)\)|\*\*([^*]+)\*\*/g
      let m: RegExpExecArray | null
      while ((m = tokenRe.exec(line)) !== null) {
        if (m.index > last) {
          parts.push(html`${esc(line.slice(last, m.index))}`)
        }
        if (m[1] !== undefined) {
          const linkText = m[1]
          const linkUrl = m[2]
          parts.push(html`<a href="${linkUrl}">${esc(linkText)}</a>`)
        } else {
          parts.push(html`<b>${esc(m[3])}</b>`)
        }
        last = m.index + m[0].length
      }
      if (last < line.length) {
        parts.push(html`${esc(line.slice(last))}`)
      }
      return parts.length === 1 ? parts[0] : joinTextWithEntities(parts, '')
    })
    return joinTextWithEntities(htmlLines, '\n')
  }

  const ukHtml = notesToEntities(postUk)
  const enHtml = postEn ? notesToEntities(postEn) : null
  const ciHtml = tgCi ? notesToEntities(tgCi) : (enHtml ?? ukHtml)
  const isPreRelease = process.env.PRE_RELEASE === 'true'
  const appLabel = isPreRelease ? 'entinyGram Beta' : 'entinyGram'

  const PRERELEASE_WARNING = html`<i>‼️ Pre-release build — for testing new features and bug fixes. This version is unstable and may crash or misbehave.</i>`

  const releaseTagName = process.env.RELEASE_TAG ?? ''
  const prevReleaseTag = process.env.PREV_RELEASE_TAG ?? ''
  const compareHtml = releaseTagName && prevReleaseTag && prevReleaseTag !== releaseTagName
    ? html`📝 <a href="https://github.com/${info.repo}/compare/${prevReleaseTag}...${releaseTagName}">Full diff on GitHub</a>`
    : null

  // Updater clips its dialog to the caption <blockquote>; tag is #release XOR #prerelease.
  const releaseTag = isPreRelease ? '#prerelease' : '#release'

  function getHeader() {
    const label = isPreRelease ? 'entinyGram Beta' : 'entinyGram'
    return html`<b>${label} v${info.verName}</b> (build ${info.buildDate})`
  }

  function getFooter() {
    const lines = [
      compareHtml,
      html`🏷️ ${releaseTag} • @entinyGram • @entinyGramChat`,
    ].filter((p): p is NonNullable<typeof p> => p !== null)

    return joinTextWithEntities(lines, '\n')
  }

  function buildPostCaption(content: ReturnType<typeof html>) {
    const parts = [getHeader()]

    if (isPreRelease) {
      parts.push(PRERELEASE_WARNING)
    }

    parts.push(content)
    parts.push(getFooter())

    return joinTextWithEntities(parts, '\n\n')
  }

  // Caption limit is 1024 chars; trim lines until it fits, full notes go in a reply.
  const buildCaption = (notesEntity: ReturnType<typeof html>) => buildPostCaption(html`<blockquote>${notesEntity}</blockquote>`)

  // Display order: with plugins first, then without. The label doubles as the DOWNLOAD link text.
  const variants: { label: string, file: string, plugins: boolean }[] = []
  const addVariant = (label: string, file: string | undefined, plugins: boolean) => {
    if (file) variants.push({ label, file, plugins })
  }
  addVariant('ARM64 + plugins', info.pluginsApk?.file, true)
  addVariant('ARM7 + plugins', info.pluginsArm7Apk?.file, true)
  addVariant('ARM64', info.apkFiles[0]?.file, false)
  addVariant('ARM7', info.apkFiles[1]?.file, false)

  let firstPostId: number
  if (variants.length === 1) {
    // A single APK keeps the classic post: the commit list is the caption, the rest goes in a reply.
    let caption = buildCaption(ciHtml)
    let needsCiFollowup = false

    // Max caption length for Telegram media is 1024 characters. Keep safety margin.
    if (caption.text.length > 1000) {
      needsCiFollowup = true
      const postCi = tgCi || postEn || postUk
      const rawLines = postCi.split('\n').map(l => l.trim()).filter(Boolean)
      const keptLines: string[] = []
      for (const line of rawLines) {
        const candidateLines = [...keptLines, line, '... (повний список нижче / full changelog below)']
        const candidateHtml = notesToEntities(candidateLines.join('\n'))
        const candidateCaption = buildCaption(candidateHtml)
        if (candidateCaption.text.length > 980) break
        keptLines.push(line)
      }
      if (keptLines.length > 0) {
        keptLines.push('... (повний список нижче / full changelog below)')
        caption = buildCaption(notesToEntities(keptLines.join('\n')))
      } else {
        caption = buildCaption(html`• Оновлення v${info.verName}\n... (повний список нижче / full changelog below)`)
      }
    }

    const apkMsg = await tg.sendMedia(channelCI, {
      type: 'document',
      file: `file:${join(artifactDir, variants[0].file)}`,
      fileName: variants[0].file,
      caption,
    })
    firstPostId = apkMsg.id

    if (needsCiFollowup) {
      console.log('CI caption was truncated to fit 1024 limit; sending full notes in reply...')
      await tg.sendText(
        channelCI,
        html`📝 <b>Changelog v${info.verName}:</b>\n\n<blockquote expandable>${ciHtml}</blockquote>`,
        { replyTo: apkMsg.id },
      )
    }
  } else {
    // Several APKs go into one album; the caption only says which file is which, the changelog follows as replies.
    const names = (plugins: boolean) => variants.filter(v => v.plugins === plugins).map(v => v.label.replace(' + plugins', ''))
    const legend: ReturnType<typeof html>[] = []
    if (names(true).length) legend.push(html`🔌 <b>With plugins:</b> ${names(true).join(' · ')}`)
    if (names(false).length) legend.push(html`📦 <b>Without plugins:</b> ${names(false).join(' · ')}`)
    legend.push(html`📝 Detailed changelog on the website and below`)
    const albumCaption = buildPostCaption(joinTextWithEntities(legend, '\n'))

    const album = await tg.sendMediaGroup(channelCI, variants.map((v, i) => ({
      type: 'document' as const,
      file: `file:${join(artifactDir, v.file)}`,
      fileName: v.file,
      ...(i === 0 ? { caption: albumCaption } : {}),
    })))
    firstPostId = album[0].id

    // The full changelog: a commit list, split into messages that fit the 4096-character limit.
    const changelogSource = tgCi || postEn || postUk
    const chunks: string[] = []
    let current = ''
    for (const line of changelogSource.split('\n').map(l => l.trim()).filter(Boolean)) {
      if (current.length + line.length + 1 > 3500) {
        chunks.push(current)
        current = ''
      }
      current += `${current ? '\n' : ''}${line}`
    }
    if (current) chunks.push(current)
    let replyTo = album[0].id
    for (const [i, chunk] of chunks.entries()) {
      const tail = i === chunks.length - 1 && compareHtml ? html`\n\n${compareHtml}` : html``
      const sent = await tg.sendText(
        channelCI,
        html`📝 <b>Changelog v${info.verName}${chunks.length > 1 ? ` (${i + 1}/${chunks.length})` : ''}:</b>\n\n<blockquote expandable>${notesToEntities(chunk)}</blockquote>${tail}`,
        { replyTo },
      )
      replyTo = sent.id
    }
  }

  // 2) --ci-only stops here: no main-channel post. Pre-releases are always ci-only (apk.yml).
  if (ciOnly) {
    console.log('CI-only mode: APK uploaded to CI channel, skipping main channel post.')
  } else {
    const extra = process.env.RELEASE_EXTRA ? esc(process.env.RELEASE_EXTRA).trim() : ''

    // Every variant lives in the same CI album, so every DOWNLOAD link points at it (Telegram cannot link a single file).
    const downloadHtml = variants.length > 1
      ? html`⬇️ <b>DOWNLOAD:</b> ${joinTextWithEntities(variants.map(v => html`<a href="${postUrl(firstPostId)}"><b>${v.label}</b></a>`), ' · ')}`
      : html`⬇️ <a href="${postUrl(firstPostId)}">Завантажити / Download</a>`
    const siteUrl = process.env.SITE_CHANGELOG_URL ?? 'https://entaytion.is-a.dev/entinygram/changelog'
    const siteHtml = html`🌐 <a href="${siteUrl}">Усі нові функції на сайті / See all new features on the website</a>`
    // Discrete blocks joined by a blank line each — no stray empty paragraphs.
    const blocks = [
      html`📡 <b>entinyGram v${info.verName}</b> (build ${info.buildDate})`,
      downloadHtml,
      extra ? html`${extra}` : null,
      ukHtml,
      enHtml ? html`🇬🇧 Eng:\n<blockquote expandable>${enHtml}</blockquote>` : null,
      siteHtml,
      html`🏷️ #release • @entinyGram • @entinyGramChat`,
    ].filter((b): b is NonNullable<typeof b> => b !== null)

    const release = joinTextWithEntities(blocks, '\n\n')

    // >3800 chars → split into UA post + EN reply (sendText limit is 4096).
    if (release.text.length <= 3800 || !enHtml) {
      await tg.sendText(channelMain, release)
    } else {
      console.log('Main channel post is long; splitting into Ukrainian post and English reply...')
      const post1Blocks = [
        html`📡 <b>entinyGram v${info.verName}</b> (build ${info.buildDate})`,
        downloadHtml,
        extra ? html`${extra}` : null,
        ukHtml,
        siteHtml,
        html`🏷️ #release • @entinyGram • @entinyGramChat`,
      ].filter((b): b is NonNullable<typeof b> => b !== null)

      const mainMsg = await tg.sendText(channelMain, joinTextWithEntities(post1Blocks, '\n\n'))

      const post2Blocks = [
        html`🇬🇧 <b>entinyGram v${info.verName}</b> (build ${info.buildDate})\n\n<blockquote expandable>${enHtml}</blockquote>`,
        html`🏷️ #release • @entinyGram • @entinyGramChat`,
      ]
      await tg.sendText(channelMain, joinTextWithEntities(post2Blocks, '\n\n'), { replyTo: mainMsg.id })
    }
  }
} finally {
  const exported = await tg.exportSession()
  if (exported !== cachedSession) {
    await persistSession(exported).catch(e => console.warn('failed to persist session:', e))
  }
  await tg.destroy()
}
