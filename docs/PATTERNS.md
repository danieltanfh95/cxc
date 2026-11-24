# PATTERNS.md What cxc does when other languages would do X

**Purpose**

cxc dissolves many constructs that other languages ship as separate mechanisms. Each dissolution has one direct replacement shape.

**How to read**

Open the linked spec section when you need the normative rule.

**Spec authority**

The spec at [docs/spec/spec-cxc-v1.0.md](spec/spec-cxc-v1.0.md) is normative.

---

## Coming from Swift or Objective-C

### Serial queue for thread safety

**Foreign**

```swift
final class FrameCollector {
    private let queue = DispatchQueue(label: "frames")
    private var frameCount = 0
    func add(_ frame: Frame) {
        queue.async { self.frameCount += 1 }
    }
}
```

**Corvid**

```elixir
def FrameCollector:
    buffer_len: Int! = 0
    frame_count: Int! = 0
    |: frame_count >= 0

    idle <: add_frame(payload_len: Int) -> idle
        |: payload_len > 0
        buffer_len => buffer_len + payload_len
        frame_count => frame_count + 1

    total :- frame_count

def main:
    use console: Console
    c: FrameCollector! = FrameCollector()

    -> running
        |> c.add_frame(payload_len: 4)
        console.write(c.total.to_string())
```

**Note**

Write one atom for state that needs serial access. cxc runs one transition at a time on each atom mailbox. Add `|:` propositions for bounds that cannot be expressed by the field types. Read [§1.9 Contracts as coordination primitives](spec/spec-cxc-v1.0.md) and [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for the full rule.

### Main actor annotation

**Foreign**

```swift
@MainActor
final class UIController {
    func refresh() { }
}
```

**Corvid**

```elixir
# aspirational, see spec §8.4
```

**Note**

Main-actor placement is specified but unimplemented. cxc names thread placement in spec §8.4 and cxc builds single thread programs only. Write a plain atom for the controller logic today. Add no placement marker. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for the shipped atom shape.

### Throwing call with catch blocks

**Foreign**

```swift
do {
    let text = try String(contentsOfFile: "/tmp/config.txt")
    console.write(text.to_string())
} catch {
    console.write("missing")
}
```

**Corvid**

```elixir
def main:
    use console: Console
    use fs: FileSystem

    -> running
        let size: Int = fs.size("/tmp/cxc-patterns-missing.txt")
        or IOError(reason: let _r):
            -1
        console.write(size.to_string())

    -> end
```

**Note**

Call the fallible method directly at the recovery site. Attach one `or IOError` arm beside the binding that can raise. Write the fallback value inside the arm body. Read [§3.5.4 Failure handling](spec/spec-cxc-v1.0.md) for raise and recovery semantics.

### Generic result wrapper

**Foreign**

```swift
enum FileOutcome {
    case handle(String)
    case missing(String)
}
```

**Corvid**

```elixir
def Outcome:
    case ok(value: Int)
    case failed(reason: String)

def main:
    use console: Console
    -> running
        let m: Outcome = Outcome.ok(value: 7)
        m:
            <: ok(value: let v):
                console.write(v.to_string())
            <: failed(reason: let r):
                console.write(r.to_string())

    -> end
```

**Note**

Define one domain enum for each outcome family that needs inline dispatch. Dispatch on the returned value with a `<:` block at the call site. Reach for a typed raise instead when failure must unwind through several layers. Read [§3.5.4 Failure handling](spec/spec-cxc-v1.0.md) and [§3.7 Pattern matching](spec/spec-cxc-v1.0.md) for both paths.

### Optional chaining

**Foreign**

```swift
let name = user?.profile?.displayName ?? "Anonymous"
```

**Corvid**

```elixir
def Counter:
    raw_n: Int = 5

    n :- raw_n

def Wrapper:
    raw_c: Counter = nil

    c :- raw_c

def main:
    use console: Console
    -> running
        console.write(((Wrapper().c.n ?? 0)).to_string())

    -> end
```

**Note**

Write plain dot access on nullable receivers. cxc returns the zero value when a scalar read lands on nil. Append `?? fallback` when the caller needs a domain default instead of zero. Read [§3.6.1 Nullability and nil punning](spec/spec-cxc-v1.0.md) and [§5.7 Nil receiver dispatch](spec/spec-cxc-v1.0.md) for the exact rules.

### Force unwrap

**Foreign**

```swift
let value = maybeValue!
```

**Corvid**

```elixir
def main:
    use console: Console
    use panic: SystemPanic

    -> running
        let maybe: Int = 3
        let value: Int! = maybe ?? panic("value required")
        console.write(value.to_string())
```

**Note**

Write `?? panic` with a reason string at the exact site that cannot continue. Declare `use panic: SystemPanic` inside the enclosing atom before any `panic` call. Keep the verbose form because the process halts at that line. Read [§3.6.1 Nullability and nil punning](spec/spec-cxc-v1.0.md) for the sanctioned shape.

### Keypath style predicate matching

**Foreign**

```swift
switch character {
case \.isNewline: return .lineBreak
default: return .other
}
```

**Corvid**

```elixir
def Kind:
    case line_break
    case other

def Classifier:
    use console: Console
    idle <: classify(is_newline: Bool) -> idle
        |: is_newline
        console.write("line")

    idle <: classify(is_newline: Bool) -> idle
        console.write("other")

def main:
    c: Classifier! = Classifier()

    -> running
        |> c.classify(is_newline: true)
        |> c.classify(is_newline: false)
```

**Note**

Write one guarded transition per predicate and one unguarded fall through arm last. cxc tries arms in source order and fires the first arm with a true guard. Read [§3.7 Pattern matching](spec/spec-cxc-v1.0.md) for dispatch order and exhaustiveness.

### Mutable buffer parameter

**Foreign**

```swift
func writeUInt16(inout buffer: [UInt8], value: UInt16) {
    buffer.append(UInt8(value >> 8))
}
```

**Corvid**

```elixir
def Buffer:
    bytes: List of Int = []

    idle <: append_pair(hi: Int, lo: Int) -> idle
        bytes => bytes.append(hi).append(lo)

    count :- bytes.count

def main:
    use console: Console
    b: Buffer! = Buffer()

    -> running
        |> b.append_pair(hi: 190, lo: 239)
        console.write(b.count.to_string())
```

**Note**

Hold the buffer inside one atom and stage each update with `=>`. Pass plain values into the transition and let the owner atom apply `.append`. Read [§3.10 Collection literal conformance](spec/spec-cxc-v1.0.md) for container methods and value semantics.

### Conditional initialization

**Foreign**

```swift
let payload: Data
if needsDecompression {
    payload = decompress(frame)
} else {
    payload = frame.payload
}
```

**Corvid**

```elixir
def main:
    use console: Console
    -> running
        let needs: Bool = true
        let raw: Int! = 5
        let payload: Int! = ||: needs : raw * 2
                            ||: else  : raw
        console.write(payload.to_string())
```

**Note**

Write one `||:` chain as the whole initializer. Give the binding one explicit type and one arm per guard. End the chain with one `else` arm that always matches. Read [§3.8.1 Multi arm dispatch](spec/spec-cxc-v1.0.md) for arm typing rules.

### Guard with early exit

**Foreign**

```swift
guard let user = auth.currentUser else {
    return .unauthenticated
}
```

**Corvid**

```elixir
def main:
    use console: Console
    -> running
        let maybe: Int = nil
        let label: String! = ||: maybe == nil : "guest"
                             ||: else       : "member"
        console.write(label.to_string())
```

**Note**

Branch with `||:` when the caller needs a value from the check. Send `self :> next` with a guard when the caller needs to leave the current transition path. Read [§3.6.1 Nullability and nil punning](spec/spec-cxc-v1.0.md) for nil checks that compile today.

### Extension on an existing type

**Foreign**

```swift
extension String {
    func shout() -> String { uppercased() }
}
```

**Corvid**

```elixir
# aspirational, see spec §3.3.2
```

**Note**

Extension declarations are specified but unimplemented. cxc describes extension composition in spec §3.3.2 and cxc accepts methods only inside the owning `def` body. Add the observation inside the owning atom or record today. Inline the derivation at call sites when the owner lives in another module. Read [§3.3.2 Extension declarations](spec/spec-cxc-v1.0.md) before designing around reopened types.

### String index positions

**Foreign**

```swift
let first = s[s.startIndex]
let next = s.index(after: s.startIndex)
```

**Corvid**

```elixir
# aspirational, see spec §3.10.7
```

**Note**

Opaque string positions are specified but unimplemented. cxc specifies `StringPosition` tokens in spec §3.10.7 and cxc exposes integer helpers such as `.code_at` and `.length()` only. Use `.code_at` for ASCII unit reads today. Avoid integer indexing into grapheme text in new code. Read [§3.10.5 The String type](spec/spec-cxc-v1.0.md) and [§3.10.7 The StringPosition type](spec/spec-cxc-v1.0.md) for both layers.

### Byte views on strings

**Foreign**

```swift
let bytes = Array("hello".utf8)
```

**Corvid**

```elixir
def main:
    use console: Console
    -> running
        let bs: Bytes = "hello".to_bytes()
        console.write(bs.length.to_string())
        console.write(bs.to_string())

    -> end
```

**Note**

Call `.to_bytes()` for the UTF 8 byte form and `.to_string()` for the return trip. Read the byte count from the `length` field on `Bytes`. Read [§3.10.4 The Bytes type](spec/spec-cxc-v1.0.md) and [§3.10.5 The String type](spec/spec-cxc-v1.0.md) for conversion coverage.

### Mutable byte buffer type

**Foreign**

```swift
var data = Data()
data.append(0x48)
```

**Corvid**

```elixir
def Blob:
    raw: String = "hi"

    size :- raw.length()

def main:
    use console: Console
    -> running
        console.write((Blob().size).to_string())

    -> end
```

**Note**

Store raw text in one field and derive byte counts with observations. Convert with `.to_bytes()` at the boundary that needs `Bytes` values. Treat bulk byte mutation methods beyond conversion as capability surface that needs a compile check per method. Read [§3.10.4 The Bytes type](spec/spec-cxc-v1.0.md) for the shipped shape.

### Automatic JSON encoding

**Foreign**

```swift
struct Point: Codable { var x: Double; var y: Double }
let data = try JSONEncoder().encode(Point(x: 1, y: 2))
```

**Corvid**

```elixir
def Point:
    x: Int = 0
    y: Int = 0

def main:
    use console: Console
    use json: Json

    -> running
        let enc: String = json.encode_str("cxc")
        console.write(enc.to_string())
        console.write((Point(x: 1, y: 2).x).to_string())

    -> end
```

**Note**

Write explicit `json` capability calls at the boundary. Keep records as plain field lists and format fields by name at the print or encode site. Treat synthesized canonical bytes from spec §3.4.6 as aspirational until a compile check proves the path. Read [§3.4.6 Canonical bytes](spec/spec-cxc-v1.0.md) and [§1.14 Prelude](spec/spec-cxc-v1.0.md) for the boundary between spec and shipped helpers.

### Regular expression matching

**Foreign**

```swift
let found = s.contains(/^\s*\/\/.*$/)
```

**Corvid**

```elixir
# aspirational, see spec §3.10.9
```

**Note**

Pattern objects are specified but unimplemented. Spec §3.10.9 specifies `Regex`, `Glob`, and `Literal` conformers. The compiler ships string predicates (`.contains`, `.starts_with`) only. Use string predicates for literal checks today. Construct no pattern literal because cxc retired prefix literal aliases with the annotation grammar. Read [§3.10.9 The Pattern subsystem](spec/spec-cxc-v1.0.md) for the specified shape.

---

## Coming from Rust

### Question mark error propagation

**Foreign**

```rust
fn process(input: &str) -> Result<Receipt, Error> {
    let v = parse(input)?;
    let clean = validate(v)?;
    Ok(save(clean)?)
}
```

**Corvid**

```elixir
def MyError:
    msg: String

def Step:
    -> running

    running <: pulse -> running
        raise MyError(msg: "boom")

def main:
    use console: Console
    r: Step = Step()

    -> running
        |> r.pulse()
        or MyError(msg: let m):
            console.write(m.to_string())

    -> end
```

**Note**

Raise one typed record at the failure site. Recover with one `or` arm at the ancestor that owns the fallback decision. Split deep chains into smaller atoms when recovery needs different fallbacks per step. Read [§3.5.4 Failure handling](spec/spec-cxc-v1.0.md) for propagation rules.

### Tuple return values

**Foreign**

```rust
fn locate(p: &str, t: &str) -> (usize, usize) { (0, 0) }
```

**Corvid**

```elixir
def Slice:
    position: Int = 0
    length: Int = 0

def main:
    use console: Console
    -> running
        console.write((Slice(position: 2, length: 5).length).to_string())
```

**Note**

Return one record with named fields for every multi value result. Read each field by name at the call site. Reach for prelude `Pair` only when no domain word fits the pair. Read [§3.10.2 Multi value carriers](spec/spec-cxc-v1.0.md) and [§3.7.1 No destructuring bindings](spec/spec-cxc-v1.0.md) for the naming rule.

### Destructuring in a binding

**Foreign**

```rust
let (a, b) = compute_pair();
```

**Corvid**

```elixir
def PairVal:
    first: Int = 0
    second: Int = 0

def main:
    use console: Console
    -> running
        let result: PairVal = PairVal(first: 3, second: 4)
        console.write((result.first + result.second).to_string())
```

**Note**

Bind the whole record first and read fields by name second. Reserve binding inside `<:` arms for variant payload extraction only. Read [§3.7.1 No destructuring bindings](spec/spec-cxc-v1.0.md) for the single exception.

### Generic middleware over fallible values

**Foreign**

```rust
fn retry<T, E>(op: impl Fn() -> Result<T, E>) -> Result<T, E> { op() }
```

**Corvid**

```elixir
def HttpFail:
    msg: String

def Fetcher:
    -> running

    running <: fetch -> running
        raise HttpFail(msg: "down")

def main:
    use console: Console
    f: Fetcher = Fetcher()

    -> running
        |> f.fetch()
        or HttpFail(msg: let m):
            console.write(m.to_string())

    -> end
```

**Note**

Write middleware over one named error record per domain. Recover once at the call site that knows the retry or fallback policy. Define a fresh error record when a second domain needs distinct recovery. Read [§3.5.4 Failure handling](spec/spec-cxc-v1.0.md) for typed raise coverage.

### Trait bounds with where clauses

**Foreign**

```rust
fn sum_all<T: UnsignedInteger + Bounded>(xs: &[T]) -> T { todo!() }
```

**Corvid**

```elixir
# aspirational, see spec §3.6
```

**Note**

Generic bounds are specified but unimplemented. cxc names refined values with `def Name: Underlying where predicate` in spec §3.6 and cxc enforces predicates through `|:` propositions on the owning atom. Write the bound as a `|:` proposition today. Read [§3.6 Refinement contracts](spec/spec-cxc-v1.0.md) and [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) before relying on named refinements.

### Trait implementation for a generic type

**Foreign**

```rust
impl<T: Trait> Serializable for MyType<T> { }
```

**Corvid**

```elixir
# aspirational, see spec §3.4.4
```

**Note**

Structural conformance with default methods is specified but unimplemented. cxc specifies default method inheritance in spec §3.4.4 and cxc checks observations inside the owning `def` body only. Write the method on the owning atom or record today. Read [§3.4 Contracts](spec/spec-cxc-v1.0.md) for the specified conformance model.

### Conversion traits

**Foreign**

```rust
let bytes: Vec<u8> = config.into();
let text: String = String::from(raw);
```

**Corvid**

```elixir
# aspirational, see spec §3.10.3
```

**Note**

Trait qualified conversion dispatch is specified but unimplemented. cxc specifies projection and construction protocols in spec §3.10.3 and cxc ships named helpers such as `.to_bytes()` and `.to_string()` and `.to_int_or` only. Call one named helper per conversion today. Write both directions explicitly when both directions carry meaning. Read [§3.10.3 Conversion protocols](spec/spec-cxc-v1.0.md) and [§3.11 Numeric conversion](spec/spec-cxc-v1.0.md) for narrowing helpers.

---

## Coming from Python or Ruby

### Tuple unpacking

**Foreign**

```python
a, b = compute_pair()
```

**Corvid**

```elixir
def PairVal:
    first: Int = 0
    second: Int = 0

def main:
    use console: Console
    -> running
        let result: PairVal = PairVal(first: 1, second: 2)
        console.write(result.first.to_string())
        console.write(result.second.to_string())
```

**Note**

Bind the record value first. Read `.first` and `.second` by name at each use site. Read [§3.7.1 No destructuring bindings](spec/spec-cxc-v1.0.md) for the uniform rule.

### Dictionary iteration with two names

**Foreign**

```python
for k, v in headers.items():
    console.write((k, v).to_string())
```

**Corvid**

```elixir
def main:
    use console: Console
    scores: Map of (String, Int)

    -> running
        alias m2 :- scores.assoc("a", 3)
        console.write(m2.count.to_string())
        console.write((m2.get("a") ?? -1).to_string())
```

**Note**

Iterate entries as single values and read key and value fields by name. Use `.for_each` with one entry parameter when the program must visit every pair. Read [§3.10.1 Iteration protocols](spec/spec-cxc-v1.0.md) and [§3.10.2 Multi value carriers](spec/spec-cxc-v1.0.md) for entry shape details. Verify the map literal form with cxc before copying this sketch into a larger program because empty map construction varies by element type context.

### Collection iteration blocks

**Foreign**

```python
for x in items:
    console.write(x.to_string())
```

**Corvid**

```elixir
def main:
    use console: Console
    xs: List of Int = [7, 11, 13]

    -> running
        xs.for_each(e => console.write(e.to_string()))
```

**Note**

Call `.for_each` on the list with one lambda parameter. Chain `.filter` before `.for_each` when the visit needs a subset only. Read [§3.10.1 Iteration protocols](spec/spec-cxc-v1.0.md) for aggregate method coverage.

### Conditional expression

**Foreign**

```python
x = foo() if bar else baz
```

**Corvid**

```elixir
def main:
    use console: Console
    -> running
        let bar: Bool = false
        let x: Int! = ||: bar : 1
                      ||: else : 2
        console.write(x.to_string())
```

**Note**

Write one `||:` chain for every conditional value. Put the fallback in the closing `else` arm. Read [§3.8.1 Multi arm dispatch](spec/spec-cxc-v1.0.md) for guard evaluation order.

### List comprehensions

**Foreign**

```python
large = [f(x) for x in items if pred(x)]
```

**Corvid**

```elixir
def main:
    use console: Console
    xs: List of Int = [1, 2, 3, 4, 5]

    -> running
        let evens: List of Int = xs.filter(x => x % 2 == 0)
        console.write(evens.count.to_string())
```

**Note**

Chain `.filter` for selection and field reads for projection. Add one small observation per projection step when the projection grows past one field read. Read [§3.10.1 Iteration protocols](spec/spec-cxc-v1.0.md) for shipped aggregate methods.

### String interpolation

**Foreign**

```python
msg = f"hello {name}"
```

**Corvid**

```elixir
def main:
    use console: Console
    -> running
        let name: String = "cxc"
        console.write(("hello " + name).to_string())
```

**Note**

Join string parts with `+` at the format site. Convert numbers with `.to_string()` before joining each numeric part. Treat dollar brace interpolation from spec §4.16.8 as specified surface that still needs a compile check per program. Read [§3.10.5 The String type](spec/spec-cxc-v1.0.md) for shipped string helpers.

---

## Coming from Java or C Sharp

### Try catch finally blocks

**Foreign**

```java
try {
    var text = Files.readString(path);
    System.out.println(text);
} catch (IOException e) {
    System.out.println("missing");
} finally {
    cleanup();
}
```

**Corvid**

```elixir
def main:
    use console: Console
    use fs: FileSystem

    -> running
        let size: Int = fs.size("/tmp/cxc-patterns-missing.txt")
        or IOError(reason: let _r):
            -1
        console.write(size.to_string())
        console.write("cleanup runs before end")

    -> end
```

**Note**

Recover at the binding that can raise and run cleanup steps after the binding inside the same `then` body. Move cleanup into a guarded transition to `end` when cleanup must run after an async completion instead. Read [§3.5.4 Failure handling](spec/spec-cxc-v1.0.md) for recovery placement.

### Nested types

**Foreign**

```java
class FrameCollector {
    enum Event { TEXT, BINARY }
}
```

**Corvid**

```elixir
def CollectorEvent:
    case text
    case binary

def main:
    use console: Console
    -> running
        let e: CollectorEvent = CollectorEvent.text
        e:
            <: text:
                console.write((1).to_string())
            <: binary:
                console.write((2).to_string())

    -> end
```

**Note**

Declare each associated type at module scope with a flat prefixed name. Dispatch on the value with a `<:` block and one arm per case. Read [§3.3.1 Types are flat](spec/spec-cxc-v1.0.md) and [§3.7.2 Leading dot shorthand](spec/spec-cxc-v1.0.md) for naming and dispatch details.

### Interfaces and abstract classes

**Foreign**

```java
interface Parser {
    Outcome parse(String input);
}
```

**Corvid**

```elixir
def Sink:
    total: Int! = 0

    idle <: absorb(amount: Int) -> idle
        total => total + amount

    seen :- total

def main:
    use console: Console
    s: Sink! = Sink()

    -> running
        |> s.absorb(amount: 3)
        console.write(s.seen.to_string())
```

**Note**

Model each contract as one atom with transitions for verbs and observations for reads. Callers depend on message names and observation names only. Treat protocol declarations with default methods from spec §3.4 as specified surface that still needs a compile check per program. Read [§3.4 Contracts](spec/spec-cxc-v1.0.md) for the full conformance model.

### Class inheritance

**Foreign**

```java
class MyController extends BaseController { }
```

**Corvid**

```elixir
def Helper:
    base: Int! = 10

    value :- base

def Owner:
    helper: Helper! = Helper()

    total :- helper.value + 1

def main:
    use console: Console
    -> running
        console.write((Owner().total).to_string())
```

**Note**

Compose one helper atom inside the owner and delegate through observations. Keep framework subclassing inside plugin modules that wrap the foreign class directly. Read [§3.4.5 Explicit conformance declarations](spec/spec-cxc-v1.0.md) for the composition boundary.

### Generic collections

**Foreign**

```java
List<String> names = new ArrayList<>();
Map<String, Integer> scores = new HashMap<>();
```

**Corvid**

```elixir
def main:
    use console: Console
    names: List of String = []
    scores: Map of (String, Int)

    -> running
        let n2: List of String = names.append("a")
        console.write(n2.count.to_string())
        console.write(scores.count.to_string())
```

**Note**

Write `List of T` and `Map of (K, V)` and `Set of T` at every container site. cxc monomorphizes each container instantiation at compile time. Avoid empty list literals in binding positions without element type context and prefer a typed field default instead. Read [§3.2 Composite types](spec/spec-cxc-v1.0.md) for container spelling.

### Null safe navigation

**Foreign**

```java
String name = user != null && user.profile != null
    ? user.profile.displayName : "Anonymous";
```

**Corvid**

```elixir
def Inner:
    n: Int = 5

def Outer:
    inner: Inner = nil

    val :- inner.n

def main:
    use console: Console
    -> running
        console.write(((Outer().val ?? 0)).to_string())

    -> end
```

**Note**

Read through nullable fields with plain dot access. Supply one `??` fallback at the site that needs a definite value. Declare the field with `T!` when nil must never reach the read site. Read [§3.6.1 Nullability and nil punning](spec/spec-cxc-v1.0.md) for field spelling.

### Postcondition old value

**Foreign**

```java
// requires count >= 0
// ensures \result == \old(count) + 1
int increment() { return count + 1; }
```

**Corvid**

```elixir
def Counter:
    n: Int! = 0

    |: n >= 0

    idle <: increment -> idle
        n => n + 1

    value :- n

def main:
    use console: Console
    c: Counter! = Counter()

    -> running
        |> c.increment()
        console.write(c.value.to_string())
```

**Note**

Stage the new field value with `=>` and read the pre image value throughout the same `then` body. Print `n + 1` inside the body when the log line needs the next value. State the lasting bound once as a `|:` proposition on the atom. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for snapshot staging rules.

---

## Coming from Elixir or F Sharp or Haskell or OCaml

### Pipe operator

**Foreign**

```elixir
data |> filter_valid() |> normalize()
```

**Corvid**

```elixir
def main:
    use console: Console
    xs: List of Int = [1, 2, 3, 4]

    -> running
        let evens: List of Int = xs.filter(x => x % 2 == 0)
        console.write(evens.count.to_string())
```

**Note**

Chain methods on the receiver value from left to right. Name each intermediate result with `let` when the chain grows past two calls. Read [§3.10.1 Iteration protocols](spec/spec-cxc-v1.0.md) for chainable container methods.

### Do notation for fallible chains

**Foreign**

```haskell
process = do
  v <- parse input
  save v
```

**Corvid**

```elixir
def Fail:
    msg: String

def Job:
    -> running

    running <: run -> running
        raise Fail(msg: "bad input")

def main:
    use console: Console
    j: Job = Job()

    -> running
        |> j.run()
        or Fail(msg: let m):
            console.write(m.to_string())

    -> end
```

**Note**

Raise at the failing step and recover at the orchestrating send. Give each step its own atom when each step needs a distinct fallback. Read [§3.5.4 Failure handling](spec/spec-cxc-v1.0.md) for unwind behavior.

### Algebraic data types

**Foreign**

```haskell
data Shape = Circle Float | Rectangle Float Float
```

**Corvid**

```elixir
def Shape:
    case circle(radius: Int)
    case square(side: Int)

def main:
    use console: Console
    -> running
        let s: Shape = Shape.circle(radius: 3)
        s:
            <: circle(radius: let r):
                console.write(r.to_string())
            <: square(side: let d):
                console.write(d.to_string())

    -> end
```

**Note**

Declare one `def` with `case` lines for each variant family. Bind payload fields by name inside each `<:` arm. Read [§3.3 User defined types](spec/spec-cxc-v1.0.md) and [§3.7 Pattern matching](spec/spec-cxc-v1.0.md) for payload rules.

### Immutable records

**Foreign**

```haskell
data Point = Point { x :: Int, y :: Int }
```

**Corvid**

```elixir
def Point:
    x: Int = 0
    y: Int = 0

def main:
    use console: Console
    -> running
        let p: Point = Point(x: 1, y: 2)
        let q: Point = Point(x: 9, y: p.y)
        console.write((q.x + q.y).to_string())
```

**Note**

Declare records as field lists with defaults and construct fresh values for each update. Hold records inside atoms when the value must evolve through named transitions. Read [§6.2 Runtime models](spec/spec-cxc-v1.0.md) for value semantics.

### Multi clause function definitions

**Foreign**

```haskell
describe 0 = "zero"
describe n = "many"
```

**Corvid**

```elixir
def Describer:
    use console: Console
    idle <: describe(n: Int) -> idle
        |: n == 0
        console.write("zero")

    idle <: describe(n: Int) -> idle
        console.write("many")

def main:
    d: Describer! = Describer()

    -> running
        |> d.describe(n: 0)
        |> d.describe(n: 5)
```

**Note**

Write one guarded transition per clause and one unguarded arm last. Keep clause order visible in source order because cxc fires the first matching guard. Read [§3.7 Pattern matching](spec/spec-cxc-v1.0.md) for arm ordering.

---


## Corvid native patterns

### Iteration as method dispatch

**Foreign**

```python
for x in xs:
    console.write(x.to_string())
```

**Corvid**

```elixir
def main:
    use console: Console
    xs: List of Int = [3, 1, 2]

    -> running
        let sorted: List of Int = xs.sort_by(x => x)
        sorted.for_each(x => console.write(x.to_string()))
```

**Note**

Call container methods on the collection value itself. Reach for `.filter` and `.sort_by` and `.for_each` before any manual index walk. Read [§3.10.1 Iteration protocols](spec/spec-cxc-v1.0.md) for the shipped method set.

### Failure handling shapes

**Foreign**

```python
try:
    value = load()
except LoadError:
    value = default
```

**Corvid**

```elixir
def LoadFail:
    msg: String

def Loader:
    -> running

    running <: load -> running
        raise LoadFail(msg: "empty")

def main:
    use console: Console
    l: Loader = Loader()

    -> running
        |> l.load()
        or LoadFail(msg: let m):
            console.write(m.to_string())

    -> end
```

**Note**

Pick one failure shape per call boundary. Return plain nullable values for simple absence and domain enums for inline dispatch and typed raises for cross layer unwind. Halt with `panic` only for broken internal bounds. Read [§3.5.4 Failure handling](spec/spec-cxc-v1.0.md) for the decision table.

| Need | Corvid shape | Spec link |
|---|---|---|
| Simple absence | Nullable return with `??` fallback | [§3.6.1](spec/spec-cxc-v1.0.md) |
| Inline outcome branch | Domain enum with `<:` dispatch | [§3.7](spec/spec-cxc-v1.0.md) |
| Cross layer unwind | Typed raise with `or` recovery | [§3.5.4](spec/spec-cxc-v1.0.md) |
| Broken internal bound | `panic` with `SystemPanic` | [§3.5.4](spec/spec-cxc-v1.0.md) |

### Coordination boundary

**Foreign**

```swift
let queue = DispatchQueue(label: "owner")
queue.async { state += 1 }
```

**Corvid**

```elixir
def Owner:
    n: Int! = 0

    idle <: bump -> idle
        n => n + 1

    value :- n

def main:
    use console: Console
    o: Owner! = Owner()

    -> running
        |> o.bump()
        console.write(o.value.to_string())
```

**Note**

Give shared mutable state to exactly one atom. Send messages to that atom from every caller that needs the state. Split the atom only when ownership or lifecycle or capability scope demands a second boundary. Read [§1.9 Contracts as coordination primitives](spec/spec-cxc-v1.0.md) for the ownership rule.

### Sugar and prelude surface

**Foreign**

```python
names = [n.upper() for n in raw_names]
```

**Corvid**

```elixir
def main:
    use console: Console
    xs: List of String = ["a", "b"]

    -> running
        let upper: List of String = xs.filter(s => s.length() > 0)
        console.write(upper.count.to_string())
```

**Note**

Build sugar shapes from named methods and plain constructors only. cxc ships prelude capabilities as ordinary plugin modules and keeps language additions under compiler control. Read [§4.20 The Prelude](spec/spec-cxc-v1.0.md) and [§4.20.4 User extensions](spec/spec-cxc-v1.0.md) for the extension boundary.

### Names over positions

**Foreign**

```python
pair = (3, 4)
total = pair[0] + pair[1]
```

**Corvid**

```elixir
def EntryVal:
    key: String = ""
    value: Int = 0

def main:
    use console: Console
    -> running
        let e: EntryVal = EntryVal(key: "a", value: 3)
        console.write(e.key.to_string())
        console.write(e.value.to_string())
```

**Note**

Name every field and every argument and every enum payload. Read values through field names at every use site. Read [§3.7.1 No destructuring bindings](spec/spec-cxc-v1.0.md) for the uniform discipline.

### Flat associated types

**Foreign**

```java
class Game {
    static class GameEvent { }
}
```

**Corvid**

```elixir
def GameEvent:
    case started
    case stopped

def main:
    use console: Console
    -> running
        let e: GameEvent = GameEvent.started
        e:
            <: started:
                console.write((1).to_string())
            <: stopped:
                console.write((0).to_string())

    -> end
```

**Note**

Declare associated types beside the owner with a shared name prefix. Import the flat name directly when the call site needs brevity. Read [§3.3.1 Types are flat](spec/spec-cxc-v1.0.md) for the naming convention.

### Mechanism check before extension

**Foreign**

```python
class Registry:
    def __init__(self):
        self.handlers = {}
```

**Corvid**

```elixir
def Registry:
    items: List of Int = []

    idle <: register(v: Int) -> idle
        items => items.append(v)

    count :- items.count

def main:
    use console: Console
    r: Registry! = Registry()

    -> running
        |> r.register(v: 5)
        console.write(r.count.to_string())
```

**Note**

Check atoms and observations and container methods before adding any new mechanism. Reach for one atom with transitions first and split only under ownership pressure. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for the default shape.

---

## Atom substrate patterns

### Emit a value from a handler

**Foreign**

```swift
delegate?.didEmit(value)
```

**Corvid**

```elixir
def Emitter:
    -> running
        self :> fire

    -> end
        :> beep

    running <: fire -> end

def main:
    use console: Console
    source: Emitter = Emitter()

    -> running

    running <: source.beep -> end
        console.write((2).to_string())
```

**Note**

Emit with bare `:>` inside a `then` body and subscribe with a `<:` arm that names the source. Name every payload field at both the emit site and the receive site. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for emit and subscribe pairing. Verify this sketch with cxc before reuse because cross atom emit wiring varies with atom topology.

### Delayed callback

**Foreign**

```swift
DispatchQueue.main.asyncAfter(deadline: .now() + 1) { tick() }
```

**Corvid**

```elixir
def main:
    use console: Console
    clock: Clock = Clock()
    beats: Int! = 0

    -> running

    running <: clock.every(rate_ms: 5)(t: Instant) -> running
        |: beats < 1
        beats => beats + 1

    running <: clock.every(rate_ms: 5)(t: Instant) -> end
        |: beats == 1
        console.write(beats.to_string())

    -> end
        console.write(beats.to_string())
```

**Note**

Declare one `Clock` field and subscribe to `clock.every` emissions with guarded arms. End the program from the arm that observes the final beat. Read [§1.12 The OS boundary](spec/spec-cxc-v1.0.md) for time as a capability. Verify this sketch with cxc before reuse because clock subscription spelling tracks the prelude build.

### Stream transformer composition

**Foreign**

```javascript
stream.pipe(debounce(200)).subscribe(update)
```

**Corvid**

```elixir
# aspirational, see spec §1.11
```

**Note**

Inline transformer chains are specified but unimplemented. cxc specifies transformer composition inside receive glyph source expressions in spec §1.11 and cxc ships plain source subscriptions only. Model each transform stage as one named sub atom today. Forward each emission with an explicit `:>` send inside `then`. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for the specified chain form.

### Event handler declaration

**Foreign**

```swift
func onInput(_ event: Event) { handle(event) }
```

**Corvid**

```elixir
def Handler:
    use console: Console
    seen: Int! = 0

    idle <: input(v: Int) -> idle
        seen => seen + 1
        console.write(v.to_string())

    count :- seen

def main:
    use console: Console
    h: Handler! = Handler()

    -> running
        |> h.input(v: 9)
        console.write(h.count.to_string())
```

**Note**

Write one `<:` transition per handler and keep the handler body inside `then`. Use a wildcard source phase only when the handler must fire from every phase. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for wildcard arm spelling.

### Stateful type declaration

**Foreign**

```swift
final class Counter { var count = 0 }
```

**Corvid**

```elixir
def Counter:
    n: Int! = 0

    idle <: bump -> idle
        n => n + 1

    value :- n

def main:
    use console: Console
    c: Counter! = Counter()

    -> running
        |> c.bump()
        console.write(c.value.to_string())
```

**Note**

Write `def Name` for every top level type. Let body shape decide atom or record or enum treatment at compile time. Read [§4.10.7 Body shape inference](spec/spec-cxc-v1.0.md) for shape rules.

### Generic container spelling

**Foreign**

```java
List<Integer> xs;
Map<String, User> users;
```

**Corvid**

```elixir
def main:
    use console: Console
    xs: List of Int = [1, 2]
    names: List of String = ["a"]

    -> running
        console.write(xs.count.to_string())
        console.write(names.count.to_string())
```

**Note**

Write `List of Int` and `Map of (String, User)` with the `of` qualifier. Give each list literal a typed binding so cxc can monomorphize the element type. Read [§3.2 Composite types](spec/spec-cxc-v1.0.md) for qualifier spelling.

### Generic helper without brackets

**Foreign**

```rust
fn first<T>(xs: &[T]) -> Option<T> { xs.first().copied() }
```

**Corvid**

```elixir
# aspirational, see spec §1.11
```

**Note**

Shape variable helpers are specified but unimplemented. cxc binds lowercase shape variables by first mention in spec §1.11 and cxc monomorphizes concrete container uses only. Inline the helper logic at each concrete call site today. Read [§3.2 Composite types](spec/spec-cxc-v1.0.md) for concrete container forms that compile.

### Multiple output channels

**Foreign**

```python
matched, unmatched = partition(pred, stream)
```

**Corvid**

```elixir
# aspirational, see spec §3.2
```

**Note**

Named tuple return channels are specified but unimplemented. cxc specifies multi channel returns in spec §3.2 and cxc returns single values through observations only. Model each channel as one emission name on one atom today. Subscribe per emission name with separate `<:` arms. Read [§3.2 Composite types](spec/spec-cxc-v1.0.md) for the specified return form.

### Capability provision to children

**Foreign**

```python
child = Child(clock=game_clock, fs=sandboxed_fs)
```

**Corvid**

```elixir
def Child(clock_ticks: Int!):
    limit: Int! = clock_ticks

    cap :- limit

def Parent:
    child: Child! = Child(clock_ticks: 16)

    ticks :- child.cap

def main:
    use console: Console
    -> running
        console.write((Parent().ticks).to_string())
```

**Note**

Pass each capability value or configuration value through constructor parameters. Declare each `use` line inside the atom that directly calls the capability. Read [§1.12 The OS boundary](spec/spec-cxc-v1.0.md) for provision semantics.

### Direct operating system calls

**Foreign**

```python
data = open(path).read()
console.write((random.random()).to_string())
```

**Corvid**

```elixir
def main:
    use console: Console
    use fs: FileSystem
    use random: Random

    -> running
        let n: Int = random.between(low: 0, high: 10)
        or RandomError(reason: let _r):
            0
        console.write(n.to_string())
        console.write((fs.size("/tmp/cxc-patterns-missing.txt")).to_string())

    -> end
```

**Note**

Declare every operating system capability with `use` inside the atom that calls it. Recover from each fallible capability call with the matching typed `or` arm. Pass `env` explicitly on every subprocess call with no default. Read [§1.12 The OS boundary](spec/spec-cxc-v1.0.md) and [§1.13 Stdlib capability shapes](spec/spec-cxc-v1.0.md) for capability names. Verify the random recovery arm with cxc before reuse because prelude error field names track the prelude build.

### Resource cleanup without finally

**Foreign**

```java
try (var f = open(path)) { use(f); }
```

**Corvid**

```elixir
def Session:
    cleaned: Int! = 0

    idle <: finish -> end

    was_cleaned :- cleaned

def main:
    use console: Console
    s: Session! = Session()

    -> running
        |> s.finish()
        console.write((1).to_string())
```

**Note**

Model each resource as one atom and run cleanup inside the transition that targets `end`. Keep the terminal phase name as `end` because any other terminal name leaves the atom idling. Omit `then` staging that writes fields on the `end` transition itself when the atom discards state. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for terminal phase rules. Verify this sketch with cxc before reuse because observation reads after `end` depend on slot lifetime.

### Foreign function bindings

**Foreign**

```c
int abs(int value);
```

**Corvid**

```elixir
def plugin:
    name:    String!       = "hello-plugin-ffi"
    version: SemVer!       = SemVer(0, 1, 0)
    compat:  VersionRange! = VersionRange(min: SemVer(1, 0, 0),
                                          max: SemVer(2, 0, 0))

    foreign "libc":
        symbol "abs"  -> (Int) -> Int
        symbol "puts" -> (String) -> Int

def main:
    use console: Console
    use plugin: Plugin

    -> ready
        plugin.get("puts")("hello via FFI")
        console.write((plugin.get("abs")(-42)).to_string())
```

**Note**

Declare foreign symbols inside `foreign` blocks nested in one `def plugin` body per module. Import the marker with `use plugin: Plugin` in peer definitions only. Keep application atoms away from raw foreign symbols. Read [§7.2.1 Plugin modules](spec/spec-cxc-v1.0.md) for module rules.

### One shot async value

**Foreign**

```swift
let value = await fetchValue()
console.write(value.to_string())
```

**Corvid**

```elixir
def Task:
    result: Int! = 0

    -> running
        self :> compute

    running <: compute -> running
        result => 42
        :> completed(r: 42)

def main:
    use console: Console
    task: Task = Task()

    -> waiting

    waiting <: task.completed(r: Int) -> end
        console.write(r.to_string())
```

**Note**

Hold the pending value inside one task atom and emit one completion message with the value. Subscribe from the waiter with one `<:` arm that targets `end`. Name the payload field identically at emit and receive sites. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for async lifecycle policy.

### Cooperative cancellation

**Foreign**

```python
task.cancel()
```

**Corvid**

```elixir
def Work:
    steps: Int! = 0

    -> running
        self :> step

    running <: step -> running
        |: steps < 2
        steps => steps + 1
        self :> step

    running <: step -> end
        |: steps >= 2

def main:
    use console: Console
    w: Work = Work()

    -> running
        console.write((1).to_string())
```

**Note**

Check one shared cancellation flag inside `|:` guards on each step transition. Route the cancelled path to `end` with a second guarded arm on the same message. Keep each transition body short so cancellation stays responsive. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for cooperative policy. Verify this sketch with cxc before reuse because self send scheduling tracks the runtime build.

### Deadline composition

**Foreign**

```python
deadline = Deadline(delay_ms=500)
```

**Corvid**

```elixir
# aspirational, see spec §1.11
```

**Note**

A standalone deadline atom remains an aspirational pattern. Compose one `Clock` field with one elapsed flag inside the worker atom instead. Guard each step with the elapsed flag through `|:`. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for composition guidance.

### Start work and await value

**Foreign**

```java
var future = executor.submit(() -> 42);
System.out.println(future.get());
```

**Corvid**

```elixir
def Job:
    -> running
        self :> compute

    running <: compute -> running
        :> completed(r: 42)

def main:
    use console: Console
    job: Job = Job()

    -> waiting

    waiting <: job.completed(r: Int) -> end
        console.write(r.to_string())
```

**Note**

Start work from the initial phase body and deliver the result through one named emission. End the waiter on the completion arm so the process exits after printing. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for completion wiring.

### Program exit behavior

**Foreign**

```python
console.write("done")
```

**Corvid**

```elixir
def main:
    use console: Console
    -> running
        console.write("done")
```

**Note**

End `main` by finishing the initial `then` body when no message arms exist. Target `end` from the final message arm when message arms exist. Audit any hanging program for one message arm with no path to `end`. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for exit rules.

### Structured concurrency scope

**Foreign**

```python
async with trio.open_nursery() as nursery:
    nursery.start_soon(job)
```

**Corvid**

```elixir
def Worker(id: Int!):
    ident: Int! = id

    tag :- ident

def Boss:
    a: Worker! = Worker(id: 1)
    b: Worker! = Worker(id: 2)

    total :- a.tag + b.tag

def main:
    use console: Console
    -> running
        console.write((Boss().total).to_string())
```

**Note**

Hold each child atom as one field on the parent atom. End the parent to cascade destruction across every child slot. Signal children through one shared flag first when children must finish in flight work before exit. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for cascade order.

### Public API surface

**Foreign**

```java
public void takeDamage(int amount) { }
```

**Corvid**

```elixir
def Unit:
    hp: Int! = 100

    idle <: take_damage(amount: Int) -> idle
        hp => hp - amount

    health :- hp

def main:
    use console: Console
    u: Unit! = Unit()

    -> running
        |> u.take_damage(amount: 5)
        console.write(u.health.to_string())
```

**Note**

Expose every `<:` transition as the public message surface. Read state through observations only and keep fields private to the atom body. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for dispatch table visibility.

### Internal wiring graph

**Foreign**

```python
game.wire(clock.tick, paddle.on_tick)
game.wire(keyboard.pressed, player.on_pressed)
```

**Corvid**

```elixir
def Paddle:
    moves: Int! = 0

    idle <: tick -> idle
        moves => moves + 1

    count :- moves
def Game:
    use console: Console
    paddle: Paddle! = Paddle()

    idle <: step -> idle
        |> paddle.tick()
        console.write(paddle.count.to_string())

def main:
    g: Game! = Game()

    -> running
        |> g.step()
```

**Note**

Write one receive arm per consumed stream on the consuming atom. Forward each event with one explicit send inside `then`. Inline one transformer atom as a field when one stage needs inspection or reuse. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for subscription placement.

### Active state reference

**Foreign**

```python
hp = file.active.hp
```

**Corvid**

```elixir
def Unit:
    hp: Int! = 10

    idle <: hit(amount: Int) -> idle
        hp => hp - amount

    health :- hp

def main:
    use console: Console
    u: Unit! = Unit()

    -> running
        |> u.hit(amount: 3)
        console.write(u.health.to_string())
```

**Note**

Read and write state through `self` fields and observations inside the owning atom. Send messages to the compound atom from outside and let dispatch reach the active phase arm. Read [§1.12 The OS boundary](spec/spec-cxc-v1.0.md) for delegation semantics.

### Capability shape conformance

**Foreign**

```java
class PosixFs implements Filesystem { }
```

**Corvid**

```elixir
def Store:
    n: Int! = 0

    idle <: put(v: Int) -> idle
        n => v

    value :- n

def main:
    use console: Console
    s: Store! = Store()

    -> running
        |> s.put(v: 4)
        console.write(s.value.to_string())
```

**Note**

Match capability shapes structurally with matching message and observation names. Add an explicit conformance marker only when the compiler build supports the marker in that module. Read [§1.12 The OS boundary](spec/spec-cxc-v1.0.md) for shape matching.

### Mutable field modeling

**Foreign**

```python
class Paddle:
    def __init__(self):
        self.y = 0.0
```

**Corvid**

```elixir
def Paddle:
    y: Int! = 0
    velocity: Int! = 0

    -> idle
        velocity => 0
    idle <: press_up -> up
    up <: release_up -> idle
    idle <: press_down -> down
    down <: release_down -> idle

def main:
    use console: Console
    p: Paddle! = Paddle()

    -> running
        |> p.press_up()
        console.write((1).to_string())
```

**Note**

Pick phases on the current atom for discrete behavior states and fields at atom scope for shared values. Promote the state to one sub atom only when the state needs queueing or interruption or an independent lifecycle. Stage phase entry values from wildcard entry arms where the compiler supports the arm. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for the single decision question. Verify this sketch with cxc before reuse because wildcard entry spelling tracks the compiler build.

### Cross scope field updates

**Foreign**

```python
player.mana -= cost
player.last_cast_at = now()
```

**Corvid**

```elixir
def Player:
    mana: Int! = 50
    casts: Int! = 0

    idle <: cast(cost: Int) -> idle
        |: mana >= cost
        mana => mana - cost
        casts => casts + 1

    left :- mana

def main:
    use console: Console
    p: Player! = Player()

    -> running
        |> p.cast(cost: 10)
        console.write(p.left.to_string())
```

**Note**

Stage every field update inside the single `then` body that owns the transition. Emit one event and listen from a second arm when a second atom must react to the same change. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for snapshot commit rules.

### Lifecycle state in a sub atom

**Foreign**

```python
if action is None:
    action = ActionMachine()
    action.start_cast(target)
```

**Corvid**

```elixir
# aspirational, see spec §1.11
```

**Note**

Nullable slot sub atom lifecycles remain an aspirational pattern. cxc specifies slot auto nil behavior on sub atom `end` in spec §1.11 and current examples prove field held sub atoms with explicit sends only. Model the lifecycle as phases on the parent atom today. Split into one field held sub atom when queueing pressure proves the boundary. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for slot coordination patterns.

### Parent and sub atom coordination

**Foreign**

```python
action.start_cast(target)
if action.is_idle:
    pass
```

**Corvid**

```elixir
def Machine:
    busy: Int! = 0

    idle <: start -> idle
        busy => 1

    is_busy :- busy
def Parent:
    use console: Console
    machine: Machine! = Machine()

    idle <: go -> idle
        |> machine.start()
        console.write(machine.is_busy.to_string())

def main:
    p: Parent! = Parent()

    -> running
        |> p.go()
```

**Note**

Send commands down with `|>` or `:>` and read state up through observations. Subscribe to sub atom emissions with source named `<:` arms when the parent must react without polling. Keep payload types identical across every arm that emits the same message name. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for the four coordination patterns.

### Construction and destruction hooks

**Foreign**

```java
class Player {
    Player() { hp = 100; }
    protected void finalize() { emitDied(); }
}
```

**Corvid**

```elixir
def Player:
    hp: Int! = 0

    -> alive
        hp => 100

    alive <: heal(amount: Int) -> alive
        hp => hp + amount

    health :- hp

def main:
    use console: Console
    p: Player! = Player()

    -> running
        |> p.heal(amount: 5)
        console.write(p.health.to_string())
```

**Note**

Set initial field values from the entry arm for the first phase. Run last chance cleanup inside the transition that targets `end` and omit field staging on that transition. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for implicit phase rules.

### Blocking and non blocking sends

**Foreign**

```python
await channel.send(msg)
channel.send_nowait(msg)
```

**Corvid**

```elixir
def Sink:
    seen: Int! = 0

    idle <: put(v: Int) -> idle
        seen => seen + v

    total :- seen

def main:
    use console: Console
    s: Sink! = Sink()

    -> running
        |> s.put(v: 2)
        s :> put(v: 3)
        console.write(s.total.to_string())
```

**Note**

Send with `|>` when the sender must wait for the receiver arm to finish. Send with `:>` when the sender must continue immediately and use `self :> msg` for same atom loops. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for send pairing rules. Verify the async read timing with cxc before reuse because mailbox draining tracks the runtime build.

### Phase exhaustiveness checks

**Foreign**

```python
assert state in ("alive", "dead")
```

**Corvid**

```elixir
def Gauge:
    raw_value: Int = 50
    max: Int = 100

    value :- raw_value

    |: raw_value >= 0
    |: raw_value <= max

def main:
    use console: Console
    -> running
        console.write((Gauge().value).to_string())
```

**Note**

State each lasting bound once as a `|:` proposition on the atom. Skip any proposition that merely restates the phase set or the field type. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for proposition placement.

### Constructor arguments as fields

**Foreign**

```java
class FileRef {
    final String path;
    FileRef(String path) { this.path = path; }
}
```

**Corvid**

```elixir
def FileRef(path: String):
    shown :- path

def main:
    use console: Console
    -> running
        console.write((FileRef(path: "a.txt").shown).to_string())
```

**Note**

Write constructor parameters on the `def` head and read each parameter as a field inside the body. Add defaults on the head when callers may omit the argument. Read [§1.11 The atom substrate](spec/spec-cxc-v1.0.md) for constructor promotion.

---

## See also

- [docs/spec/spec-cxc-v1.0.md](spec/spec-cxc-v1.0.md), normative spec
- [docs/writing-corvid/01-your-first-atom.md](writing-corvid/01-your-first-atom.md), first program walkthrough
- [compiler/examples/](../compiler/examples/), shapes that pass today
