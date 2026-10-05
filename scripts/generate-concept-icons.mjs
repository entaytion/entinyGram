// Renders the launcher "concept" icons from src/res/launcher/concepts/svg.
// Each SVG becomes a 432x432 full-bleed adaptive-icon background (the squircle sits
// in the 288px visible area, the margin is a blurred bleed of the same artwork),
// plus an adaptive-icon XML. Run manually after changing the SVGs; output is committed.
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
const VISIBLE = 288
mkdirSync(pngDir, { recursive: true })
mkdirSync(xmlDir, { recursive: true })

for (const file of readdirSync(svgDir).filter((f) => f.endsWith('.svg'))) {
  const slug = file.replace('.svg', '')
  const data = `data:image/svg+xml;base64,${Buffer.from(readFileSync(join(svgDir, file))).toString('base64')}`
  const html = `<!doctype html><html><body style="margin:0;width:${SIZE}px;height:${SIZE}px;overflow:hidden;position:relative;background:#000">
<img src="${data}" style="position:absolute;left:50%;top:50%;width:${SIZE * 3}px;height:${SIZE * 3}px;transform:translate(-50%,-50%);filter:blur(40px) saturate(1.1)">
<img src="${data}" style="position:absolute;left:${(SIZE - VISIBLE) / 2}px;top:${(SIZE - VISIBLE) / 2}px;width:${VISIBLE}px;height:${VISIBLE}px">
</body></html>`
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
