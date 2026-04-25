#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <gradle-args...>" >&2
  exit 2
fi

max_attempts="${MAX_ATTEMPTS:-3}"
attempt=1

while (( attempt <= max_attempts )); do
  echo "[gradle-retry] Attempt ${attempt}/${max_attempts}: ./gradlew --no-daemon --stacktrace $*"
  if ./gradlew --no-daemon --stacktrace "$@"; then
    echo "[gradle-retry] Success on attempt ${attempt}."
    exit 0
  fi

  if (( attempt == max_attempts )); then
    echo "[gradle-retry] Failed after ${max_attempts} attempts." >&2
    exit 1
  fi

  sleep_seconds=$(( attempt * 5 ))
  echo "[gradle-retry] Retrying in ${sleep_seconds}s..."
  sleep "$sleep_seconds"
  attempt=$(( attempt + 1 ))
done
