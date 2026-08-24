import { spawn } from 'node:child_process'
import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { html, MemoryStorage, TelegramClient } from '@mtcute/node'
import { joinTextWithEntities } from '@mtcute/node/utils.js'

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
      caption: html`#release <br/> <b>entinyGram v${info.verName}</b> (build ${info.buildNum}, ${abiOf(file)})<br/><br/>@entinyGram / @entinyGramChat`,
    })
    apkPosts.push({ file, id: msg.id })
  }

  // 2) If --ci-only, stop here — no main channel post.
  if (ciOnly) {
    console.log('CI-only mode: APKs uploaded to CI channel, skipping main channel post.')
  } else {
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
    const extra = process.env.RELEASE_EXTRA ? esc(process.env.RELEASE_EXTRA).replace(/\n/g, '<br/>') : ''

    const arm64 = apkPosts.find(p => !/universal/i.test(p.file)) ?? apkPosts[0]
    const univ = apkPosts.find(p => /universal/i.test(p.file))
    const linksHtml = arm64 && univ
      ? html`<a href="${postUrl(arm64.id)}">ARM64</a> • <a href="${postUrl(univ.id)}">Universal</a>`
      : (arm64 ? html`<a href="${postUrl(arm64.id)}">Завантажити APK</a>` : '')

    const release = html`
      📡 <b>entinyGram v${info.verName}</b> (build ${info.buildNum}) ${linksHtml ? html`— ${linksHtml}` : ''}
      ${extra ? html`<br/><br/>${extra}` : ''}
      <br/><br/>
      ${ukHtml}
      ${enHtml ? html`<br/><br/>🇬🇧 Eng:<br/><blockquote expandable>${enHtml}</blockquote>` : ''}
      <br/><br/>
      #release
      <br/>
      @entinyGram / @entinyGramChat
    `

    // 4) Send clean text release post directly to main channel (no buttons, exteraless style).
    await tg.sendText(channelMain, release)
  }
}
} finally {
  const exported = await tg.exportSession()
  if (exported !== cachedSession) {
    await persistSession(exported)
  }
  await tg.destroy()
}
