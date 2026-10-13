# Corvid user manual

A single document that explains what Corvid is, how to install it, how
to write a first program, and where to go for each next step. The
authoritative sources are the files it points at; this doc exists so
that a stranger has one URL to open.

- [What Corvid is](#what-corvid-is)
- [Install](#install)
- [Hello, Corvid](#hello-corvid)
- [The atom model in one page](#the-atom-model-in-one-page)
- [Reading sequence by goal](#reading-sequence-by-goal)
- [Capabilities (stdlib)](#capabilities-stdlib)
- [The trace viewer](#the-trace-viewer)
- [Debugging a compile error](#debugging-a-compile-error)
- [Translating from Swift / Rust / Python](#translating-from-swift--rust--python)
- [Design philosophy](#design-philosophy)
- [Contributing](#contributing)

## What Corvid is

Corvid is an atom-based language for the Apple ecosystem. On disk it's
`.cxc`. The bootstrap compiler is written in Clojure, reads `.cxc`,
emits Objective-C, and drives clang to produce a macOS binary.

- **Atoms** are the stateful units. Each atom owns its state, a mailbox
  of messages, and a set of phases.
- **Phases** are the states an atom can be in. Transitions move the
  atom between phases as messages arrive.
- **Observations** are read-only, derived values an atom exposes to
  itself and to callers.
- **Records** are plain data. **Enums** are tagged variants. **Caps**
  are capability-shaped effects (filesystem, http, random, ...).

The surface is small. Four grammar glyphs (`:-` observation, `<:`
receive, `->` transition, `|>` sync send, `:>` async send) and a
handful of keywords. The whole language fits in your head after an
afternoon.

**Public alpha.** The design is stable, but surface details will shift
as feedback lands. Don't deploy production on it.

## Install

Requires macOS, `clang` from the Xcode command-line tools, and the
Clojure CLI (`brew install clojure/tools/clojure`). The compiler
drives clang directly, so a working toolchain is non-negotiable.

```
git clone https://github.com/danieltanfh95/cxc
cd cxc/compiler
clojure -M:test
```

The test suite compiles and runs every `.cxc` program under
`compiler/examples/`. A green test run means your toolchain is wired.

## Hello, Corvid

Compile and run `examples/hello.cxc` in one shot:

```
cd compiler
clojure -M -e '
(require (quote [cxc.build :as b]))
(let [src (slurp "examples/hello.cxc")
      out "/tmp/cxc-hello"]
  (clojure.java.io/make-parents (str out "/x"))
  (let [r (b/compile-source-to-file src "hello" out)]
    (when (zero? (:status r))
      (println (:stdout (b/run-binary (:bin-path r)))))))
'
```

Prints `hello, world`. Every `.cxc` under
[compiler/examples/](compiler/examples/) runs through the same path.
The expected stdout for each is listed in
[compiler/examples/README.md](compiler/examples/README.md).

## The atom model in one page

A counter that counts. Thirteen lines.

```
def Counter:
    n: Int = 0

    bump    :- n + 1
    doubled :- n * 2

    idle <: increment -> idle
        then: n => n + 1

def main:
    -> running
        then:
            let c: Counter = Counter()
            |> c.increment()
            |> c.increment()
            |> c.increment()
            print(c.doubled)
```

Prints `6`. Walk through it top to bottom.

- `def Counter:` introduces a new kind. The compiler infers "atom"
  from the body shape, specifically the `-> idle` initial transition
  and the `<:` receive arm.
- `n: Int = 0` is a data field with a default. Fields are mutable
  state owned by the atom.
- `bump :- n + 1` is an observation. It's a derived value with no
  arguments, read by `counter.bump`. Observations never mutate.
- `idle <: increment -> idle` is a transition arm. In phase `idle`,
  on receiving the `increment` message, stay in phase `idle` and run
  the `then:` body.
- `then: n => n + 1` stages a field update. The `=>` arrow is one-shot
  per field per transition path. Multiple `=>` on the same field is a
  compile error.
- `|> c.increment()` is a synchronous send from `main`. The message
  runs on the receiver's queue, serialized against every other
  message to the same atom. `:>` is the async-send variant.

Four reading sequences get you from here to any Corvid code you'll
meet. See [reading sequence by goal](#reading-sequence-by-goal) below.

## Reading sequence by goal

Each row points at the doc that owns that goal. Read the pointed-at
doc, not a secondhand version of it.

| Goal | Read |
|---|---|
| Write my first `.cxc` from scratch | [docs/writing-corvid/01-your-first-atom.md](docs/writing-corvid/01-your-first-atom.md) |
| Syntax quick-reference | [docs/writing-corvid/02-language-tour.md](docs/writing-corvid/02-language-tour.md) |
| Architectural patterns to reach for | [docs/writing-corvid/03-design-patterns.md](docs/writing-corvid/03-design-patterns.md) |
| Common mistakes and compile errors | [docs/writing-corvid/04-common-mistakes.md](docs/writing-corvid/04-common-mistakes.md) |
| A longer worked example (task queue) | [docs/writing-corvid/05-worked-example.md](docs/writing-corvid/05-worked-example.md) |
| Coming from Swift, Rust, or Python | [design/PATTERNS.md](design/PATTERNS.md) |
| Normative language spec | [design/spec/spec-cxc-v1.0.md](design/spec/spec-cxc-v1.0.md) |
| Design philosophy and premises | [design/spec/charter.md](design/spec/charter.md) |
| Browse working example programs | [compiler/examples/](compiler/examples/) |
| Contribute a patch | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Work in Corvid as an AI agent | [AGENTS.md](AGENTS.md) |

The two "come back for lookup" docs are
[02-language-tour.md](docs/writing-corvid/02-language-tour.md) for
syntax and
[04-common-mistakes.md](docs/writing-corvid/04-common-mistakes.md)
for the shapes that fight the compiler.

## Capabilities (stdlib)

Corvid ships a small set of capabilities as prelude atoms. A program
that needs file I/O, HTTP, the system clock, or anything else that
touches the outside world holds the capability as a `use` field. The
compiler threads the authority through the atom graph instead of
granting ambient access.

Current capabilities, each with a single file in
[compiler/resources/prelude/target/macos/](compiler/resources/prelude/target/macos/):

- **FileSystem** — `read_text`, `write_text`, `exists`, `is_dir`,
  `mkdirs`, `remove`, `copy`, `rename`, `list_dir`, and friends.
- **Http** — request/response, raises typed errors on wire failure.
- **Json** — parse, encode, path-based field access.
- **Subprocess** — run external commands. `env:` is explicit, no
  implicit environment inheritance.
- **EnvVars** — read env (total, never raises). Separate from...
- **EnvMut** — write env (raises `EnvError`). Read consumers never
  receive mutation authority.
- **Random** — cryptographically strong (always). Non-crypto fast is
  `SeededPrng(seed:)`, a separate type that does NOT conform to
  `Random`.
- **Time** — monotonic now, instant arithmetic.
- **Hash** — SHA256 and friends.
- **Console** — print, readln.
- **Args** — command-line arguments, with typed `.get_or_int(i, k)`.
- **Clock** — periodic streams, test-overridable.

Each capability raises typed error records (`IOError`, `HttpError`,
`JsonError`, ...) rather than sentinel return values. Predicates stay
total and never raise. See
[docs/writing-corvid/03-design-patterns.md](docs/writing-corvid/03-design-patterns.md)
for the recover-with-`or` idiom.

## The trace viewer

A compiled Corvid program can emit a trace that records every atom
init, every message send and receive, every transition, every field
read and write. The viewer renders the trace as a scoped graph. The
screenshot in the README is one such view, a cards game at the
scope-of-main level.

```
cxc trace view <path-to-trace.jsonl>
cxc trace serve <path-to-trace.jsonl>   # local http at 127.0.0.1:8769
```

The viewer surfaces:

- Atoms as pills, sized by phase and arm count.
- Messages as directed edges, with `▲` / `▼` glyphs showing receive /
  send direction.
- Observations as cross-boundary edges when they span scope.
- Field counts (`34f · 12r` means 34 fields and 12 refs) and
  phase/arm counts on each pill.
- A fractal zoom: click a pill to scope into it, breadcrumb back out,
  the lifecycle view inside shows per-phase arms and events.

Design notes under
[compiler/tools/.trace-viewer-polish.md](compiler/tools/.trace-viewer-polish.md).

## Debugging a compile error

When clang fails or the lower phase rejects your `.cxc`, walk through
these in order:

1. **The error message.** Corvid's diagnostics name the spec section
   they enforce (`spec §3.6.1`, `spec §3.8.5`) and often suggest the
   rename or reorder.
2. **[04-common-mistakes.md](docs/writing-corvid/04-common-mistakes.md).**
   The 25 shapes that most often fight the compiler, each with a
   before/after.
3. **The example.** If an error references a feature, find the
   smallest `.cxc` under `compiler/examples/` that uses the same
   feature and compare shapes.
4. **The spec.** When the diagnostic cites a section, open
   [design/spec/spec-cxc-v1.0.md](design/spec/spec-cxc-v1.0.md) and
   read it. The spec is often the fastest way to understand why the
   rule exists.

Field, observation, and let-local names that collide with C keywords
(`char`, `int`, `return`, ...) parse through Corvid cleanly but
surface as `expected identifier` from clang. Rename on the Corvid
side.

## Translating from Swift / Rust / Python

Corvid dissolves many constructs other languages ship: `try/catch`,
`Result<T, E>`, `switch`, tuples, destructuring bindings, nested type
declarations, `DispatchQueue`, `for` as a keyword, `|>` as the pipe
operator. Each dissolution has a specific Corvid shape that replaces
it, often invisible until you know it exists.

[design/PATTERNS.md](design/PATTERNS.md) catalogs the mapping. Find
your source language, find the pattern you'd reach for, see the
Corvid form with a code sketch and a pointer to the spec section
explaining why. If no source-language match applies, the "cxc-native
patterns" section at the end summarizes the structural commitments
the dissolutions descend from.

Quick ones to internalize before you start porting:

- **No `Result[T, E]`.** Use exceptions plus `or T(...)` for recovery.
  Caps raise typed error records.
- **No `switch` / `case` as a statement.** Use transitions plus `|:`
  propositions on variant patterns.
- **No `try/catch`.** Transitions wrap errors at the arm boundary.
- **No sentinel returns.** Predicates are total; readers like
  `list.at(i) ?? fallback` and `map.get(k) ?? fallback` handle
  misses.
- **No user-defined operators.** `.append(v)`, `.concat(other)`,
  `.at(i)` are named methods, not glyph overloads.

## Design philosophy

Corvid descends from nine design premises in
[design/spec/charter.md](design/spec/charter.md). The short version:

1. **Data before code.** A design without a data shape has nowhere to
   hide. Name the record before the transition, name the field before
   the guard.
2. **Stability is a virtue.** Backward compatibility is a core
   value. Changes to the surface are deliberate and infrequent.
3. **Correctness by construction.** Where a wrong state can't be
   expressed, no runtime check is needed.
4. **Orthogonal mechanisms.** Few, composable primitives beat many
   overlapping ones.
5. **Library over language.** Prefer macros, methods, and prelude
   additions over new keywords.

The charter spells each out. If you find yourself wanting a new
language feature, read the charter first. The right question is
usually "what shape does Corvid already have for this?"

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Open an issue before writing
code for anything beyond a typo or a clear bug fix, and read
[AGENTS.md](AGENTS.md) if you're an AI agent contributing on behalf
of a human.
