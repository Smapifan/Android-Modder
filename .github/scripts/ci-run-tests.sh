#!/usr/bin/env bash
set -euo pipefail

TASK_FILE=".github_tasks.txt"
trap 'rm -f "$TASK_FILE"' EXIT

# Use -q for cleaner output; fallback without -q for Gradle setups that hide tasks with quiet output.
if ! ./gradlew --no-daemon -q tasks --all > "$TASK_FILE" 2>/dev/null; then
  ./gradlew --no-daemon tasks --all > "$TASK_FILE"
fi

if grep -q "testDebugUnitTest" "$TASK_FILE"; then
  echo "[ci-run-tests] Running Android local unit tests (testDebugUnitTest)."
  ./.github/scripts/gradle-retry.sh testDebugUnitTest
elif grep -Eq '(^|[[:space:]])test([[:space:]]|$)' "$TASK_FILE"; then
  echo "[ci-run-tests] Running JVM tests (test)."
  ./.github/scripts/gradle-retry.sh test
else
  echo "[ci-run-tests] No known unit test task found." >&2
  echo "[ci-run-tests] Available tasks excerpt:" >&2
  head -n 120 "$TASK_FILE" >&2 || true
  exit 1
fi
