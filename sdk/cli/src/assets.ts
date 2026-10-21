import type { Plugin as EsbuildPlugin } from 'esbuild'
import { createHash } from 'node:crypto'
import fs from 'node:fs/promises'
import { basename, extname } from 'node:path'

/** keep in step with the `declare module` list in `@inugram/plugin-types` */
const MIME_TYPES: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
  tgs: 'application/x-tgsticker',
  mp4: 'video/mp4',
  webm: 'video/webm',
  mp3: 'audio/mpeg',
  ogg: 'audio/ogg',
  opus: 'audio/ogg',
  wav: 'audio/wav',
  m4a: 'audio/mp4',
  ttf: 'font/ttf',
  otf: 'font/otf',
  woff: 'font/woff',
  woff2: 'font/woff2',
  bin: 'application/octet-stream',
  dex: 'application/vnd.android.dex',
}

const ASSET_FILTER = new RegExp(`\\.(?:${Object.keys(MIME_TYPES).join('|')})$`, 'i')
const FOOTER_START = '/* ==InuAssets=='
const FOOTER_END = '==/InuAssets== */'
const LINE_LENGTH = 120

interface Asset {
  mime: string
  data: Buffer
}

function stripFooter(code: string): string {
  if (!code.trimEnd().endsWith(FOOTER_END)) return code
  const start = code.lastIndexOf(`\n${FOOTER_START}`)
  return start === -1 ? code : code.slice(0, start + 1)
}

function renderFooter(assets: Map<string, Asset>): string {
  if (assets.size === 0) return ''
  const lines = [FOOTER_START]
  // onLoad runs in parallel, so insertion order would make the output differ between builds
  const sorted = [...assets].sort(([a], [b]) => (a < b ? -1 : 1))
  for (const [name, asset] of sorted) {
    lines.push(`@asset ${name} ${asset.mime}`)
    const data = asset.data.toString('base64')
    for (let i = 0; i < data.length; i += LINE_LENGTH) lines.push(data.slice(i, i + LINE_LENGTH))
  }
  lines.push(FOOTER_END)
  return `\n${lines.join('\n')}\n`
}

/**
 * Compiles binary imports to `inu.assets.get(name)` and appends their bytes to the output as the
 * asset footer, which the app splits off before running the code.
 */
export function bundleAssets(): EsbuildPlugin {
  return {
    name: 'inu-assets',
    setup(build) {
      const assets = new Map<string, Asset>()
      build.onStart(() => {
        assets.clear()
      })
      build.onLoad({ filter: ASSET_FILTER }, async (args) => {
        const data = await fs.readFile(args.path)
        const extension = extname(args.path)
        const hash = createHash('sha256').update(data).digest('hex').slice(0, 8)
        const name = encodeURI(`${basename(args.path, extension)}-${hash}${extension}`).replaceAll('*', '%2A')
        assets.set(name, { mime: MIME_TYPES[extension.slice(1).toLowerCase()], data })
        return {
          contents: `export default inu.assets.get(${JSON.stringify(name)})`,
          loader: 'js',
          watchFiles: [args.path],
        }
      })
      build.onEnd(async (result) => {
        const outFile = build.initialOptions.outfile
        if (result.errors.length > 0 || build.initialOptions.write === false || outFile === undefined) return
        // a rebuild may leave an unchanged output in place, footer included
        const code = stripFooter(await fs.readFile(outFile, 'utf8'))
        await fs.writeFile(outFile, code + renderFooter(assets))
      })
    },
  }
}
