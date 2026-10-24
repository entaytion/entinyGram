#!/usr/bin/env bash
# Build (once) + run the HeapProbe OSGi bundle inside Eclipse MAT.
#
# Usage:
#   MAT=<mat-root> ./build_and_run.sh <dump.ec.hprof> <command> [args...]
#
# MAT root: macOS = MemoryAnalyzer.app, Windows = the folder holding MemoryAnalyzerc.exe.
#   macOS:   MAT=/Applications/MemoryAnalyzer.app ./build_and_run.sh dump.ec.hprof counts org.telegram.ui.ChatActivity
#   Windows: MAT="$HOME/Downloads/mat/mat" ./build_and_run.sh dump.ec.hprof counts org.telegram.ui.ChatActivity
#
# Examples:
#   ./build_and_run.sh dump.ec.hprof tally org.telegram.ui.ChatActivity ChatBackgroundDrawable,LaunchActivity
#   ./build_and_run.sh dump.ec.hprof paths 'org.telegram.ui.Components.Paint.Views.LPhotoPaintView$2'
#   ./build_and_run.sh dump.ec.hprof observers org.telegram.ui.Adapters.MentionsAdapter
#
# On Git Bash a $-containing class name must be single-quoted so the shell doesn't eat it.
# Java is NOT available inside the agent sandbox: run this with the sandbox disabled.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) IS_WIN=1 ;;
  *) IS_WIN=0 ;;
esac

winpath() { if [ "$IS_WIN" = 1 ]; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

if [ -z "${MAT:-}" ]; then
  for candidate in /Applications/MemoryAnalyzer.app "$HOME/Downloads/mat/mat" "$HOME/mat/mat"; do
    if [ -e "$candidate" ]; then MAT="$candidate"; break; fi
  done
fi
if [ -z "${MAT:-}" ]; then
  echo "MAT not found: pass MAT=<MemoryAnalyzer.app | folder with MemoryAnalyzerc.exe>" >&2
  exit 2
fi

if [ -d "$MAT/Contents/Eclipse" ]; then ECLIPSE="$MAT/Contents/Eclipse"; else ECLIPSE="$MAT"; fi
PLUGINS="$ECLIPSE/plugins"
BUNDLES="$ECLIPSE/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
JAR="$PLUGINS/heapprobe_1.0.0.jar"
if [ "$IS_WIN" = 1 ]; then LAUNCHER="$ECLIPSE/MemoryAnalyzerc.exe"; else LAUNCHER="$MAT/Contents/MacOS/MemoryAnalyzer"; fi

if [ $# -lt 2 ]; then
  echo "usage: build_and_run.sh <dump> <command> [args...]"; exit 2
fi
DUMP="$1"; shift

# Kill stray MAT processes first. A leftover instance from a prior run holds the
# equinox '-clean' config lock, and the next invocation silently blocks instead
# of erroring. This is the #1 "it's stuck".
if [ "$IS_WIN" = 1 ]; then
  taskkill //F //IM MemoryAnalyzerc.exe >/dev/null 2>&1 || true
  taskkill //F //IM MemoryAnalyzer.exe >/dev/null 2>&1 || true
else
  pkill -f "MemoryAnalyzer.*-application" 2>/dev/null || true
fi

# (Re)build the jar if missing or the source changed. Windows needs ';' between
# classpath entries and native paths, so the POSIX list goes through cygpath.
if [ ! -f "$JAR" ] || [ "$HERE/HeapProbeApp.java" -nt "$JAR" ]; then
  echo ">> building heapprobe bundle"
  CP_POSIX="$(find "$PLUGINS" -name '*.jar' | tr '\n' ':')"
  CP="${CP_POSIX%:}"
  if [ "$IS_WIN" = 1 ]; then CP="$(cygpath -w -p "$CP")"; fi
  rm -rf "$HERE/bin"; mkdir -p "$HERE/bin"
  javac -cp "$CP" -d "$(winpath "$HERE/bin")" "$(winpath "$HERE/HeapProbeApp.java")"
  cp "$HERE/plugin.xml" "$HERE/bin/"
  ( cd "$HERE/bin" && jar cfm "$(winpath "$JAR")" "$(winpath "$HERE/META-INF/MANIFEST.MF")" HeapProbeApp*.class plugin.xml )
  echo ">> installed $JAR"
fi

# Register in bundles.info once (idempotent). Back it up the first time.
if ! grep -q '^heapprobe,' "$BUNDLES"; then
  cp "$BUNDLES" "$BUNDLES.bak"
  echo 'heapprobe,1.0.0,plugins/heapprobe_1.0.0.jar,4,true' >> "$BUNDLES"
  echo ">> registered heapprobe in bundles.info"
fi

exec "$LAUNCHER" -consolelog -nosplash -clean \
  -application heapprobe.app "$(winpath "$DUMP")" "$@"
