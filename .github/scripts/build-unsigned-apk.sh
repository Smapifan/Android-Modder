#!/usr/bin/env bash
set -euo pipefail

echo "[unsigned-build] Building UNSIGNED release APK..." >&2
export FORCE_UNSIGNED_RELEASE=1
unset ANDROID_STORE_FILE ANDROID_STORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD

./gradlew --no-daemon --stacktrace --no-configuration-cache clean assembleRelease

unsigned_apk="$(find . -type f -path '*/build/outputs/apk/release/*-release-unsigned.apk' | head -n 1 || true)"
if [[ -z "${unsigned_apk}" ]]; then
  echo "[unsigned-build] ERROR: Unsigned release APK not found." >&2
  exit 1
fi

echo "[unsigned-build] OK: ${unsigned_apk}" >&2
