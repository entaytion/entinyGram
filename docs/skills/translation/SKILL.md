---
name: translation
description: Audit and update entinyGram locale files (strings_inu.xml) and add a new language. Use when asked to check, sync or translate strings, or to add a locale.
---

# translation

guide for auditing and updating entinygram's `strings_inu.xml` locale files.

## audit a locale

```sh
bun run check-translations <iso>
```

one line per problem, exit code 1 when anything is reported.

| result | action |
| --- | --- |
| missing | add the key from the base locale and translate its value |
| same as en | translate values that still match the english base |
| extra | remove keys that no longer exist in the base |
| placeholders | make `%s` and `%d` match the base |
| broken | fix unbalanced `<string>` tags |

run the check for a specific locale, for example `uk` or `ru`, or without arguments for all. skip version-qualified resource folders that do not contain `strings_inu.xml`.

## translation rules

- keep the base key order where practical.
- preserve placeholders and formatting exactly: `%1$s`, `%1$d`, `%s`, `\n`, `**bold**`, `[link](url)`, and escaped quotes.
- keep plural keys such as `_one`, `_few`, `_many`, and `_other` consistent with the target language.
- translate accurately and idiomatically, using standard spelling and grammar for the target locale; preserve the source tone and established terminology.

## add a locale

1. add `src/res/values-<iso>/strings_inu.xml` with the full set of base keys.
2. register the file in `forkSyncFiles` in `assets/scripts/config.ts`.
3. add the locale to `localeFilters` in `TMessagesProj_App/build.gradle` through `entiny__build-configuration`.
4. run `bun run setup` to create the resource symlink in `worktree/`.
5. run the locale audit above and resolve its results.

use android's locale qualifier format: `values-ja` for a language, or `values-zh-rCN` for a language and region.
