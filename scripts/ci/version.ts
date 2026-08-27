import fs from 'node:fs/promises'

const props = await fs.readFile('worktree/gradle.properties', 'utf8')
const appVerName = /^APP_VERSION_NAME=(.+)$/m.exec(props)?.[1]
if (!appVerName) throw new Error('failed to read APP_VERSION_NAME')
const appVerCode = /^APP_VERSION_CODE=(\d+)$/m.exec(props)?.[1]
if (!appVerCode) throw new Error('failed to read APP_VERSION_CODE')

const sha = process.env.GITHUB_SHA ?? ''
const shortSha = sha.slice(0, 7)
const verName = `${appVerName}-${shortSha}`

const now = new Date()
const y4 = String(now.getUTCFullYear())
const mm = String(now.getUTCMonth() + 1).padStart(2, '0')
const dd = String(now.getUTCDate()).padStart(2, '0')
const date = `${y4}${mm}${dd}`
const yyyymmdd = Number(date)

// dailyCounter = how many releases have already shipped today (0-indexed), persisted across
// runs as INU_DAY_STATE = "YYYYMMDD:N" (see apk.yml "Bump build variables"). Resets to 0 the
// first time `date` changes -- this is a real same-day release slot: 00, 01, 02, ...
const dayState = process.env.INU_DAY_STATE ?? ''
const [dayStateDate, dayStateCounterRaw] = dayState.split(':')
const dailyCounter = dayStateDate === date ? Number(dayStateCounterRaw ?? '0') : 0
if (!Number.isInteger(dailyCounter) || dailyCounter < 0) {
  throw new Error(`invalid INU_DAY_STATE: ${dayState}`)
}
if (dailyCounter > 9) {
  throw new Error(`10+ releases already shipped today (dailyCounter=${dailyCounter}) -- versionCode slot exhausted, wait for UTC midnight`)
}

// tag/release-list id: YYYYMMDD + dailyCounter, e.g. 202608270, 202608271, ...
const tag = `v${appVerName}-${date}${dailyCounter}`

// versionCode = YYYYMMDD (8 digits) * 100 + dailyCounter(1 digit) * 10 -- single arm64 build,
// no lite variant, so the trailing digit is always 0 (kept as a spare digit, not a flag).
// 10 digits total, stays under Play's 2.1B versionCode ceiling until the year 2100.
const verCode = yyyymmdd * 100 + dailyCounter * 10

const out = {
  'app-ver-name': appVerName,
  'app-ver-code': appVerCode,
  'ver-name': verName,
  'ver-code': String(verCode),
  'day-counter': String(dailyCounter),
  date,
  // the in-app updater (UpdateHelper.kt) parses the versionCode straight out of the
  // filename -- it must not re-derive the date-based formula on-device.
  'apk-arm64': `entinygram-arm64-${appVerName}-${verCode}.apk`,
  tag,
}

const githubOutput = process.env.GITHUB_OUTPUT
if (githubOutput) {
  const lines = `${Object.entries(out).map(([k, v]) => `${k}=${v}`).join('\n')}\n`
  await fs.appendFile(githubOutput, lines)
}

console.log(JSON.stringify(out, null, 2))
