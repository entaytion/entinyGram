import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { $, chalk } from 'zx'
import { rootDir } from './config.js'

$.verbose = false

// hunk headers carry line numbers that shift on every re-export even when the
// content is identical; strip them so only real content diffs remain.
function normalize(patch: string) {
  return patch.replace(/^@@ -[\d,]+ \+[\d,]+ @@/gm, '@@')
}

// the +/- lines alone, keyed by file, ignoring context, hunk headers and the diffstat
function extractChanges(patch: string) {
  const out: string[] = []
  let file = ''
  let inHunk = false
  for (const line of patch.split('\n')) {
    if (line.startsWith('+++ b/')) {
      file = line.slice(6)
      inHunk = false
    } else if (line.startsWith('@@')) {
      inHunk = true
    } else if (inHunk && (line.startsWith('+') || line.startsWith('-'))) {
      out.push(`${file}\t${line}`)
    } else if (line.startsWith('diff --git ')) {
      inHunk = false
    }
  }
  return out.join('\n')
}

const git = $({ cwd: rootDir })

const names = (await git`git diff --name-only -- patches/*.patch`).stdout.split('\n').filter(Boolean)

const contentChanged: string[] = []
const contextMoved: string[] = []
for (const name of names) {
  if (!existsSync(join(rootDir, name))) continue // skip deleted
  const [current, old] = await Promise.all([
    git`cat ${name}`,
    git`git show HEAD:${name}`,
  ])

  if (normalize(old.stdout) === normalize(current.stdout)) continue

  if (extractChanges(old.stdout) === extractChanges(current.stdout)) {
    contextMoved.push(name)
  } else {
    contentChanged.push(name)
  }
}

function printGroup(label: string, group: string[]) {
  if (group.length === 0) return
  console.error(chalk.blue('==>'), `${label} (${group.length})`)
  for (const name of group) console.log(name)
}

printGroup('content changed', contentChanged)
printGroup('context moved', contextMoved)
console.error(
  chalk.blue('==>'),
  `${contentChanged.length} content, ${contextMoved.length} context-only of ${names.length} changed patches`,
)
