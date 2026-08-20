# Inugram Agent Guide

Inugram is a **patchset**, not a fork. `worktree/` is a stock Telegram checkout with
stgit patches applied on top. Fork code lives in `src/kotlin`/`src/res` (symlinked
into the worktree). `patches/` and `series` are export targets, not source of truth.

`FEATURES.md` is the user-facing list of fork features/bugfixes. Keep it in sync —
when adding, removing or meaningfully changing a patch, update `FEATURES.md` in
the same change.

## Golden rules (never violate)

1. **Edit `worktree/` directly.** Never hand-edit `patches/*.patch` or `series` — they regenerate from stgit.
2. **Do not run `stg` or `git` yourself** unless explicitly asked. Read-only `stg top` / `stg show` is fine. NEVER run `stg export`.
3. **Stock patches stay tiny & code-only.** Only Java wiring/hooks/guards in `TMessagesProj/src/main/java/...`. Real logic goes in `src/kotlin`. **NEVER include XML resources, drawables, or non-Java assets in stgit patches.** New icons, drawables, and XML resources MUST be placed in `src/res/drawable/` or `src/res/`, NOT tracked inside `patches/*.patch`. A patch touching only `src/**` or resource XMLs is WRONG.
4. **Default off = stock-identical.** Every behavior change gated behind an `InuConfig.*.getValue()` check. Verify every call site is gated.
5. **Check if stock or origin (inugram) already does it** before implementing a toggle (Lite Mode, `InuConfig`, `src/kotlin` helpers, `series`). Tell the user, don't silently re-implement. Duplicating origin is a hard error — see rule 19.
6. **Confirm bug repro in unpatched worktree** before treating a visual/behavior issue as a patch regression.
7. **No renames in stock. No removing stock imports** (except `desu.inugram.*`).
8. **Prefer data-layer patches over UI-layer** — one hook in a controller beats fifteen hooks in views.
9. **Never touch `TLRPC.java`** — auto-generated, rebasing changes there is hell.
10. **Never touch stock DB schema or `LAST_DB_VERSION`** — fork state goes in `inu_*` tables / `inu_kv` via `InuDatabaseHelper`.
11. **No LSP, no local build.** Don't try to compile.
12. **Debug logs use `android.util.Log.d`**, not `FileLog`.
13. **Prefer non-`_solar` icons** when an alternative exists.
14. **Format Commits Carefully.** Use the exact format: `type(scope): subject` followed by an empty line, an optional short summary paragraph, and an explicit bulleted list of changes starting with `- `. 
**Important for upstream syncs:** When syncing changes from upstream inugram, clearly denote it as `sync with upstream inugram` in the commit subject so the release notes AI knows to group it. For our own specific changes, list them clearly with `- ` bullets. If the changes are very minor, you can just write a single-line commit message without bullets.
Example:
```
chore(maintainer): migrate owned patches to entiny/ namespace...

...it-history stats crash, remove premium translation lock, and harden media-session lifecycle

- migrate 16 authored patches into patches/entiny/
- document entiny/ ownership and merge behavior in AGENTS.md
- fix InuDatabaseHelper SQLite crash: old_text -> text in inu_edit_history stats
```
15. **Release Bumping:** The APK `versionCode` and release tag are controlled solely by the `INU_BUILD` GitHub variable, not `gradle.properties`. To check the current build number, run `gh variable list --repo entaytion/entinyGram` in the terminal. To perform a major version jump (e.g., when upstream updates), instruct the user to update the variable via the browser, or execute: `gh variable set INU_BUILD --body <number> --repo entaytion/entinyGram`.
16. **Check stgit Stack Before Patch Operations:** Always check `stg top` and `stg series` BEFORE creating, modifying, or refreshing patches. Verify if a patch for the feature already exists in `patches/` or `series`. Never run `stg refresh` blindly on whatever patch happens to be at the top of the stack.
17. **Take Over Existing Patches Properly:** When modifying an existing inugram base patch, float and rename it (`stg float <patch>`, `stg rename <old> entiny__<name>`) BEFORE refreshing changes so that changes don't spill into unrelated patches or create duplicate entries in `series`.
18. **Commit & Push Only On Explicit Approval:** NEVER automatically run `git commit` or `git push` unless the user explicitly gives approval to commit or push. Always present the prepared changes and wait for user confirmation before executing git commits or pushes.
19. **Never duplicate origin. Never rewrite a hotspot for a feature origin already has.** If inugram already has the feature, take theirs — do not add a parallel `entiny/` patch, a second toggle, or a rewrite of `ChatMessageCell` / other 10k+ stock files. Bubble metadata (time, views, forwards, edited) goes through `ChatHelper.timePrefix` / `extraTimeWidth` / `timeAdditionsHash`, not a new cell patch. Checklist: `.claude/skills/write-patches/SKILL.md`. Code comparison: `.claude/skills/write-patches/dont-reinvent.md`.

## Patch groups & naming

Format: `group__name` → `patches/<group>/<name>.patch`. Commit subject = plain human sentence (`Allow editing by double tapping a message`).

| group | when |
| --- | --- |
| `bugfix` | fixes an upstream bug |
| `feature` | adds user-facing capability (qol, ui tweak, customization) |
| `debloat` | hides/disables stock behavior behind a toggle |
| `hooks` | thin stock hooks for fork code to attach to; no user-visible change alone |
| `misc` | build, branding, infra |
| `entiny` | **entinyGram-owned patches** — our work, not inugram's. Ghost mode, adblock, save-deleted, branding, updater, etc. Live in `patches/entiny/`. Inugram merges never touch this folder. **Always include this group** when listing, searching, auditing, or exporting patches; skipping it means you are looking at origin's fork, not ours. |

`debloat` vs `feature`: only *removes/toggles off* stock → `debloat`. Adds new capability → `feature`. `visual__`, `ui__`, etc. are **not** valid groups.

The first five groups (`bugfix`, `feature`, `debloat`, `hooks`, `misc`) are the **inherited inugram base**. `entiny` is **this fork**. A complete patch list is `series` (or `patches/*/` including `patches/entiny/`). If your search or table omits `entiny/`, you missed our patches.

**Ownership boundary:** everything under `patches/<bugfix|feature|debloat|hooks|misc>/` is the inherited inugram base — don't rename it. When you *take over* a patch (meaningfully modify it for entinygram), move it into `entiny/` via `stg rename <old> entiny__<name>`; at that point you own its maintenance (upstream fixes won't auto-apply). `entiny/` is your layer, so merging upstream stays clean.

**Merge behavior — will a patch duplicate?** Before taking over (or when planning a merge), check whether inugram also has the patch:
```bash
git show upstream/HEAD:series | grep <name>
```
- **New `entiny__` patches** (inugram doesn't have them) → **zero merge conflict**; inugram never touches them.
- **Taken-over patches** (you renamed an inugram patch to `entiny__`) → on each inugram merge, inugram's original `<group>/<name>` comes back → **duplication**. Resolve by dropping inugram's `<group>/<name>` from `series` (keep your `entiny__` version), or manually port inugram's fixes into yours.
- **Shared inugram patches you only tweak** (e.g. `misc/branding`) → don't take over; inugram overwrites them every merge, so re-assert your values post-merge (see merge hygiene). Taking over wouldn't avoid this.

As of writing, all `entiny__*` patches are **new** (not in inugram) — only `feature__translator` and `misc__branding` remain shared inugram base patches.


Propose a patch name (and comment) for every newly made patch — don't touch stgit yourself.

## Writing a stock patch

### Minimal wiring pattern

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
- When figuring out stock code history/regressions, make sure to run git **inside** the `worktree/` dir. Root dir is just the fork code, it DOES NOT track stock code.

### Exposing stock internals

- `private` field/method needed from fork? Change to `public`. That is the whole patch.
- Adding a new field/method/overload to a stock class? Prefix `inu_` (Java fields too: `inu_addTab`, `inu_internalType`, etc.).
- Prefer exposing over adding. Adding to a base class is especially rebase-fragile — look for an existing extension point first.

### Helper boundary

- <~5–7 lines of logic → **inline** in the patch.
- Bigger → extract to a Kotlin helper.
- Helper reads `InuConfig` itself; don't pass config values as parameters.
- Helper references stock constants directly (make them `public` if needed).
- One helper per feature area (e.g. `FolderHelper` owns icons + DB + layout + drawing).

### Where logic must live

- Bugfix in a specific stock class → write the fix **inline in that Java class**. `EditTextBoldCursor` bugs get fixed in `EditTextBoldCursor.java`. Don't detour through a Kotlin helper just to keep the patch "clean".
- Non-trivial feature logic → Kotlin helper.
- Pure config toggle with no Java wiring → don't write a stock patch at all.

## Do not reinvent origin

If inugram already has the feature, use it. Do not add a parallel `entiny/` patch, a second toggle, or a `ChatMessageCell` rewrite.

**Code comparison (ours vs origin, forward count):** `.claude/skills/write-patches/dont-reinvent.md`

Checklist: `.claude/skills/write-patches/SKILL.md`. Rule 19 above.

## Commonly touched stock files

Paths under `worktree/TMessagesProj/src/main/java/`. Line counts approximate.
**Files >2k lines: never Read top-to-bottom.** `rg` for the exact symbol, then Read with `offset` + small `limit`.

| file | ~lines | owns |
| --- | ---: | --- |
| `org/telegram/ui/ChatActivity.java` | 46k | chat screen |
| `org/telegram/ui/Cells/ChatMessageCell.java` | 29k | message bubble |
| `org/telegram/ui/PhotoViewer.java` | 24k | photo/video viewer + preview for ChatAttachAlert |
| `org/telegram/messenger/MessagesController.java` | 24k | messages domain state |
| `org/telegram/ui/ProfileActivity.java` | 17k | profile screen |
| `org/telegram/ui/Components/ChatActivityEnterView.java` | 15k | message input — voice, attach, text |
| `org/telegram/ui/DialogsActivity.java` | 14k | main page / dialogs list |
| `org/telegram/ui/Components/SharedMediaLayout.java` | 13k | profile shared-media player |
| `org/telegram/messenger/MediaDataController.java` | 10k | stickers, reactions, recent data |
| `org/telegram/ui/LoginActivity.java` | 10k | login flow |
| `org/telegram/ui/LaunchActivity.java` | 9k | root activity |
| `org/telegram/ui/Components/ChatAttachAlert.java` | 7k | attachments panel |
| `org/telegram/ui/Cells/DialogCell.java` | 6k | single dialog row |
| `org/telegram/ui/Components/ChatAttachAlertPhotoLayout.java` | 5k | attach panel photo grid |
| `org/telegram/messenger/LocaleController.java` | 4.5k | i18n |
| `org/telegram/ui/Components/ReactionsContainerLayout.java` | 2.6k | reactions bar in message menu |
| `org/telegram/ui/Components/FilterTabsView.java` | 2k | folder tabs strip in DialogsActivity |
| `org/telegram/messenger/SharedConfig.java` | 2k | stock prefs |
| `org/telegram/ui/Components/Reactions/ReactionsLayoutInBubble.java` | 1.9k | inline reaction chips on messages |
| `org/telegram/ui/Components/EditTextBoldCursor.java` | 1.3k | text input base (used by ~every input) |
| `org/telegram/ui/MainTabsActivity.java` | 1k | main bottom tabs |
| `org/telegram/ui/Components/glass/GlassTabView.java` | 0.6k | liquid-glass tab rendering |
| `org/telegram/messenger/LiteMode.java` | 0.4k | perf flag presets |

When adding to a hotspot, check `patches/hooks/` first — it likely already exposes the surface you need.

## `patches/hooks/` — shared extension points

Standalone hook patches expose surfaces (menu builders, callbacks, `public` field promotions, `inu_*` helpers) that multiple features consume. Intentionally **no user-visible effect on their own**.

| patch | what it exposes |
| --- | --- |
| `admin-logs.patch` | hooks inside admin logs activity |
| `app-loader.patch` | custom `ApplicationLoaderImpl` instead of stock |
| `chat-activity.patch` | various ChatActivity hooks — message menu (`ChatHelper.addMenuItems`/`processMenuOption`), `undoView`, `replyingMessageObject` etc. |
| `icon-replacement.patch` | custom resource loader for icon replacement |
| `internal-web-app.patch` | `WebViewRequestProps.inu_internalType` + `WebAppHelper.getInternalBotName` for internal bot web sheets |
| `loginactivity.patch` | hooks inside LoginActivity |
| `messagescontroller.patch` | access `MessagesController` instances as they're created |
| `notifications-controller.patch` | hooks inside NotificationsController |
| `photo-viewer-menu.patch` | `PhotoViewerHelper.{addMenuItems,updateMenuItems,resetMenuItems,handleMenuClick}` + `inu_getCurrentPhotoFile`; exposes `containerView`, `menuItem`, `showDownloadAlert` |
| `popup-swipeback.patch` | foreground translation + unified touch coords on swipeback popup |
| `profile-menu.patch` | `ProfileHelper.addMenuItems` + `ProfileHelper.handleMenuClick` |
| `universal-recycler.patch` | extra features in `UniversalRecyclerView` used by settings pages |

**When to add a `hooks/` patch vs a normal patch:**

- New stock surface that **>1 future patch will wire into** → `hooks/`.
- One-off wiring for a single feature → keep inside the `feature/`/`debloat/` patch.
- **Rule of 3**: if 3+ existing patches touch roughly the same stock surface, consolidate.
- A `hooks/` patch must be functionally a no-op with its consumers stubbed out.

Conventions: expose the minimum, promote `private` → `public` over duplicating data, `inu_` prefix on new fields, entry point is always a call to `desu.inugram.helpers.XxxHelper.*` — never inline logic.

## Helpers

Live in `src/kotlin/helpers/`. Sub-packages by feature area: `chat/`, `dialogs/`, `menu/`, `translate/`, `search/`, `media/`, `font/`, `update/`, `cloud/`, `security/`, `theme/`, `profile/`, `icons/`, `maps/`, `notifications/`. Cross-cutting / standalone ones stay flat.

Naming (don't mass-rename):
- `*Helper` = feature-coordinator singleton
- `*Config` = `InuConfig.Item` subclass / data model
- `*Utils`/`*Parser`/`*Drawable`/`*Resources` = concrete type or algorithm

Common entry-point helpers: `ChatHelper` (chat features), `ProfileHelper` (profile menu), `PhotoViewerHelper` (photo viewer), `FolderHelper` (folder tabs), `MainTabsHelper` (bottom tabs), `MonetHelper` (theming), `NonIslandHelper` (non-island UI gating), `InuDatabaseHelper` (fork DB), `InuUtils` (id generation etc.).

Before creating a new helper, check whether an existing one owns the area.

## `InuHooks` — central lifecycle bus

`src/kotlin/InuHooks.kt`. Generic lifecycle dispatch only — feature-specific code goes on its own helper.

Currently exposed (update this table when adding):

| method | called from | purpose |
| --- | --- | --- |
| `init(Context)` | `ApplicationLoader.onCreate` | bootstrap `InuConfig`, fonts, crash reporter, etc. |
| `onResume(LaunchActivity)` | `LaunchActivity.onResume` | monet refresh, crash sheet |
| `onUpdate(TLObject?, Int)` | update dispatch | fork `LoginHelper` hook; also feeds `TL_updateUserStatus` to `PresenceHelper.onStatusUpdate` |
| `onDeepLink(LaunchActivity, Intent?)` | deeplink handling | passcode + settings deeplinks |
| `onAuthSuccess(Int)` | login flow | clear per-account passcode |
| `onMessagesControllerCreated(MessagesController, Int)` | `MessagesController.<init>` | per-account setup (maps provider; loads `PinHelper`'s local-pins cache and `PresenceHelper`'s watch-list cache; registers the `didReceiveNewMessages` → `onNewMessage` observer) |
| `onNewMessage(TLRPC.Message, Int)` | `didReceiveNewMessages` observer | generic new-message dispatch (all arrival paths incl. difference catch-up); fans out to `UpdateHelper` etc. |
| `syncDoubleTapDelay()` | fork + `init` | propagate `DOUBLE_TAP_DELAY` into stock gesture detectors |
| `syncAnimationSpeed()` | fork + `init` | propagate `ANIMATION_SPEED` into stock animators |
| `syncChatInputRowHeight()` | fork + `init` | propagate classic-ui input row height/padding into `ChatActivityEnterView` statics |
| `getCurrentAppIconLicense()` | About page | current launcher icon's license string |

New hook → `@JvmStatic fun` on `InuHooks`, one-line call site in the patch, **update this table**.

## `InuConfig` pattern

```kotlin
@JvmField val HIDE_STORIES = BoolItem("hide_stories", false)
```

- Always `@JvmField` so Java sees a field, not `getHIDE_STORIES()`.
- Types: `BoolItem`, `IntItem`, `FloatItem`, `StringItem`. Subclass `Item<T>` for anything else (enums — see `FoldersDisplayModeItem`, `FormattingPopupConfig`).
- `BoolItem` has `.toggle()`.
- From Java: `InuConfig.HIDE_STORIES.getValue()` — **never `.value`** (`@JvmField` exposes the wrapper, not its inner value).
- Pref key = snake_case of the field name; default is the second arg. SharedPreferences name: `inugram`. Loaded once from `InuHooks.init`.

## Database

- Stock schema and `LAST_DB_VERSION` are off-limits.
- Fork versioning lives in `inu_kv`, managed by `InuDatabaseHelper`.
- Fork tables: `inu_*` prefix, created/migrated in `InuDatabaseHelper.migrate()`.
- Populate fork fields by **hooking** stock load/save calls (see `patches/feature/folders-display-mode.patch`) — don't edit stock SQL.

## Settings UI

- Extend `desu.inugram.ui.settings.SettingsPageActivity` (wraps `UniversalFragment` with edge-to-edge + insets + `showRestartBulletin()`). Register pages in `InuSettingsActivity`.
- Prefer adding to an existing page:
  - `AppearanceSettingsActivity` — general appearance
  - `ChatsSettingsActivity` — chat-related appearance (bubbles, menus)
  - `MessagesSettingsActivity` — message bubble / inline reactions / sticker size
  - `DialogsSettingsActivity` — dialogs list (main page) appearance
  - `AnnoyancesSettingsActivity` — removes annoying stock stuff (only when user explicitly asks)
  - `BehaviorSettingsActivity` — general behavior
- Any toggle needing a restart → call `showRestartBulletin()` in the click handler (verify restart is actually needed).
- Custom cells: `SliderCell`, `ExpandableBoolGroup`, `RadioDialogBuilder`, `StickerSizePreviewMessagesCell`.

### Settings search & deeplinks

- `desu.inugram.SearchRegistry` wires fork pages into stock settings search (`ProfileActivity.SearchAdapter`) and routes `tg://settings/inu/<slug>` deeplinks.
- Each searchable `*SettingsActivity` declares a `@JvmField val PAGE = SearchRegistry.Page(...)` in its companion: page `slug`, title res, icon res, factory, list of `SearchRegistry.Entry(slug, titleRes, itemId)` — one per searchable `UItem`. `itemId` reuses the page's `InuUtils.generateId()` constant (also used as the `UItem.id`).
- Register in `SearchRegistry.pages`. Slugs are persistent identity (deeplinks + recents), globally unique — uniqueness asserted at first access. Renaming a slug is a breaking change.
- Row highlight on open: `SettingsPageActivity.withHighlight(itemId)` + existing `onTransitionAnimationEnd` hook. No extra wiring per page.

## Strings

- `src/res/values/strings_inu.xml`. All keys prefixed `Inu` (`InuHideStories`).
- Subtitle/info strings: same key + `Info` suffix (`InuHideStoriesInfo`).
- Access: `LocaleController.getString(R.string.InuXxx)`.

## Drawables / assets

- `src/res/drawable/` (density-independent), `src/res/drawable-xxhdpi/` (bitmaps), `src/res/assets/`.
- New asset dir → add path to `scripts/config.ts` → `forkSyncFiles`.
- Icons: lucide pre-bundled; selection list in `scripts/config.ts` → `ICON_SELECTION`. Tabler pack preferred for visual consistency.

## Monet themes

`src/res/assets/monet_{light,dark,amoled}.attheme` — stock attheme format, values resolved by
`MonetHelper.getColor` (hooked into `Theme.getThemeFileValues` by `feature/monet-theme.patch`).

- Values are palette tones (`a1_600`, `n1_50`), M3 semantic tokens (`monet_surface_container_light`), custom names (`monetGreen`), or raw ints.
- Modifiers: `(a=)` alpha %, `(s=)` blend→white %, `(l=)` blend→black %, `(t=)` absolute HCT tone, `(c=)` HCT chroma multiplier % (0–400, relative so monochrome palettes stay gray). Comma-separated: `monet_secondary_container_light(t=90,c=75)`.
- Debug hot reload: `bun run push-theme [light|dark|amoled] [--watch] [--clear] [-s <serial>]` — adb-pushes the asset to the app's external files dir and broadcasts `desu.inugram.RELOAD_THEME`. Debug builds only (`getThemeOverrideFile` is a no-op otherwise); the app must be running.

## Java ↔ Kotlin gotchas

- `.value` (Kotlin) → `.getValue()` from Java.
- Kotlin `object` → `InuXxx.INSTANCE.method()` from Java unless `@JvmStatic`.
- For hooks called from stock Java, default to `@JvmStatic fun foo(...)` on a Kotlin `object` — cleanest call site.
- Inside stgit patches, the `worktree/` prefix is omitted from paths.
- `LayoutHelper.createLinear` / `createFrame` margin args are dp either way (both int and float overloads pass through `AndroidUtilities.dp(...)`). But Kotlin won't auto-promote `Int → Float`, and several overloads exist **only in the Float variant** — notably the 6-arg `createLinear(w, h, l, t, r, b)`. Write `12f` not `12` for margins or you'll hit "actual type is Int, but Float was expected".

Don't overuse `@JvmStatic`, only add it if the method/field is actually accessed from Java.

## Common pitfalls (from prior sessions)

1. **Running `stg`/`git`.** Don't. Read-only `stg top` / `stg show` only.
2. **Hand-editing `patches/*.patch`.** They're exports. Edit `worktree/`; user re-exports.
3. **Oversized stock patches.** Logic beyond a guard + helper call → move to Kotlin.
4. **Helper for 2–5 lines.** Inline it. Only extract when >5–7 lines or genuinely reused.
5. **Replacing stock behavior instead of running after it.** Stock stays intact; fork logic runs before (early return) or after, gated by config.
6. **Routing a trivial set through a helper method.** If the patch just assigns a field based on config, assign in-place at the stock call site.
7. **Modifying stock base classes.** Look for an existing extension hook first (stock often has setup hooks for themed things). Base-class edits rebase poorly.
8. **Writing Kotlin helpers for what must be a Java fix.** Bug in `EditTextCaption` → fix it **in** `EditTextCaption.java`. Don't detour.
9. **Ungated fork behavior.** Default-off must equal stock. Verify every call site.
10. **Java using `.value`.** It's `.getValue()`. Kotlin `.value` is a property; `@JvmField` only exposes the wrapper.
11. **Forgetting `inu_` prefix** when adding fields/methods/overloads to stock classes. Including Java fields.
12. **Re-indenting stock** to wrap it in an `if`. Kills rebases. Use early returns, add-after-stock, or keep indentation the same.
13. **Reimplementing an origin feature** as a parallel `entiny/` patch (second toggle, second drawable, `ChatMessageCell` rewrite). Search origin first. See write-patches skill / forward-count case study.
14. **Opening `ChatMessageCell.java` for bubble metadata.** Time, forwards, views, edited, deleted icons go through `ChatHelper.timePrefix` and friends. If that hook is missing, add a 1-line call there — do not patch the cell.

## stgit workflow (user-initiated only)

You never run these unless explicitly asked — documented so you can answer questions / suggest commands.

```bash
# create a new patch
stg new feature__my-patch -m 'Allow editing by double tapping a message'
# ...edit worktree/...
stg refresh
bun run export

# modify existing patch in-place
# ...edit worktree/...
stg refresh -p feature__my-patch  # --index for staged-only

# modify existing patch, floating to top (preferred for non-trivial changes)
stg float feature__my-patch
# ...edit...
stg refresh
bun run export
```

`bun run export` rewrites `patches/` + `series` from the stack. User runs it.

If user asks "which patch am I on" → `stg top`.

## Merge hygiene — read before/after EVERY merge (do not skip)

A merge from upstream (inugram) frequently lands in a **broken, half-finished** state:
upstream branding/APIs overwrite fork files, and conflict markers get left behind in odd
places. Never treat an upstream merge as "done" or "safe" just because source control
reported no conflict. Every merge breakage seen here was silent or half-applied.

Golden merge attitude:
1. **Assume the merge is broken until proven otherwise.** Treat "no conflict reported" as
   a lie. Actually verify the build/identity matches the fork, not the upstream.
2. **Never leave a merge unfinished.** Any `<<<<<<<`, `=======`, `>>>>>>>` marker — even a
   lone marker with no matching pair (which still kills XML parsing) — is a hard error.
   Resolve all of them before moving on. Do not "commit" around them.
3. **Verify branding survived the merge.** The fork identity must be restored every time by running `bun run scripts/apply-branding.ts`. This script automatically:
   - Updates `google-services.json` `package_name` values to `ua.entaytion.entinygram` and applies `skip-worktree` so Git ignores them in the future.
   - Restores the `entiny__branding` patch (which changes `TMessagesProj_App/build.gradle` `applicationId`).
   Do NOT manually edit `misc/build-support.patch` to hardcode `ua.entaytion.entinygram`, as this causes merge conflicts with upstream. Use `entiny__branding` instead.
4. **Post-merge static scan (all must come back empty)** across fork-relevant code:
   - `<<<<<<<` / `=======` / `>>>>>>>` markers anywhere (`*.xml`, `*.kt`, `*.java`, `*.gradle`).
   - Well-formedness of every touched `res/values/*.xml` (malformed XML is a silent
     resource-build failure — `packageDebugResources`).
   - In `src/kotlin`: `\.getValue\(\)` (must be `.value` per Java↔Kotlin gotchas) and any
     stock API that may have been renamed by the merge.
5. **Verify fork Kotlin against current stock APIs.** A merge can rename stock
   fields/methods the fork relies on (e.g. `UItem.text2` → `UItem.subtext`) or change
   signatures. Before assuming a bad symbol is a fork bug, confirm the current stock API:
   `rg` the exact symbol in `worktree/TMessagesProj/src/main/java/...`, then fix the fork
   call site to match. Do the same for Kotlin-side access of `InuConfig` items.
6. **Do not "fix forward" a half-merge by patching symptoms.** First fully resolve the merge
   (markers, branding, API drift), then validate holistically. If any check raises a doubt,
   stop and tell the user instead of guessing.
7. **Final gate before declaring done:** every item above is green, and reason through the
   runtime path (not just symbols). Because we follow rule 11 (no local build), be explicit
   about what was verified statically vs what remains unverified, and flag residual risk.

## Self-maintenance

When adding a new `InuHooks` method, settings page, or shared `hooks/` patch — update this file. When a feature is dropped because origin already had a cleaner version, add it to the write-patches case studies. Tribal knowledge rots.