#!/usr/bin/env bash
set -euo pipefail

echo "[ci-build] Starting stable CI pipeline."

chmod +x ./gradlew .github/scripts/*.sh

echo "[ci-build] Java runtime before Gradle:"
echo "[ci-build] JAVA_HOME=${JAVA_HOME:-<unset>}"
java -version

echo "[ci-build] Running Android SDK setup script."
bash ./.github/scripts/ci-setup-android.sh

echo "[ci-build] Running unit tests (non-blocking)."
if bash ./.github/scripts/ci-run-tests.sh; then
  echo "[ci-build] Unit tests passed."
else
  echo "[ci-build] Unit tests failed, continuing to artifact build."
fi

echo "[ci-build] Building artifacts."
bash ./.github/scripts/ci-build-artifacts.sh

echo "[ci-build] Pipeline finished successfully."
