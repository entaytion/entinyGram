# removed features

what entinygram used to have and no longer ships, and why. one row per feature, rows are not rewritten.

## removed

| feature | what it did | why it is gone |
| --- | --- | --- |
| dc status page | showed the state and ping of every telegram datacenter | rarely used and hard to find. the code is deleted: `DatacenterStatusActivity`, `DatacenterPing`, `TelegramDc`, `Obfuscated2` |
| old config migrations | imported legacy keys for ghost mode, self-destruct, regex filter, translation language and feed | old keys are no longer read. `ConfigSanitizer` resets a broken value to its default on load. only recent migrations stay: archive scope, dialogs fab, center title |
| censorship bypass and websocket tunnel | routed the connection through a websocket tunnel with a leak guard | removed until it can come back with better proxy support |
| own icon packs | entinygram's own phosphor and vkui icon sets | upstream `.icons` packs from inugram are used instead |

## how to add a row

- name the feature the way the settings page did.
- say what it did in one short phrase.
- give the reason in a form that is fine to show publicly.
