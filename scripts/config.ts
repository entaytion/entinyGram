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

export const debugAppId = 'ua.entaytion.entinygram'
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

// changes carried against a submodule's pinned commit, applied to its working tree after
// `git submodule update --init`. the pin stays upstream's, so nothing here needs a fork.
export const submodulePatches: SubmodulePatch[] = [
  {
    submodule: 'TMessagesProj_App/jni/lsplant',
    patch: join(rootDir, 'patches-native/lsplant-c-abi.patch'),
  },
  {
    submodule: 'TMessagesProj_App/jni/lsplant',
    patch: join(rootDir, 'patches-native/lsplant-unhook-backup-id.patch'),
  },
]


export interface ForkSyncFile {
  source: string
  target: string
  directory?: boolean
  replace?: boolean
  pluginsOnly?: boolean
}

export const forkSyncFiles: ForkSyncFile[] = [
  // code
  {
    source: 'src/fork',
    target: 'TMessagesProj/src/inu/kotlin/desu/inugram',
    directory: true,
  },
  {
    source: 'src/fork-app',
    target: 'TMessagesProj_App/src/main/kotlin/desu/inugram',
    directory: true,
  },
  // the plugin bridge's own suite, against the real stock classes.
  // `./gradlew :TMessagesProj:connectedDebugAndroidTest`
  {
    source: 'src/test/kotlin',
    pluginsOnly: true,
    target: 'TMessagesProj/src/androidTest/kotlin/desu/inugram',
    directory: true,
  },
  // the js oracles, for the suites that run one rather than restating what it asserts. Test assets
  // only - the app itself ships none of them, so a debug build starts with no plugins installed
  {
    source: 'src/test/plugins/*',
    pluginsOnly: true,
    target: 'TMessagesProj/src/androidTest/assets/inu_plugins',
  },
  // src/test/kotlin is synced into a kotlin source root, so what the suite needs as a *file* is
  // kept beside it rather than in it
  {
    source: 'src/test/assets/*.{dex,gif,json}',
    pluginsOnly: true,
    target: 'TMessagesProj/src/androidTest/assets/inu',
  },
  {
    source: 'src/kotlin-osmdroid',
    target: 'TMessagesProj/src/main/kotlin-osmdroid/desu/inugram/helpers/maps/osm',
    directory: true,
  },
  {
    source: 'src/core',
    target: 'InuCore',
    directory: true,
  },
  // native: rust plugin engine (rquickjs/quickjs-ng + jni bridge), built into libinu_native.so
  // by a cargo-ndk Exec task wired in TMessagesProj_App/build.gradle
  {
    source: 'src/native',
    pluginsOnly: true,
    target: 'TMessagesProj_App/native',
    directory: true,
  },
  // ART baseline profile. A plugin action runs the bridge's read path a few hundred times and
  // stops, under the JIT's threshold, so without this every crossing a user pays for runs
  // interpreted (measured at 5-8x the compiled cost). Release builds AOT-compile the listed
  // classes at install, through profileinstaller; the debuggable variant ignores it
  {
    source: 'src/profile/baseline-prof.txt',
    pluginsOnly: true,
    target: 'TMessagesProj_App/src/main',
  },
  {
    source: 'src/vendor/google_material',
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
    source: 'src/res/values/styles_inu.xml',
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
    source: 'src/res/values-ar/strings_inu.xml',
    target: 'TMessagesProj/src/main/res/values-ar',
  },
  {
    source: 'src/res/values-fa/strings_inu.xml',
    target: 'TMessagesProj/src/main/res/values-fa',
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
    source: 'src/res/drawable/phosphor/*',
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
  // concept launcher icons, produced by `node scripts/generate-concept-icons.mjs`
  {
    source: 'src/res/launcher/concepts/generated/png/*',
    target: 'TMessagesProj/src/main/res/drawable-nodpi',
  },
  {
    source: 'src/res/launcher/concepts/generated/mipmap/*',
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
      'sun',
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
      'skull',
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
      'circle',
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
      'app-window',
      'layout-align-center',
      'focus-2',
      'dots-vertical',
      'pin',
      'arrow-bar-to-right',
      'marquee-2',
      'heart',
      'pig-money',
      'credit-card',
      'currency-ethereum',
      'currency-bitcoin',
      'currency-solana',
      'diamond',
      'alert-triangle-filled',
    ],
  },
]
