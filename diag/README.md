⚠️ Engine removed; format kept for a future redesign.

# .entinylog diagnostic scripts

A `.entinylog` file tells the app what to record, so a bug can be chased without a new APK.
Send the file to a tester in any chat → they tap it → confirm → reproduce → **Settings → entinyGram → Additional → Diagnostics → Send logs**.
Output lands in `<date>_diag.txt` inside the logs zip (beta builds only). The profile switches itself off after `hours`.

```jsonc
{
  "entinylog": 1,                 // required marker
  "name": "contacts vanish",      // shown in settings
  "hours": 24,                    // auto-off, 1..336 (default 48)
  "stacks": true,                 // append call sites to zone traces (default true)
  "watch": [634949235],           // ids marked with ★ in every log line

  "categories": ["contacts", "net"], // built-in zones, or ["*"]
  "events":   ["contactsDidLoad", "*Contact*"], // NotificationCenter events by name (globs)
  "requests": [
    "TL_contacts_*",
    {
      "name": "messages.sendMessage",
      "fields": ["peer", "message"],  // log only these fields
      "match": { "peer.user_id": "watch" } // match specific value or "watch"
    }
  ],
  "updates":  ["TL_updatePeerSettings", "*Contact*"],
  "dump": {
    "what": [
      "contacts",
      "user:634949235",
      "dialog:-100123",
      "push",
      "config:GHOST_*",
      "field:MessagesController.<acc>.dialogs_dict.size",
      "field:org.telegram.messenger.SharedConfig.currentProxy"
    ],
    "on": ["activate", "start"],  // when profile is imported / on app start
    "every": 300                  // seconds, min 30; 0 = off
  },
  "when": [
    {
      "category": "net",
      "contains": "ConnectingToProxy",
      "dump": ["field:org.telegram.messenger.SharedConfig.currentProxy"],
      "limit": 5,                 // max fire count (default 10)
      "cooldown": 10              // seconds between fires
    }
  ]
}
```

Globs: `*` matches anything, case-insensitive; a `namespace.` prefix is ignored (`account.registerDevice` = `registerDevice`).
Pass-through security: passwords, keys, auth tokens and byte arrays are never written.

## Zones (`categories`)

| zone | what it traces |
|---|---|
| `contacts` | list loads/swaps, contact updates, deletes with caller, db writes |
| `push` | UnifiedPush register / endpoint / lost / wake, provider switches |
| `feed` | loads, live new/deleted posts, read flushes, mark all read |
| `export` | history pages, failed files, stall skips, saved folder |
| `forward` | Forward Pro copies with edited text (entity types kept) |
| `ghost` | reserved |
| `crash` | `FileLog.e` calls and uncaught exceptions with full stack traces |
| `nav` | opening and closing `BaseFragment` screens with arguments |
| `config` | any `InuConfig` toggle change (key, old → new) |
| `net` | connection state transitions and proxy settings changes |

## State Dumps (`dump.what`)

| target | description |
|---|---|
| `contacts` | loaded count, sync flags, paranoia status, watched presence |
| `user:<id>` | contact flags, mutual contact, deleted, bot, premium flags |
| `dialog:<id>` | top message, unread count, read inbox max, folder, pinned status |
| `push` | push type, token set, distributor, gateway |
| `config:<glob>` | InuConfig items matching pattern |
| `field:<path>` | generic reflection dump (`<acc>` = `getInstance(account)`, max depth 2) |

## Triggers (`when`)

Triggers run state dumps when matching conditions occur without flooding logs.

| key | description |
|---|---|
| `event` | NotificationCenter event name or glob |
| `request` | TL request class name or glob |
| `update` | TL update class name or glob |
| `category` / `zone` | log category name (`*` for all) |
| `contains` / `text` | substring to match in the formatted log message |
| `dump` | list of dump targets (falls back to `dump.what` if omitted) |
| `limit` / `max` | maximum trigger executions (default 10) |
| `cooldown` | minimum seconds between executions |

## Safety & Resource Controls

- **File rotation**: log file rotates at 5 MB, keeping the current and one previous (`.1`) file.
- **Rate limit**: identical lines exceeding 20/s are folded into `×N` summaries.
- **Isolated writer**: background writes run on a dedicated single-threaded executor.
- **Fail-safe**: internal diagnostic exceptions are caught and swallowed, never crashing the app.
