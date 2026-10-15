
# Cleanup-audit reference

Routed from the `cxc` skill when the task is auditing an existing `.cxc` file for anti-patterns.

Systematic audit of an existing `.cxc` file against the shapes the
authoring guide names as anti-patterns. The upstream companion is
[cxc-authoring](../../cxc-authoring/SKILL.md). Where cxc-authoring
tells you what to write, this skill tells you what to look for and
remove in code you already have.

## When to invoke

- Before a landing PR touches a `.cxc` file that has grown past
  ~200 lines.
- After the writing-corvid guide adds a new anti-pattern (this file
  should list it too, and if not, update this file first).
- After a spec change that retires a form (record a rule below).
- When a `.cxc` file was ported from a pre-v1.0 draft and hasn't been
  re-read since.

Do **not** invoke for a small edit to a small file. Overhead > value.

## Input

A single `.cxc` file path, or a glob (`compiler/examples/*.cxc`,
`your-project/*.cxc`). The audit runs per-file, and findings are
grouped per-file in the report.

## Output shape

```elixir
=== <file-path> ===
findings: <N>
severity  rule         line(s)  quote                     fix
--------  -----------  -------  ------------------------  ----------------
high      T?-banned    L47      state: PlayerState?       T? banned; use T (nullable) or T! (non-nil)
med       imperative   L92      xs.add(item)              retired; xs => xs.append(item)
med       switch       L104     switch phase: ...         retired; use transitions + |: guards
low       inline-eff   L18-30   Effect(kind:.none,...)    hoist to atom field _no_effect
...
```

Severity meaning:

- **high**: compiler-rejected in v1.0 or spec-forbidden form. Must fix.
- **med**: retired-form or accidental-pattern that the compiler
  currently accepts but the guide names as an anti-pattern.
- **low**: duplication, un-hoisted default, or minor shape drift that
  would benefit from a refactor.

## The checklist

Each check names: **rule slug**, **what to look for**, **why it's
flagged**, **fix**. Work through in order.

### 1. `T?` used anywhere (high)

- **Look for**: any `: <TypeName>?` in the file.
- **Why**: `T?` is banned universally at v1.0. Legal forms are `T`
  (nullable) and `T!` (non-nil).
- **Fix**: convert to `T` if the field is legitimately nullable, or
  `T!` if it must hold a value.

### 2. `T` (nullable) that should be `T!` (med)

- **Look for**: fields typed `T` whose reads never guard with `??`
  and whose writes never assign `nil`. Deceptively nullable.
- **Why**: `T!` documents non-nil intent to the compiler and to
  readers. `T` invites a nil-messaging bug the day someone writes
  `nil` into it.
- **Fix**: promote to `T!` and let the compiler catch the write sites.

### 3. Imperative container mutation (high)

- **Look for**: `.add(`, `.remove(`, `.put(`, `.delete(`, `.insert(`
  as **statements** (not FP-return-value calls).
- **Why**: retired 2026-09-16. All container mutation is FP:
  `.append`, `.filter`, `.assoc`, `.dissoc`, `.insert` (returning
  new set), `.without`.
- **Fix**:
  | Retired form | Current form |
  |---|---|
  | `xs.add(v)` | `xs => xs.append(v)` |
  | `xs.remove(v)` | `xs => xs.filter(x => x != v)` |
  | `m.put(k, v)` | `m => m.assoc(key: k, value: v)` |
  | `m.delete(k)` | `m => m.dissoc(key: k)` |

### 4. `switch` / `case` dispatch statements (high)

- **Look for**: `switch <expr>:` blocks, or `case <name>:` outside a
  `def X:` enum body.
- **Why**: retired. Replaced by transitions + `|:` guards, or
  multi-arm `||:` in expression position.
- **Fix**: rewrite as guarded arms on the source phase, or `||:` chain
  in an observation body.

### 5. `Result[T, E]` types (high)

- **Look for**: `Result[`, `Ok(`, `Err(` in the file.
- **Why**: retired. Replaced by typed `raise` + `or T(...)` recovery.
- **Fix**: capability methods raise typed records (`IOError`, etc.).
  callers recover with `alias x :- capability.method(...) or T(...)`.

### 6. `try` / `catch` blocks (high)

- **Look for**: `try:`, `catch:`, `@try`, `@catch`.
- **Why**: retired for user code. `@try/@catch` survives in the
  compiler's F48 crash-model wrapper (do not touch), while user code
  uses the `or` recovery form at call sites.
- **Fix**: move to `alias x :- fallible() or T(...)`.

### 7. `if X: Y else: Z` as expression (med)

- **Look for**: `if ... : ... else: ...` inside an expression.
- **Why**: retired at v1.0. Expression-position dispatch uses `||:`.
- **Fix**: rewrite as `||: <guard> : <body> ||: else : <body>` chain.

### 8. `for x in xs:` loops (med)

- **Look for**: `for ... in ...:`.
- **Why**: no `for` in Corvid. Iteration is method dispatch.
- **Fix**: `xs.for_each(x => body)` or an aggregate method
  (`.count`, `.filter`, `.sort_by`).

### 9. User-defined operators (high)

- **Look for**: `+(`, `-(`, `==(`, `[](` as method definitions on user types.
- **Why**: retired. Built-in operators on primitives are
  compiler-blessed, and user types use named methods.
- **Fix**: rename to `.at(idx)`, `.concat(other)`, `.equals(other)`, etc.

### 10. Annotation grammar `@name(args)` (high)

- **Look for**: `@<lowercase>(` decorating a def.
- **Why**: retired 2026-08-30. No decorators. Kind info is inferred
  from body shape.
- **Fix**: delete the annotation. If it encoded semantics, encode
  those in the body's shape (or in a comment if truly meta).

### 11. Forward-reference in record definitions (high)

- **Look for**: a `def R:` whose fields reference another `def` that
  appears later in the same file.
- **Why**: records lower to C struct typedefs in file order. Forward
  reference produces a C-level error at build time.
- **Fix**: reorder defs so referenced types precede their referencers.

### 12. `|>` sync-send cycle to same atom (high)

- **Look for**: an arm on atom `X` that sends `|> X.something()` or
  `|> self.something()`, particularly via a helper that calls back.
- **Why**: rejected at runtime with "sync-send to atom X while a prior
  sync-send to the same atom on this thread is still awaiting reply."
- **Fix**: use `self :> msg` (async), or refactor the callee's work
  into an observation (no dispatch), or fold into the caller's body.

### 13. Multiple `=>` on the same field per transition path (high)

- **Look for**: two or more `<field> =>` lines targeting the same field
  in the same `then:` body.
- **Why**: `=>` is snapshot-staged. Multiple stages on the same field
  is smuggled assignment. cxc rejects.
- **Fix**: fold into one expression, or hoist via `let`.

### 14. `=>` used as if incremental (med, latent bug)

- **Look for**: `field => field + N` on one line, then a subsequent
  line in the same body reading `field` (expecting the new value).
- **Why**: the body reads pre-image throughout. Reads see the OLD
  value.
- **Fix**: read `field + N` directly, or bind to `let` first.

### 15. List/Map/Set literal in observation body without type context (med)

- **Look for**: `:- ||: guard : [1, 2, 3]` or similar with the literal
  as a bare arm result.
- **Why**: the compiler can't infer element type from context.
- **Fix**: hoist to typed fields (`_arm_0: List of Int = [1, 2, 3]`)
  and reference them from the observation.

### 16. Ability-flag enum trap (med)

- **Look for**: an enum whose cases name specific entities or their
  effects (`Ability.axe_king_bonus`, `Ability.marksman_pierce`).
- **Why**: enum-of-behaviours. Every new behaving entity adds a case
  AND an arm at every consumer of the enum.
- **Fix**: collapse to effect-as-data. Enum shrinks to kinds of effect,
  and consumers dispatch on the record's `.kind`. See
  [03-design-patterns.md](../../../../docs/writing-corvid/03-design-patterns.md).

### 17. Parametric state via parallel fields (med)

- **Look for**: `count_0: Int`, `count_1: Int`, `count_2: Int` (or
  similar suffixed-by-index fields on one atom).
- **Why**: `=>` staging targets a compile-time field name. You can't
  parameterise by index. Parallel fields fake the parameterisation.
- **Fix**: sub-atom pattern. `Lane` with its own `count`, then
  `Board.lanes: List of Lane`.

### 18. Precomputed presentation strings on records (low)

- **Look for**: fields ending in `_str`, `_display`, `_short` that
  duplicate a raw data field.
- **Why**: rot on schedule mismatch (update the number, forget the
  string).
- **Fix**: replace the stored string field with an observation that
  formats the raw field on read.

### 19. Sentinel value instead of envelope (low)

- **Look for**: fields where `-1`, `""`, `NaN`, or a "magic" value
  means "absent."
- **Why**: sentinels rot when the domain grows to admit the sentinel
  as legitimate.
- **Fix**: use a nullable slot with `??` fallback, or an envelope
  record with a `present: Bool` field.

### 20. Missing fall-through arm on guarded dispatch (low)

- **Look for**: several arms on `phase <: msg` each with `|:` guards,
  no trailing unguarded arm.
- **Why**: any incoming message not matching a guard is unhandled.
  New enum cases require a code change at every dispatch site.
- **Fix**: add a trailing unguarded arm as the vanilla case. See
  [03-design-patterns.md#fall-through-arm](../../../../docs/writing-corvid/03-design-patterns.md).

### 21. Wildcard on-enter counting on self-loop fire (med, latent bug)

- **Look for**: `-> <phase> then: <body>` (wildcard on-enter) plus
  arms shaped `<phase> <: msg -> <phase>` that expect the on-enter
  to fire again.
- **Why**: wildcard on-enter fires on phase change, not on self-loops
  within the same phase. Self-loops reuse the phase without re-firing.
- **Fix**: move the side effect into the specific arm's `then:` body.

### 22. Payload type non-uniform across arms emitting to the same subscriber (high)

- **Look for**: two `:> event(payload: ...)` emit sites where the
  payload types differ (one emits `Card`, another emits `Card?`).
- **Why**: subscribers can't be typed consistently. Compiler rejects.
- **Fix**: make the payload uniform. Use a sentinel record instead
  of `nil` if the payload can be "empty."

### 23. Capability method used as if it returned an option (med)

- **Look for**: `fs.read_string(...)` followed by a nil-check on the
  result.
- **Why**: fallible capability methods raise. They do not return `nil`.
  A nil-check is dead code.
- **Fix**: `alias content :- fs.read_string(...) or IOError(: "")`.

### 24. Subprocess call without `env:` argument (high)

- **Look for**: `subprocess.run(cmd)` without `env:`.
- **Why**: R17. There is no default env. The capability model requires
  explicit `env: .empty` / `.inherit` / `.merge({...})`.
- **Fix**: add explicit `env:` argument.

### 25. `Random` used for non-crypto RNG (med)

- **Look for**: `Random` capability used for a seeded fast-path RNG.
- **Why**: R18. `Random` is CSPRNG-only. For seeded non-crypto,
  `SeededPrng(seed:)` is the correct type (does not conform to `Random`).
- **Fix**: switch to `SeededPrng(seed:)`.

## Working the audit

The recommended flow:

1. Read the target `.cxc` file end to end. Do not skim.
2. Walk the 25 checks in order, one file per pass. Grep patterns first
   (fast, catches ~60% of hits), then eyeball the flagged locations.
3. Group findings per-file and produce the report table above.
4. For each **high** finding, propose a diff. For each **med** finding,
   note whether the fix is mechanical or requires a design decision.
   For each **low** finding, note the change but do not diff.
5. If the file has more than ~10 total findings, split into two
   passes: land the highs first, then re-audit for meds/lows.

## Compile-verify after fix

Any diff proposed by this audit MUST compile before commit. See
[../cxc-authoring/SKILL.md#compile-verify-discipline](../../cxc-authoring/SKILL.md)
for the pipeline, and remember that an audit producing uncompilable
diffs is worse than no audit.

## When to update this skill

- A new anti-pattern lands in [04-common-mistakes.md](../../../../docs/writing-corvid/04-common-mistakes.md).
  Add it here.
- A new design pattern in [03-design-patterns.md](../../../../docs/writing-corvid/03-design-patterns.md)
  identifies a shape to reach for. If the corresponding anti-shape is
  detectable, add it here.
- A spec revision retires a form. Add the retirement rule here.

The guide is the source of truth. This skill is the operational
walk over it.
