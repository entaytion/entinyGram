# Building the APK

Assumes you've already run through [Development](README.md#development) —
`bun install`, `bun run setup`, `worktree/` exists and `bun run lint-patches` is clean.
(Windows-specific environment setup: [WINDOWS_SETUP.md](WINDOWS_SETUP.md).)

## Requirements

- **JDK 21** (Microsoft Build of OpenJDK, Temurin, or any other distribution — CI uses
  `temurin@21`).
- **Android SDK**, matching what `TMessagesProj_App/build.gradle` pins:
  - `compileSdk 36` / `targetSdk 36`, `minSdk 26`
  - `build-tools 36.0.0`
  - `ndk 27.2.12479018`
  - `cmake 3.22.1`

The simplest way to get all of the SDK components: install Android Studio and let its
first-run "Standard" setup wizard download the default SDK, then let it prompt you to
install the exact NDK/cmake versions above the first time you open `worktree/` (it
detects the pinned versions from `build.gradle` and offers to install what's missing via
**Tools → SDK Manager** if it doesn't do so automatically).

### Gradle JDK

Android Studio's own bundled JetBrains Runtime (`jbr-21`, shown as the default `Gradle
JDK` for new projects) can sometimes crash the Gradle daemon before it even starts
(`The daemon has terminated unexpectedly on startup attempt #1`). If you hit that, point
Gradle at the real JDK 21 install instead:

**Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** → pick
your JDK 21 install (or **Add JDK from disk...** and browse to it) → **Apply** → re-sync.

## Debug build

```sh
bun run build-debug
```

Equivalent to (run from the repo root, or drop the `cd worktree &&` if you're already
there):

```sh
cd worktree
./gradlew TMessagesProj_App:assembleDebug
```

- **Signing:** already handled. The debug build type points at
  `TMessagesProj/config/release.keystore`, which is checked into the repo, with the
  matching `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`
  already set in the root `gradle.properties`. Nothing to configure locally.
- **Application ID:** the debug build type adds `applicationIdSuffix ".beta"`, so it
  installs as `ua.entaytion.entinygram.beta` — a separate app alongside any real install,
  never overwrites it.
- **Output:** `worktree/TMessagesProj_App/build/outputs/apk/debug/app.apk`.
- **versionCode:** locally this auto-increments from `worktree/.local_build_number`
  (starts at `1000000`), completely separate from the CI date-based scheme below — you
  don't need to set anything for local builds.
- **ABI:** defaults to `arm64-v8a` only (fast local builds). Set the `ABI_FILTERS` env
  var (comma-separated ABIs, or `all`) before running Gradle if you need other
  architectures, e.g. `ABI_FILTERS=all ./gradlew TMessagesProj_App:assembleDebug`.

Only `:TMessagesProj_App` is a real, buildable app module — `TMessagesProj_AppHuawei`,
`TMessagesProj_AppHockeyApp`, and `TMessagesProj_AppStandalone` still exist as
directories but aren't included in `settings.gradle` (they were retired by
`misc/remove-unused-*` patches).

## Release build

```sh
cd worktree
./gradlew TMessagesProj_App:assembleRelease
```

Same local keystore/credentials as the debug build above, but `minifyEnabled true` +
`shrinkResources true` (R8/ProGuard) and no `.beta` suffix — this is what you'd use to
sanity-check a release-shaped build locally, not what actually ships.

Output: `worktree/TMessagesProj_App/build/outputs/apk/release/app.apk`.

## How this differs from CI

`.github/workflows/apk.yml` runs the same two Gradle tasks (`--no-daemon`), but:

- `INU_BUILD` (the real `versionCode`) is computed by `scripts/ci/version.ts` from the
  `INU_DAY_STATE` GitHub variable — date-based, not the local `.local_build_number`
  counter. See the "Release Bumping" rule in `CLAUDE.md` for the exact format.
- `COMMIT_ID` is set from `github.sha` (locally it falls back to `git rev-parse --short
  HEAD` automatically, so `verName` still gets a commit suffix either way).
- `ABI_FILTERS=arm64-v8a` is set explicitly (same as the local default).
- Signing in CI may use a different keystore via the `RELEASE_KEYSTORE_PATH` env var and
  injected secrets, rather than the repo-committed dev keystore used above.

## Troubleshooting

- **`EPERM: operation not permitted, symlink`** during `bun run setup` — Windows
  Developer Mode isn't on. See [WINDOWS_SETUP.md](WINDOWS_SETUP.md).
- **Gradle daemon crashes on startup** — usually the Gradle JDK, see above.
- **`stg`/`bun`/`java` "command not found" right after installing** — PATH changes only
  apply to new terminal sessions/processes, not ones already open.
