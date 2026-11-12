import { existsSync } from 'node:fs'
import fs from 'node:fs/promises'
import { formatTrackState, nextTrack, parseTrackState, type Track } from './lib'

// entiny: plugin-only matrix legs have no pluginless worktree, the version props are identical in both
const worktree = existsSync('worktree-pluginless/gradle.properties') ? 'worktree-pluginless' : 'worktree'
const props = await fs.readFile(`${worktree}/gradle.properties`, 'utf8')
const base = /^APP_VERSION_NAME=(.+)$/m.exec(props)?.[1]
const appVerCode = /^APP_VERSION_CODE=(\d+)$/m.exec(props)?.[1]
if (!base || !appVerCode) throw new Error('failed to read APP_VERSION_NAME / APP_VERSION_CODE')

const track: Track = process.env.PRERELEASE === 'true' ? 'beta' : 'release'
const shortSha = (process.env.GITHUB_SHA ?? '').slice(0, 7)

const now = new Date()
const date = `${now.getUTCFullYear()}${String(now.getUTCMonth() + 1).padStart(2, '0')}${String(now.getUTCDate()).padStart(2, '0')}`

// INU_DAY_STATE = "YYYYMMDD:N", N is how many builds shipped today (the versionCode slot)
const [dayDate, dayRaw] = (process.env.INU_DAY_STATE ?? '').split(':')
const dayCounter = dayDate === date ? Number(dayRaw ?? '0') : 0
if (!Number.isInteger(dayCounter) || dayCounter < 0) throw new Error(`invalid INU_DAY_STATE: ${process.env.INU_DAY_STATE}`)
if (dayCounter > 9) throw new Error(`10+ builds already shipped today (${dayCounter}), versionCode slot exhausted, wait for UTC midnight`)

const rawTrack = process.env.INU_TRACK_STATE ?? ''
const prev = parseTrackState(rawTrack)
if (rawTrack.trim() && !prev) throw new Error(`invalid INU_TRACK_STATE: ${rawTrack}`)
const next = nextTrack(prev, base, track)
const n = next[track]

// versionCode = YYYYMMDD * 100 + dayCounter * 10; the updater reads it from the file name, never re-derives it
const verCode = Number(date) * 100 + dayCounter * 10
const verName = `${base}-${shortSha}`
const label = `${track} ${n}`
const infix = track === 'beta' ? '-beta' : ''
const apk = (abi: string, plugins = false) => `entinygram${infix}${plugins ? '-plugins' : ''}-${abi}-${base}-${verCode}.apk`

const out = {
  'app-ver-name': base,
  'app-ver-code': appVerCode,
  'ver-name': verName,
  'ver-code': String(verCode),
  'day-counter': String(dayCounter),
  date,
  track,
  'track-number': String(n),
  label,
  'track-state': formatTrackState(next),
  'apk-arm64': apk('arm64'),
  'apk-arm7': apk('armeabi-v7a'),
  'apk-plugins': apk('arm64', true),
  'apk-arm7-plugins': apk('armeabi-v7a', true),
  tag: `v${base}-${track}.${n}`,
}

await fs.mkdir('out', { recursive: true })
await fs.writeFile('out/version.json', `${JSON.stringify(out, null, 2)}\n`)
if (process.env.GITHUB_OUTPUT) {
  await fs.appendFile(process.env.GITHUB_OUTPUT, `${Object.entries(out).map(([k, v]) => `${k}=${v}`).join('\n')}\n`)
}
console.log(JSON.stringify(out, null, 2))
