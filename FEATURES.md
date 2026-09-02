# entinyGram features

> non-exhaustive list of what this fork adds, tweaks or fixes vs stock telegram android.
> keep this updated as patches are added/removed.

most things are toggleable in `Settings → entinyGram`, with sensible opinionated defaults.

🐶 - Inugram-exclusive (as far as i know, as of writing)
📡 - entinyGram-exclusive

## entinyGram additions

our own layer on top of the inugram patchset: restricted Telegram features, privacy tools, power-user controls, and experiments.

### privacy & protection

- 📡 **ghost mode (stealth)**: privacy suite with granular toggles:
  - **quick toggle** in the drawer header, drawer menu, and chat-list menu — flips every option to full stealth at once (AyuGram-style); its indicator lights up only in that full state
  - **quick-toggle locks** 🔒: exclude individual options from the quick toggle, like exteraless/NagramX
  - **status indicator** with a ghost icon in the dialogs list and active chat header
  - **quick access** to full Ghost settings by long-pressing the drawer button
  - hide read receipts for messages, voice notes, and video messages
  - **read on send**: mark incoming messages read only when replying (enabled by default)
  - **mark as read locally**: clear unread counter badges on open while keeping messages unread on the server (configurable toggle)
  - hide story views, online status, typing, recording, and upload indicators
  - **whitelist management**: dedicated screen listing every chat exempt from Ghost Mode, with one-tap add/remove
- 📡 **anti-deletion & edit history**:
  - **save deleted messages** (marked with 🗑️) and media to `Downloads/entinyGram/media/`
  - **per-category controls** for private chats, groups, channels, and bots, plus a separate toggle to skip saving your own outgoing deleted messages; secret chats are never saved
  - **delete permanently** from a saved-deleted message's own long-press menu, wiping just that one entry (and its media) instead of clearing the whole cache
  - **save edit history** (marked with ✏️) in an interactive message sheet
  - **text diff mode** with inline color-coded changes
  - **deleted-message styling** with transparency, custom mark and accent color, plus a live settings preview
  - **cache TTL and storage manager** with automatic expiration and per-dialog cleanup
  - **search the local archive**: full-text search across saved deleted messages and edit history
- 📡 **self-destructing & expiring media**:
  - **save view-once media**: view-once photos, videos and video messages show as standard media without auto-destruction
  - **keep self-destruct content per-category**: keep secret-chat media, secret-chat text, view-once media and auto-delete (timed) messages locally after timers expire
- 📡 **save user info** (beta): locally preserve phone country, registration date, and name/avatar change history
- 📡 **stalker pack**:
  - **presence logger**: opt-in per-contact online/offline tracking — pick who to watch from their profile menu or a dedicated watch-list screen (which also shows each contact's local status-change log), every status change is logged locally, with an optional popup notification the moment they come online or go offline
  - **anti-P2P call IP leak**: force voice/video calls through Telegram's relay servers so the other party can never learn your real IP
  - **typing status spoofing**: loop a fake typing/recording/uploading indicator in any chat, reachable from the chat header menu or a quick-launch list in settings
- 📡 **adblock & content filtering**:
  - **hide sponsored messages**: completely disables Telegram sponsored channel ads and video ads
  - **hide proxy sponsor chat**: blocks the promoted chat/channel some MTProto proxies pin to the top of your chat list while connected
  - **regex content filter**: manage multiple named filters (not just one combined pattern) with per-filter enable/case-insensitive/allow-list toggles, global or scoped to a single chat, with per-chat exclusions for global filters. Add a filter straight from a message's long-press menu, or manage the full list from settings. Hides or spoiler-covers matches; caches match results locally so scrolling stays fast even with many filters. Export/import the full filter set as JSON.

### restricted features

- 📡 allow screenshots and screen recording in protected windows, secret chats, expiring media, and protected stories (now including no-forwards stories)
- 📡 **hide screenshot notifications**: taking a screenshot in a secret chat no longer alerts the other person
- 📡 copy text and select messages in protected chats when restricted forwarding bypass is enabled
- 📡 **local Telegram Premium**: unlock client-side premium UI and limits, including badges, custom icons, emoji statuses, colors, translation bar, reactions, animations, and transcription. The toggle applies instantly; server-side premium-only writes remain unavailable.
- 📡 **content protection & forward bypass**:
  - **save any story**: direct download of any story to gallery, bypassing Telegram Premium requirements and download restrictions
  - **bypass restricted forwarding**: copy and forward messages/media from protected chats and channels where saving/forwarding is restricted
  - **forward as own message**: download and re-send forwards as your own new message without the "Forwarded from" header
- 📡 **whole-chat translation without telegram premium**: unlock translation bar and real-time chat translation without subscription — pick a third-party provider below; the stock Telegram API provider still requires Telegram Premium
- 📡 **third-party translation providers**: use Google, DeepL, Yandex, Bing/Azure, MyMemory, Lingo, or TranSmart for chat and message translation. Providers expose supported-language pickers, retries, fallback, force-translate mode, optional translation of your own messages, and translated link previews.
- 📡 **free voice transcription**: voice-to-text for voice notes and video messages through configurable AI providers (Groq Whisper, Gemini Flash, OpenAI, Cloudflare)
- 📡 **official-app emulation** (APP_ID 4): direct purchases and premium checkout without official app blocks
- 📡 **hidden & archived star gifts**: restores removed, archived, and limited Star Gifts in the Telegram gifts catalog
- 📡 **unlimited limits override**: locally raise Telegram's pin, favorites, and folder ceilings — pinned chats and folder chats beyond the server limit (5/10 pins, 100/200 folder chats with Premium) are tracked client-only and never revert on resync; favorite stickers/GIFs simply stop being trimmed from the local cache
- 📡 **force relay calls**: always route voice/video calls through Telegram's relay servers, never peer-to-peer, so the other party's client can never learn your real IP address

### power-user tools

- 📡 **developer badges**: show entinyGram, inuGram, and official-channel badges in chat headers and profiles. Tap a badge for details or disable them in Settings → Chats.
- 📡 **mutual contact icon**: icon in user lists and chats with auto-shifted role tags & toggle in settings
- 📡 **estimated registration date & DC**: profile info card with fast copy and DC location resolution
- 📡 **delete profile photos**: one-tap delete all or selected profile photos from profile menu, photo viewer, or settings
- 📡 **extended message details & JSON view**: inspect raw message objects and properties
- 📡 **url parsing improvements**: support multi-component TLDs, hyphens in subdomains, preserve clickable ASCII domains after Cyrillic text, and make bio/description links clickable without `http(s)://`
- 📡 **ai compose & tools**: draft rewriting, translation, and formatting with configurable providers
- 📡 **emoji picker in name and surname fields**: built-in emoji selection button in account profile editor
- 📡 **custom title alignment & modular navigation**: selective centering for chat headers and centered status-bar titles across all other screens; modular M3 drawer cards
- 📡 **auto marquee for long titles**: chat header titles/subtitles, action bar screen titles and profile names smoothly auto-scroll instead of fading out - *inspired by the auto_marquee plugin (@chestertech)*
- 📡 **typing status spoof**: per-chat menu option to loop a fake "typing…", "recording…" or "uploading a file…" indicator to the other side; stops automatically when you leave the chat
- 📡 **open by numeric Telegram ID**: open any user profile directly by numeric ID — tap numeric IDs in messages to open their profile via a context menu shortcut, type an ID in the global search bar to jump straight to their profile, or use the "Open by ID" tool in settings
- 📡 **avatar corners**: custom slider (0–28dp) with dynamic badge indicator (`SHARP`, `ROUND`, or radius), live interactive dialog mock preview card with squareness-aware online dot cutout, and `Unified corner radius` toggle for regular and forum avatars - *ported from [NagramXF](https://github.com/Keeperorowner/NagramXF)*
- 📡 **customizable bottom tabs (beta)**: independent reordering and show/hide toggles for Contacts, Settings, Calls, and Profile tabs in MainTabsActivity, with separate tab title visibility toggle
- 📡 **customizable settings screen**: reorder and hide the rows of Telegram's own Settings screen (Inugram Settings, Account, Chat Settings, Privacy, Notifications, Data, Folders, Devices, Power Saving, Language, Premium, Stars, TON, Wallet, Business, Gift, Ask a Question, FAQ, Features, Privacy Policy) — untouched it renders exactly like stock, customized it collapses into one flat section
- 📡 **customizable My Profile info rows**: independent reordering and show/hide toggles for the phone number, bio, username, ID, and estimated registration date rows on your own profile
- 📡 **branded first-run intro**: replaces the stock Telegram wordmark and plane logo on the first onboarding slide with entinyGram's name and glyph/brand color
- 📡 **keep downloads running in background**: don't cancel in-progress downloads when a message scrolls off screen or you leave the chat, with an optional wake lock to keep transfers alive while the screen is off - *ported from a "Don't kill the download!" plugin (@shareui, fixed by @itNotMax/@MaxExteraPlugins)*
- 📡 **show poll results before you vote**: always reveal vote percentages, even for anonymous polls you haven't voted in yet
- 📡 **send as round video**: crop any gallery video square and send it as a real round video-note message (only offered for videos ≤60s — Telegram's round-message format has a hard duration cap and silently fails to send past it)
- 📡 **standalone AMOLED theme**: a true-black theme selectable from the regular theme list, independent of Monet (no dynamic accent tracking) - *ported from NagramX Turbo*
- 📡 **show spoilers directly**: skip the tap-to-reveal step everywhere — text and media (photos, videos, round videos) spoilers show their content right away
- 📡 **iOS-style design**, ported from [exteraless](https://github.com/exteraless/exteraless):
  - wide, full-width bottom navigation bar (60dp, evenly-spread tabs, like Telegram for iOS)
  - Chats tab tap returns to the first folder before scrolling to top
  - centered chat header: avatar+title+subtitle grouped in a pill, chat avatar moved into the "⋮" menu slot (long-press it for the chat menu) — **this one is a pretty janky patch (touch-target overlap in chats with extra header icons, off-by-a-few-dp centering when a call/search icon is present, untested interaction with search/action mode) and we don't have much motivation to keep polishing it ourselves, so pull requests to clean it up are welcome**

### debloat & premium noise

- 📡 **profile & gifts**:
  - hide the Story / Gift / Live Stream & Video Chat buttons on profiles
  - hide "Send a Gift" and "Archived Stories" from the profile "⋮" menu
  - hide Premium badge, collectible (NFT gift) status styling, Stars rating, and organization verification badge next to names
  - hide the colorful peer-color profile background and the decorative animated pattern icons on profile headers
  - hide the ring of gift icons around the avatar, and/or the whole Gifts tab in shared media (independent toggles)
  - hide the Similar Channels/Bots tab in shared media
  - hide the group emoji-pack promo hint
- 📡 **chat gifts & upsells**:
  - hide the gift button in the message input field
  - hide gift service messages (Stars/TON/Premium gifts) and giveaway messages from chat
  - hide the Premium upsell shown when a caption exceeds the free length limit (the limit itself still applies)
  - hide Premium lock badges on attach-menu tabs and "Unlock" buttons on locked emoji packs
- 📡 **chat list & prompts**:
  - hide the gift-auctions promo banner and the "free up space" cache-cleanup card pinned above the chat list
  - hide PSA promo chats (separate from the existing proxy-sponsor-chat toggle)
  - hide the "similar channels" discovery card Telegram injects into chats after joining one
  - disable the post-call rating prompt
  - disable the contacts-permission and lockscreen (MIUI / Android 14+ full-screen-intent) permission nags shown on cold start

the sections below contain the broader feature set: inugram functionality, features adapted from other telegram clients, bug fixes, and entinygram additions in their original context.

## inuGram additions

### appearance & general

- navigation drawer, like in older Telegram versions; can be used alongside or instead of the bottom tabs
  - 🐶 an option to (ab)use predictive back to open the drawer
- 📡 **no accounts list in settings**: the redundant accounts block is always hidden from the settings screen - switching happens from the avatar menu or the drawer - *ported from [inugram PR #34](https://github.com/teidesu/inugram/pull/34), made unconditional*
- monet (material you) theme support - *based on [NagramX](https://github.com/risin42/NagramX)*, 🐶 improved. plus a quick switcher in appearance settings (light/dark/amoled/auto)
- 🐶 classic ui mode for folders bar, shared media tabs, global search and chat elements (reverts the >12.6 "liquid glass" look)
- icon replacement (currently: solar pack, [480 Design](https://t.me/Design480)) - *ported from [NagramX](https://github.com/risin42/NagramX)*
- notification icon: Telegram (default) or entinyGram
- icon replacement (solar pack by [480 Design](https://t.me/Design480) - *ported from [NagramX](https://github.com/risin42/NagramX)*; vkui pack by [VK](https://github.com/VKCOM/icons) - *ported from [Catogram](https://github.com/Catogram/Catogram)*)
- notification icon: Telegram (default) or Inugram
- show seconds in timestamps
- override Telegram's detected device performance class
- 🐶 customizable animation speed multiplier (incl. instant)
- join/creation date in group & channel profiles
- show linked channel in discussion group profile similar to personal channel
- hide own phone number from ui
- fonts: manage a list of fonts used in the media editor text tool; device system fonts can be toggled on/off. install fonts from .ttf/.otf/.ttc files right from the chats.
- extra meme-style outlined text style in the media editor text tool
- 🐶 app-font *stack*: pick a user-provided TTF/OTF/TTC family as the whole-app font, optionally with fallbacks (e.g. for other scripts)
- 🐶 separate monospace font: pick any roster font for code blocks (inline + pre), previewed live alongside the stack
- 🐶 hide fade views
- 🐶 old (pre-12.6) mention/reaction indicator
- 🐶 toggleable scrim blur
- toggle to disable glass glare
- 🐶 reduce menu motion: skip context menu stagger and reaction bar slide-in/scale animations
- material 3:
  - switches
  - fabs
  - predictive back
  - navigation animation
  - lists & sections
  - avatars (tonal on-container initials instead of white)
  - profile action buttons
- 🐶 toggle to replace profile photo bottom blur with a plain gradient fade
- disable number rounding
- export/import settings to/from json file
- cloud sync of settings via web app storage api
- search and deeplinks for fork settings, incl. `tg://settings/...` links tapped from inside a chat message
- osmdroid-based map view (default) — pure-Java OpenStreetMap renderer with no native libraries, replacing the old MapLibre view to cut ~12MB of native code from the APK
- 📡 trimmed APK size: dropped unused firebase-appindexing/datatransport and com.google.android.material (which only existed for an opt-in wavy progress bar), and unused annotation-only Guava transitives
- customizable map preview provider
- in-app updater - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
- 🐶 crash report sheet: catches uncaught exceptions, offers to share the log on next launch, posts a tap-to-restart notification
- keep search query after picking a result in peer selection screens

### dialogs list / main page

- bottom tabs: 🐶 compact mode, hide contacts tab, hide bar entirely
- bottom tabs: material design 3 style with a pill indicator behind the selected icon
- double-tap account tab to switch to next account
- long-tap "chats" tab to pick folder from menu
- folder tabs: display mode (titles / titles+icons / icons-only), optional indicator stroke style, move to bottom of the screen
- folder unread counter modes: hide / regular / exclude muted / 🐶 exclude muted non-dms
- hide "all chats" folder tab
- remove "Archived Chats" from the chat list entirely
- custom title text: Inugram / @username / first name / "Chats"
- 🐶 dialogs fab customization: main + secondary actions, hide-on-scroll, left-side
- 🐶 "create as supergroup" toggle in group creation
- 🐶 deeplink / username quick-open from global search
- open archive directly on pull-down (🐶 done right, without revealing the cell)
- mutual contact icon in contacts list
- customizable dialogs list pull-down action: reveal archive (stock), open archive directly (🐶 done right, without revealing the cell), open saved messages, open search, or disabled entirely. when the pull-down no longer leads to the archive, the archive row is hidden from the list and an "Archived Chats" entry appears in the drawer/overflow menu instead
- interactive chat preview (long-tap avatar): tappable bubbles, no tap-to-expand
- 🐶 community display modes: regular / open on avatar long-tap / invisible
- "select all" in the chat selection three-dot menu (selects all loaded chats in the current folder tab)

### chats

- customizable sticker size - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
- 🐶 remove extra bottom padding under stickers
- 🐶 full-quality sticker previews in sticker sheets & emoji panel (instead of blurry 90px thumbs; only on unmetered network or when already downloaded)
- show all recent stickers
- minimize sticker creator button in recent stickers
- sticker time overlay modes: show / 🐶 hide time / 🐶 hide on incoming / hide completely
- "Refresh" in the sticker/emoji pack menu
- compact edited indicator: pencil icon instead of the "edited" label
- toggleable message bubble tails
- 🐶 jump-to-discussion button from comments
- jump-to-beginning button in calendar popup - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
- remember all clicked replies when jumping back via the down-button
- 🐶 keep bot draft messages at the bottom
- 🐶 long-press reply panel in "Replies" chat opens discussion group
- show a "Deleted message" placeholder for deleted messages
- hide pinned panel
- hide channel, group preview (🐶) and replies (🐶) bottom bar (mute/join/etc)
- send message to discussion group without joining
- 🐶 member count on private invite sheets (instead of "private group/channel"; like tdesktop)
- 🐶 search: media-type filter + "show only matches"
- tapping a hashtag in a public channel defaults to the "This Chat" tab
- 🐶 "from user" picker in search also finds users not in chat by name, username, or cached user ID (like tdesktop)
- static pinned reactions in the reaction bar
- 🐶 reachable reactions bar (moved to the bottom of message menu)
- 🐶 reachable "seen by" (moved to bottom of message menu)
- double-tap message actions (separate for incoming/outgoing/editable channel posts), 🐶 customizable double-tap delay
- hide keyboard on scroll
- always show go-to-bottom button (don't hide on scroll-down)
- web preview: replacements (e.g. twitter→fixupx)
- 🐶 strip tracking params (utm_*, fbclid, si, erid, …) from links before opening and/or when pasting — *rules from AdGuard URL Tracking filter*
- 🐶 web preview refetch from menu
- 🐶 disable web preview limit on twitter-like websites
- 🐶 spoiler web previews: when the preview-generating link is under a spoiler, cover the whole preview card too
- tap a web preview photo to open it in the photo viewer
- 🐶 "Preview" in the link long-tap menu: peek a t.me message link (public or private) as a chat preview at that exact message
- message details from menu (+ show json)
- per-message statistics from message menu
- remove single message's file from cache from the message menu
- "Repeat" in message menu - re-send the same message to the same chat
- "Save to Downloads" for stickers & custom emoji (both in chat and in egs/sheets)
- customizable message context menu - reorder and hide items + long-tap forward/reply items + quick actions row (*ported from [NagramX](https://github.com/risin42/NagramX)*)
- wide channel posts - channel posts (and, separately, channel posts/forwards shown in the feed) stretch to the full available width instead of a narrow auto-sized bubble, with a live before/after preview in settings (*ported from [exteraless](https://github.com/exteraless/exteraless)*)
- customizable chat menu + extra actions:
  - Recent actions
  - Go to beginning
  - Go to message by ID
  - Delete my messages
  - Statistics / Administrators / Permissions / Invite links (admin shortcuts)
- 🐶 disable custom wallpaper and theme per chat
- per-forum client-side topics layout override (tabs/list) from the profile menu
- read-only chat "admin" page for non-admins
- split media restriction toggles for stickers / gifs / games / inline
- show id in profile, show user json
- long-tap the name in profile to copy it
- long-tap a selected user chip (privacy exceptions, add members, etc.) to open their profile (channels open the chat instead)
- "Stop" button in a bot profile stops (blocks) the bot instead of offering to delete the chat
- 🐶 drag the pinned-music sheet by its header to scroll/expand the playlist
- long-tap inline callback button to copy text or callback data
- "select between messages" (🐶 done right)
- 🐶 lift 100-message selection cap (forwards/saves/deletes are auto-chunked)
- 🐶 two-finger swipe over messages to select/deselect them
- more bulk actions in message selection mode (save, translate, gallery, pin/unpin, no-quote forward)
- in-place message translation, with optional web preview translation, original-text appending and on-device source-language auto-detection (hides Translate when already in your language)
- instant view pages translator
- show original time/date in the forwarded header, with regular, icon-only label, and compact one-line modes
- show forward count next to the view count on channel posts
- long-tap forward bar (above input) to cycle between regular / without sender / without caption
- long-tap a mention in a message to insert a name-mention into the input with custom text
- 🐶 restrict/ban menu items the avatar long-tap menu
- hide messages from blocked users: with a spoiler or completely - *partially ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
  - also hide messages from a custom list of users/channels without blocking them (via settings picker or profile menu)

### message input / formatting

- 🐶 customizable max input lines (and bumped default)
- 🐶 voice recorder moved into attachments drawer
- 🐶 camera placement in the attach panel: instant (stock), static, floating button or bottom tab. the last two free up the grid cell
- 🐶 custom formatting popup ui (better ux for span manipulation)
- 🐶 customizable text classifier (native / improved / off) - reduces false positive expansions
- show custom emoji *after* regular ones in `:smile` emoji suggestion popup
- "delete for both/all" default checkbox state
- hide "send as" picker (long-tap stickers button to reveal)
- round recorder:
  - zoom slider below the video feed
  - keep zoom on pinch release
  - gentler exponential zoom curve (like in normal camera apps)
  - toggle to disable dual-camera mode

### photo viewer

- Ultra HDR and PQ/HLG photos with HDR-capable displays - *based on [NagramX](https://github.com/risin42/NagramX)*
- "hide with spoiler" toggle
- "copy photo" / "copy frame" menu actions
- show dc + platform of the photo in menu
- seek bar for mp4 gifs
- always use the modern speed-control rewind on long-press
- mark public (fallback) / personal profile photos next to the date

### admin / event log

- 🐶 inline diff for message edits
- 🐶 "ban member" confirmation
- 🐶 expanded message details

### accounts

- passkey login
- qr login
- password autofill hints in login (for password managers)
- account limit raised to 8 (premium gating disabled)
- 🐶 customizable account order
- 🐶 launcher shortcut that asks which account to open
- per-account passcodes, hidden accounts, panic code, hidden settings deeplink - *ported from [Nekogram](https://github.com/Nekogram/Nekogram)*
- 🐶 paranoia mode: pick chats/channels to hide everywhere; all secret chats hidden too; exit by typing a custom code in chat search
  - optional whitelist mode: hide everything *except* the picked chats (service notifications stay visible)
  - optionally hide the Inugram settings entirely when enabled
  - optionally disguise as stock Telegram when enabled
  - optionally silence all notifications while enabled
  - optionally hide all other accounts while enabled
  - optionally collapse folder tabs (show only All Chats) while enabled
  - optionally hide your own stories (ring, profile tabs, archive) while enabled
  - optional launcher long-press shortcut to enter it quickly (hidden while active)
- biometric confirmation before deleting/clearing a chat or logging out - *inspired by [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)*
- optional biometric lock on Archived Chats — ask once per app session or every time you open the archive
- session list in Settings > Devices shows the real app name (entinyGram) instead of the registered api_id title ("Inugram")

### behavior

- call confirmation
- HD call audio on Bluetooth
- 🐶 reaction confirmation in non-joined chats
- 🐶 internal link confirmation (tg://, t.me/...)
- support `tg://user?id=…` links (opens the profile; user must be known locally)
- 🐶 disable swipe-down to minimize the in-app browser (only the minimize button collapses it)
- predictive back mode selector
- disable pull-to-next-channel
- disable swipe-to-unarchive
- 🐶 disable swipe-to-hide the General topic in the forum topics list
- disable motion photos (rendering + detection, in picker and in messages)
- disable notification chat bubbles
- 🐶 disable cloud drafts upload
- 🐶 disable wallpaper parallax
- 🐶 disable scroll-snap in profile
- 🐶 reduce profile motion (skip various enter animations, disable avatar scale-on-scroll effect)
- 🐶 prefer "Media" tab in profile over Gifts/Posts
- 🐶 recyclerlistview instant-tap
- open bottom-tab menus early by swiping up; flat highlight (not ripple) on menu hover
- faster downloads/uploads
- auto-disable the configured proxy while a VPN is active
- send MP4 files attached through Files as playable videos without conversion
- sort attach panel albums by photo count instead of recency
- 🐶 "Minimize" option in the attach panel discard prompt to keep the selection (e.g. to choose a message to reply to)
- choose the media save folder (Inugram/Telegram) used for saved photos, videos, music and downloads
- original video quality option in quality picker, including audio removal without re-encoding video
- remember last used settings in polls + reasonable defaults
- strip tracking parameters from URLs (UTM, fbclid, etc.) on open and paste using AdGuard tracking rules

### annoyances

- hide trending stickers/emoji in egs
- 🐶 hide ai features
- hide stories
- hide voice hint
- hide paid reaction upsell
- hide hashtag suggestions in chat input
- hide rich text editor button in chat input
- hide repost to story
- 🐶 hide bot commands and webview buttons
- hide intro greeting + non-clickable custom intro sticker
- 🐶 hide server-pushed suggestions
- disable phone number in chat title
- hide call button in chat title (still in overflow menu)
- hide reactions send animation
- 🐶 simple (non-bouncy) attach panel animation
- disable notification bubbles
- disable volume keys playing visible video with sound in chat
- disable quick share (long-tap share button → send to frequent contact without confirmation)
- disable auto-play when opening the pinned music player on a profile

### 🐶 bugfixes (vs stock)

- connection status title no longer gets stuck on "Updating..." when its transition animation is cancelled
- accelerated video playback no longer applies an unnecessary pitch shift; live speed-slider changes use Android's low-latency audio mixer path
- "Save to Downloads" copies uncached documents after downloading instead of requiring a second attempt
- cancelling a video download kept restarting it after streaming the video in PhotoViewer (the player's loader thread swallowed its shutdown interrupt, survived the viewer close, and re-requested the file on every cancel; also a file-reference refresh landing mid-cancel resurrected the operation into an uncancellable zombie)
- "Save to Downloads" preserves the original filename on Android 10+
- downloaded photos/videos no longer show up in the system gallery on devices whose scanner indexes app-private dirs (stock never wrote `.nomedia` into the media cache dirs; only on Android 11+, where the gallery-visible copies live elsewhere)
- gboard image paste no longer skips PhotoViewer
- reordering an attach-panel album preserves per-photo captions and no longer duplicates its album caption
- photo crop silently not applied to the sent image
- high-quality photo cropping in PhotoViewer (crop *before* downscaling)
- sticker creator output sent as photo when high-quality default is on
- non-square webm stickers rendered off-center in the emoji panel (precached frames were blitted top-left and scaled by width only)
- webm sticker permanently stuck blank until the app is restarted (an image load cancelled mid-flight published a decoder-less drawable into the animation cache, whose stream stayed cancelled and which never retried decoding)
- webm stickers played too fast (frame pacing was driven by the container's declared fps instead of frame timestamps, so variable-rate or mis-probed webm ran at up to 2x)
- recyclerlistview double-tap requires same view
- list ripple left behind when the pressed row moves because another row changed height (selector was only re-synced on scroll)
- link ripples in album captions work across the entire group
- link long-tap menu no longer draws the lifted link 2dp to the left of the actual text (the scrim was being nudged onto the popup's scale pivot even when it isn't morphing into a rewritten url)
- long-tap scrims (link / date / card / username / phone menus) block chat touches while open — the lifted text is pinned to screen coords captured on open, so scrolling, swipe-to-reply or swipe-back behind it detached it from its bubble
- dead zones in list rows where a hidden clickable child kept stale bounds from a previous binding (e.g. top-right corner of a member row in profile after a tagged member was recycled)
- share sheet search results unclickable near the top (the touch dead zone under the search field double-counted the status bar inset, swallowing taps on the first row of results)
- chat list crash while flinging when RecyclerView exposes a stale child without a ViewHolder
- dialogs list pull-to-reveal-archive glitches
- inline code in dialog previews no longer inherits chat-bubble colors
- chat previews no longer persist the scroll position, so opening the chat normally afterwards still starts where you left off
- big emoji jumping around in emoji-only messages when a visible reply preview shared its spans (stock bug: per-span draw-position cache fought over by both layouts), plus oversized animated emoji in reply previews (stock bug: `cloneSpan` overwrote the resized value with the old size)
- pinned dialog reorder scrolling/glitching mid-drag in the archive (stock bug: async list diffing dispatched the move after the drag swap)
- forwards from users with hidden forward privacy: the optimistic message shows the anonymized name right away (when their profile is cached), and the server-confirmed hidden header is applied in place instead of showing the linked author until chat reopen
- shared media player visual glitches
- profile pinned-music sheet bugfixes
- shared media pager: fling mid-animation to chain tabs or reverse (was ignored until settled); at the edge tab the fling falls through to swipe-to-close
- shared media grid cells (the opened one's neighbour especially) going blank after opening a photo — the viewer hides the source cell's image receiver a frame or more after resolving it, and restores it by re-resolving by message id; if the grid recycled that cell onto another message in between, the wrong cell was blanked and the restore landed elsewhere, so it never came back. deferred hides now verify the cell still shows what they were resolved for, and a cell rebound to a different message drops any inherited hidden flag
- second copy of the photo showing over the shared media grid during the open/close transition (our own regression: stock's transition draws a list-space copy through a hole in the viewer's background, which is invisible while the main content is hidden — our keyboard fix keeps that content drawn, so the shared media provider now opts out of the second copy like ChatActivity does)
- shared media photo transition starting/landing ~2dp off (down, and right for left-column cells): the grid's place provider pre-added the cell's image offset to the window coords, which the photo viewer then adds again via `drawRegion` — every other provider, including the sibling branches in the same method, passes the raw view position
- image memory cache sized off `getMemoryClass()` while the app runs with `android:largeHeap` — the cache was ~29MB instead of ~56MB, too small to hold the shared media grid and the photo viewer's full-screen bitmaps at once, so opening any photo evicted the whole grid and every cell flashed its blurred thumbnail
- attach panel: better perf, safe close before fully open
- paid reaction animation respects litemode
- custom emoji reaction burst respects litemode (stock only gated the "around" animation of regular emoji)
- reaction counter shift during long-tap menu
- reactions silently disappearing right after being sent (stale server read race)
- channel reactions: toggling "Enable Reactions" was silently discarded on back (unsaved-changes check only compared the emoji selection, never the enabled state), and re-enabling always saved the prefilled list as an explicit set instead of "All"
- rounded section backgrounds ignored the alpha of the fading container they lived in, so they stayed fully opaque during the animation and only popped away on the next unrelated redraw (e.g. toggling "Enable Reactions" in channel reactions)
- sticky date pill jump and color shift when replacing an inline date separator
- bubble jump when ime height changes mid send-animation
- out-of-bubble panels (reply/forward/name) on custom wallpapers jumping tint when the keyboard opens (wallpaper-sampling offset flipped by the action bar height via a stale keyboard-layout conditional)
- markdown `__`/`**`/`~~`/`||` no longer parsed inside auto-detected links on send
- "regular" formatting option with mixed-span selections
- applying a style over a mixed-span selection smearing one span (e.g. mono) across the whole range
- photo viewer ui respects litemode blur
- search-as-list box respects litemode blur
- lazy face detect (only on filters tab)
- lazy chromecast init in photo viewer
- stale video seekbar leaking onto photos in photo viewer
- fix photo zoom/video progress resetting on message edit
- photo viewer no longer dismissing the keyboard / jumping at end of close animation (12.8 regression)
- fix edge-to-edge for instant view
- missing action bar title/date and open/close animation when viewing a photo of a user who hid theirs from you (stock bug: profile photo locations carried no dc id)
- text spoilers jittering/blinking while scrolling on high-refresh displays (12.8 regression)
- revealed spoilers in album captions re-hiding themselves after a scroll (reveal flag was set on the drawing cell's message instead of the group's primary one)
- black screen after rotating the screen with a chat preview open
- round video recorder cancel crash when leaving chat
- missing `Emoji.replaceEmoji` calls
- background media loading cpu usage (experimental)
- animated photo spoilers respect power-saving setting
- shared media spoiler positioning
- nav stack lockup after rapid back swipes
- click-through area to the left/right of bottom bar tabs
- profile scroll jump when opening uncached user
- stale unread badges on global-search top peers
- stale unread mention pointer after reading mention on another device (mention button jumping to old message)
- folder pins silently missing when the pinned dialog isn't in the local dialogs cache (now fetched from server)
- folder tab unread counters slowly drifting to zero (badge disappearing while the list still shows unread chats) as chats got read
- photo/video gallery performance improvements
- edits (incl. crop) survive gallery refreshes and the source file being replaced by another app while its editor is open; also fixes fresh screenshots sometimes not appearing in the attach sheet or making it flash
- messages consisting of only 2 or 3 emojis are huge in chat search results
- admin logs scroll jumping when loading events
- fix glitch when quickly dismissing photo editor after cropping
- persist crop when rotating photo in photo editor
- chat preview no longer marks visible reactions/poll votes as read
- dialogs list briefly flashing over the lockscreen after ending/declining a call
- fix camera2api a/v sync issue in round messages
- forward bar showing stale message count/senders after deselecting messages in the forward options sheet
- forwarding with captions removed no longer blanks out text-only messages in the optimistic copy (stock only checked `media != null`, but locally-sent text messages carry `messageMediaEmpty` sometimes)
- cross-peer reply: clear stale quote so a leftover quote-reply target doesn't override the new one at send
- emoji suggestion panel popping up after sending a message (late `:keyword` lookup callback ignored the input having been cleared)
- phantom empty dialog rows after peeking a non-joined channel / discuss group
- expand emoji tabs when there's enough space to fit without scrolling
- "pause music on media" now lets external players auto-resume (transient focus instead of indefinite)
- "pause music on media" now also applies to videos in the photo viewer, with transient focus so external players auto-resume (stock never requests audio focus for them)
- reply box right padding when the sender-name line is wider than the message text line
- fix lingering webpage when quickly sending
- fix sponsored message media not respecting data saver
- fix non-joined channels history getting stuck in the past
- jumping to an uncached message no longer flashes and returns to the chat bottom
- expandable switch cell (e.g. admin rights groups) counter badge overlapping the switch on long titles
- fix stuck red snapshot box when the frame capture fails/times out on enter in pip
- heads-up notifications show only the new message instead of the whole group, on the correct channel (Private Chats/Groups/Channels) instead of "Internal notifications" (visible since android 16 forced grouping)
- fix npe checking admin/owner when channel admins not yet loaded
- comments/topic thread restored as plain group chat after activity recreation
- phantom message selection after back-gesture swipe over a reply header (leaked long-press timer)
- fix deeplinking to a non-primary album member sometimes mis-anchoring
- a bunch of stock memory/resource leaks (incl. `NativeByteBuffer` wrapper pool growing unbounded on write-heavy threads, reply-line sticker emoji never detaching and pinning closed chats via the global emoji cache, story viewer input/mention observers, `BotLocation` caching an activity context)
- correctly handle "Open in..." in ChatAttachAlert: open editor before sending + support multi-attach
- avatar of a bubble right below a topic separator (forum "All" tab) only clickable in its lower part
- opt out of android media resumption, so a phantom telegram player chip no longer reappears in quick settings after a reboot or once the app process is gone
- fix profile crash when a contact's note is removed server-side while the open animation is running (note row built from stale user info, bound against fresh)
- notifications for chats read on another device no longer linger forever when the app process was killed in between (stock only tracked posted notifications in memory)
- more stale notification fixes: reaction, "scheduled message sent", story and forum-topic notifications now clear when read/seen on another device while the app is connected (stock only cleared these via FCM pushes, which aren't delivered to online sessions, or on opening the chat locally); notification refresh is no longer skipped when unread counts happen to stay equal (forums, communities, muted chats)
- unchanged notifications are no longer re-posted on every update (stock re-notifies every chat each time anything changes, making notification bridges like Mi Fitness re-forward the whole stack to the wearable)
- crash long-pressing a sticker set while off-screen rows are cached (reorder update bound null item on cached/hidden holders)
- crash cutting out a sticker after the photo editor recycled the source image mid-segmentation
- crash after transferring channel ownership (admin sort comparator overflowed on 64-bit peer ids)
- "Save to Downloads" copies uncached documents after downloading instead of requiring a second attempt
- link ripples in album captions work across the entire group
- duplicate edit-info and profile-photo actions in the standalone self-profile overflow menu
- chat drifting a few px off the input when hiding the keyboard while a message animation runs (aosp recyclerview bug: end-anchored padding delta applied twice across pre+post layout)
- crash expanding/loading more votes in poll results (sections adapter diffed against an empty hash list on the first update, re-inserting every already-laid-out item)
- crash tapping the story privacy badge on a story from a user with no first name (e.g. deleted account)
- stop spamming doomed admin-list requests (`COMMUNITY_FILTER_INVALID`) on every open of a community you're not an admin of
- crash in the forward picker inside a community when the community's info updates (stock updates an action bar avatar view that picker mode never creates)
- fix bottom progress bar on video bubbles now following inline playback (autoplay & play-with-sound)
- correctly publish album name for streamed music
- permanently white/stale message bubbles on low-memory devices (stock bug: `MessageDrawable` committed its radius/color cache keys even when the bitmap allocation for the bubble nine-patch failed, so the stale drawable was never rebuilt; the shadow nine-patch also recycled its old bitmap before allocating the new one, leaving a recycled bitmap in use)
- unread reaction/poll vote badge stuck on a chat after reading some of them on another device (stock only persisted the dialog counter once it hit zero); counter now follows every single-message read, each read is acked to the server right away; the jump-to-reaction button no longer marks everything read when its offset overshoots
- group call recording timer showing a nonsense duration (the whole unix epoch, e.g. `496691:48:15`) right after starting a recording: stock flips the local `recording` flag optimistically while `record_start_date` is still 0
