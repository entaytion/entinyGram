# removed features

what entinygram used to have and no longer ships, and why. one row per feature, rows are not rewritten.

## removed

| feature | what it did | why it is gone |
| --- | --- | --- |
| dc status page | showed the state and ping of every telegram datacenter | rarely used and hard to find. the code is deleted: `DatacenterStatusActivity`, `DatacenterPing`, `TelegramDc`, `Obfuscated2` |
| old config migrations | imported legacy keys for ghost mode, self-destruct, regex filter, translation language and feed | old keys are no longer read. `ConfigSanitizer` resets a broken value to its default on load. only recent migrations stay: archive scope, dialogs fab, center title |
| own icon packs | entinygram's own phosphor and vkui icon sets | upstream `.icons` packs from inugram are used instead |
| download queue | large files downloaded one at a time in request order, with a downloads list in the chats menu and cancel | the author does not use it and some of its parts caused download problems. the file reference and download patches it touched were rolled back to the upstream versions |
| keep downloads in background | downloads kept running when you scrolled away or left the chat, with an optional wake lock while downloading | the author does not use it and some of its parts caused problems with downloads. the wake lock option only worked under it and went with it |
| custom edited and deleted marks | your own text for the "edited" and "deleted" labels on messages | too narrow for the core app. it lives in the [custom marks](https://github.com/entinyGram/plugins/tree/main/plugins/features/custom-marks) plugin instead |
| drawer material 3 sections | grouped side drawer rows into rounded cards | inugram shipped its own material 3 drawer redesign, kept as the base after the merge so the two styles do not stack |
| raise fab buttons | a toggle that lifted the chat-list buttons a few dp | it had no effect: the code that read it was never called |
| offset for bottom bar | a toggle for how far the chat-list buttons sit above the bottom bar | it had no effect: the code that read it was never called. `tg://entinySettings/dialogs-fab-offset-for-bottom-bar` now opens the hide on scroll row |
| thicken bold text | a fonts toggle that faked a bold weight for custom fonts without one | it only touched non-variable fonts lacking a bold face and needed a restart, so it did almost nothing. the fake bold stays always on. `tg://entinySettings/thicken-bold-text` now opens the fonts page |

## how to add a row

- name the feature the way the settings page did.
- say what it did in one short phrase.
- give the reason in a form that is fine to show publicly.
