<p align="center">
  <img src="assets/logo.svg" width="108" height="108" alt="entinyGram" />
</p>

<h3 align="center">entinyGram</h3>

<p align="center">
  An independent StGit patchset on top of official Telegram and Inugram.<br>
  Focused on client-side privacy, restriction bypass, and UI customization.
</p>

<p align="center">
  <a href="https://github.com/entaytion/entinyGram/releases"><img src="https://img.shields.io/badge/releases-latest_APK-333333?style=flat-square&labelColor=333333&logo=github&logoColor=white" alt="Releases"></a>
  <a href="https://entinygram.vercel.app/build"><img src="https://img.shields.io/badge/build_docs-entinygram.vercel.app-333333?style=flat-square&labelColor=333333&logo=vercel&logoColor=white" alt="Build docs"></a>
  <a href="https://t.me/entinyGram"><img src="https://img.shields.io/badge/channel-@entinyGram-333333?style=flat-square&labelColor=333333&logo=telegram&logoColor=white" alt="Telegram channel"></a>
  <br>
  <a href="https://github.com/entaytion/entinyGram/releases"><img src="https://img.shields.io/github/downloads/entaytion/entinyGram/total?style=flat-square&label=downloads&labelColor=333333&color=333333" alt="Downloads"></a>
  <a href="https://developer.android.com/"><img src="https://img.shields.io/badge/android-8.0%2B-333333?style=flat-square&labelColor=333333" alt="Android 8.0+"></a>
  <a href="https://stacked-git.github.io/"><img src="https://img.shields.io/badge/patchset-StGit-333333?style=flat-square&labelColor=333333" alt="StGit"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-333333?style=flat-square&labelColor=333333" alt="License: MIT"></a>
</p>

## Overview

**entinyGram** is a StGit patchset on top of [Inugram](https://github.com/teidesu/inugram) and the official [Telegram for Android](https://github.com/DrKLO/Telegram) client.

Stock Telegram decides for you what counts as read, what disappears when someone deletes it, and what only Premium is allowed to do. This patchset moves those decisions back to the client and adds a comfort layer around them.

Nothing is forced: every change is a separate patch behind a switch in `Settings → entinyGram`, and with all switches off the app behaves like the stock client.

What that layer actually gives you - privacy and ghost mode, archives of deleted messages and edits, restricted-content unlocks, adblock, translation and transcription on your own keys, interface and startup tweaks - is listed in [FEATURES.md](FEATURES.md), patch by patch.

## Repository architecture

Most Telegram forks clone millions of lines and mix custom features into the commit history, which makes every upstream update painful. entinyGram keeps stock code and fork code strictly separated with [StGit](https://stacked-git.github.io/).

```text
entinyGram/
├── src/
│   ├── fork/             # Kotlin helpers, InuConfig toggles, settings pages
│   ├── fork-app/         # the same, for the bits in the app module
│   ├── core/             # InuCore, a plain JVM module (testable without a device)
│   ├── native/           # rust bridge for the plugin engine
│   ├── kotlin-osmdroid/  # our map provider, replaces MapLibre
│   ├── res/              # our strings, drawables, layouts, icon packs
│   └── vendor/           # third-party code kept as-is
├── patches/
│   ├── entiny/           # entinyGram patches and overrides
│   ├── feature/          # Inugram features
│   ├── debloat/          # Inugram debloat patches
│   ├── bugfix/           # fixes for stock Telegram bugs
│   ├── hooks/            # extension points bridging stock Java to our Kotlin
│   └── misc/             # build support and everything else
├── patches-native/       # native-layer patches (LSPlant)
├── sdk/                  # plugin SDK: API typings and the author CLI
├── assets/scripts/       # setup, export, lint, code generation
├── upstream-commit       # pinned Telegram base commit
├── series                # patch application order
└── worktree/             # Telegram checkout the stack is applied to (gitignored)
```

Stock code in `worktree/` is never rewritten in git history; the patches only inject small hooks that call our Kotlin helpers. Updating to a new Telegram version means rebasing the stack, not merging a million-line diff. Real logic lives in `src/fork`, resources live in `src/res`, and stock Java files stay tiny.

*Tooling note: AI assistance is used here for research, patch scaffolding and code review. Every patch is manually curated, tested on device and isolated to keep rebase hygiene clean.*

## Building from source

Signed pre-built APKs are on the [Releases](https://github.com/entaytion/entinyGram/releases) page.

You need **git**, **StGit**, **[Bun](https://bun.sh/)** and **JDK 21**. For the Android SDK, Windows specifics and signing setup see [entinygram.vercel.app/build](https://entinygram.vercel.app/build).

```sh
git clone https://github.com/entaytion/entinyGram.git
cd entinyGram

bun install
bun run setup          # fetch the Telegram source and apply the stack to worktree/

bun run build-debug            # full build with the plugin engine
```

APKs land in `worktree/TMessagesProj_App/build/outputs/apk/`.

Before opening a pull request, run the validation suite:

```sh
bun run lint-patches        # patch series integrity and overwrite detection
bun run entinychecker       # settings search slugs and unused variants
bun run check-translations  # missing, untranslated and broken locale strings
bun run export              # regenerate patches/ and series from stgit
```

## Credits and prior art

entinyGram stands on the shoulders of the Telegram modding community. Special thanks to **[@teidesu](https://github.com/teidesu)** for [Inugram](https://github.com/teidesu/inugram), which is the foundation and the build system of this project, and to [DrKLO](https://github.com/DrKLO/Telegram) for the official client.

<details>
<summary><strong>Forks and projects we borrowed from</strong></summary>
<br>

- **[AyuGram](https://github.com/AyuGram)** - ghost mode concepts, quick-toggle locks, archive exclusion UI.
- **[gettele/android](https://github.com/gettele/android)** ([@nitreojs](https://github.com/nitreojs)) - keep-in-chat model for deleted messages and the chat-style archive.
- **[exteraGram / exteraless](https://github.com/exteraless/exteraless)** - pill stack, wide channel posts, recent chats switcher, instant reaction reads, Material 3 music player.
- **[Nekogram](https://github.com/Nekogram/Nekogram)** - in-app updater, hidden accounts behind a passcode, sticker sizing, datacenter ping.
- **[NagramX](https://github.com/risin42/NagramX)** ([@risin42](https://github.com/risin42)) - icon pack switcher, Solar pack wiring.
- **[NagramXTurbo](https://github.com/temporaryna/NagramXTurbo)** ([@temporaryna](https://github.com/temporaryna)) - download byte budget, background download queue, by-reference forwards of protected content, bold text tweaks.
- **[NagramX Turbo Classic](https://github.com/AlexeyLCP/NagramXTurboClassic)** ([@AlexeyLCP](https://github.com/AlexeyLCP)) - AMOLED theme, standalone black palette.
- **[NagramXF](https://github.com/Keeperorowner/NagramXF)** ([@Keeperorowner](https://github.com/Keeperorowner)) - avatar radius control, clown avatar for users who blocked you.
- **[NagramXFC](https://github.com/rhythmcache/NagramXFC)** ([@rhythmcache](https://github.com/rhythmcache)) - hide seen mentions.
- **[NiagramX](https://github.com/HSSkyBoy/NiagramX)** ([@HSSkyBoy](https://github.com/HSSkyBoy)) - login countdown skip, forced HD photo sending.
- **[Nagram](https://github.com/NextAlone/Nagram)** ([@NextAlone](https://github.com/NextAlone)) - liquid glass refraction controls, compact GIFs in bubbles.
- **[OwlGram](https://github.com/OwlGramDev/OwlGram)** - per-dialog auto-translation UX.
- **[Catogram](https://github.com/Catogram/Catogram)** - VKUI icon pack.
- **[materialgram](https://github.com/kukuruzka165/materialgram)** ([@kukuruzka165](https://github.com/kukuruzka165)) - Material You patterns.
- **[Telegram-Monet](https://github.com/mi-g-alex/Telegram-Monet)** (mi-g-alex) - Monet palette generation.
- Plugin authors **[@anivPlugins](https://t.me/anivPlugins)** (hide business bot banner), **Pixuz** (local message edit), **[@chestertech](https://t.me/chestertech)**.
- **[480 Design](https://t.me/Design480)** - Solar icons; **[@r4in80w](https://t.me/r4in80w)** - the bundled Solar pack.

</details>

## License

The patchset and all fork code in this repository are released under the **MIT license** - see [LICENSE](LICENSE). That grant covers source code only: the project name, logo and visual identity are not licensed and may not be used to imply affiliation with entinyGram.

The built application contains Telegram for Android, licensed under GPL-2.0-or-later, and other third-party components under their own licenses.
