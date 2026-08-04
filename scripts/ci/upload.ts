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
const info: BuildInfo = JSON.parse(await fs.readFile(join(artifactDir, 'build-info.json'), 'utf8'))
for (const file of info.apkFiles) {
  await fs.access(join(artifactDir, file))
}

const apiId = Number(process.env.TELEGRAM_API_ID)
const apiHash = process.env.TELEGRAM_API_HASH
const botToken = process.env.TELEGRAM_BOT_TOKEN
const channel = process.env.TELEGRAM_CHANNEL ?? 'entinyGramCI'

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
  const commits = info.commits.filter(c => !c.message.startsWith('infra:')).reverse()

  // Bilingual release notes from scripts/release-notes.ts (may be absent).
  let en = ''
  let uk = ''
  try {
    const notes = JSON.parse(await fs.readFile(join(artifactDir, 'release-notes.json'), 'utf8'))
    en = String(notes.en ?? '')
    uk = String(notes.uk ?? '')
  } catch { }

  const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const abiOf = (file: string) => /universal/i.test(file) ? 'Universal' : 'ARM64'
  const postUrl = (id: number) => `https://t.me/${channel}/${id}`
  const bullets = (text: string) =>
    text.split('\n').map(l => l.trim()).filter(Boolean).map(l => html`${esc(l)}`)

  // 1) Upload the APK documents first, remembering their post ids for the links.
  const apkPosts: { file: string, id: number }[] = []
  for (const file of info.apkFiles) {
    const msg = await tg.sendMedia(channel, {
      type: 'document',
      file: `file:${join(artifactDir, file)}`,
      fileName: file,
      caption: html`#release <br/> <b>entinyGram v${info.verName}</b> (build ${info.buildNum}, ${abiOf(file)})`,
    })
    apkPosts.push({ file, id: msg.id })
  }

  // 2) Release banner photo (the "cover").
  const bannerPath = process.env.BANNER_PATH ?? resolve(process.cwd(), 'banner.png')
  try {
    await tg.sendMedia(channel, { type: 'photo', file: `file:${bannerPath}` })
  } catch (e) {
    console.warn('release: banner post failed (continuing):', e)
  }

  // 3) Full release message with download links + bilingual changelog.
  const downloadLinks = apkPosts.map(p => {
    const url = postUrl(p.id)
    return html`• <b><a href="${url}">Download ${abiOf(p.file)} APK</a></b>`
  })
  const enBlock = en ? joinTextWithEntities(bullets(en), '\n') : html`• See the channel`
  const ukBlock = uk ? joinTextWithEntities(bullets(uk), '\n') : html`• Дивіться канал`
  const extra = process.env.RELEASE_EXTRA ? esc(process.env.RELEASE_EXTRA).replace(/\n/g, '<br/>') : ''

  const release = html`
    #release
    <br/>
    📡 entinyGram <b>v${info.verName}</b> (build ${info.buildNum})
    <br/><br/>
    ${joinTextWithEntities(downloadLinks, '\n')}
    ${extra ? html`<br/>${extra}` : ''}
    <br/><br/>
    🇺🇸 EN:
    <br/>
    <blockquote expandable>
    ${enBlock}
    </blockquote>
    <br/><br/>
    🇺🇦 UK:
    <br/>
    <blockquote expandable>
    ${ukBlock}
    </blockquote>
    <br/><br/>
    @entinyGram
  `

  await tg.sendText(channel, release)
} finally {
  const exported = await tg.exportSession()
  if (exported !== cachedSession) {
    await persistSession(exported)
  }
  await tg.destroy()
}
