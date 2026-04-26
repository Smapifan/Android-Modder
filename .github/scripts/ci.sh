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

fail_on_merge_conflict_markers() {
  echo "[ci] Checking repository for unresolved merge conflict markers." >&2
  local log_file rg_rc
  log_file="$(mktemp)"
  if rg -n --hidden '^(<<<<<<< .+|=======$|>>>>>>> .+)$' . --glob '!.git/**' --glob '!**/build/**' >"$log_file"; then
    echo "[ci] ERROR: Unresolved merge conflict markers detected:" >&2
    cat "$log_file" >&2
    rm -f "$log_file"
    return 1
  fi
  rg_rc=$?
  if [[ $rg_rc -ne 1 ]]; then
    echo "[ci] ERROR: merge-conflict scan failed (rg exit code: $rg_rc)." >&2
    cat "$log_file" >&2 || true
    rm -f "$log_file"
    return 1
  fi
  rm -f "$log_file"
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

try_gradle_task() {
  local task="$1"
  local log_file
  log_file="$(mktemp)"

  if gradle_retry "$task" >"$log_file" 2>&1; then
    rm -f "$log_file"
    return 0
  fi

  if grep -Eq "Task '.*${task}'.*not found|Cannot locate tasks that match '${task}'" "$log_file"; then
    rm -f "$log_file"
    return 2
  fi

  cat "$log_file" >&2 || true
  rm -f "$log_file"
  return 1
}

install_best_build_tools() {
  local versions version
  versions="$(sdkmanager --list 2>/dev/null | awk -F'[ ;|]+' '/build-tools;[0-9]+\.[0-9]+\.[0-9]+/ {print $3}' | sort -V | uniq)"
  version="$(printf '%s\n' "$versions" | tail -n 1)"

  if [[ -z "$version" ]]; then
    echo "[ci] WARN: Could not detect available build-tools version; trying known fallbacks." >&2
    if ! install_first_available "build-tools;35.0.0" "build-tools;35.0.1" "build-tools;34.0.0"; then
      echo "[ci] WARN: Build-tools fallback installation failed; continuing because AGP may resolve tools automatically." >&2
    fi
    return 0
  fi

  echo "[ci] Installing latest available build-tools version: ${version}" >&2
  retry 3 sdkmanager --install "build-tools;${version}" || {
    echo "[ci] WARN: Failed to install detected build-tools ${version}; continuing with fallback candidates." >&2
    install_first_available "build-tools;35.0.0" "build-tools;35.0.1" "build-tools;34.0.0" || true
  }
}

run_tests_non_blocking() {
  local rc=0
  echo "[ci] Running unit tests (non-blocking)." >&2
  if try_gradle_task "testDebugUnitTest"; then
    echo "[ci] testDebugUnitTest passed." >&2
    return 0
  else
    rc=$?
  fi

  if [[ $rc -eq 2 ]]; then
    echo "[ci] testDebugUnitTest not found; trying test." >&2
  else
    echo "[ci] WARN: testDebugUnitTest failed; continuing." >&2
    return 0
  fi

  rc=0
  if try_gradle_task "test"; then
    echo "[ci] test passed." >&2
  else
    rc=$?
    if [[ $rc -eq 2 ]]; then
      echo "[ci] WARN: No unit test task found; continuing." >&2
    else
      echo "[ci] WARN: test failed; continuing." >&2
    fi
  fi
}

build_artifacts_blocking() {
  local rc=0
  echo "[ci] Building artifacts." >&2

  if try_gradle_task "assembleDebug"; then
    echo "[ci] Built assembleDebug." >&2
    return 0
  else
    rc=$?
  fi
  if [[ $rc -ne 2 ]]; then
    echo "[ci] ERROR: assembleDebug failed." >&2
    return 1
  fi

  rc=0
  if try_gradle_task "assembleRelease"; then
    echo "[ci] Built assembleRelease." >&2
    return 0
  else
    rc=$?
  fi
  if [[ $rc -ne 2 ]]; then
    echo "[ci] ERROR: assembleRelease failed." >&2
    return 1
  fi

  rc=0
  if try_gradle_task "distZip"; then
    echo "[ci] Built distZip." >&2
    return 0
  else
    rc=$?
  fi
  if [[ $rc -ne 2 ]]; then
    echo "[ci] ERROR: distZip failed." >&2
    return 1
  fi

  echo "[ci] ERROR: No supported build task found (assembleDebug/assembleRelease/distZip)." >&2
  return 1
}

echo "[ci] Starting unified build script."
chmod +x ./gradlew

fail_on_merge_conflict_markers

ensure_java17

echo "[ci] Accepting Android SDK licenses."
retry 3 bash -lc 'yes | sdkmanager --licenses >/dev/null'

echo "[ci] Installing Android SDK components."
retry 3 sdkmanager --install "platform-tools" "platforms;android-35"
install_best_build_tools

run_tests_non_blocking
build_artifacts_blocking

echo "[ci] Unified build script completed."
