# PATTERNS.md — What Corvid does when other languages would do X

**Purpose.** Corvid's design has, session by session, dissolved many constructs that other languages ship: `try/catch`, `throws`, `Result<T, E>`, tuples, destructuring bindings, nested type declarations, `DispatchQueue`, `for` as a keyword, `|>` (pipe). Each dissolution has a specific "here's how Corvid does it instead" answer that's obvious once known and invisible until it is. This document catalogs those patterns.

**How to read.** Find your source language below. For each pattern you'd reach for, see the Corvid form with a code sketch and a pointer to the spec section that explains why. When no source-language match applies, the "Corvid-native patterns" section at the end summarizes the structural commitments the dissolutions descend from.

**This is not the spec.** The spec (`spec/spec-v0.11.md`) is normative. This document is onboarding guidance. When they disagree, the spec wins.

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
            ensures result.frame_count == self.frame_count + 1
            
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

### "I need keypath-projection pattern matching (Swift `case \.isNewline:`)"

Use a guard pattern: `case c if c.is_newline():`. §3.7 supports predicates on patterns via `case <pattern> if <predicate>:` — same capability as Swift keypath projection, without the punctuation-projection syntax.

```
switch character:
    case c if c.is_newline():   return .line_break
    case c if c.is_whitespace():return .whitespace
    case c if c.is_digit():     return .digit
    case _:                     return .other
```

Reads more explicitly than `case \.isNewline:` — the predicate is a normal method call, not a projected property access decoded through backslash-dot machinery.

See §3.7.

### "I need `inout` on a mutable array parameter (Swift `inout [UInt8]`)"

Not needed. `Array<T>`, `Map<K, V>`, `Set<T>` are `kind: regular` (reference-shaped) because they carry `state:`. Mutations inside a function are visible to the caller with no marker:

```
function write_uint16(buffer: [UInt8], value: UInt16):
    buffer.append(UInt8(value >> 8))
    buffer.append(UInt8(value & 0xFF))

# Caller sees the appended bytes:
let bytes: [UInt8] = []
write_uint16(buffer: bytes, value: 0xBEEF)
# bytes now [0xBE, 0xEF]
```

If you want value semantics for a container (no visible mutation across boundaries), wrap it in a `record` — records forbid `state:`, so mutation happens only via constructing a new record.

See §3.2, §4.10.7.

### "I need `let x: T` with conditional initialization (definite assignment)"

`let` requires an initializer at declaration. For conditional initialization, use the expression form of `if` / `switch` / `do:` — the whole branch is the initializer.

```
let payload: Bytes = if self.needs_decompression:
                        self.delegate.decompress(...) ?? frame.payload
                     else:
                        frame.payload
```

Same guarantee as Swift's definite-assignment analysis (assigned exactly once, then immutable), no separate mechanism. Reader sees "let = <one expression>" which happens to be a multi-line `if`. See §3.8, §7.1.

### "I need `guard` / `guard let`"

`guard let x = expr else: exit_stmt` — same as Swift, multi-binding added v0.8.1. Exit verbs: `return` / `break` / `continue` / `panic`. No `throw`.

```
guard let user = auth.current_user, let session = user.session else:
    return .unauthenticated
```

### "I need `extension Foo { ... }` (add methods to an existing class)"

`extend class Foo:` (§3.3.2, v0.9.0). Same shape as a `class` definition, prefixed with `extend`:

```
extend class NSString:
    methods:
        function shout(self) -> NSString:
            return self.upper_case
```

Generic parameters and where-clauses at declaration position:

```
extend class Array<T> where T: UnsignedInteger:
    methods:
        function sum_all(self) -> T:
            return self.reduce(0, (a, b) => a + b)
```

Extensions can add methods, invariants, and behavioral sections — but not new `state:` fields (memory layout is fixed at compile time). Extensions declared in module B are visible to any module that imports B.

Method-name conflicts across extensions are **warnings** (not errors) with deterministic resolution: current module wins over imported, imported wins over original. The `replace` modifier documents intent explicitly and silences the warning. For CI-strict projects, escalate with `-Werror=shadowing-extension`. Same-name self-conflict *within one class body* remains a hard compile error (author self-conflict). Revised in v0.10.0 from v0.9.1's stricter compile-error rule.

Retired: the `category(target_class: X):` form used in v0.8.x and earlier. Migration is mechanical.

See §3.3.2 for the full seven-rule composition semantics.

### "I need `String.Index`, `s[s.startIndex..<i]`, or int indexing on strings"

Corvid strings are grapheme-native (§3.10.5). No integer indexing (`s[5]` is a compile error). Positional access uses opaque `StringPosition` tokens (§3.10.7):

```
let pos = s.start_position()
let after_first = s.position(after: pos) ?? s.end_position()
let head = s.substring(from: pos, to: after_first)
let g = s.grapheme_at(position: pos)     # Char (grapheme cluster)
```

Swift's `s.startIndex`, `s.endIndex`, `s.index(after: i)`, `s[i..<j]`, `s.count` map to `s.start_position()`, `s.end_position()`, `s.position(after: i)`, `s.substring(from: i, to: j)`, `s.grapheme_count()`. Rationale: integer indexing over Unicode text admits three incompatible interpretations (bytes / code units / graphemes); Corvid refuses to pick silently.

### "I need `.utf8`, `.utf16`, `data.count`, `[UInt8](string.utf8)` for byte access"

`.as() -> Bytes` (UTF-8 default) or `.as_bytes(encoding: Encoding)` for other encodings (§3.10.5). Byte access is via the universal `As<Bytes>` conformance, not via peer views on String.

```
let bytes: Bytes = "hello".as()                           # UTF-8
let utf16_bytes = "hello".as_bytes(encoding: .utf16_le)
```

Reverse direction: `String.decoded(bytes)` returns `DecodeOutcome<String>` — switch on `.decoded(s)` / `.invalid(offset, reason)` per v0.8.5 no-throws discipline (§3.10.8).

### "I need `Data` / `NSMutableData` / `[UInt8]` for byte buffers"

`Bytes` (§3.10.4). Mutable regular class, toll-free bridged to NSData/NSMutableData. Read (`length`, `subscript`, `slice`, `concat`, `iter`), write (`append`, `insert`, `remove`, `clear`, `subscript_set`). Array-literal conformance: `let b: Bytes = [0x48, 0x69]`. No separate builder type — Bytes itself is the builder.

Raw-pointer access (`data.withUnsafeBytes { ... }`) is `bytes.raw_pointer()` and requires an `unsafe:` block (§6.7) — only for C interop.

### "I need `Codable` / JSONEncoder / auto-serialization"

No reflection-based auto-serialization. `kind: regular` classes write `.as() -> Bytes` explicitly (via `As<Bytes>`, §3.10.3); mutable state, potential cycles, and identity make mechanical serialization unsafe. Format-specific overlays (`As<Json>`, `As<MsgPack>`, etc.) are trait-qualified conformances added via ordinary `extend class Foo:` (§3.3.2). Third-party format packages ship a target type and users write `function as(self) -> ThatType:` on their records — no special language support needed. Method name is always `.as()` differentiated by return type; never `as_bytes()` / `as_json()`.

Value types (records, enums, primitives) get **auto-conformance** to `As<Bytes>` and `From<Bytes>` as of v0.11 (§3.4.6). Compiler synthesizes `function as(self) -> Bytes` and `class function from(source: Bytes) -> DecodeOutcome<Self>` from field layout at composition time. Same shape as automatic structural equality on records (§3.2) — a base-class property, not a stdlib layer requiring opt-in. Canonical wire format spec (§3.10.10): network-order (big-endian) for multi-byte primitives, fields concatenated in declaration order, enums as discriminator + associated data, strings/collections as `UInt32 length || payload`.

```
record Point:
    data: x: Float
    data: y: Float
    # No user-written .as() needed. Compiler synthesizes:
    #   .as() -> Bytes           returns 8 bytes: [<x big-endian 4>, <y big-endian 4>]
    #   .from(source: Bytes)     returns DecodeOutcome<Point>

let p = Point(x: 1.0, y: 2.0)
let b: Bytes = p.as()
let p2 = Point.from(source: b)   # -> DecodeOutcome.converted(p)
```

Override the auto-derived encoding by writing your own — per §3.3.2, own definitions win over inherited defaults. Missing `As<Bytes>` on a field's type → compile error at the record declaration with fix-it pointing at the field.

**Auto-conformance is only for `As<Bytes>` / `From<Bytes>`.** JSON, MsgPack, CBOR — no auto-derivation. Those formats have per-record author choices (field naming, null handling, precision, enum spelling); silent choice would be a footgun. Users author the conformance explicitly. Same principle as compile-error-on-narrowing (v0.10.1 Convertible retirement).

Rationale: `Codable`/`Serializable` reflection is the direction Java took and paid for repeatedly (serialization CVEs, class-shape drift breaking on-disk formats). Explicit `.as() -> Bytes` methods make the format visible in source; the trap doesn't compound.

```
class Message:
    data: id: String
    data: body: Bytes
    methods:
        function as(self) -> Bytes:
            # Author picks the wire format. Length-prefixed here.
            return length_prefix(self.id.as()) + length_prefix(self.body)
        function as(self) -> Json:
            return Json.object([
                Entry(key: "id", value: Json.string(self.id)),
                Entry(key: "body", value: Json.string(self.body.base64_encoded())),
            ])
```

### "I need `NSRegularExpression`, `Regex` (Swift 5.7+), or `re.compile` for pattern matching"

`Pattern` protocol (§3.10.9), three concrete conformers in prelude: `Regex`, `Glob`, `Literal`. String methods take any `Pattern` and dispatch through the protocol.

Prelude literal forms via raw-content prefix aliases (§4.16.17):

```
s.substring(matching: regex"^\s*//.*$")           # regex — trailing whitespace stripping
files.filter(f => glob"*.log".first_match(f) != nil)   # glob — filename matching
s.split(pattern: literal(","))                    # split on exact string
s.replace_all(pattern: regex"\s+", with: " ")     # collapse whitespace
```

**Named captures are mandatory.** Swift's `Regex("(\d+)")` unnamed group is a compile error in Corvid; write `regex"(?<year>\d+)"`. Access via `match.group("year")`. Rationale: consistent with the named-only discipline used everywhere else (records, enum associated values, `accepts` signatures). The reader sees capture names inside the pattern source without cross-referencing consumer code.

**Compilation caching is automatic** when the pattern source is a compile-time constant — `regex"^\d+$"` compiles once at parse time. No `Regex.compile(...)` boilerplate. Runtime-built patterns (`Regex(user_input)`) are constructed per call.

**Extensibility.** Add PEG, fuzzy, Levenshtein, etc. by defining a class with `first_match` + `all_matches` — structural conformance to `Pattern` is automatic, no declaration required. Or declare it explicitly via header sugar `class PegPattern(Pattern):` / body-form `conforms: Pattern` (§3.4.5) to get compile-time validation at the class declaration. String methods pick them up either way.

```
class LevenshteinPattern:
    metadata: kind: regular
    data: needle: String
    data: max_distance: Int
    methods:
        function first_match(self, source: String) -> Match?: ...
        function all_matches(self, source: String) -> Iterator<Match>: ...

# One-line prelude-style user alias for a literal form:
alias @fuzzy"<content>" raw = LevenshteinPattern(needle: <content>, max_distance: 2)

# Use:
s.first_position(matching: @fuzzy"aplle")
```

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

`extend class Foo<T> where T: X:` (§3.3.2, v0.9.0). Reuses the same generic-parameter and where-clause syntax as function generics.

```
extend class Array<T> where T: UnsignedInteger, T: Bounded:
    methods:
        function saturating_sum(self) -> T: ...
```

Multi-constraint uses comma-separated where atoms — no `&` conjunction operator.

### "I need `impl<T: Trait> for Foo<T>` (Rust trait implementation for a generic type)"

`extend class Foo<T> where T: Trait:` (§3.3.2). Same shape. If the trait provides default methods, Corvid's structural conformer inheritance (§3.4.4) means Foo<T> automatically inherits them when the where-clause is satisfied — no `impl` block needed; conformance is structural.

If you want to add methods to Foo<T> that go beyond what the trait provides: same `extend class` block.

```
protocol Serializable:
    accepts serialize(self) -> Bytes
    
    # Default:
    function serialize_with_prefix(self, prefix: Bytes) -> Bytes:
        return prefix + self.serialize()

extend class MyType:
    methods:
        function serialize(self) -> Bytes: ...
        # `serialize_with_prefix` inherited from Serializable's default automatically.
```

### "I need `From<T>` / `Into<T>` for conversions"

`As<T>` and `From<T>` in Corvid (§3.10.3). Same shape and intent as Rust's pair:

- `As<T>` — projection direction (self → T). Method: `function as(self) -> T`.
- `From<T>` — construction direction (T → Self). Method: `class function from(source: T) -> Self`.

```
class Config:
    data: raw: Bytes
    methods:
        function as(self) -> Bytes: return self.raw               # As<Bytes>
        function as(self) -> String: return String.decoded(self.raw)  # As<String>
    class function from(source: String) -> Self:                     # From<String>
        return Self(raw: source.as())
```

Trait-qualified dispatch: multiple `as` methods differ by return type via the conformed `As<T>` instantiation, not by parameter overloading (§3.4.4). Call-site resolution: return-type context, turbofish (`.as::<Bytes>()`), or `As<Bytes>.as(x)`.

**Difference from Rust.** No blanket `impl<T, U> Into<U> for T where U: From<T>` — Corvid does not auto-derive one direction from the other. Author both explicitly when both are meaningful. Rationale: projection and construction have distinct semantics; symmetric auto-derivation hides where information is lost or where construction may fail.

**Numeric narrowing lives OUTSIDE `As<T>`.** Prelude ships `As<T>` conformance only for **widening** numeric pairs (source range ⊆ target range — lossless). `Int32(300).as() -> UInt8` is a compile error; the diagnostic points at three narrowing methods on the source primitive:

```
Int32(300).wrapping_to::<UInt8>()      # -> UInt8(44)   modular per §3.9
Int32(300).saturating_to::<UInt8>()    # -> UInt8(255)  clamp
Int32(300).checked_to::<UInt8>()       # -> ConvertOutcome<UInt8>.overflow(...)
```

These are ordinary methods, not a protocol family. **Rationale:** `As<T>` differentiates by target type via trait-qualified dispatch, but the three narrowing semantics share a target type (UInt8) — target type can't distinguish them. Inventing sibling protocols (`WrappingAs<T>` etc.) would mimic As's shape without using its dispatch mechanism. Per §1.2 (author intent, not authority), the semantics choice belongs at the call site as a method name. This is a design invariant worth internalizing: when multiple candidate methods share a return type but differ in semantics, use method names, not protocols. See §3.11.1–§3.11.4.

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

Conformance is structural by default; explicit `class Foo(Parser):` header sugar or body-form `conforms: Parser` legal but optional (§3.4.5). Explicit conformance is compile-time-validated at the declaration site — missing/wrong-signature method fails at the class declaration, not at the first use-site assignment.

See §3.4, §3.4.5, §4.10.7.

### "I need `class Foo extends Bar` / `class Foo : Bar` / class inheritance"

Corvid commits to composition-over-inheritance for pure Corvid code — there's no `extends` for regular classes. Use protocol conformance + composition (holding a reference to a helper class) instead.

The one exception is ObjC interop: subclassing UIViewController, NSObject, etc. is unavoidable when writing framework-facing code. That uses body-form `metadata: extends:` (never header syntax):

```
class MyViewController:
    metadata:
        extends: UIViewController                          # ObjC-only inheritance
    state:
        table_view: UITableView?
    dynamic function viewDidLoad(self):
        super.viewDidLoad()
        self.table_view = UITableView()
```

Rules: `extends:` accepts one class, must be `kind: regular`, must be ObjC-imported. Pure Corvid classes cannot be subclassed. `super.method()` dispatches to the extended class per §5.7.

**Deprecated in v0.10.2, removed v0.11**: the double-colon header form `class MyViewController: UIViewController:`. The parser emits a fix-it warning suggesting body-form migration.

See §3.4.5, §9.3.1.

### "I need generic collections"

Generics work. Constraints via `<T: Comparable>` on type parameters. Array is `[T]` (sugar for `Array<T>`), Dictionary is `{K: V}` (sugar for `Map<K, V>`), Set is `{T}` (sugar for `Set<T>`) — all prelude aliases.

See §3.10, §4.20.1.

### "I need `null`-safe navigation"

Every reference type in Corvid allows nil. Message-send on nil is a no-op that returns nil (auto-lifted). Combine with `??` for defaults, `if let` for gating.

See §3.6.1.

### "I need `old(expr)` / `\old(expr)` / `x'` in a postcondition (from Dafny, JML, Eiffel, Ada, Spec#, Alloy)"

No `old(...)` form. Inside an `ensures` expression, `self` and parameter identifiers refer to the entry-state snapshot the contract runtime already had to capture; `result` refers to the post-state — the returned value for value-returning methods, or the receiver at method exit for void methods.

```
# Dafny/JML style:
# ensures count == old(count) + 1

# Corvid:
function increment(self)
    ensures result.count == self.count + 1:
    self.count += 1
```

Reads: "count-after equals count-before plus one." Pre-state on the right, post-state on the left, same claim.

Value-returning method:

```
function next_index(self) -> Index
    requires !self.did_reach_end()
    ensures result > self.current_index:
    return self.string.index_after(self.current_index)
```

Reference types capture handles at entry (same as any ordinary read). To detect field-level mutation on a referenced object, compare specific fields (`result.doc.title != self.doc.title`), not identity.

See §3.4.3.

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

- **Full spec:** `spec/spec-v0.11.md`
- **Design decisions log:** `working-notes.md`
- **Positioning docs:** `positioning/overview.md`, `positioning/game-of-life.md`, `positioning/ecosystem.md`
- **Empirical validation:** `validation/README.md` and per-parity friction logs
