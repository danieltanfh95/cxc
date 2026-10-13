# compiler/self_host — Clojure-to-cxc self-host port

A port of the Clojure bootstrap compiler to cxc itself. Byte-for-byte
parity on `parity_target/cards.cxc` → `cards.m` is the acceptance test.

## Layout

- `src/` — cxc source files, one per Clojure layer slice (lex, parse,
  resolve, named-tuple, mangle, env, diagnostics).
- `oracle/` — Clojure driver scripts that run the reference
  implementation on the same inputs. Each slice has a `.clj` oracle.
- `harness/` — bash harnesses that compile the port, run both sides on
  the slice corpus, and `diff -q` the outputs.
- `corpus/` — slice input tables. Multi-field rows use `|` as the
  delimiter (not `\t` — see the handoff for the reason).
- `parity_target/cards.cxc` — the single-file test program whose
  `cards.m` emit is the end-to-end parity signal.
- `design/` — per-slice design notes.

## Running one slice

```sh
pkill -f cxc.daemon >/dev/null 2>&1 || true
bash compiler/self_host/harness/<slice>.sh
```

Each harness ends with `[harness] PARITY OK` or the first `diff` chunk.

## Running every slice

```sh
for h in compiler/self_host/harness/*.sh; do
  bash "$h" || echo "FAIL $h"
done
```

## Status

Chapter 21 landed. 32 parity-green slices covering mangle, lex, resolve,
env, named-tuple, diagnostics, parse-layer bootstrap, and ASCII
predicates. The 33rd `.cxc` under `src/` is `ast_shape_demo.cxc`, a
design artifact that is not a parity slice.

Next priorities, in order: container inner types in TNT bodies
(bridge + parse-layer), float literal lex into the shared lex table,
escape sequences in string / char lex into the shared lex table.

## Note

The chapter-by-chapter idiom learnings and the chapter directive queue
live in local handoff documents outside the public tree. If you are
reading this on GitHub, chapters are tracked in the commit log on
`main` under `self-host:` subject prefixes.
