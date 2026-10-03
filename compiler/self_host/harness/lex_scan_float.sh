#!/usr/bin/env bash
# Parity harness for lex::scan-number (decimal int + float scope).

set -euo pipefail

pkill -f cxc.daemon >/dev/null 2>&1 || true

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF_HOST="$(cd "$HERE/.." && pwd)"
COMPILER_DIR="$(cd "$SELF_HOST/.." && pwd)"

CORPUS="$SELF_HOST/corpus/lex_scan_float.txt"
SRC="$SELF_HOST/src/lex_scan_float.cxc"
ORACLE_CLJ="$SELF_HOST/oracle/lex_scan_float.clj"

WORK="$(mktemp -d)"
CXC_OUT_DIR="$WORK/cxc_build"
CXC_OUT="$WORK/cxc_out.txt"
ORACLE_OUT="$WORK/oracle_out.txt"

echo "[harness] building cxc port"
rm -f "$CXC_OUT_DIR/lex_scan_float"
"$COMPILER_DIR/bin/cxc" compile "$SRC" "$CXC_OUT_DIR" >/dev/null
BIN="$CXC_OUT_DIR/lex_scan_float"
if [ ! -x "$BIN" ]; then
  echo "[harness] cxc build produced no executable at $BIN" >&2
  exit 1
fi

echo "[harness] running cxc port on corpus"
: > "$CXC_OUT"
while IFS= read -r line || [ -n "$line" ]; do
  "$BIN" "$line" >> "$CXC_OUT"
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
