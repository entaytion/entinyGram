import { spawn } from 'node:child_process'
import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { html, MemoryStorage, TelegramClient } from '@mtcute/node'
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
  commitSha: string
  commits: { sha: string, message: string }[]
  repo: string
}

const artifactDir = resolve(process.argv[2] ?? 'out')

// --ci-only flag: upload APKs to CI channel only, skip main channel release post
const ciOnly = process.argv.includes('--ci-only')

const info: BuildInfo = JSON.parse(await fs.readFile(join(artifactDir, 'build-info.json'), 'utf8'))
for (const { file } of info.apkFiles) {
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

/**
 * Most recent stable #release message in the CI channel, for the footer download link.
 * Returns null when not found / search failed.
 */
async function findLastReleaseMessageId(): Promise<number | null> {
  try {
    const results = await tg.searchMessages({
      chatId: channelCI,
      query: '#release',
      limit: 20,
    })
    for (const msg of results) {
      const text = msg.text ?? ''
      if (/#release\b/.test(text) && !/#prerelease\b/.test(text)) {
        return msg.id
      }
    }
    return null
  } catch (e) {
    console.warn(`upload: could not search CI channel for last release: ${e}`)
    return null
  }
}

try {
  const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const postUrl = (id: number) => `https://t.me/${channelCI}/${id}`

  // On-device updater clips its dialog to the caption <blockquote>, so the real
  // changelog lives in the CI caption too — not only in the main-channel post.
  let tgUk = ''
  let tgEn = ''
  let enNotes = ''
  try {
    const notes = JSON.parse(await fs.readFile(join(artifactDir, 'release-notes.json'), 'utf8'))
    tgUk = String(notes.tg_uk ?? '').trim()
    tgEn = String(notes.tg_en ?? '').trim()
    enNotes = String(notes.en ?? '').trim()

    if (!tgUk && !tgEn && notes.tg) {
      const rawTg = String(notes.tg)
      const ukMatch = rawTg.match(/🇺🇦\s*UK:\s*([\s\S]*?)(?=🇺🇸\s*EN:|🇬🇧\s*Eng:|$)/i)
      const enMatch = rawTg.match(/(?:🇺🇸\s*EN:|🇬🇧\s*Eng:)\s*([\s\S]*?)(?=🇺🇦\s*UK:|$)/i)
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
  const ciHtml = enHtml ?? ukHtml
  const isPreRelease = process.env.PRE_RELEASE === 'true'
  const appLabel = isPreRelease ? 'entinyGram Beta' : 'entinyGram'

  const preReleaseBanner = isPreRelease
    ? html`<i>‼️ Pre-release build — for testing new features and bug fixes. This version is unstable and may crash or misbehave.</i><br/>`
    : ''

  // Footer links: last stable release + full GitHub diff. Omitted when unresolvable.
  const lastReleaseId = await findLastReleaseMessageId()
  const lastReleaseHtml = lastReleaseId !== null
    ? html`<br/>⬇️ The last release — <a href="${postUrl(lastReleaseId)}">download</a>`
    : ''

  const releaseTagName = process.env.RELEASE_TAG ?? ''
  const prevReleaseTag = process.env.PREV_RELEASE_TAG ?? ''
  const compareHtml = releaseTagName && prevReleaseTag && prevReleaseTag !== releaseTagName
    ? html`<br/>📝 <a href="https://github.com/${info.repo}/compare/${prevReleaseTag}...${releaseTagName}">Full diff on GitHub</a>`
    : ''

  // Updater clips its dialog to the caption <blockquote>; tag is #release XOR #prerelease.
  const releaseTag = isPreRelease ? '#prerelease' : '#release'
  const { file } = info.apkFiles[0]

  // Caption limit is 1024 chars; trim lines until it fits, full notes go in a reply.
  const buildCaption = (notesEntity: ReturnType<typeof html>) => html`<b>${appLabel} v${info.verName}</b> (build ${info.buildDate})<br/><br/>${preReleaseBanner}<blockquote>${notesEntity}</blockquote>${lastReleaseHtml}${compareHtml}<br/>🏷️ ${releaseTag} • @entinyGram • @entinyGramChat`

  let caption = buildCaption(ciHtml)
  let needsCiFollowup = false

  // Max caption length for Telegram media is 1024 characters. Keep safety margin.
  if (caption.text.length > 1000) {
    needsCiFollowup = true
    const postCi = postEn || postUk
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
    file: `file:${join(artifactDir, file)}`,
    fileName: file,
    caption,
  })

  if (needsCiFollowup) {
    console.log('CI caption was truncated to fit 1024 limit; sending full notes in reply...')
    await tg.sendText(
      channelCI,
      html`📝 <b>Changelog v${info.verName}:</b>\n\n<blockquote expandable>${ciHtml}</blockquote>`,
      { replyTo: apkMsg.id }
    )
  }

  // Optional arm7 (32-bit) build -- rare, only present when apk.yml's build_arm7 toggle was on.
  // Posted as a reply so it doesn't compete with the arm64 file most people actually need.
  const arm7File = info.apkFiles[1]?.file
  const arm7Msg = arm7File
    ? await tg.sendMedia(channelCI, {
      type: 'document',
      file: `file:${join(artifactDir, arm7File)}`,
      fileName: arm7File,
      caption: html`⚠️ <b>armeabi-v7a (32-біт)</b> — рідкісна збірка для старих пристроїв. Якщо не знаєш, що це — тобі не потрібен цей файл, бери APK вище.\n\n⚠️ <b>armeabi-v7a (32-bit)</b> — rare build for old devices. If unsure, you don't need this — grab the APK above instead.`,
    }, { replyTo: apkMsg.id })
    : null

  // 2) --ci-only stops here: no main-channel post. Pre-releases are always ci-only (apk.yml).
  if (ciOnly) {
    console.log('CI-only mode: APK uploaded to CI channel, skipping main channel post.')
  } else {
    const extra = process.env.RELEASE_EXTRA ? esc(process.env.RELEASE_EXTRA).trim() : ''

    const linksHtml = html`<a href="${postUrl(apkMsg.id)}">Завантажити / Download</a>`
    const arm7NoteHtml = arm7Msg
      ? html`⚠️ 32-біт (arm7) для старих пристроїв, більшості не треба — <a href="${postUrl(arm7Msg.id)}">тут</a> / 32-bit (arm7) for old devices, most people don't need it — <a href="${postUrl(arm7Msg.id)}">here</a>`
      : null

    // Discrete blocks joined by a blank line each — no stray empty paragraphs.
    const blocks = [
      html`📡 <b>entinyGram v${info.verName}</b> (build ${info.buildDate}) — ${linksHtml}`,
      arm7NoteHtml,
      extra ? html`${extra}` : null,
      ukHtml,
      enHtml ? html`🇬🇧 Eng:\n<blockquote expandable>${enHtml}</blockquote>` : null,
      html`🏷️ #release • @entinyGram • @entinyGramChat`,
    ].filter((b): b is NonNullable<typeof b> => b !== null)

    const release = joinTextWithEntities(blocks, '\n\n')

    // >3800 chars → split into UA post + EN reply (sendText limit is 4096).
    if (release.text.length <= 3800 || !enHtml) {
      await tg.sendText(channelMain, release)
    } else {
      console.log('Main channel post is long; splitting into Ukrainian post and English reply...')
      const post1Blocks = [
        html`📡 <b>entinyGram v${info.verName}</b> (build ${info.buildDate}) — ${linksHtml}`,
        arm7NoteHtml,
        extra ? html`${extra}` : null,
        ukHtml,
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
    await persistSession(exported)
  }
  await tg.destroy()
}
