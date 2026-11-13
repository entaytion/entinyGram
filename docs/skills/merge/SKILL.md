---
name: merge
description: Take changes from upstream inugram into entinyGram with a plain git merge, then rebuild the stgit patch stack and fix what breaks. Use when the user says "merge inugram", "sync upstream", "take from inugram", "updated to inugram", or when inugram has new commits to pull. Not for a new stock Telegram base (that is `rebase`).
---

# merge

inugram is the fork we started from. we pull its changes with a plain `git merge`, never a rebase, and then rebuild our patch stack on top of the result. the last full merge was `6c721877` (25 upstream commits, 202 files).

the difference from `rebase`: `rebase` moves our stack to a newer stock Telegram. `merge` brings in inugram's own work.

## 0. policy

| rule | meaning |
| --- | --- |
| take upstream by default | inugram's version wins unless one of the cases below applies |
| keep ours | our `entiny__` patches and overlays (`patches/entiny/`), and features inugram does not have. say which one you keep and why |
| search before writing | if inugram already has the feature, use theirs. no duplicate toggles, drawables or helpers (`InuConfig`, `src/fork/helpers`, `series`, `patches/hooks/`) |
| no upstream changelogs | never pull `changelogs/` from upstream. our notes live in `changelogs/<YYYYMMDD>.md` |
| no upstream FEATURES | never take inugram's `FEATURES.md`. ours lists only entinyGram additions and links to inugram's file for the base |
| nothing goes upstream | never open issues or PRs to inugram or Telegram |

## 1. before the merge

```bash
git remote -v
git fetch inugram
git log --oneline HEAD..inugram/main
git log --oneline --no-merges HEAD..inugram/main | wc -l
```

- the `inugram` remote is not in `git remote -v` anymore. add it once with the inugram URL, then fetch.
- read the big commits: `git show --stat <sha>`. note what hits our hooks in `src/fork`.
- the tree must be clean. `git status --short` should show nothing, or only files the user knows about.
- name a backup ref: `git branch -f merge-backup-<date> HEAD`.

## 2. merge

```bash
git merge inugram/main
```

a plain merge. do not rebase, do not squash. the merge commit subject is `[merge] sync inugram`, body with one `- ` line per change (see `AGENTS.md`, commit body style).

right after the merge, check for leaked files:

```bash
git diff --cached --name-only | rg '^(changelogs/|FEATURES\.md$)'
```

upstream changelogs and `FEATURES.md` must not stay. `6c721877` brought `changelogs/55.md` in. remove it: `git rm changelogs/<file>`. for `FEATURES.md`, restore ours: `git checkout HEAD -- FEATURES.md`.

## 3. resolve conflicts

for each conflicting file:

1. decide by the policy in section 0: upstream, ours, or a combination.
2. edit with the editor tool, not `sed -i` with line numbers. a line-number `sed` removed a closing brace in `Schemas.kt` once and an indent-stripping `sed c\` broke Java once.
3. after resolving, scan:

```bash
rg -n '^(<<<<<<<|=======|>>>>>>>)' src TMessagesProj_App
```

XML must still be well-formed. check it after edits.

4. stock API renames come with upstream. example: `UItem.text2` became `UItem.subtext`. grep our `src/fork` for the old name.

`git add` may say a path is ignored (the worktree's `TMessagesProj/src/main/java` is gitignored though tracked). use `git add -f`.

## 4. rebuild the patch stack

after the merge, `patches/` and `series` describe the old stack. rebuild it:

```bash
bun run setup --force
```

`setup` re-imports every entry of `series` with `stg import --3way`. `--force` resets the stack, so run it only with a clean worktree. `setup` refuses to run on a stack that diverges from `series`, which is why this step is needed.

when an import fails, the rejected hunks are the work to do:

1. read the failed patch and the hunks `stg` reports as not applied.
2. apply the hunk by hand onto the new code. upstream often moved context.
3. create or refresh the patch with index-only staging (`AGENTS.md` section 0.4): `git -C worktree add <path>`, check `git -C worktree diff --cached`, then `stg -C worktree new -r -i` or `stg refresh`. never bare `stg refresh`.

the last merge needed this for `hide-gift-elements` and `ios-input-bar`, which hit a changed `non-island-ui` context. both were ported by hand.

then:

```bash
bun run export
bun run entinychecker
bun run check-translations
bun run lint-patches
```

`export` regenerates `patches/` and `series`. do not hand-edit them. `lint-patches` runs without `SKIP_PATCH_CHECK` once, before any push.

## 5. known traps

| trap | fix |
| --- | --- |
| `changelogs/55.md` from upstream | remove it (section 2) |
| inugram's `FEATURES.md` in the merge | restore ours (section 2) |
| upstream "what's new" sheet and settings page | drop them, keep our updater |
| `NON_ISLAND_SHARED_MEDIA_TABS` | keep it OFF. upstream `SharedMediaLayout.inu_dockOffset()` clips the list bottom |
| `google-services.json` and `entiny__branding` | run `bun run assets/scripts/apply-branding.ts` right after the merge |
| API renames (`text2` to `subtext`) | grep `src/fork` for the old name |
| a new locale | needs all three: `forkSyncFiles` in `assets/scripts/config.ts`, `localeFilters` in `TMessagesProj_App/build.gradle`, and the symlink from `bun run setup` |
| old config keys from a backup | `ConfigSanitizer` resets broken values. unknown keys stay |
| sed edits by line number | use the editor tool |

## 6. build and check

do not build locally unless the user asks (`AGENTS.md` section 0.6). when they do, `bun run build-debug`, run outside the sandbox.

if the user asks for a review of the merge, check with `docs/skills/craft` for bugs and `docs/skills/rebase` section 7 for the stack checks.

## 7. commit

commit only on the user's explicit request, one commit for the merge. the subject is `[merge] sync inugram`, the body has one `- ` line per notable change. changelog bullet: `Updated to Telegram X.Y.Z (via inugram)` in both `## uk` and `## en`, in `changelogs/unreleased.md`.

do not push. a push is a separate, explicit request.
