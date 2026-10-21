import { createHash } from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import { join } from 'node:path'
import { context } from 'esbuild'
import { expect, it } from 'vitest'
import { bundleAssets } from '../src/assets.js'

function hash(data: Buffer) {
  return createHash('sha256').update(data).digest('hex').slice(0, 8)
}

it('compiles binary imports to asset lookups and keeps one sorted footer across rebuilds', async () => {
  const root = await fs.mkdtemp(join(os.tmpdir(), 'inu-assets-'))
  try {
    await fs.mkdir(join(root, 'img'))
    const png = Buffer.alloc(200, 7)
    await fs.writeFile(join(root, 'img', 'my frame.png'), png)
    const font = Buffer.from([1, 2, 3])
    await fs.writeFile(join(root, 'font.woff2'), font)
    const otherFont = Buffer.from([4, 5, 6])
    await fs.writeFile(join(root, 'img', 'font.woff2'), otherFont)
    await fs.writeFile(join(root, 'a.ts'), 'import frame from \'./img/my frame.png\'\nimport font from \'./img/font.woff2\'\nexport const a = [frame, font]')
    await fs.writeFile(join(root, 'entry.ts'), 'import font from \'./font.woff2\'\nimport { a } from \'./a.js\'\nglobalThis.out = [a, font]')
    const outfile = join(root, 'out.js')
    const ctx = await context({
      absWorkingDir: root,
      entryPoints: [join(root, 'entry.ts')],
      outfile,
      bundle: true,
      format: 'esm',
      plugins: [bundleAssets()],
      logLevel: 'silent',
    })
    try {
      await ctx.rebuild()
      const first = await fs.readFile(outfile, 'utf8')
      await ctx.rebuild()
      expect(await fs.readFile(outfile, 'utf8')).toBe(first)

      const pngName = `my%20frame-${hash(png)}.png`
      expect(first).toContain(`inu.assets.get("${pngName}")`)
      expect(first).toContain(`inu.assets.get("font-${hash(font)}.woff2")`)
      expect(first).toContain(`inu.assets.get("font-${hash(otherFont)}.woff2")`)
      const footer = first.slice(first.indexOf('\n/* ==InuAssets==\n') + 1)
      expect(footer.endsWith('==/InuAssets== */\n')).toBe(true)
      const lines = footer.trimEnd().split('\n')
      expect(lines.filter(line => line.startsWith('@asset'))).toEqual([
        ...[`@asset font-${hash(font)}.woff2 font/woff2`, `@asset font-${hash(otherFont)}.woff2 font/woff2`].sort(),
        `@asset ${pngName} image/png`,
      ])
      expect(lines.every(line => line.length <= 120)).toBe(true)
      const pngData = lines.slice(lines.indexOf(`@asset ${pngName} image/png`) + 1, -1).join('')
      expect(Buffer.from(pngData, 'base64')).toEqual(png)

      await fs.writeFile(join(root, 'entry.ts'), 'globalThis.out = 1')
      await ctx.rebuild()
      expect(await fs.readFile(outfile, 'utf8')).not.toContain('InuAssets')
    } finally {
      await ctx.dispose()
    }
  } finally {
    await fs.rm(root, { recursive: true, force: true })
  }
})
