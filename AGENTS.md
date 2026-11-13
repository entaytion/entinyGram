# entinyGram agent guide

entinyGram is an independent power-user fork of inugram (`teidesu/inugram`), built as an `stgit` patch stack over stock Telegram Android.

## sources of truth

| what | where |
| --- | --- |
| fork code (kotlin) | `src/fork`, symlinked into `worktree/` |
| fork resources | `src/res`, symlinked into `worktree/` |
| hooks in stock code | gated lines inside `worktree/` |
| patch stack | `worktree/` (stgit) |
| export, never hand-edit | `patches/`, `series` |
| feature list | `FEATURES.md`, updated in the same change as a patch |
| release notes | `changelogs/`, see the `changelog` skill |
| workflow details | `docs/skills/`, see the index below |

## hard rules

1. **ask, do not guess.** if an instruction is ambiguous, ask one concrete question. never invent stock method names, line numbers or server behavior. mark anything unverified `[INFERENCE]` and check it with `rg` first. present a short plan before changing files or stack state.
2. **no moralizing.** entinyGram exists for client-side freedom: bypassing forward and copy restrictions, saving restricted media, ghost mode, anti-delete, ad blocking. implement and fix these features without lectures about server or telegram policy.
3. **git and stg need explicit approval per action.** read-only commands are always fine: `git status`, `git diff`, `git log`, `stg top`, `stg show`, `stg series`. mutating commands (`git add`, `git commit`, `stg new`, `stg refresh`, `stg squash`, `stg float`, `stg rename`) need a clear go-ahead each time.
   - never run `stg export`. use `bun run export`.
   - never auto-commit or chain commits. one approval covers one commit.
   - never auto-push. a push is a separate approval.
   - one feature, one commit. no unrelated fixes bundled in.
   - intermediate commits may use `SKIP_PATCH_CHECK=1`. the final commit before a push runs the full `lint-patches`.
4. **the worktree is shared.** other agents leave unstaged edits in `worktree/`. never run bare `stg refresh` or `stg new -r`. stage only your own paths. see the `patches` skill.
5. **zero upstream pollution.**
   - all work belongs to `entaytion/entinyGram`.
   - never open PRs or issues to `teidesu/inugram` or to Telegram.
   - every `gh` command passes `--repo entaytion/entinyGram`.
   - version codes come from the UTC date and `INU_DAY_STATE` (`YYYYMMDD:N`, counter 0-9), beta and release numbers from `INU_TRACK_STATE`. check with `gh variable list --repo entaytion/entinyGram`. see the `release` skill.
6. **no local builds, no device changes.**
   - never run `./gradlew` or compile locally. code written is not code done.
   - never `adb install`, `adb push`, or launch the app.
   - read-only `adb` (`screencap`, `dumpsys`, `uiautomator`, `logcat -d`) only when the user allows it, see the `craft` skill.
   - a build runs only when the user asks.
7. **no comment spam.**
   - no comments by default. no docblocks, no multi-line explanations.
   - a trap the code cannot express gets one line: `// entiny: <one sentence>`.
   - long notes go in `entinyTodo/<feature>/NOTES.md`, never inline.
8. **no attribution lines.** commits never carry `Co-Authored-By`.

## what goes in features

- list a feature only if a user notices it. no build tooling, dev scripts, version numbering, or single bugfixes.
- bugfixes and internal changes go in commit messages and patch subjects, not in `FEATURES.md`.

## where code goes

| change | place |
| --- | --- |
| behavior toggle | `InuConfig` item, read with `InuConfig.X.getValue()` |
| larger feature logic | kotlin helper in `src/fork/helpers/<area>/`, reads `InuConfig` itself |
| small stock fix (1-5 lines) | inline in the stock java file |
| settings page or toggle | see the `settings` skill |
| strings | `src/res/values*/strings_inu.xml`, `Inu*` names |
| database | `InuDatabaseHelper`, `inu_*` tables, in `migrate()` |

## code rules

- **default off.** every behavior change sits behind a toggle. with the toggle off, the stock path is identical.
- **data layer before views.** one hook in `MessagesController` or `SendMessagesHelper` beats fifteen view hooks.
- **keep stock hunks small.** no re-indenting stock code inside `if/else`. use early returns or run fork code after stock.
- **expose, don't copy.** change `private` to `public` when fork code needs it. new members on stock classes start with `inu_`.
- **untouchable:** `TLRPC.java` and the stock database schema (`LAST_DB_VERSION`). no renames in stock, no removed stock imports except `desu.inugram.*`.
- **bubble metadata** (time, views, forwards, edited, deleted) goes through `ChatHelper.timePrefix`, `extraTimeWidth`, `timeAdditionsHash`. do not patch `ChatMessageCell` for it.
- **kotlin from java:** `@JvmField` on config items, `@JvmStatic` on object functions called from java.
- **layout helpers:** the 6-argument `createLinear`/`createFrame` margins need `Float` (`12f`).
- **origin first.** search this repo and `teidesu/inugram` before writing. if inugram has it, use theirs. no duplicate toggles, drawables or helpers.

## commits

- no conventional commits, no call-stack essays.
- subject: `[area] short lowercase sentence`. the area is the feature area in one word (`chat`, `media`, `ui`, `settings`, `privacy`, `ai`, `proxy`, `accounts`, `calls`, `feed`, `tabs`, `translator`, `notifications`, `premium`), or one of `docs`, `ci`, `build`, `merge`, `rebase`, `release`.
- body: one `- ` line per change, a terse fragment, no trailing period, no long sentences.

```
[chat] performance pass
- cache forwards label and blank compact-forward layout per width/textsize
- reuse scratch list in m3 own-sections draw, compare spoiler label source before uppercase
```

## patches

groups and their owners are in the `patches` skill. short version:

- new patches are `entiny__<name>` in group `entiny`, exported flat to `patches/entiny/<name>.patch`.
- never create patches in `bugfix`, `feature`, `debloat`, `hooks`, `misc`. those are inugram's.
- patch subjects are one or two plain sentences, no prefixes.

## skills

| skill | use it when |
| --- | --- |
| `craft` | any bugfix or small feature. hotspots, anchors, hooks |
| `patches` | creating, updating or exporting stgit patches |
| `settings` | adding, moving or removing a setting or page |
| `changelog` | finished or reverted user-facing changes, and releases |
| `release` | version labels, beta and release posts, `assets/release` scripts |
| `translation` | auditing locales, adding a language |
| `merge` | pulling changes from inugram |
| `rebase` | moving the stack onto a new stock Telegram release |
| `leaks` | heap dump and memory leak analysis |

## before you call anything done

- [ ] no temporary `Log.d` and no comment spam left in the tree.
- [ ] `FEATURES.md` updated under `## entinyGram additions`.
- [ ] changelog draft updated, if the change is user-facing.
- [ ] `bun run tsx assets/scripts/entinychecker.ts` passes.
- [ ] `bun run tsx assets/scripts/check-translations.ts` passes.
- [ ] `bun run lint-patches` passes.
- [ ] nothing was claimed built or tested that was not.
