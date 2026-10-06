---
name: inugram-plugin-port
description: >
  Use when porting a third-party Python Telegram client plugin (a `.plugin` file with
  `from base_plugin import BasePlugin`, `__id__`/`__name__` dunders, Chaquopy `jclass`/`find_class`,
  `hook_method`, `on_send_message_hook`, `create_settings`) to an Inugram plugin (`.inu.js` / TS
  built with `@inugram/cli`). Trigger on "port this plugin", "rewrite X.plugin for inugram",
  "convert this python plugin", or when the user hands over a `.plugin` file.
---

# Porting Python plugins to Inugram

A port is a rewrite against a different, higher-level API, not a transliteration. The source
plugins reach into app internals because their host offers little else. Inugram usually offers a
typed, granted, thread-safe API for the same thing. The usual outcome is a fraction of the
original's code with fewer and narrower grants.

Contract: the `@inugram/plugin-types` typings, `common.d.ts` plus `android.*.d.ts` and
`canvas.d.ts` (`node_modules/@inugram/plugin-types/` in a plugin project, `sdk/types/` in the
Inugram repo). Concepts and quirks: the plugin docs (`sdk/docs/*.md` in the Inugram repo, or
https://github.com/teidesu/inugram/tree/main/sdk/docs). Read the docs for every API area the port
touches before writing code. The typings tag each API with `@needs-grant` and `**Limits:**`.

## 1. Understand before writing

1. Read the whole original. List each user-visible behavior and each setting. Ignore its
   implementation for now.
2. Audit it. Do not port, and report to the user, anything not needed for the advertised feature:
   joining/subscribing to channels, sending data to hosts unrelated to the feature, self-update or
   "check min version" logic, telemetry, `exec`/`marshal`/`zlib`/base64 blobs of code, embedded DEX
   payloads. An embedded DEX without source is a blocker: ask the user, do not ship it.
3. Check Inugram already does it: `FEATURES.md` in the Inugram repo, stock settings (including Lite Mode), and
   built-in plugin APIs (e.g. translation providers). If it does, say so instead of porting.
4. Split. A source plugin bundling several unrelated tweaks behind toggles becomes several small
   plugins, one feature each, no enable/disable toggles (the plugin list is the toggle). Propose
   the split before writing. Each gets only the grants it needs.
5. The API is a work in progress - if while porting you find a missing feature, instead of trying to hack around it,
   tell the user about that so that they can reach out to Inugram devs.

## 2. Pick the highest API that works

Go down this list and stop at the first rung that covers the behavior:

1. **Data and events**: `onNewMessage`/`onMessageEdited`/`onMessageDeleted`, `onUpdate`,
   `interceptSendMessage`, `interceptRpc`, `interceptUpdate`. Most "hide X", "block ads",
   "change what is sent", "auto-do Y on message" plugins are one interceptor. Rewriting the
   server's answer beats hooking every UI place that shows it.
2. **Account API**: `account.sendMessage`/`sendMedia`/`editMessage`/`setReaction`/`readHistory`,
   `getUser`/`getChat`/`getMessagesCached`/`iterHistory` etc. Then `invokeRpc` scoped per
   method for anything not covered.
3. **Declarative UI**: `inu.ui.settingsPage`, `register{Global,Chat,Message,Profile}Action`,
   `bulletin`, `dialog`, `chooser`, `prompt`, `selectPeers`, `openPage`, `openChatHistory`,
   `getCurrentScreen`/`onScreenChanged`, `inu.icons.*`.
4. **Web-like I/O**: `fetch` (scoped to hosts), `localStorage`, `inu.fs`, `inu.canvas`,
   `inu.clipboard`, `inu.openUrl`.
5. **`unsafe.jvm`**: reflection, `inu.jvm.defineClass` for interfaces/subclasses/listeners,
   `inu.android.nativeView` for custom views.
6. **`unsafe.xposed`**: method hooks, only when the behavior lives purely in app code with no
   data-layer seam (layout, drawing, animations, stock UI logic).
7. **`inu.jvm.loadDex`**: effectively never. Use `defineClass` with routine bodies instead.

Every `unsafe.*` grant is shown to the user as dangerous. Mixing rungs is fine: a UI tweak can be
an xposed hook while its settings are a plain `settingsPage`.

## 3. API map

Source API on the left, the recommended Inugram equivalent on the right. "Avoid" rows name the
mechanical equivalent only so it is recognized; use the recommended one instead. Exact signatures
are in the typings.

### Plugin metadata and lifecycle (`base_plugin.BasePlugin`)

| Source | Inugram |
| --- | --- |
| `__id__` | manifest `id`, only if the user wants one; reverse-domain recommended |
| `__name__`, `__version__`, `__description__` | manifest `name`, `version`, `description` (`{ en, ru }` when the original is bilingual) |
| `__author__` | manifest `author`: `'<original> (original), <porter> (Inugram port)'` |
| `__icon__ = "Set/3"` | manifest `icon: 'tg://addstickers?set=Set&idx=3'` |
| `__min_version__`, `__app_version__`, update checkers | drop |
| `on_plugin_load()` | top-level code. Register interceptors first (startup waits up to 500 ms for them). No top-level `await`: wrap async start-up in a function |
| `on_plugin_unload()` | usually nothing: every registration, timer, page and hook is released on unload. `inu.onUnload` only for state left in the app's Java objects |
| `unhook_method(h)`, `remove_menu_item(id)`, `remove_hook(name)` | call the `Disposer` the registration returned |
| `on_app_event(AppEvent.START / STOP / RESUME / PAUSE)` | `inu.onAppVisibilityChange`, modes `'foreground'` / `'background'`, `'resumed'` / `'paused'` respectively |
| `self.log(...)`, `android_utils.log(...)` | `console.log` / `console.warn` / `console.error`. Keep only what helps debugging |
| `try/except` + `traceback.format_exc()` around everything | drop. Uncaught errors are logged; `.catch` floating promises |

### Settings (`get_setting`, `set_setting`, `create_settings`, `ui.settings`)

| Source | Inugram |
| --- | --- |
| `get_setting(key, default)` / `set_setting(key, value)` | `localStorage` with a `DEFAULTS` object, one JSON value per key (see `in-app-notifications.ts`) |
| `create_settings()` returning rows | `inu.ui.settingsPage({ title, items: () => [...] })` + `inu.registerSettings(page)` |
| `Header(text)` | `inu.ui.header(text)` |
| `Divider(text)` | `inu.ui.separator(text)` |
| `Switch(key, text, default, subtext, icon, on_change)` | `inu.ui.check({ id, text, subtitle, icon, checked, onChange })`; `onChange` must store the value |
| `Selector(key, text, default, items, icon, on_change)` | `inu.ui.select({ id, text, items, selected, onChange })` |
| `Input(key, text, default, subtext, icon)` / `EditText` | `inu.ui.button({ value, onClick })` opening `inu.ui.prompt` |
| `Text(text, icon, on_click, accent, red)` | `inu.ui.button({ text, icon, onClick })` |
| numeric `Input` for a bounded number | `inu.ui.slider` |
| `Custom(view)` | rethink with stock rows first; else `inu.android.nativeView(view)` (needs `unsafe.jvm`) |
| conditional rows (`if self.get_setting(...)`) | same `if` inside `items()`; give dynamic rows explicit `id`s |
| `icon="msg_settings"` (an `R.drawable` name) | `inu.android.resourceIcon('msg_settings')`, or `inu.icons.common(...)` when one fits, but prefer common icons |

### Messages and requests (`client_utils`, send/request/update hooks)

| Source | Inugram |
| --- | --- |
| `add_on_send_message_hook()` + `on_send_message_hook(account, params)` | `inu.interceptSendMessage(filter, ({ message, account }) => ...)` (needs `interceptSendMessage`) |
| `if not params.message.startswith('.cmd'): return HookResult()` | `{ text: /^\.cmd\b/ }` filter: non-matching sends never reach JS |
| `params.message`, `params.entities`, `params.caption` | `message.text` (a `TextWithEntities`; assign a string or `md`/`html` result). The caption is `message.text` too |
| `params.peer` | `message.peer` (marked id) |
| `params.replyToMsg`, `params.replyToTopMsg`, `params.replyQuote` | `message.reply`, `message.topicId` |
| `params.photo`, `params.document` | `message.media` (`LocalMedia` / `InputMedia`), `account.createLocalMedia` |
| `params.scheduleDate`, `params.notify` | `message.scheduleDate`, `message.silent` |
| `HookResult(strategy=MODIFY / MODIFY_FINAL, params=params)` | mutate `message`, return `'send'` |
| `HookResult()` / `HookStrategy.DEFAULT` | return `'send'` without changes |
| `HookStrategy.CANCEL` + `run_on_queue(work)` + `send_message(...)` later | async middleware: `await` the work, edit `message`, return `'send'` (60 s budget, `context.signal`). Avoid drop-and-resend |
| `HookStrategy.CANCEL` with nothing to send (local-only command) | return `'drop'` |
| `pre_request_hook('TL_messages_getHistory', account, request)` | `inu.interceptRpc('messages.getHistory', (ctx, next) => ...)`, edit `ctx.request` (`interceptRpc(messages.getHistory)`) |
| `post_request_hook(name, account, response, error)` | same middleware: `const res = await next()`, edit or replace `res`; an error rejects `next()` as `inu.RpcError` |
| `HookStrategy.CANCEL` in `pre_request_hook` | return a response without calling `next()`, or return/throw `inu.RpcError` |
| `add_hook('TL_updateNewMessage')` + `on_update_hook` to modify/drop | `inu.interceptUpdate('updateNewMessage', ({ update }) => 'deliver' / 'drop')` |
| `on_update_hook` / `on_updates_hook` only to react | `inu.onNewMessage` / `onMessageEdited` / `onMessageDeleted` (`onUpdate(new_message)` etc.), or `inu.onUpdate([...types])` |
| `add_hook(name, match_substring=True)` | list exact constructors / methods; there is no substring match |
| `send_message({'peer', 'message', 'entities', 'replyToMsg', ...})`, `send_text` | `account.sendMessage(peer, md\`...\`, { replyToMessageId, topicId, silent })` (`account.write(send)`) |
| `send_photo`, `send_document`, `send_audio` | `account.sendMedia` / `sendMultiMedia` with `createLocalMedia(blob)` or `uploadFile` |
| `send_request(TL_x(), RequestCallback(lambda res, err: ...))` | typed account method if one exists, else `try { await inu.invokeRpc({ _: 'x', ... }) } catch (e) { if (e instanceof inu.RpcError) ... }` (`invokeRpc(x)`) |
| `get_messages_controller().getUser(id)` / `getChat(id)` / `dialogs_dict` | `account.getUser` / `getChat` / `getDialog` / `iterDialogs` (`account.read(peers)`, `account.read(dialogs)`) |
| `get_messages_controller().isDialogMuted(...)` | `account.isDialogMuted(dialogId, { topicId })` |
| `get_messages_controller().getUserFull`, `loadFullUser` | `account.getUserFull` / `getChatFull` |
| `getMessagesStorage()` reads, history loops | `account.getMessagesCached` / `getMessages` / `iterHistory` (`account.read(messages)`, `account.read(history)`) |
| `MessageObject.messageOwner.message`, `.isOut()`, `.getDialogId()`, media checks | `inu.Message` getters: `text`, `out`, `dialogId`, `senderId`, `topicId`, `mediaType`, `replyToMessageId`, `forwardedFrom`; `raw` for the rest |
| `MessageObject.messageText` (what the chat list shows) | `account.previewMessage(message)` |
| `get_user_config().getClientUserId()`, `getCurrentUser()` | `account.userId`, `account.getMe()` (`account.read(self)`) |
| `UserConfig.selectedAccount`, `get_account_instance()`, `get_connections_manager()` | the `account` passed to the handler, `inu.account()`, `inu.withCurrentAccount` |
| `get_file_loader().loadFile(...)`, `FileLoader.getPathToAttach` | `account.downloadMedia(message)` / `downloadMediaToFile` |
| `getSendMessagesHelper().sendReaction`, `markDialogAsRead`, `deleteMessages`, `editMessage` | `account.setReaction` / `readHistory` / `deleteMessages` / `editMessage` (`account.write(...)` scopes) |
| `sendTyping` via `TL_messages_setTyping` | `account.sendTyping` (`account.write(typing)`) |
| custom translation via hooks on the translate controller | `inu.registerTranslationProvider` |
| `NotificationCenter.getInstance(acc).addObserver(...)` | the event APIs above first; `inu.android.addNotificationCenterDelegate` (`unsafe.notificationCenter` + `unsafe.jvm`) last |
| hooks suppressing system notifications | `inu.notifications.suppress()` / `account.suppressNotifications()` (`notifications.suppress`) |
| `markdown_utils.parse_markdown(text)` | `inu.utils.md` (`**b**`, `__i__`, `--u--`, `~~s~~`, `\|\|spoiler\|\|`), or `html` / `thtml` |

### UI (`add_menu_item`, `ui.bulletin`, `ui.alert`, fragments)

| Source | Inugram |
| --- | --- |
| `add_menu_item(MenuItemData(menu_type=MenuItemType.DRAWER_MENU, ...))` | `inu.registerGlobalAction({ id, text, icon, callback })` |
| `MenuItemType.CHAT_ACTION_MENU` | `inu.registerChatAction` (`ctx.dialogId`, `ctx.topicId`, `ctx.account`) |
| `MenuItemType.MESSAGE_CONTEXT_MENU` | `inu.registerMessageAction` (`ctx.messages`, `placements: ['bubble', 'selection']`) |
| `MenuItemType.PROFILE_ACTION_MENU` | `inu.registerProfileAction` |
| `MenuItemData(item_id, text, icon, subtext, condition, priority)` | `id` (stable), `text`, `icon`, `visible: ctx => ...`; no priority (users reorder) |
| `context.get('dialog_id' / 'message' / 'user' / 'chat')` in `on_click` | `ctx.dialogId`, `ctx.messages`, `account.getUser(ctx.dialogId)` / `getChat` |
| `context.get('fragment')` to open something | `inu.ui.openPage({ type: 'chat' / 'profile' / 'settings', ... })` |
| `BulletinHelper.show_success / show_error / show_info(text)` | `inu.ui.bulletin({ text, icon: inu.icons.animation('success' / 'error' / 'info') })` |
| `BulletinHelper.show_with_button(text, icon, button, on_click)`, `show_undo` | `const r = await inu.ui.bulletin({ text, icon, button })`, act on `r === 'button'` |
| `BulletinHelper.show_two_line(title, subtitle)` | `inu.ui.bulletin({ text, subtitle, icon })` |
| `BulletinHelper.show_copied_to_clipboard()` | `inu.clipboard.write(text)` + a short bulletin |
| `BulletinHelper.show_simple(text)` / Android `Toast` | `inu.ui.bulletin(...)` / `inu.ui.toast(text)` |
| `AlertDialogBuilder` with title, message, positive/negative/neutral buttons | `const r = await inu.ui.dialog({ title, message, positive, negative, neutral })` |
| `AlertDialogBuilder.set_items(...)` | `inu.ui.chooser({ items })` (`multiple: true` for checkboxes) |
| `AlertDialogBuilder` + `EditTextBoldCursor` via `set_view` | `inu.ui.prompt({ title, hint, value })` |
| `make_button_red` | `chooser` item `{ text, danger: true }` |
| `ALERT_TYPE_SPINNER` / `ALERT_TYPE_LOADING` progress dialog | bulletin with `inu.icons.animation('loading')`, or none: the work is async anyway |
| custom `BottomSheet` / `BaseFragment` lists | `inu.ui.settingsPage` opened with `inu.ui.openPage(page)` (`transient: true` for one-off pages) |
| a fragment showing a list of messages | `inu.ui.openChatHistory` |
| contact/chat picker activities (`DialogsActivity` with a delegate) | `inu.ui.selectPeers` |
| `get_last_fragment()` to check where the user is | `inu.ui.getCurrentScreen()` / `inu.ui.onScreenChanged` |
| `get_last_fragment()` / `get_activity()` as a Java object | `inu.android.getCurrentFragment()` / `getCurrentActivity()` (`unsafe.jvm`), only for JVM work |
| `Browser.openUrl`, `Intent.ACTION_VIEW` | `inu.openUrl(url)` (`openUrl`) |
| `android_utils.copy_to_clipboard`, `ClipboardManager` | `inu.clipboard.write` / `read` (`clipboard.write`, `clipboard.read`) |
| Intent-based file pickers, `startActivityForResult` hooks | `inu.ui.pickFile`, `inu.ui.saveFile` |
| `R.drawable.x` | `inu.android.resourceIcon('x')` |
| `OnClickListener(fn)` on a settings or menu row | the row's `onClick` / `callback` |

### Threads, I/O, Python libraries

| Source | Inugram |
| --- | --- |
| `run_on_ui_thread(fn)`, `run_on_queue(fn, PLUGINS_QUEUE / GLOBAL_QUEUE)`, `threading.Thread` | not needed: high-level APIs hop threads themselves. Use `async`/`await`; split long loops with `await` (2 s per turn) |
| `time.sleep(n)` | `await new Promise(r => setTimeout(r, n * 1000))` |
| `threading.Timer`, polling loops | `setTimeout` / `setInterval` (throttled in background) |
| `requests.get/post(...)`, `urllib` | `fetch(url, { timeout })` with `fetch(host)` grant per host |
| `json`, `re`, `datetime`, `random`, `uuid` | JS builtins; `inu.utils.formatDate` / `formatNumber` / `formatFileSize` / `formatDuration`; `crypto.randomUUID` |
| `base64`, hex | `inu.utils.toBase64` / `fromBase64` / `toHex` / `fromHex` |
| `file_utils.get_cache_dir / get_files_dir / get_plugins_dir`, `read_file`, `write_file`, `ensure_dir_exists`, `list_dir`, `open()` | `inu.fs` (`read`, `write`, `append`, `mkdir`, `readdir`, `exists`, `stat`, `rm`, `copy`, `move`) in the private dir (`fs`, `fs(200mb)`) |
| writing to public Downloads / gallery | `inu.ui.saveFile` |
| JSON settings files on disk | `localStorage` |
| `PIL.Image`, `ImageDraw`, `ImageFont`, `ImageSequence` | `inu.canvas` (`create`, `decode`, `decodeAnimation`, `loadFont`, `createEncoder`, `convertToBlob`) |
| `android.graphics.Bitmap` / `Canvas` via reflection for image work | `inu.canvas` |
| `MediaMetadataRetriever` for frames/size | `LocalMedia.width/height/duration`, `inu.canvas.decodeAnimation` |
| bundled pure-Python libs | an npm package bundled by the CLI (no DOM/Node APIs), or a few lines of TS. Ask before adding a dependency |

### JVM and hooking (`hook_utils`, `java`, xposed)

Almost all of the below need `unsafe.jvm` (and some additionally need `unsafe.xposed`).

| Source | Inugram |
| --- | --- |
| `find_class('a.b.C')`, `jclass('a.b.C')`, `from org.telegram... import C` | `inu.jvm.cls('a.b.C')`; nested `a.b.C$D` |
| `C.staticMethod(x)`, `C.FIELD` | `C.callStatic('staticMethod', x)`, `C.getStaticField('FIELD')` |
| `obj.method(x)`, `obj.field` | `obj.call('method', x)`, `obj.getField('field')` |
| `get_private_field(obj, 'f')` / `set_private_field(obj, 'f', v)` | `obj.getField('f')` / `obj.setField('f', v)` |
| `C.getClass().getDeclaredMethod('m', T1, T2)` + `setAccessible` | `C.getDeclaredMethod('m(LT1;LT2;)V')` (Dalvik descriptor) |
| `jarray(T)(...)`, `ArrayList()` building | `new (inu.jvm.cls('java.util.ArrayList'))()`; JS arrays do not cross the bridge |
| `Bundle()` + `putX` | `inu.android.bundle({ ... })` |
| `TLRPC.TL_x()` built by hand | a plain TL object `{ _: 'x', ... }`; `inu.jvm.fromTl` only when Java needs the instance |
| `dynamic_proxy(Runnable)` | `inu.jvm.routine(...)` (always runs) or `inu.jvm.runnable(fn)` (rare, needs plugin APIs) |
| `dynamic_proxy(SomeInterface)` / listener classes | `inu.jvm.defineClass({ interfaces: [I], methods: { ... } })`; routine bodies when called often or off-thread |
| Python subclass of a Java class | `defineClass({ superclass, methods, constructors })`, `inu.jvm.getSuper(this)` |
| `InMemoryDexClassLoader` / `DexClassLoader` with a bundled DEX | `defineClass` + routines. Never `inu.jvm.loadDex` without the user's sign-off; an opaque payload is a blocker |
| `self.hook_method(m, MethodHook)` with `before_hooked_method` / `after_hooked_method` | `inu.xposed.hookMethod(m, { before, after })` (`unsafe.xposed` + `unsafe.jvm`) |
| `MethodReplacement.replace_hooked_method` / `XC_MethodReplacement` | `before` calling `ctx.setReturnValue(v)` |
| `param.args[i] = v`, `param.thisObject`, `param.getResult()`, `param.setResult(v)` | `ctx.args[i] = v`, `ctx.thisObject`, `ctx.returnValue`, `ctx.setReturnValue(v)` |
| `param.setThrowable(t)` | `ctx.setThrowable(t)` |
| `XposedBridge.invokeOriginalMethod` | `inu.xposed.callOriginalMethod(method, thisObject, args)` |
| `self.hook_all_methods(C, 'name', hook)` | `inu.xposed.hookAllOverloads(C, 'name', hook)` |
| `self.hook_all_constructors(C, hook)` | `inu.xposed.hookAllConstructors(C, hook)` |
| `priority=...` | none; hooks chain in registration / plugin order |
| `@hook_filters(HookFilter.ArgumentNotNull(0), HookFilter.Condition(...))` | `filter: inu.jvm.routine((a0) => a0 !== null && ...)` gating a JS hook, or the check inside a routine hook |
| hook on a hot path (bind, measure, layout, draw, per-message) in Python | `inu.xposed.routine((ctx) => ...)`. Never a plain JS hook there |
| `layout_hook`, `XposedHook` helper decorators | plain `hookMethod` / `hookAllOverloads` with routine phases |
| `PluginsController` / host plugin internals, the source client's own `*Config` classes and packages | do not exist. Find the stock Telegram equivalent or drop the feature |

## 4. When JVM or xposed is unavoidable

- Verify every class, method, field and descriptor against the app's sources: `rg` in
  `worktree/TMessagesProj/src/main/java` inside the Inugram repo, otherwise the stock
  Telegram Android sources or a jadx decompile of the Inugram APK. The source client is a different fork; names drift. Pin overloads with Dalvik
  descriptors (`'areTabsEnabled(Lorg/telegram/tgnet/TLRPC$Chat;)Z'`).
- Hook as close to the data as possible: one controller/model method beats many view methods.
- Hot path (binding, layout, drawing, per-message, per-list-item) or must-always-apply logic:
  `inu.xposed.routine` / `inu.jvm.routine`. Read `routines.md` for the subset (no closures,
  `===` only, Java members, captures are snapshots, 250 ms).
- Rare hook that needs plugin APIs: routine `filter` + JS `before`/`after`.
- Plain JS hooks only for rare calls off hot paths.
- `defineClass` bodies called often or off the plugin thread: routine bodies.
- Read constants once outside routines (`const X = Cls.getStaticField('X')`) and capture them.
- Do not port defensive `try/except` around every reflection call. Hook phase errors are already
  logged and skipped; keep a `try` only where a failure is expected and must not stop the rest.
- Reach out! In many cases an awkward xposed hook is best battled with a proper high-level API 

## 5. Writing the code

- Less code. Drop the source's boilerplate: logging wrappers, `traceback` dumps, UI-thread
  juggling, manual listener bookkeeping, version gates, dead settings.
- Keep behavior and defaults the user sees. Where the port must differ (an API cannot read
  something, a setting makes no sense here), note it in a short comment and in the report.
- No enable/disable switch for the whole plugin.
- Localization: only if the original had it. Pick by `inu.info().language`; keep the original
  strings.
- Narrowest grants: scoped `account.read(...)`/`account.write(...)`, `fetch(host)`,
  `invokeRpc(method)`, `interceptRpc(method)`, `onUpdate(type)`. Use the `interceptSendMessage`
  filter so non-matching sends never enter JS.
- Attach `.catch` to every promise not awaited; an unhandled rejection disables the plugin.
- Do not keep TL views or interceptor contexts past the call; copy fields out.
- No `minify`.

## 7. Report

Short: what the port does, the grants and why each is needed, what was split out, what was
dropped or changed versus the original and why, anything from the audit, and anything that needs
on-device checking (hooks especially).
