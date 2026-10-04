import fs from 'node:fs/promises'
import { join, resolve } from 'node:path'

/**
 * Publishes the website changelog card (`site` card and the full `en`/`uk` notes from release-notes.json) to the entinyGram site.
 * Best-effort: a missing token or an unreachable site must never fail a release.
 *
 *   CHANGELOG_TOKEN - shared bearer secret (also set on the site)
 *   SITE_API_URL    - optional, defaults to the production endpoint
 *   RELEASE_TAG     - tag of the GitHub release, used for the "full release" link
 */

const artifactDir = resolve(process.argv[2] ?? 'out')
const token = process.env.CHANGELOG_TOKEN
if (!token) {
  console.warn('publish-site: CHANGELOG_TOKEN not set, skipping')
  process.exit(0)
}

try {
  const info = JSON.parse(await fs.readFile(join(artifactDir, 'build-info.json'), 'utf8'))
  const notes = JSON.parse(await fs.readFile(join(artifactDir, 'release-notes.json'), 'utf8'))
  if (!notes.site) {
    console.warn('publish-site: release-notes.json has no site card, skipping')
    process.exit(0)
  }
  const tag = process.env.RELEASE_TAG ?? ''
  const res = await fetch(process.env.SITE_API_URL ?? 'https://entaytion.is-a.dev/api/entinygram/changelog', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({
      version: tag && !/^[0-9a-f]{40}$/.test(tag) ? tag.replace(/^v/, '') : info.verName,
      prerelease: process.env.PRE_RELEASE === 'true',
      url: tag && !/^[0-9a-f]{40}$/.test(tag) ? `https://github.com/${info.repo}/releases/tag/${tag}` : '',
      site: notes.site,
      md: { en: notes.en, uk: notes.uk },
    }),
    signal: AbortSignal.timeout(20000),
  })
  console.log(`publish-site: ${res.status} ${await res.text()}`)
} catch (e) {
  console.warn(`publish-site: failed (${e})`)
}
