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
const date = [
  String(now.getUTCFullYear()),
  String(now.getUTCMonth() + 1).padStart(2, '0'),
  String(now.getUTCDate()).padStart(2, '0'),
].join('')

const suffix = buildNum > 0 ? `-${buildNum}` : ''
const tag = buildNum > 0 ? `v${appVerName}-${buildNum}` : `v${appVerName}`

const out = {
  'app-ver-name': appVerName,
  'app-ver-code': appVerCode,
  'build-num': String(buildNum),
  'ver-name': verName,
  'ver-code': String(buildNum + 1),
  date,
  'apk-arm64': `entinygram-arm64-${date}${suffix}.apk`,
  'apk-universal': `entinygram-universal-${date}${suffix}.apk`,
  tag,
}

const githubOutput = process.env.GITHUB_OUTPUT
if (githubOutput) {
  const lines = `${Object.entries(out).map(([k, v]) => `${k}=${v}`).join('\n')}\n`
  await fs.appendFile(githubOutput, lines)
}

console.log(JSON.stringify(out, null, 2))
