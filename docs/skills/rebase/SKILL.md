---
name: rebase
description: Move the entinyGram stgit patch stack onto a newer upstream Telegram Android release and resolve its conflicts. Use when `bun run rebase` reports conflicts, worktree files show `<<<<<<< current` / `>>>>>>> patched` markers, or the user says "rebase the patchset", "update the base", "fix the rebase conflict", "continue the rebase". Not for editing one patch or for ordinary git merges.
---

# rebase

the stack is replayed on top of a new stock release. a new release is its own change, never mixed with features. this file is the whole procedure. the rules behind it are in `AGENTS.md`.

## 0. what a new release means

a rebase replays every patch in `worktree/` onto the new base. the base is the stock telegram commit printed by `bun run rebase <ref>` (`Rebasing to <sha> (upstream/master)`). note it. `git show <sha>:<path>` shows what stock changed.

a rebase request is end to end: run it, drive every conflict, run the checks in section 7, and run `bun run export` yourself. do not commit or push.

## 1. size the change

| size | expect |
| --- | --- |
| a dozen touched files | a short rebase |
| a few hundred | a long day of conflicts. read sections 3 and 4 before starting |

the files our patches touch against the files upstream changed:

```bash
git -C worktree diff --name-only <old-base>..<new-base> > $TMPDIR/up.txt
```

name a backup ref before starting, so the stack can be restored:

```bash
git -C worktree branch -f stack-before-<version> HEAD
```

## 2. drop fixes that stock now does itself

before resolving, look for our own patches that upstream also wrote:

- search upstream commits for the feature: `git log --oneline <old>..<new> -i --grep='<keyword>'`, then read the bodies of the candidates;
- diff the file a fix patch touches: `git diff <old>..<new> -- <file>`. a fix is obsolete only when stock does the same thing, not when it merely edits nearby.

if it is obsolete, drop the patch from the stack, remove its row from `FEATURES.md`, and log it in `TODO.md`.

keeping an obsolete fix applies a worse variant of stock code.

## 3. conflict loop

`scripts/status.sh` shows the current patch, its subject, conflicted files and marker lines. `scripts/continue.sh` stages with `git add -f`, runs `stg refresh`, then `stg push -a`, and refuses while markers remain.

| step | action |
| --- | --- |
| identify | `docs/scripts/rebase/status.sh`. the patch subject is the intent. anchor the fix to it |
| read both sides | enough context to see the enclosing loop, method or braces |
| read the patch | `patches/<group>/<name>.patch` (for `entiny`: `patches/entiny/<name>.patch`). its `+` and `-` lines are the real fork delta. skipping this turns a one-token change into a fake rewrite |
| resolve | section 4 |
| verify and continue | `rg -n -a '^(<<<<<<<\|>>>>>>>)' <file>` must be empty, then `docs/scripts/rebase/continue.sh` |

explain each conflict before applying it. one line for mechanical ones. for ambiguous or architectural ones, list the options and ask.

stgit labels the sides, not branches:

| marker | meaning |
| --- | --- |
| `current` | the tree now: new stock plus patches already pushed in this rebase |
| `patched` | what the patch recorded: old base plus the fork's change. stale context |

usually: keep `current`'s stock, re-apply only the fork's delta.

## 4. conflict categories

the one question behind every conflict: does the fork's delta still apply to the new code?

| answer | action |
| --- | --- |
| applies as-is | re-apply onto `current` |
| its target moved | re-target it |
| its target is gone | drop it, log to `TODO.md` |
| incompatible or unclear | ask the user, then log |

| # | category | fix | how often |
| --- | --- | --- | --- |
| 1 | additive: both sides add at the same spot | keep both | most conflicts |
| 2 | modernization: upstream rewrote the stock line (new signature, new param, dropped SDK guard, new API) | take `current`'s line, re-add only the fork's delta. check the patch so you do not mistake stock context for a fork edit | common |
| 3 | re-target: upstream moved the code the fork hooked | find the new location, apply the intent there | occasional |
| 4 | derived value: a literal that depends on counts (e.g. `%d/N`) | count it in the merged code. never pick a side for a number | rare |
| 5 | upstream removed the target | drop the fork change, `rg` the symbol to find dangling refs | rare |
| 6 | structural: upstream changed nesting | take `current`'s structure, insert the fork line, trace braces, check variable scope | rare |
| 7 | architectural: two incompatible approaches | ask the user. if told to drop, log it. often "follow upstream" | rare |

worked example: the maps patch wraps `getString(R.string.Hybrid)` in `if (MapsHelper.isHybridAvailable())`. upstream changed `LocaleController.getString` to `getString`. keep `current`'s call and wrap only that line in the guard.

do not over-verify: `src/fork` is untouched by the rebase, so its helpers and `InuConfig` items still resolve. verify only what your own edit caused: dangling refs, scope, brace balance, unused imports.

## 5. build

```bash
bun run build-debug
```

run it outside the sandbox.

- **new submodules:** `git -C worktree submodule update --init --recursive --depth 1`. `bun run setup` also does this, but it refuses while the stack diverges from `series`, so run it after the export.
- **new gradle subprojects:** upstream may reference a project without `settings.gradle`. add it to the fork's `settings.gradle` (owned by `misc__build-support`): `include ':<name>'` plus `project(':<name>').projectDir = file('...')`.
- **compile errors:** upstream renamed or moved something. fix the stock patch and the matching `src/fork` Kotlin. the symlink makes `src/fork` the same file in both places.
- fix build errors in the patch that owns the line: `git -C worktree add -f <file>`, then `stg -C worktree refresh -p <patch>`.

do not build a debug apk unless the user asks.

## 6. smoke test

only when the user asks. walk the basics with all toggles at defaults first: open a chat, the message menu, send text, photo and sticker, the profile, `settings → entinyGram` (every page opens, search finds a setting), the chat list title, the folder tabs. then the areas the rebase touched by hand. adb rules are in `craft`.

## 7. finishing checks

a rebase is not done until every check passes.

| # | check | command or action |
| --- | --- | --- |
| 1 | stack applied, no leftovers | `stg series \| rg -c '^-'` (0 unapplied), `git -C worktree diff --name-only --diff-filter=U` (empty), `rg -l -a '^(<<<<<<<\|>>>>>>>)' worktree/TMessagesProj/src/main/java` (empty) |
| 2 | export | `bun run export` (before check 3) |
| 3 | `patches/` matches the stack | `bun assets/scripts/ci/pre-commit.ts && echo "SYNC OK"`. any output: re-export and investigate. never hand-edit `patches/` |
| 4 | TL tables | `curl -fsSL https://raw.githubusercontent.com/mtcute/mtcute/refs/heads/master/packages/core/scripts/tl/data/int53-overrides.json -o $TMPDIR/int53-mtcute.json`, then `pnpm run generate-tl $TMPDIR/int53-mtcute.json`. fix every int53 warning in `assets/scripts/data/int53-overrides.json`: re-target renamed fields, drop removed ones, add missing ones in alphabetical order. add any new id or size long |
| 5 | NotificationCenter typings | compare event names with stock (see `docs/scripts/rebase/`). reread every `postNotificationName` site of a changed event and update `sdk/types/android.notification-center.d.ts`. boolean to `boolean`, integer, long, float, double to `number`, random 64-bit ids to `number \| bigint`, string and character to `string`, `byte[]` to `Uint8Array`, other objects to `JavaObject`, null-passing slots to `\| null`, omitted slots to optional |
| 6 | plugin chat history options | `rg -o 'public final static int OPTION_\w+' worktree/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java`. classify each option not in `LOCAL_MESSAGE_OPTIONS` or `REAL_MESSAGE_OPTIONS` (`PluginChatHistory`). log the left-out ones to `TODO.md` |
| 7 | forced SQLite indexes | `docs/scripts/rebase/forced-indexes.sh`. any output is a broken `INDEXED BY` hint: point it at the replacement index or drop it. `checkSQLException` swallows the error, so the query silently does nothing |
| 8 | static lottie table | check `StaticLottie.COMPAT` (`src/fork/helpers/icons`) against each lottie it names. expected frames: tabs 6, input transitions 12, `voice_and_video` 60, all at 60 fps. re-derive progress keys from the views that play them if they changed |
| 9 | the app builds | `bun run build-debug` passes |

then hand back. report the new `APP_VERSION_CODE` so the user can name the commit. do not commit or push.

## 8. docs to update

| file | change |
| --- | --- |
| `FEATURES.md` | drop rows of dropped patches, so no link points at a missing patch |
| `TODO.md` | one entry for every non-obvious resolution (below) |
| `changelogs/unreleased.md` | a user-facing change only. a line under `## uk` and `## en` |

`TODO.md` entry format: `## <patch-name> - <one line what and why>`, then 2-4 terse lines. log:

| case | content |
| --- | --- |
| dropped a fork change | what was dropped, where to re-apply it |
| judgment call (upstream over fork) | the choice and why |
| merged result needs eyeballing | animation math, computed counts, layout |
| coverage gap | what now applies only partly |
| redundant with stock | what to trim |

## 9. recurring shapes

| symptom | fix |
| --- | --- |
| `git -C worktree add` says "paths are ignored" | the worktree's `TMessagesProj/src/main/java` is gitignored though tracked. use `git add -f` |
| `rg` says "binary file matches" | some stock files have a null byte (`MediaController.java`). use `rg -a` |
| `stg refresh` says "resolve outstanding conflicts" | the resolved file was not `git add -f`'d |
| `stg push -a` clears several patches, then stops | expected. the next conflict is the next patch |
| a stock body reappears inside a patch | the patch carried it as context. take `current`'s body and re-add only the fork lines |
| build fails in `jni`/native code with odd C++ errors | submodules are at old commits: `git submodule update --init` |

for broad exploration (an upstream refactor across big files, or a 20k-46k line hotspot like `ChatActivity`, `PhotoViewer`, `MediaController`), dispatch an explore or general-purpose subagent with the patch name, the file and lines, and one precise question. resolve it yourself after the report.
