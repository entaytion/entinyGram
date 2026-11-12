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

check with `gh variable list --repo entaytion/entinyGram`. an empty `INU_TRACK_STATE` starts both counters at 0.

## posts

| build | ci channel caption | main channel |
| --- | --- | --- |
| beta | title, warning, commit list in a quote, links | none |
| release | title, english notes in a quote, links | ukrainian notes, english in a quote, site, donate |

- the commit list drops `docs`, `ci`, `build`, `merge`, `rebase`, `release` areas and the `[area]` prefix.
- a release uses `changelogs/<buildDate>.md`. without it the notes fall back to the commit list and warn.
- a caption is cut to 1000 characters by keeping the first and last lines. a main post above 3800 splits into a reply.
- no emoji in the layout.

## updater contract

`UpdateHelper.kt` relies on these. never change them without changing it too.

| what | rule |
| --- | --- |
| file name | `entinygram[-beta][-plugins]-arm64-<base>-<versionCode>.apk`. the arm7 build is invisible to the updater |
| tags in the post | `#release` or `#prerelease`, exactly |
| notes | the first blockquote of the caption is the update dialog |
| release changelog | fetched from `changelogs/<versionCode / 100>.md` on `main` |

## checks without a build

```bash
GITHUB_SHA=abcdef1 INU_DAY_STATE=20260101:0 INU_TRACK_STATE="13.2.1 beta:4 release:1" PRERELEASE=true bun assets/release/version.ts
```

`version.ts` writes `out/version.json` in the working directory. delete it afterwards.
