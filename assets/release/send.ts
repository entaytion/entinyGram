import { spawn } from 'node:child_process'
import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { InputMedia, MemoryStorage, TelegramClient, type InputRichMessageMedia, type RichMediaUploadCache } from '@mtcute/node'
import { flat, type Sections, type Track } from './lib'
import { fitCaption, mainPost, noteLines, richApks, richCiPost, richMainPost, richSections, type PostInfo } from './post'

interface ApkFile { file: string }
interface BuildInfo {
  verName: string
  base: string
  label: string
  track: Track
  apkFiles: ApkFile[]
  pluginsApk?: ApkFile | null
  pluginsArm7Apk?: ApkFile | null
  repo: string
}
interface Notes { commits: string[], uk: Sections['uk'], en: Sections['en'] }

/**
 * Uploads the APKs to the CI channel with a caption, and for a stable build run without --ci-only also posts to the main channel.
 * RICH_POST=1 posts one rich message per channel instead of the caption and text posts.
 * Usage: bun assets/release/send.ts [out dir] [--ci-only]
 */

const outDir = resolve(process.argv[2] ?? 'out')
const ciOnly = process.argv.includes('--ci-only')
const richMode = process.env.RICH_POST === '1'
const APK_MIME = 'application/vnd.android.package-archive'

const info: BuildInfo = JSON.parse(await fs.readFile(join(outDir, 'build-info.json'), 'utf8'))
const notes: Notes = JSON.parse(await fs.readFile(join(outDir, 'release-notes.json'), 'utf8'))
for (const { file } of [...info.apkFiles, info.pluginsApk, info.pluginsArm7Apk].filter((f): f is ApkFile => !!f)) {
  await fs.access(join(outDir, file))
}

const apiId = Number(process.env.TELEGRAM_API_ID)
const apiHash = process.env.TELEGRAM_API_HASH
const botToken = process.env.TELEGRAM_BOT_TOKEN
const channelCI = process.env.TELEGRAM_CI_CHANNEL ?? process.env.TELEGRAM_CHANNEL ?? 'entinyGramCI'
const channelMain = process.env.TELEGRAM_MAIN_CHANNEL ?? 'entinyGram'
if (!apiId || !apiHash || !botToken) throw new Error('TELEGRAM_API_ID, TELEGRAM_API_HASH and TELEGRAM_BOT_TOKEN must be set')

const cachedSession = process.env.MTPROTO_SESSION || undefined
const tg = new TelegramClient({ apiId, apiHash, storage: new MemoryStorage() })
if (cachedSession) {
  await tg.importSession(cachedSession, true)
  await tg.connect()
} else {
  await tg.start({ botToken })
}

async function persistSession(session: string) {
  const token = process.env.GH_VARS_TOKEN
  const repo = process.env.GITHUB_REPOSITORY
  if (!token || !repo) {
    console.warn('GH_VARS_TOKEN or GITHUB_REPOSITORY missing, skipping session persist')
    return
  }
  await new Promise<void>((res, rej) => {
    const p = spawn('gh', ['secret', 'set', 'MTPROTO_SESSION', '-R', repo], { env: { ...process.env, GH_TOKEN: token }, stdio: ['pipe', 'inherit', 'inherit'] })
    p.stdin.end(session)
    p.on('error', rej)
    p.on('exit', code => (code === 0 ? res() : rej(new Error(`gh exited ${code}`))))
  })
}

async function publishRich(post: PostInfo, files: string[]) {
  const apks = richApks(files)
  const attachments: Record<string, InputRichMessageMedia> = {}
  for (const apk of apks) {
    // @ts-expect-error mtcute 0.31 omits document inputs from its rich-media type
    attachments[apk.id] = InputMedia.document(`file:${join(outDir, apk.file)}`, { fileName: apk.file, fileMime: APK_MIME })
  }
  const uploadCache: RichMediaUploadCache = new Map()
  const written = info.track !== 'beta' && flat(notes.en).length > 0
  const sections = written
    ? richSections(notes.en, 'en', false)
    : [{ title: 'New', lines: notes.commits.length ? notes.commits : [`Update ${info.base}`] }]
  const uk = written ? richSections(notes.uk, 'uk', true) : []
  await tg.sendRichMessage(channelCI, { content: { type: 'markdown', content: richCiPost(post, sections, apks, undefined, uk), attachments }, uploadCache })

  if (ciOnly || info.track === 'beta') {
    console.log('CI channel only: skipping the main channel post.')
    return
  }
  const main = richMainPost(post, { uk: notes.uk, en: notes.en }, apks, process.env.RELEASE_EXTRA?.trim() ?? '')
  await tg.sendRichMessage(channelMain, { content: { type: 'markdown', content: main, attachments }, uploadCache })
}

try {
  const post: PostInfo = {
    base: info.base,
    label: info.label,
    track: info.track,
    repo: info.repo,
    tag: process.env.RELEASE_TAG,
    prevTag: process.env.PREV_RELEASE_TAG,
  }

  // display order: with plugins first
  const files = [info.pluginsApk?.file, info.pluginsArm7Apk?.file, info.apkFiles[0]?.file, info.apkFiles[1]?.file].filter((f): f is string => !!f)

  if (richMode) {
    await publishRich(post, files)
  } else {
    // beta shows the commit list, release the written notes
    const ciNotes = info.track === 'beta' || !flat(notes.en).length
      ? notes.commits.map(c => `– ${c}`)
      : noteLines(notes.en, 'en', false)
    const caption = fitCaption(post, ciNotes.length ? ciNotes : [`Update ${info.base}`])

    let firstId: number
    if (files.length === 1) {
      const msg = await tg.sendMedia(channelCI, { type: 'document', file: `file:${join(outDir, files[0])}`, fileName: files[0], caption })
      firstId = msg.id
    } else {
      // in an album each caption renders under its own file, so it goes on the last one
      const album = await tg.sendMediaGroup(channelCI, files.map((f, i) => ({
        type: 'document' as const,
        file: `file:${join(outDir, f)}`,
        fileName: f,
        ...(i === files.length - 1 ? { caption } : {}),
      })))
      firstId = album[0].id
    }

    if (ciOnly || info.track === 'beta') {
      console.log('CI channel only: skipping the main channel post.')
    } else {
      const { first, reply } = mainPost(post, { uk: notes.uk, en: notes.en }, `https://t.me/${channelCI}/${firstId}`, process.env.RELEASE_EXTRA?.trim() ?? '')
      const msg = await tg.sendText(channelMain, first)
      if (reply) await tg.sendText(channelMain, reply, { replyTo: msg.id })
    }
  }
} finally {
  const exported = await tg.exportSession()
  if (exported !== cachedSession) await persistSession(exported).catch(e => console.warn('failed to persist session:', e))
  await tg.destroy()
}
