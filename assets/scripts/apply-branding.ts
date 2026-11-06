import fs from 'node:fs/promises'
import { join } from 'node:path'
import { worktreeDir } from './config.js'
import { cd, step, success } from './lib.js'

const TARGET_PACKAGE = 'ua.entaytion.entinygram'

// HockeyApp/Huawei/Standalone app variants are removed by misc/remove-unused-* patches --
// only the main app modules still build and need a branded google-services.json.
const GOOGLE_SERVICES_FILES = [
  'TMessagesProj/google-services.json',
  'TMessagesProj_App/google-services.json',
]

// A real google-services.json is a Firebase console export tied to registered client_id/api_key
// values for that package -- a package-name string swap on an inugram (or stock Telegram) file
// can never produce a file Firebase actually recognizes. So this script does NOT generate/rewrite
// google-services.json content anymore. It only verifies the already-placed file is branded and
// (re)applies skip-worktree so `git checkout`/merges don't touch it.
//
// To (re)provision a branded file: download it from the Firebase console for the
// TARGET_PACKAGE app and copy it manually into place before running this script.
async function ensureGoogleServicesBranding() {
  const repo = cd(worktreeDir)
  let missing = 0

  for (const file of GOOGLE_SERVICES_FILES) {
    const absPath = join(worktreeDir, file)
    try {
      const stat = await fs.lstat(absPath).catch(() => null)
      if (!stat || !stat.isFile()) {
        continue
      }

      const content = await fs.readFile(absPath, 'utf8')
      if (!content.includes(TARGET_PACKAGE)) {
        missing++
        console.warn(`Warning: ${file} does not contain "${TARGET_PACKAGE}" -- copy a real Firebase-console export for that package into place before building.`)
        continue
      }

      // Ensure skip-worktree is set so upstream merges/checkouts don't overwrite it
      // Using forward slashes for Git on Windows
      const gitPath = file.replaceAll('\\', '/')
      await repo`git update-index --skip-worktree ${gitPath}`
    } catch (err) {
      console.warn(`Warning: Could not process ${file}: ${err}`)
    }
  }

  if (missing === 0) {
    success('All google-services.json files are branded and marked skip-worktree.')
  } else {
    step(`${missing} google-services.json file(s) still need a real branded export -- see warnings above.`)
  }
}

async function main() {
  step('Applying entinyGram branding...')
  await ensureGoogleServicesBranding()
  success('Branding applied successfully.')
}

main().catch(err => {
  console.error('Error applying branding:', err)
  process.exit(1)
})
