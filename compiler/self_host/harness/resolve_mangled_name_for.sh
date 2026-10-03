#!/usr/bin/env bash
# Parity harness for the resolve::mangled-name-for port.
#
# Each corpus line is `<module>|<name>`. The pipe delimiter is used
# instead of tab because bash `read -r a b` with `IFS=$'\t'` collapses
# a leading tab, which turns `\tDuration` into `a=Duration b=""`
# rather than `a="" b=Duration`. Pipe is not a whitespace char so
# `IFS='|' read` preserves the empty leading field correctly. Empty
# module field stands in for Clojure nil.

set -euo pipefail

# Chapter 4 (2026-10-04): kill any stale cxc-daemon before this harness
# builds. The daemon caches loaded source and does not auto-reload on
# compiler-side edits; see memory corvid-stale-daemon-bites-parity.
pkill -f cxc.daemon >/dev/null 2>&1 || true

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF_HOST="$(cd "$HERE/.." && pwd)"
COMPILER_DIR="$(cd "$SELF_HOST/.." && pwd)"

CORPUS="$SELF_HOST/corpus/resolve_mangled_name_for.txt"
SRC="$SELF_HOST/src/resolve_mangled_name_for.cxc"
ORACLE_CLJ="$SELF_HOST/oracle/resolve_mangled_name_for.clj"

WORK="$(mktemp -d)"
CXC_OUT_DIR="$WORK/cxc_build"
CXC_OUT="$WORK/cxc_out.txt"
ORACLE_OUT="$WORK/oracle_out.txt"

echo "[harness] building cxc port"
"$COMPILER_DIR/bin/cxc" compile "$SRC" "$CXC_OUT_DIR" >/dev/null
BIN="$CXC_OUT_DIR/resolve_mangled_name_for"
if [ ! -x "$BIN" ]; then
  echo "[harness] cxc build produced no executable at $BIN" >&2
  exit 1
fi

echo "[harness] running cxc port on corpus"
: > "$CXC_OUT"
while IFS='|' read -r mod nm || [ -n "$mod" ]; do
  "$BIN" "$mod" "$nm" >> "$CXC_OUT"
done < "$CORPUS"

echo "[harness] running clj oracle on corpus"
(cd "$COMPILER_DIR" && clj -M -e "$(cat "$ORACLE_CLJ")") < "$CORPUS" > "$ORACLE_OUT"

echo "[harness] diffing"
if diff -q "$CXC_OUT" "$ORACLE_OUT"; then
  echo "[harness] PARITY OK"
  rm -rf "$WORK"
  exit 0
else
  echo "[harness] PARITY FAIL. outputs preserved at $WORK"
  diff "$CXC_OUT" "$ORACLE_OUT" | head -40 || true
  exit 2
fi
