/**
 * dump-registry.ts
 *
 * Parses all *SettingsActivity.kt files (and FontStackActivity / FontsSettingsActivity)
 * to extract SearchRegistry.Page / SearchRegistry.Entry declarations, then resolves their
 * R.string.* titleRes values from src/res/values/strings_inu.xml.
 *
 * Writes `<artifactDir>/settings-registry.json`:
 *   [{ "slug": "appearance", "label": "Appearance" }, ...]
 *
 * Used by release-notes.ts to give the AI a mapping of feature slugs → English labels
 * so it can embed tg://entinySettings/<slug> deep links in changelog bullets.
 *
 * Usage:  bun run tsx scripts/dump-registry.ts [artifactDir=out]
 */

import fs from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { join, resolve, dirname } from 'node:path'

const artifactDir = resolve(process.argv[2] ?? 'out')
const __filename = fileURLToPath(import.meta.url)
const root = resolve(dirname(__filename), '..')

// ── 1. Parse strings_inu.xml (English values only) ──────────────────────────

const stringsXml = await fs.readFile(
  join(root, 'src/res/values/strings_inu.xml'),
  'utf8',
)

/** Map from R.string.Foo → its English text */
const strings: Record<string, string> = {}
for (const m of stringsXml.matchAll(/<string name="([^"]+)">([^<]*)<\/string>/g)) {
  // Unescape basic XML entities and Android escape sequences
  strings[m[1]] = m[2]
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/\\'/g, "'")
    .replace(/\\n/g, ' ')
    .trim()
}

// ── 2. Collect all *SettingsActivity.kt source files ────────────────────────

const settingsDir = join(root, 'src/kotlin/ui/settings')

async function findKtFiles(dir: string): Promise<string[]> {
  const results: string[] = []
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) results.push(...await findKtFiles(full))
    else if (entry.name.endsWith('.kt')) results.push(full)
  }
  return results
}

const ktFiles = await findKtFiles(settingsDir)

// ── 3. Extract (slug, R.string.Xxx) pairs from PAGE / Entry declarations ─────

interface RawEntry { slug: string; stringKey: string }

const rawEntries: RawEntry[] = []

// Match:  slug = "some-slug",  titleRes = R.string.SomeName
// across all SettingsActivity files.  PAGE and Entry blocks share the same
// named-argument format, so one regex covers both.
const SLUG_RE = /slug\s*=\s*"([^"]+)"/g
const TITLE_RE = /titleRes\s*=\s*R\.string\.(\w+)/g

for (const file of ktFiles) {
  const src = await fs.readFile(file, 'utf8')

  // Find all PAGE/Entry blocks by scanning for SearchRegistry.Page( and
  // SearchRegistry.Entry( then extracting the slug + titleRes from the same
  // constructor call block.  A simple approach: split on "SearchRegistry."
  // and parse each segment independently.
  const segments = src.split(/SearchRegistry\.(Page|Entry)\s*\(/)
  for (let i = 1; i < segments.length; i++) {
    const seg = segments[i]
    // Grab up to the first unbalanced ')' — naive but enough for well-formatted Kotlin
    let depth = 1
    let end = 0
    for (; end < seg.length && depth > 0; end++) {
      if (seg[end] === '(') depth++
      else if (seg[end] === ')') depth--
    }
    const block = seg.slice(0, end)

    // A Page constructor: Page(slug=..., titleRes=..., ...)
    // An Entry constructor: Entry("slug", R.string.Xxx, ...)  ← positional
    //                    or Entry(slug="slug", titleRes=R.string.Xxx, ...)
    let slug: string | undefined
    let stringKey: string | undefined

    // Named args
    const slugM = /slug\s*=\s*"([^"]+)"/.exec(block)
    const titleM = /titleRes\s*=\s*R\.string\.(\w+)/.exec(block)
    if (slugM) slug = slugM[1]
    if (titleM) stringKey = titleM[1]

    // Positional Entry args: Entry("round-recorder-zoom", R.string.InuRoundRecorderZoom, ...)
    if (!slug || !stringKey) {
      const posM = /^\s*"([^"]+)"\s*,\s*R\.string\.(\w+)/.exec(block)
      if (posM) {
        slug = posM[1]
        stringKey = posM[2]
      }
    }

    if (slug && stringKey) rawEntries.push({ slug, stringKey })
  }
}

// ── 4. Resolve string keys → labels and deduplicate ─────────────────────────

interface RegistryEntry { slug: string; label: string }

const seen = new Set<string>()
const registry: RegistryEntry[] = []

for (const { slug, stringKey } of rawEntries) {
  if (seen.has(slug)) continue
  seen.add(slug)
  const label = strings[stringKey]
  if (!label) {
    console.warn(`dump-registry: no string for ${stringKey} (slug: ${slug})`)
    continue
  }
  registry.push({ slug, label })
}

registry.sort((a, b) => a.slug.localeCompare(b.slug))

// ── 5. Write output ──────────────────────────────────────────────────────────

await fs.mkdir(artifactDir, { recursive: true })
const outPath = join(artifactDir, 'settings-registry.json')
await fs.writeFile(outPath, JSON.stringify(registry, null, 2))
console.log(`dump-registry: wrote ${registry.length} entries to ${outPath}`)
