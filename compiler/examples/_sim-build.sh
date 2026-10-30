#!/usr/bin/env bash
# _sim-build.sh — shared iOS Simulator build driver for the UIKit
# samples (ios-hello, uikit-canvas, uikit-dots). Each sample's
# build.sh is a one-line wrapper that forwards its directory and
# the rest of the argv.
#
# Usage (from a sample wrapper):
#   exec "$(dirname "$HERE")/_sim-build.sh" "$HERE" "<app-name>" "$@"
#
# Flags accepted after the two positional args:
#   --boot                compile, install, launch in sim
#   --device <UDID>       pick a specific simulator
# Flags are parsed before compile; unknown flags fail fast.

set -euo pipefail

if [[ $# -lt 2 ]]; then
    echo "_sim-build.sh: usage: _sim-build.sh <sample-dir> <app-name> [--boot] [--device UDID]" >&2
    exit 2
fi

SAMPLE_DIR="$1"; shift
APP_NAME="$1";   shift

BOOT=0
DEVICE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --boot)   BOOT=1; shift ;;
        --device)
            if [[ $# -lt 2 || -z "$2" ]]; then
                echo "_sim-build.sh: --device needs a UDID" >&2
                exit 2
            fi
            DEVICE="$2"; shift 2 ;;
        *) echo "_sim-build.sh: unknown flag: $1" >&2; exit 2 ;;
    esac
done

OUT_DIR="$SAMPLE_DIR/build"
mkdir -p "$OUT_DIR"

export CXC_NO_DAEMON=1
cxc compile "$SAMPLE_DIR" "$OUT_DIR" --target=ios-sim

APP_PATH="$OUT_DIR/${APP_NAME}.app"
if [[ ! -d "$APP_PATH" ]]; then
    echo "_sim-build.sh: expected $APP_PATH to exist" >&2
    exit 1
fi
echo "built: $APP_PATH"

if [[ $BOOT -eq 0 ]]; then
    exit 0
fi

if [[ -z "$DEVICE" ]]; then
    DEVICE=$(xcrun simctl list devices available \
             | grep -E 'iPhone' | head -1 \
             | sed -E 's/.*\(([-A-F0-9]+)\).*/\1/')
fi
if [[ -z "$DEVICE" ]]; then
    echo "_sim-build.sh: no iOS Simulator available. Install a runtime via Xcode." >&2
    exit 3
fi

BUNDLE_ID=$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP_PATH/Info.plist")

echo "booting device: $DEVICE"
xcrun simctl boot "$DEVICE" 2>/dev/null || true
xcrun simctl terminate "$DEVICE" "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl install "$DEVICE" "$APP_PATH"
open -a Simulator
xcrun simctl launch --console-pty "$DEVICE" "$BUNDLE_ID"
