import type { SvgToDrawableOptions } from './svg-to-vector.js'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { locate } from '@iconify/json'

export const upstreamUrl = 'https://github.com/DrKLO/Telegram'
export const rootDir = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
export const worktreeDir = join(rootDir, 'worktree')
export const patchesDir = join(rootDir, 'patches')
export const seriesFile = join(rootDir, 'series')
export const upstreamCommitFile = join(rootDir, 'upstream-commit')
export const assetsDir = join(rootDir, 'src/res/assets')

export const debugAppId = 'ua.entaytion.entinygram'
// commit authorship that counts as entinyGram's own; anything else came in through inugram
export const forkAuthorEmails = ['entaytion@gmail.com']
// nested submodules we never build, skipped by the recursive update. lsplant's test deps are
// private repos behind ssh urls
export const skippedSubmodules = [
  'test/src/main/jni/external/lsparself',
  'test/src/main/jni/external/lsprism',
]

export interface SubmodulePatch {
  submodule: string
  patch: string
}

const lsplant = 'TMessagesProj_App/jni/lsplant'
const wallet = 'TMessagesProj/jni/wallet-engine'
const media = 'TMessagesProj_Modules/media'
function nativePatch(submodule: string, name: string): SubmodulePatch {
  return {
    submodule,
    patch: join(rootDir, `patches-native/${name}.patch`),
  }
}

// changes carried against a submodule's pinned commit, applied to its working tree after
// `git submodule update --init`. the pin stays upstream's, so nothing here needs a fork.
export const submodulePatches: SubmodulePatch[] = [
  nativePatch(lsplant, 'lsplant-c-abi'),
  nativePatch(lsplant, 'lsplant-unhook-backup-id'),
  nativePatch(lsplant, 'lsplant-hook-backup-race'),
  nativePatch(wallet, 'wallet-engine-private-key'),
  nativePatch(media, 'media-auto-quality'),
  nativePatch(media, 'media-format-document-id'),
]

export interface VendoredCrate {
  name: string
  version: string
  sha256: string
  patch: string
}

export const nativeVendorDir = join(rootDir, 'src/native/vendor')

// crates.io crates carried with patches: unpacked into nativeVendorDir and wired in through
// [patch.crates-io] in src/native/Cargo.toml
export const vendoredCrates: VendoredCrate[] = [
  {
    name: 'rquickjs-sys',
    version: '0.14.0',
    sha256: 'cee271d0eeba64f0915b846cb7ae02e16faf3dfdffdca91731101d9d30fe3423',
    patch: join(rootDir, 'patches-native/rquickjs-sys-security.patch'),
  },
]

export interface ForkSyncFile {
  source: string
  target: string
  directory?: boolean
  replace?: boolean
  pluginsOnly?: boolean
}

const resTarget = 'TMessagesProj/src/main/res'
function res(source: string, dir: string, extra: Partial<ForkSyncFile> = {}): ForkSyncFile {
  return { source: `src/res/${source}`, target: `${resTarget}/${dir}`, ...extra }
}

// a new locale needs a values-<iso>/strings_inu.xml, an entry here, and a localeFilters entry in
// TMessagesProj_App/build.gradle (entiny__build-configuration)
const locales = ['ru', 'uk', 'ja', 'zh-rCN', 'tr', 'ar', 'fa', 'es', 'it']
const splashColorDirs = ['values', 'values-night-v31', 'values-v34']

export const forkSyncFiles: ForkSyncFile[] = [
  res('xml/provider_paths.xml', 'xml', { replace: true }),
  // code
  { source: 'src/fork', target: 'TMessagesProj/src/inu/kotlin/desu/inugram', directory: true },
  { source: 'src/fork-app', target: 'TMessagesProj_App/src/main/kotlin/desu/inugram', directory: true },
  // the plugin bridge's own suite, against the real stock classes.
  // `./gradlew :TMessagesProj:connectedDebugAndroidTest`
  { source: 'src/test/kotlin', target: 'TMessagesProj/src/androidTest/kotlin/desu/inugram', directory: true, pluginsOnly: true },
  // the js oracles, for the suites that run one rather than restating what it asserts. Test assets
  // only - the app itself ships none of them, so a debug build starts with no plugins installed
  { source: 'src/test/plugins/*', target: 'TMessagesProj/src/androidTest/assets/inu_plugins', pluginsOnly: true },
  // src/test/kotlin is synced into a kotlin source root, so what the suite needs as a *file* is
  // kept beside it rather than in it
  { source: 'src/test/assets/*.{dex,gif,json}', target: 'TMessagesProj/src/androidTest/assets/inu', pluginsOnly: true },
  { source: 'src/kotlin-osmdroid', target: 'TMessagesProj/src/main/kotlin-osmdroid/desu/inugram/helpers/maps/osm', directory: true },
  { source: 'src/core', target: 'InuCore', directory: true },
  // native: rust plugin engine (rquickjs/quickjs-ng + jni bridge), built into libinu_native.so
  // by a cargo-ndk Exec task wired in TMessagesProj_App/build.gradle
  { source: 'src/native', target: 'TMessagesProj_App/native', directory: true, pluginsOnly: true },
  // ART baseline profile. A plugin action runs the bridge's read path a few hundred times and
  // stops, under the JIT's threshold, so without this every crossing a user pays for runs
  // interpreted (measured at 5-8x the compiled cost). Release builds AOT-compile the listed
  // classes at install, through profileinstaller; the debuggable variant ignores it
  { source: 'src/profile/baseline-prof.txt', target: 'TMessagesProj_App/src/main', pluginsOnly: true },
  { source: 'src/vendor/google_material', target: 'TMessagesProj/src/main/java/google_material', directory: true },
  // values
  res('animator/*.xml', 'animator'),
  ...splashColorDirs.map(dir => res(`${dir}/colors_inu_splash.xml`, dir)),
  res('values-v31/*_inu_splash.xml', 'values-v31'),
  res('values/strings_inu.xml', 'values'),
  res('values/ids_inu.xml', 'values'),
  res('values/styles_inu.xml', 'values'),
  ...locales.map(iso => res(`values-${iso}/strings_inu.xml`, `values-${iso}`)),
  res('values-night/styles.xml', 'values-night', { replace: true }),
  // drawables
  res('drawable/icplaceholder.jpg', 'drawable', { replace: true }),
  res('drawable/sticker.webp', 'drawable'),
  res('drawable-xxhdpi/*', 'drawable-xxhdpi'),
  res('drawable/solar/*', 'drawable'),
  res('drawable/*.xml', 'drawable'),
  // assets
  { source: 'src/res/assets/icon_packs', target: 'TMessagesProj/src/main/assets/icon_packs', directory: true },
  { source: 'src/res/assets/*', target: 'TMessagesProj/src/main/assets' },
  res('raw/*', 'raw'),
  // launcher icons, produced by `bun run generate-icons`
  res('launcher/generated/drawable/*', 'drawable'),
  res('launcher/generated/mipmap/*', 'mipmap-anydpi-v26', { replace: true }),
  res('launcher/concepts/webp/*', 'drawable-nodpi'),
  res('launcher/concepts/generated/mipmap/*', 'mipmap-anydpi-v26', { replace: true }),
  { source: 'src/res/launcher/generated/mipmap-debug/*', target: 'TMessagesProj_App/src/debug/res/mipmap-anydpi-v26', replace: true },
  { source: 'src/google-services.json', target: 'TMessagesProj', replace: true },
  { source: 'src/google-services.json', target: 'TMessagesProj_App', replace: true },
]

export interface IconSet {
  options?: SvgToDrawableOptions
  icons: string[]
}

export interface IconPack {
  icons: Record<string, { body: string, width?: number, height?: number }>
  width?: number
  height?: number
}

export const loadIconPack = (prefix: string): IconPack => JSON.parse(readFileSync(locate(prefix), 'utf8'))

// every glyph here becomes a vector drawable `inu_<set>_<name>` when `bun run setup` runs
export const ICON_SETS: Record<string, IconSet> = {
  'tabler': {
    options: { overrideStrokeWidth: 1.67, paddingInset: 1 }, // to match Telegram
    icons: [
      // text editing
      'bold',
      'italic',
      'underline',
      'strikethrough',
      'quote',
      'code',
      'link',
      'background',
      'clear-formatting',
      'select-all',
      'scissors',
      'copy',
      'clipboard',
      'text-wrap',
      'text-wrap-disabled',
      // people and chat
      'message-dots',
      'mood-smile',
      'mood-annoyed',
      'users-group',
      'user-search',
      'user-scan',
      'user-x',
      'id',
      'spy',
      'heart',
      'pin',
      // privacy and security
      'lock-password',
      'key',
      'shield-check',
      'eye',
      'eye-off',
      'trash',
      'trash-x',
      'trash-off',
      // files and media
      'cloud',
      'cloud-download',
      'cloud-x',
      'file-diff',
      'file-search',
      'photo-x',
      'microphone',
      'camera-bolt',
      // layout and navigation
      'app-window',
      'layout-sidebar',
      'layout-align-center',
      'list-details',
      'arrows-sort',
      'arrow-bar-to-right',
      'dots-vertical',
      'focus-2',
      'marquee-2',
      'filter',
      'adjustments-horizontal',
      'layout-cards',
      'stack-2',
      'dashboard',
      'radar',
      // system
      'cpu',
      'server',
      'terminal-2',
      'keyboard',
      'device-desktop-cog',
      'device-mobile',
      'battery-charging',
      'clock',
      'clock-hour-4',
      'sun',
      'palette',
      'language',
      'language-off',
      'world',
      'sparkles',
      'info-circle',
      'info-square',
      'circle',
      'circle-plus',
      'alert-triangle-filled',
      // money
      'coins',
      'send',
      'credit-card',
      'pig-money',
      'diamond',
      'currency-bitcoin',
      'currency-ethereum',
      'currency-solana',
      // brands
      'brand-github',
      'brand-telegram',
    ],
  },
  'bi': { icons: ['currency-dollar', 'currency-euro', 'openai'] },
  'bxl': { icons: ['groq-ai'] },
  'simple-icons': { icons: ['bitcoin', 'ethereum', 'tether', 'bnbchain', 'ton', 'cloudflare', 'googlegemini', 'openrouter'] },
  'cryptocurrency': { icons: ['trx'] },
}
