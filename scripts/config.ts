import type { IconifyJSON } from '@iconify-json/tabler'
import type { SvgToDrawableOptions } from './svg-to-vector.js'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { icons as tablerIcons } from '@iconify-json/tabler'

export const upstreamUrl = 'https://github.com/DrKLO/Telegram'
export const rootDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
export const worktreeDir = join(rootDir, 'worktree')
export const patchesDir = join(rootDir, 'patches')
export const seriesFile = join(rootDir, 'series')
export const upstreamCommitFile = join(rootDir, 'upstream-commit')
export const assetsDir = join(rootDir, 'src/res/assets')

export const debugAppId = 'ua.entaytion.entinygram.beta'

export interface ForkSyncFile {
  source: string
  target: string
  directory?: boolean
  replace?: boolean
}

export const forkSyncFiles: ForkSyncFile[] = [
  // code
  {
    source: 'src/kotlin',
    target: 'TMessagesProj/src/main/kotlin/desu/inugram',
    directory: true,
  },
  {
    source: 'src/kotlin-app',
    target: 'TMessagesProj_App/src/main/kotlin/desu/inugram',
    directory: true,
  },
  {
    source: 'src/kotlin-maps',
    target: 'TMessagesProj/src/main/kotlin-maps/desu/inugram/helpers/maps',
    directory: true,
  },
  {
    source: 'src/core',
    target: 'InuCore',
    directory: true,
  },
  {
    source: 'src/java/google_material',
    target: 'TMessagesProj/src/main/java/google_material',
    directory: true,
  },
  // assets
  {
    source: 'src/res/values/strings_inu.xml',
    target: 'TMessagesProj/src/main/res/values',
  },
  {
    source: 'src/res/values/ids_inu.xml',
    target: 'TMessagesProj/src/main/res/values',
  },
  {
    source: 'src/res/values-ru/strings_inu.xml',
    target: 'TMessagesProj/src/main/res/values-ru',
  },
  {
    source: 'src/res/values-uk/strings_inu.xml',
    target: 'TMessagesProj/src/main/res/values-uk',
  },
  {
    source: 'src/res/values-ja/strings_inu.xml',
    target: 'TMessagesProj/src/main/res/values-ja',
  },
  {
    source: 'src/res/values-zh-rCN/strings_inu.xml',
    target: 'TMessagesProj/src/main/res/values-zh-rCN',
  },
  {
    source: 'src/res/values-tr/strings_inu.xml',
    target: 'TMessagesProj/src/main/res/values-tr',
  },
  {
    source: 'src/res/values-night/styles.xml',
    target: 'TMessagesProj/src/main/res/values-night',
    replace: true,
  },
  {
    source: 'src/res/drawable/icplaceholder.jpg',
    target: 'TMessagesProj/src/main/res/drawable',
    replace: true,
  },
  {
    source: 'src/res/drawable/sticker.webp',
    target: 'TMessagesProj/src/main/res/drawable',
  },
  {
    source: 'src/res/drawable-xxhdpi/*',
    target: 'TMessagesProj/src/main/res/drawable-xxhdpi',
  },
  {
    source: 'src/res/drawable/solar/*',
    target: 'TMessagesProj/src/main/res/drawable',
  },
  {
    source: 'src/res/drawable/vkui/*',
    target: 'TMessagesProj/src/main/res/drawable',
  },
  {
    source: 'src/res/drawable/*.xml',
    target: 'TMessagesProj/src/main/res/drawable',
  },
  {
    source: 'src/res/assets/*',
    target: 'TMessagesProj/src/main/assets',
  },
  {
    source: 'src/res/raw/*',
    target: 'TMessagesProj/src/main/res/raw',
  },
  // launcher icons, produced by `bun run generate-icons`
  {
    source: 'src/res/launcher/generated/drawable/*',
    target: 'TMessagesProj/src/main/res/drawable',
  },
  {
    source: 'src/res/launcher/generated/mipmap/*',
    target: 'TMessagesProj/src/main/res/mipmap-anydpi-v26',
    replace: true,
  },
  {
    source: 'src/res/launcher/generated/mipmap-debug/*',
    target: 'TMessagesProj_App/src/debug/res/mipmap-anydpi-v26',
    replace: true,
  },
  {
    source: 'src/google-services.json',
    target: 'TMessagesProj',
    replace: true,
  },
  {
    source: 'src/google-services.json',
    target: 'TMessagesProj_App',
    replace: true,
  },
]

export const ICON_SELECTION: { pack: IconifyJSON, icons: string[], options?: SvgToDrawableOptions }[] = [
  {
    pack: tablerIcons,
    options: { overrideStrokeWidth: 1.67, paddingInset: 1 }, // to match Telegram
    icons: [
      'copy',
      'clipboard',
      'scissors',
      'bold',
      'underline',
      'italic',
      'strikethrough',
      'background',
      'quote',
      'code',
      'link',
      'select-all',
      'clear-formatting',
      'filter',
      'cloud',
      'file-diff',
      'text-wrap',
      'text-wrap-disabled',
      'infinity',
      'radar',
      'shield-lock',
      'trash-off',
      'clock-off',
      'lock-open',
      'brand-github',
      'sparkles',
      'brain',
      'cpu',
      'server',
      'microphone',
      'spy',
      'key',
      'fingerprint',
      'shield-cancel',
      'shield-check',
      'eye-off',
      'eye',
      'user-search',
      'keyboard',
      'link-off',
      'bolt',
      'adjustments-horizontal',
      'device-floppy',
      'database',
      'brand-telegram',
      'flame',
      'file-export',
      'file-import',
      'terminal-2',
      'cloud-download',
      'cloud-x',
      'message-x',
      'photo-x',
      'password',
      'user-scan',
      'user-x',
      'menu-2',
      'camera-rotate',
      'mood-smile',
      'list',
      'folder',
      'users-group',
      'circle-plus',
      'language',
      'language-off',
      'world',
      'id',
      'file-search',
      'trash',
      'trash-filled',
      'trash-x',
      'clock-hour-4',
      'user-circle',
      'user-search',
      'gift',
      'crown',
      'star',
    ],
  },
]
