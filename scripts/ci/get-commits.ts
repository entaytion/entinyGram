import { execSync } from 'child_process'

const base = process.argv[2]
const head = process.argv[3]
const range = base && base !== head ? `${base}..${head}` : `-1 ${head}`

// %H is full commit hash, %B is raw body (subject and body)
// We use \0 to separate the hash from the message, and \0\0 to separate commits
const log = execSync(`git log ${range} --pretty=format:%H%x00%B%x00`, { encoding: 'utf8' })

const commits = log.split('\0\0').filter(Boolean).map(c => {
  const idx = c.indexOf('\0')
  return {
    sha: c.slice(0, idx).trim(),
    message: c.slice(idx + 1).trim()
  }
})

console.log(JSON.stringify(commits))
