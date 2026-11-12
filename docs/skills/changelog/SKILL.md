---
name: changelog
description: Write and release entinyGram changelog bullets, the gitignored draft changelogs/unreleased.md, the release step bun run changelog:release, and the Telegram post limits. Use when a user-facing change is finished or reverted, when preparing a release, or when the user asks for release notes.
---

# changelog

release notes are hand-written. never generate them from commits or with a model.

## files

| file | role |
| --- | --- |
| `changelogs/unreleased.md` | gitignored draft. one bullet per finished user-facing change |
| `changelogs/<YYYYMMDD>.md` | the published notes for a build date (UTC, `versionCode / 100`) |
| `FEATURES.md` | the feature list. update it in the same change as the patch |

## bullets

write under both `## uk` and `## en`, under `## entinyGram additions` in the matching topical subsection.

| rule | detail |
| --- | --- |
| language | ukrainian and english only. never russian |
| content | one short sentence on what the user gets. no mechanics, no option lists, no `(beta)`, `(wip)` or `experimental` labels |
| settings link | in the uk bullet only: `[label](tg://entinySettings/<slug>)`, slug from `SearchRegistry`. en bullets carry the plain label |
| attribution | ported or inspired features end with `- *ported from <Fork>*` or `- *inspired by <Fork> (@author)*` |
| groups | optional `### new`, `### fixed`, `### removed` inside a language. bullets without one count as new. the post shows bold titles only when a language uses more than new |
| reverted | delete the bullet |
| wip | the bullet ends with `⚠️` and a short note in its own language, under about 60 characters. word it fresh each time: not finished or tested, may misbehave, bug reports welcome |
| telegram base | one plain line, no bracket prefix, english tag in both languages: `Rebase to 12.10.4 (ported by entinyGram)` after a rebase, `Updated to Telegram X.Y.Z (via inugram)` after a merge |

## release

1. check the draft: both languages, no russian, no wip bullet that should ship.
2. run `bun run changelog:release` (`assets/release/changelog.ts`). it moves the draft into `changelogs/<YYYYMMDD>.md`, appends to that file on a second release the same day, and resets the draft.
3. commit and push that file to `main` before the build workflow. CI and the in-app updater read the file by date. without it, CI falls back to commit subjects.

## limits

| channel | limit |
| --- | --- |
| telegram main post | 4096 visible characters, link urls not counted. header, site link, donate line and tags take about 400, so uk and en together stay under about 3600 |
| split | `assets/release/post.ts` sends a ukrainian message and an english reply when the post is above 3800 |
| ci caption under an apk | about 1000 |
| github release notes | english text |

## checks

- no duplicated bullets between the draft and the file.
- every settings link slug exists in `SearchRegistry` (`bun run changelog:release` warns on unknown slugs).
- the post stays under the limits.
