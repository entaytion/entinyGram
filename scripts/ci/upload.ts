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
 * Finds the most recent message in the CI channel that carries the `#release` hashtag.
 * Used to generate the "The last release — download" footer link.
 * Returns the message id, or null if not found / search failed.
 */
async function findLastReleaseMessageId(): Promise<number | null> {
  try {
    // searchMessages returns an ArrayPaginated (array-like), not an async iterable.
    // We search the CI channel for '#release' and pick the first result that is a
    // proper stable release (has #release but NOT #prerelease in its text).
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

  /**
   * Converts a plain-text changelog block to mtcute entities.
   *
   * Handles:
   *   - Markdown links:  [text](url)  → <a href="url">text</a>
   *   - Markdown bold:   **text**     → <b>text</b>
   *   - Everything else is HTML-escaped and passed through as-is.
   *
   * The AI may emit tg://entinySettings/<slug> links and **bold** spans in the tg_uk
   * line when it finds a close match in the settings registry / wants to anchor a
   * flagship feature name — this parser renders them as proper Telegram entities.
   */
  function notesToEntities(text: string) {
    const lines = text.split('\n').map(l => l.trim()).filter(Boolean)
    const htmlLines = lines.map(line => {
      // Split the line on [text](url) and **text** occurrences (in order) and
      // reassemble as HTML.
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
  // CI channel caption is English — UpdateHelper.kt's extractApkInfo/applyUpdate clips the
  // update-dialog text to exactly this <blockquote> entity, so whatever goes here is what
  // shows up in the on-device update dialog too. Fall back to Ukrainian only if no EN notes.
  const ciHtml = enHtml ?? ukHtml

  const isPreRelease = process.env.PRE_RELEASE === 'true'

  // Pre-releases now share the main app's applicationId (see apk.yml), so they install *over*
  // the main app (same package) instead of side-by-side -- call that out explicitly, including a
  // one-time note for testers who still have the old separate .beta app from before this change.
  const preReleaseBanner = isPreRelease
    ? html`🧪 <b>PRE-RELEASE BUILD</b><blockquote>⚠️ Test build for fixing reported bugs — expect instability and possible bugs\n📦 Installs <b>over your main entinyGram app</b> (same package), not as a separate app\n♻️ Still have the old separate .beta app? Uninstall it manually once — this build updates the main app only</blockquote><br/>`
    : ''

  // ── Last stable release link ─────────────────────────────────────────────────
  // Shown in the CI channel caption between the changelog blockquote and the hashtag line.
  // For a prerelease: search the CI channel for the most recent non-prerelease #release message.
  // For a stable release: same — search for the previous release (we haven't posted yet, so the
  // most recent one in the channel is still the previous stable release).
  // If the search fails or finds nothing, the line is omitted gracefully.
  const lastReleaseId = await findLastReleaseMessageId()
  const lastReleaseHtml = lastReleaseId !== null
    ? html`<br/>⬇️ The last release — <a href="${postUrl(lastReleaseId)}">download</a>`
    : ''

  // ── GitHub compare link ─────────────────────────────────────────────────────
  // The GitHub release itself already gets a "Full Changelog: .../compare/prev...tag" line
  // (see the "Create GitHub release" step in apk.yml, which exports both tags via $GITHUB_ENV
  // ahead of this script). Mirrored here so the CI channel — where testers actually watch for
  // builds — always has the same full-diff link, not just the AI-trimmed changelog above.
  // Omitted gracefully if either tag is missing (e.g. a manual/local run) or there's no prior tag.
  const releaseTagName = process.env.RELEASE_TAG ?? ''
  const prevReleaseTag = process.env.PREV_RELEASE_TAG ?? ''
  const compareHtml = releaseTagName && prevReleaseTag && prevReleaseTag !== releaseTagName
    ? html`<br/>📝 <a href="https://github.com/${info.repo}/compare/${prevReleaseTag}...${releaseTagName}">Full diff on GitHub</a>`
    : ''

  // 1) Upload the APK document to the CI channel — always happens. The changelog goes in a
  // <blockquote>: UpdateHelper.kt's extractApkInfo/applyUpdate clips the update-dialog text to
  // exactly this entity, discarding the #release/label wrapper text around it.
  //
  // Tag is #release XOR #prerelease (never both) — UpdateHelper.kt's on-device update checker
  // searches the CI channel by this tag to decide what to offer a given user. Regular users only
  // ever search #release; only users who opted into the beta toggle also search #prerelease. A
  // pre-release build must never also carry #release, or it would get offered to everyone.
  const releaseTag = isPreRelease ? '#prerelease' : '#release'
  const { file } = info.apkFiles[0]

  // Construct caption safely respecting Telegram's 1024 character limit for media captions
  const buildCaption = (notesEntity: ReturnType<typeof html>) => html`<b>entinyGram v${info.verName}</b> (build ${info.buildDate})<br/><br/>${preReleaseBanner}<blockquote>${notesEntity}</blockquote>${lastReleaseHtml}${compareHtml}<br/>🏷️ ${releaseTag} • @entinyGram • @entinyGramChat`

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

  // 2) If --ci-only, stop here — no main channel post. Pre-releases are always ci-only (see
  // apk.yml), so this is also where their message content lives -- the main-channel blocks below
  // never run for a pre-release.
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

    // Telegram's sendText limit is 4096 chars. If the combined post exceeds 3800 chars,
    // split into Post 1 (Ukrainian + download link) and Post 2 (English reply).
    if (release.text.length <= 3800 || !enHtml) {
      await tg.sendText(channelMain, release)
    } else {
      console.log('Main channel post is long; splitting into Ukrainian post and English reply...')
      const post1Blocks = [
        html`📡 <b>entinyGram v${info.verName}</b> (build ${info.buildDate}) — ${linksHtml}`,
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
