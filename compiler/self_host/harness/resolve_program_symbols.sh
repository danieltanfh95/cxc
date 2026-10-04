#!/usr/bin/env bash
# Parity harness for the chapter-31 symbol-table slice (slice 37). A
# composition harness. Every row goes through slice 36
# (`parse_program_bootstrap`, argv[2] `prog`) and its output, through a
# file, into slice 37 (`resolve_program_symbols`). The oracle runs the
# public `cxc.parse/parse` and then `cxc.resolve/build-symbol-table` on
# the same row.
#
# `build-symbol-table` reads `cxc.resolve/prelude-name->module` itself.
# The port gets the same table as a file, written once per run by
# `oracle/resolve_program_symbols_index.clj`.
#
# Passes, each in both modes, the symbol table (`build-symbol-table`)
# and refs (`resolve-references-in-file`, argv[4] `refs` on the port,
# CXC_RESOLVE_MODE=refs on the oracle).
# 1. The slice-36 corpus, no module.
# 2. The real-world sweep (every `.cxc` file under compiler/examples,
#    design/examples, and compiler/resources/prelude, collapsed to one
#    row on the fly as in the slice-36 harness), no module.
# 3. The slice-37 hand corpus, no module.
# 4. The slice-37 hand corpus with the module `app.core` (argv[3] on the
#    port, CXC_RESOLVE_MODULE on the oracle). In table mode it stands
#    for the file's `:module` marker, in refs mode for the file's own
#    module, which is `main` when no module is given.

set -euo pipefail

pkill -f cxc.daemon >/dev/null 2>&1 || true

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF_HOST="$(cd "$HERE/.." && pwd)"
COMPILER_DIR="$(cd "$SELF_HOST/.." && pwd)"
REPO_DIR="$(cd "$COMPILER_DIR/.." && pwd)"

PARSE_CORPUS="$SELF_HOST/corpus/parse_program_bootstrap.txt"
CORPUS="$SELF_HOST/corpus/resolve_program_symbols.txt"
PARSE_SRC="$SELF_HOST/src/parse_program_bootstrap.cxc"
SRC="$SELF_HOST/src/resolve_program_symbols.cxc"
ORACLE_CLJ="$SELF_HOST/oracle/resolve_program_symbols.clj"
INDEX_CLJ="$SELF_HOST/oracle/resolve_program_symbols_index.clj"
SER_CLJ="$SELF_HOST/oracle/parse_program_bootstrap.clj"

WORK="$(mktemp -d)"
CXC_OUT="$WORK/cxc_out.txt"
ORACLE_OUT="$WORK/oracle_out.txt"
SWEEP="$WORK/sweep_rows.txt"
INDEX="$WORK/prelude_index.txt"
ONE="$WORK/one.txt"

echo "[harness] collapsing the real-world sweep"
: > "$SWEEP"
(cd "$REPO_DIR" && find compiler/examples design/examples compiler/resources/prelude -name '*.cxc' | LC_ALL=C sort) |
while IFS= read -r f; do
  perl -0pe 's/\\n/\\N/g; s/\n/\\n/g; $_ .= "\n"' "$REPO_DIR/$f" >> "$SWEEP"
done
echo "[harness] sweep rows: $(wc -l < "$SWEEP")"

echo "[harness] writing the prelude index"
(cd "$COMPILER_DIR" && clj -M -e "$(cat "$INDEX_CLJ")") > "$INDEX"
echo "[harness] prelude index rows: $(wc -l < "$INDEX")"

echo "[harness] building cxc ports"
rm -f "$WORK/p36/parse_program_bootstrap" "$WORK/p37/resolve_program_symbols"
"$COMPILER_DIR/bin/cxc" compile "$PARSE_SRC" "$WORK/p36" >/dev/null
"$COMPILER_DIR/bin/cxc" compile "$SRC" "$WORK/p37" >/dev/null
PARSE_BIN="$WORK/p36/parse_program_bootstrap"
BIN="$WORK/p37/resolve_program_symbols"
for b in "$PARSE_BIN" "$BIN"; do
  if [ ! -x "$b" ]; then
    echo "[harness] cxc build produced no executable at $b" >&2
    exit 1
  fi
done

run_port() {
  local rows="$1" mod="$2" mode="$3"
  while IFS= read -r line || [ -n "$line" ]; do
    "$PARSE_BIN" "$line" prog > "$ONE"
    "$BIN" "$ONE" "$INDEX" "$mod" "$mode" >> "$CXC_OUT"
  done < "$rows"
}

run_oracle() {
  local rows="$1" mod="$2" mode="$3"
  (cd "$COMPILER_DIR" && CXC_SER_ORACLE="$SER_CLJ" CXC_RESOLVE_MODULE="$mod" \
     CXC_RESOLVE_MODE="$mode" clj -M -e "$(cat "$ORACLE_CLJ")") < "$rows" >> "$ORACLE_OUT"
}

: > "$CXC_OUT"
: > "$ORACLE_OUT"
echo "[harness] running the composition on the slice-36 corpus, the sweep, and the hand corpus"
for mode in table refs; do
  run_port "$PARSE_CORPUS" "" "$mode"
  run_port "$SWEEP" "" "$mode"
  run_port "$CORPUS" "" "$mode"
  run_port "$CORPUS" "app.core" "$mode"
done

echo "[harness] running clj oracle"
for mode in table refs; do
  run_oracle "$PARSE_CORPUS" "" "$mode"
  run_oracle "$SWEEP" "" "$mode"
  run_oracle "$CORPUS" "" "$mode"
  run_oracle "$CORPUS" "app.core" "$mode"
done

echo "[harness] output rows: $(wc -l < "$CXC_OUT"), tables: $(grep -c '^(table ' "$CXC_OUT" || true), ref sets: $(grep -c '^(refs ' "$CXC_OUT" || true)"
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
