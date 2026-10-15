# Corvid user manual

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

Corvid is a language for the Apple ecosystem based on lifecycles.
Corvid supports functional (codata) object-oriented programming
without imperative baggage. The disk extension is `.cxc`. cxc is
written in Clojure, reads `.cxc`, emits Objective-C, and drives clang
to produce a macOS binary.

- **Atoms** are the stateful units. Each atom owns its state, a mailbox
  of messages, and a set of phases.
- **Phases** are the states an atom can be in. Transitions move the
  atom between phases as messages arrive.
- **Observations** are read-only, derived values an atom exposes to
  itself and to callers.
- **Records** are plain data. **Enums** are tagged variants. **Caps**
  are capability-shaped effects (filesystem, http, random, ...).

The surface is small. Five grammar glyphs (`:-` observation, `<:`
receive, `->` transition, `|>` sync send, `:>` async send) and a
handful of keywords. The whole language fits in your head after an
afternoon.

## Install

cxc requires macOS, `clang` from the Xcode command-line tools, and
the Clojure CLI (`brew install clojure/tools/clojure`). cxc drives
clang directly, so wire the toolchain first.

```bash
git clone https://github.com/danieltanfh95/cxc
cd cxc/compiler
clojure -M:test
```

The test suite compiles and runs every `.cxc` program under
`compiler/examples/`. A green test run means your toolchain is wired.

## Hello, Corvid

Compile and run `examples/hello.cxc` in one shot:

```bash
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

Every `.cxc` under [compiler/examples/](compiler/examples/) runs
through the same path, with expected stdout in
[compiler/examples/README.md](compiler/examples/README.md).

## The atom model in one page

A counter that counts. Thirteen lines.

```elixir
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

- `def Counter:` introduces a new kind. cxc infers "atom"
  from the body shape, specifically the `-> idle` initial transition
  and the `<:` receive arm.
- `n: Int = 0` is a data field with a default. Fields are mutable
  state owned by the atom.
- `bump :- n + 1` is an observation. It's a derived value with no
  arguments, read by `counter.bump`. Observations never mutate.
- `idle <: increment -> idle` is a transition arm. Stay in phase
  `idle` on `increment` and run the `then:` body.
- `then: n => n + 1` stages a field update. The `=>` arrow is one-shot
  per field per transition path. Multiple `=>` on the same field is a
  compile error.
- `|> c.increment()` is a synchronous send from `main`. The message
  runs on the receiver's queue, serialized against every other
  message to the same atom. `:>` is the async-send variant.

## Reading sequence by goal

| Goal | Read |
|---|---|
| Write my first `.cxc` from scratch | [docs/writing-corvid/01-your-first-atom.md](docs/writing-corvid/01-your-first-atom.md) |
| Syntax quick-reference | [docs/writing-corvid/02-language-tour.md](docs/writing-corvid/02-language-tour.md) |
| Architectural patterns to reach for | [docs/writing-corvid/03-design-patterns.md](docs/writing-corvid/03-design-patterns.md) |
| Common mistakes and compile errors | [docs/writing-corvid/04-common-mistakes.md](docs/writing-corvid/04-common-mistakes.md) |
| A longer worked example (task queue) | [docs/writing-corvid/05-worked-example.md](docs/writing-corvid/05-worked-example.md) |
| Coming from Swift, Rust, or Python | [docs/PATTERNS.md](docs/PATTERNS.md) |
| Normative language spec | [docs/spec/spec-cxc-v1.0.md](docs/spec/spec-cxc-v1.0.md) |
| Design philosophy and premises | [docs/spec/charter.md](docs/spec/charter.md) |
| Browse working example programs | [compiler/examples/](compiler/examples/) |
| Contribute a patch | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Work in Corvid as an AI agent | [AGENTS.md](AGENTS.md) |

Reopen [02-language-tour.md](docs/writing-corvid/02-language-tour.md)
for syntax. Reopen
[04-common-mistakes.md](docs/writing-corvid/04-common-mistakes.md)
for the shapes that fight the compiler.

## Capabilities (stdlib)

Corvid ships a small set of capabilities as prelude atoms. A program
that needs file I/O, HTTP, the clock, or another outside effect holds
the capability in a `use` field, so cxc threads the authority through
the atom graph instead of granting ambient access.

Current capabilities, each with a single file in
[compiler/resources/prelude/target/macos/](compiler/resources/prelude/target/macos/):

- **FileSystem**, `read_text`, `write_text`, `exists`, `is_dir`,
  `mkdirs`, `remove`, `copy`, `rename`, `list_dir`, and friends.
- **Http**, request/response, raises typed errors on wire failure.
- **Json**, parse, encode, path-based field access.
- **Subprocess**, run external commands. `env:` is explicit, no
  implicit environment inheritance.
- **EnvVars**, read env (total, never raises). Separate from...
- **EnvMut**, write env (raises `EnvError`). Read consumers never
  receive mutation authority.
- **Random**, cryptographically strong (always). Non-crypto fast is
  `SeededPrng(seed:)`, a separate type that does NOT conform to
  `Random`.
- **Time**, monotonic now, instant arithmetic.
- **Hash**, SHA256 and friends.
- **Console**, print, readln.
- **Args**, command-line arguments, with typed `get_or_int(index: Int, fallback: Int)`.
- **Clock**, periodic streams, test-overridable.

Each capability raises typed error records (`IOError`, `HttpError`,
`JsonError`, ...) rather than sentinel return values. Predicates stay
total and never raise. Recover with `or T(...)`. Full idiom at
[docs/writing-corvid/03-design-patterns.md](docs/writing-corvid/03-design-patterns.md).

## The trace viewer

A compiled Corvid program can emit a trace that records every atom
init, every message send and receive, every transition, every field
read and write. cxc's trace viewer renders the trace as a scoped
graph, and the screenshot in the README is one such view, a cards
game at the scope-of-main level.

```bash
cxc view <path-to-trace.jsonl>                 # open the viewer in the browser
cxc trace serve --trace-dir <dir>              # local http at 127.0.0.1:8769
```

cxc's trace viewer surfaces:

- Atoms as pills, sized by phase and arm count.
- Messages as directed edges, with `▲` / `▼` glyphs showing receive /
  send direction.
- Observations as cross-boundary edges when they span scope.
- Field counts (`34f · 12r` means 34 fields and 12 refs) and
  phase/arm counts on each pill.
- Click a pill to zoom in. Follow the breadcrumb back out. The
  lifecycle view inside shows per-phase arms and events.

[compiler/tools/.trace-viewer-polish.md](compiler/tools/.trace-viewer-polish.md)
records trace viewer design notes.

## Debugging a compile error

Walk through these in order when clang fails or the lower phase
rejects your `.cxc`.

1. **The error message.** Corvid diagnostics name the enforced spec
   section (`spec §3.6.1`, `spec §3.8.5`) and suggest the rename or
   reorder.
2. **[04-common-mistakes.md](docs/writing-corvid/04-common-mistakes.md).**
   The 25 shapes that most often fight the compiler, each with a
   before/after.
3. **The example.** Find the smallest `.cxc` under
   `compiler/examples/` using the same feature. Compare shapes.
4. **The spec.** When the diagnostic cites a section, open
   [docs/spec/spec-cxc-v1.0.md](docs/spec/spec-cxc-v1.0.md).

Field, observation, and let-local names that collide with C keywords
(`char`, `int`, `return`, ...) parse through Corvid cleanly but
surface as `expected identifier` from clang. Rename on the Corvid
side.

## Translating from Swift / Rust / Python

cxc drops many constructs other languages ship. The retired list
covers `try/catch`, `Result<T, E>`, `switch`, tuples, destructuring
bindings, nested type declarations, `DispatchQueue`, `for` as a
keyword, and `|>` as the pipe operator. Each dissolution maps to a
specific Corvid shape.

[docs/PATTERNS.md](docs/PATTERNS.md) catalogs the mapping. Find your
source language and its Corvid form. Read the "cxc-native patterns"
section for the structural commitments the dissolutions descend from.

- **No `Result[T, E]`.** Use exceptions plus `or T(...)` for recovery.
  Caps raise typed error records.
- **No `switch` / `case` as a statement.** Use transitions plus `|:`
  propositions on variant patterns.
- **No `try/catch`.** Transitions wrap errors at the arm boundary.
- **No sentinel returns.** Predicates are total, readers like
  `list.at(i) ?? fallback` and `map.get(k) ?? fallback` handle
  misses.
- **No user-defined operators.** `.append(v)`, `.concat(other)`,
  `.at(i)` are named methods, not glyph overloads.

## Design philosophy

[docs/spec/charter.md](docs/spec/charter.md) lists nine design
premises.

1. **Data before code.** A design without a data shape has nowhere to
   hide. Name the record before the transition, name the field before
   the guard.
2. **Stability is a virtue.** Keep backward compatibility. Changes
   to the surface are deliberate and infrequent.
3. **Correctness by construction.** cxc excludes wrong states.
   Excluded states need no runtime check.
4. **Orthogonal mechanisms.** Few, composable primitives beat many
   overlapping ones.
5. **Library over language.** Prefer macros, methods, and prelude
   additions over new keywords.

Read the charter before adding a language feature. Ask what shape
Corvid already has for this.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Open an issue before writing
code for anything beyond a typo or a clear bug fix, and read
[AGENTS.md](AGENTS.md) if you're an AI agent contributing on behalf
of a human.
