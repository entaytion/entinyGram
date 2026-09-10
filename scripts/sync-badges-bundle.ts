import fs from 'node:fs/promises'
import { join } from 'node:path'
import { rootDir } from './config.js'
import { step, success } from './lib.js'

/**
 * Refreshes the compiled-in badge fallback that BadgeRegistry.kt parses on first launch
 * (before its first network fetch) and offline. Run this after adding/removing a badge or
 * holder on entaytion-is.a.dev, so the bundled copy doesn't drift from the server - it used
 * to be a hand-typed `hashMapOf` in Kotlin, kept in sync with the DB by eyeballing a diff.
 *
 *   bun run scripts/sync-badges-bundle.ts
 */

const ENDPOINT = 'https://entaytion.is-a.dev/api/entinygram/badges'
const target = join(rootDir, 'src/res/raw/inu_badges_bundled.json')

step(`Fetching ${ENDPOINT}`)
const res = await fetch(ENDPOINT)
if (!res.ok) {
  throw new Error(`Fetch failed: ${res.status} ${res.statusText}`)
}
const manifest = await res.json()
if (!Array.isArray(manifest.badges) || !Array.isArray(manifest.holders)) {
  throw new Error('Response is missing badges[]/holders[] - refusing to overwrite the bundled fallback')
}

// Pretty-printed and newline-terminated so the diff stays reviewable.
await fs.writeFile(target, `${JSON.stringify(manifest, null, 2)}\n`)
success(`Wrote ${manifest.badges.length} badges / ${manifest.holders.length} holders to src/res/raw/inu_badges_bundled.json`)
