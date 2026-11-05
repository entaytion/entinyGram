---
name: entinygram-craft
description: >
  MANDATORY speed & anti-waste skill for entinyGram. Use for ANY bugfix, UI
  glitch ("баг", "зникає", "з'їхав", "глюк", "please fix entiny"), or feature
  implementation. Stops blind grep/file-reading loops, provides exact Telegram
  stock anchors, and enforces the fastest path to a 1–3 line fix.
---

# entinyGram Craft: Zero-Waste Speed & Exact Anchors

**YOUR #1 ENEMY IS CONTEXT BLOAT FROM BLIND SEARCHING.**
Telegram Android has 50k-line files. If you run generic `rg` searches or read 10 files to "understand the architecture", you will flood your context, get confused, waste 10 minutes, and write garbage.

---

## 1. THE ANTI-STUPIDITY & ANTI-GREP RULES (Hard Limits)

1. **Read `src/fork/` BEFORE `worktree/`:**
   - `src/fork/` is small, clean Kotlin. `worktree/` is massive stock Java.
   - **Always** check `src/fork/InuConfig.kt`, `src/fork/InuHooks.kt`, and `src/fork/helpers/` first. In 80% of cases, the hook you need already exists there!

2. **NEVER Grep Generic Words in `worktree/`:**
   - **FORBIDDEN:** Running `rg` in `worktree/` for common words like `padding`, `margin`, `title`, `avatar`, `button`, `send`, `draw`, `measure`, `alpha`.
   - **ALLOWED:** Searching ONLY for an exact method/field name from the **Anchor Cheat Sheet** below, an exact `R.string.*` / `R.drawable.*` key, or an existing `inu_` / `InuConfig.` hook.
   - Always scope `rg` to the specific file: `rg -n "exactSymbol" worktree/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java`.

3. **The "2-Stall" Stop Rule:**
   - If you read 2 code snippets in `worktree/` and still don't see the exact cause/hook: **STOP READING CODE IMMEDIATELY.**
   - Do NOT read a 3rd, 4th, or 5th file "just in case".
   - Instead: either use `adb` to get the exact View class/bounds on screen, put ONE targeted `Log.d`, or ask the user ONE concrete question.

4. **Never Spawn Sub-Agents for Exploration:**
   - Do not delegate "go explore how ChatActivity works" to a sub-agent. It burns 50k tokens and returns useless summaries. Look up the exact anchor below in 2 seconds.

---

## 2. EXACT ANCHOR CHEAT SHEET (Don't Search — Go Straight Here)

Instead of grepping `worktree/`, jump directly to these exact symbols:

### Chat Screen & Bubbles (`ChatActivity.java` / `ChatMessageCell.java`)
- **Bubble time, views, forwards, edited/deleted markers:** DO NOT touch `ChatMessageCell.java`! Go to `src/fork/helpers/ChatHelper.kt` → `timePrefix`, `extraTimeWidth`, `timeAdditionsHash`.
- **Message context menu (tap/long-press on bubble):** Already hooked! Go to `src/fork/helpers/ChatHelper.kt` → `addMenuItems` & `processMenuOption` (wired to `ChatActivity.fillMessageMenu` / `processSelectedOption`).
- **Header avatar / title / subtitle ("online", "typing"):** `ChatAvatarContainer.java` (`updateSubtitle`, `setTitle`, `statusTextView`, `titleTextView`) and `ActionBar.java` (`dispatchDraw` for glass/pills).
- **Pinned message bar:** `ChatActivity.java` → `updatePinnedMessageView`, `closePinned`.
- **Side floating buttons (page down, mentions `@`, reactions ❤️):** `ChatActivitySideControlsButtonsLayout.java` (`showButton`, `BUTTON_MENTION`, `BUTTON_REACTIONS`).
- **Selection limit (100 messages):** `ChatActivity.java` → search `>= 100` in `addToSelectedMessages`.
- **Forwarded / Reply header inside bubble:** `ChatMessageCell.java` → `forwardedNameLayout`, `replyNameLayout`, `measureTime`.

### Message Input Bar (`ChatActivityEnterView.java` / `EditTextBoldCursor.java`)
- **Input text field & hint drawing:** `ChatActivityEnterView.messageEditText` (`EditTextCaption` → `EditTextBoldCursor.drawHint`).
- **Horizontal field padding (emoji/attach button offsets):** `ChatActivityEnterView.setHorizontalPadding` (watch out: stock hardcodes `dp(40)` assuming stock button placement!).
- **Pasting text / links:** `EditTextCaption.java` → `onTextContextMenuItem(android.R.id.paste)` and `handleRichHtmlPaste`.
- **Sending text entry point:** `ChatActivityEnterView.processSendingText` → `SendMessagesHelper.sendMessage(SendMessageParams)`.
- **Send button long-press menu (Schedule / Send Silently):** `ChatActivityEnterView.sendPopupLayout`.
- **Gift button in input field:** `ChatActivityEnterView.createGiftButton` / `updateGiftButton`.
- **Sticker / GIF tap from panel:** `EmojiView.EmojiViewDelegate.onStickerSelected` / `onGifSelected`.

### Sending, Media & Network (`SendMessagesHelper.java` / `MessagesController.java`)
- **All outgoing messages & forwards:** `SendMessagesHelper.sendMessage` (`SendMessageParams` for single, `ArrayList<MessageObject>` for forwards).
- **Preparing photos / HD default:** `MediaController.MediaEditState.isHighQuality()` and `SendMessagesHelper.generatePhotoSizes`.
- **Preparing videos / GIFs / muted video:** `SendMessagesHelper.prepareSendingVideo` (`videoEditedInfo.muted` → `TL_documentAttributeAnimated`).
- **Preparing documents (.tgs, .mkv, etc.):** `SendMessagesHelper.prepareSendingDocumentInternal`.
- **File upload chunks & retries:** `FileUploadOperation.java` → `startUploadRequest`.
- **Read receipts (blue ticks):** `MessagesController.markDialogAsRead` and `completeReadTask`.
- **Typing / recording status:** `MessagesController.sendTyping`.
- **Online status ping:** `TL_account.updateStatus` in `MessagesController` / `ConnectionsManager`.
- **Deleting messages:** `MessagesController.deleteMessages` (outgoing) and `MessagesStorage.markMessagesAsDeletedInternal` / `processDeletedMessages` (incoming).
- **Message edits:** `MessagesStorage.putMessages` (`load_type == -2`) / `TL_updateEditMessage`.
- **Drafts (cloud vs local):** `MediaDataController.saveDraft` (`TL_messages_saveDraft`).

### Chat List & Folders (`DialogsActivity.java` / `DialogCell.java` / `FilterTabsView.java`)
- **Single chat row layout & drawing:** `DialogCell.java` → `buildLayout`, `onMeasure`, `onDraw`, `drawVerified`, `mentionString`.
- **Folder tabs strip:** `FilterTabsView.java` and `MessagesController.getDialogFilters()`.
- **Sorting & pinned dialogs:** `MessagesController.sortDialogs` and `TL_messages_toggleDialogPin`.
- **Chat list header title / stories bar:** `DialogsActivity.java` (`actionBar.setTitle`) and `DialogStoriesCell.java`.

### Profiles, Stories & Viewers (`ProfileActivity.java` / `PhotoViewer.java` / `StoriesController.java`)
- **Profile menu (`⋮`):** Already hooked! Go to `src/fork/helpers/ProfileHelper.kt` → `addMenuItems` & `handleMenuClick`.
- **Profile rows (ID, DC, phone, bio, channel):** `ProfileActivity.java` → `updateRowsIds`, `onBindViewHolder` (`phoneRow`, `numberRow`, `usernameRow`, ` bioRow`).
- **Profile header background & blur:** `ProfileActivity.TopView`.
- **User display name formatting:** `UserObject.getUserName` / `getFirstName` (central choke point for all user names!).
- **Photo/Video viewer menu:** Already hooked! Go to `src/fork/helpers/PhotoViewerHelper.kt`.
- **Screenshot protection (`FLAG_SECURE`):** `FlagSecureReason.updateWindowSecure`, `PhotoViewer.openPhoto`, `SecretMediaViewer.openMedia`, `StoryViewer.allowScreenshots`.
- **Stories visibility & stealth read:** `StoriesController.hasStories()` and `StoriesController.markStoryAsRead` (`TL_stories_readStories`).

### Links & Confirmations
- **Opening any link in chat:** `ChatActivity.openClickableLink` and `Browser.openUrl`.
- **"Open link?" confirmation dialog:** `AlertsCreator.showOpenUrlAlert`.
- **`tg://` internal deeplinks:** `LinkManager.handleTg` and `LaunchActivity.runLinkRequest`.

---

## 3. BUG HUNTING WORKFLOW ("please-fix-entiny": Fastest Path)

When the user reports a bug ("баг", "зникає", "з'їхав", "глюк", "please fix entiny"):
**Measure first → Read 30 lines around the exact anchor → 1 log if needed → 1-line fix.**

1. **Diff Configs First (0 Code Reading):**
   - If given `*.entiny-settings.json`, diff it against baseline immediately:
     ```bash
     python -c "import json;a=json.load(open(A))['values'];b=json.load(open(B))['values'];[print(k,a.get(k),b.get(k)) for k in sorted(set(a)|set(b)) if a.get(k)!=b.get(k) and 'menu' not in k and 'rows' not in k and 'pill_stack' not in k]"
     ```
   - Missing key = default in `InuConfig.kt`.
   - If it only happens with specific settings, ask the user to **flip ONE toggle and re-screenshot**.

2. **Measure on Device via Read-Only `adb` (When Permitted; NEVER install/push/launch):**
   - Windows Git Bash: `export MSYS_NO_PATHCONV=1`.
   - Active screen check: `adb shell dumpsys activity activities | grep topResumed`.
   - Screenshot: `adb exec-out screencap -p > $TEMP/s.png`.
   - Main window view bounds & exact class names: `adb shell dumpsys activity top > $TEMP/top.txt` (grep the class name — now you know the EXACT Java class without grepping the codebase!).
   - Separate window (stories, sheets, popups): `adb shell uiautomator dump /sdcard/ui.xml && adb exec-out cat /sdcard/ui.xml`.

3. **Prove With ONE Targeted `Log.d` Instead of Guessing:**
   - If reading the exact anchor (30–60 lines) doesn't make the bug 100% obvious, **stop reading other files**.
   - Add ONE temporary `android.util.Log.d("entinyBug", ...)` printing the exact coordinates/flags (`getLeft`, `scrollX`, `translationX`, `scaleX`, `bounds`), throttled or only on the anomaly.
   - User reproduces → `adb logcat -d -s entinyBug`.
   - Remove the temp log before finalizing.

---

## 4. CRITICAL TELEGRAM ANDROID GOTCHAS (Don't Step on These Rakes)

- **Reused Stock Views:** `ChatActivityEnterView` is reused inside the Story Viewer; `DialogCell` is reused inside Forward/Share pickers. Always check whether your hook breaks secondary screens, or guard against stock hardcoded offsets (like `dp(40)` in `setHorizontalPadding`).
- **Cell Recycling Bleed:** `DialogCell`, `ChatMessageCell`, `TextCell`, and `TextCheckCell2` are recycled. If you change alpha, visibility, drawable, or spans when a toggle is ON, you MUST reset it in the `else` branch or at the top of `bind` — otherwise scrolled rows inherit stale state.
- **Account `0` Bug:** Never use a static variable without `currentAccount` key, and never hardcode `0`. Always pass `currentAccount` into helpers.
- **UI Thread Rule:** Callbacks from `ConnectionsManager`, `MessagesStorage`, or background threads must wrap UI updates in `AndroidUtilities.runOnUIThread`.
- **Empty `messagesDidLoad` Crash:** Never call `NotificationCenter.getInstance(acc).postNotificationName(NotificationCenter.messagesDidLoad)` without full arguments — `ChatActivity` reads `args[10]` and crashes with `AIOOBE`. Use `updateInterfaces` or `dialogsNeedReload`.
- **In-Memory vs DB:** Never write spoofed user/chat flags or local names into `MessagesStorage` (`cache4.db`). Mutate only in-memory objects or display getters (`UserObject.getUserName`).