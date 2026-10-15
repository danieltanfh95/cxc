#!/bin/zsh
# Headless PNG capture of trace-viewer.html with the system Google Chrome.
# Appends ?sync=1 so trace-viewer runs ELK on the main thread. On the default
# async path, Chrome's --virtual-time-budget runs out before the layout
# finishes and the capture is blank. Set CHROME_BIN if Chrome lives elsewhere.
set -euo pipefail

if (( $# < 2 || $# > 3 )); then
  print -u2 "usage: $0 <trace-viewer-url> <output.png> [<layout-dump.html>]"
  print -u2 "  third arg: also dump the DOM (holds the layout-debug geometry"
  print -u2 "  blob) for layout-metrics.py"
  exit 2
fi

source_url=$1
output_path=${2:A}
dump_path=${3:-}
chrome_bin=${CHROME_BIN:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}

if [[ ! -x $chrome_bin ]]; then
  print -u2 "Google Chrome was not found at: $chrome_bin"
  print -u2 "Set CHROME_BIN to its executable path."
  exit 1
fi

if [[ $source_url == *\?* ]]; then
  render_url="${source_url}&sync=1"
else
  render_url="${source_url}?sync=1"
fi

mkdir -p "${output_path:h}"
rm -f "$output_path"
set +e
"$chrome_bin" \
  --headless=new \
  --disable-gpu \
  --no-first-run \
  --no-default-browser-check \
  --run-all-compositor-stages-before-draw \
  --window-size=1920,1200 \
  --force-device-scale-factor=2 \
  --virtual-time-budget=60000 \
  --screenshot="$output_path" \
  "$render_url"
chrome_exit=$?
set -e

if (( chrome_exit != 0 )); then
  print -u2 "Chrome exited with status $chrome_exit"
  exit $chrome_exit
fi

if [[ ! -f $output_path ]]; then
  print -u2 "Chrome did not create: $output_path"
  exit 1
fi

if ! file "$output_path" | grep -q 'PNG image data'; then
  print -u2 "Chrome output is not a PNG: $output_path"
  file "$output_path" >&2
  exit 1
fi

zmodload zsh/stat
zstat -H output_stat "$output_path"
minimum_bytes=$((200 * 1024))
if (( output_stat[size] <= minimum_bytes )); then
  print -u2 "PNG is only $output_stat[size] bytes; expected more than $minimum_bytes bytes"
  exit 1
fi

print "wrote $output_path ($output_stat[size] bytes)"
file "$output_path"

if [[ -n $dump_path ]]; then
  "$chrome_bin" \
    --headless=new \
    --disable-gpu \
    --no-first-run \
    --no-default-browser-check \
    --window-size=1920,1200 \
    --virtual-time-budget=60000 \
    --dump-dom \
    "$render_url" > "${dump_path:A}" 2>/dev/null
  print "wrote ${dump_path:A}"
fi
