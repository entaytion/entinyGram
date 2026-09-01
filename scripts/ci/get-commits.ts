import { execSync } from 'child_process'

const base = process.argv[2]
const head = process.argv[3]
const range = base && base !== head ? `${base}..${head}` : `-1 ${head}`

// %H is full commit hash, %an is author name, %B is raw body (subject and body)
// We use \0 to separate fields, and \0\0 to separate commits
const log = execSync(`git log --first-parent ${range} --pretty=format:%H%x00%an%x00%B%x00%x00`, { encoding: 'utf8' })

const commits = log.split('\0\0').filter(Boolean).map(c => {
  const first = c.indexOf('\0')
  if (first === -1) return null
  const second = c.indexOf('\0', first + 1)
  if (second === -1) {
    return {
      sha: c.slice(0, first).trim(),
      author: '',
      message: c.slice(first + 1).trim()
    }
  }
  return {
    sha: c.slice(0, first).trim(),
    author: c.slice(first + 1, second).trim(),
    message: c.slice(second + 1).trim()
  }
}).filter(Boolean)

console.log(JSON.stringify(commits))
