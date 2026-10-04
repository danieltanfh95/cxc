#!/usr/bin/env bash
# Parity harness for the chapter-19 composition slice,
# parse_layer_bootstrap | tnt_to_nt_bridge | named_tuple_collect_shapes.
#
# Each corpus line is a cxc source snippet. The port side chains three
# cxc binaries; the oracle side runs the live cxc.parse +
# cxc.named-tuple/collect-shapes directly.

set -euo pipefail

pkill -f cxc.daemon >/dev/null 2>&1 || true

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF_HOST="$(cd "$HERE/.." && pwd)"
COMPILER_DIR="$(cd "$SELF_HOST/.." && pwd)"

CORPUS="$SELF_HOST/corpus/collect_shapes_from_parse_layer.txt"
PARSE_SRC="$SELF_HOST/src/parse_layer_bootstrap.cxc"
BRIDGE_SRC="$SELF_HOST/src/tnt_to_nt_bridge.cxc"
CS_SRC="$SELF_HOST/src/named_tuple_collect_shapes.cxc"
ORACLE_CLJ="$SELF_HOST/oracle/collect_shapes_from_parse_layer.clj"

WORK="$(mktemp -d)"
PARSE_DIR="$WORK/parse_build"
BRIDGE_DIR="$WORK/bridge_build"
CS_DIR="$WORK/cs_build"
CXC_OUT="$WORK/cxc_out.txt"
ORACLE_OUT="$WORK/oracle_out.txt"

mkdir -p "$PARSE_DIR" "$BRIDGE_DIR" "$CS_DIR"

echo "[harness] building parse_layer_bootstrap"
rm -f "$PARSE_DIR/parse_layer_bootstrap"
"$COMPILER_DIR/bin/cxc" compile "$PARSE_SRC" "$PARSE_DIR" >/dev/null
PARSE_BIN="$PARSE_DIR/parse_layer_bootstrap"

echo "[harness] building tnt_to_nt_bridge"
rm -f "$BRIDGE_DIR/tnt_to_nt_bridge"
"$COMPILER_DIR/bin/cxc" compile "$BRIDGE_SRC" "$BRIDGE_DIR" >/dev/null
BRIDGE_BIN="$BRIDGE_DIR/tnt_to_nt_bridge"

echo "[harness] building named_tuple_collect_shapes"
rm -f "$CS_DIR/named_tuple_collect_shapes"
"$COMPILER_DIR/bin/cxc" compile "$CS_SRC" "$CS_DIR" >/dev/null
CS_BIN="$CS_DIR/named_tuple_collect_shapes"

for bin in "$PARSE_BIN" "$BRIDGE_BIN" "$CS_BIN"; do
  if [ ! -x "$bin" ]; then
    echo "[harness] missing binary $bin" >&2
    exit 1
  fi
done

echo "[harness] running cxc chain on corpus"
: > "$CXC_OUT"
while IFS= read -r line || [ -n "$line" ]; do
  parse_out=$("$PARSE_BIN" "$line")
  bridge_out=$("$BRIDGE_BIN" "$parse_out")
  canon_out=$("$CS_BIN" "$bridge_out")
  printf '%s\n' "$canon_out" >> "$CXC_OUT"
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
