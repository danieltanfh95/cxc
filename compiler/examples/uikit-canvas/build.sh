#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
exec "$(dirname "$HERE")/_sim-build.sh" "$HERE" "uikit-canvas" "$@"
