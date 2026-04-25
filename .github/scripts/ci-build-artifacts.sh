#!/usr/bin/env bash
set -euo pipefail

TASK_FILE=".github_tasks.txt"
trap 'rm -f "$TASK_FILE"' EXIT

if ! ./gradlew --no-daemon -q tasks --all > "$TASK_FILE" 2>/dev/null; then
  ./gradlew --no-daemon tasks --all > "$TASK_FILE"
fi

if grep -q "assembleDebug" "$TASK_FILE"; then
  echo "[ci-build] Building debug APK."
  ./.github/scripts/gradle-retry.sh assembleDebug
elif grep -q "assembleRelease" "$TASK_FILE"; then
  echo "[ci-build] Building release APK."
  ./.github/scripts/gradle-retry.sh assembleRelease
elif grep -q "distZip" "$TASK_FILE"; then
  echo "[ci-build] Building distZip fallback."
  ./.github/scripts/gradle-retry.sh distZip
else
  echo "[ci-build] No supported build task found." >&2
  echo "[ci-build] Available tasks excerpt:" >&2
  head -n 120 "$TASK_FILE" >&2 || true
  exit 1
fi
