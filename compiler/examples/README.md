# Compiler examples — programs the current compiler can build and run

Every `.cxc` file in this directory is compiled and executed by the
end-to-end test suite ([compiler/test/cxc/build_test.clj](../test/cxc/build_test.clj)).
The tests verify the produced binary's stdout matches the expected string.

These are **pipeline-verification programs**, not showcase examples.
They exist to prove the current lower→emit→clang→run chain works for a
specific subset of cxc. Design demonstrations (game-of-life,
game-toys.cxc, etc.) live under `../../design/examples/` and may exceed
what the current compiler can lower.

## Files (current coverage)

| File | What it verifies | Expected stdout |
|---|---|---|
| [`hello.cxc`](hello.cxc) | `print` of a string literal | `hello, world` |
| [`greet.cxc`](greet.cxc) | Different string round-trips through the pipeline | `cxc is running` |
| [`arithmetic.cxc`](arithmetic.cxc) | Int binop precedence + `%d` format selection | `11` |
| [`record-value.cxc`](record-value.cxc) | Record → C typedef struct; ctor + field access + arithmetic | `103` |
| [`nested-record.cxc`](nested-record.cxc) | Record-of-record; chained `.a.b` field access; float promotion | `1.500000` |
| [`record-defaults.cxc`](record-defaults.cxc) | Omitted fields fall back to declared defaults (`V().x` uses default) | `3` |
| [`atom-simple.cxc`](atom-simple.cxc) | Def-atom → ObjC class; `Counter()` → `[[Counter alloc] init]`; `.n` → property | `42` |
| [`record-methods.cxc`](record-methods.cxc) | Record method → C free function; `v.plus(o)` → `V_plus(v, o)` | `4.000000` |
| [`atom-ctor-params.cxc`](atom-ctor-params.cxc) | Def-atom ctor params → `initWithFoo:` selector; `data:` defaults resolve against ctor scope | `42` |
| [`nullable-nil-punning.cxc`](nullable-nil-punning.cxc) | `Counter?` type + `nil` literal; property access on nil → 0 (ObjC nil-messaging) | `0` |
| [`enum-cases.cxc`](enum-cases.cxc) | `enum Key: ...` → C typedef enum; `Key.arrow_down` → `Key_arrow_down` (index 1) | `1` |
| [`bool-and-is.cxc`](bool-and-is.cxc) | `and`/`or`/`is` lower to `&&`/`\|\|`/`==`; identity check on nil pointer | `1` |
| [`atom-methods.cxc`](atom-methods.cxc) | Methods on def-atoms → ObjC instance methods; `[c bump]` and `[c plusWithother:5]` selectors | `26` |
| [`atom-on-enter.cxc`](atom-on-enter.cxc) | Wildcard on-enter for initial phase — `then: n => 100` fires during init, overrides `data:` default | `100` |
| [`atom-on-enter-self-field.cxc`](atom-on-enter-self-field.cxc) | Unqualified data-field reference on RHS of `then:` resolves to `self.<field>` (direct ivar) | `30` |
| [`observable-simple.cxc`](observable-simple.cxc) | `doubled :< raw + raw` synthesizes zero-arg getter method; `.doubled` reads it via ObjC dot syntax | `42` |
| [`observable-chain.cxc`](observable-chain.cxc) | Observables typed in declaration order; later observable may reference earlier one | `7` |
| [`invariant-pass.cxc`](invariant-pass.cxc) | `invariants:` predicates checked at end of init; passing case runs through | `50` |
| [`invariant-fail.cxc`](invariant-fail.cxc) | Failing invariant aborts init with a stderr diagnostic; nonzero exit | *(abort)* |
| [`use-cap.cxc`](use-cap.cxc) | `use: clock: Clock` sugars to a required ctor-param + data-field; cap accessible in observables via `clock.offset` | `1042` |
| [`chained-comparison.cxc`](chained-comparison.cxc) | `lo <= val <= hi` desugars to `(lo<=val) and (val<=hi)` at parse-time; passing case runs through | `5` |
| [`nested-slot.cxc`](nested-slot.cxc) | `def paddle: Paddle` self-constructs a sub-atom in parent init; observable reads through the slot | `7` |
| [`nested-slot-ctor-args.cxc`](nested-slot-ctor-args.cxc) | `def cell: Cell(v: 99)` passes named args to the sub-atom's initWith selector | `99` |
| [`self-sync-send-in-on-enter.cxc`](self-sync-send-in-on-enter.cxc) | `self \|> bump` in initial-phase on-enter fires the transition twice under Layer 4 single-thread (direct call via `cxc_send_sync`); `alive <: bump -> alive then: n => n + 1` runs | `2` |

## Running one by hand

```
cd compiler
clj -M -m cxc.core examples/hello.cxc /tmp
/tmp/hello
```

The CLI compiles `examples/<name>.cxc` to `/tmp/<name>` (a native
binary) via clang. Deletion is up to you.

## Adding a new example

1. Write the `.cxc` file. Keep it minimal — one thing per file.
2. Add a build_test.clj entry using `(slurp "examples/<name>.cxc")` and
   assert on the produced stdout.
3. Add a row to the table above.

If the program should NOT compile yet (i.e. it stresses a feature the
lowering doesn't cover), put it under `../../design/examples/` instead
with a note about what blocks it.
