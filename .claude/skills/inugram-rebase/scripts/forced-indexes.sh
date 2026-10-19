#!/usr/bin/env bash
# Checks that every `INDEXED BY` in stock (bugfix__storage-query-plans and others) names an index the
# rebased schema still creates. A missing index fails the statement at prepare time, and
# checkSQLException swallows it, so the query silently stops doing anything.
set -euo pipefail

root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
dir="$root/worktree/TMessagesProj/src/main/java/org/telegram/messenger"
storage="$dir/MessagesStorage.java"
migrations="$dir/DatabaseMigrationHelper.java"

for idx in $(rg -o --no-filename 'INDEXED BY (\w+)' -r '$1' "$root/worktree/TMessagesProj/src/main/java" | sort -u); do
  rg -q "CREATE INDEX IF NOT EXISTS $idx ON" "$storage" || echo "$idx: not created in MessagesStorage.createTables"
  created=$(rg -n "CREATE INDEX IF NOT EXISTS $idx ON" "$migrations" | tail -1 | cut -d: -f1 || true)
  dropped=$(rg -n "DROP INDEX IF EXISTS $idx\b" "$migrations" | tail -1 | cut -d: -f1 || true)
  [ -z "$created" ] && echo "$idx: never created by a migration"
  [ -n "$dropped" ] && [ "${created:-0}" -lt "$dropped" ] && echo "$idx: dropped by a migration after its last creation"
done
echo "forced indexes checked"
