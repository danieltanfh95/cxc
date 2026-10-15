#!/usr/bin/env bash
# Run every *-e2e.mjs test under 120s and print a pass/fail summary.
# Expects a trace-server running at $VIEWER_BASE (default 127.0.0.1:8769) with
# compiler/research/ as the trace-dir.

set -u
cd "$(dirname "$0")"

VIEWER_BASE="${VIEWER_BASE:-http://127.0.0.1:8769}"
export VIEWER_BASE

if ! curl -fs -o /dev/null -w '%{http_code}' "${VIEWER_BASE}/trace-viewer.html" | grep -q 200; then
  printf 'cannot reach %s/trace-viewer.html — start the trace server first\n' "${VIEWER_BASE}" >&2
  exit 2
fi

PASS=0
FAIL=0
FAILED=()
RESULTS=()

for t in *-e2e.mjs; do
  START=$(date +%s)
  if OUT=$(timeout 120 node "$t" 2>&1); then
    PASS=$((PASS + 1))
    STATUS=PASS
  else
    FAIL=$((FAIL + 1))
    FAILED+=("$t")
    STATUS=FAIL
  fi
  DUR=$(( $(date +%s) - START ))
  LASTLINE=$(printf '%s\n' "$OUT" | tail -1)
  RESULTS+=("$(printf '%-30s %4s  %3ds  %s' "$t" "$STATUS" "$DUR" "$LASTLINE")")
done

printf '\n== e2e summary ==\n'
for r in "${RESULTS[@]}"; do printf '%s\n' "$r"; done
printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf 'failing: %s\n' "${FAILED[*]}"
  exit 1
fi
exit 0
