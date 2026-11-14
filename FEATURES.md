# features

entinygram is built on top of [inugram](https://github.com/teidesu/inugram). for the base features see [inugram FEATURES.md](https://github.com/teidesu/inugram/blob/main/FEATURES.md). this page lists only what entinygram adds. most things are toggled in **settings → entinygram**.

## highlights

| area | what you get |
| --- | --- |
| [privacy & protection](#privacy--protection) | ghost mode, deleted messages and edit history kept |
| [unlocks & restrictions](#unlocks--restrictions) | copy and save from protected chats, local names and premium ui |
| [translation & ai](#translation--ai) | whole-chat translation and transcription on your own keys |
| [appearance & customization](#appearance--customization) | ios-style layout, themes, tabs, feed, player |
| [messages & sending](#messages--sending) | forward pro, round videos, bookmarks, export |
| [media, camera & player](#media-camera--player) | downloads, hd photos, camera and player tweaks |
| [chat list & folders](#chat-list--folders) | local folders, unread chats first |
| [notifications & calls](#notifications--calls) | unifiedpush, background service, call fixes |
| [proxy, plugins & performance](#proxy-plugins--performance) | plugin market, osmdroid map, smaller apk |
| [debloat & premium noise](#debloat--premium-noise) | hide gifts, premium badges, upsells and banners |

## entinygram additions

### privacy & protection

- **ghost mode** — hide read receipts, story views, online status, typing, recording and upload indicators. read-on-send, local mark-as-read, per-chat and per-chat-type overrides, quick toggle in the drawer, chats menu and launcher shortcut
- **anti-deletion & edit history** — deleted messages stay in the chat under a 🗑️ mark. deleted text, media and every edit revision go to an archive with word and caption diffs. a chat's archive opens as a read-only stock chat, deleted and edited switched from ⋮. archive has search, ttl, selective deletion and json export. attachments per network are capped and saved into one hidden folder under their original names
- **self-destructing media** — save view-once photos, videos, video notes and voice notes, keep secret-chat media and timed text playable
- **save user info** — phone country, registration date, change log of name, username, bio and photo
- **read time & remembered last seen** — when the other side read your private messages, and the last online time that survives hidden status
- **presence logger** — per-contact online and offline history with notifications
- **force relay calls** — route calls through telegram servers to hide your ip
- **adblock & content filtering** — hide sponsored messages, proxy sponsor chat and business bot banner. regex rules spoiler or hide messages, including inline buttons
- **hide last message preview** — chat list previews show under a spoiler

### unlocks & restrictions

- **device spoof** — custom device name (or random per launch), system and app version, hidden time zone and install source
- **screenshots anywhere** — disable FLAG_SECURE in protected windows, secret chats, expiring media and protected stories
- **copy from protected chats** — a separate switch brings Copy back in the message menu and text selection without allowing forwarding
- **protected content** — copy text and select messages in protected chats, save any story or auto-save viewed ones, copy and forward from protected channels with background bulk download, forward without the "Forwarded from" header, re-send saved view-once media
- **quote protected posts** into another chat as a bold title and blockquote
- **share button action** — the round button next to channel posts can forward, forward pro, save, edit, delete, translate, copy or show details
- **local unlocks** — premium ui and limits on the client, premium custom emoji without premium, local names (name, username, photo, bio, badges, emoji status) visible only to you, with export and import
- **local message edit** — full-screen editor with a live bubble preview, resettable anytime
- **official-app emulation** — purchases and premium checkout without official-app blocks
- **wallet for everyone** — the gram wallet entry is always available
- **raised limits** — pins, favorite stickers and gifs kept locally past the server cap, and folders

### translation & ai

- **whole-chat translation without premium** via google, deepl, bing, azure, mymemory or any openai-compatible llm. auto-translate per chat with exceptions
- **translate before sending** — in the send preview, as you send, and drafts from the input menu
- **llm translation with conversation context**, auto-detected endpoints, models picked from the provider list
- **ai providers as cards** — any number of gemini, openai, groq, openrouter, cloudflare or custom openai-compatible endpoints. choose which one serves chat and voice
- **ai by task** — voice-to-text, ai editor and summaries each get their own provider, model and temperature
- **ai compose & tools** — the stock ai editor (styles, translate, fix, emojis) on your own endpoint
- **free voice transcription** via groq whisper, gemini flash, openai or cloudflare ai
- **insecure endpoints opt-in** — plain-http providers (e.g. ollama on your lan) work behind an explicit toggle. secure by default

### appearance & customization

- **hide fab** — hide chat-list buttons with a separate toggle, keep your chosen actions
- **ios-style design** — compact bottom bar, compact header pill with adjustable avatar placement, ios input bar, larger avatars in the chat list, message menu as a card over a blurred chat, unread counter on the header back button - *inspired by iOs_pop_up (Gabiitha)*
- **themes & chrome** — standalone amoled, material 3 colors for monet themes, liquid-glass refraction, tint and blur strength, avatar corner radius, action button colors, chat input text size, optional font scaling for messages
- adjustable backdrop blur behind message menus - *inspired by NagramXF*
- **glass glare** — disabled, default or solid outline
- **chat headers** — live preview, choice of centered titles, auto marquee for long names
- **bottom tabs** — custom layout, optional separate search button, any tab first, hide on scroll, sliders for bar and fab height
- **profile swipe** — disable swipe-up on profile to open the account menu, long press still works
- **system folders** — administrator, bots, unread, groups and channels tabs in the folder bar, each switchable on this device
- **pill stack** — interactive status pills in the chats search bar
- **recent chats** — popup, side panel or full page with tabs, search and a history limit
- **feed** — all subscribed channels in one chat-style timeline with folder filter, newest on top, off by default
- **material 3 music player** — full-screen player with cover-based colors, wavy seek bar, queue, synced lyrics and mini player
- **online dots** — hide, color-code, or show on sender avatars in groups
- **clown avatars** — blocked users show 🤡 (or 💩 / 💀). users who blocked you are guessed from a missing photo and last seen, so it can be wrong
- **messages appearance** — hide reply background, custom reply colors, show spoilers directly, forum topics as material 3 cards, snowflakes over the chat
- **wide channel posts** — posts and albums stretch to chat width with adjustable side padding - *inspired by [exteraless](https://github.com/exteraless/exteraless)*
- **tablet mode** — force tablet layout on or off, or follow screen size
- **settings screen** — reorder and hide rows, hide the accounts list, custom my profile rows, downloads button mode, full-screen app icon picker, clock format independent of telegram language
- **side drawer** — material 3 sections with aligned icons and labels
- **menu shortcuts** — clear cache and restart in the chats menu, restart and plugin settings in the side menu
- **disable vibration** across the app, calls and notifications

### profile & identity

- **developer badges** in chat headers and profiles
- **mutual contact icon** left of the avatar
- **estimated registration date & dc** in profiles
- **cleanup** — delete profile photos (all or selected), delete your own messages in any chat
- **identity tools** — open a chat by numeric id
- **bot accounts stay in sync** — bot tokens keep receiving new messages after long offline gaps

### messages & sending

- **forward pro** — full-screen editor over your wallpaper with a real bubble preview, albums stay grouped, comment before or after the forward, formatted comment text. forward can open it from chats, shared media and global search
- **no forward limit** — "forward all messages" in the chat menu sends the whole chat or topic in batches of 100, oldest first, to any chats you pick. shared media gets "select all" in the selection menu for everything already loaded in the tab. idea and credit: [@zwylair](https://t.me/zwylair) and his NoForwardLimit plugin
- **round videos** — send from the gallery, zoom slider with presets, pinch zoom, exposure with AE lock, up to 720p with 60 fps and video bitrate up to 2 Mbps. 512p stays round, 720p is sent as regular video
- **send as sticker** — long-press send on photos to send 512 px stickers
- **confirm before sending** voice, video messages, stickers and gifs, keep screen on while recording
- **typing status spoof** — fake typing, recording or uploading in any chat
- **input actions** — link preview toggle, find & replace and attachment picker in the input menu
- **link previews** — never generate or send them, or send the preview's media straight from its menu
- **message menu** — message details and json, hide a message until you leave the chat, copy photo
- **export chat** as json or html with media, including voice and video messages
- **bookmarks** — up to 500 per chat, star next to the time, tap to jump to the message
- **silent broadcast bell** — moved out of the input into the chat menu
- **reactions** — hide them, disable setting them, hide chat-list badges, mark read instantly
- **chat menu** — privacy and local-data actions grouped under entinygram, with its own order section
- **text & input fixes** — better url parsing, separator after mention, keyboard stays up when scrolling a chat with a draft
- **long-press to close the pinned panel** — until a new pin, or always in this chat
- **no confirmation for links** — external links open without the "Open link" dialog

### media, camera & player

- **save what you see** — background downloads with progress and cancel, download button for selected files in profile media and search, "save to downloads" for voice messages, file-size badge on photos, date in saved file names
- outgoing media shows conversion percentage while processing and byte progress during upload - *inspired by NagramXTurbo (@temporaryna)*
- **photos, gifs & stickers** — hd photos at q87 without double compression, small gifs in bubbles, whole sticker or emoji pack as zip
- **square camera tile** — the attach gallery camera takes one cell
- **camera, player & gestures** — mirrored front camera, original-quality video playback, tap-to-fullscreen gifs, music sleep timer, halved swipe distances, no pull-down-to-stories

### chat list & folders

- **chat list sections** — rounded material 3 cards with adjustable side margins, separate pinned chats - *inspired by Modular Sections (@RnPlugins)*
- **local folders** once the server limit is reached, folder icon picker, the list opens on the folder you used last
- **add to folder from any folder** — the chat selection menu offers "add to folder" inside folders too, so a chat moves between folders without the All chats tab; off by default
- **unread chats first** — unread chats, muted ones too, go above read ones in every folder; pinned chats stay on top
- **tap avatar** to open the profile from the chat list
- **keep three-dot menu** — keep the chats menu button when the side menu is on

### notifications & calls

- **unifiedpush** notifications without google play
- **background service** — optional always-on connection for on-time notifications, at a battery cost
- **notification fixes** — topic settings stay scoped to their topic, in-app vibration switch takes effect
- **calls** — rotate the call screen, keep the screen on by ignoring the proximity sensor
- **audio** — fix playback stuck on pause (miui and some dsps), no preloading or auto-play of the next track or voice message

### proxy, plugins & performance

- **plugin market** — themed catalog with filters, version badges, authors and tags, a dedicated page and automatic sdk installation
- **osmdroid map view** — pure-java openstreetmap renderer replacing maplibre, about 12 MB less native code
- **faster cold start**, locale caching and a smaller apk without android auto
- **memory** — image caches freed shortly after minimize, closed chats and photo viewers no longer leak through the emoji panel
- **highest refresh rate** — keep the screen at its highest refresh rate - *inspired by Graniton*
- **system emoji** — draw emoji with the system font
- **quiet voice messages when locked** — headset, car or lock screen controls cannot start voice or round messages on a locked phone - *inspired by HushTelegram*
- **download queue** — large files download one at a time in request order, with a Downloads entry in the chats menu and cancel - *inspired by HushTelegram*
- **cache management** — stock storage chart per category, every category opens: archives, saved media browser, or a clear sheet
- **stability & ui fixes** — photo viewer close crash, topics panel flicker, interrupted theme switches recover, rtl truncation in the header
- **push notifications** in optimized builds

### debloat & premium noise

- **profile & gifts** — hide story, gift and live stream buttons, gift entries, gift service messages, the gift ring and gifts tab, similar channels and bots tab
- **premium noise** — hide premium and verified badges, stars rating, collectible styling, animated pattern icons, upsells and lock badges in chats
- **banners & prompts** — hide gift auctions, cache cleanup card, psa promo chats, similar-channels card; disable the post-call rating and permission prompts on start
- **hide channel share button**
- **hide recent and frequent chats** from the app icon long-press menu
- **hide story avatars** — tapping an avatar opens the profile instead of stories

## docs

- [release notes](changelogs/) — released changes
- [removed features](docs/removed.md) — what was dropped and why
