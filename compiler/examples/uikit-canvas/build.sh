#!/usr/bin/env bash
# uikit-canvas/build.sh — compile the Corvid source + UIKit shim for
# the iOS Simulator and (optionally) boot it. Idempotent: safe to re-
# run. Uses cxc compile's directory mode so the co-located libuikit.m
# gets auto-discovered and co-compiled.
#
# Usage:
#   ./build.sh                       # compile only
#   ./build.sh --boot                # compile, install, launch
#   ./build.sh --boot --device UDID  # pick a specific simulator

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$HERE/build"
APP_NAME="uikit-canvas"

mkdir -p "$OUT_DIR"

export CXC_NO_DAEMON=1
cxc compile "$HERE" "$OUT_DIR" --target=ios-sim

APP_PATH="$OUT_DIR/${APP_NAME}.app"
if [[ ! -d "$APP_PATH" ]]; then
    echo "build.sh: expected $APP_PATH to exist" >&2
    exit 1
fi

echo "built: $APP_PATH"

BOOT=0
DEVICE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --boot) BOOT=1; shift ;;
        --device) DEVICE="$2"; shift 2 ;;
        *) echo "build.sh: unknown flag: $1" >&2; exit 2 ;;
    esac
done

if [[ $BOOT -eq 1 ]]; then
    if [[ -z "$DEVICE" ]]; then
        DEVICE=$(xcrun simctl list devices available \
                 | grep -E 'iPhone' | head -1 \
                 | sed -E 's/.*\(([-A-F0-9]+)\).*/\1/')
    fi
    if [[ -z "$DEVICE" ]]; then
        echo "build.sh: no available iOS Simulator device. Download a runtime via Xcode Settings → Platforms." >&2
        exit 3
    fi
    echo "booting device: $DEVICE"
    xcrun simctl boot "$DEVICE" 2>/dev/null || true
    xcrun simctl install "$DEVICE" "$APP_PATH"
    BUNDLE_ID=$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' \
                "$APP_PATH/Info.plist")
    open -a Simulator
    xcrun simctl launch --console-pty "$DEVICE" "$BUNDLE_ID"
fi
