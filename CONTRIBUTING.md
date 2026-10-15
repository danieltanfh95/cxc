# Contributing

PRs won't be reviewed. Issues are welcome for bug reports.

## Local setup

cxc requires macOS with `clang` (Xcode command-line tools) and a
Clojure CLI (`brew install clojure/tools/clojure`). `clojure -M:test`
drives clang end-to-end on every example in `compiler/examples/`, so
wire the toolchain first.

```sh
git clone https://github.com/danieltanfh95/cxc
cd cxc/compiler
clojure -M:test
```

See the README's "Try it" block for the one-off compile-and-run
invocation.

## Tests

`clojure -M:test` compiles and runs every `.cxc` under
`compiler/examples/`. Keep each example's expected stdout matching
when you touch the lower or emit layer. Land new compiler behaviour
with a new example that exercises it, plus a one-line description in
`compiler/examples/README.md`.

Run the tests for viewer, docs, and skills work too. Catch shared
cache misses and prelude-reload bugs early.
