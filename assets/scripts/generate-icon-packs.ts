import fs from 'node:fs/promises'
import { join } from 'node:path'
import { rootDir } from './config.js'
import { cd, ensureDir, step, success } from './lib.js'

// run manually after changing src/res/icon-packs; output is committed

const SOURCE_DIR = join(rootDir, 'src/res/icon-packs')
const OUT_DIR = join(rootDir, 'src/res/assets/icon_packs')
// zip stores local mtimes, so pin them (and the timezone) to keep archives reproducible
const EPOCH = new Date('2020-01-01T00:00:00Z')

await ensureDir(OUT_DIR)
for (const name of await fs.readdir(SOURCE_DIR)) {
  const dir = join(SOURCE_DIR, name)
  const files = (await fs.readdir(dir)).sort()
  for (const file of files) {
    await fs.utimes(join(dir, file), EPOCH, EPOCH)
  }

  const out = join(OUT_DIR, `${name}.icons`)
  await fs.rm(out, { force: true })
  step(`Packing ${name} (${files.length} files)`)
  await cd(dir)({ env: { ...process.env, TZ: 'UTC' } })`zip -X -q -9 ${out} ${files}`
}
success(`Icon packs written to ${OUT_DIR}`)
