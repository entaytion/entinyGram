import { spawn } from 'node:child_process'
import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { BotKeyboard, html, MemoryStorage, TelegramClient } from '@mtcute/node'
import { joinTextWithEntities } from '@mtcute/node/utils.js'
import sharp from 'sharp'

interface BuildInfo {
  verName: string
  verCode: number
  appVerCode: number
  buildNum: number
  apkFiles: string[]
  commitSha: string
  commits: { sha: string, message: string }[]
  repo: string
}

const artifactDir = resolve(process.argv[2] ?? 'out')

// --ci-only flag: upload APKs to CI channel only, skip main channel release post
const ciOnly = process.argv.includes('--ci-only')

const info: BuildInfo = JSON.parse(await fs.readFile(join(artifactDir, 'build-info.json'), 'utf8'))
for (const file of info.apkFiles) {
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
  // Bilingual release notes from scripts/release-notes.ts (may be absent).
  let tgNotes = ''
  let enNotes = ''
  try {
    const notes = JSON.parse(await fs.readFile(join(artifactDir, 'release-notes.json'), 'utf8'))
    tgNotes = String(notes.tg ?? '')  // short bilingual post for Telegram
    enNotes = String(notes.en ?? '')  // full notes (used as fallback if tg is empty)
  } catch { }

  const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const abiOf = (file: string) => /universal/i.test(file) ? 'Universal' : 'ARM64'
  const postUrl = (id: number) => `https://t.me/${channelCI}/${id}`

  // 1) Upload the APK documents to the CI channel — always happens.
  const apkPosts: { file: string, id: number }[] = []
  for (const file of info.apkFiles) {
    const msg = await tg.sendMedia(channelCI, {
      type: 'document',
      file: `file:${join(artifactDir, file)}`,
      fileName: file,
      caption: html`#release <br/> <b>entinyGram v${info.verName}</b> (build ${info.buildNum}, ${abiOf(file)})`,
    })
    apkPosts.push({ file, id: msg.id })
  }

  // 2) If --ci-only, stop here — no main channel post.
  if (ciOnly) {
    console.log('CI-only mode: APKs uploaded to CI channel, skipping main channel post.')
  } else {
    // 3) Create inline download buttons.
    const replyMarkup = BotKeyboard.inline(
      apkPosts.map(p => [
        BotKeyboard.url(`📥 Download ${abiOf(p.file)} APK`, postUrl(p.id))
      ])
    )

    // Use short tg notes; fall back to first ~600 chars of EN notes if missing.
    const postBody = tgNotes || enNotes.slice(0, 600) || '• See the channel'
    const extra = process.env.RELEASE_EXTRA ? esc(process.env.RELEASE_EXTRA).replace(/\n/g, '<br/>') : ''

    // Convert [+]/[*]/[-]/[=] prefixed lines into HTML for Telegram.
    function notesToHtml(text: string) {
      const lines = text.split('\n').map(l => l.trim()).filter(Boolean)
      return lines.map(l => html`${esc(l)}`)
    }

    const bodyLines = notesToHtml(postBody)
    const bodyHtml = joinTextWithEntities(bodyLines, '\n')

    const bannerPath = process.env.BANNER_PATH ?? resolve(process.cwd(), 'banner.svg')
    let bannerFile: string | Buffer = `file:${bannerPath}`
    if (bannerPath.endsWith('.svg')) {
      try {
        let svgText = await fs.readFile(bannerPath, 'utf8')
        svgText = svgText.replace(/entinyGram • [^<]+/, `entinyGram • ${info.verName}`)
        bannerFile = await sharp(Buffer.from(svgText)).png().toBuffer()
      } catch (e) {
        console.warn('Failed to convert SVG banner to PNG (sharp?), falling back to raw file', e)
      }
    }

    const release = html`
      #release
      <br/>
      📡 entinyGram <b>v${info.verName}</b> (build ${info.buildNum})
      ${extra ? html`<br/><br/>${extra}` : ''}
      <br/><br/>
      ${bodyHtml}
      <br/><br/>
      @entinyGram
    `

    // 4) Send banner + release text to main channel.
    const releaseText = release.text
    if (releaseText.length <= 1024) {
      try {
        await tg.sendMedia(channelMain, { type: 'photo', file: bannerFile }, {
          caption: release,
          replyMarkup,
        })
      } catch (e: any) {
        if (e?.message?.includes('CAPTION_TOO_LONG')) {
          await tg.sendMedia(channelMain, { type: 'photo', file: bannerFile })
          await tg.sendText(channelMain, release, { replyMarkup })
        } else {
          throw e
        }
      }
    } else {
      try {
        await tg.sendMedia(channelMain, { type: 'photo', file: bannerFile })
      } catch (e) {
        console.warn('release: banner post failed (continuing):', e)
      }
      await tg.sendText(channelMain, release, { replyMarkup })
    }
  }
} finally {
  const exported = await tg.exportSession()
  if (exported !== cachedSession) {
    await persistSession(exported)
  }
  await tg.destroy()
}
