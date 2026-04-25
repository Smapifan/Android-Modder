#!/usr/bin/env bash
set -euo pipefail

./gradlew --no-daemon tasks --all > .github_tasks.txt

if grep -q "assembleDebug" .github_tasks.txt; then
  echo "[ci-build] Building debug APK."
  ./.github/scripts/gradle-retry.sh assembleDebug
elif grep -q "assembleRelease" .github_tasks.txt; then
  echo "[ci-build] Building release APK."
  ./.github/scripts/gradle-retry.sh assembleRelease
elif grep -q "distZip" .github_tasks.txt; then
  echo "[ci-build] Building distZip fallback."
  ./.github/scripts/gradle-retry.sh distZip
else
  echo "[ci-build] No supported build task found." >&2
  exit 1
fi
