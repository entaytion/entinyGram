---
name: patches
description: Create, update, rename, squash, and export entinyGram stgit patches (entiny__ group, topics, index-only staging, subjects). Use when a change needs its own patch, an existing patch needs a fix, an inugram patch is taken over, or the patch stack is exported.
---

# patches

`worktree/` holds the stgit stack. `patches/` and `series` are exports: never hand-edit them.

## groups

| group | owner | rule |
| --- | --- | --- |
| `entiny` | entinyGram | new patches go here as `entiny__<name>`. exported to `patches/entiny/<topic>/<name>.patch`. the topic must exist in `assets/scripts/patch-topics.json` |
| `bugfix`, `feature`, `debloat`, `hooks`, `misc` | inugram base | never create a new patch here |

topics: `accounts`, `ai`, `build`, `calls`, `chat`, `feed`, `media`, `misc`, `notifications`, `premium`, `privacy`, `proxy`, `restrictions`, `settings`, `tabs`, `translator`, `ui`.

## create a patch (index-only)

other agents may leave unstaged edits in `worktree/`. never run bare `stg refresh` or `stg new -r`.

```bash
git -C worktree diff --cached                       # must be empty
git -C worktree add <path>                          # stage only your files
git -C worktree apply --cached <hunk.patch>         # or only your hunks
git -C worktree diff --cached                       # no lines from other work
stg -C worktree new -r -i -m "<one plain sentence>" entiny__<name>
stg -C worktree show --stat
```

then add the topic to `patch-topics.json` if it is new.

## subject and message

- 1-2 plain sentences. no `[]`, no `feat()`, no `Subject:`, no essay.
- no `[area]` prefix in patch subjects. the `[area]` form is for git commits only.
- the author is the active `git config user.name` and `user.email`. keep the original author when porting.

## update a patch

| task | command |
| --- | --- |
| fold a fix into an existing patch | `stg squash -n <existing> -m "<subject>" <existing> <new>` |
| take over an inugram patch | `stg float <patch>`, then `stg rename <old> entiny__<name>`, then add its topic |
| edit a patch in place | stage only the file, `stg refresh -p <patch>` |

fold fixes into the owning patch. do not leave tiny follow-up patches.

## export and check

```bash
bun run export                 # regenerates patches/ and series
bun run lint-patches           # run without SKIP_PATCH_CHECK before a push
```

`stg export` is never run directly. intermediate commits may use `SKIP_PATCH_CHECK=1 git commit ...`, the final one runs the full check.

## common mistakes

| mistake | fix |
| --- | --- |
| a patch carries unrelated hunks | `stg float` it, restage only its files |
| the diff shows whole lines re-indented | keep stock indentation, use early returns or run fork code after stock |
| a resource or xml inside a patch | move it to `src/res/`, the patch refers to it |
| a topic missing from `patch-topics.json` | add it, or `lint-patches` fails |
