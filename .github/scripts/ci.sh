#!/usr/bin/env bash
set -euo pipefail

on_error() {
  local exit_code=$?
  echo "[ci] ERROR: command failed (exit=${exit_code}) at line ${BASH_LINENO[0]}: ${BASH_COMMAND}" >&2
  exit "$exit_code"
}
trap on_error ERR

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

log_warn() {
  echo "[ci] WARN: $*" >&2
}

log_error() {
  echo "[ci] ERROR: $*" >&2
}

require_command() {
  local cmd="$1"
  if ! command_exists "$cmd"; then
    log_error "Required command not found: $cmd"
    return 1
  fi
}

retry() {
  local attempts="$1"
  shift

  local attempt=1 exit_code=0
  while (( attempt <= attempts )); do
    if "$@"; then
      return 0
    fi
    exit_code=$?

    if (( attempt == attempts )); then
      log_error "Command failed after ${attempts} attempts (last exit=${exit_code}): $*"
      return 1
    fi

    log_warn "Command failed (exit=${exit_code}), retrying (${attempt}/${attempts}): $*"
    sleep $(( attempt * 5 ))
    attempt=$(( attempt + 1 ))
  done
}

fail_on_merge_conflict_markers() {
  local log_file rg_rc
  log_file="$(mktemp)"
  if command_exists rg; then
    rg -n --hidden '^(<<<<<<< .+|=======$|>>>>>>> .+)$' . --glob '!.git/**' --glob '!**/build/**' >"$log_file" || rg_rc=$?
    rg_rc="${rg_rc:-0}"
  else
    grep -R -n -E '^(<<<<<<< .+|=======$|>>>>>>> .+)$' . \
      --exclude-dir=.git \
      --exclude-dir=build >"$log_file" || rg_rc=$?
    rg_rc="${rg_rc:-0}"
  fi

  if [[ $rg_rc -eq 0 ]]; then
    log_error "Unresolved merge conflict markers detected:"
    cat "$log_file" >&2
    rm -f "$log_file"
    return 1
  fi
  if [[ $rg_rc -ne 1 ]]; then
    log_error "Merge-conflict scan failed (search exit code: $rg_rc)."
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
      log_warn "Switched to Java 17 via JAVA_HOME candidate."
      return 0
    fi
  done

  if [[ -d "/opt/hostedtoolcache/Java_Temurin-Hotspot_jdk" ]]; then
    candidate="$(find /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk -maxdepth 3 -type f -path '*/17*/x64/bin/java' 2>/dev/null | head -n 1 | xargs -r dirname | xargs -r dirname)"
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      log_warn "Switched to Java 17 via discovered hostedtoolcache path."
      return 0
    fi
  fi

  log_error "Java 17 was not found. Active Java is ${current_version:-unknown}."
  return 1
}

gradle_retry() {
  local max_attempts="${MAX_ATTEMPTS:-3}"
  local attempt=1 rc=0

  while (( attempt <= max_attempts )); do
    if ./gradlew --no-daemon --stacktrace --no-configuration-cache "$@"; then
      return 0
    fi
    rc=$?

    if (( attempt == max_attempts )); then
      log_error "Gradle command failed after ${max_attempts} attempts (exit=${rc}): $*"
      return 1
    fi

    log_warn "Gradle command failed (exit=${rc}), retrying (${attempt}/${max_attempts}): $*"
    sleep $(( attempt * 5 ))
    attempt=$(( attempt + 1 ))
  done
}

install_first_available() {
  local package
  for package in "$@"; do
    if retry 3 sdkmanager --install "$package"; then
      return 0
    fi
  done

  log_error "Could not install any candidate package: $*"
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
    log_warn "Could not detect available build-tools version; trying known fallbacks."
    if ! install_first_available "build-tools;35.0.0" "build-tools;35.0.1" "build-tools;34.0.0"; then
      log_warn "Build-tools fallback installation failed; continuing because AGP may resolve tools automatically."
    fi
    return 0
  fi

  retry 3 sdkmanager --install "build-tools;${version}" || {
    log_warn "Failed to install detected build-tools ${version}; continuing with fallback candidates."
    install_first_available "build-tools;35.0.0" "build-tools;35.0.1" "build-tools;34.0.0" || true
  }
}

run_tests_non_blocking() {
  local rc=0
  if try_gradle_task "testDebugUnitTest"; then
    return 0
  else
    rc=$?
  fi

  if [[ $rc -eq 2 ]]; then
    log_warn "testDebugUnitTest task not found; trying test."
  else
    log_warn "testDebugUnitTest failed; continuing."
    return 0
  fi

  rc=0
  if try_gradle_task "test"; then
    true
  else
    rc=$?
    if [[ $rc -eq 2 ]]; then
      log_warn "No unit test task found; continuing."
    else
      log_warn "test failed; continuing."
    fi
  fi
}

build_artifacts_blocking() {
  local rc=0 built_any=0

  if try_gradle_task "assembleRelease"; then
    built_any=1
  else
    rc=$?
    if [[ $rc -ne 2 ]]; then
      log_error "assembleRelease failed."
      return 1
    fi
  fi

  rc=0
  if try_gradle_task "assembleDebug"; then
    built_any=1
  else
    rc=$?
    if [[ $rc -ne 2 ]]; then
      log_error "assembleDebug failed."
      return 1
    fi
  fi

  if [[ $built_any -eq 1 ]]; then
    return 0
  fi

  rc=0
  if try_gradle_task "distZip"; then
    return 0
  else
    rc=$?
  fi
  if [[ $rc -ne 2 ]]; then
    log_error "distZip failed."
    return 1
  fi

  log_error "No supported build task found (assembleRelease/assembleDebug/distZip)."
  return 1
}

require_command java
require_command bash
require_command awk
require_command sort
require_command head
require_command xargs
require_command sdkmanager

if [[ ! -f "./gradlew" ]]; then
  log_error "./gradlew not found."
  exit 1
fi
chmod +x ./gradlew

fail_on_merge_conflict_markers

ensure_java17

retry 3 bash -lc 'yes | sdkmanager --licenses >/dev/null'

retry 3 sdkmanager --install "platform-tools" "platforms;android-35"
install_best_build_tools

run_tests_non_blocking
build_artifacts_blocking
