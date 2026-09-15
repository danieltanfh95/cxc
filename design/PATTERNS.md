# PATTERNS.md — What cxc does when other languages would do X

**Purpose.** cxc's design has, session by session, dissolved many constructs that other languages ship: `try/catch`, `throws`, `Result<T, E>`, tuples, destructuring bindings, nested type declarations, `DispatchQueue`, `for` as a keyword, `|>` (pipe). Each dissolution has a specific "here's how cxc does it instead" answer that's obvious once known and invisible until it is. This document catalogs those patterns.

**How to read.** Find your source language below. For each pattern you'd reach for, see the cxc form with a code sketch and a pointer to the spec section that explains why. When no source-language match applies, the "cxc-native patterns" section at the end summarizes the structural commitments the dissolutions descend from.

**This is not the spec.** The spec (`spec/spec-cxc-v1.0.md`) is normative. This document is onboarding guidance. When they disagree, the spec wins.

> **v1.0-consolidation supersession note (2026-08-29,
> supersedes 2026-08-17 post-collapse note).** Code sketches
> throughout still teach retired section-header syntax (`use:` /
> `data:` / `state:` / `invariants:` / `methods:` / `metadata:`
> as top-level section headers on class bodies) — under Proposal G
> (F67 L3/L4/L5, 2026-08-17) the class-body vocabulary collapsed
> to two glyphs (`:-` observable, `<:` transition) with top-level
> fields, and the 2026-08-25 v1.0 user-surface consolidation
> retired the section-header form entirely. Ratified v1.0
> spellings: `use <name>: <T>` (atom-body keyword, not section),
> direct fields, and `|:` propositions in place of `invariants:`.
> (The annotation grammar `@name(args)` was itself retired
> 2026-08-30 per decisions-frozen-2026-08-30.md —
> `@metadata` / `@deprecated` gone; kind info flows through
> body-shape inference; deprecation is a comment convention.
> The `@` sigil is a hard lex error at v1.0, which retires the
> **`alias @Name = ...`** user-alias mechanism (spec §4.16.16
> banner) — the samples below that say "declare `alias @X = ...`
> locally" are stale. User aliases as a mechanism are deferred to
> v1.x; use named types/methods directly at v1.0. R9 2026-08-31
> further retired user-defined operator overloading — no `alias
> @|> ...`, no user-declared operator glyphs of any kind.)
> Read
> code samples as PATTERN illustrations (the shape of the answer
> is what matters); consult [spec/spec-cxc-v1.0.md](spec/spec-cxc-v1.0.md)
> for current surface. A pattern-level syntax-refresh sweep
> remains a planned follow-up.
>
> **Further retirements not yet swept from the code sketches below:**
> - **`switch` / `case`** — retired (F69 2026-08-18 + Q12 2026-08-29).
>   Variant dispatch is expression-position `<:` receive-glyph blocks
>   with `|:` variant-arm guards (see `||:` multi-arm dispatch,
>   [[corvid-multi-arm-dispatch]]). Any `switch` / `case` in sketches
>   below is pattern illustration only.
> - **`Result[T, E]` / `Result of (T, E)`** — retired (Q11 2026-08-29).
>   Fallible operations raise typed exceptions recovered via
>   `or T():` arms (see [[corvid-failure-and-dispatch]]).
> - **`module <name>` header** — retired (F73 2026-08-20). File path
>   IS the module name; no header.
> - **FFI capability rename** — `Extern` retired 2026-09-01 (R11);
>   `Plugin` is the sole FFI capability. Any `use extern: Extern` or
>   prelude-shim references in sketches below should be read as
>   `use plugin: Plugin` provisioned inside a `def plugin:` module.

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

cxc does not ship `Result<T, E>` in the prelude. Use a domain-specific enum with a case per failure mode. Names read better; associated data per case is more expressive; metadata (§4.18) makes the shape inspectable.

```
def FileOpenOutcome:
    case handle(File)
    case not_found(path: String)
    case permission_denied(path: String)
    case volume_unavailable(volume: String)
```

If you're writing genuinely generic middleware over "any failable operation," define a `Failable<T>` protocol conformed to by your domain enums; middleware operates over the protocol. cxc does not push everyone toward a single `Result` wrapper.

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

`let` requires an initializer at declaration. For conditional initialization, use the expression-position multi-arm dispatch `||: <guard> : <body>` — the whole chain is the initializer.

```
let payload: Bytes = ||: self.needs_decompression : self.delegate.decompress(...) or Bytes(): frame.payload
                     ||: else                     : frame.payload
```

Same guarantee as Swift's definite-assignment analysis (assigned exactly once, then immutable), no separate mechanism. Reader sees "let = <one expression>" which happens to be a `||:` chain. See §3.8 (`||:` grammar, R6 2026-08-31) and §7.1.

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

cxc strings are grapheme-native (§3.10.5). No integer indexing (`s[5]` is a compile error). Positional access uses opaque `StringPosition` tokens (§3.10.7):

```
let pos = s.start_position()
let after_first = s.position(after: pos) ?? s.end_position()
let head = s.substring(from: pos, to: after_first)
let g = s.grapheme_at(position: pos)     # Char (grapheme cluster)
```

Swift's `s.startIndex`, `s.endIndex`, `s.index(after: i)`, `s[i..<j]`, `s.count` map to `s.start_position()`, `s.end_position()`, `s.position(after: i)`, `s.substring(from: i, to: j)`, `s.grapheme_count()`. Rationale: integer indexing over Unicode text admits three incompatible interpretations (bytes / code units / graphemes); cxc refuses to pick silently.

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

Value types (records, enums, primitives) carry canonical bytes as a **base-class property** as of v0.11 (§3.3, §3.4.6). The compiler synthesizes `function as(self) -> Bytes` and `class function from(source: Bytes) -> DecodeOutcome<Self>` from field/case layout at composition time, the same way it synthesizes structural equality and hashing. Structural conformance to `As<Bytes>` and `From<Bytes>` (§3.4) follows because the methods exist — no separate "auto-conformance" mechanism, no compiler awareness of specific target-type names. Canonical wire format spec (§3.10.10): network-order (big-endian) for multi-byte primitives, fields concatenated in declaration order, enums as discriminator + associated data, strings/collections as `UInt32 length || payload`.

```
record Point:
    x: Float
    y: Float
    # No user-written .as() needed. Compiler synthesizes:
    #   .as() -> Bytes           returns 8 bytes: [<x big-endian 4>, <y big-endian 4>]
    #   .from(source: Bytes)     returns DecodeOutcome<Point>

let p = Point(x: 1.0, y: 2.0)
let b: Bytes = p.as()
let p2 = Point.from(source: b)   # -> DecodeOutcome.converted(p)
```

Override the synthesized encoding by writing your own — per §3.3.2, own definitions win over inherited defaults. Missing `.as() -> Bytes` on a field's type → compile error at the record declaration with fix-it pointing at the field.

**Only bytes get the base-class property.** JSON, MsgPack, CBOR — no compiler synthesis. Those formats have per-record author choices (field naming, null handling, precision, enum spelling); silent choice would be a footgun. Users author the methods explicitly. Same principle as compile-error-on-narrowing (v0.10.1 Convertible retirement).

Rationale: `Codable`/`Serializable` reflection is the direction Java took and paid for repeatedly (serialization CVEs, class-shape drift breaking on-disk formats). Explicit `.as() -> Bytes` methods make the format visible in source; the trap doesn't compound.

```
class Message:
    id: String
    body: Bytes
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

**Named captures are mandatory.** Swift's `Regex("(\d+)")` unnamed group is a compile error in cxc; write `regex"(?<year>\d+)"`. Access via `match.group("year")`. Rationale: consistent with the named-only discipline used everywhere else (records, enum associated values, `accepts` signatures). The reader sees capture names inside the pattern source without cross-referencing consumer code.

**Compilation caching is automatic** when the pattern source is a compile-time constant — `regex"^\d+$"` compiles once at parse time. No `Regex.compile(...)` boilerplate. Runtime-built patterns (`Regex(user_input)`) are constructed per call.

**Extensibility.** Add PEG, fuzzy, Levenshtein, etc. by defining a class with `first_match` + `all_matches` — structural conformance to `Pattern` is automatic, no declaration required. Or declare it explicitly via header sugar `class PegPattern(Pattern):` / body-form `conforms: Pattern` (§3.4.5) to get compile-time validation at the class declaration. String methods pick them up either way.

```
class LevenshteinPattern:
    metadata: kind: regular
    needle: String
    max_distance: Int
    methods:
        function first_match(self, source: String) -> Match: ...
        function all_matches(self, source: String) -> Iterator<Match>: ...

# Pre-R1 (retired) — user prefix-literal alias:
# alias @fuzzy"<content>" raw = LevenshteinPattern(needle: <content>, max_distance: 2)
# s.first_position(matching: @fuzzy"aplle")

# v1.0 — call the constructor directly:
s.first_position(matching: LevenshteinPattern(needle: "aplle", max_distance: 2))
```

*(The `@name"content"` prefix-literal alias mechanism retired 2026-08-30 with the rest of the `@`-marker grammar; there is no user-declarable literal-form escape hatch at v1.0.)*

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

Domain-specific enums cover the common cases better. If you truly need a generic wrapper for cross-domain middleware (retry combinators, testing scaffolds), author your own `Result<T, E>` enum or define a `Failable<T>` protocol and write middleware over the protocol. cxc's stdlib does not ship one.

### "I need traits with `where` clauses"

`extend class Foo<T> where T: X:` (§3.3.2, v0.9.0). Reuses the same generic-parameter and where-clause syntax as function generics.

```
extend class Array<T> where T: UnsignedInteger, T: Bounded:
    methods:
        function saturating_sum(self) -> T: ...
```

Multi-constraint uses comma-separated where atoms — no `&` conjunction operator.

### "I need `impl<T: Trait> for Foo<T>` (Rust trait implementation for a generic type)"

`extend class Foo<T> where T: Trait:` (§3.3.2). Same shape. If the trait provides default methods, cxc's structural conformer inheritance (§3.4.4) means Foo<T> automatically inherits them when the where-clause is satisfied — no `impl` block needed; conformance is structural.

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

`As<T>` and `From<T>` in cxc (§3.10.3). Same shape and intent as Rust's pair:

- `As<T>` — projection direction (self → T). Method: `function as(self) -> T`.
- `From<T>` — construction direction (T → Self). Method: `class function from(source: T) -> Self`.

```
class Config:
    raw: Bytes
    methods:
        function as(self) -> Bytes: return self.raw               # As<Bytes>
        function as(self) -> String: return String.decoded(self.raw)  # As<String>
    class function from(source: String) -> Self:                     # From<String>
        return Self(raw: source.as())
```

Trait-qualified dispatch: multiple `as` methods differ by return type via the conformed `As<T>` instantiation, not by parameter overloading (§3.4.4). Call-site resolution: return-type context, turbofish (`.as::-Bytes>()`), or `As<Bytes>.as(x)`.

**Difference from Rust.** No blanket `impl<T, U> Into<U> for T where U: From<T>` — cxc does not auto-derive one direction from the other. Author both explicitly when both are meaningful. Rationale: projection and construction have distinct semantics; symmetric auto-derivation hides where information is lost or where construction may fail.

**Numeric narrowing lives OUTSIDE `As<T>`.** Prelude ships `As<T>` conformance only for **widening** numeric pairs (source range ⊆ target range — lossless). `Int32(300).as() -> UInt8` is a compile error; the diagnostic points at three narrowing methods on the source primitive:

```
Int32(300).wrapping_to::-UInt8>()      # -> UInt8(44)   modular per §3.9
Int32(300).saturating_to::-UInt8>()    # -> UInt8(255)  clamp
Int32(300).checked_to::-UInt8>()       # -> ConvertOutcome<UInt8>.overflow(...)
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

Use expression-position multi-arm dispatch: `x = ||: bar : foo() ||: else : baz`. First truthy guard wins; `else` is the catch-all. No ternary syntax; `if` is statement-only at v1.0 (R6, 2026-08-31 retired the `if X: Y else: Z` expression form).

See §3.8 (`||:` grammar).

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

Consumers who want brevity use a `from` import: `from com.example.ws import FrameCollectorEvent`. *(Pre-R1 sample: `alias @Event = com.example.ws.FrameCollectorEvent`. The `@`-marker alias mechanism retired 2026-08-30 — see top-of-file banner.)*

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

cxc commits to composition-over-inheritance for pure cxc code — there's no `extends` for regular classes. Use protocol conformance + composition (holding a reference to a helper class) instead.

The one exception is ObjC interop: subclassing UIViewController, NSObject, etc. is unavoidable when writing framework-facing code. That uses body-form `metadata: extends:` (never header syntax):

```
class MyViewController:
    metadata:
        extends: UIViewController                          # ObjC-only inheritance
    state:
        table_view: UITableView
    dynamic function viewDidLoad(self):
        super.viewDidLoad()
        self.table_view = UITableView()
```

Rules: `extends:` accepts one class, must be `kind: regular`, must be ObjC-imported. Pure cxc classes cannot be subclassed. `super.method()` dispatches to the extended class per §5.7.

**Deprecated in v0.10.2, removed v0.11**: the double-colon header form `class MyViewController: UIViewController:`. The parser emits a fix-it warning suggesting body-form migration.

See §3.4.5, §9.3.1.

### "I need generic collections"

Generics work. Constraints via `<T: Comparable>` on type parameters. Array is `[T]` (sugar for `Array<T>`), Dictionary is `{K: V}` (sugar for `Map<K, V>`), Set is `{T}` (sugar for `Set<T>`) — all prelude aliases.

See §3.10, §4.20.1.

### "I need `null`-safe navigation"

Every reference type in cxc allows nil. Message-send on nil is a no-op that returns nil (auto-lifted). Combine with `??` for defaults, `if let` for gating.

See §3.6.1.

### "I need `old(expr)` / `\old(expr)` / `x'` in a postcondition (from Dafny, JML, Eiffel, Ada, Spec#, Alloy)"

No `old(...)` form. Inside an `ensures` expression, `self` and parameter identifiers refer to the entry-state snapshot the contract runtime already had to capture; `result` refers to the post-state — the returned value for value-returning methods, or the receiver at method exit for void methods.

```
# Dafny/JML style:
# ensures count == old(count) + 1

# cxc:
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

Not in the prelude. Method chaining `x.f().g().h()` is cxc's native pipeline; universal method dispatch and cheap extensions/sections make it work at parity. Under R9 (2026-08-31) there is no project-local `@|>` escape hatch either — user code cannot declare operator aliases at v1.0. Projects wanting pipe-shaped composition build it as a named `.pipe(f)` method.

See §4.20.3 (paradigm-mismatch exclusions), §4.16.16 (R9 alias-@ retirement banner).

### "I need `do`-notation / for-comprehension for `Result`"

Not spec'd. Explicit `switch` on domain enums at each layer. Trade-off is the same as with Rust's `?` — cxc picks visible dispatch over cross-cutting propagation. If nesting exceeds 3 layers, decompose.

### "I need algebraic data types"

Tagged-variant defs — `def X:` with `case` alternatives at class-body top level. F69 body-shape inference picks the tagged-variant runtime treatment (retired the `kind: enum` / `enum` sugar keyword / `cases:` wrapper section — 2026-08-18). Discrimination via expression-position `<:` receive-glyph dispatch (F69 retired `switch` for `<:`), structural equality, tag + payload storage.

```
def Shape:
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

## cxc-native patterns

These emerge from cxc's structural commitments and are worth naming even if they don't correspond to a construct from another language.

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

The bootstrap grammar (§10) is minimal. Almost everything that looks like syntax is a **prelude alias**: `record`, `enum`, `protocol`, `[…]`, `{…}`, `..<`, `??`, `${…}`, and so on. The prelude is spec-committed (§4.20); users can inspect every alias to see exactly what it expands to. User-side surface extensions are not available at v1.0 — the `alias @name …` mechanism (v0.8.2 `@` marker discipline) retired 2026-08-30 (R1 annotation-grammar-retirement) and 2026-08-31 (R9 user-operator retirement). Language surface additions are compiler-team-owned at v1.0; user-facing composition happens through named types, methods, and `from … import …`.

### Names beat positions, uniformly

- No positional enum associated values (v0.8.1) — `case closed(reason: String, code: UInt16)`, not `case closed(String, UInt16)`
- No tuples, no positional destructuring (v0.8.3) — `Pair<A, B>` with `.first` / `.second`; domain records where a domain word applies
- No positional function arguments — call sites use named arguments matching parameter names
- Enum case discrimination in `switch` uses named field patterns

The one exception: `case .some(let x)` — single-associated-value shorthand inside `case` patterns only, no ambiguity possible.

### Class-associated types are flat

No nested type declarations (v0.8.4). Types conventionally associated with a class use `<ClassName><Role>` at module scope. Leading-dot shorthand (§3.7.2) makes use sites read cleanly regardless. Consumers who want brevity use `from <mod> import Event` to bring the flat name into module scope. *(Pre-R1 sample: `alias @Event = ...`; retired 2026-08-30.)*

### Before adding a mechanism, check what's already there

cxc's design has consistently dissolved would-be mechanisms into existing tools: `for` → `Iterable`, tuples → records, nested types → convention, `throws` → return type, `try/catch` → switch, `DispatchQueue` → contracts + `execution:`. The five-session pattern (v0.8.3–v0.8.6) is stable enough to be a principle:

> When you're tempted to add a new mechanism, first ask whether cxc's structural commitments (class-as-universal, nil-messaging inversion, contracts as coordination, universal method dispatch, sections, aliases) already cover the case via convention or composition.

Most of the time, they do.

---

## Atom-substrate dissolutions (v1.0 direction; §1.11, §1.12, §1.13)

The v1.0 target is the atom model with a small set of surface commitments. Each entry below shows a mechanism from a prior spec draft (or one you might reach for from another language) that dissolves under the atom substrate.

### "I need `emit(value)` to push a message from a handler"

Use the `:>` push-channel glyph, symmetric with the `:-` observable pull-channel:

```
def debounce(duration: Duration):
    use: clock: Clock
    state: pending = nil
    
    * <: input(event: Event) -> self
        then: pending => event
        do: clock.after(duration) |> fire

    -> fire
        do:
            :> pending           # emit on default channel
            pending => nil
```

Bare `:> value` emits on the default channel. `:> name(value)` emits on a named channel (required when the atom has a named-tuple return type). See §1.11 for the pull/push symmetry rationale.

### "I need `after N -> handler` for a delayed callback"

There is no `after` primitive. Time is a capability atom (`Clock`). Declare it in `use:` and use one of two forms — a time-guarded transition or a Delay/Throttle transformer:

```
# Time-guarded transition — atom transitions when the guard's time predicate fires:
use: clock: Clock

waiting when: clock.after(deadline)
-> ready
    do: :> timer_elapsed

# Or as a transformer inside a receive-glyph source expression:
running <: source_stream |> Throttle{interval: 100ms} {payload: T} -> running
    do: handler(payload: payload)
```

The `~>` time-deferred wire glyph is retired (2026-08-12). See §1.12 for time-as-capability rationale. Test-clocks, game-clocks, simulation-clocks are user-writable atoms conforming to `Clock` — the `std.clock` plugin provides the default `WallClock` (R11 2026-09-01); user code substitutes.

### "I need to compose transformers on a stream — where does `|>` go?"

`|>` composes anonymous transformer atoms inside a receive-glyph source expression:

```
# Single transformer:
running <: mouse.moves |> Debounce{duration: 200ms} {pos: Position} -> running
    do: cursor :> update(pos: pos)

# Multi-stage chain (reads left-to-right as data flow):
running <: mouse.moves
           |> Filter{predicate: on_screen}
           |> Debounce{duration: 200ms}
           {pos: Position}
-> running
    do: cursor :> update(pos: pos)
```

Each `|> Transformer{args}` is an anonymous sub-atom scoped to the enclosing phase. Alternative: name the transformer as a slot (`data: debouncer: Debounce = Debounce{duration: 200ms}`) when you want to inspect intermediate state or reuse the transformer across several receive sites.

**Retired:** free-standing wires at atom-body top-level (`source |> target` outside any transition). All subscriptions are receive-glyph transitions. See §1.11 "Stream composition in receive-glyph source expressions."

See §1.11 wiring-glyph table. The six glyphs are: `->` (return-type + transition arrow), `|>` (stream composition + sync send), `:-` (observable pull), `:>` (emit async), `<:` (receive async), `<|` (receive sync). Zero overlap.

### "I need `on event(...):` for a handler declaration"

Handlers are wildcard receive-glyph transitions — the same primitive as any other transition, no separate opener:

```
# NO:
on input(event):
    ...

# NO — the `-> input(event):` prefix (F14 sugar) is RETIRED at F67 L3:
-> input(event):
    ...

# YES — explicit wildcard handler-shape transition:
* <: input(event: Event) -> self
    do: ...
```

`* <:` reads as "from any phase, receive"; `-> self` reads as "stay in whatever phase we were in" (side-effect handler; no phase change). The typed-params receive form `input(event: Event)` distinguishes a handler from a payload-destructure receive `input{event: Event}` (which reads a stream/observable emission). See §1.11 class-body-collapse addendum.

### "I need `atom Foo:` to declare an atom / stateful class"

Use `def`. `atom` is the substrate concept (in prose); `def` is the syntax. `class`, `record`, `enum`, `protocol`, `program` remain as sugar keywords that desugar to `def` shapes.

```
def DebouncedCounter:
    use: clock: Clock
    state: count: Int = 0
    invariants: 0 <= count <= 100
    ...
```

See §2.5 keywords, §1.11 substrate.

### "I need `List<T>` / `Map<K, V>` / `Stream<T>` — generic type parameter brackets"

Use the `of` qualifier. `<T>` is retired at v1.0.

```
# NO:
Stream<Bytes>
List<Int>
Map<String, User>

# YES:
Stream of Bytes
List of Int
Map of (String, User)
```

Lowercase names in `of` position are shape variables bound in the enclosing signature scope by first-mention. TitleCase are concrete type references. Case convention IS the type-variable marker.

```
def dedupe:
    Stream of s -> Stream of s where s conforms Hashable
```

See §3.2 composite types, §1.11 v1.0 substrate.

### "I need `<T>` on a generic function or class"

You don't declare the type parameter. It's inferred structurally from body use (handler params, state assignments, emit calls). Constraints go in `where`.

```
# NO:
def map<A, B>(f: (A) -> B, s: Stream<A>) -> Stream<B>: ...

# YES:
def map(f: (a) -> b):
    Stream of a -> Stream of b
    * <: input(item: a) -> self do: :> f(item)
```

`a` binds first-mention in `f`'s input type; `b` binds first-mention in `f`'s return type; channel signature references both. No universal-quantifier syntax needed.

### "I need `channels:` section to declare multiple output channels"

Use the named tuple return type. Return type is authoritative; no separate section.

```
def partition(predicate: (a) -> Bool):
    Stream of a -> (matched: Stream of a, unmatched: Stream of a)

    * <: input(item: a) -> self
        do:
            if predicate(item):
                :> matched(item)
            else:
                :> unmatched(item)
```

Callers reference channels by name on the atom instance: `partitioner.matched |> handler_a`, `partitioner.unmatched |> handler_b`. See §3.2 multi-channel subsection.

### "I need `provide:` to give my children a shadowed capability"

Use `use <name>: <T>` with a construction expression. Source expression tells the composition checker where the value comes from:

```
def GameProgram:
    use clock: GameClock(tick_ms: 16)                        # constructed locally; provided to children
    use random: Random                                        # shape-only: inherited from parent env
    use fs: SandboxedFs(inner: fs, allowed: ["/data/**"])    # wraps parent's fs (outer `fs` resolves in parent env)
    use console: Console
```

Shape-only entry inherits from parent env; construction/expression entry is local and propagates to children. `provide:` was never committed — `use <name>: <T>` covers both roles. See §1.12 `use` declaration semantics.

### "I need `read_file(path)` / `println(x)` / `random()` / `sleep(ms)` — direct OS calls"

No language primitives touch the OS. Every OS-adjacent operation is a method on a capability atom in `env`. Declare the capability in `use:` and call it:

```
def MyProgram:
    use:
        fs: Filesystem
        console: Console
        random: Random
        clock: Clock
    
    -> start:
        let contents = await fs.open("/path/file", read).then(f -> f.read_all())
        console.write_line("Random: " + random.next_u32.to_string())
        clock.after(1s) |> tick_handler
```

Env provisions first-party plugin atoms (`std.fs`/PosixFs, `std.console`/PosixConsole, `std.random`/OsRandom, `std.clock`/WallClock — R11 2026-09-01) at program launch; alternatives (test, sandbox, remote) are user-writable atoms conforming to the same prelude shape. See §1.12 OS-boundary layering and §1.13 stdlib capability catalog.

### "I need `try/finally` for resource cleanup (files, sockets, threads)"

Use state-machine atoms with `-> before_destroy:` for RAII. Resources have natural lifecycles; the atom's active state controls which operations are valid:

```
def File:
    path: Path
    def unopened: pass
    def opened:
        handle: NativeHandle
        -> read(n: Int) -> Stream of Bytes: ...
        -> close: native_close(handle); :> closed()
        -> before_destroy: native_close(handle)    # RAII cleanup
    def closed: pass
```

Use-after-close and double-close fall out of the state-machine model (handlers only exist on states that own the resource). Auto-close on scope exit via `-> before_destroy:`. See §1.12 resource-lifecycle example.

### "I need FFI / C ABI bindings in my library"

You don't. User code accesses OS through typed capabilities (`Clock`, `Filesystem`, `Network`, `Console`, ...) — never through raw FFI. If a typed capability doesn't exist for what you need, contribute a plugin (first-party under `std.*` or third-party under your own namespace).

Under the hood: FFI is a single capability atom (`Plugin`) provisioned only to shim atoms inside modules that carry a top-level `def plugin:` marker (R11 plugin-only wave 2026-09-01, superseding same-day R10's short-lived Extern+Plugin split). Foreign symbol declarations use one scoped form: nested `foreign "..."` sections inside a `def plugin:` body (R7 2026-08-31). Plugin peer defs use `plugin.get("name")` / `plugin.send(recv, "sel", args...)` / `plugin.class_send("Class", "sel", args...)` against their own `def plugin:` body's `foreign "..."` blocks — cross-plugin symbol reach is grammatically impossible, and application code cannot request `Plugin` at all.

```
# ---- std/fs/darwin.cxc (first-party plugin; ships with compiler) ----

def plugin:
    name:    String!       = "std.fs"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign "libc":
        symbol "open":  (path: CString, flags: Int32, mode: Int32) -> Int32
        symbol "read":  (fd: Int32, buf: Ptr of Byte, len: SizeT) -> SsizeT
        symbol "close": (fd: Int32) -> Int32

def PosixFs:
    conforms: Filesystem                              # shape from prelude/shared/capabilities.cxc
    use plugin: Plugin                                # sole FFI capability at v1.0 (R11)
    native_open:  (CString, Int32, Int32) -> Int32       = plugin.get("open")
    native_read:  (Int32, Ptr of Byte, SizeT) -> SsizeT  = plugin.get("read")
    native_close: (Int32) -> Int32                        = plugin.get("close")
    -> open(path: Path) -> Future of (File | IOError):
        ...
```

**Direction inferred from symbol type shape.** `(Args) -> Ret` = outbound (cxc invokes; native impl). `Stream of T` = inbound (native invokes via runtime callback; cxc subscribes). Callbacks-with-return unsupported in v1.0. See §1.12 for the trust boundary and §1.14 for the (small) prelude structure and worked WallClock plugin example (both directions).

Rejected: `native def` keyword splits the type system into two atom categories. FFI-as-capability was rejected in an earlier draft on type-safety grounds but is now accepted under the plugin-typed form (types come from the plugin's own foreign declarations, resolved at compile time).

### "I need `Future<T>` / `Signal<T>` / `Promise<T>` for one-shot async values"

There aren't any. Async values are atoms whose lifecycle carries the "not ready → ready" transition; the atom holding the eventual value IS the shape. A caller who wants the value observes the atom (waits for a `:> completed(result)` emission, or reads a `result` field after the atom transitions to a done phase).

```
def Fetch:
    result: String! = nil

    -> loading
        then:
            # ... do the fetch ...
            self :> arrived(text)

    loading <: arrived(text: String) -> ready
        then: result => text

def main:
    fetch: Fetch = Fetch()
    -> waiting
    waiting <: fetch.arrived(text: String) -> done
        then: print(text)
```

No `Future[T]` type, no `await` keyword. The atom's phase IS the "is it ready" bit; the atom's emission IS the "here's the value" delivery. See §1.11 "Async lifecycle policies" for the nine-axis mapping.

### "I need to cancel a long-running operation"

Corvid does not preempt running transitions. Cancellation is cooperative: use `CancelToken` from the async prelude, and check it in your transition guards.

```
def Work(token: CancelToken):
    steps_done: Int! = 0

    -> running
        then: self :> step

    running <: step -> running
        |: not token.cancelled
        then:
            steps_done => steps_done + 1
            self :> step

    running <: step -> end
        |: token.cancelled

def main:
    token: CancelToken = CancelToken()
    worker: Work = Work(token: token)

    -> running
        then:
            # ... work runs concurrently ...
            |> token.cancel()
```

`CancelToken` has a `cancelled: Bool` field and a `cancel` message; sending the message flips the field. Every transition guard that reads `token.cancelled` gives the transition a chance to bail. A transition body that never reads it cannot be cancelled — this is the price of cooperative cancellation and the reason it never corrupts mid-write state.

Top-down (parent-held token, children observe), bottom-up (each child's own token, parent joins), and simultaneous (shared token graph) are all compositions of the same `CancelToken` — the library doesn't need three variants. See §1.11 "Async lifecycle policies" for the design rationale.

### "I need a `Deadline` — cancel after N milliseconds"

Compose `Clock` and `CancelToken`. Not a shipped atom — the composition is short enough to write per use case.

```
def Deadline:
    timer: Clock = Clock(ticks: 1)
    elapsed: Bool! = false

    -> waiting
        then: timer.every(delay_ms)

    waiting <: timer.tick -> waiting
        then: elapsed => true

    -> end
```

Constructor param `delay_ms: Int!` fills in via the same shape as any atom ctor. Callers read `deadline.elapsed` from a transition guard, just like `token.cancelled`. A worker that respects both:

```
running <: step -> end
    |: token.cancelled or deadline.elapsed
```

If a first-class Deadline shipped in the prelude, this is the shape it would have. It's small enough that leaving it in user code (or per-project) trades one indirection for zero library surface.

### "I need `Future<T>` result — start work, await value"

The pattern: a task atom does the work, publishes the result via a companion `:> completed(r: T)` emission, and terminates. The waiter subscribes to the emission.

```
def Task:
    result: Int! = 0

    -> running
        then: self :> compute

    running <: compute -> running
        then:
            result => 42
            :> completed(r: 42)

    -> end

def main:
    task: Task = Task()

    -> waiting

    waiting <: task.completed(r: Int) -> end
        then: print(r)
```

Two things worth calling out. **Payload args must be named** at both emit and receive: `:> completed(r: 42)` matches `<: task.completed(r: Int)`. Positional emit is a lower-time rejection. And **the atom holding the Task is the "future"** — there is no separate `Future[T]` type; the atom shape carries the "not ready → ready → value delivered" progression via its phase transitions and companion emissions.

For work that raises, no separate machinery: the transition body raises, the substrate fires the `<msg>_failed(reason:)` companion (§1.11 propagation rules), and the atom auto-nils in its slot. Subscribers observe both the success companion and the failure companion.

### "My program does its work and prints, but never exits"

Every atom's lifetime is scoped to its containing atom; `def main` is scoped to the program. **The process exits when `main` transitions to `end`, not when its `-> running then:` block finishes.** If `main` never reaches `end`, `dispatch_main()` keeps running forever — that's the actor-system model, not a bug.

Two common shapes:

```
# Exits on its own — main's -> running then: is the whole program.
# No message-triggered transitions on main, so no dispatch_main loop
# is emitted; main() returns normally after the then: block.
def main:
    token: CancelToken = CancelToken()
    -> running
        then:
            |> token.cancel()
            print(token.cancelled)

# Exits when the awaited event arrives. main has a message-triggered
# transition, so dispatch_main runs; the -> end target routes through
# -transitionToEnd which fires cxc_shutdown() → exit(0).
def main:
    task: Task = Task()
    -> waiting
    waiting <: task.completed(r: Int) -> end
        then: print(r)
```

If your program prints "done" and hangs, look for a message-triggered transition on main with no path to `-> end`. Add one — often via observing the completion emission of a sub-atom, or via a `CancelToken` combined with a `<: token.cancel -> end` transition.

### "I need a `Scope` / `Nursery` for structured concurrency"

Hold children as fields (static shape) or as a `List of Worker` (dynamic membership). Cascade destruction (§1.11) already ends them when the parent ends — post-order, synchronous.

```
# Static shape: fixed set of children.
def Boss:
    a: Worker = Worker(id: 1)
    b: Worker = Worker(id: 2)

    -> running
    -> end       # cascade: b ends, a ends, Boss ends

# Dynamic shape: variable-count children.
def Team:
    workers: List of Worker = [Worker(id: 1), Worker(id: 2), Worker(id: 3)]

    -> running
    -> end       # cascade: each worker ends in list order, Team ends
```

No shipped `Scope` atom. The substrate answer is: the parent atom IS the scope. `end` is the scope-exit point. Cascade IS the destruction policy — currently "cancelled destruction" in the Brown taxonomy (mid-transition work in children is dropped when cascade fires). If you need "awaited destruction" (parent waits for children to complete their in-flight work), the children have to cooperate: parent signals via a `CancelToken`, children finish their unit of work and self-transition to `end`, parent observes the slot going nil before its own `-> end`.

Trio-style nurseries (spawn tasks inside a `with` block, aggregate exceptions on exit) don't exist as a first-class construct because the mechanism is already there — a compound atom with sub-atom slots is a nursery. See §1.11 "Async lifecycle policies" for the destruction axis and the rationale for cooperative vs. preemptive teardown.

### "I need an `entrypoints:` section to declare my public API"

Every receive-glyph transition is exposed by default. There is no `entrypoints:` section. External callers, internal wires, and any message sender all reach handlers the same way — a handler is a message name in the atom's dispatch table; who sends is composition context.

```
# NO — special section:
entrypoints:
    take_damage(amount: Int) |> active.damage(amount)

# YES — wildcard handler-shape transition:
* <: take_damage(amount: Int) -> self
    do: self :> damage(amount: amount)
```

If a truly private handler is ever needed, some form of visibility marker (e.g. `private * <:`) would be an escape hatch — no such need has surfaced in the design so far. See §1.11 class-body-collapse addendum.

### "I need a `wires:` section for the compound atom's internal wiring graph"

There is no `wires:` section and no free-standing wires anywhere. Every subscription is a receive-glyph transition on the atom that consumes the stream:

```
def Game:
    use:
        clock:    Clock
        keyboard: KeyboardInput
    
    paddle:  Paddle        = Paddle{}
    player:  Player        = Player{}
    spawner: EnemySpawner  = EnemySpawner{}
    
    # No `wires:` section, no top-level `|>`. Each stream we consume is a
    # receive-glyph transition, one per handler.
    running <: clock.tick{dt: Duration}      -> running do: paddle :> tick(dt: dt)
    running <: clock.tick{dt: Duration}      -> running do: spawner.active_enemies.for_each(e => e :> tick(dt: dt))
    running <: keyboard.pressed{key: Key}    -> running do: player :> pressed(key: key)
    running <: keyboard.released{key: Key}   -> running do: player :> released(key: key)
    
    running <: quit_requested -> end
```

With inline stream composition when a transformer is needed:

```
# Debounce mouse moves without a named intermediate:
running <: mouse.moves |> Debounce{duration: 200ms} {pos: Position} -> running
    do: cursor :> update(pos: pos)
```

**Retired (2026-08-12):** free-standing `|>` at atom-body top-level (standing wires). Replaced by receive-glyph transitions with optional inline transformer chains in the source expression. See §1.11 "Stream composition in receive-glyph source expressions."

**Retired (2026-08-12):** `-> handler |> wire_expr` one-liner handler bodies. Handler bodies must be multi-line blocks; stream transformation lives in receive-glyph source expressions.

**On `-> setup:` / `-> enter:` retirement (earlier).** Retired in favor of the universal `init` phase. Field initialization happens in the wildcard on-enter (`-> <first phase>`) transition.

### "I need `active.hp` / `active.close()` to reference the currently-active state atom"

`active` was retired. `self` is the sole atom-self reference. In compound atoms with nested state atoms, message dispatch **auto-delegates** — the compound checks its own handler table first; if no match, it dispatches to the currently-active nested state atom's handlers.

```
# NO — active pronoun:
file.active.read(1024)
active.hp -= amount

# YES — bare dispatch on self, auto-delegates to active state:
file.read(1024)                # if file's active state is `opened`, resolves to opened.read
self.hp -= amount              # inside a state atom's handler, `self` is that state atom
```

Pattern-match to narrow when needed: `if file is opened: file.read(1024)`. See §1.12 auto-delegation semantics.

### "I need to declare that my atom implements the `Filesystem` capability shape"

Optional. Structural conformance works without any declaration — an atom conforms to a shape by having the right methods. Add `conforms: Filesystem` for documentation and explicit compiler shape-match check. If you omit it and the compiler notices a likely match, it emits a warning suggesting the declaration.

```
def PosixFs:
    conforms: Filesystem       # optional, documentation-only
    ...
```

Retires the "you must declare conformance or the compiler won't accept your atom as an env.fs binding" ceremony. See §1.12 conforms-optional framing.

### "My atom needs a mutable field — how do I model this?"

One question at the top: **does this state need its own lifecycle — phases, queueing, interruption, or independent concurrent activity?**

**Yes → sub-atom in a nullable slot.** See the "state has phases with lifecycle" entry above. Sub-atom carries its own transitions, deconstructs on `end`, slot auto-nils. Example: Player's `action: ActionMachine` for a fireball cast (nullable by default under the unified rule — `T?` is banned).

**No, but the atom discriminates behavior across discrete phases → phases on the current atom, fields at compound scope, wildcard on-enter (`-> phase`) for phase-determined values.** Phases are inferred from transition arrows; no `def phase` declarations. On-enter transitions carry per-phase constants:

```
def Paddle:
    max_speed: Float = 500.0
    y:         Float = 0.0
    velocity:  Float = 0.0
    
    # Phases idle / up / down inferred from arrows. Wildcard on-enter
    # sets phase-determined velocity — no enum field, no observable indirection.
    -> idle then: velocity => 0.0
    -> up   then: velocity => -max_speed
    -> down then: velocity => +max_speed
    
    idle <: pressed{key: Key.arrow_up}    -> up
    idle <: pressed{key: Key.arrow_down}  -> down
    up   <: released{key: Key.arrow_up}   -> idle
    down <: released{key: Key.arrow_down} -> idle
```

**No, the state just accumulates or updates on events → self-transition with `then:`.** Nothing special; every atom does this:

```
def Paddle:
    y: Float = 0.0
    
    self <: tick{dt: Duration} -> self
        then: y => clamp(y + velocity * dt.to_seconds(), -screen_bounds, +screen_bounds)
```

The `then:` block is **not** assignment — `y => clamp(...)` reads as "y becomes clamp(...)" (functional update). Unlisted fields carry forward from source self. Whole transition is pure: `state → message → new_state`. Assignment operators (`=`, `+=`, ...) on `data:` fields are a syntax error at every scope.

**Retired**: the earlier Kind 1a / Kind 1b / Kind 2 taxonomy. It required a decision tree ("distinct-shape vs same-shape phases?") every time you added a state field. Under the current model:

- Kind 1a "distinct-shape phases with per-phase field declarations" → per-phase declarations were hiding mutation as staticness; sub-atoms in slots replace them cleanly for real lifecycle-carrying state; simple phase discrimination uses compound-scope fields.
- Kind 1b "enum + derived observable for same-shape phases with constants" → phases + wildcard on-enter does this directly; no enum-in-field + observable indirection.
- Kind 2 "continuous accumulator" → was never really a distinct kind; every atom updates fields via self-transitions.

The single question above replaces the taxonomy entirely.

**Anti-patterns the compiler may hint on**:
- Per-phase field declarations (`def alive: hp: Int = 100`) — lift `hp` to compound scope; the phase entry transition sets it via `then:`.
- Union-typed slots for state discrimination (`action: Idle | FireballCasting | MeleeAttack`) — that's a state machine wearing a union costume; lift into a sub-atom (ActionMachine) in a nullable slot.
- Enum-in-data-field + switch observable for phase-determined constants — the phase itself is the discriminator; use phases + wildcard on-enter.

### "How do I update a compound-scope field from a nested-state transition?" (cross-scope updates)

Two idiomatic forms, both immutable.

**Form 1 — `then:` on the phase transition itself.** Both target-scope and compound-scope updates land in one `then:` block; lexical name resolution routes each field to its scope:
```
alive -> firing_fireball
    when: cast_fireball() and can_cast and mana >= cost
    then:
        mana         => mana - cost       # firing_fireball.mana (target scope)
        last_cast_at => clock.now         # Player.last_cast_at (compound scope)
    do:   :> fireball_launched
```

**Form 2 — Emit + listen.** The phase transition emits an event; a separate compound self-transition listens for the event:
```
alive -> firing_fireball
    when: cast_fireball() and ...
    then: mana => mana - cost
    do:   :> fireball_launched

self -> self                              # compound self-transition
    when: fireball_launched()
    then: last_cast_at => clock.now
```

Form 1 is more compact when the update is intrinsic to the phase change. Form 2 is clearer when the compound-scope update is a semantic consequence that other listeners should also see (multiple subscribers to `fireball_launched`, testing the compound-transition in isolation, etc.).

**Explicit path escape.** When a name is ambiguous (shadowed between scopes) or when you want to make scope visible for reader clarity, use qualified paths:
```
then:
    Player.last_cast_at   => clock.now      # explicit compound-scope
    firing_fireball.mana  => mana - cost    # explicit target-scope
```

Cross-scope writes via assignment (`Compound.field = value`) are a syntax error — there is no assignment anywhere in the language. Field updates happen exclusively through `then:` with lexical resolution and explicit-path escape.

### "My atom's state has phases with lifecycle — casting a fireball, queueing actions, an interruptible activity"

Sub-atom in a nullable slot. Not a union-typed slot, not per-phase field declarations.

State-machine-shaped things — anything with phases, transitions, potential for queueing, interruption, or independent concurrent activity — belong in their own atom. The parent holds a nullable slot; the sub-atom carries its own transitions, its own fields, its own lifecycle. When the sub-atom reaches `end`, it deconstructs and the slot auto-nils.

```
def Player:
    action: ActionMachine = nil    # nullable slot; nil = no action running
    
    alive <: cast_fireball
    -> alive
        when: action.is_idle and mana >= cost
        then:
            mana   => mana - cost
            action => ActionMachine(clock: clock)   # instantiate into slot
        do:
            action :> start_cast(target: cursor_position, cast_time: 500ms)
    
    # React to sub-atom completion event
    alive <: action.cast_completed{target: Position}
    -> alive
        do: :> fireball_flew(target: target)

def ActionMachine(clock: Clock):
    fireball_target:    Position  = nil
    cast_started_at:    Instant   = nil
    
    is_idle :- self is idle    # stable predicate for parents
    
    idle <: start_cast{target: Position, cast_time: Duration}
    -> casting_fireball
        then:
            fireball_target => target
            cast_started_at => clock.now
    
    casting_fireball
    when: clock.after(cast_started_at + cast_time)
    -> idle
        then: [null the fireball fields]
        do: :> cast_completed(target: fireball_target)
    
    casting_fireball <: cancel_cast
    -> idle
        then: [null the fireball fields]
        do: :> cast_canceled
```

**Why not a union type (`Idle | FireballCasting | MeleeAttack`)?** Union-typed slots that discriminate "which state is the thing in" are state-machines-in-disguise. You end up reinventing queue fields, cancel flags, interruption logic — all state-machine machinery, poorly. Real state machines (atoms) get channel-based queuing, interruption via messages, independent lifecycle, and testability for free.

**Heuristic**: if the "variants" of your candidate union type have transition rules between them (idle → in-progress → done), it's a state machine — promote to a sub-atom. Union types remain the right call for genuinely-polymorphic disjoint values (message payloads, result outcomes) where variants have no natural progression.

See §1.11 (sub-atom slots, sub-atom coordination patterns).

### "How does a parent atom coordinate with a sub-atom in a slot?"

Four patterns, each with its own syntax:

**1. Command down** — parent sends messages to sub:
```
do: action :> start_cast(target: pos, cast_time: 500ms)
```
Method-call syntax on the slot; sugar for message emission on the sub-atom's named channel. Nil-safe: sending to a nil slot is nil-punned (no-op), consistent with §3.6.1.

**2. Event up** — parent reacts to sub's emissions:
```
alive <: action.cast_completed{target: Position}
-> alive
    do: :> fireball_flew(target: target)
```
Receive glyph in the transition header; matches the sub's `:> cast_completed(...)` emission.

**3. State query** — parent reads sub's state via observable predicates:
```
# In sub-atom (ActionMachine):
is_idle :- self is idle

# In parent (Player):
alive <: cast_fireball
-> alive
    when: action.is_idle and ...
```
Expose stable predicates from the sub-atom; parents check the predicate, NOT the raw phase name. Insulates parents from internal phase renames in the sub-atom.

**4. Continuous stream from sub to parent** — sub's output feeds a parent transition on every emission. Same mechanism as event-up (receive-glyph transition on the sub's emission), optionally with a transformer chain in the source expression:
```
# Every progress update from action feeds a UI-update transition on parent:
alive <: action.cast_progress{pct: Float}
-> alive
    do: self :> update_cast_ui(pct: pct)

# With a transformer (throttle to 10 Hz):
alive <: action.cast_progress |> Throttle{interval: 100ms} {pct: Float}
-> alive
    do: self :> update_cast_ui(pct: pct)
```

See §1.11 (sub-atom coordination patterns).

### "I need to fire something when my atom is created / destroyed (constructor / destructor)"

Use the universal implicit phases `init` and `end`.

**`init`** is the constructor. Every atom has it; every atom's first transition is the implicit `init -> [first-mentioned target phase]`. Populate initial field values via wildcard on-enter for the first phase:

```
def Player:
    hp: Int = 0            # value set by init transition, not this default
    mana: Int = 0
    
    -> alive then:            # wildcard on-enter for alive; matches init transition
        hp   => 100
        mana => 50
```

The `= 0` on the field declaration is placeholder; the init transition's `then:` sets the real initial value. Compiler may hint that `= 0` is dead if a wildcard on-enter for the initial phase always sets it.

**`end`** is the destructor. Any transition targeting `end` deconstructs the atom — the transition's `do:` block runs (last chance for cleanup / emit), then the atom is destroyed. Nullable slots holding the atom auto-nil:

```
def Enemy:
    health: Int = 100
    
    alive <: take_damage{amount: Int}
    -> dead
        when: health <= amount
        do: :> died
    
    dead -> end                # transitions from dead to end after emitting `died`
```

Parent atoms detect sub-atom completion via `is nil` on the slot, or by listening for the emit-in-`do:` before destruction.

`end` transitions may have `when:` and `do:` — but NOT `then:` (the atom's state is being discarded). Cascade behavior: parent `-> end` recursively transitions sub-atoms in slots to `end`.

See §1.11 (universal implicit phases).

### "I need blocking vs non-blocking message sends"

Sender-side and receiver-side blocking are **two independent axes**. Any sender glyph pairs with any receiver glyph.

**Sender axis** — does my transition wait for the send to complete?
- `atom :> msg(args)` — async (fire-and-forget). Sender continues immediately.
- `atom |> msg(args)` — sync (processing ordering). Sender's transition suspends until the receiver's transition on this message has fired. **No reply value** — pure ordering guarantee. If you need data back, wire an explicit reply channel (`:>` request, `<:` response).

**Receiver axis** — does my transition park waiting for this message?
- `source <: msg{args} -> target` — async receive. Non-blocking pickup during phase; other transitions in the phase can fire on their own triggers meanwhile.
- `source <| msg{args} -> target` — sync receive. Transition parks specifically on this message; other transitions in the phase are suspended until it arrives.

**Four legal combinations:**

| Sender | Receiver | Semantics |
|---|---|---|
| `:>` | `<:` | Classic actor — fire-and-forget, mailbox pickup |
| `:>` | `<\|` | Producer-consumer — sender queues, consumer parks between messages |
| `\|>` | `<\|` | Rendezvous — sender waits for processing, receiver parked ready |
| `\|>` | `<:` | Sender waits for processing, receiver dispatches async within its phase |

**Default async, reach for sync only when needed.** Most game/event/notification flows are async — sender emits, receiver processes when able. `|>` is for cases where the sender needs ordering-with-processing before proceeding (e.g., "make sure this cancel was accepted before I continue"). Sync sends can deadlock in circular-send patterns (A `|>` B while B is parked on `|>` back to A) — user discipline; no language-level cycle detection.

**Why `|>` carries no reply value.** In this substrate, receive sites are phase-scoped — a `<:` for reply-message R exists only in the phase where it's declared. If `|>` baked in a reply obligation, the compiler would have to prove receiver's reply channel is active at receive time, a phase-dependent property that can only be checked by inspecting receiver's current phase. Keeping `|>` as ordering-only sidesteps that class of check. Reply, if any, is just another wire, living in whichever phase declares it.

**Buffered async — modifier on the receive glyph.** Bare `<:` drops unmatched messages. For queueing:
```
def ActionMachine:
    idle <:fifo(cap: 8) start_cast{...}       # queue up to 8 pending casts
    -> casting_fireball
        ...
```
Other modifiers: `<:priority`, `<:sliding(cap: 1)`. See §1.11 channel-semantics table.

### "Should I write `self is alive or self is dead` in `invariants:`?"

No. **Exhaustiveness invariants on phases are redundant** — phases inferred from transition arrows enumerate the full phase set at compile time; enum-valued fields' types do the same. No code path can produce a phase outside the declared set, so the runtime check evaluates to `true` on every dispatch and adds no safety.

```
# NO — restates what the compiler already knows:
def Player:
    hp: Int = 100
    mana: Int = 50
    
    # Phases alive/dead inferred from arrows below
    -> alive then: hp => 100; mana => 50
    alive -> dead when: hp <= 0
    
    invariants:
        self is alive or self is dead    # tautology — compiler already enforces

# YES — either omit `invariants:` entirely, or use it for constraints the type
# system doesn't know about:
def Player:
    hp: Int = 100
    mana: Int = 50
    last_cast_at: Instant = Instant.epoch
    
    invariants:
        last_cast_at <= clock.now    # temporal constraint — not knowable from types
        hp >= 0                      # bound not knowable from Int type
```

Compound-scope invariants apply across all phases. If a constraint is only meaningful in one phase (e.g., mana ceiling only during a cast), lift the phase-specific state into a sub-atom slot and put the invariant on the sub-atom.

Rule of thumb: if the invariant is a predicate the compiler could derive from types alone, delete it. Reserve `invariants:` for semantic constraints beyond the type system (bounds, temporal relations, cross-field consistency).

### "I need to write a constructor to initialize my data fields from arguments"

You don't — constructor params ARE data fields. `def File(path: Path):` desugars to `def File: data: path: Path` plus an auto-synthesized constructor that accepts `path` as a named argument.

```
# NO — separate data field + explicit constructor:
def File:
    path: Path
    constructor(path: Path):
        self.path = path

# YES — constructor param IS the data field:
def File(path: Path):
    # `self.path` is now available
    ...
```

Explicit `constructor:` section remains legal when custom logic is needed (validation, derived fields, side effects at construction). Auto-synthesis covers the common case. See §1.11 atom-body vocabulary.

---

## Cross-references

- **Full spec:** `spec/spec-cxc-v1.0.md`
- **Positioning:** `positioning/overview.md`
- **Examples:** `examples/README.md`
