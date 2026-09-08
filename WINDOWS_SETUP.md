# Setting up the project on Windows (from scratch)

This is a step-by-step guide to setting up `entinyGram` on a clean Windows install (e.g.
after reinstalling the OS). Building/signing the APK is a separate topic — will be covered
in `BUILD.md` once Android Studio / JDK / Android SDK are set up.

## 1. Install tools

### Git

Usually already present (`git --version`). If not — https://git-scm.com/download/win.

### Bun

PowerShell (no admin needed):

```powershell
irm bun.sh/install.ps1 | iex
```

Verify: `bun --version`.

### StGit (stacked git)

Modern stgit is a Rust program; there's an official Windows `.msi` installer (no need to
install Rust/cargo yourself): https://github.com/stacked-git/stgit/releases →
`stgit-<version>.msi`.

After install, `stg.exe` lives in `C:\Program Files\StGit\bin`.

**Gotcha:** if `stg --version` fails with `error while loading shared libraries:
api-ms-win-crt-locale-l1-1-0.dll` — a fresh Windows install is missing the Visual C++
Redistributable. Install via winget:

```powershell
winget install --id Microsoft.VCRedist.2015+.x64
```

**Gotcha:** if right after installing the `.msi` your terminal still says
"stg: command not found" — the installer added `C:\Program Files\StGit\bin` to the
system PATH, but any **already open** terminal (or already-running process) won't pick
that up. Open a new terminal.

### Git identity (required for stgit)

`stg init` requires a configured commit author:

```powershell
git config --global user.name "Your Name"
git config --global user.email "your@email"
```

### Windows Developer Mode (required for symlinks)

`bun run setup` creates symlinks (`src/kotlin`, `src/res` → `worktree/...`). By default
Windows only allows creating symlinks with administrator rights. To allow a regular user
account to do this, enable Developer Mode:

**Settings → Privacy & security → For developers → Developer Mode → On.**

Without this, `bun run setup` fails with `EPERM: operation not permitted, symlink`.

## 2. Install dependencies and apply the patchset

From the repo root:

```powershell
bun install --ignore-scripts
```

`--ignore-scripts` skips a native build step (`node-gyp`, needs a Python + C++ toolchain
on Windows) for a transitive dependency (`better-sqlite3`) that nothing in this repo
actually loads at runtime — it's only ever pulled in by importing `@mtcute/node/sqlite`,
which no script here does. Safe to skip permanently, not just a temporary workaround.

```powershell
bun run setup
```

This will:
1. Clone stock Telegram into `worktree/` (branch `inugram`).
2. Initialize stgit and apply every patch from `series` (~399 patches).
3. Symlink `src/kotlin` / `src/res` into the right places under `worktree/`.
4. Generate icon drawables from `ICON_SELECTION`.

A successful run ends with `ok Setup complete`.

## 3. Verify

```powershell
bun run lint-patches
```

Prints a list of "partial overwrites" between patches (expected — later patches
intentionally overwrite earlier ones) — what matters is that the **exit code is 0** and
there are no "full reversions" (that would mean a patch fully clobbers another one —
an actual problem).

```powershell
cd worktree
stg top      # should show the last patch from series
stg series   # every patch should be marked `+` (applied)
```

## Next

- Open `worktree/` in Android Studio to work on the code.
- Building the APK (JDK 21, Android SDK, `gradlew`, signing, versionCode) is a separate
  step — will be documented in `BUILD.md` once we get there.
