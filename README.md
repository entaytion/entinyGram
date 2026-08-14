# entinyGram

> there are many tga forks, but this one is mine

a very cool and custom-pilled fork (or rather, *patchset*, see below) of Telegram Android.

## primary goals

this fork is primarily intended for long-term Telegram users who want a clean, fast, and robust experience without all the bloat.

you can expect:
- removed annoyances
- *a lot* of QoL and privacy features (Ghost Mode, deleted/edited message history, TOS self-destruct media bypass, story downloads without Premium, AdBlock)
- UI tweaks to make it look prettier, cleaner, and customized
- opinionated defaults with full user control
- maps use a custom Google Maps API key (entinygram), so no personal key lives in the repo

all that while still allowing users to disable custom tweaks to achieve a stock-like experience when needed.

see [FEATURES.md](FEATURES.md) for a non-exhaustive list of what's added/tweaked/fixed (kept in sync as patches land).

### why should i use this over whatevergram?

i don't know. maybe you shouldn't.

entinyGram exists primarily for personal use, because i got tired of the bloat that most forks are, and latest stock Telegram is borderline cluttered.
and apparently my vision for a good UI/UX client aligns well with power users who spend hours in the app daily.

feel free to fork this repo and remove patches you don't like or add your own.

## patchset, not a fork

unlike most alternative clients based on Telegram Android, entinyGram is a patchset.
it is not a fork in the traditional sense, but rather a collection of clean patches applied to the stock Telegram codebase.

advantages of this approach:
- easier rebase, since stock code vs fork code is clearly separated
- easier to audit changes, since modifications are all modularized
- easier for bugfixes to stay clean

the patchset is managed using `stgit` and supporting scripts in `scripts/`.

## repo layout

- `src/kotlin`: our custom Kotlin code
- `src/res`: our custom resources
- `patches/`: stgit patches. `bugfix` / `feature` / `debloat` / `hooks` / `misc` = inherited inugram base. `patches/entiny/` = **ours** (entinyGram-only; not in origin)
- `series`: patch apply order
- `upstream-commit`: pinned Telegram commit
- `worktree/`: local Telegram checkout, gitignored

patches are grouped by their type:

| type | description |
| --- | --- |
| `bugfix` | fixes a bug in the upstream codebase |
| `feature` | adds a contained feature (QoL, UI tweaks, TOS bypasses, etc.) |
| `debloat` | hides stock "features" behind a toggle |
| `hooks` | small hooks into various parts of the app to jump into custom Kotlin code |
| `misc` | build support, branding, and infrastructure |
| `entiny` | **our patches** — entinyGram-only work (ghost mode, adblock, save-deleted, branding, updater, …). Not present in inugram. Do not omit this folder when listing patches. |

`src/kotlin`, `src/res`, and `patches/entiny/` are entinyGram files. Everything else under `patches/` is the inugram patchset we apply on top of stock Telegram.

## contributing

contributions are welcome!

requirements: Bun (`bunx`), `git`, `stg`

```sh
bun install
bun run setup
```

this will clone upstream into `worktree/` and set up `stgit` along with all current patches.

### adding a new patch

```bash
stg new feature__my-patch -m 'my patch description'
# ...do whatever you need in worktree/...
stg refresh
bun run export
```

### modifying an existing patch

```bash
# option 1: edit the patch in-place via stg refresh
# ...do whatever you need in worktree/...
stg refresh -p feature__my-patch # --index to only append staged changes
bun run export

# option 2: push the patch to the top of the stack
stg float feature__my-patch
# ...do whatever you need in worktree/...
stg refresh
bun run export
```

### auditing patch interactions

```bash
bun run lint-patches
bun run lint-patches -- --check
```


## acknowledgements

- **[Inugram](https://github.com/teidesu/inugram)** - huge respect and special thanks to the original Inugram project & patchset architecture, which serves as the foundation and core inspiration for entinyGram.
- **[Telegram Android](https://github.com/DrKLO/Telegram)** - the official Telegram client codebase.
- A bunch of features were ported or adapted from [Nekogram](https://github.com/Nekogram/Nekogram), [NagramX](https://github.com/risin42/NagramX), [Cherrygram](https://github.com/arsLan4k1390/Cherrygram), [materialgram](https://github.com/kukuruzka165/materialgram), and [Catogram](https://github.com/Catogram/Catogram).
- `src/res/drawable/icplaceholder.jpg` is a blurred version of [this artwork by Chobles](https://www.pixiv.net/en/artworks/128756420).
- Tabler icons by [Tabler Team](https://tabler.io/icons).
- Solar icon pack by [480 Design](https://t.me/Design480).
- VKUI icon pack by [VK](https://github.com/VKCOM/icons) (MIT), ported from [Catogram](https://github.com/Catogram/Catogram).
- AdGuard URL Tracking filter by [AdGuard](https://adguard.com/).

this project is llm-assisted: a bunch of the code and the patches were (and will be) written by claude. this doesn't mean it's "ai slop", i still review all the code myself,
but im not an android dev by any means so it might not be perfect. ai-assisted contributions are welcome as long as you disclose that in the pr.

if you have an issue with that - go make your own fork.

## license

licensed under the MIT license.
