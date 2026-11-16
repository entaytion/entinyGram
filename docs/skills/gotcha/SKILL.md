---
name: gotcha
description: Known traps in entinyGram, each with how it bites and what to do instead. Read before adding anything that starts a download, before touching the stock file loader classes, before diagnosing disk usage or a tester report, and before building on this windows machine. Add a new entry whenever the same mistake costs time twice.
---

# gotcha

traps that already cost real time. each entry: the trap, why it bites, what to do instead. entries marked `[INFERENCE]` were not proven.

## how to add an entry

- one trap per entry, written so a stranger recognises the situation before it happens.
- say how it bites (the symptom someone will actually see) and what to do instead.
- keep facts that were measured apart from guesses.
- do not write what was done or who did it. that belongs in the commit message.

## 1. a hook that downloads on its own

**trap.** a hook on every incoming message calls `FileLoader.loadFile` so something is "already there when needed" (arrival prefetch for the deleted-message archive was exactly this).

**how it bites.**

- it runs for every user, not only those who want the feature: the toggle it hung on (`ARCHIVE_MEDIA_ENABLED`) was on by default.
- it ignores everything stock uses to decide whether to download: per-network presets, per-type size limits, chat kind, contacts or not. testers saw apks from channels download "regardless of size" with auto-download switched off.
- traffic and storage grow without any user action, so it looks like a leak, not a feature.
- a second patch can make it worse. `keep-downloads-in-background` got an exception so scrolling away did not cancel these downloads either.

**instead.**

- a hook that starts a download is a behaviour change: toggle, **default off**.
- pass `DownloadController.canDownloadMedia(messageObject)`. do not write your own size cap and do not read only a preset `enabled` flag.
- a half gate looks like a fix and is not one: `getCurrentDownloadMask() == 0` skips only when auto-download is off everywhere. with one type enabled the mask is non-zero and everything up to the cap downloads again.
- if the right gate makes the feature almost inert, that is the honest result. do not widen the gate to save the feature.
- before writing any auto-start, search who else already starts downloads: `rg -n "loadFile\(" src/fork patches`.

## 2. patching the stock file loader for a fork feature

**trap.** `FileLoader`, `FileLoadOperation`, `FileRefController`, `FileStreamLoadOperation`, `DownloadController` and the loader queues are shared by every media path. a fork feature needs "just a small guard" in one of them.

**how it bites.**

- the guard breaks paths far from the feature: stuck photos until restart, downloads retrying in a loop, error toasts for media that no longer exists.
- each patch calls for the next one. three edits of one patch in three days (`file-ref-stale-cache-stall`) was the sign, not progress.
- fixes written without reproducing the stall cannot be told apart from guesses.

**instead.**

- do not touch these classes unless the stall is reproduced on a debug build first.
- a third edit of the same patch means stop and reproduce, not keep patching.
- prefer a fork-side change that runs before or after stock over a change inside stock.
- do not extend an inugram `bugfix__*` patch under an `entiny__` name. a changed copy makes the next upstream merge conflict. if a real fix is needed, add a separate small patch.
- when an inugram patch is wrong for us, compare with the version in `f452a5c3` (the 13.2.1 stack) before rewriting it.

## 3. the missing gigabytes

**trap.** android shows 13.64 gb for the app, the in-app storage screen shows 1 gb, so "the app stores too much" is assumed and a cache cleaner is written.

**what was measured on the phone.**

| source | size |
| --- | --- |
| `/sdcard/Android/data/<pkg>` | 1.7 gb, 595 mb of it a leftover `LumineGram` folder |
| `/sdcard/Download/entinyGram` | 56 mb |
| `dumpsys diskstats` for the package | about 2.7 gb, a cached snapshot |
| private `/data/data/<pkg>` (debug build) | 254 mb |
| free space on `/data` | 78.9 gb before a reinstall, 90.8 gb right after, data intact |

**how it bites.**

- the 12 gb vanished when the process was killed. the mechanism that fits is **deleted files still held open by the process**. `du` and the storage screen cannot see them. `[INFERENCE]` likely holders are `.temp` files of cancelled or restarted downloads.
- no file names were ever captured, so the holder is still unknown.

**instead.**

- measure with the open-file list, on a debug build, after hours of use (right after install it is empty):

```bash
adb shell "run-as ua.entaytion.entinygram sh -c 'ls -l /proc/$(pidof ua.entaytion.entinygram)/fd | grep -i deleted'"
```

- compare free space on `/data` before and after a force stop. a jump without any file removed means open deleted files.
- do not trust `diskstats` for current numbers, it is a snapshot that refreshes rarely.

## 4. reading private data from a release build

**trap.** "install shizuku and look" or "just `adb shell du` the data folder".

**how it bites.**

- shizuku gives the same rights as `adb shell`, and `adb shell` cannot read `/data/data/<pkg>` (permissions and selinux). `run-as` answers "package not debuggable" on a release build.
- installing a debug build over a release one works only with the same package, the same signing key and a `versionCode` that is not lower. a downgrade or a different key wipes the data you wanted to measure.

**instead.**

- install the debug build with `adb install -r` over the same signing key, version code not lower, then use `run-as`.
- or have the app report its own private sizes. the in-app cache screen counts only some folders today.
- a rooted phone is the other way, with `su -c`.

## 5. "it used to work" from a tester

**trap.** a tester says the last build was fine, so the last commit is blamed, or the other way round.

**how it bites.** the tester may be on the private channel build, which is a different build from the public one. a fix shipped in one build then looks like it did nothing. one tester reported "still downloads" while his build did not contain the fix `[INFERENCE]`.

**instead.** ask in this order:

1. the version from the app info screen (`13.2.2-<hash>`). the hash is a worktree commit, `git -C worktree log` maps it.
2. which toggles are on, with a screenshot, or the `.entiny-settings.json` export, whose author is known.
3. what exactly downloads, from which chat kind, how large.
4. whether it stops when "save attachments" is turned off.

a settings export posted in a chat may belong to someone else. do not reason from it as the reporter's config.

## 6. building on this windows machine

**trap.** `bun run gradle`, `bash ./gradlew` from powershell, or a release build with the default version.

**how it bites.**

- `bun run gradle` calls `gradlew.bat`, which `worktree/` does not have. it fails at once.
- `bash` in powershell resolves to wsl, which has no distribution here, and exits with a wsl help text.
- the release build takes about 9 minutes, with r8 on unless `INU_BUILD_TYPE=beta`.
- the release `versionCode` is `YYYYMMDD * 100 + counter * 10`. a hand-picked code such as `2026111501` is lower than `2026111510`, so the in-app updater and `adb install -r` can treat it as a downgrade.

**instead.**

```powershell
$env:INU_BUILD = "<versionCode>"
$env:ABI_FILTERS = "arm64-v8a"
Set-Location worktree
& "C:\Program Files\Git\bin\bash.exe" ./gradlew TMessagesProj_App:assembleRelease --console=plain
```

- the apk lands in `TMessagesProj_App/build/outputs/apk/release/app.apk`. check it with `aapt2 dump badging` and `apksigner verify --print-certs` instead of installing blind.
- file name for hand-built betas: `entinygram-beta-arm64-<base>-<versionCode>.apk`.
- a local build runs only when the user asks for it.

## 7. changing the stack and the tree together

**trap.** reverting fork code (`src/fork`) while the patch stack in `worktree/` still calls it, or the other way round.

**how it bites.** the tree does not compile: patches keep calling helper functions that were deleted. nothing warns until a build.

**instead.**

- search the patches for every symbol before deleting it from `src/fork`: `rg -n "symbol" patches`.
- remove the patch, the hook, the config item, the settings row, the strings in every locale and the `FEATURES.md` line in the same change.
- `stg delete` on a patch in the middle needs a push of everything above it. check for conflicts and stop if one appears.
- `bun run export` rewrites many unrelated patch files with context offsets. that is noise, not a mistake.
- a removed feature gets a row in `docs/removed.md`, written so it can be shown publicly.
