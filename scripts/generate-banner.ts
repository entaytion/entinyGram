import fs from 'node:fs/promises'
import { join } from 'node:path'
import sharp from 'sharp'
import { rootDir } from './config.js'

async function generateBanner() {
  const url500 = 'https://fonts.gstatic.com/s/natasans/v1/1q2XY5KBClBit88SU_tUw-brVNlaeZChg54J6g0.ttf'
  const url700 = 'https://fonts.gstatic.com/s/natasans/v1/1q2XY5KBClBit88SU_tUw-brVNlaeZChg0sO6g0.ttf'

  console.log('Downloading Nata Sans fonts...')
  const [res500, res700] = await Promise.all([fetch(url500), fetch(url700)])
  const buf500 = Buffer.from(await res500.arrayBuffer())
  const buf700 = Buffer.from(await res700.arrayBuffer())

  const b64_500 = buf500.toString('base64')
  const b64_700 = buf700.toString('base64')

  const svg = `<svg width="1900" height="500" viewBox="0 0 1900 500" fill="none" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <style>
      @font-face {
        font-family: 'Nata Sans';
        font-style: normal;
        font-weight: 500;
        src: url(data:font/truetype;charset=utf-8;base64,${b64_500}) format('truetype');
      }
      @font-face {
        font-family: 'Nata Sans';
        font-style: normal;
        font-weight: 700;
        src: url(data:font/truetype;charset=utf-8;base64,${b64_700}) format('truetype');
      }
    </style>
  </defs>

  <!-- Background -->
  <rect width="1900" height="500" fill="#000000" />

  <!-- App Icon Circle -->
  <circle cx="450" cy="250" r="180" fill="#F20C3C" />
  
  <!-- App Icon (Satellite Dish) -->
  <g transform="translate(268, 72) scale(15)">
    <g fill="none" stroke="#FFFFFF" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.8">
      <path d="M4 10a7.31 7.31 0 0 0 10 10Z" />
      <path d="m9 15 3-3" />
      <path d="M17 13a6 6 0 0 0-6-6" />
      <path d="M21 13A10 10 0 0 0 11 3" />
    </g>
  </g>

  <!-- Texts -->
  <text x="700" y="240" font-family="'Nata Sans', sans-serif" font-weight="700" font-size="100" fill="#F20C3C">Update available</text>
  <text x="705" y="340" font-family="'Nata Sans', sans-serif" font-weight="500" font-size="55" fill="#F20C3C" opacity="0.8">entinyGram • 12.9.2</text>
</svg>
`

  const bannerPath = join(rootDir, 'banner.svg')
  await fs.writeFile(bannerPath, svg, 'utf8')
  console.log('banner.svg updated with embedded Nata Sans and new satellite dish icon!')

  // Test sharp conversion to PNG
  const pngBuf = await sharp(Buffer.from(svg)).png().toBuffer()
  console.log(`Rendered banner PNG: ${pngBuf.length} bytes`)
}

generateBanner().catch(console.error)
