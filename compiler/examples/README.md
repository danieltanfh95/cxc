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
