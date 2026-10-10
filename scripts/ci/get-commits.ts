import { execFileSync } from 'child_process'

const base = process.argv[2]
const head = process.argv[3]

const args = ['log', '--first-parent']
if (base && base !== head) args.push(`${base}..${head}`)
else args.push('-1', head)

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
}).filter(c => c.sha)

console.log(JSON.stringify(commits))
