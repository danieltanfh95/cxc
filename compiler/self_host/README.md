# Self-host port of the cxc compiler

Goal state. The ported cxc compiler, when fed `cards.cxc`, emits a byte-for-byte identical `cards.m` to the current Clojure compiler. `diff -q` returns clean. Not "equivalent", not "close", byte-for-byte.

Parity target. `/Users/danieltan/Projects/g-daniel/cards/dota2/cxc/cards.cxc` (960 LOC, found in the `cards/dota2` subrepo). Oracle output is `cards.m`, 1,496,605 bytes, as emitted by the Clojure compiler at commit cb2d5ab or later.

## Layout

```
compiler/self_host/
  README.md                  # this file
  src/                       # cxc source for the ported layers
  oracle/                    # clj CLIs that invoke the live cxc.* modules
  corpus/                    # input fixtures each layer is tested against
  harness/                   # shell drivers that run both sides, diff output
```

Everything under this directory is net-new. The existing compiler tree stays untouched.

## Port strategy

Terminal from the bottom up. We port leaf layers first (pure functions, no AST entanglement) and build toward lower + emit. Each layer gets:

1. a cxc source port under `src/`
2. a clj oracle under `oracle/` that drives the live cxc.* namespace against stdin
3. a corpus under `corpus/`
4. a shell harness under `harness/` that compiles the cxc source, runs it against each corpus line, runs the oracle on the same corpus, and `diff -q`s the outputs

Byte-for-byte diff is the only accepted exit condition. If a layer's parity hinges on JVM-side nondeterminism (hash ordering, UUID, etc.) we canonicalise both sides and flag the canonicalisation step.

## Layer order (dependency-ascending)

1. **mangle::c-safe-param-name** — 10 LOC, pure, lookup against a 44-entry C/ObjC reserved-word list. First slice. STATUS landed. 52 inputs parity-equal.
2. **lex::classify-word** — 2 LOC, pure, lookup against a 40-entry cxc reserved-word set. Second slice. STATUS landed. 54 inputs parity-equal.
3. **mangle::->c-safe** — 10 LOC, pure given the `*decl->module*` map. Needs the map structure and `resolve/mangled-name-for`. BLOCKED on resolve.
4. **diagnostics** — 38 LOC, three throw helpers. Can be ported but there is no byte output to compare, only error-shape at the ex-info boundary. Low-priority.
5. **lex sub-functions** — scan-digits, scan-string, scan-char. Each is a pure string to value function. Need cxc stdlib additions for character predicates (see Blockers).
6. **lex (full)** — 835 LOC. ASCII-safe subset is portable today, Unicode identifiers break on `Character/isUnicodeIdentifierStart` which has no cxc analogue.
7. **env + named_tuple + resolve** — 183 + 488 + 808 LOC. Data-shape layers.
8. **parse** — 4214 LOC. Depends on instaparse grammar in the current compiler. Blocker, cxc has no instaparse analogue, port will be a hand-written recursive descent.
9. **lower + lower/* submodules** — 10329 + ~10000 LOC. Hardest layer.
10. **emit** — 2851 LOC. Mechanical, byte-stability depends on map iteration order in a few sites that need to be canonicalised.

## cxc stdlib readiness audit (as of 2026-10-04)

What is in place.

- String: `+` concat, `.length`, `.contains`, `.starts_with`, `.ends_with`, `.substring(offset, length)`, `.index_of`, `.trim`, `.trim_prefix`, `.trim_suffix`, `.replace`, `.uppercase`, `.lowercase`, `.to_int_or`, `.to_bool_or`, `.repeat`, `.pad_left`, `.pad_right`, `.to_bytes`
- Bytes: `.at(offset) -> Int` byte-wise read
- List of T: `.append`, `.filter`, `.at`, `.count`, `.contains`, `.for_each`, `.sort_by`
- Set of T: `.insert`, `.without`, `.contains`, `.count`
- Map of T: `.assoc`, `.dissoc`, `.get`, `.at`, `.count`
- FileSystem cap: `read_text`, `write_text`, `read_bytes`, `write_bytes`, `exists`, `is_dir`, `is_file`, `mkdirs`, `remove`, `copy`, `rename`, `size`, `tmp_path`, `list_dir`
- Subprocess cap with explicit env, Args, Console (write/write_err/readln), EnvVars/EnvMut split
- `||:` multi-arm dispatch, `:-` observations, typed error raises

What is missing (blockers for later layers).

- String character-at. No `.at(i) -> Char` for String. Workaround for ASCII: `s.substring(i, 1) == " "` or convert to Bytes and byte-at. Unicode identifier scanning cannot be expressed without a code-point iterator.
- Character predicates. No `is_whitespace`, `is_digit`, `is_letter`, `is_hex_digit`, `is_unicode_identifier_start`. Implementable in pure cxc for ASCII; blocked for full UAX #31.
- Parse-int with base. `.to_int_or` is decimal-only. Hex, octal, binary literal scanning needs a base-N parser.
- String split. No `.split(sep) -> List of String`. Implementable in pure cxc on top of `.index_of` and `.substring`.
- Character ↔ integer code-point conversion. No `Char.code()`, no `String.from_code(n)`. Needed for escape-sequence handling (`\\u{HEX+}`).
- Line reading. `console.readln()` exists; no `fs.read_lines(path)`. Workaround: `fs.read_text(path).split("\n")` once split lands.
- No instaparse analogue. The current parse layer uses instaparse's grammar DSL. Porting parse means a hand-written recursive-descent walker.

What is NOT a blocker (verified in-scope).

- Set-of-String literal via `.insert` chaining is fine for small reserved-word sets.
- Positional argv access via `args.get_or(1, "")`.
- Shell-driven differential testing. Each run forks a cxc binary per corpus line and a single clj process for the oracle.

## Oracle convention

Each layer's oracle is a short `clj -M -e "..."` one-liner that requires the live cxc.* namespace, reads stdin line-by-line, calls the target function, writes `\n`-terminated stdout. The oracle NEVER mutates the live namespace. The oracle NEVER canonicalises unless the layer requires it (and the canonicalisation step is documented in that layer's section below).

## cxc program convention

Each layer's cxc port is a `def main:` program that reads one positional arg (`args.get_or(1, "")`), applies the ported function, writes the result with `console.write`. The shell harness invokes the binary N times (once per corpus line). This is intentionally slow but byte-stable.

## Resuming the port

If you are picking this up next session, start here.

- Last-landed layers. `mangle::c-safe-param-name` and `lex::classify-word`. Each is a reserved-word lookup. See `harness/*.sh` and `src/*.cxc`. Both parity-confirmed byte-equal on the committed corpora.
- Next viable layer with no stdlib blockers. `diagnostics` as a shape-only port, or begin landing stdlib additions for `scan-digits` (base-10 decimal path, byte-at-via-Bytes workaround).
- Reusable corpus-driven slice pattern. Each slice is a function with a stable `String -> String` or `String -> Int` contract. The cxc port and the clj oracle emit newline-terminated output on the same corpus, and the harness `diff -q`s the two output files. New slices should follow the same four-file shape (src, oracle, corpus, harness).
- First layer with a hard blocker. Full `lex` on Unicode source. The ASCII subset can land first, Unicode identifiers need a cxc stdlib code-point iterator.
