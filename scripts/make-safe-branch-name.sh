#!/usr/bin/env bash
set -euo pipefail

# Converts an arbitrary free-text title into a short, git-safe branch name.
# Example:
#   ./scripts/make-safe-branch-name.sh "Add .codepatch support, Android app build"
#   -> codex/add-codepatch-support-android-app-build

raw="${*:-}"
if [[ -z "$raw" ]]; then
  echo "Usage: $0 <title text>" >&2
  exit 1
fi

# Lowercase
name="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"

# If available, normalize unicode accents (ä->a etc.)
if command -v iconv >/dev/null 2>&1; then
  name="$(printf '%s' "$name" | iconv -f UTF-8 -t ASCII//TRANSLIT 2>/dev/null || printf '%s' "$name")"
fi

# Replace URL escapes and separators with '-'
name="$(printf '%s' "$name" | sed -E 's/%[0-9a-f]{2}/-/g; s#[/\\]+#-#g; s/[ _.,:;|]+/-/g')"

# Keep only safe chars, collapse '-', trim edges
name="$(printf '%s' "$name" | sed -E 's/[^a-z0-9._-]+/-/g; s/-+/-/g; s/^-+//; s/-+$//')"

name="$(printf '%s' "$name" | sed -E 's/^codex-//')"

# Protect against empty names
if [[ -z "$name" ]]; then
  name="update"
fi

# Keep it reasonably short for remote tooling
max_len=48
if (( ${#name} > max_len )); then
  name="${name:0:max_len}"
  name="$(printf '%s' "$name" | sed -E 's/-+$//')"
fi

echo "codex/$name"
