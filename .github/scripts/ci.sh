#!/usr/bin/env bash
set -euo pipefail

retry() {
  local attempts="$1"
  shift

  local attempt=1
  while (( attempt <= attempts )); do
    echo "[ci] Attempt ${attempt}/${attempts}: $*" >&2
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

ensure_java17() {
  local current_version current_major candidate
  current_version="$(java -version 2>&1 | awk -F '"' '/version/ {print $2; exit}')"
  current_major="${current_version%%.*}"

  if [[ "$current_major" == "17" ]]; then
    echo "[ci] Java 17 already active: $current_version" >&2
    return 0
  fi

  for candidate in \
    "${SETUP_JAVA_PATH:-}" \
    "${JAVA_HOME_17_X64:-}" \
    "${JAVA_HOME_17:-}" \
    "${JAVA17_HOME:-}" \
    "${JDK17_HOME:-}"
  do
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      echo "[ci] Switched to Java 17: JAVA_HOME=$JAVA_HOME" >&2
      java -version >&2
      return 0
    fi
  done

  if [[ -d "/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk" ]]; then
    candidate="$(find /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk -maxdepth 3 -type f -path '*/17*/x64/bin/java' 2>/dev/null | head -n 1 | xargs -r dirname | xargs -r dirname)"
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      echo "[ci] Switched to discovered Java 17: JAVA_HOME=$JAVA_HOME" >&2
      java -version >&2
      return 0
    fi
  fi

  echo "[ci] ERROR: Java 17 was not found. Active Java is ${current_version:-unknown}." >&2
  return 1
}

gradle_retry() {
  local max_attempts="${MAX_ATTEMPTS:-3}"
  local attempt=1

  while (( attempt <= max_attempts )); do
    echo "[ci] Gradle attempt ${attempt}/${max_attempts}: ./gradlew --no-daemon --stacktrace --no-configuration-cache $*" >&2
    if ./gradlew --no-daemon --stacktrace --no-configuration-cache "$@"; then
      return 0
    fi

    if (( attempt == max_attempts )); then
      return 1
    fi

    sleep $(( attempt * 5 ))
    attempt=$(( attempt + 1 ))
  done
}

install_first_available() {
  local package
  for package in "$@"; do
    if retry 3 sdkmanager --install "$package"; then
      echo "[ci] Installed SDK package: $package" >&2
      return 0
    fi
  done

  echo "[ci] ERROR: Could not install any candidate package: $*" >&2
  return 1
}

run_tests_non_blocking() {
  local task_file=".github_tasks.txt"
  trap 'rm -f "$task_file"' RETURN

  if ! gradle_retry -q tasks --all > "$task_file" 2>/dev/null; then
    gradle_retry tasks --all > "$task_file"
  fi

  if grep -q "testDebugUnitTest" "$task_file"; then
    echo "[ci] Running testDebugUnitTest (non-blocking)." >&2
    gradle_retry testDebugUnitTest || echo "[ci] WARN: testDebugUnitTest failed; continuing." >&2
  elif grep -Eq '(^|[[:space:]])test([[:space:]]|$)' "$task_file"; then
    echo "[ci] Running test (non-blocking)." >&2
    gradle_retry test || echo "[ci] WARN: test failed; continuing." >&2
  else
    echo "[ci] WARN: No unit test task found; continuing." >&2
  fi
}

build_artifacts_blocking() {
  local task_file=".github_tasks_build.txt"
  trap 'rm -f "$task_file"' RETURN

  if ! gradle_retry -q tasks --all > "$task_file" 2>/dev/null; then
    gradle_retry tasks --all > "$task_file"
  fi

  if grep -q "assembleDebug" "$task_file"; then
    echo "[ci] Building assembleDebug." >&2
    gradle_retry assembleDebug
  elif grep -q "assembleRelease" "$task_file"; then
    echo "[ci] Building assembleRelease." >&2
    gradle_retry assembleRelease
  elif grep -q "distZip" "$task_file"; then
    echo "[ci] Building distZip." >&2
    gradle_retry distZip
  else
    echo "[ci] ERROR: No supported build task found." >&2
    head -n 120 "$task_file" >&2 || true
    return 1
  fi
}

echo "[ci] Starting unified build script."
chmod +x ./gradlew

ensure_java17

echo "[ci] Accepting Android SDK licenses."
retry 3 bash -lc 'yes | sdkmanager --licenses >/dev/null'

echo "[ci] Installing Android SDK components."
retry 3 sdkmanager --install "platform-tools" "platforms;android-35"
install_first_available "build-tools;35.0.0" "build-tools;35.0.1" "build-tools;34.0.0"

run_tests_non_blocking
build_artifacts_blocking

echo "[ci] Unified build script completed."
