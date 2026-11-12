---
name: craft
description: Fast path for entinyGram bugfixes and small features. Use for any bug report or feature work. Checks src/fork first, uses exact stock anchors, aims for a 1-3 line fix.
---

# entinygram craft

context bloat is the main risk: stock telegram android has 50k-line files. search narrowly, read little.

## rules

| rule | meaning |
| --- | --- |
| src/fork first | check `src/fork/InuConfig.kt`, `InuHooks.kt`, `helpers/` before `worktree/`. the hook often exists already |
| no generic grep in worktree | never `rg` words like padding, margin, title, avatar, button, draw. search only exact symbols, `R.string.*` / `R.drawable.*` keys, or `inu_` / `InuConfig.` hooks |
| scope every rg | `rg -n "symbol" worktree/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java` |
| 2-stall stop | two snippets read and still no cause: stop reading. use one adb check, one `Log.d`, or ask one question |
| no sub-agents for exploration | a sub-agent burns tokens and returns summaries. look up the anchor |

## anchors

### chat and bubbles

| need | go to |
| --- | --- |
| bubble time, views, forwards, edited/deleted | `src/fork/helpers/ChatHelper.kt`: `timePrefix`, `extraTimeWidth`, `timeAdditionsHash`. do not touch `ChatMessageCell.java` for this |
| message context menu | `ChatHelper.kt`: `addMenuItems`, `processMenuOption` (wired to `fillMessageMenu` / `processSelectedOption`) |
| header avatar, title, subtitle | `ChatAvatarContainer.java`: `updateSubtitle`, `setTitle`, `statusTextView`, `titleTextView`. `ActionBar.java`: `dispatchDraw` |
| pinned message bar | `ChatActivity.java`: `updatePinnedMessageView`, `closePinned` |
| floating side buttons | `ChatActivitySideControlsButtonsLayout.java`: `showButton`, `BUTTON_MENTION`, `BUTTON_REACTIONS` |
| selection limit (100) | `ChatActivity.java`: search `>= 100` in `addToSelectedMessages` |
| forwarded / reply header | `ChatMessageCell.java`: `forwardedNameLayout`, `replyNameLayout`, `measureTime` |

### input bar

| need | go to |
| --- | --- |
| text field, hint | `ChatActivityEnterView.messageEditText`, `EditTextBoldCursor.drawHint` |
| horizontal padding | `ChatActivityEnterView.setHorizontalPadding`. stock hardcodes `dp(40)` for stock button placement |
| paste text, links | `EditTextCaption.java`: `onTextContextMenuItem(android.R.id.paste)`, `handleRichHtmlPaste` |
| send entry point | `ChatActivityEnterView.processSendingText` -> `SendMessagesHelper.sendMessage(SendMessageParams)` |
| send long-press menu | `ChatActivityEnterView.sendPopupLayout` |
| gift button | `createGiftButton`, `updateGiftButton` |
| sticker / gif from panel | `EmojiView.EmojiViewDelegate.onStickerSelected` / `onGifSelected` |

### sending, media, network

| need | go to |
| --- | --- |
| outgoing messages, forwards | `SendMessagesHelper.sendMessage` (`SendMessageParams` for one, `ArrayList<MessageObject>` for forwards) |
| photo quality | `MediaController.MediaEditState.isHighQuality()`, `SendMessagesHelper.generatePhotoSizes` |
| video, gif, muted video | `SendMessagesHelper.prepareSendingVideo` (`videoEditedInfo.muted` -> `TL_documentAttributeAnimated`) |
| documents (.tgs, .mkv) | `SendMessagesHelper.prepareSendingDocumentInternal` |
| upload chunks, retries | `FileUploadOperation.java`: `startUploadRequest` |
| read receipts | `MessagesController.markDialogAsRead`, `completeReadTask` |
| typing status | `MessagesController.sendTyping` |
| online ping | `TL_account.updateStatus` in `MessagesController` / `ConnectionsManager` |
| delete (outgoing / incoming) | `MessagesController.deleteMessages` / `MessagesStorage.markMessagesAsDeletedInternal`, `processDeletedMessages` |
| edits | `MessagesStorage.putMessages` (`load_type == -2`), `TL_updateEditMessage` |
| drafts | `MediaDataController.saveDraft` (`TL_messages_saveDraft`) |

### chat list and folders

| need | go to |
| --- | --- |
| row layout and drawing | `DialogCell.java`: `buildLayout`, `onMeasure`, `onDraw`, `drawVerified`, `mentionString` |
| folder tabs | `FilterTabsView.java`, `MessagesController.getDialogFilters()` |
| sorting, pins | `MessagesController.sortDialogs`, `TL_messages_toggleDialogPin` |
| header title, stories bar | `DialogsActivity.java` (`actionBar.setTitle`), `DialogStoriesCell.java` |

### profiles, stories, viewers

| need | go to |
| --- | --- |
| profile menu | `src/fork/helpers/ProfileHelper.kt`: `addMenuItems`, `handleMenuClick` |
| profile rows | `ProfileActivity.java`: `updateRowsIds`, `onBindViewHolder` (`phoneRow`, `numberRow`, `usernameRow`, `bioRow`) |
| profile header, blur | `ProfileActivity.TopView` |
| display names | `UserObject.getUserName`, `getFirstName` (all names pass here) |
| photo/video viewer menu | `src/fork/helpers/PhotoViewerHelper.kt` |
| screenshot block (`FLAG_SECURE`) | `FlagSecureReason.updateWindowSecure`, `PhotoViewer.openPhoto`, `SecretMediaViewer.openMedia`, `StoryViewer.allowScreenshots` |
| stories visibility, stealth read | `StoriesController.hasStories()`, `markStoryAsRead` (`TL_stories_readStories`) |

### links

| need | go to |
| --- | --- |
| link in chat | `ChatActivity.openClickableLink`, `Browser.openUrl` |
| "open link?" dialog | `AlertsCreator.showOpenUrlAlert` |
| `tg://` deeplinks | `LinkManager.handleTg`, `LaunchActivity.runLinkRequest` |

## bug workflow

measure first, read 30-60 lines around the anchor, add one log only if needed, then make a 1-3 line fix.

1. **config diff, no code reading.** if the user gives `*.entiny-settings.json`, diff it against the baseline. a missing key means the default in `InuConfig.kt`. if the bug needs one setting, ask the user to flip one toggle and send a screenshot.

2. **read-only adb** (only when the user allows; never install, push or launch):
   - `export MSYS_NO_PATHCONV=1` (Git Bash on Windows)
   - top screen: `adb shell dumpsys activity activities | grep topResumed`
   - screenshot: `adb exec-out screencap -p > $TEMP/s.png`
   - view bounds and class names: `adb shell dumpsys activity top > $TEMP/top.txt`
   - separate windows (stories, sheets, popups): `adb shell uiautomator dump /sdcard/ui.xml && adb exec-out cat /sdcard/ui.xml`

3. **one targeted log.** if the anchor does not explain the bug, add one `android.util.Log.d("entinyBug", ...)` with the exact values (`getLeft`, `scrollX`, `translationX`, `scaleX`, bounds), user reproduces, then `adb logcat -d -s entinyBug`. remove the log before finishing.

## gotchas

- **reused stock views.** `ChatActivityEnterView` is reused in the story viewer, `DialogCell` in forward and share pickers. check secondary screens.
- **cell recycling.** `DialogCell`, `ChatMessageCell`, `TextCell`, `TextCheckCell2` are recycled. reset alpha, visibility, drawables and spans in the `else` branch or at the top of `bind`.
- **account 0.** never use a static without `currentAccount`, never hardcode `0`.
- **ui thread.** callbacks from `ConnectionsManager`, `MessagesStorage` or background threads wrap UI work in `AndroidUtilities.runOnUIThread`.
- **empty `messagesDidLoad`.** posting without full arguments crashes `ChatActivity` (`args[10]`, AIOOBE). use `updateInterfaces` or `dialogsNeedReload`.
- **db vs memory.** never write spoofed flags or local names into `MessagesStorage` (`cache4.db`). change in-memory objects or display getters.

## hotspots

stock files are large. use `rg` on them, never read top to bottom.

| lines | file |
| --- | --- |
| 46k | `ui/ChatActivity.java` |
| 29k | `ui/Cells/ChatMessageCell.java` |
| 24k | `ui/PhotoViewer.java`, `messenger/MessagesController.java` |
| 17k | `ui/ProfileActivity.java` |
| 15k | `ui/Components/ChatActivityEnterView.java` |
| 14k | `ui/DialogsActivity.java` |
| 13k | `ui/Components/SharedMediaLayout.java` |
| 10k | `messenger/MediaDataController.java` |
| 10k | `ui/LoginActivity.java` |
| 9k | `ui/LaunchActivity.java` |
| 7k | `ui/Components/ChatAttachAlert.java` |
| 6k | `ui/Cells/DialogCell.java` |
| 5k | `ui/Components/ChatAttachAlertPhotoLayout.java` |
| 4.5k | `messenger/LocaleController.java` |
| 2.6k | `ui/Components/ReactionsContainerLayout.java` |
| 2k | `ui/Components/FilterTabsView.java`, `messenger/SharedConfig.java` |
| 1.9k | `ui/Components/Reactions/ReactionsLayoutInBubble.java` |
| 1.3k | `ui/Components/EditTextBoldCursor.java` |
| 1k | `ui/MainTabsActivity.java` |
| 0.6k | `ui/Components/glass/GlassTabView.java` |
| 0.4k | `messenger/LiteMode.java` |

all paths are under `worktree/TMessagesProj/src/main/java/org/telegram/`.

## shared hooks (`patches/hooks/`)

| hook | entry points |
| --- | --- |
| `admin-logs` | |
| `app-loader` | |
| `chat-activity` | `ChatHelper.addMenuItems`, `ChatHelper.processMenuOption` |
| `icon-replacement` | |
| `internal-web-app` | |
| `loginactivity` | |
| `messagescontroller` | |
| `notifications-controller` | |
| `photo-viewer-menu` | `PhotoViewerHelper.*` |
| `popup-swipeback` | |
| `profile-menu` | `ProfileHelper.*` |
| `universal-recycler` | |

## central lifecycle (`src/fork/InuHooks.kt`)

`init`, `onResume`, `onUpdate`, `onDeepLink`, `onAuthSuccess`, `onMessagesControllerCreated`, `onNewMessage`, `syncDoubleTapDelay`, `syncAnimationSpeed`, `syncChatInputRowHeight`, `getCurrentAppIconLicense`.
