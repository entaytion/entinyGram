import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'

/**
 * Publishes the changelog card and the full notes to the entinyGram site. Best effort: a missing token or an
 * unreachable site must never fail a release.
 *
 *   CHANGELOG_TOKEN - shared bearer secret, also set on the site
 *   SITE_API_URL    - optional, defaults to the production endpoint
 *   RELEASE_TAG     - tag of the GitHub release, used for the release link
 *
 * Usage: bun assets/release/site.ts [out dir]
 */

const outDir = resolve(process.argv[2] ?? 'out')
const token = process.env.CHANGELOG_TOKEN
if (!token) {
  console.warn('site: CHANGELOG_TOKEN not set, skipping')
  process.exit(0)
}

try {
  const info = JSON.parse(await fs.readFile(join(outDir, 'build-info.json'), 'utf8'))
  const notes = JSON.parse(await fs.readFile(join(outDir, 'release-notes.json'), 'utf8'))
  const tag = process.env.RELEASE_TAG ?? ''
  const res = await fetch(process.env.SITE_API_URL ?? 'https://entaytion.is-a.dev/api/entinygram/changelog', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      version: tag ? tag.replace(/^v/, '') : info.verName,
      prerelease: notes.track === 'beta',
      url: tag ? `https://github.com/${info.repo}/releases/tag/${tag}` : '',
      site: notes.site,
      md: notes.md,
    }),
    signal: AbortSignal.timeout(20000),
  })
  console.log(`site: ${res.status} ${await res.text()}`)
} catch (e) {
  console.warn(`site: failed (${e})`)
}
