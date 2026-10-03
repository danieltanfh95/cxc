#!/usr/bin/env bash
# Parity harness for the mangle::c-safe-param-name port.
#
# Builds the cxc port, runs it against each corpus line, runs the
# clj oracle against the same corpus, diff -q the two outputs.
# Exit 0 iff byte-equal.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF_HOST="$(cd "$HERE/.." && pwd)"
COMPILER_DIR="$(cd "$SELF_HOST/.." && pwd)"

CORPUS="$SELF_HOST/corpus/c_safe_param.txt"
SRC="$SELF_HOST/src/c_safe_param.cxc"
ORACLE_CLJ="$SELF_HOST/oracle/c_safe_param.clj"

WORK="$(mktemp -d)"
CXC_OUT_DIR="$WORK/cxc_build"
CXC_OUT="$WORK/cxc_out.txt"
ORACLE_OUT="$WORK/oracle_out.txt"

echo "[harness] building cxc port"
"$COMPILER_DIR/bin/cxc" compile "$SRC" "$CXC_OUT_DIR" >/dev/null
BIN="$CXC_OUT_DIR/c_safe_param"
if [ ! -x "$BIN" ]; then
  echo "[harness] cxc build produced no executable at $BIN" >&2
  exit 1
fi

echo "[harness] running cxc port on corpus"
: > "$CXC_OUT"
while IFS= read -r line; do
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
