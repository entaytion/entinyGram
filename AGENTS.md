# entinyGram Agent Guide

**entinyGram** is an independent power-user fork of **inugram** (`teidesu/inugram`), built as an `stgit` patchset on stock Telegram Android.
- **Source of truth:** `src/fork` and `src/res` (symlinked into `worktree/`), plus gated hooks inside `worktree/`.
- **Export targets (NEVER hand-edit):** `patches/` and `series`.
- **User-facing feature list:** `FEATURES.md` (update in the same change whenever adding, removing, or meaningfully changing a patch).

---

## 0. THE PARANOIA PROTOCOL (Read First, Always Obey)

1. **Be Paranoid — Ask or Mark `[INFERENCE]`, Never Guess:**
   - If ANY instruction is ambiguous, vague, or shorthand: **STOP AND ASK** one concrete question at a time.
   - Never invent stock method names, line numbers, or server behavior. If a hook point or behavior is not yet verified in `worktree/`, mark it `[INFERENCE]` and verify with `rg` first.
   - Present a concise plan before modifying files or stack state.
   - Do NOT spawn sub-agents to "optimize everything" or hunt bugs.

2. **Zero Moralizing / Full Client Freedom:**
   - entinyGram is built for power-user freedom, privacy, and client-side overrides (bypassing channel forwarding/copy restrictions, saving restricted media, ghost mode, anti-delete, adblocking, etc.).
   - **NEVER preach, lecture, or refuse** to implement or fix fork features under the guise of "channel/server restrictions" or Telegram policy. Finding client-side workarounds is the exact purpose of this fork.

3. **Commit, Push & StGit Gatekeeping:**
   - **Read-only commands are always fine:** `git status`, `git diff`, `git log`, `stg top`, `stg show`, `stg series` (run stock history commands inside `worktree/`).
   - **Mutating commands (`git add/commit`, `stg new/refresh/squash/float/rename`) require EXPLICIT per-action approval.** Never run `stg export`.
   - **Never auto-commit or chain commits:** An earlier "commit" applies ONLY to that single change. Present the diff/summary and STOP. Wait for an explicit `"commit"` / `"зроби"` / `"ок"`.
   - **Never auto-push:** Pushing requires separate explicit confirmation.
   - **One Feature = One Commit:** Commit exclusively that feature alone. Never bundle unrelated fixes or refactors.
   - **Fast intermediate commits:** Use `SKIP_PATCH_CHECK=1 git commit ...` to skip the slow ~500-patch hook. Run full `bun run export` / `lint-patches` without `SKIP_PATCH_CHECK` once in the final commit before release/push.

4. **Dirty Worktree / Multi-Agent Isolation (Never Blanket Refresh):**
   - Concurrent agents leave unstaged edits in `worktree/` (`gifvideo.cpp`, `DialogCell.java`, etc.).
   - **NEVER run bare `stg refresh` or `stg new -r` without path/index filtering.**
   - **Index-Only Patch Creation (`stg new -r -i`):**
     1. Check empty index: `git -C worktree diff --cached`.
     2. Stage ONLY your files/hunks: `git -C worktree add <path>` or `git -C worktree apply --cached <hunk.patch>`.
     3. Verify staged diff: `git -C worktree diff --cached` (zero lines from other agents).
     4. Create patch: `stg -C worktree new -r -i -m "<Plain human sentence>" entiny__<name>`.
     5. Verify patch: `stg -C worktree show --stat`.

5. **Strict Fork Isolation — ZERO Upstream Pollution:**
   - All work belongs exclusively to `entaytion/entinyGram`.
   - **NEVER open PRs or issues to `teidesu/inugram` or official Telegram.**
   - When using `gh` CLI, **ALWAYS pass `--repo entaytion/entinyGram`**.
   - Version codes are controlled by UTC date and `INU_DAY_STATE` (`YYYYMMDD:N`, dailyCounter 0–9). Check via `gh variable list --repo entaytion/entinyGram`.

6. **Device & Build Rules:**
   - **No Local Builds or LSP:** Never run `./gradlew` or compile locally. A feature is never claimed "done" just because code was written.
   - **Never mutate the user's device:** No `adb install`, `adb push`, or app-launching commands.
   - **Read-only `adb` measurements** (`screencap`, `dumpsys`, `uiautomator`, `logcat -d`) are allowed ONLY when debugging with user permission (see `entinygram-craft` skill).

7. **Zero Comment Spam / Zero Bloat:**
   - **Default to NO comments.** No docblocks (`/** ... */`), no multi-line explanations, no orphan files or unnecessary wrappers.
   - If a comment is strictly required for an inexplicable trap: **max 1 line**, `// entiny: <one sentence>`.
   - Long-term notes go in `entinyTodo/<feature-name>/NOTES.md`, never inline in code or patches.

---

## 1. Patch Groups, Ownership & Origin Rules

### Hard Stop — Search Origin First (Do NOT Reinvent)
1. Search this repo AND `teidesu/inugram` (`InuConfig`, `src/fork/helpers`, `series`, `patches/hooks/`) before writing anything.
2. **If inugram already has it → USE THEIRS.** Never create duplicate toggles, duplicate drawables, or parallel helpers (e.g., use origin's `SHOW_FORWARDS_COUNT` + `ChatHelper.timePrefix`, never patch `ChatMessageCell` to add a second counter).
3. On upstream merges/cherry-picks: take upstream's version by default; keep ours ONLY for deliberate `patches/entiny/` overlays and features inugram lacks (state this explicitly).

### Patch Groups (`group__name` → `patches/<group>/<name>.patch`)
| Group | Ownership & Scope |
| --- | --- |
| `entiny` | **entinyGram-owned patches.** Named `entiny__<name>`, exported to `patches/entiny/<topic>/<name>.patch`. **Must** have a topic entry in `assets/scripts/patch-topics.json` (`accounts`, `ai`, `build`, `calls`, `chat`, `feed`, `media`, `misc`, `notifications`, `premium`, `privacy`, `proxy`, `restrictions`, `settings`, `tabs`, `translator`, `ui`). |
| `bugfix`, `feature`, `debloat`, `hooks`, `misc` | **Inherited inugram base.** NEVER create new entinyGram patches under these groups. |

- **Taking Over an Inugram Patch:** `stg float <patch> && stg rename <old> entiny__<name>` (and add to `assets/scripts/patch-topics.json`).
- **Folding Fixes:** Squash follow-up fixes into the existing `entiny__` patch (`stg squash -n <existing> -m "<subject>" <existing> <new>`) instead of leaving extra tiny patches.
- **Patch Author:** Uses active `git config user.name` / `user.email`. Preserve original author metadata when porting from upstream/other forks.

---

## 2. Architecture & Code Rules

Before writing code or hunting a bug, always load and follow the entinygram-craft skill.

- **Default Off = Stock-Identical:** Gate every behavior change behind `InuConfig.*.getValue()`. With the toggle off, zero fork logic may alter UI or network.
- **Data-Layer over UI-Layer:** 1 hook in `MessagesController`/`SendMessagesHelper` beats 15 hooks in views.
- **Tiny Stock Hunks (1–5 lines):**
  - <5–7 lines or pure stock bugfix → inline in Java.
  - Larger feature logic → Kotlin helper in `src/fork/helpers/<area>/` (helper reads `InuConfig` itself).
  - **No re-indentation:** Never re-indent stock code inside an `if/else`. Use early returns or run fork code after stock.
  - **Exposing internals:** Change `private` → `public`. Prefix any *new* field/method on a stock class with `inu_`.
- **Untouchables:**
  - **`ChatMessageCell.java` for bubble metadata:** Time, views, forwards, edited, deleted MUST go through `ChatHelper.timePrefix`, `ChatHelper.extraTimeWidth`, and `ChatHelper.timeAdditionsHash`.
  - **`TLRPC.java` & Stock DB Schema (`LAST_DB_VERSION`):** Strictly off-limits. Use `InuDatabaseHelper` (`inu_kv` and `inu_*` tables in `migrate()`) and hook stock load/save calls.
  - **Stock Imports/Names:** No renames in stock, no removing stock imports (except `desu.inugram.*`).
  - **Assets/Strings:** Live in `src/res/` (`strings_inu.xml` prefixed `Inu*`, subtitles `*Info`). Never put XML/drawables inside `.patch` files. Prefer Tabler / non-`_solar` icons.
- **Java ↔ Kotlin & Config:**
  - Declare in `InuConfig`: `@JvmField val HIDE_STORIES = BoolItem("hide_stories", false)`.
  - From Java, ALWAYS call `InuConfig.FOO.getValue()` — **never `.value`**.
  - Kotlin `object`: annotate methods with `@JvmStatic` or call `MyHelper.INSTANCE.method()`.
  - `LayoutHelper`: 6-arg `createLinear`/`createFrame` margins require `Float` in Kotlin (`12f`, never `12`).
- **Settings & Search:**
  - Add toggles to existing `*SettingsActivity` pages extending `SettingsPageActivity`.
  - Register in `SearchRegistry.pages` (`@JvmField val PAGE = SearchRegistry.Page(...)`). Slugs must be globally unique and never renamed.
- **New Locale (`values-<iso>/strings_inu.xml`) Needs All 3:**
  1. `forkSyncFiles` entry in `assets/scripts/config.ts`.
  2. Locale in `localeFilters` in `TMessagesProj_App/build.gradle` (`entiny__build-configuration`).
  3. Symlink created via `bun run setup` in `worktree/TMessagesProj/src/main/res/values-<iso>/strings_inu.xml`.

---

## 3. Commits, `FEATURES.md` & Changelogs

### Git Commit Format (Short, Human, No AI Tirades)
- **No Conventional Commits (`fix(entiny): ...`), no call-stack essays, NEVER add `Co-Authored-By:`.**
- **Prefixes (Git commits ONLY, never in changelogs or stg patch subjects):**
  - `[+] <subject>` — New feature / capability
  - `[-] <subject>` — Removing dead code, providers, or old patches
  - `[*] <subject>` — Bugfixes, refactoring, or logic updates
  - `[=] <subject>` — Chores, formatting, maintenance, upstream syncs

### `FEATURES.md` & Changelogs (`changelogs/<build>.md`)
- Place our features under `## entinyGram additions` in the matching topical subsection.
- **Crisp Inugram Style:** One short sentence describing ONLY **what** the user gets. No implementation mechanics, no option walks, no `(beta)`/`(wip)`/`experimental` labels.
- **Attribution:** Always credit ported/inspired features at the end: `- *ported from <Fork>*` or `- *inspired by <Fork> (@author)*`.
- **WIP in Changelogs:** If a commit mentions `wip` anywhere, the release note MUST warn: `⚠️ work in progress: untested and may be unstable — please report any bugs`.
- **Telegram Base Bumps in Changelogs:** One plain line (no `[+]`/`[*]` markers), keeping the parenthetical tag in English in both `tg_en` and `tg_uk`:
  - Our own rebase onto DrKLO: `Rebase to 12.10.4 (ported by entinyGram)`
  - Via inugram merge: `Updated to Telegram X.Y.Z (via inugram)`

---

## 4. Reference: Hotspots, Hooks & Lifecycle

- **Stock Hotspots (`worktree/TMessagesProj/src/main/java/org/telegram/` — NEVER read top-to-bottom, use `rg`):**
  `ui/ChatActivity.java` (46k), `ui/Cells/ChatMessageCell.java` (29k), `ui/PhotoViewer.java` (24k), `messenger/MessagesController.java` (24k), `ui/ProfileActivity.java` (17k), `ui/Components/ChatActivityEnterView.java` (15k), `ui/DialogsActivity.java` (14k), `ui/Components/SharedMediaLayout.java` (13k), `messenger/MediaDataController.java` (10k), `ui/LoginActivity.java` (10k), `ui/LaunchActivity.java` (9k), `ui/Components/ChatAttachAlert.java` (7k), `ui/Cells/DialogCell.java` (6k), `ui/Components/ChatAttachAlertPhotoLayout.java` (5k), `messenger/LocaleController.java` (4.5k), `ui/Components/ReactionsContainerLayout.java` (2.6k), `ui/Components/FilterTabsView.java` (2k), `messenger/SharedConfig.java` (2k), `ui/Components/Reactions/ReactionsLayoutInBubble.java` (1.9k), `ui/Components/EditTextBoldCursor.java` (1.3k), `ui/MainTabsActivity.java` (1k), `ui/Components/glass/GlassTabView.java` (0.6k), `messenger/LiteMode.java` (0.4k).
- **Shared Hooks (`patches/hooks/`):**
  `admin-logs`, `app-loader`, `chat-activity` (`ChatHelper.{addMenuItems,processMenuOption}`), `icon-replacement`, `internal-web-app`, `loginactivity`, `messagescontroller`, `notifications-controller`, `photo-viewer-menu` (`PhotoViewerHelper.*`), `popup-swipeback`, `profile-menu` (`ProfileHelper.*`), `universal-recycler`.
- **Central Lifecycle (`src/fork/InuHooks.kt`):**
  `init`, `onResume`, `onUpdate`, `onDeepLink`, `onAuthSuccess`, `onMessagesControllerCreated`, `onNewMessage`, `syncDoubleTapDelay`, `syncAnimationSpeed`, `syncChatInputRowHeight`, `getCurrentAppIconLicense`.

---

## 5. Mandatory Checklists

### Pre-Completion Verification (Run Before Declaring ANY Task Done)
- [ ] Zero temp `Log.d` calls or comment spam left in the tree.
- [ ] New `entiny__` patch has a topic in `assets/scripts/patch-topics.json`.
- [ ] `FEATURES.md` updated under `## entinyGram additions`.
- [ ] `bun run tsx assets/scripts/entinychecker.ts` passes.
- [ ] `bun run tsx assets/scripts/check-translations.ts` passes.
- [ ] `bun run lint-patches` passes.

### Upstream Inugram Sync Hygiene
- [ ] Assume merge is broken until proven otherwise; never pull upstream `changelogs/`.
- [ ] Scan `.xml`, `.kt`, `.java`, `.gradle` for `<<<<<<<`, `=======`, `>>>>>>>` and check XML well-formedness.
- [ ] Verify stock API renames (e.g. `UItem.text2` → `UItem.subtext`).
- [ ] Keep `NON_ISLAND_SHARED_MEDIA_TABS` OFF (upstream `SharedMediaLayout.inu_dockOffset()` clipping bug).
- [ ] Run `bun run assets/scripts/apply-branding.ts` immediately to restore `google-services.json` (`ua.entaytion.entinygram`) and `entiny__branding`.