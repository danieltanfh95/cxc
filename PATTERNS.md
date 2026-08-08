# PATTERNS.md — What Corvid does when other languages would do X

**Purpose.** Corvid's design has, session by session, dissolved many constructs that other languages ship: `try/catch`, `throws`, `Result<T, E>`, tuples, destructuring bindings, nested type declarations, `DispatchQueue`, `for` as a keyword, `|>` (pipe). Each dissolution has a specific "here's how Corvid does it instead" answer that's obvious once known and invisible until it is. This document catalogs those patterns.

**How to read.** Find your source language below. For each pattern you'd reach for, see the Corvid form with a code sketch and a pointer to the spec section that explains why. When no source-language match applies, the "Corvid-native patterns" section at the end summarizes the structural commitments the dissolutions descend from.

**This is not the spec.** The spec (`spec/spec-v0.8.md`) is normative. This document is onboarding guidance. When they disagree, the spec wins.

---

## Coming from Swift or Objective-C

### "I need a serial queue to make this class thread-safe"

Don't allocate a queue. Write `state:` + `invariants:` and add `execution:` metadata for thread / QoS. The runtime serializes state transitions via the class's contracts (§1.9). No queue field, no `queue.async { }` boilerplate.

```
class FrameCollector:
    execution:
        qos: user_initiated
    
    state:
        buffer: Bytes = Bytes()
        frame_count: Int = 0
    
    invariants:
        frame_count >= 0
        frame_count == 0 implies buffer.is_empty
    
    methods:
        expose async function add(self, frame: Frame):
            requires frame.payload.length > 0
            ensures self.frame_count == old(self.frame_count) + 1
            
            self.buffer.append(frame.payload)
            self.frame_count += 1
```

See §1.9 (contracts as coordination), §8.9 (execution metadata).

### "I need `@main_actor`"

`execution: thread: main` on the class or method. Metadata-shaped, inspectable, per-method override where needed.

```
class UIController:
    execution: thread: main, qos: user_interactive
    
    methods:
        expose async function refresh(self): ...
```

See §8.9.

### "I need `try foo()` and `catch`"

No `try` keyword. No `catch`. No `finally`. Failable operations return a **domain-specific enum** with a case per outcome; the caller `switch`es on it. `with:` handles resource cleanup that would otherwise use `finally`.

```
switch File.open("/config.json"):
    case .handle(let f):        return parse_config(f)
    case .not_found:            return Config.default()
    case .permission_denied:    prompt_credentials(); return open_config()
    case .corrupted(_, let d):  panic("config corrupted: ${d}")
```

See §3.5.4 (failure handling model), §4.10.4.5 (`with:` for cleanup).

### "I need `Result<T, E>`"

Corvid does not ship `Result<T, E>` in the prelude. Use a domain-specific enum with a case per failure mode. Names read better; associated data per case is more expressive; metadata (§4.18) makes the shape inspectable.

```
enum FileOpenOutcome:
    case handle(File)
    case not_found(path: String)
    case permission_denied(path: String)
    case volume_unavailable(volume: String)
```

If you're writing genuinely generic middleware over "any failable operation," define a `Failable<T>` protocol conformed to by your domain enums; middleware operates over the protocol. Corvid does not push everyone toward a single `Result` wrapper.

See §3.5.4.

### "I need optional chaining `?.`"

Retired v0.7.0. `.` operates on nil receivers uniformly under nil-messaging inversion — the call returns nil on nil receiver (auto-lifted). No `?.` needed.

```
name = user.profile.display_name ?? "Anonymous"
# If user is nil, .profile returns nil, .display_name on nil returns nil,
# ?? "Anonymous" catches it. No ?. anywhere.
```

See §3.6.1 (nullability + nil-punning), §5.7 (nil-receiver dispatch).

### "I need force-unwrap `x!`"

Retired v0.7.0. If you genuinely need to panic on nil, write `x ?? panic("reason")`. The verbosity is intentional — panic should look exceptional in source because it's exceptional in behavior.

### "I need `guard` / `guard let`"

`guard let x = expr else: exit_stmt` — same as Swift, multi-binding added v0.8.1. Exit verbs: `return` / `break` / `continue` / `panic`. No `throw`.

```
guard let user = auth.current_user, let session = user.session else:
    return .unauthenticated
```

See §3.6.1.

---

## Coming from Rust

### "I need `?` for error propagation"

No `?`. `switch` on the returned enum explicitly at each layer. Trade-off: more code per layer; more visible dispatch; no hidden control-flow jumps. If your switch-chain is nesting deeper than 3 levels, that's a design signal to decompose the function.

```
switch parse(input):
    case .value(let v):
        switch validate(v):
            case .accepted(let clean):  return .completed(clean)
            case .rejected(let r):      return .invalid(reason: r)
    case .malformed(let d):
        return .parse_failed(details: d)
```

See §3.5.4.

### "I need tuples for multiple return values"

No tuples. Return a class with named fields — either a domain-specific record or one of the prelude carriers (`Pair<A, B>`, `Entry<K, V>`). Names beat positions.

```
record Slice:
    data:
        position: Int
        length: Int

function locate(pattern: String, in text: String) -> Slice: ...
```

See §3.10.2, §3.7.1 (no destructuring).

### "I need pattern-match destructuring in `let`"

Not supported. `let (a, b) = pair` doesn't exist. Case destructuring inside `switch` patterns *is* supported (it's discrimination-with-binding), but nowhere else.

```
# NO:
let (a, b) = compute_pair()

# YES:
let result = compute_pair()
process(first: result.first, second: result.second)

# YES — inside case patterns only:
switch result:
    case .success(value: let v): ...
    case .failure(reason: let r): ...
```

See §3.7.1.

### "I need a generic `Result<T, E>` for middleware"

Domain-specific enums cover the common cases better. If you truly need a generic wrapper for cross-domain middleware (retry combinators, testing scaffolds), author your own `Result<T, E>` enum or define a `Failable<T>` protocol and write middleware over the protocol. Corvid's stdlib does not ship one.

### "I need traits with `where` clauses"

Corvid protocols support conformance requirements. Generic constraints on categories (extension analog) are still spec-open — see `#W-69` in `validation/websocket/00-friction-log.md`.

---

## Coming from Python or Ruby

### "I need destructuring `a, b = pair`"

Not supported. Named accessors instead.

```
result = compute_pair()
a = result.first
b = result.second
```

See §3.7.1.

### "I need `for k, v in dict:`"

`for entry in dict:` — the iterator yields an `Entry<K, V>` with `.key` and `.value` accessors. Named access, one object per step.

```
for entry in headers:
    log("header: ${entry.key} = ${entry.value}")
```

See §3.10.1 (Iterable / Iterator), §3.10.2 (Entry).

### "I need `.each { |x| ... }` on collections"

`.for_each { x -> body }`, `.map { x -> f(x) }`, `.filter { x -> pred(x) }`. All method dispatch on any `Iterable<T>` conformer. `for x in y: body` is prelude sugar for `y.iter().for_each { x -> body }`.

See §3.10.1.

### "I need `x = foo() if bar else baz`"

`if` is an expression in Corvid — write it as `if bar: foo() else: baz`, no ternary syntax needed. `switch` is also an expression.

See §3.8 (everything is an expression).

### "I need list comprehensions"

`items.filter { x -> pred(x) }.map { x -> f(x) }`. Method chaining on the collection.

### "I need string interpolation"

`"${expr}"` inside a string literal. Simple identifier form `"$name"` shorthand for `"${name}"`.

See §4.20.1 (prelude string interpolation alias).

---

## Coming from Java or C#

### "I need `try/catch/finally`"

No `try` / `catch`. `switch` on the returned enum. `with:` handles cleanup that would otherwise use `finally` — deterministic destructor invocation regardless of exit path.

```
function process(path: String) -> ProcessOutcome:
    with:
        f = File.open(path)              # File conforms to Resource
        contents = f.read()
        return .completed(contents)
    # f destroyed on any exit path
```

See §3.5.4, §4.10.4.5.

### "I need nested types (`Foo.Bar`)"

Not supported. Use `<ClassName><Role>` naming at module scope. Leading-dot shorthand (§3.7.2) keeps use-site ergonomics.

```
expose class FrameCollector: ...

expose enum FrameCollectorEvent:
    case text(String)
    case binary(Bytes)

# At use sites, leading-dot resolves via inferred type:
switch event:
    case .text(let s): ...
```

Consumers who want brevity add a per-file alias: `alias @Event = com.example.ws.FrameCollectorEvent`.

See §3.3.1, §3.7.2.

### "I need interfaces / abstract base classes"

`class Foo: metadata: kind: protocol` (usually written via the `protocol` sugar keyword). `accepts:` section holds required method signatures; `methods:` section holds default implementations.

```
protocol Parser:
    accepts parse(input: String) -> ParseOutcome
    
    # Default implementation:
    function parse_batch(self, inputs: [String]) -> [ParseOutcome]:
        return inputs.map { i -> self.parse(i) }
```

Conformance is structural by default; explicit `class Foo: Parser:` legal but optional.

See §3.4, §4.10.7.

### "I need generic collections"

Generics work. Constraints via `<T: Comparable>` on type parameters. Array is `[T]` (sugar for `Array<T>`), Dictionary is `{K: V}` (sugar for `Map<K, V>`), Set is `{T}` (sugar for `Set<T>`) — all prelude aliases.

See §3.10, §4.20.1.

### "I need `null`-safe navigation"

Every reference type in Corvid allows nil. Message-send on nil is a no-op that returns nil (auto-lifted). Combine with `??` for defaults, `if let` for gating.

See §3.6.1.

---

## Coming from Elixir / F# / Haskell / OCaml

### "I need `|>` (pipe operator)"

Not in the prelude. Method chaining `x.f().g().h()` is Corvid's native pipeline; universal method dispatch and cheap extensions/sections make it work at parity. Projects that genuinely want pipe can declare `alias <lhs> @|> <rhs> ...` locally per v0.8.2 `@` marker discipline.

See §4.20.3 (paradigm-mismatch exclusions).

### "I need `do`-notation / for-comprehension for `Result`"

Not spec'd. Explicit `switch` on domain enums at each layer. Trade-off is the same as with Rust's `?` — Corvid picks visible dispatch over cross-cutting propagation. If nesting exceeds 3 layers, decompose.

### "I need algebraic data types"

`kind: enum` classes with `cases:` section and named associated values. Exhaustive `switch`, structural equality, discriminated storage.

```
enum Shape:
    case circle(radius: Float)
    case rectangle(width: Float, height: Float)
    case triangle(sides: [Float])
```

See §3.3.

### "I need immutable-by-default records"

`kind: record` classes. Value-typed, no `state:`, structural equality, copy-on-assign, auto-generated constructor from `data:` fields, auto-generated `with(field: value)` for updates.

See §6.2.

### "I need pattern matching in function definitions (multi-clause defs)"

Not spec'd as function-level clauses. Use one function per method, `switch` on the argument inside.

---

## Corvid-native patterns

These emerge from Corvid's structural commitments and are worth naming even if they don't correspond to a construct from another language.

### Iteration is method dispatch

`for` is prelude sugar over `.iter().for_each { }` on `Iterable<T>` (§3.10.1, §4.20.1). Anywhere a collection can be traversed, the collection is the receiver, and traversal is a method call. `.map`, `.filter`, `.reduce`, `.find`, `.any`, `.all` are the primary shapes. `while` and `loop` remain as bootstrap primitives that stdlib uses to implement `.for_each` and friends — user code should reach for method dispatch first.

### Failure is return type

Three shapes, no exception mechanism:

| Failure shape | Mechanism |
|---|---|
| Uniform ("did we get a value") | Return `T`, nil signals failure, use `??` / `if let` / `on_nil` |
| Discriminated ("how did we fail") | Return a domain-specific enum, `switch` at the call site |
| Invariant violation | `panic("...")` |

No `throws`, no `try`, no `catch`, no `Result<T, E>` in prelude. See §3.5.4.

### Coordination boundary = class boundary

The class is the unit of thread-safety. Its `state:` fields under invariants get atomic transitions via §1.9 contracts. Reader methods (don't modify state) can run concurrently; writer methods serialize. No `DispatchQueue`, no explicit mutex, no per-object lock. Thread affinity and QoS via `execution:` metadata (§8.9).

If you find yourself wanting to synchronize "across two classes," you probably want one class that owns both bits of state. The class boundary IS the synchronization boundary.

### Sugar is a library

The bootstrap grammar (§10) is minimal. Almost everything that looks like syntax is a **prelude alias**: `record`, `enum`, `protocol`, `[…]`, `{…}`, `..<`, `??`, `${…}`, `for`, and so on. The prelude is spec-committed (§4.20); users can inspect every alias to see exactly what it expands to. Projects that want their own surface additions declare `alias @name …` per the v0.8.2 `@` marker discipline — user aliases are visually distinct at every use site.

### Names beat positions, uniformly

- No positional enum associated values (v0.8.1) — `case closed(reason: String, code: UInt16)`, not `case closed(String, UInt16)`
- No tuples, no positional destructuring (v0.8.3) — `Pair<A, B>` with `.first` / `.second`; domain records where a domain word applies
- No positional function arguments — call sites use named arguments matching parameter names
- Enum case discrimination in `switch` uses named field patterns

The one exception: `case .some(let x)` — single-associated-value shorthand inside `case` patterns only, no ambiguity possible.

### Class-associated types are flat

No nested type declarations (v0.8.4). Types conventionally associated with a class use `<ClassName><Role>` at module scope. Leading-dot shorthand (§3.7.2) makes use sites read cleanly regardless. Consumers who want brevity add per-file `alias @Event = ...`.

### Before adding a mechanism, check what's already there

Corvid's design has consistently dissolved would-be mechanisms into existing tools: `for` → `Iterable`, tuples → records, nested types → convention, `throws` → return type, `try/catch` → switch, `DispatchQueue` → contracts + `execution:`. The five-session pattern (v0.8.3–v0.8.6) is stable enough to be a principle:

> When you're tempted to add a new mechanism, first ask whether Corvid's structural commitments (class-as-universal, nil-messaging inversion, contracts as coordination, universal method dispatch, sections, aliases) already cover the case via convention or composition.

Most of the time, they do.

---

## Cross-references

- **Full spec:** `spec/spec-v0.8.md`
- **Design decisions log:** `working-notes.md`
- **Positioning docs:** `positioning/overview.md`, `positioning/game-of-life.md`, `positioning/ecosystem.md`
- **Empirical validation:** `validation/README.md` and per-parity friction logs
