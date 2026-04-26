#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <gradle-args...>" >&2
  exit 2
fi

bash ./.github/scripts/ci.sh gradle "$@"
