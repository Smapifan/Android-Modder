#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <gradle-args...>" >&2
  exit 2
fi

ensure_java17() {
  local current_version current_major
  current_version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
  current_major="${current_version%%.*}"

  if [[ "$current_major" == "17" ]]; then
    return 0
  fi

  local candidate=""
  local -a candidates=(
    "${JAVA_HOME_17_X64:-}"
    "${JAVA_HOME_17:-}"
    "${JAVA17_HOME:-}"
    "${JDK17_HOME:-}"
  )

  for candidate in "${candidates[@]}"; do
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      echo "[gradle-retry] Switched to Java 17 at JAVA_HOME=$JAVA_HOME" >&2
      java -version >&2
      return 0
    fi
  done

  if [[ -d "/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk" ]]; then
    candidate="$(find /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk -maxdepth 3 -type f -path '*/17*/x64/bin/java' 2>/dev/null | head -n 1 | xargs -r dirname | xargs -r dirname)"
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      echo "[gradle-retry] Switched to discovered Java 17 at JAVA_HOME=$JAVA_HOME" >&2
      java -version >&2
      return 0
    fi
  fi

  echo "[gradle-retry] Warning: Java ${current_version:-unknown} detected and no Java 17 installation was found in known locations." >&2
}

ensure_java17

max_attempts="${MAX_ATTEMPTS:-3}"
attempt=1

while (( attempt <= max_attempts )); do
  echo "[gradle-retry] Attempt ${attempt}/${max_attempts}: ./gradlew --no-daemon --stacktrace --no-configuration-cache $*" >&2
  if ./gradlew --no-daemon --stacktrace --no-configuration-cache "$@"; then
    echo "[gradle-retry] Success on attempt ${attempt}." >&2
    exit 0
  fi

  if (( attempt == max_attempts )); then
    echo "[gradle-retry] Failed after ${max_attempts} attempts." >&2
    exit 1
  fi

  sleep_seconds=$(( attempt * 5 ))
  echo "[gradle-retry] Retrying in ${sleep_seconds}s..." >&2
  sleep "$sleep_seconds"
  attempt=$(( attempt + 1 ))
done
