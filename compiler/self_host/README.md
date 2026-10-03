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
11. **lex::tokenize (whitespace+word+int subset)** — four-phase atom-loop (stepping / in_word / in_int / end), composes slices seven, eight, nine, keyword classification from the 40-entry reserved list, column tracking. Emits `;`-separated `<kind>:<value>@<col>` tokens via `.code_at` instead of `.to_bytes().at` for perf. Eleventh slice (chapter 5). STATUS landed. 29 inputs parity-equal.
12. **named_tuple::canonical-named-tuple-name (primitive-only, sort deferred)** — single-pass parser over `<f>|<m>;...` input with full name + mangle validation (reserved-prefix detection: `Nn_`, `_Cxc_NT`, `Nt<digit>`, `Of<digit>`). Fields pre-sorted by caller because cxc has no string-compare primitive yet; the sort step defers to a later slice. Twelfth slice (chapter 5). STATUS landed. 16 inputs parity-equal across 7 valid shapes and 7 invalid rejections.
13. **lex::scan-char (ASCII, no escapes)** — straight-line rejection ladder on `'x'` char literals. Rejects empty, unterminated, multi-char, and backslash bodies. Thirteenth slice (chapter 5). STATUS landed. 21 inputs parity-equal.
14. **lex::scan-digits (full coverage with underscore-malformed errors)** — extends slice seven. Atom-loop over cursor + last-was-digit + error flags; error-arms set `error => true` and self-send step, a guard-only `<: step -> end |: error` arm fires on the next tick (no then-body — `then:` on a target=end transition is a compile error). Covers all three Clojure throw paths. Chapter-5 landing. STATUS landed. 32 inputs parity-equal.
15. **named_tuple::canonical (sort upgrade)** — chapter-6 extension of slice twelve. The sort step is now in-port using the chapter-6 `.compare_to` landing. Selection sort as an atom-local inner loop: observations extract the current and best pair's name prefix, `.compare_to` gives strcmp-shape ordering, smallest-remaining pair migrates from `pairs` to `sorted_pairs` once per outer tick. Field order on the input is now free. STATUS landed. 26 inputs parity-equal across pre-sorted, reverse, and shuffled.
16. **named_tuple::structural-conforms?** — field-by-field conformance predicate. Width-subtyping scope: extra fields on the value side are legal, required-side fields all must be present, invariant type equality. Emits `OK` / `MISS:<field>` / `WRONG:<field>:<expected>:<got>` for parity with the Clojure fn's `{:conforms …}` map (dropping `:available` so no second sort pass). Chapter-6 slice fifteen. STATUS landed. 20 inputs parity-equal across OK, missing, wrong-type, empty-side, and reordered.
17. **resolve::import-source-map (alias-expansion)** — per-entry alias rule only. `(or :as :name)` for from-import symbols, `(or :as :from)` for bare import, uniform input DSL `<kind>|<mod>|<sym>|<alias>` with four fields always so the parse offsets stay constant. Chapter-6 slice sixteen. STATUS landed. 17 inputs parity-equal.
18. **diagnostics (shape-only)** — fail-nyi! / fail-user! / fail-compile! shape port. Doesn't actually raise; emits the `<kind>|<message>` pair the oracle extracts from the thrown `ex-info` so the parity harness verifies the composed shape (kind + message prefix). Real raise lives behind the Corvid typed-raise + `or T():` recovery form once records matching the diagnostic AST land. Chapter-6 slice seventeen. STATUS landed. 10 inputs parity-equal across the three helpers.
19. **AST shape records (design + demo)** — chapter-7 compiler landing. Design note at `design/ast-shape-records.md` documents the Clojure producer, the cxc consumer encoding, the two sealed-sum compiler gaps that bite today (self-referential enum payload emits before the forward decl; mutual recursion through List-of-record ditto), and the flat tagged-record encoding the port uses as a bridge until the sealed-sum migration. `src/ast_shape_demo.cxc` compiles and runs the shape end-to-end: four cases on `TypeKind`, one flat `TypeNode` record, demos construction + dispatch via `||:` guards on `.kind`.
20. **resolve::resolve-name-to-module** — four-way lookup ladder (prelude > own-decls > imports > unresolved) via parametric observations on `main`. Table lookup via `.starts_with` for the leading-key case + `,` + seek interior-match. Chapter-7 slice eighteen. STATUS landed. 25 inputs parity-equal.
21. **resolve::module-scoped-ref-names** — walker port over a flat (kind, name) stream. Five contributing kinds (type-ref, extend always-include; call/dot/type-of include when capitalized). Dedup + alphabetical sort via selection-sort using `.compare_to`. Chapter-7 slice nineteen. STATUS landed. 20 inputs parity-equal.
22. **lex::scan-string with escapes** — extends chapter-4's lex_scan_string. Preserves escape sequences in literal form in the buffer (oracle re-encodes its decoded payload back to literal form before diff). Supported escapes: `\n`, `\t`, `\r`, `\\`, `\"`, `\'`. Hex (`\xNN`) and unicode (`\u{…}`) deferred — the chapter-7 corpus avoids them. Chapter-7 slice twenty. STATUS landed. 22 inputs parity-equal.
23. **resolve::build-symbol-table** — top-level walker producing `name → module` mapping. Simulates the Clojure walk's module-marker state threading; later declarations of the same name replace earlier ones (`assoc` last-wins). Prelude-provenance interception avoided by corpus-side name selection (declaration names outside the prelude record set). Chapter-7 slice twenty-one. STATUS landed. 15 inputs parity-equal.
24. **named_tuple::collect-shapes (shallow)** — flat tagged-record encoding per the chapter-7 design note. Walks a `;`-separated `Nt<N>_<f>_<T>_...` stream from argv, strips the `Nt<digit>+_` prefix, prepends `_Cxc_NT_`, dedupes, selection-sort via `.compare_to`. Chapter-8 slice twenty-two. STATUS landed. 14 inputs parity-equal.
25. **named_tuple::emit-typedefs** — pair-walking emit with field-type → primitive-C-name lookup. Primitive mapping passed via `CXC_PRIM_MAPPING` env var (bash `clj -M -e` consumes trailing positional args as script path, env var sidesteps). Corpus-side pre-sort required because the port preserves input order and the Clojure side sorts fields alphabetically. Chapter-8 slice twenty-three. STATUS landed. 12 inputs parity-equal.
26. **lex::scan-number (decimal + hex int)** — base-prefix dispatch (hex `0x`/`0X`, C-octal `0`+digit reject, bin `0b`/`0B` reject, oct `0o`/`0O` reject, else decimal). Value accumulated during scan so the port never calls a base-N string parser (cxc's `.to_int_or` is decimal-only). F5 unit-suffix detection via `is_ident_cont` fallthrough. Chapter-8 slice twenty-four. STATUS landed. 31 inputs parity-equal.
27. **resolve::find-unscoped-type-refs (composition)** — first composition slice. Slice-21 module-scoped-ref-names walker + slice-20 resolve-name-to-module ladder + unscoped filter in one atom. Single-file scope (declared-in aggregation elided; a name declared in own-decls is always in-scope). Oracle calls walker + resolver directly instead of driving `find-unscoped-type-refs` with synthetic AST (prelude is a hardcoded module-wide blob in `build-module-scope-index`, so the composition logic is what parity-checks). Chapter-8 slice twenty-five. STATUS landed. 20 inputs parity-equal.
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

- Last-landed layers. Twenty-five parity-confirmed byte-equal slices covering mangle (c-safe-param-name, ->c-safe), lex (classify-word, match-multi-glyph, scan-digits decimal, scan-digits full, scan-word, scan-string, scan-string with escapes, scan-char, scan-number, tokenize), resolve (mangled-name-for, import-alias, resolve-name-to-module, module-scoped-ref-names, build-symbol-table, find-unscoped-type-refs), env (predicates), named_tuple (canonical name with sort, structural-conforms?, collect-shapes, emit-typedefs), diagnostics (shape-only), AST shape records (design + demo), and ASCII char predicates. See the layer order list above for per-slice corpus sizes.
- Compiler landings. `String.split(sep) -> String` (newline-joined, interim) in commit `438e40b`. `fs.read_lines(path) -> String` (same interim shape) in commit `3489ea3`. Daemon detects stale compiler source and signals bin/cxc to retry cold in commit `9cdec7c`. `String.code_at(i) -> Int` ASCII-safe byte-at-index in commit `d074226`. `String.compare_to(other) -> Int` strcmp-shape UTF-8 byte order in commit `dbba76c`. All five verified cards.m baseline byte-identical before and after. Chapter 7 added no compiler change — the AST shape landing is a design note + a cxc-side demo that already compiles against existing primitives. Chapter 8 also added no compiler change — the forward-decl emit for recursive user types was DEFERRED to chapter 9 after a scope read showed the two sub-cases (self-reference enum payload + mutual recursion through List-of-record) require value→pointer semantic changes beyond simple decl ordering; the chapter-7 flat tagged-record encoding carried the chapter-8 slices (collect-shapes, emit-typedefs) that would have otherwise needed the recursive emit.
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
- Next viable layer with no stdlib blockers. `diagnostics` as a shape-only port. Named-tuple sort step (once a String-compare primitive or atom-level char-by-char compare helper is standardised). `cxc.named-tuple/collect-shapes` + `emit-typedefs` require AST walkers which depend on cxc records matching the Clojure `:node`-keyed AST — deferred until the parse layer lands.
- First layer with a hard blocker. Full `lex` on Unicode source. The ASCII subset can land first, Unicode identifiers need a cxc stdlib code-point iterator. Full `scan-string` with escape sequences needs code-point conversion for `\u{...}`, blocked today.
- Chapter 5 idiom learnings.
  - Terminal phase MUST be named `end`. Non-`end` terminal phase names (`emit`, `finish`, `done`, …) leave the atom idling in the dispatch runloop — `-transitionToEnd` is the only exit cascade. `then:` on a target=`end` transition is a compile error (spec §1.11), so error paths set an `error` flag and dispatch `self :> step`, letting a guard-only `<: step -> end |: error` arm fire on the next tick.
  - Multi-arm dispatch at the SAME phase on different message names coexist safely (`parsing <: step` and `parsing <: commit`), but single arms without `|:` guards may drop silently per F8 L3 dispatch rules; inline branch logic into guarded arms instead of relying on an unguarded fallthrough.
  - `||: cond : stmt-A ||: else : stmt-B` with statement-position bodies emits `emit-stmt: unknown node :c-ternary`. Compute via `let out: T! = ||: cond : A ||: else : B` and act on `out`; `||:` is an expression operator only.
  - `.code_at(i)` reads NSString unit widened to Int (ASCII-safe, matches the byte for 0 to 127). Out-of-range or nil receiver returns -1 so the sentinel composes with `||: at_end : -1 ||: else : …` observations without allocating NSData per tick.
- Chapter 6 idiom learnings.
  - `.compare_to(other)` returns strcmp-shape on UTF-8 bytes. Negative, 0, positive — the three guards an inline selection sort needs. Nil-tolerant: nil self sorts first, nil other sorts last. Use the chained-observation trick to extract the sort key (`scan_cur_name :- scan_cur.substring(0, scan_cur.index_of("|").max(0))`) before feeding `.compare_to`.
  - Field / observation names that collide with C-reserved words (`char`, `int`, `long`, `float`, `double`, `void`, `return`, …) emit as `self.<name>` at the C level and trigger `expected identifier` from clang, NOT a cxc-side error. Rename to a non-C-reserved synonym (`cur` for `char`, `len` for `int`, …) — the chapter-3 lesson about parser-reserved words (`module`) is distinct; this one bites at the emit boundary and surfaces as a clang error, not a cxc parse error.
  - Observations with chained `.at(index) ?? ""` for runtime list access work even when the index is temporarily out of range (e.g. a sentinel `scan_best_idx == -1`), because the `??` fallback short-circuits before any downstream substring call. Use `.max(0)` on index arithmetic that feeds `.substring` to keep intermediate values legal under cxc's eager let evaluation.
- Chapter 7 idiom learnings.
  - Enum case names cannot be cxc-reserved words. `case of` fails parse with `expected :ident got :kw "of"` (`of` is reserved per `cxc.lex/reserved-words`). Rename at the enum-definition site (`case parametric` for `:type-of`); the wire-level output tag can still spell `of:` if that matches the Clojure AST convention.
  - Recursive and mutually-recursive user types (`case ref(inner: Self)`, `case nt(fields: List of NamedField)` where NamedField contains Self) parse through cxc but clang rejects the generated `.m` with `unknown type name` because the struct typedef emits before any forward `@class` or forward typedef. See `design/ast-shape-records.md` for the flat tagged-record bridge encoding the self-host port uses until a compiler landing teaches the emit layer to walk the type dependency graph.
  - Parametric observations are the atom-local "method" surface: `name(params) :- ReturnType` + indented body with `alias` for intermediate let-like bindings and a final expression as the return value. Call via `self.name(args)` from a transition's then-body. Fields and `use …:` caps are visible inside the observation body; local lets inside the observation body use `alias` not `let`.
  - Observations with keyword-position names like `is_empty` are legal (observations have their own identifier scope) but keyword-matching argv tags still bite. `want == "of"` as a guard against argv is parse-time fine — the collision is only on definition sites.
- Chapter 8 idiom learnings.
  - Scope decision on live-compiler emit changes. The chapter-8 directive flagged ELEVATED RISK on item (1) forward-decl pass for recursive user types. The scope read showed two inseparable sub-cases: self-referential enum payload (`case nonnil(inner: TypeNode!)`) requires value→pointer semantic change (not just decl ordering) because the struct emits containing the full struct inline; mutual recursion through `List of NamedField` is a container-scan gap in `scan-atom-env-fields` + `scan-record-env-fields` that doesn't walk enum payload fields. Both are non-trivial. Coordinator's explicit defer offer taken: both deferred to chapter 9. Chapter-8 slices (collect-shapes, emit-typedefs) continued to use the chapter-7 flat tagged-record bridge.
  - CLI invocation env-vars dodge `clj -M -e` arg eating. `clj -M -e "$(cat oracle.clj)" "$extra"` passes `$extra` as `*command-line-args*` but clj interprets the second positional as a script path and errors. Use `CXC_PRIM_MAPPING="$MAPPING" clj -M -e "$(cat oracle.clj)"` with `(System/getenv "CXC_PRIM_MAPPING")` on the clj side. The named_tuple_emit_typedefs oracle uses this pattern.
  - Pair-walking requires explicit prefix recognition when the delimiter also appears in payload. `str/split body #"_"` + `(partition 2)` shatters `Nn_Int` into two tokens. Walk pair-by-pair with explicit `(str/starts-with? rest-b "Nn_")` check instead of naive splitting. Named_tuple corpus-side pre-sort also required because the port preserves input order while Clojure canonical sorts fields alphabetically.
  - Base-N literal scanning accumulates value during scan. cxc's `.to_int_or` is decimal-only; hex integers (`0x1A`) can't go through it. Instead the scan loop maintains `value => value * base + digit_val` where `digit_val :- ||: cur >= 48 and cur <= 57 : cur - 48 ||: cur >= 97 and cur <= 102 : cur - 87 ||: cur >= 65 and cur <= 70 : cur - 55 ||: else : 0`. Starting-phase dispatch picks `base => 16` on `0x`/`0X` prefix, else `base => 10`.
  - Composition slice shape. For a slice that pulls N prior slice logics together, both port and oracle re-implement the composition independently; the parity check verifies they agree. Driving the live Clojure composition function with synthetic AST input works for slices where the function's dependencies are injectable, but when the function reaches for a hardcoded program-wide blob (prelude-name-set in `build-module-scope-index`), re-implement the composition logic in both sides via calls to the slice-level helpers (`module-scoped-ref-names` + `resolve-name-to-module`) and skip the top-level fn.
  - `puts()` adds a trailing newline. `console.write` emits via `puts([text UTF8String])`, so an empty-string argument still produces a single `\n` byte. Oracle-side one-`println`-per-input-line (even when no output lines) matches this; use `(println (str/join "\n" entries))` which handles 0, 1, N entries uniformly.
