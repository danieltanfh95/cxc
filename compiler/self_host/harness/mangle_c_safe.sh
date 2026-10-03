#!/usr/bin/env bash
# Parity harness for the mangle::->c-safe port.
#
# Two corpus files, mangle_c_safe_table.txt is the ambient
# `*decl->module*` map in `<name>|<module>` row format, and
# mangle_c_safe_queries.txt is one query name per line. The cxc
# binary takes argv1 = table path, argv2 = query, and runs once per
# query line. The clj oracle reads the table path from env var
# CXC_TABLE_PATH, and the queries from stdin.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF_HOST="$(cd "$HERE/.." && pwd)"
COMPILER_DIR="$(cd "$SELF_HOST/.." && pwd)"

TABLE="$SELF_HOST/corpus/mangle_c_safe_table.txt"
QUERIES="$SELF_HOST/corpus/mangle_c_safe_queries.txt"
SRC="$SELF_HOST/src/mangle_c_safe.cxc"
ORACLE_CLJ="$SELF_HOST/oracle/mangle_c_safe.clj"

WORK="$(mktemp -d)"
CXC_OUT_DIR="$WORK/cxc_build"
CXC_OUT="$WORK/cxc_out.txt"
ORACLE_OUT="$WORK/oracle_out.txt"

echo "[harness] building cxc port"
"$COMPILER_DIR/bin/cxc" compile "$SRC" "$CXC_OUT_DIR" >/dev/null
BIN="$CXC_OUT_DIR/mangle_c_safe"
if [ ! -x "$BIN" ]; then
  echo "[harness] cxc build produced no executable at $BIN" >&2
  exit 1
fi

echo "[harness] running cxc port on queries"
: > "$CXC_OUT"
while IFS= read -r q || [ -n "$q" ]; do
  "$BIN" "$TABLE" "$q" >> "$CXC_OUT"
done < "$QUERIES"

echo "[harness] running clj oracle on queries"
(cd "$COMPILER_DIR" && CXC_TABLE_PATH="$TABLE" clj -M -e "$(cat "$ORACLE_CLJ")") < "$QUERIES" > "$ORACLE_OUT"

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
