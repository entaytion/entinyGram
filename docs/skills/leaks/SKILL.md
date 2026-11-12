---
name: leaks
description: Find likely memory leak roots in Java/Android heap dumps (.hprof, Eclipse MAT .ec.hprof). Use for hprof, heapdump, memory dump or OOM artifacts, MAT leak suspects and histograms, tracing retained objects to GC roots, matching heap evidence to source lifecycle cleanup, or proposing a leak fix.
---

# explore hprof leaks

target: $ARGUMENTS

## workflow

1. **inputs.** find the `.hprof` or `.ec.hprof`, existing MAT zips (`*_Leak_Suspects.zip`, `*_Top_Components.zip`, older `*_Top_Consumers.zip`), MAT install path, repro notes, build version.
2. **convert if needed.** android hprofs are not MAT-compatible. convert with the SDK's `hprof-conv`:
   - macOS: `~/Library/Android/sdk/platform-tools/hprof-conv`
   - Windows: `%LOCALAPPDATA%\Android\Sdk\platform-tools\hprof-conv.exe` (Git Bash: `$LOCALAPPDATA/Android/Sdk/platform-tools/hprof-conv.exe`)
   - output name: `<name>.ec.hprof`. skip if the input is already `.ec.hprof`.
3. **first look.** `docs/scripts/leaks/extract_mat_reports.py <dump>`. it reuses existing zips or runs MAT. gives histogram and top consumers.
4. **read** system overview, top consumers, histogram, thread overview, leak suspects.
5. **no suspect?** keep going. abnormal instance counts and retained cohorts matter more than the verdict.
6. **pick suspects by lifecycle.** activities, fragments, views and adapters should not accumulate. caches and controllers may legitimately hold data.
7. **trace to GC roots** with `docs/scripts/leaks/heapprobe/` (see `references/heapprobe.md`). exclude weak, soft and phantom refs. record the first strong owner that should have been cleared.
8. **map anonymous classes** to source (see below).
9. **check add/remove symmetry:** observers, listeners, callbacks, delegates, runnables, animators, adapters, static maps, ThreadLocals, native-backed drawables and bitmaps.
10. **name the root cause only when source lifecycle explains the heap path.** if unproven, say which dump or test would decide it.

## MAT usage

```bash
python3 /path/to/entinyGram/docs/scripts/leaks/extract_mat_reports.py private/my-oom.ec.hprof
```

if reports are missing and MAT is installed:

```bash
# macOS
python3 /path/to/entinyGram/docs/scripts/leaks/extract_mat_reports.py dump.ec.hprof --mat /Applications/MemoryAnalyzer.app --xmx 8g
# Windows (Git Bash): --mat points at the folder with MemoryAnalyzerc.exe
python /path/to/entinyGram/docs/scripts/leaks/extract_mat_reports.py dump.ec.hprof --mat "$HOME/Downloads/mat/mat" --xmx 6g
```

| note | detail |
| --- | --- |
| headless parser | macOS `ParseHeapDump.sh`; Windows `ParseHeapDump.bat` or `MemoryAnalyzerc.exe` |
| memory | default `MemoryAnalyzer.ini` has `-Xmx1024m`. 300 MB+ dumps need 6-8 GB |
| Git Bash | do not wrap the `.bat` in `cmd //c`. call `MemoryAnalyzerc.exe` with plain args |
| report names | `<dump-base>_Leak_Suspects.zip` and `_Top_Components.zip` (or `_Top_Consumers.zip`) |
| sandbox | run outside the sandbox. java is not available inside it |

## operational gotchas

| symptom | cause and fix |
| --- | --- |
| parse hangs: near-zero CPU, ~40 MB RSS, no output | a stray MAT process holds the equinox config lock. find it (`ps aux \| rg MemoryAnalyzer` or `tasklist \| rg -i memoryanalyzer`), kill it (`kill -9` or `taskkill //F //IM MemoryAnalyzerc.exe`), retry. `build_and_run.sh` does this |
| `SnapshotException: Heap dump record ... not a supported record type` at a tiny offset (exit 13) | vendor or android dumps can carry a nonstandard leading record. check the header with a hex dump. if standard records follow, rerun with `-DhprofStrictnessWarning=true` (the extractor already passes it) |
| `hprof-conv` prints `ERROR: read N of M bytes` | the dump was truncated. nothing to salvage. ask for a fresh full capture |
| HTML Leak Suspects or Details page crashes (AIOOBE in `MultiplePath2GCRootsQuery`) | skip the renderer. use `docs/scripts/leaks/heapprobe/` queries directly |

## deep queries: heapprobe

once reports point at a cohort, prove retention with `docs/scripts/leaks/heapprobe/build_and_run.sh`, a small MAT plugin. pass `MAT=<root>`; it detects the layout on macOS and Windows.

| command | use |
| --- | --- |
| `tally` | first-strong-holder distribution. often the key one |
| `paths` | strong GC-root paths with weak refs excluded |
| `inspect` | class hierarchy, GC-root type and outbound refs for a few instances |
| `root` | explains one address from a path |
| `observers` | how many instances are still in `NotificationCenter` lists, and on which event ids |
| `counts`, `histogram`, `inbound` | basic counts and references |

feel free to extend `HeapProbeApp.java`. the script recompiles on change.

## multiply-held cohorts

if a leaked class is a top-level dominator (immediate dominator is `<ROOT>`), it has several independent strong paths. cutting one frees nothing.

- enumerate with `heapprobe tally <class> <candidate,holders>`. fix in impact order.
- the named suspect's own retained size can be small while the mass sits behind a multiply-held child. trace to the accumulation point and list its external pins.
- one dangling observer, animator or watcher can be the only root of an orphaned subtree. `observers` confirms add/remove asymmetry.

## anonymous classes

`OuterClass$18` in MAT:

1. inspect `this$0` (outer instance) and `val$...` (captured locals) in MAT first. they often reveal the owner.
2. with `.class` files: `javap -classpath <classes-root> -v -p 'org.telegram.ui.ChatActivity$18'`. read `EnclosingMethod`, `InnerClasses`, fields, and constructor `putfield` order.
3. with only APK or DEX: `jadx -d /tmp/jadx <apk>` or `baksmali d <classes.dex> -o /tmp/smali`. look for `this$0`, `val$` and `EnclosingMethod`.
4. no bytecode: `rg -n 'new .+\(.*\) \{' worktree/TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java`. ordinal counting is a fallback only.
5. then find what registers it (`addObserver`, `setDelegate`, `addListener`, `postDelayed`, static collection) and where the unregister or cancel belongs.

## leak rules

| rule | why |
| --- | --- |
| top dominator is not always the root | it can be a downstream object of the real root |
| biggest object can be valid cache | a small observer can retain a huge UI graph |
| tiny thread locals | probably not a thread leak |
| native explosions (`Paint`, `DirectByteBuffer`, `sun.misc.Cleaner`) | often follow leaked views. fix the root first |
| `byte[]`, `Object[]`, `ArrayList`, `HashMap` | containers. find the domain owner |
| `WeakHashMap` with a value that captures its key | the entry pins its own key and never evicts |
| count after navigation | one activity may be fine. dozens or hundreds is a leak |
| fix shape | usually a missing `removeObserver`, `removeListener`, `onDestroy`, `detach`, `cancelRunOnUIThread`, `removeDelegate`, or adapter cleanup |
| do not clear useful caches | unless the path proves the cache is wrong for the lifecycle |
| confirm the path is strong | MAT's suspects can run through a weak ref (e.g. `ObjectAnimator.mTarget`). re-check with `heapprobe paths` |

## known telegram leak classes

| class | root and fix |
| --- | --- |
| `ChatAvatarContainer` | observer removal belongs in `onDestroy` or detach. clear parent refs and detach drawables |
| `ChatAttachAlert` | `onDestroy` missed `mentionContainer.getAdapter().onDestroy()`. the adapter stayed in `NotificationCenter` and pinned the chat |
| `MentionsAdapter` (recurs) | registers 4 observers in the constructor, removes them only in `onDestroy()`. the owner must call it. fix at the owner's terminal teardown, not in `onDetachedFromWindow` (that breaks reattach) |
| PhotoViewer caption editor | did not call the adapter's `onDestroy()`. `destroyPhotoViewer()` pinned the whole photo viewer and 100 MB+ of GL buffers |
| `EditTextEmoji` | global `emojiLoaded` observer added in the constructor, some owners never call `onDestroy()`. attach or detach lifecycle is safer |
| `LaunchActivity` | static delegate and `WeakHashMap` value captures the key. remove the delegate in `onDestroy`. `chatSwitchedForum` observer was not removed in `onFinish()` |
| `ChatBackgroundDrawable` | inverted `attachedViews.contains()` guard meant views were never removed. the background's global observers leaked one `ChatActivity` per closed chat |
| `ReactionsEffectOverlay` | removal was draw-loop driven with two static slots. orphaned overlays stayed in `DecorView`. needed a fragment-keyed sweep on `onFragmentDestroy` |
| paint editor (`LPhotoPaintView`) | `PhotoViewer.paintKeyboardAnimator` pins `renderView` via `ObjectAnimator.mTarget`. the blur3 `ViewPositionWatcher` pins its view through a `WeakHashMap` whose value captures the key. cancel the animator and unsubscribe at paint teardown |

example dump: `139 x ChatActivity`, `17 x ChatAttachAlertPhotoLayout`, `135k x MediaController$PhotoEntry`, `170k x Paint`, `537k x sun.misc.Cleaner`. the photo layout was downstream of the leaked chat. cut the roots first.

## report

| field | content |
| --- | --- |
| heap signals | suspicious counts, retained sizes, MAT verdict |
| root path | GC root -> owner chain -> leaked cohort |
| source explanation | exact add or create site, missing cleanup site |
| confidence | confirmed, likely or speculative |
| fix shape | smallest lifecycle cleanup. avoid broad nulling unless it cuts the proven path |
| verification | a fresh dump should show the suspect counts drop. compare before and after |
