import fs from 'node:fs/promises'

const props = await fs.readFile('worktree/gradle.properties', 'utf8')
const appVerName = /^APP_VERSION_NAME=(.+)$/m.exec(props)?.[1]
if (!appVerName) throw new Error('failed to read APP_VERSION_NAME')
const appVerCode = /^APP_VERSION_CODE=(\d+)$/m.exec(props)?.[1]
if (!appVerCode) throw new Error('failed to read APP_VERSION_CODE')

const buildNum = Number(process.env.INU_BUILD ?? '0')
if (!Number.isInteger(buildNum) || buildNum < 0) {
  throw new Error(`invalid INU_BUILD: ${process.env.INU_BUILD}`)
}

const sha = process.env.GITHUB_SHA ?? ''
const shortSha = sha.slice(0, 7)
const verName = `${appVerName}-${shortSha}`

const now = new Date()
const y4 = String(now.getUTCFullYear())
const mm = String(now.getUTCMonth() + 1).padStart(2, '0')
const dd = String(now.getUTCDate()).padStart(2, '0')
const date = `${y4}${mm}${dd}`
const yyyymmdd = Number(date)

const tag = buildNum > 0 ? `v${appVerName}-${buildNum}` : `v${appVerName}`

// versionCode = YYYYMMDD (8 digits) * 100 + dailyCounter(1 digit) * 10 + variant(1=full, 0=lite).
// buildNum is bumped +1 on every release (see apk.yml "Bump build variables"), so a
// same-day bugfix release still gets a strictly higher code -- as long as fewer than
// 10 releases land on the same UTC day, which dailyCounter = buildNum % 10 covers.
// 10 digits total, stays under Play's 2.1B versionCode ceiling until the year 2100.
const dailyCounter = buildNum % 10
const verCodeBase = yyyymmdd * 100 + dailyCounter * 10
const verCodeFull = verCodeBase + 1
const verCodeLite = verCodeBase + 0

const out = {
  'app-ver-name': appVerName,
  'app-ver-code': appVerCode,
  'build-num': String(buildNum),
  'ver-name': verName,
  'ver-code-full': String(verCodeFull),
  'ver-code-lite': String(verCodeLite),
  date,
  // the in-app updater (UpdateHelper.kt) parses the versionCode straight out of the
  // filename -- it must not re-derive the date-based formula on-device.
  'apk-arm64-full': `entinygram-arm64-full-${appVerName}-${verCodeFull}.apk`,
  'apk-arm64-lite': `entinygram-arm64-lite-${appVerName}-${verCodeLite}.apk`,
  tag,
}

const githubOutput = process.env.GITHUB_OUTPUT
if (githubOutput) {
  const lines = `${Object.entries(out).map(([k, v]) => `${k}=${v}`).join('\n')}\n`
  await fs.appendFile(githubOutput, lines)
}

console.log(JSON.stringify(out, null, 2))
