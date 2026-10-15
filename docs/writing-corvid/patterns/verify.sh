#!/usr/bin/env bash
# verify.sh — recompile every pattern .cxc and diff against its .expected capture.
# Non-zero exit indicates a regression.

set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CXC="${CXC:-$HERE/../../../compiler/bin/cxc}"
[ -x "$CXC" ] || { echo "cxc not executable at $CXC" >&2; exit 2; }

mkdir -p "$HERE/out"
fail=0
for f in "$HERE"/*.cxc; do
  base=$(basename "$f" .cxc)
  expected="$HERE/$base.expected"
  [ -f "$expected" ] || { echo "SKIP $base — no .expected file"; continue; }
  rm -f "$HERE/out/$base"
  "$CXC" compile "$f" "$HERE/out" > "$HERE/out/$base.compile.log" 2>&1
  if [ ! -x "$HERE/out/$base" ]; then
    echo "FAIL compile: $base"
    cat "$HERE/out/$base.compile.log" | sed 's/^/  /'
    fail=$((fail+1))
    continue
  fi
  actual=$("$HERE/out/$base" 2>&1)
  if diff <(echo "$actual") "$expected" > "$HERE/out/$base.diff" 2>&1; then
    echo "OK   $base"
  else
    echo "FAIL parity: $base"
    cat "$HERE/out/$base.diff" | sed 's/^/  /'
    fail=$((fail+1))
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "all patterns passed"
  exit 0
else
  echo "$fail pattern(s) failed"
  exit 1
fi
