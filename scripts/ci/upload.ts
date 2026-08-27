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

try {
  const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const postUrl = (id: number) => `https://t.me/${channelCI}/${id}`

  // Parse AI-generated release notes up front — the CI channel caption embeds the real
  // changelog in a <blockquote> too, since UpdateHelper.kt (on-device) reads its update-dialog
  // text straight from this channel's message, not from the main channel's text-only post.
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

  function notesToEntities(text: string) {
    const lines = text.split('\n').map(l => l.trim()).filter(Boolean)
    return joinTextWithEntities(lines.map(l => html`${esc(l)}`), '\n')
  }

  const ukHtml = notesToEntities(postUk)
  const enHtml = postEn ? notesToEntities(postEn) : null
  // CI channel caption is English — UpdateHelper.kt's extractApkInfo/applyUpdate clips the
  // update-dialog text to exactly this <blockquote> entity, so whatever goes here is what
  // shows up in the on-device update dialog too. Fall back to Ukrainian only if no EN notes.
  const ciHtml = enHtml ?? ukHtml

  // 1) Upload the APK document to the CI channel — always happens. The changelog goes in a
  // <blockquote>: UpdateHelper.kt's extractApkInfo/applyUpdate clips the update-dialog text to
  // exactly this entity, discarding the #release/label wrapper text around it.
  const { file } = info.apkFiles[0]
  const apkMsg = await tg.sendMedia(channelCI, {
    type: 'document',
    file: `file:${join(artifactDir, file)}`,
    fileName: file,
    caption: html`<b>entinyGram v${info.verName}</b> (build ${info.buildDate})<br/><br/><blockquote>${ciHtml}</blockquote><br/>🏷️ #release • @entinyGram • @entinyGramChat`,
  })

  // 2) If --ci-only, stop here — no main channel post.
  if (ciOnly) {
    console.log('CI-only mode: APK uploaded to CI channel, skipping main channel post.')
  } else {
    const extra = process.env.RELEASE_EXTRA ? esc(process.env.RELEASE_EXTRA).trim() : ''

    const linksHtml = html`<a href="${postUrl(apkMsg.id)}">Завантажити / Download</a>`

    // Build as discrete blocks joined by a single blank line each -- avoids the stray empty
    // paragraph that mixing literal template-literal newlines with <br/> tags used to leave
    // behind whenever `extra` (or any other optional block) was empty.
    const blocks = [
      html`📡 <b>entinyGram v${info.verName}</b> (build ${info.buildDate}) — ${linksHtml}`,
      extra ? html`${extra}` : null,
      ukHtml,
      enHtml ? html`🇬🇧 Eng:\n<blockquote expandable>${enHtml}</blockquote>` : null,
      html`🏷️ #release • @entinyGram • @entinyGramChat`,
    ].filter((b): b is NonNullable<typeof b> => b !== null)

    const release = joinTextWithEntities(blocks, '\n\n')

    // 4) Send clean text release post directly to main channel (no buttons, exteraless style).
    await tg.sendText(channelMain, release)
  }
} finally {
  const exported = await tg.exportSession()
  if (exported !== cachedSession) {
    await persistSession(exported)
  }
  await tg.destroy()
}
