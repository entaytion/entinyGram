import { execFileSync } from 'child_process'

// entiny: a base that fell out of history (CI commit never reached main) must not collapse the list to one commit
const FALLBACK_COUNT = 30

const base = process.argv[2]
const head = process.argv[3]

function inHistory(sha: string) {
  try {
    execFileSync('git', ['cat-file', '-e', `${sha}^{commit}`], { stdio: 'ignore' })
    return true
  } catch {
    return false
  }
}

const args = ['log', '--first-parent']
if (base && base !== head && inHistory(base)) {
  args.push(`${base}..${head}`)
} else if (base !== head) {
  console.error(`get-commits: base "${base ?? ""}" is not in history, using the last ${FALLBACK_COUNT} commits`)
  args.push(`-${FALLBACK_COUNT}`, head)
} else {
  args.push('-1', head)
}

// %ae is the stable identity; author names have several spellings per person.
args.push('--pretty=format:%H%x00%ae%x00%an%x00%B%x00%x00')

const log = execFileSync('git', args, { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 })

const commits = log.split('\0\0').filter(Boolean).map(c => {
  const [sha, email, author, ...body] = c.split('\0')
  return {
    sha: (sha ?? '').trim(),
    author: (author ?? '').trim(),
    email: (email ?? '').trim(),
    message: body.join('\0').trim()
  }
}).filter(c => c.sha && c.email !== 'ci@entinygram')

console.log(JSON.stringify(commits))

