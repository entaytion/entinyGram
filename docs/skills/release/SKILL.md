---
name: release
description: How entinyGram builds, versions and announces a release or beta: assets/release scripts, the INU_TRACK_STATE and INU_DAY_STATE counters, post layout, updater contract. Use when changing the release pipeline, a post, the version labels, or when asked why a build got a number.
---

# release

the pipeline is `.github/workflows/apk.yml`. its scripts live in `assets/release/`. a build runs only when the user asks.

## scripts

| file | job |
| --- | --- |
| `version.ts` | base, track counter, versionCode, apk names, tag. writes `out/version.json` |
| `commits.ts` | commits since the last build, for the beta list |
| `notes.ts` | `release-notes.json`: commit list (beta) or `changelogs/<date>.md` (release) |
| `post.ts` | renders the caption and the main post. no network |
| `send.ts` | uploads the apks to the ci channel, posts to the main channel on a stable run |
| `site.ts` | publishes the changelog card to the website, best effort |
| `changelog.ts` | `bun run changelog:release`: draft to `changelogs/<date>.md` |
| `lib.ts` | changelog parser, track counters, commit filter |

## versions

| part | rule |
| --- | --- |
| base | `APP_VERSION_NAME`, the stock telegram version |
| beta, release | two counters per base. a new base resets both. a release does not reset beta |
| label | `beta N` or `release N`, shown in the post header |
| tag | `v<base>-beta.N` or `v<base>-release.N` |
| versionCode | `YYYYMMDD * 100 + dayCounter * 10`, at most 10 builds a day |
| internal versionName | `<base>-<sha>`, set by gradle. the label is not in the apk |

state lives in repo variables, written by the last step of `apk.yml`:

| variable | value |
| --- | --- |
| `INU_DAY_STATE` | `YYYYMMDD:N` |
| `INU_TRACK_STATE` | `<base> beta:N release:N`, for example `13.2.1 beta:4 release:1` |

check with `gh variable list --repo entinyGram/android`. an empty `INU_TRACK_STATE` starts both counters at 0.

## posts

| build | ci channel caption | main channel |
| --- | --- | --- |
| beta | title, warning, commit list in a quote, links | none |
| release | title, english notes in a quote, links | ukrainian notes, english in a quote, site, donate |

- the commit list drops `docs`, `ci`, `build`, `merge`, `rebase`, `release` areas and the `[area]` prefix.
- a release uses `changelogs/<buildDate>.md`. without it the notes fall back to the commit list and warn.
- a caption is cut to 1000 characters by keeping the first and last lines. a main post above 3800 splits into a reply.
- no emoji in the layout.

`RICH_POST=1` switches `send.ts` to one rich markdown message per channel, sent with `sendRichMessage` over mtproto (files up to 2 GB).
- layout: `##` title, `<footer>` with the tag, one `![](tg://document?id=apkN "variant")` per apk (`arm64`, `arm7`, `· plugins`), then two collapsed `<details>`: `Що нового` (uk) and `What's new` (en), each group as `### Title` with a list. a beta has only `What's new` with the commit list.
- the main post reuses the ci uploads through `uploadCache`. the limit is 32000 characters, cut like the caption, headings kept.
- the updater reads both formats. old installed clients read only the caption format, so keep the old mode while they still update from the channel. the rich path is untested on a device.

## updater contract

`UpdateHelper.kt` relies on these. never change them without changing it too.

| what | rule |
| --- | --- |
| file name | `entinygram[-beta][-plugins]-arm64-<base>-<versionCode>.apk`. the arm7 build is invisible to the updater |
| tags in the post | `#release` or `#prerelease`, exactly |
| notes | caption post: the first blockquote is the update dialog. rich post: the `Що нового` details for `uk`, `What's new` for other languages, falling back to the other one. the `Plugins` group is dropped for pluginless builds |
| rich apk | documents of `rich_message`, same file name rule. a truncated (`part`) post is loaded with `getRichMessage` |
| release changelog | fetched from `changelogs/<versionCode / 100>.md` on `main`, unless the rich post carries its own |

## checks without a build

```bash
GITHUB_SHA=abcdef1 INU_DAY_STATE=20260101:0 INU_TRACK_STATE="13.2.1 beta:4 release:1" PRERELEASE=true bun assets/release/version.ts
```

`version.ts` writes `out/version.json` in the working directory. delete it afterwards.
