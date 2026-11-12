---
name: settings
description: Add, move, or retire an entinyGram settings toggle or page. Covers InuConfig items, Schemas.kt sections, SearchRegistry slugs and legacy aliases, strings in all locales, and the backup rules. Use when a setting is added, moved between pages, renamed, or removed.
---

# settings

## the pieces

| piece | where | role |
| --- | --- | --- |
| config item | `src/fork/InuConfig.kt` | the stored value: `BoolItem`, `IntItem`, `StringItem`, `StringSetItem` |
| page schema | `src/fork/ui/settings/Schemas.kt` | the layout DSL: `schema(slug, titleRes, iconRes) { section(...) { toggle(...) } }` |
| settings page | `*SettingsActivity` extending `SettingsPageActivity` | pages that are not schema-driven |
| search and deeplinks | `src/fork/SearchRegistry.kt` | `pages` list, `entries`, slugs, `LEGACY_ENTRY_SLUGS`, `LEGACY_PAGE_SLUGS` |
| strings | `src/res/values*/strings_inu.xml` | `Inu*` titles, `*Info` subtitles |

## add a toggle

1. declare the item in `InuConfig`:

```kotlin
@JvmField val HIDE_STORIES = BoolItem("hide_stories", false)
```

2. add it to a `section` in `Schemas.kt`:

```kotlin
toggle(InuConfig.HIDE_STORIES, R.string.InuHideStories, R.string.InuHideStoriesInfo)
```

3. add the strings to `values/strings_inu.xml` and the other locales (english fallback is allowed).
4. default off: with the toggle off, no fork logic may change ui or network.
5. read it from java as `InuConfig.HIDE_STORIES.getValue()`, never `.value`.

## move a setting

- the page slug stays. a moved setting keeps its entry slug.
- if a page slug disappears, add it to `LEGACY_PAGE_SLUGS` with the page it now lives on, so old `tg://entinySettings/<slug>` links still open.
- a retired entry slug goes into `LEGACY_ENTRY_SLUGS` the same way.

## rules

| rule | why |
| --- | --- |
| slugs are unique and never renamed | deeplinks and changelog links use them |
| no duplicate toggle when inugram has one | use theirs (`InuConfig` origin check) |
| `exportable = false` for run-records | a backup must not re-arm a one-shot migration |
| backup import takes only registered items | remove an item, and a backup key for it is ignored |
| broken values reset to default | `ConfigSanitizer` checks types on load |
| no description subtitles on root pages | the root stays minimal: icon and title only |

## remove a setting

1. remove the item from `InuConfig` and its row from `Schemas.kt`.
2. remove its strings from every locale (`bun run tsx assets/scripts/entinychecker.ts` reports dead strings).
3. if the slug was public, keep a `LEGACY_*` alias.
4. record the removal in `assets/CLEANUP.md` with the reason.
5. remove the matching `FEATURES.md` row and changelog line when the feature goes.

## check

- `bun run tsx assets/scripts/entinychecker.ts` passes (duplicate slugs, dead strings, aapt2-safe resources).
- `bun run tsx assets/scripts/check-translations.ts` passes.
- the setting is found by the settings search.
