# entinyGram

> A clean, configurable Telegram Android patchset for people who use Telegram every day.

entinyGram is based on [Inugram](https://github.com/teidesu/inugram) and the official [Telegram Android](https://github.com/DrKLO/Telegram) codebase. In simple terms, it is an Inugram X-style continuation: the same patchset idea, taken further with additional features, experiments, and its own layer of changes.

> **This is an openly AI-assisted project.** AI is used to research, design, write, refactor, and review code and patches. The result is still checked and maintained by the project owner, but the project does not pretend that every line was written manually.

Most changes are optional. Disable the custom settings and the app can stay close to stock Telegram.

## Highlights

- Ghost Mode and other privacy controls
- Deleted/edited message history and extended message details
- AdBlock, tracking-parameter removal, and media download improvements
- Customizable UI, themes, fonts, folders, menus, and gestures
- Free voice-to-text and AI-assisted draft tools with configurable endpoints
- More controls, experiments, and integrations built on top of the Inugram base
- Crash reporting, settings export/import, and cloud settings sync

The main idea is simple: keep up with Inugram upstream, then add the features Telegram restricts or gates—such as AdBlock, anti-deletion and edit history, story downloads, restricted forwarding, and free translation or voice transcription—alongside entinyGram's own experiments and power-user tools.

See [FEATURES.md](FEATURES.md) for the full, non-exhaustive list.

## entinyGram vs Inugram

| Area | Inugram | entinyGram |
| --- | --- | --- |
| Foundation | Telegram Android patchset | Inugram patchset + entinyGram-owned layer |
| Patch ownership | `bugfix`, `feature`, `debloat`, `hooks`, `misc` | Same groups, plus `patches/entiny/` for fork-specific work |
| Focus | Clean UI, QoL, and fewer annoyances | Inugram continued with more privacy, power-user controls, experiments, and customization |
| Base features | Original Inugram work | Kept and built upon; existing Inugram features remain Inugram work |
| New layer | Inugram patchset | Additional entinyGram patches in `patches/entiny/` |
| Build tooling | Node.js + pnpm | Bun-based scripts and patch validation |

## Why a patchset?

The stock Telegram source stays in `worktree/`. Custom Kotlin code, resources, and patches live separately. This keeps changes easier to audit, rebase, and remove than a traditional full fork.

```text
src/kotlin/     entinyGram Kotlin code
src/res/        entinyGram resources
patches/        exported stgit patches
patches/entiny/ entinyGram-only patches
series          patch apply order
worktree/       local Telegram checkout (generated, ignored)
```

## Development

Requirements: [Bun](https://bun.sh/), Git, and StGit.

```sh
bun install
bun run setup
```

The setup script prepares `worktree/` and applies the current patch stack. Open `worktree/` in Android Studio to work on the Android project.

Useful checks:

```sh
bun run lint-patches
bun run lint-patches -- --check
```

When creating or changing patches, edit `worktree/` and export through the documented StGit workflow. Do not edit files in `patches/` by hand.

## Acknowledgements

Special thanks to [Inugram](https://github.com/teidesu/inugram), [Telegram Android](https://github.com/DrKLO/Telegram), and the projects that inspired or contributed features, including [Nekogram](https://github.com/Nekogram/Nekogram), [NagramX](https://github.com/risin42/NagramX), [Cherrygram](https://github.com/arsLan4k1390/Cherrygram), [materialgram](https://github.com/kukuruzka165/materialgram), and [Catogram](https://github.com/Catogram/Catogram).

See [LICENSE](LICENSE) for licensing information.
