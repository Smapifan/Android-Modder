#!/usr/bin/env bash
set -euo pipefail

./gradlew --no-daemon tasks --all > .github_tasks.txt

if grep -q "testDebugUnitTest" .github_tasks.txt; then
  echo "[ci-run-tests] Running Android local unit tests (testDebugUnitTest)."
  ./.github/scripts/gradle-retry.sh testDebugUnitTest
elif grep -Eq "(^|\s)test(\s|$)" .github_tasks.txt; then
  echo "[ci-run-tests] Running JVM tests (test)."
  ./.github/scripts/gradle-retry.sh test
else
  echo "[ci-run-tests] No known unit test task found." >&2
  exit 1
fi
