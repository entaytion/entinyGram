---
name: please-fix-entiny
description: >
  Use when hunting a UI/behavior bug in entinyGram that shows up on the user's
  device or in a screenshot/settings export ("баг", "зникає", "з'їхав", "глюк",
  "please fix entiny"). Cheap, evidence-first workflow: diff configs, read
  adb data instead of guessing, log only the anomaly, fix one line.
---

# please fix entiny

Goal: find the root cause with the fewest tokens. Measure first, read code second, edit last.
Project rules (AGENTS.md) still apply: no commits/pushes unasked, no local gradle, comments are `// entiny:` one line, logs use `android.util.Log.d`.

## 1. Narrow with what the user already gave you
- **Settings export** (`*.entiny-settings.json`): diff against the user's own export before reading any code.
  ```bash
  python -c "import json;a=json.load(open(A))['values'];b=json.load(open(B))['values'];[print(k,a.get(k),b.get(k)) for k in sorted(set(a)|set(b)) if a.get(k)!=b.get(k) and 'menu' not in k and 'rows' not in k and 'pill_stack' not in k]"
  ```
  A key missing from the export means "default" — check `InuConfig.kt` for the real default before calling it a difference (e.g. `chat_input_text_size` default is 18, so a missing key is not "smaller").
- Reproduces for the user but not for the reporter (or vice versa) → the bug is behind a config combination. Bisect toggles with the user: flip ONE, re-screenshot. A single toggle that flips the bug (here `ios_button_placement`) cuts the search space to that code path.
- Ask the user ONE concrete question at a time; don't write long option lists.

## 2. Measure on the device (the user allows adb when asked; never install/launch/push)
Git Bash on Windows: `export MSYS_NO_PATHCONV=1` or `/sdcard/...` gets mangled.
- Screenshot: `adb exec-out screencap -p > $TEMP/s.png`, then Read it.
- Main-window view tree: `adb shell dumpsys activity top > $TEMP/top.txt`, grep for the class (`ChatAvatarContainer`, `ActionBar{`). Bounds are in px; custom views show real class names.
- Views in a **separate window** (story viewer, bottom sheets) are NOT in `dumpsys activity top`. Use `uiautomator dump /sdcard/ui.xml`, `adb exec-out cat`, then parse bounds with a small python regex (parent views may be omitted).
- Check `dumpsys activity activities | grep topResumed` first — the screen may have changed since the user said "open".

## 3. Prove the cause with a targeted log, not by reading 50 files
When static reading stalls after ~2 files, add ONE temporary `Log.d("entiny<Thing>", ...)` that prints the exact numbers in question, **only on the anomaly** or throttled (700 ms). Examples that found real bugs:
- pill missing → log only when `inu_glassTitleRight - inu_glassTitleLeft < min`.
- hint shifted → log `getLeft, scrollX, translationX, scaleX, pivotX, bounds` from `EditTextBoldCursor.drawHint`.
Then: user builds + reproduces, you run `adb logcat -d -s entiny<Thing>`. Numbers beat theories (the log showed `tx=-82`, which no amount of reading would have).
Remove every temp log before the patch.

## 4. Read code the cheap way
- `rg` the exact symbol, read 30–60 lines around it. Never read 10k+ line files top to bottom (ChatActivity, ChatMessageCell, PhotoViewer…).
- Reused stock views: the same class is shared by many screens (stories reuse `ChatActivityEnterView`; the header pill is `ActionBar.dispatchDraw`). Stock code can assume layout that our fork changes — the usual root cause is **a stock offset that assumes the stock margin** (here stock `setHorizontalPadding` shifts the field by `dp(40)` assuming the emoji-button margin).
- Intermittent "sometimes missing" draw → look for `saveLayer`/layers/cached display lists in the draw path before suspecting layout.
- Rule 18: if unsure it's ours, check whether stock behaves the same.

## 5. Fix + hand off
- Smallest inline fix in the stock file (1–3 lines) or a Kotlin helper call. Default off = stock-identical.
- Don't build or commit. Tell the user exactly what to check on the device and what it means if it fails.
- Report: cause (with the number/log that proved it), the one-line change, what remains untested, list of temp logs still in the tree.
- Several unrelated findings → separate commits (one feature per commit); do not bundle.

## Token savers (checklist)
1. Config diff before code. 2. One toggle flip before one log. 3. Don't re-screenshot the same state. 4. Don't spawn an "optimize everything" agent for a bug hunt (a delegated agent does not run on a bigger model than you; check `get_session.model`). 5. State uncertainty plainly instead of guessing a patch, then ask for the one measurement that decides it.
