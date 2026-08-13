import fs from 'node:fs/promises'
import { join } from 'node:path'
import { worktreeDir } from './config.js'
import { cd, step, success } from './lib.js'

const TARGET_PACKAGE = 'ua.entaytion.entinygram'
const SOURCE_PACKAGE = 'desu.inugram'

const GOOGLE_SERVICES_FILES = [
  'TMessagesProj/google-services.json',
  'TMessagesProj_App/google-services.json',
  'TMessagesProj_AppHockeyApp/google-services.json',
  'TMessagesProj_AppHuawei/google-services.json',
  'TMessagesProj_AppStandalone/google-services.json',
]

async function ensureGoogleServicesBranding() {
  const repo = cd(worktreeDir)
  let updatedCount = 0

  for (const file of GOOGLE_SERVICES_FILES) {
    const absPath = join(worktreeDir, file)
    try {
      const stat = await fs.lstat(absPath).catch(() => null)
      if (!stat || !stat.isFile() || stat.isSymbolicLink()) {
        continue
      }
      
      const content = await fs.readFile(absPath, 'utf8')
      if (content.includes(SOURCE_PACKAGE)) {
        step(`Updating branding in ${file}`)
        const updated = content.replaceAll(SOURCE_PACKAGE, TARGET_PACKAGE)
        await fs.writeFile(absPath, updated, 'utf8')
        updatedCount++
      }

      // Ensure skip-worktree is set so upstream merges don't overwrite it
      // Using forward slashes for Git on Windows
      const gitPath = file.replaceAll('\\', '/')
      await repo`git update-index --skip-worktree ${gitPath}`
    } catch (err) {
      console.warn(`Warning: Could not process ${file}: ${err}`)
    }
  }
  
  if (updatedCount > 0) {
    success(`Updated branding in ${updatedCount} google-services.json file(s)`)
  } else {
    step('All google-services.json files are already up to date')
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
