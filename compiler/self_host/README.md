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
5. **mangle::->c-safe** — 15 LOC, composes slice 4 with a file-based ambient-map lookup via index_of + substring. Fifth slice. STATUS landed. 22 queries against a 16-row ambient table parity-equal across hit and miss cases.
6. **ASCII char predicates** — 15 LOC, four kinds (digit10, digit16, digit2, whitespace) matching Clojure Character/digit and Character/isWhitespace on 0 to 127. Sixth slice. STATUS landed. 44 inputs parity-equal.
7. **lex::scan-digits (decimal, well-formed)** — 5 LOC, strips underscores via `.replace("_", "")` and verifies via `.to_int_or`. Seventh slice. STATUS landed. 22 inputs parity-equal on the well-formed subset.
8. **lex::scan-word (ASCII)** — atom-loop idiom, `is_cont` observation over cursor + Bytes.at, three transitions (loader + step + end). Eighth slice. STATUS landed. 37 inputs parity-equal including identifier runs, non-id-continue stops, underscores, and leading non-id-continue (empty result).
9. **lex::scan-string (ASCII, no escapes)** — atom-loop, same shape as scan-word with the stop condition inverted to the closing double quote. Ninth slice. STATUS landed. 33 inputs parity-equal covering empty, single-char, punctuation-in-body, and longer bodies.
10. **env predicates** — combined `synth` / `phantom` / `lookup` port in one program. The lookup predicate reads a two-column env file and uses the slice five index_of + substring pattern with max/min clamps to keep intermediate values legal under eager evaluation. Tenth slice. STATUS landed. 26 inputs parity-equal across 7 synth, 5 phantom, 14 lookup cases.
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

- Last-landed layers. Ten parity-confirmed byte-equal slices covering mangle (c-safe-param-name, ->c-safe), lex (classify-word, match-multi-glyph, scan-digits decimal, scan-word, scan-string), resolve (mangled-name-for), env (predicates), and ASCII char predicates. See the layer order list above for per-slice corpus sizes.
- Compiler landings. `String.split(sep) -> String` (newline-joined, interim) in commit `438e40b`. `fs.read_lines(path) -> String` (same interim shape) in commit `3489ea3`. Daemon detects stale compiler source and signals bin/cxc to retry cold in commit `9cdec7c`. All three verified cards.m baseline byte-identical before and after.
- Reusable corpus-driven slice pattern. Each slice is a function with a stable scalar-input to scalar-output contract. For slices that need multi-row ambient state, pass the state as a file path in argv and read via `fs.read_text`. The cxc port and the clj oracle emit newline-terminated output on the same corpus, and the harness `diff -q`s the two output files. New slices follow the four-file shape (src, oracle, corpus, harness).
- Delimiter convention for multi-field corpora. Use `|` not `\t`. Bash `IFS=$'\t' read -r a b` collapses a leading tab, which turns `\tX` into `a=X b=""` instead of `a="" b=X`. The pipe is not a whitespace char so empty leading fields round-trip correctly. The two-arg glyph harness uses tab and happens to parity by coincidence (its empty-source rows have no match either way), the pipe is now the recommended delimiter for new slices.
- Idioms learned.
  - Always annotate primitive locals as `T!` (`let i: Int! = ...`) when the value will cross into an ObjC cast or substring index.
  - Avoid lambda capture of local Int bindings. Either inline the function with `||:` chains or hoist the local to an atom field.
  - Keep `||:` chains on one physical line even if long.
  - Use `args.get_or_int(index, fallback)` not `??`-coalesced int. `??` on a non-nullable primitive storage widens it back to Optional per the gap documented above.
  - `module` is a reserved keyword, use `mod_name` or similar.
  - For ambient-table slices, pass the table file path in argv and scan via `fs.read_text` plus `index_of` + `substring`. No lambda capture needed, matches the ->c-safe shape.
- Daemon caveat (chapter 4 fix). `compiler/bin/cxc` fronts a long-lived Clojure daemon. The daemon now records its start time and refuses `compile` requests with exit 99 when any `src/cxc/**/*.clj` or `resources/prelude/**/*.cxc` file is newer than the daemon start. `bin/cxc` catches the 99 and falls through to `exec clj`. Every self-host harness also runs `pkill -f cxc.daemon` as its first line as a belt-and-braces defence.
- Atom-loop idiom (chapters 4+). For slices that need iteration (scan-word, scan-string), use the three-arm dispatch pattern per `corvid-atom-loop-idiom`. Fields hold the accumulator and the cursor, a `-> running then: self :> step` kicks the loop, two `running <: step` arms guarded on continuation / termination handle advance and exit. Observations factor the loop predicate.
- Substring safety under eager eval. cxc evaluates all `let` bindings eagerly. When a port uses `index_of` + `substring` to extract a sub-range on possible-miss inputs, the intermediate `substring(start, len)` call fires even on miss. If `len` goes negative, NSString raises an NSInvalidArgumentException and the program crashes. Clamp with `.max(0)` and `.min(input.length())` for every intermediate index / length, let the final `||: hit_idx == -1 : ...` short-circuit discard the garbage substring.
- Next viable layer with no stdlib blockers. `diagnostics` as a shape-only port. `named_tuple` as data-shape helpers. Partial `lex::tokenize` driver that composes slices seven, eight, nine.
- First layer with a hard blocker. Full `lex` on Unicode source. The ASCII subset can land first, Unicode identifiers need a cxc stdlib code-point iterator. Full `scan-digits` with underscore-malformed error reporting needs an atom-loop extension that raises typed errors; the shape is clear, the slice is left for a chapter 5 landing. Full `scan-string` with escape sequences needs code-point conversion for `\u{...}`, blocked today.
