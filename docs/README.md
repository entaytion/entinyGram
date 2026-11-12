# entinygram

an independent telegram for android, built as a stgit patch stack over stock telegram.

## highlights

everything entinygram adds lives in **settings → entinygram**, grouped into pages.

- [ghost mode](../FEATURES.md#privacy--protection): hide read receipts, story views, online status, and typing indicators.
- [kept deleted messages](../FEATURES.md#privacy--protection): keep deleted messages in chat and browse deleted text, media, and edit history in an archive.
- [protected content](../FEATURES.md#unlocks--restrictions): copy, save, and forward content from protected chats, including stories and view-once media.
- [translation and ai](../FEATURES.md#translation--ai): translate chats and messages, transcribe voice, and connect your own ai providers.
- [local names and identities](../FEATURES.md#unlocks--restrictions): set names and profile details that only you see.
- [forward pro](../FEATURES.md#messages--sending): compose forwards with grouped albums, formatted comments, and a live bubble preview.
- [custom interface](../FEATURES.md#appearance--customization): tune themes, tabs, chat layout, message menus, and other parts of the app.
- [channel feed](../FEATURES.md#appearance--customization): read subscribed channels in one chat-style timeline.
- [plugin system](../FEATURES.md#proxy-plugins--performance): extend the client with plugins from the catalog or build your own with the sdk.

## docs

| guide | contents |
| --- | --- |
| [all features](../FEATURES.md) | every entinyGram feature |
| [removed features](removed.md) | what was removed and why |
| [build guide](https://entinygram.vercel.app/build) | prerequisites, setup, and signing |
| [plugin sdk](../sdk/docs/README.md) | plugin api and authoring |
| [translation](skills/translation/SKILL.md) | audit locale strings and add a language |
| [craft](skills/craft/SKILL.md) | bugfixes and small features |
| [rebase](skills/rebase/SKILL.md) | moving the patch stack to a new upstream release |
| [leaks](skills/leaks/SKILL.md) | heap dump memory leak analysis |
| [merge](skills/merge/SKILL.md) | pulling changes from inugram and rebuilding the stack |
