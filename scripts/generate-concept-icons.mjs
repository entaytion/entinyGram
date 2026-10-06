// Renders the launcher "concept" icons from src/res/launcher/concepts/svg.
// Each SVG becomes a 432x432 full-bleed adaptive-icon background (the 100x100 artboard fills the 288px
// visible area, its background continues into the margin), plus an adaptive-icon XML. Run manually after changing the SVGs; output is committed.
// Needs Chrome/Edge: set CHROME_PATH if it is not in a default location.
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const root = resolve(import.meta.dirname, '..')
const svgDir = join(root, 'src/res/launcher/concepts/svg')
const pngDir = join(root, 'src/res/launcher/concepts/generated/png')
const xmlDir = join(root, 'src/res/launcher/concepts/generated/mipmap')
const chrome = [
  process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
].find((p) => p && existsSync(p))
if (!chrome) throw new Error('Chrome not found, set CHROME_PATH')

const SIZE = 432
mkdirSync(pngDir, { recursive: true })
mkdirSync(xmlDir, { recursive: true })

for (const file of readdirSync(svgDir).filter((f) => f.endsWith('.svg'))) {
  const slug = file.replace('.svg', '')
  // the art keeps a 100x100 artboard but its background runs on to -25..125, so the 432px canvas
  // (288px visible + 72px margin per side) is plain artwork, no faked bleed
  const svg = readFileSync(join(svgDir, file), 'utf8').replace('viewBox="0 0 100 100"', `viewBox="-25 -25 150 150" width="${SIZE}" height="${SIZE}"`)
  const data = `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`
  const html = `<!doctype html><html><body style="margin:0;width:${SIZE}px;height:${SIZE}px;overflow:hidden;background:#000"><img src="${data}" style="display:block;width:${SIZE}px;height:${SIZE}px"></body></html>`
  const page = join(tmpdir(), `concept-${slug}.html`)
  writeFileSync(page, html)
  execFileSync(chrome, [
    '--headless', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
    `--window-size=${SIZE},${SIZE}`, '--virtual-time-budget=3000',
    `--screenshot=${join(pngDir, `icon_bg_concept_${slug}.png`)}`, pathToFileURL(page).href,
  ], { stdio: 'ignore' })

  writeFileSync(join(xmlDir, `ic_launcher_concept_${slug}.xml`), `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/icon_bg_concept_${slug}" />
    <foreground android:drawable="@drawable/icon_foreground_blank" />
    <monochrome android:drawable="@drawable/icon_plane_inu" />
</adaptive-icon>
`)
  console.log('✓', slug)
}
