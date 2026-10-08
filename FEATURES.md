# entinyGram features

> non-exhaustive list of what this fork adds, tweaks or fixes vs stock telegram android.
> keep this updated as patches are added/removed.

most things are toggleable in `Settings → entinyGram`, with sensible opinionated defaults.

🐶 - Inugram-exclusive (as far as i know, as of writing)
📡 - entinyGram-exclusive

## entinyGram additions

our own layer on top of the inugram patchset: restricted Telegram features, privacy tools, and power-user controls.

### privacy & protection

- 📡 **ghost mode**:
  - quick toggle in drawer, chats menu and launcher shortcut, with optional status indicator - *inspired by [AyuGram](https://github.com/AyuGram)*
  - hide read receipts, story views, online status, typing, recording and upload indicators
  - **read on send**: mark messages read only when you reply
  - **mark as read locally**: clear unread badges while keeping them unread on the server
  - **go offline automatically** after sending
  - **per-chat overrides** for read receipts and typing - *inspired by Alexgram / XF*
  - **apply to**: private chats, groups and channels separately
- 📡 **anti-deletion & edit history**:
  - save messages and media deleted by the other side (🗑️); your own deletions are never kept
  - per-category controls for private chats, groups, channels and bots
  - save edit history (✏️) with formatting, media and text diff
  - custom styling for deleted messages
  - full-text search and a browsable archive of everything saved, with sender names in groups
  - cache TTL and automatic expiration
  - export the archive to a JSON file
- 📡 **self-destructing & expiring media**:
  - save view-once photos, videos, video notes and voice notes, optionally straight to the gallery when you open them
  - keep secret-chat media, text and timed messages
  - expired voice notes and round videos stay playable
- 📡 **save user info**: keep phone country, registration date and a change log of name, username, bio and photo, shown in the profile menu
- 📡 **read time**: when the other side read your private messages, shown next to the bubble time
- 📡 **last seen**: remembers the last online time, so it stays visible after a contact hides their status
- 📡 **presence logger**: per-contact online/offline history with notifications
- 📡 **force relay calls**: route calls through Telegram servers to hide your IP
- 📡 **censorship bypass**:
  - **WebSocket tunnel**: connect through Telegram Web endpoints, no third-party servers; excluded from settings backups - *inspired by [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) (@Flowseal) and [NimarkoGram](https://github.com/Ettacent/NimarkoGram)*
  - **block direct connections**: extra requests go only through your SOCKS5 proxy
- 📡 **datacenter status**: live ping for DC1–DC5 - *inspired by [Nekogram](https://github.com/Nekogram/Nekogram) / [NagramX](https://github.com/temporaryna/NagramXTurbo)*
- 📡 **adblock & content filtering**:
  - hide sponsored messages
  - hide the proxy sponsor chat
  - hide or spoiler-cover messages matching regex rules
  - optionally hide replies to filtered messages

### restricted features

- 📡 **device spoof**: choose the device name, system and app version shown in Settings → Devices, hide time zone and install source, and check what Telegram sees
- 📡 **disable FLAG_SECURE**: allow screenshots and screen recording in protected windows, secret chats, expiring media and protected stories
- 📡 **hide screenshot notifications** in secret chats
- 📡 copy text and select messages in protected chats
- 📡 **local Telegram Premium**: Premium UI and limits on the client side
- 📡 **local custom emoji**: send Premium custom emoji without Premium
- 📡 **local names**: change name, username, photo, bio, verified badge, Premium badge and emoji status of any person, bot, group or channel on your device only, with export and import
- 📡 **content protection & forward bypass**:
  - save any story to gallery, or auto-save the ones you view
  - copy and forward from protected chats and channels
  - forward as your own message, without the "Forwarded from" header
  - forward, re-send or share saved view-once media
- 📡 **whole-chat translation without Premium**
- 📡 **translation providers**: Google, DeepL, OpenAI-compatible LLM, Bing, Azure, MyMemory
- 📡 **auto-translate every chat**, with per-chat exceptions - *inspired by [OwlGram](https://github.com/OwlGramDev/OwlGram)*
- 📡 **LLM translation with conversation context**
- 📡 **input actions**: translate a draft before sending, link preview toggle, find & replace, attachment picker from the input menu
- 📡 **translate in send preview** - *inspired by [NagramX](https://github.com/temporaryna/NagramXTurbo)*
- 📡 **free voice transcription** via Groq Whisper, Gemini Flash, OpenAI or Cloudflare AI
- 📡 **official-app emulation**: purchases and premium checkout without official app blocks
- 📡 **raised limits**: pins, favorites and folders
- 📡 **skip login code countdown** - *inspired by [NiagramX](https://github.com/HSSkyBoy/NiagramX)*

### appearance & customization

- 📡 **avatar corners**: adjustable radius - *ported from [NagramXF](https://github.com/Keeperorowner/NagramXF)*
- 📡 **clown avatar for blocked users**: blocked users show 🤡 (or 💩 / 💀) instead of their photo - *ported from [NiagramX](https://github.com/HSSkyBoy/NiagramX)*
- 📡 **clown avatar for users who blocked me**: guessed from a missing photo and last seen, so it can be wrong - *ported from [NagramXF](https://github.com/Keeperorowner/NagramXF)*
- 📡 **standalone AMOLED theme** - *ported from NagramX Turbo*
- 📡 **theme switch recovery**: fixes interrupted theme transitions and stale Monet palettes
- 📡 **liquid glass controls**: adjustable refraction angle and intensity - *ported from [Nagram](https://github.com/NextAlone/Nagram)*
- 📡 **niche settings**: extra hidden options, revealed by tapping the settings header five times
- 📡 **icon packs**: Stock, Solar, VKUI and Phosphor
- 📡 **iOS-style design** - *ported from [exteraless](https://github.com/exteraless/exteraless), [Cherrygram](https://github.com/arsLan4k1390/Cherrygram) and [Nagram X Turbo](https://github.com/temporaryna/NagramXTurbo)*:
  - compact bottom navigation bar
  - compact chat header pill with adjustable avatar placement
  - option to hide the chat avatar and keep the three-dot menu
  - iOS-style input bar with optional compact mode
- 📡 **action button style**: Accent, Neutral or White - *ported from [Nagram X Turbo](https://github.com/temporaryna/NagramXTurbo) (@temporaryna)*
- 📡 **header centering** and customizable chat headers
- 📡 **compact pill & profile transition fixes**
- 📡 **auto marquee** for long titles - *inspired by auto_marquee plugin (@chestertech)*
- 📡 **Material 3 music player**: full-screen player with cover-based colors, wavy seek bar, morphing controls and a mini player - *ported from [exteraless](https://github.com/exteraless/exteraless)*
- 📡 **show spoilers directly**
- 📡 **customizable bottom tabs**, with an optional separate Search button - *button layout ported from [NagramXF](https://github.com/Keeperorowner/NagramXF)*
- 📡 **recent chats**: popup, side panel or full page with tabs, search and history limit - *inspired by [exteraless](https://github.com/exteraless/exteraless), [Aprel Gram](https://t.me/aprelmods) and [NagramXF](https://github.com/Keeperorowner/NagramXF)*
- 📡 **clear cache** and **restart app** shortcuts in the chats menu; **restart app** is also available in the side menu
- 📡 **customizable settings screen**: reorder and hide rows
- 📡 **hide accounts list** in settings
- 📡 **customizable My Profile info rows**
- 📡 **wide channel posts** with adjustable side padding
- 📡 **feed**: all subscribed channels in one native chat-style timeline with channel management, folder filter and an option to show the newest posts on top, off by default - *ported from [exteraless](https://github.com/exteraless/exteraless)*
- 📡 **color-coded online dot** - *inspired by [NagramX](https://github.com/NextAlone/NagramX)*
- 📡 **clock format**: system, 12-hour or 24-hour
- 📡 **branded first-run intro**
- 📡 **pill stack**: interactive pills in chats search bar - *ported from [exteraGram](https://github.com/exteraless/exteraless)*
- 📡 **support card**: optional way to support the developer, can be hidden
- 📡 **downloads button**: always show the downloads list button in the chats header

### profile & identity tools

- 📡 **developer badges** in chat headers and profiles
- 📡 **mutual contact icon**
- 📡 **estimated registration date & DC** in profiles
- 📡 **delete profile photos**: all or selected
- 📡 **delete my messages** in any chat
- 📡 **emoji picker** in name and surname fields
- 📡 **open by numeric ID**

### messages & media tools

- 📡 **message details & JSON view**
- 📡 **hide message**: message menu item that hides a message until you leave the chat - *inspired by exteraGram*
- 📡 **url parsing improvements**
- 📡 **AI providers as cards**: add any number of providers (Gemini, OpenAI, Groq, OpenRouter, Cloudflare or a custom OpenAI-compatible endpoint), auto-detect the endpoint, pick models from a searchable list and choose which one serves chat and voice
- 📡 **AI by task**: voice-to-text, AI editor and message summaries each get their own provider, model and temperature; AI editor opens on your own provider
- 📡 **ai compose & tools**: rewrite, translate and format drafts
- 📡 **typing status spoof**: fake "typing…", "recording…" or "uploading…" in any chat
- 📡 **background downloads** - *ported from [MaxExteraPlugins](https://github.com/MaxExteraPlugins)*
- 📡 **small GIFs** in chat bubbles - *ported from [Nagram](https://github.com/NextAlone/Nagram)*
- 📡 **send as round video** from the gallery
- 📡 **round recorder**: zoom buttons, exposure lock and EV buttons - *exposure lock inspired by [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)*
- 📡 **60 FPS round video messages**
- 📡 **proxy management**: delete failed, ping all, save without connecting
- 📡 **tap avatar to open profile** from the chat list
- 📡 **confirm before sending** voice/video messages, stickers and GIFs
- 📡 **faster cold start** and locale caching
- 📡 **smaller APK**
- 📡 **Forward Pro**: edit text or captions before forwarding, keep albums grouped - *inspired by Turbotel / NagramX*
- 📡 **instant mark reactions as read** - *inspired by [exteraless](https://github.com/exteraless/exteraless)*
- 📡 **local folders** once the server limit is reached
- 📡 **remember last folder**: the chat list opens on the folder you used last, not on All Chats
- 📡 **folder icon picker**
- 📡 **separator after mention** - *inspired by [exteraGram](https://github.com/exteraSquad/exteraGram)*
- 📡 **export chat** as JSON or HTML
- 📡 **UnifiedPush** notifications without Google Play - *ported from [Forkgram](https://github.com/forkgram/TelegramAndroid) & [Mercurygram](https://github.com/mercurygram/mercurygram)*
- 📡 **background service**: optional always-on connection so notifications arrive on time, at the cost of battery - *inspired by [exteraGram](https://github.com/exteraSquad/exteraGram)*
- 📡 **fix stuck playback on pause** (MIUI and certain DSPs)
- 📡 **fix in-app vibration setting ignored**: turning in-app notification vibration on or off now takes effect
- 📡 **disable all vibration**: haptics, in-app, call and notification vibration off, whatever Telegram's own settings say
- 📡 **rotate the call screen** instead of locking it to portrait
- 📡 **cache management**: per-category size overview; clear deleted messages, edit history, removed reactions, saved media, presence logs, feed, recent chats, temp files and logs one by one or all at once
- 📡 **media size badge**: optional file size label on photos
- 📡 **square camera tile**: the attach gallery camera takes one cell instead of two
- 📡 **save selected files**: download button when selecting files in profile media and search - *ported from the Save Selected Files plugin by @akresik*
- 📡 **free memory in background**: clears image caches shortly after the app is minimized
- 📡 **keep keyboard while typing**: scrolling the chat doesn't hide the keyboard when there's a draft
- 📡 **keep three-dot menu**: option to keep the chats list menu button when the side menu is on
- 📡 **keep screen on while recording**: the screen doesn't turn off during a voice or video message

### debloat & premium noise

- 📡 **profile & gifts**:
  - hide Story / Gift / Live Stream buttons, gift entries in menus, and the gift button in channels
  - hide Premium badge, collectible status styling, Stars rating and organization verification badge
  - hide the peer-color profile background and animated pattern icons
  - hide the gift ring around the avatar and the Gifts tab in shared media
  - hide the Similar Channels/Bots tab
  - hide the group emoji-pack hint
- 📡 **chat gifts & upsells**:
  - hide the gift button in the input field
  - hide gift service messages and giveaways
  - hide Premium upsells and lock badges
- 📡 **chat list & prompts**:
  - hide the gift-auctions banner and the cache-cleanup card
  - hide PSA promo chats
  - hide the similar-channels card after joining a channel
  - disable the post-call rating prompt
  - disable contacts and lockscreen permission prompts on start
- 📡 **hide channel share button**

the sections below contain the broader feature set: inugram functionality, features adapted from other telegram clients, bug fixes, and entinygram additions in their original context.

## inuGram additions

### appearance & general

- navigation drawer, like in older Telegram versions; can be used alongside or instead of the bottom tabs
  - 🐶 an option to (ab)use predictive back to open the drawer
- monet (material you) theme support - *based on [NagramX](https://github.com/risin42/NagramX)*, 🐶 improved. plus a quick switcher in appearance settings (light/dark/amoled/auto)
- 🐶 classic ui mode for folders bar, shared media tabs, global search and chat elements (reverts the >12.6 "liquid glass" look)
- icon replacement (solar pack by [480 Design](https://t.me/Design480) - *ported from [NagramX](https://github.com/risin42/NagramX)*; vkui pack by [VK](https://github.com/VKCOM/icons) - *ported from [Catogram](https://github.com/Catogram/Catogram)*)
- 16 concept launcher icons (Inu tribute, Radar, Constellation, Orbit, Avatar, Nothing, Mono, Sunset, Amethyst, Aurora, Google ’26, Lunar, Math Book, Jungle, Gold, Dish) in the app icon picker, free for everyone
- notification icon: Telegram (default) or entinyGram
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
  - sliders
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
- 🐶 deeplink / username / cached peer ID quick-open from global search
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
- hide pinned panel (📡 long-press close: until a new pin, or always in this chat)
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
- 🐶 tap the photo/video in the bot "What can this bot do?" card to open it in the photo viewer
- 🐶 "Preview" in the link long-tap menu: peek a t.me message link (public or private) as a chat preview at that exact message
- message details from menu (+ show json)
- per-message statistics from message menu
- remove single message's file from cache from the message menu
- "Repeat" in message menu - re-send the same message to the same chat
- "Save to Downloads" for stickers & custom emoji (both in chat and in egs/sheets)
- customizable message context menu - reorder and hide items + long-tap forward/reply items + quick actions row (*ported from [NagramX](https://github.com/risin42/NagramX)*)
- wide channel posts - channel posts stretch to the full available width instead of a narrow auto-sized bubble, with a live before/after preview in settings (*ported from [exteraless](https://github.com/exteraless/exteraless)*)
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
- 📡 chat input text size slider (12-24, default 18)
- 🐶 voice recorder moved into attachments drawer
- 🐶 camera placement in the attach panel: instant (stock), static, floating button or bottom tab. the last two free up the grid cell
- 🐶 custom formatting popup with a separate code block button
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
- account limit raised to 16 (premium gating disabled); a slowdown warning is shown when adding more than 8
- 🐶 customizable account order
- 🐶 launcher shortcut that asks which account to open
- 📡 option to hide recent/frequent chats from the app icon's long-press menu
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
- 🐶 keep previous chats in the back stack when opening another chat
- 🐶 disable cloud drafts upload
- 🐶 disable wallpaper parallax
- 🐶 disable scroll-snap in profile
- 🐶 reduce profile motion (skip various enter animations, disable avatar scale-on-scroll effect)
- 🐶 prefer "Media" tab in profile over Gifts/Posts
- 🐶 recyclerlistview instant-tap
- open bottom-tab menus early by swiping up; flat highlight (not ripple) on menu hover
- faster downloads/uploads
- auto-disable the configured proxy while a VPN is active
- batch import proxy links from clipboard (one per line)
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

- channel update queue no longer replays the same update indefinitely after an earlier queued update advances PTS
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
- expired once-view/self-destruct media now reliably shows the "Expired video/photo" placeholder instead of sometimes getting stuck showing live media (stock bug: the refresh triggered when `SecretMediaViewer` closes checked the media fields directly, which aren't cleared yet at that point — only a separate `forceExpired` flag is)
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
- background media loading cpu usage
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
- crash rendering tables in rich messages and instant view with oversized/negative col/rowspans or a row wider than the first (stock sized the grid from raw spans and the first row only)
- hashtags, mentions, custom emoji and other entities missing from rich message previews in the chat list (stock preview formatter dropped every rich text type it did not list)
- blank white area where the keyboard was when opening another screen from a chat with the keyboard up (leftover adjustResize-era filler drawn over the edge-to-edge chat)
- opening/closing a screen with the keyboard up no longer waits up to 250ms for the keyboard to hide first (adjustResize-era delay, pointless with edge-to-edge)
- avatar long-press preview stuck on blurred placeholder after the cached profile photo reference expired (stock used the often-null `UserFull.user` and a parent that cannot refresh profile photo references)
- fix bottom progress bar on video bubbles now following inline playback (autoplay & play-with-sound)
- correctly publish album name for streamed music
- permanently white/stale message bubbles on low-memory devices (stock bug: `MessageDrawable` committed its radius/color cache keys even when the bitmap allocation for the bubble nine-patch failed, so the stale drawable was never rebuilt; the shadow nine-patch also recycled its old bitmap before allocating the new one, leaving a recycled bitmap in use)
- group call recording timer showing a nonsense duration (the whole unix epoch, e.g. `496691:48:15`) right after starting a recording: stock flips the local `recording` flag optimistically while `record_start_date` is still 0
