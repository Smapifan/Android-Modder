#!/usr/bin/env bash
set -euo pipefail

retry() {
  local attempts="$1"
  shift

  local attempt=1
  while (( attempt <= attempts )); do
    echo "[ci-setup-android] Attempt ${attempt}/${attempts}: $*" >&2
    if "$@"; then
      return 0
    fi

    if (( attempt == attempts )); then
      return 1
    fi

    sleep $(( attempt * 5 ))
    attempt=$(( attempt + 1 ))
  done
}

install_first_available() {
  local package=""
  for package in "$@"; do
    if retry 3 sdkmanager --install "$package"; then
      echo "[ci-setup-android] Installed package: $package" >&2
      return 0
    fi
    echo "[ci-setup-android] Failed package candidate: $package" >&2
  done

  echo "[ci-setup-android] No package candidate could be installed: $*" >&2
  return 1
}

echo "[ci-setup-android] Accepting SDK licenses." >&2
retry 3 bash -lc 'yes | sdkmanager --licenses >/dev/null'

echo "[ci-setup-android] Installing baseline Android packages." >&2
retry 3 sdkmanager --install "platform-tools" "platforms;android-35"

echo "[ci-setup-android] Installing build-tools with fallback versions." >&2
install_first_available "build-tools;35.0.0" "build-tools;35.0.1" "build-tools;34.0.0"
