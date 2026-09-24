# entinyGram Agent Guide

**entinyGram** is our independent fork of **inugram** (`teidesu/inugram`), which is built as
an stgit patchset on stock Telegram Android. Fork code lives in `src/kotlin` and `src/res`
(symlinked into the `worktree/`). `patches/` and `series` are stgit export targets, not the source of truth.

`FEATURES.md` is the user-facing list of fork features/bugfixes. Keep it in sync —
when adding, removing, or meaningfully changing a patch, update `FEATURES.md` in the same change.

---

## 0. THE PARANOIA PROTOCOL (Read First, Always Obey)

1. **Be Paranoid — Ask 100 Times Before Doing:**
   - If ANY instruction is ambiguous, vague, or shorthand: **STOP AND ASK**. Never assume, guess, or take liberties.
   - Present a clear, concise plan of action before modifying files or stack state.
   - Better to clarify 100 times than to introduce unrequested changes, corrupt patches, or touch external remotes.

2. **Commit & Push ONLY on Explicit, Per-Action Approval & Per-Feature Scope:**
   - **Never auto-commit.** An earlier "commit this" applies ONLY to that single change. It NEVER grants permission to commit follow-up fixes or chain commits on momentum.
   - **One Feature = One Commit:** When adding a new feature, commit EXCLUSIVELY that feature alone. Never lump unrelated fixes, refactors, or multiple features into one kitchen sink. (Global cleanup/chores can be batched before release).
   - Present the diff/summary and STOP. Wait for an explicit "commit" / "зроби" / "ок".
   - **Never auto-push.** Pushing to remote requires its own separate explicit confirmation.

3. **Strict Fork Isolation — ZERO Upstream Pollution:**
   - All tasks, code, and bugfixes belong **exclusively** to THIS fork (`entinyGram`).
   - **NEVER open Pull Requests or issues to upstream inugram (`teidesu/inugram`) or official Telegram.** We do not work for upstream and do not submit our changes to them.
   - When using `gh` CLI for ANY reason, **ALWAYS explicitly pass `--repo entaytion/entinyGram`**. Never rely on default remote resolution (gh can default to upstream and create an irreversible public PR).
   - Our standard workflow is local commit and direct push to `main` on `origin` (entaytion/entinyGram) upon explicit approval — no PRs.

4. **Zero Comment Spam / Zero Bloat (Keep it Clean):**
   - **Default to NO comments at all.** Only comment when code is completely inexplicable.
   - If a comment is strictly required: **max 1 line**, tagged `// entiny: <one sentence>`.
   - **NEVER** write multi-paragraph explanations, rationale tirades, or class/function `/** ... */` docblocks.
   - If design rationale or notes are genuinely needed for future reference, put them in a scratch file under `entinyProto/<feature-name>/NOTES.md`, NEVER inline in code or patches.
   - No orphan files, no temporary experiment junk, no unnecessary abstractions or wrappers.

5. **Never Touch the User's Device:**
   - No `adb install`, `adb shell`, `adb logcat`, `adb push`, or app-launching commands.
   - Ask the user to run/install and paste logs if needed.

---

## 1. Golden Rules (Never Violate)

1. **entinyGram Owns `patches/entiny/`:** All our features, bugfixes, and tweaks live under `patches/entiny/` and are named `entiny__<name>`. Upstream groups (`bugfix`, `feature`, `debloat`, `hooks`, `misc`) belong to the inugram base. NEVER create a new entinyGram patch under `feature/` or `debloat/`.
2. **Search Origin First (Do NOT Reinvent):** Check if stock or inugram already has the feature before writing anything (`InuConfig`, `src/kotlin/helpers`, `series`). If inugram has it, use theirs. Never build parallel toggles, duplicate drawables, or rewrite stock hotspots.
3. **Stock Patches Stay Tiny & Code-Only:** Only 1–5 lines of Java hooks/guards in `worktree/TMessagesProj/src/main/java/...`. Real logic goes into Kotlin helpers (`src/kotlin/helpers/`). **NEVER put drawables, assets, or XML resources inside `.patch` files.** All assets live in `src/res/`.
4. **Edit `worktree/` Directly:** Never hand-edit `patches/*.patch` or `series` — they regenerate from stgit.
5. **Do Not Run `stg` or `git` Yourself:** Unless explicitly asked. Read-only `stg top` / `stg show` is fine. NEVER run `stg export`.
6. **Default Off = Stock-Identical:** Every behavior change must be gated behind `InuConfig.*.getValue()`. Default is false/stock.
7. **Take Over Existing Patches Properly:** When modifying an inherited inugram patch, float and rename it first: `stg float <patch>`, `stg rename <old> entiny__<name>` before refreshing.
8. **Never Touch Stock Hotspots for Metadata:** Bubble metadata (time, views, forwards, edited, deleted) MUST go through `ChatHelper.timePrefix`, `ChatHelper.extraTimeWidth`, and `ChatHelper.timeAdditionsHash`. Never patch `ChatMessageCell.java` for this.
9. **Untouchables:**
   - Never touch `TLRPC.java` (auto-generated, rebasing is hell).
   - Never touch stock DB schema or `LAST_DB_VERSION` (fork state goes in `inu_*` tables / `inu_kv` via `InuDatabaseHelper`).
   - No renames in stock, no removing stock imports (except `desu.inugram.*`).
10. **No Local Builds or LSP:** Don't run `./gradlew` or try to compile locally.
11. **Patch Author Name:** Exported patches (`patches/**/*.patch`) must carry `From: Oleksii Kulinich <entaytion@gmail.com>`. Ordinary git commits do not need `--author`.
12. **Debug Logs:** Use `android.util.Log.d`, not `FileLog`.
13. **Icons:** Prefer non-`_solar` icons when an alternative exists. Tabler pack preferred.
14. **Mandatory Pre-Completion Verification:** Run before declaring ANY task done:
    - `bun run tsx scripts/entinychecker.ts` (catches duplicate `SearchRegistry` slugs and unused variants)
    - `bun run tsx scripts/check-translations.ts` (catches missing/stale translations)
    - `bun run lint-patches` (catches patch series overwrites)
    - Update `FEATURES.md` under `## entinyGram additions` (never buried unmarked in `## inuGram additions`).
15. **Release & Version Codes:** Controlled by UTC date and `INU_DAY_STATE` (`YYYYMMDD:N`), dailyCounter 0–9. Check with `gh variable list --repo entaytion/entinyGram`.
16. **Commit Format & Granularity — No AI Essays (`[+]`, `[-]`, `[*]`, `[=]`):**
    - **Never write Conventional Commits or AI Tirades:** No `fix(entiny): ...` essays explaining internal class call stacks or why listeners were changed. Keep it short, human, and directly to the point ("змістовно, але не канцелярно").
    - **NEVER add `Co-Authored-By: ...`** or AI email signatures.
    - **Commit Prefixes:**
      - `[+] <subject>` — New feature / capability added (e.g. `[+] hijri calendar`).
      - `[-] <subject>` — Removing / dropping dead code, providers, or old patches.
      - `[*] <subject>` — Bugfixes, refactoring, or logic modifications (e.g. `[*] translator logic`).
      - `[=] <subject>` — Chores, formatting, maintenance, or upstream syncs (e.g. `[=] sync with upstream inugram`).
    - **Body Structure:** Short description or concise `- ` bullet points:
      ```text
      [+] hijri calendar

      - add hijri calendar support to date picker
      - gate behind InuConfig.HIJRI_CALENDAR
      ```
      ```text
      [*] translator logic

      - collapse provider resolution into unified path
      - fix entity marker resolution indexing
      - drop dead TranSmart and Lingo providers
      ```
    - **One Feature Per Commit:** Each feature gets its own dedicated commit containing EXCLUSIVELY that feature. Batch commits are reserved only for pre-release cleanup or mass sync passes.
17. **Confirm Bug Repro in Unpatched Worktree:** Before treating a visual/behavior issue as a patch regression, verify if stock behaves the same way.
18. **Prefer Data-Layer over UI-Layer:** One hook in a controller beats fifteen hooks in views.
19. **Attribution & Borrowing from Other Forks:** When porting or adapting an existing feature or implementation from another open-source Telegram fork (e.g. AyuGram, ExteraGram, CherryGram, Nekogram, OwlGram, NagramX, etc):
    - Always respect the original creators and credit the source at the end: e.g. `*inspired by / ported from <Fork> (@author)*`.
    - Never claim borrowed implementations as built entirely from scratch.
20. **Concise `FEATURES.md` Entries (Inugram Style):**
    - Keep `FEATURES.md` feature descriptions short, crisp, and directly to the point — mirroring the concise inugram style.
    - **NEVER write essays, design tirades, or multi-paragraph changelog entries in `FEATURES.md`.** One clear sentence or a few compact sub-bullets explaining what the user gets.
21. **WIP Features in Changelogs:** If a commit mentions `wip` (work in progress) anywhere (subject or body), every changelog / release note entry for that feature MUST say the feature is untested and unstable, and ask users to report bugs (e.g. "⚠️ work in progress: untested and may be unstable — please report any bugs").
22. **Telegram Version Bumps in Changelogs:** When a commit updates the stock Telegram base, say who did it: our own rebase onto DrKLO (e.g. `[*] rebase to 12.10.4 (7099)` done by us before inugram) → "Updated to Telegram X.Y.Z (ported by entinyGram)"; an inugram merge that brings the new base → "Updated to Telegram X.Y.Z (via inugram)". Never credit inugram for a base update we ported ourselves.

> You are allowed to violate these rules only if the user explicitly asks.

---

## 2. Patch Groups & Ownership

Format: `group__name` → `patches/<group>/<name>.patch`. Commit subject = plain human sentence (`Allow editing by double tapping a message`).

| Group | Ownership & Scope |
| --- | --- |
| `entiny` | **entinyGram-owned patches** — our work, not inugram's. Ghost mode, adblock, save-deleted, branding, updater, etc. Live in `patches/entiny/`. Inugram merges never touch this folder. **Always include this group** when listing, searching, auditing, or exporting patches. |
| `bugfix` | Inherited inugram base: upstream bug fixes. |
| `feature` | Inherited inugram base: upstream user-facing capabilities. |
| `debloat` | Inherited inugram base: hides/disables stock behavior behind a toggle. |
| `hooks` | Inherited inugram base: thin stock hooks for fork code to attach to. |
| `misc` | Inherited inugram base: build, branding, infra. |

**A brand-new entinyGram feature is ALWAYS `entiny__<name>`, NEVER `feature__`/`debloat__`/`bugfix__`/`hooks__`/`misc__`.**
Naming our patch `feature__x` misclassifies ownership, breaks merge tracking, and causes accidental overwrites.

**Taking Over an Inugram Patch:**
When you meaningfully modify an inugram base patch, move it into `entiny/`:
```bash
stg float <patch>
stg rename <old> entiny__<name>
```
At that point you own its maintenance.

**Documentation in `FEATURES.md`:**
Every entinyGram feature gets its own bullet under `## entinyGram additions` in `FEATURES.md`, in the topical subsection that fits it (privacy & protection / restricted features / power-user tools / debloat & premium noise) — never left as an unmarked line inside `## inuGram additions`.
- Keep descriptions short, clear, and human-friendly (mirroring inugram style). No AI essays.
- If ported or inspired by another fork, always append a short credit (e.g. `*ported from <Fork>*` or `*inspired by <Fork> (@author)*`).

---

## 3. Writing Patches & Feature Workflow (The write-patches Rule)

### Hard Stop — Search Origin First
Before writing **any** new feature or toggle:
1. Search **this repo**: `InuConfig`, `src/kotlin/helpers`, `series`, `patches/hooks/`.
2. Search **origin** (`teidesu/inugram`): same names, plus the helper that owns the surface (`ChatHelper` for bubbles, `ProfileHelper` for profile menu, `PullActionHelper` for dialogs pull, etc.).
3. If origin already has it → **keep/use theirs**. Do NOT add `patches/entiny/<same-thing>.patch`, a second `InuConfig` toggle, a second drawable, or a second settings row.
4. Duplicating origin is not "our version" — it is two implementations that fight and break on every upstream merge.

### Where Code Goes

| What | Where |
| --- | --- |
| Feature logic | `src/kotlin/helpers/<area>/` (`ChatHelper`, etc.) |
| Toggle | `InuConfig` + existing settings page (`MessagesSettingsActivity` for bubble chrome) |
| Strings / drawables | `src/res/` — never inside a `.patch` |
| Stock Java | 1–3 line hook: `if (InuConfig.X.getValue()) Helper.foo(this);` or a call already in `patches/hooks/` |
| Bugfix of a stock class | inline in that Java file (this is *not* a feature) |

### Bubble Metadata Rule (ChatMessageCell is Off-Limits)
Bubble metadata (time, forwards, views, edited, deleted) **must** go through:
- `ChatHelper.timePrefix`
- `ChatHelper.extraTimeWidth`
- `ChatHelper.timeAdditionsHash`

Those three already have Java hooks in the cell. Opening `ChatMessageCell.java` for a new icon or text next to the time is automatically wrong unless you first prove those hooks cannot express it.

### Case Study: Forward Count (Do Not Reinvent Origin)
- **What we wanted:** Show how many times a post was forwarded, next to the timestamp.
- **Origin already did this:** `InuConfig.SHOW_FORWARDS_COUNT` + `ChatHelper.timePrefix`.
- **We did it anyway (dropped):** Wrote `patches/entiny/post-forwards-count.patch` — 53 lines in `ChatMessageCell.java`, 4 new fields on the 29k-line cell, custom `ic_repost_mini`, second toggle `SHOW_POST_FORWARDS_COUNT`. It lacked `timeAdditionsHash`, so the cell failed to relayout on appearance.
- **Origin's clean approach (kept):** 0 lines in `ChatMessageCell.java`. Added forwards count into `ChatHelper.timePrefix`, handled `extraTimeWidth` and `timeAdditionsHash`.
- **Side by side:**
  | | Ours (dropped) | Origin (kept) |
  | --- | --- | --- |
  | Where | `ChatMessageCell.java` (4 call sites) | `ChatHelper.kt` |
  | Stock hotspot | Yes (29k lines, #1 rebase casualty) | No |
  | Toggle | `SHOW_POST_FORWARDS_COUNT` (dup) | `SHOW_FORWARDS_COUNT` |
  | Drawable | New `ic_repost_mini` | Stock `mini_forwarded` |
  | Relayout | Missed `timeAdditionsHash` | Hash + extra width |
  | Origin merge | Conflicts + broken braces | Untouched |
  | Java lines | 53 lines in cell | 0 lines in cell |

**Why origin's is better:**
1. One owner: Timestamp extras already live in `ChatHelper`.
2. Layout works: Hash + width invalidate correctly.
3. The cell is poison: Every Telegram update touches `ChatMessageCell`. 53 lines there is endless rebase conflict debt.
4. One toggle: Two settings rows fight in preferences.
5. It already existed.

We deleted `post-forwards-count`. Never bring it back.

### Checklist Before Writing a Patch
- [ ] Origin does **not** already have this (`InuConfig` / helpers / `series`).
- [ ] Existing helper/hook cannot express it (`timePrefix`, `addMenuItems`, etc.).
- [ ] New Java is a guard + helper call, not a rewrite of stock.
- [ ] New resources are in `src/res/`, not in the patch.
- [ ] Default-off is stock-identical.
- [ ] You are **not** editing `ChatMessageCell` for timestamp-adjacent UI.

### Minimal Wiring Pattern
```java
public void doSomething() {
    if (desu.inugram.InuConfig.MY_TOGGLE.getValue()) {
        MyHelper.handle(this);
        return;
    }
    // ...stock code unchanged...
}
```
- Guard goes **before** stock, early-returns when fork takes over.
- For mode-dependent behavior, prefer an `if`/`else` wrapper with **no re-indentation** of the stock branch — keeps rebases trivial.
- When extending behavior rather than replacing it, **run fork logic after** the stock block. Don't rewrite stock.
- When figuring out stock code history/regressions, make sure to run git **inside** the `worktree/` dir. Root dir does NOT track stock history.

### Exposing Stock Internals
- `private` field/method needed from fork? Change to `public`. That is the whole patch.
- Adding a new field/method/overload to a stock class? Prefix `inu_` (e.g. `inu_addTab`, `inu_internalType`).
- Prefer exposing over adding. Adding to a base class is rebase-fragile — look for an existing extension point first.

### Helper Boundary
- <~5–7 lines of logic → inline in the patch.
- Bigger → extract to a Kotlin helper in `src/kotlin/helpers/<area>/`.
- Helper reads `InuConfig` itself; don't pass config values as parameters.
- One helper per feature area (e.g. `FolderHelper` owns icons + DB + layout + drawing).

---

## 4. Commonly Touched Stock Files

Paths under `worktree/TMessagesProj/src/main/java/`. Files >2k lines: never read top-to-bottom. Use `rg` for the exact symbol, then read with small offsets.

| File | ~Lines | Owns |
| --- | ---: | --- |
| `org/telegram/ui/ChatActivity.java` | 46k | Chat screen |
| `org/telegram/ui/Cells/ChatMessageCell.java` | 29k | Message bubble |
| `org/telegram/ui/PhotoViewer.java` | 24k | Photo/video viewer + preview for ChatAttachAlert |
| `org/telegram/messenger/MessagesController.java` | 24k | Messages domain state |
| `org/telegram/ui/ProfileActivity.java` | 17k | Profile screen |
| `org/telegram/ui/Components/ChatActivityEnterView.java` | 15k | Message input — voice, attach, text |
| `org/telegram/ui/DialogsActivity.java` | 14k | Main page / dialogs list |
| `org/telegram/ui/Components/SharedMediaLayout.java` | 13k | Profile shared-media player |
| `org/telegram/messenger/MediaDataController.java` | 10k | Stickers, reactions, recent data |
| `org/telegram/ui/LoginActivity.java` | 10k | Login flow |
| `org/telegram/ui/LaunchActivity.java` | 9k | Root activity |
| `org/telegram/ui/Components/ChatAttachAlert.java` | 7k | Attachments panel |
| `org/telegram/ui/Cells/DialogCell.java` | 6k | Single dialog row |
| `org/telegram/ui/Components/ChatAttachAlertPhotoLayout.java` | 5k | Attach panel photo grid |
| `org/telegram/messenger/LocaleController.java` | 4.5k | i18n |
| `org/telegram/ui/Components/ReactionsContainerLayout.java` | 2.6k | Reactions bar in message menu |
| `org/telegram/ui/Components/FilterTabsView.java` | 2k | Folder tabs strip in DialogsActivity |
| `org/telegram/messenger/SharedConfig.java` | 2k | Stock prefs |
| `org/telegram/ui/Components/Reactions/ReactionsLayoutInBubble.java` | 1.9k | Inline reaction chips on messages |
| `org/telegram/ui/Components/EditTextBoldCursor.java` | 1.3k | Text input base |
| `org/telegram/ui/MainTabsActivity.java` | 1k | Main bottom tabs |
| `org/telegram/ui/Components/glass/GlassTabView.java` | 0.6k | Liquid-glass tab rendering |
| `org/telegram/messenger/LiteMode.java` | 0.4k | Perf flag presets |

---

## 5. Shared Extension Points (`patches/hooks/`)

Standalone hook patches expose surfaces that multiple features consume. Intentionally no user-visible effect on their own.

| Patch | What it Exposes |
| --- | --- |
| `admin-logs.patch` | Hooks inside admin logs activity |
| `app-loader.patch` | Custom `ApplicationLoaderImpl` instead of stock |
| `chat-activity.patch` | ChatActivity hooks — message menu (`ChatHelper.addMenuItems`/`processMenuOption`), `undoView`, etc. |
| `icon-replacement.patch` | Custom resource loader for icon replacement |
| `internal-web-app.patch` | `WebViewRequestProps.inu_internalType` + `WebAppHelper.getInternalBotName` |
| `loginactivity.patch` | Hooks inside LoginActivity |
| `messagescontroller.patch` | Access `MessagesController` instances as created |
| `notifications-controller.patch` | Hooks inside NotificationsController |
| `photo-viewer-menu.patch` | `PhotoViewerHelper.{addMenuItems,updateMenuItems,resetMenuItems,handleMenuClick}` + `inu_getCurrentPhotoFile` |
| `popup-swipeback.patch` | Foreground translation + touch coords on swipeback popup |
| `profile-menu.patch` | `ProfileHelper.addMenuItems` + `ProfileHelper.handleMenuClick` |
| `universal-recycler.patch` | Extra features in `UniversalRecyclerView` used by settings pages |

---

## 6. Central Lifecycle (`InuHooks`)

`src/kotlin/InuHooks.kt` dispatches generic lifecycle events. Feature logic stays in its own helper.

| Method | Called From | Purpose |
| --- | --- | --- |
| `init(Context)` | `ApplicationLoader.onCreate` | Bootstrap `InuConfig`, fonts, crash reporter, etc. |
| `onResume(LaunchActivity)` | `LaunchActivity.onResume` | Monet refresh, crash sheet |
| `onUpdate(TLObject?, Int)` | update dispatch | Fork `LoginHelper` hook; feeds status to `PresenceHelper` |
| `onDeepLink(LaunchActivity, Intent?)` | deeplink handling | Passcode + settings deeplinks |
| `onAuthSuccess(Int)` | login flow | Clear per-account passcode |
| `onMessagesControllerCreated(MessagesController, Int)` | `MessagesController.<init>` | Per-account setup (maps, pins, presence, recent chats, badge manifest) |
| `onNewMessage(TLRPC.Message, Int)` | `didReceiveNewMessages` observer | Generic message dispatch (fans out to `UpdateHelper` etc.) |
| `syncDoubleTapDelay()` | fork + `init` | Propagate `DOUBLE_TAP_DELAY` into gesture detectors |
| `syncAnimationSpeed()` | fork + `init` | Propagate `ANIMATION_SPEED` into animators |
| `syncChatInputRowHeight()` | fork + `init` | Propagate classic-ui row height/padding into EnterView statics |
| `getCurrentAppIconLicense()` | About page | Current launcher icon's license string |

---

## 7. `InuConfig` Pattern

```kotlin
@JvmField val HIDE_STORIES = BoolItem("hide_stories", false)
```
- Always `@JvmField` so Java sees a field, not `getHIDE_STORIES()`.
- Types: `BoolItem`, `IntItem`, `FloatItem`, `StringItem`, or subclass `Item<T>` for enums.
- From Java: `InuConfig.HIDE_STORIES.getValue()` — **never `.value`**.
- Pref key is snake_case. SharedPreferences name: `inugram`.

---

## 8. Database (`InuDatabaseHelper`)

- Stock schema and `LAST_DB_VERSION` are strictly off-limits.
- Fork versioning lives in `inu_kv`, managed by `InuDatabaseHelper`.
- Fork tables use `inu_*` prefix, created and migrated in `InuDatabaseHelper.migrate()`.
- Populate fork fields by **hooking** stock load/save calls — never edit stock SQL schemas.

---

## 9. Settings UI & Search Registry

- Extend `desu.inugram.ui.settings.SettingsPageActivity`.
- Add toggles to existing categories:
  - `AppearanceSettingsActivity` — general appearance
  - `ChatsSettingsActivity` — chat appearance, headers, menus
  - `MessagesSettingsActivity` — bubbles, reactions, sticker size
  - `DialogsSettingsActivity` — chat list appearance, pills, FAB
  - `AnnoyancesSettingsActivity` — removing stock annoyances
  - `BehaviorSettingsActivity` — general behavior
- **Settings Search (`SearchRegistry`):**
  - Each searchable `*SettingsActivity` declares `@JvmField val PAGE = SearchRegistry.Page(...)` in its companion.
  - Register in `SearchRegistry.pages`. Slugs MUST be globally unique (enforced by `entinychecker.ts`). Renaming a slug is breaking.

---

## 10. Strings & Assets

- Strings: `src/res/values/strings_inu.xml`. Keys prefixed `Inu` (`InuHideStories`). Info/subtitles end in `Info` (`InuHideStoriesInfo`).
- Assets: `src/res/drawable/`, `src/res/drawable-xxhdpi/`, `src/res/assets/`.
- New asset dir → register in `scripts/config.ts` → `forkSyncFiles`.

---

## 11. Java ↔ Kotlin Gotchas

- **`.value` vs `.getValue()`:** From Java, always call `InuConfig.FOO.getValue()`. `.value` is a Kotlin property wrapper.
- **Kotlin `object`:** Accessed as `MyHelper.INSTANCE.method()` from Java unless annotated with `@JvmStatic`.
- **`LayoutHelper` margins:** `createLinear` / `createFrame` margin parameters take dp. Overloads like the 6-arg `createLinear(w, h, l, t, r, b)` exist **only in the Float variant**. Always write `12f`, not `12`, to avoid Kotlin compile errors.
- **No re-indentation:** Never re-indent stock code to wrap it in an `if`. Use early returns or keep indentation identical to avoid merge conflicts.

---

## 12. Upstream Inugram Sync & Merge Hygiene

Upstream (`teidesu/inugram`) is an external dependency. Merges from upstream frequently overwrite branding or leave silent regressions.

1. **Assume the merge is broken until proven otherwise.**
2. **Never leave conflict markers:** Any `<<<<<<<`, `=======`, `>>>>>>>` is a hard stop.
3. **Restore Branding Immediately:**
   ```bash
   bun run scripts/apply-branding.ts
   ```
   Updates `google-services.json` (`ua.entaytion.entinygram`) and restores `entiny__branding`. Never edit `misc/build-support.patch` for branding.
4. **Post-Merge Checks:**
   - Scan for markers across `.xml`, `.kt`, `.java`, `.gradle`.
   - Check well-formedness of touched `res/values/*.xml`.
   - Verify stock API renames (e.g. `UItem.text2` → `UItem.subtext`).
5. **Known Upstream Bugs to Leave Off:**
   - `NON_ISLAND_SHARED_MEDIA_TABS` (`SharedMediaLayout.inu_dockOffset()` clips profile media tabs). Upstream bug; keep toggle OFF.