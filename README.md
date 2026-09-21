<p align="center">
  <img src="assets/logo.svg" alt="entinyGram Logo" width="108" height="108" />
</p>

<h1 align="center">entinyGram</h1>

<p align="center">
  <strong>A refined, private, and deeply customizable Telegram Android client</strong><br>
  Built as an independent StGit patchset on top of official Telegram and Inugram.
</p>

<p align="center">
  <a href="https://t.me/entinyGram"><img src="https://img.shields.io/badge/Telegram-Channel-26A5E4?style=flat-square&logo=telegram&logoColor=white" alt="Telegram Channel"></a>
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+"></a>
  <a href="https://stacked-git.github.io/"><img src="https://img.shields.io/badge/Patchset-StGit-4A90E2?style=flat-square" alt="StGit Patchset"></a>
  <a href="https://bun.sh"><img src="https://img.shields.io/badge/Tooling-Bun-f472b6?style=flat-square&logo=bun&logoColor=white" alt="Bun Tooling"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--2.0%20%2F%20GPL--3.0-orange?style=flat-square" alt="License: GPL"></a>
</p>

---

**entinyGram** is our independent fork of **[Inugram](https://github.com/teidesu/inugram)** and the official **[Telegram Android](https://github.com/DrKLO/Telegram)** codebase. 

While Inugram established a clean, modular patchset architecture focused on minimalism, Material 3, and bugfixes, **entinyGram** pushes it further: restoring user privacy (Ghost Mode, anti-deletion), unlocking server-restricted capabilities, providing free AI translation & transcription, and introducing deep visual customization.

All features are optional. Toggle them on in `Settings → entinyGram`, or keep them off to stay 100% stock-identical.

> **Open Engineering:** This project is developed with AI assistance for research, patch engineering, refactoring, and code review. Every change is tested, maintained, and curated with strict fork isolation and rebase hygiene.

---

## ✨ Key Highlights

### 🛡️ Ghost Mode & Privacy
- **Stealth Browsing:** Read messages, stories, and watch video notes without sending read receipts.
- **Online & Status Spoofing:** Hide online status, auto-resend offline presence after sending messages, and spoof fake typing/recording indicators.
- **Anti-Leak Calls:** Enforce routing through Telegram relay servers to completely prevent peer-to-peer IP leaks.
- **Expiring Media Keeper:** Keep view-once photos, videos, and self-destructing secret chat media permanently saved and playable.

### 🗑️ Anti-Deletion & Edit Archive
- **Save Deleted Messages:** Automatically preserve deleted messages and media locally in `Downloads/entinyGram/media/`.
- **Edit History:** Track every edit with text diffs, original formatting, and media preservation.
- **Local Archive Search:** Full-text search across saved deleted messages and edit history.

### 🔓 Restrictions Bypass & Local Premium
- **Save Any Story:** Download any user or channel story directly to your gallery.
- **Forward Restricted Content:** Copy text, download media, and forward messages from protected channels without author tags.
- **Local Premium Perks:** Unlocked client-side premium badges, custom app icons, emoji statuses, and increased limits for pins and folders.
- **Skip Login Countdown:** Tap the countdown timer on the login screen to request a new SMS or call code immediately.

### 🌐 Free AI Translation & Voice-to-Text
- **Whole-Chat Translation:** Free chat translation bar powered by Google, DeepL, Yandex, Bing, Azure, or custom OpenAI-compatible LLM endpoints with conversation context.
- **Free Voice Transcription:** Transcribe voice messages and video notes via Groq Whisper, Gemini Flash, Cloudflare AI, or OpenAI without Telegram Premium.

### 🎨 Aesthetics & Deep Customization
- **True AMOLED Theme:** Pure-black AMOLED theme independent of system Monet.
- **Avatar Corner Slider:** Freely adjust avatar roundness from 0dp (square) to 28dp (circle) with live preview.
- **iOS-Style UI:** Centered pill chat header, full-width bottom navigation bar, and clean dialog actions.
- **Icon Packs:** Built-in switcher for Solar, VKUI, and Phosphor icon sets with real-time UI preview.
- **Pill Stack:** Interactive status pills in the search bar (clock, battery, storage, proxy ping, RAM, currency rates).

### 🧹 Debloat & Performance
- **Zero Ads:** Completely eliminate Telegram sponsored messages, proxy promo channels, and gift auction banners.
- **Gift & Upsell Muting:** Hide profile gift rings, gift tabs, star reactions, and subscription upsell banners.
- **Trimmed Binary:** Dropped unused transitives and legacy dependencies to reduce APK size and memory overhead.
- **Instant Cold Start:** Binary serialization cache for downloaded translations drops locale load times from ~1.6s to ~20ms.

---

## 📊 Comparison

| Feature / Philosophy | Official Telegram | Inugram | entinyGram |
| :--- | :---: | :---: | :---: |
| **Architecture** | Monolithic Java/C++ | StGit Patchset | StGit Patchset (`patches/entiny/`) |
| **Ghost Mode (Stealth)** | ❌ | ❌ | ✅ |
| **Save Deleted Messages & Edits** | ❌ | ❌ | ✅ |
| **Bypass Protected Content & Stories** | ❌ | ❌ | ✅ |
| **AdBlock & Sponsored Debloat** | ❌ | Partial | ✅ Complete |
| **Free Voice-to-Text (Whisper/Gemini)** | ❌ (Paid) | ❌ | ✅ |
| **Free Chat Translation (LLM/DeepL)** | ❌ (Paid) | ❌ | ✅ |
| **AMOLED Theme & Avatar Corner Slider**| ❌ | ❌ | ✅ |
| **Material 3 / Classic UI Modes** | ❌ | ✅ | ✅ |
| **Map Engine** | MapLibre (heavy) | OSMDroid (lite) | OSMDroid (lite) |

---

## 🏗️ Architecture (The Patchset Way)

Unlike conventional forks that clone millions of lines of stock code into messy commits, entinyGram uses **[StGit](https://stacked-git.github.io/)** patch stacks over clean stock Telegram Android:

```text
entinyGram/
├── src/
│   ├── kotlin/        # Fork logic, UI helpers, and InuConfig toggles
│   └── res/           # Drawables, layouts, and strings (values/strings_inu.xml)
├── patches/
│   ├── entiny/        # entinyGram-owned patches (exclusive features & overrides)
│   ├── feature/       # Upstream Inugram features
│   ├── debloat/       # Upstream Inugram debloat patches
│   ├── bugfix/        # Stock Telegram bug fixes
│   └── hooks/         # Extension points for Kotlin helpers
├── series             # Patch application order
└── worktree/          # Clean stock Telegram Android checkout (symlinked, gitignored)
```

- **Stock stays clean:** Telegram code is never rewritten directly; patches hook into Kotlin helpers.
- **Rebase resilience:** Updating to the latest Telegram Android version is a matter of rebasing patches rather than resolving thousands of git conflicts.
- **Strict Fork Isolation:** All our work lives in `patches/entiny/` and `src/`, completely isolated from upstream remotes.

---

## 🚀 Getting Started

### Downloading the APK
Pre-built APKs are available on the **[Releases](../../releases)** page.

### Building from Source

Requirements: **[Bun](https://bun.sh/)**, **Git**, **StGit**, and **JDK 21**.

1. **Clone the repository:**
   ```sh
   git clone https://github.com/entaytion/entinyGram.git
   cd entinyGram
   ```

2. **Bootstrap the workspace:**
   ```sh
   bun install
   bun run setup
   ```
   *The setup script prepares `worktree/` and applies the StGit patch stack.*

3. **Build the debug APK:**
   ```sh
   bun run build-debug
   ```
   *The resulting APK will be placed at `worktree/TMessagesProj_App/build/outputs/apk/debug/app.apk`.*

For advanced build configurations, ABI filters, and release signing, see **[BUILD.md](BUILD.md)**.

### Patch Validation & Code Quality
```sh
bun run lint-patches       # Verify patch series and detect conflicts
bun run entinychecker      # Validate SearchRegistry slugs and variants
bun run check-translations # Verify localization completeness
```

---

## 🤝 Acknowledgements & Credits

entinyGram is made possible thanks to the work of the open-source Telegram community:

- **[Inugram](https://github.com/teidesu/inugram)** by **[@teidesu](https://github.com/teidesu)** — the foundational project, build architecture, and StGit patchset methodology we build upon.
- **[Telegram Android](https://github.com/DrKLO/Telegram)** — official client codebase.
- **[AyuGram](https://github.com/AyuGram)** — inspiration for Ghost Mode architecture, quick-toggle locks, and anti-deletion concepts.
- **[Cherrygram](https://github.com/arsLan4k1390/Cherrygram)** by **[@arsLan4k1390](https://github.com/arsLan4k1390)** — iOS design patterns, camera AE lock, and biometric safety guards.
- **[exteraGram / exteraLess](https://github.com/exteraless/exteraless)** — pill stack, wide channel posts, recent chats switcher, and instant reactions read.
- **[Nekogram](https://github.com/Nekogram/Nekogram)** — in-app updater, passcode privacy/hidden accounts, sticker sizing, and blocked users filtering.
- **[NagramX](https://github.com/risin42/NagramX)** by **[@risin42](https://github.com/risin42)** & **[NagramXF](https://github.com/Keeperorowner/NagramXF)** by **[@Keeperorowner](https://github.com/Keeperorowner)** — Monet theming, avatar corner radius controls, icon replacement, and Ultra HDR support.
- **[Nagram](https://github.com/NextAlone/Nagram)** by **[@NextAlone](https://github.com/NextAlone)** — compact small GIFs in chat bubbles.
- **[OwlGram](https://github.com/OwlGramDev/OwlGram)** — per-dialog auto-translation UX ideas.
- **[NiagramX](https://github.com/HSSkyBoy/NiagramX)** by **[@HSSkyBoy](https://github.com/HSSkyBoy)** — login code countdown skip.
- **[Catogram](https://github.com/Catogram/Catogram)** — VKUI icon pack integration.
- **[materialgram](https://github.com/kukuruzka165/materialgram)** by **[@kukuruzka165](https://github.com/kukuruzka165)** — Material You patterns and Android design inspiration.
- **[Turbotel](https://github.com)** — Forward Pro inspiration.
- **[MaxExteraPlugins](https://github.com/MaxExteraPlugins)** — background download resilience.
- **[480 Design](https://t.me/Design480)** — Solar icon pack.

---

## 📜 License

Licensed under the **GNU General Public License v2.0 or later** (matching upstream Telegram Android and Inugram). See **[LICENSE](LICENSE)** for the full text.
