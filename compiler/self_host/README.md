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

1. **mangle::c-safe-param-name** — 10 LOC, pure, lookup against a 44-entry C/ObjC reserved-word list. First slice. STATUS landed. 93 inputs parity-equal (including Unicode, emoji, idempotence on already-suffixed names).
2. **lex::classify-word** — 2 LOC, pure, lookup against a 40-entry cxc reserved-word set. Second slice. STATUS landed. 54 inputs parity-equal.
3. **lex::match-multi-glyph** — 7 LOC, pure, longest-first match against a 16-entry glyph table. Third slice. STATUS landed. 37 inputs parity-equal.
4. **resolve::mangled-name-for** — 15 LOC, pure, four-case module-plus-name mangler. Fourth slice. STATUS landed. 25 inputs parity-equal across nil / std.* / main / dotted-user-module cases.
5. **mangle::->c-safe** — 10 LOC, consults `*decl->module*` ambient and delegates to `mangled-name-for`. Can now be built on top of slice 4, but still needs a Map-of-String-to-String binding to stand in for the ambient. Deferred until List-of-String harness patterns settle.
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

Compiler gaps discovered while landing chapter 2.

- `let x: T` where T is a primitive (Int, Bool, Float) stores x as `_Cxc_Optional_T` even when the RHS returns a raw non-nullable primitive. Reaching for the raw value inside a `substring` or `NSUInteger` cast then fails compile with "operand of type `_Cxc_Optional_Int64` where arithmetic or pointer type is required". Workaround is `let x: T!` for the non-nullable storage. The surface rule is in `corvid-nullability-rule` memory and the lesson is to type-annotate `T!` whenever you reach for a raw int inside an ObjC cast.
- Lambda bodies cannot span multiple statements and cannot bind locals via `let`. The lambda surface is `arg => expr`. Captures of outer `Int` locals widen even when the outer binding is `T!`, so the practical rule for now is to avoid capturing locals of primitive type in filter and map lambdas, or hoist them to atom fields.
- Multi-arm `||: ... ||: else : ...` chains do not continue across newlines. The parser errors with "expected an expression, got a newline". The whole chain must live on one physical line, or be hoisted into an observation body on one physical line. Long chains are the current workaround for the lambda-capture gap and lose readability past about 10 arms.

## Oracle convention

Each layer's oracle is a short `clj -M -e "..."` one-liner that requires the live cxc.* namespace, reads stdin line-by-line, calls the target function, writes `\n`-terminated stdout. The oracle NEVER mutates the live namespace. The oracle NEVER canonicalises unless the layer requires it (and the canonicalisation step is documented in that layer's section below).

## cxc program convention

Each layer's cxc port is a `def main:` program that reads one positional arg (`args.get_or(1, "")`), applies the ported function, writes the result with `console.write`. The shell harness invokes the binary N times (once per corpus line). This is intentionally slow but byte-stable.

## Resuming the port

If you are picking this up next session, start here.

- Last-landed layers. Four parity-confirmed byte-equal slices, `mangle::c-safe-param-name` with 93-input corpus, `lex::classify-word` with 54, `lex::match-multi-glyph` with 37, `resolve::mangled-name-for` with 25.
- Compiler landing. `String.split(sep) -> String` (newline-joined, interim) landed in commit `438e40b`. cards.m baseline byte-identical before and after. New blocker surface for later slices that need to split a file into lines.
- Reusable corpus-driven slice pattern. Each slice is a function with a stable `String -> String` or `(String, Int) -> String` or `(String, String) -> String` contract. The cxc port and the clj oracle emit newline-terminated output on the same corpus, and the harness `diff -q`s the two output files. New slices follow the same four-file shape (src, oracle, corpus, harness).
- Delimiter convention for multi-field corpora. Use `|` not `\t`. Bash `IFS=$'\t' read -r a b` collapses a leading tab, which turns `\tX` into `a=X b=""` instead of `a="" b=X`. The pipe is not a whitespace char so empty leading fields round-trip correctly. The two-arg glyph harness uses tab and happens to parity by coincidence (its empty-source rows have no match either way), the pipe is now the recommended delimiter for new slices.
- Idioms learned.
  - Always annotate primitive locals as `T!` (`let i: Int! = ...`) when the value will cross into an ObjC cast or substring index.
  - Avoid lambda capture of local Int bindings. Either inline the function with `||:` chains or hoist the local to an atom field.
  - Keep `||:` chains on one physical line even if long.
  - Use `args.get_or_int(index, fallback)` not `??`-coalesced int. `??` on a non-nullable primitive storage widens it back to Optional per the gap documented above.
- Next viable layer with no stdlib blockers. `diagnostics` as a shape-only port, or `mangle::->c-safe` once a Map-of-String-to-String corpus input shape is designed (slice 4 covers the delegate function; the ambient-map part is still a cxc gap).
- First layer with a hard blocker. Full `lex` on Unicode source. The ASCII subset can land first, Unicode identifiers need a cxc stdlib code-point iterator.
