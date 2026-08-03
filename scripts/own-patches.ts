import { getAppliedPatchNames, step, success, warn } from './lib.js'
import { $ } from 'zx'
import { worktreeDir } from './config.js'

// zx on Windows must run commands via cmd.exe, not WSL (which may lack a distro).
$.verbose = false
if (process.platform === 'win32') {
  $.shell = 'cmd.exe'
  $.prefix = ''
}

// Your commit identity — patches authored by these emails move into `entiny/`.
const OWN_AUTHORS = new Set<string>(['entaytion@gmail.com'])

const apply = process.argv.includes('--apply')

if (process.argv.includes('--help') || process.argv.includes('-h')) {
  console.log('own-patches: move patches you authored into the entiny/ namespace.')
  console.log('  bun run own-patches            # dry-run: list what would move')
  console.log('  bun run own-patches --apply    # perform the renames')
  console.log('Then run `bun run export` to rewrite patches/ + series.')
  process.exit(0)
}

// Applied patches form a linear stack on top of the base, so the N most recent
// commits of HEAD are exactly the N applied patches, in reverse order.
const patchNames = await getAppliedPatchNames(worktreeDir) // bottom -> top
const n = patchNames.length
const git = $({ cwd: worktreeDir })
const rows = (await git`git log -${n} --no-merges --format=%H%x09%ae HEAD`)
  .stdout.trim()
  .split(/\r?\n/)
  .filter(Boolean)
  .reverse() // now bottom -> top

const own: Array<{ index: number; name: string; email: string }> = []
for (let i = 0; i < patchNames.length; i++) {
  const email = rows[i]?.split('\t')[1]?.trim().toLowerCase()
  if (email && OWN_AUTHORS.has(email)) {
    own.push({ index: i, name: patchNames[i], email })
  }
}

if (own.length === 0) {
  success('No own-authored patches found')
  process.exit(0)
}

step(`Found ${own.length} own-authored ${own.length === 1 ? 'patch' : 'patches'}` + (apply ? '' : ' (dry-run)'))

for (const p of own) {
  const bare = p.name.includes('__') ? p.name.split('__').slice(1).join('__') : p.name
  const newName = `entiny__${bare}`
  if (apply) {
    await git`stg rename ${p.name} ${newName}`
    success(`${p.name}  ->  ${newName}`)
  } else {
    console.log(`  ${p.name}  ->  ${newName}`)
  }
}

if (!apply) {
  warn('Dry-run. Run with --apply to perform, then `bun run export`.')
}
