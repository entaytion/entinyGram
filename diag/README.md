# .entinylog diagnostic scripts

A `.entinylog` file tells the app what to record, so a bug can be chased without a new APK.
Send the file to a tester in any chat → they tap it → confirm → reproduce → **Settings → entinyGram → Additional → Diagnostics → Send logs**.
Output lands in `<date>_diag.txt` inside the logs zip (works on stable builds too). The profile switches itself off after `hours`.

```jsonc
{
  "entinylog": 1,                 // required marker
  "name": "contacts vanish",      // shown in settings
  "hours": 24,                    // auto-off, 1..336 (default 48)
  "stacks": true,                 // append call sites to zone traces (default true)
  "watch": [634949235],           // ids marked with ★ in every log line

  "categories": ["contacts"],     // built-in zones, or ["*"]
  "events":   ["contactsDidLoad", "*Contact*"],        // NotificationCenter events by name (globs)
  "requests": ["TL_contacts_*", "account.registerDevice"], // server requests + responses by TL class
  "updates":  ["TL_updatePeerSettings", "*Contact*"],   // server-pushed updates by TL class
  "dump": {
    "what": ["contacts", "user:634949235", "dialog:-100123", "push", "config:GHOST_*"],
    "on": ["activate", "start"],  // when the profile is imported / on every app start
    "every": 300                  // seconds, min 30; 0 = off
  }
}
```

Globs: `*` matches anything, case-insensitive; a `namespace.` prefix is ignored (`account.registerDevice` = `registerDevice`).
Requests/updates are printed field by field; passwords, keys, auth payloads and byte arrays are never written.
There is also a manual **Snapshot now** button that runs `dump.what` immediately.

## Zones (`categories`)

| zone | what it traces |
|---|---|
| `contacts` | list loads/swaps (who vanished), contact updates, deletes with caller, db writes, addContact result |
| `push` | UnifiedPush register / endpoint / lost / wake, provider switches |
| `feed` | loads, live new/deleted posts, read flushes, mark all read |
| `export` | history pages, failed files, stall skips, saved folder |
| `forward` | Forward Pro copies with edited text (entity types kept) |
| `ghost` | reserved |

## Dumps (`dump.what`)

`contacts` · `user:<id>` · `dialog:<id>` · `push` · `config:<glob of InuConfig names>`

A new zone = a few `DiagLog.log("zone", …)` calls in Kotlin (see `src/kotlin/helpers/diag/`). Everything else above works on any build that has this engine.
