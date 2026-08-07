# Friction Log — Starscream WebSocket Translation (Parity Pass)

**Exercise:** Translate Starscream's frame parser + handshake to Corvid v0.5.
**Pass:** Parity — line-by-line, staying close to source structure.
**Files translated:** Framer.swift (frame parser + serializer), FrameCollector.swift (fragment state machine), HTTPHandler.swift (upgrade handshake).
**File deferred:** FoundationHTTPHandler.swift — see `03a-FoundationHTTPHandler.deferred.md`.
**Total original Swift:** ~622 lines across 3 files (+124 deferred).
**Total translated Corvid:** ~950 lines (parity + inline friction annotations).

The primary output of this pass is this log. It surfaces where Corvid v0.5 lacks the syntax to hold a real Swift codebase.

## Summary of Findings

**73 distinct friction points** across three files (numbered `#W-1` through `#W-73`). Plus 8 additional categories catalogued from FoundationHTTPHandler without translation (`F1` through `F8`).

Categorized:

- **Structural gaps (design conflicts):** 3 items — genuine design tensions the parity pass surfaces but does not resolve.
- **Spec gaps (unspecified but needed):** 55 items — real syntactic holes in v0.5. Most cluster into a small number of families.
- **Idiomatic differences (working as designed):** 6 items — Corvid diverges from Swift by design; not bugs.
- **Wins (contracts, invariants, state-machine sync):** 9 candidates — surface only in the idiomatic pass but flagged inline here where the Swift source obscured them.

The Ink exercise found 38 friction points across ~245 Swift lines (~15 per 100 lines). This exercise found ~73 across ~622 lines (~12 per 100 lines) — comparable density. What changed is the *category mix*: Ink's frictions clustered around syntactic gaps (`try`, string interp, pattern matching); this exercise's cluster is bit-level manipulation, generics-on-extensions, and Foundation-primitive interop — territory Ink didn't reach.

**Cross-exercise note:** several Ink frictions recurred here — string interpolation (Ink #22), `try` semantics (Ink #17), argument labels (Ink #21), guard-let (implicit in Ink), `if let` (Ink #9), nested types (variant of Ink #16a). These are counted here for completeness but not for prioritization — they were already known.

---

## Category 1: Structural Gaps (Design Conflicts)

Places where the parity translation exposes an incompatibility between Swift's model and Corvid's design choices.

### 1.1 `struct`-as-namespace (`#W-25`)

Swift's `HTTPWSHeader` is a struct with only `static let` constants and `static func` methods. It is never instantiated. Corvid's struct discipline (§6.1) makes struct instances first-class values — a never-instantiated struct is grammatically legal but semantically odd.

**Impact:** every wire-protocol constants-bag in the Foundation-Swift world looks like this. Common shape.

**Options:**
1. Accept the shape; add a `class:` section for the static members (assumed but not spec-committed — see `#W-26`).
2. Introduce a first-class `namespace` construct.
3. Move constants to module scope, functions to free functions.

**Not a design bug — a spec gap disguised as an idiom mismatch.**

### 1.2 DispatchQueue-shaped serialization (`#W-46`, `#W-49`)

`WSFramer.add(data:)` uses `DispatchQueue(label:...).async { [weak self] in ... }` to serialize appends and processing on a background queue. Corvid's concurrency model (§8) offers `channel:` and `signal:` sections but no equivalent of a "single-consumer serial queue" primitive at the language level. §8 explicitly notes "explicit OS threads restricted to `unsafe:` in Stage 1."

**Impact:** almost every real Apple-ecosystem class uses a dispatch queue for internal serialization. This is one of Foundation's most-used shapes. Without a language-level story, the parity translation wraps the entire method in `unsafe:` — honest but unergonomic.

**Options:**
1. Add a `serial_queue:` section that generates a compiler-checked equivalent of GCD's serial queue.
2. Provide a channel-based idiom with one consumer, one producer, and clearly document that as "the DispatchQueue replacement."
3. Keep it in `unsafe:` — DispatchQueue is Foundation's job, Corvid is thin over it.

**Real design decision, not merely a spec addition.**

### 1.3 Closure syntax + capture lists (`#W-49`)

Related to 1.2 but distinct. Even if DispatchQueue is bound directly, the `async { [weak self] in ... }` pattern requires:
- **Closure literal syntax** — trailing braces with `in` separator (Swift's specific form), or something else.
- **Capture list annotations** — `[weak self]`, `[unowned self]`, `[self]` explicit strong capture.

Corvid spec: no shown syntax for either. Reference card is empty on closure syntax entirely.

**Impact:** widespread. Any Foundation-heavy Corvid code will need to pass closures to callbacks, delegates, and dispatch APIs.

**Options:**
1. Adopt Swift's `{ ... in ... }` form.
2. Function-literal syntax (`function () -> Type: ...`) — closer to the rest of the language.
3. Named local functions only — no anonymous closures.

**Not addressable without a design decision.**

---

## Category 2: Spec Gaps (Unspecified in v0.5)

Grouped by family. Individual `#W-N` references are the specific translation sites where each surfaced.

### 2.1 Bit-level operations

**Bitwise operators** (`&`, `|`, `^`, `~`, `<<`, `>>`) — `#W-54`. Referenced ~30 times across the Framer alone. Every wire-protocol codebase needs these. Spec: not specified anywhere. Reference card confirms as gap.

**Compound bit-assignment** (`|=`, `&=`, `^=`, `<<=`, `>>=`) — `#W-64`. Same family. Every `pointer[0] |= RSV1Mask`-shaped line in the Framer.

**Bit shifts by runtime values** — `#W-73`. Shift amount is an expression, not a literal. Common in encoders/decoders.

**Priority:** high. Cannot express any wire-protocol code without these.

### 2.2 Enum raw values (integer-backed enumerations)

**Explicit backing type on `enum`** — `#W-45`. Swift: `enum CloseCode: UInt16 { case normal = 1000 }`. Spec: does not commit to raw-value enumerations. Reference card explicitly names this as a gap.

**Impact:** any protocol/interop code uses these. WebSocket has three (`CloseCode`, `FrameOpCode`, HTTP status codes). HTTP has one. Every C-ABI enum has one. The parity translation compensates with paired `raw_value(self)` and `class function from_raw(value:)` methods per enum — ~40 lines of boilerplate for what Swift expresses in one declaration line.

**Priority:** very high. Widest single impact.

### 2.3 Optional handling

**`??` nil-coalescing operator** — `#W-21`, `#W-42`, `#W-50`, `#W-36`. **Resolved v0.7 (§3.6.1)** — `??` is spec-committed.

**`?[]` optional subscript** — `#W-32`. **Resolved v0.7 (§3.6.1)** — `.` and subscript both nil-flow through under reference-types-allow-nil model. No optional-subscript operator needed.

**Optional chaining `?.`** — `#W-16`, `#W-50`. **Resolved v0.7 (§3.6.1, §5.7)** — `?.` retired; `.` operates on nil receivers uniformly (auto-lift return, `on_nil` dispatch).

**`guard let`** — `#W-29`, `#W-40`, `#W-51`. **Resolved v0.7 (§3.6.1)** — `if let` and `guard let` spec-committed for scoped unwrap. Multi-binding form still needs spec text.

**Priority (remaining):** low. Nil-messaging inversion (v0.7) resolved the family.

### 2.4 Collection literal and generic syntax

**Dictionary/Map type syntax** — `#W-24`. `[String: String]` in Swift; Corvid spec commits to `Bytes` and array types but shows no map primitive.

**Array literal** — `#W-27`. `["wss", "https"]` — spec shows `[Byte].filled(size: n)` class-function form; a bracket literal syntax is not explicitly spec'd.

**Range literals and ranges as values** — `#W-37(a)`, `#W-67`. `0..<16` (half-open), `0...7` (closed). Not shown anywhere in the spec. Range-based `for` loops (`for i in 0..<n`) similarly unspecified.

**Priority:** high. Impossible to write iteration or collection construction cleanly without these.

### 2.5 Numeric type conversion

**Widening / narrowing conversions** — `#W-56`, `#W-59`, `#W-65`. `UInt64(payloadLen)`, `UInt8(payloadLength)`, `Int(dataLength)`. Very frequent.

**Type extrema** — `#W-66`. `UInt16.max`. Standard-library convenience; not shown.

**Priority:** medium-high. Every wire-protocol codebase does this constantly.

### 2.6 Generics on extensions / categories

**Empty marker protocols** — `#W-69`. `public protocol MyWSArrayType {}` — a protocol with no members, used as a capability tag.

**Generic constraints on categories** — `#W-69`. Swift: `extension Array where Element: MyWSArrayType & UnsignedInteger`. Requires (a) generic parameters on the category, (b) `where` clauses, (c) protocol conjunction (`A & B`).

**Categories on primitive/generic types** — `#W-70`. Whether `category(target_class: [Byte])` is legal is not shown.

**Priority:** medium. Blocks any generic library work; not immediately needed for direct translation but a real ceiling.

### 2.7 Type system — nested types, tuples, aliases

**Nested type declarations and dotted access** — `#W-2`, `#W-48`. `FrameCollector.Event`. Parity lifts nested enums to module scope, losing namespacing.

**Positional (unnamed) associated values** — `#W-6`. `case closed(String, UInt16)`. Spec shows named form only.

**Tuple types** — implied by `#W-35`. `for (key, val) in dict` — dictionary iteration returns tuples in Swift. Corvid tuples not shown.

**Type aliases** — `F2` (deferred file). `public typealias Foo = Bar`.

**Priority:** medium. Each blocks a specific idiom.

### 2.8 Constructors and initialization

**Constructor parameter defaults** — `#W-47`. `init(isServer: Bool = false)`.

**Definite assignment for `let`** — `#W-20`, `#W-60`. `let x: T` followed by conditional assignment relies on Swift's definite-assignment analysis. Corvid's `let` semantics under definite-assignment not spec'd. Parity uses `var` and loses the guarantee.

**Priority:** medium.

### 2.9 Strings

**Interpolation syntax** — `#W-55`, `#W-36`, `#W-30`. Simple `$identifier` visible in spec examples; expression form (`${expr}`, `\(expr)`, `{expr}`) not committed. Parity uses `${...}` per Ink convention but this is a working guess.

**String position API** — `#W-30`. `index_before`, `end_index`, `remove(at:)` — already flagged as Ink priority #5. Recurred here.

**String from bytes** — `#W-14`. `String(data: bytes, encoding: .utf8)` — Foundation constructor with labels, returns optional. Parity assumes `String.try_from(bytes:encoding:)`.

**Character-literal-to-byte** — `F6` (deferred). `UInt8(ascii: "\r")` — byte-array constants for delimiter recognition.

**Priority:** medium — mostly one-per-family fixes, but the interpolation gap is load-bearing (see Ink log).

### 2.10 Static / class-level members

**`class:` section on struct** — `#W-26`, `#W-28`. Whether Corvid supports class-level members on structs is inferred from Ink log FRICTION #16a, not spec-committed. Every wire-protocol namespace uses this.

**Priority:** medium.

### 2.11 Iteration and control flow

**Range-based for loops** — `#W-67`. `for i in 0..<n`. Every counting loop in the Framer.

**Tuple destructuring in `for`** — `#W-35`. `for (k, v) in dict`.

**`else if` chains** — `#W-17`. Chained conditionals: not spec-committed even though `if` and `else` are shown. Parity assumes it works.

**Modulo operator** — `#W-68`. `i % 4`. Standard-library gap.

**Priority:** medium.

### 2.12 Interop primitives

**Byte-array access from Bytes** — `#W-53`. Swift: `data.withUnsafeBytes { ... }` for raw buffer access. Corvid: no shown syntax for exposing the underlying byte array of a Bytes value.

**Byte slice methods** — `#W-52`, `#W-61`, `F8`. `Data.advanced(by:)`, `Data.subdata(in:)`, and generic slice → Bytes conversion. Foundation adjacency; every parser needs at least one form.

**MemoryLayout<T>.size** — `#W-57`. Type-size introspection. Compiled to a constant in Swift; every wire-protocol parser uses it to move offsets.

**Raw C-function import** — `F5` (deferred). `import c "..."` shown in reference card; full workflow for CoreFoundation not detailed.

**CF↔Foundation bridging** — `F3`, `F4` (deferred). `as CFString`, `.takeRetainedValue()`.

**Conditional compilation** — `F1` (deferred). `#if os(watchOS)`. Multi-target concern.

**Priority:** mixed. §2.12 items break down further by whether you're touching pure Foundation (medium priority) or CoreFoundation (deferred until it matters).

### 2.13 `inout` / mutable parameter

**Free functions with mutable array parameter** — `#W-72`. `func writeUint16(_ buffer: inout [UInt8], ...)`. Two orthogonal questions: (a) how is the parameter marked as caller-visible-mutable, (b) if arrays are reference-shaped in Corvid, `inout` is unnecessary. Neither answered by spec.

**Priority:** low — parity workaround (return-new-array) is verbose but functional. Idiomatic pass can revisit.

### 2.14 Module-level declarations

**Module-level constants** — `#W-43`. `let X: UInt8 = 0x80` at file scope. Not explicitly shown; assumed legal.

**Hex literal syntax** — `#W-44`. `0xFF`. Implied by C-interop mentions; not specified in §2 (Lexical Structure).

**Priority:** low — presumed working, but should be spec-committed.

### 2.15 Random / stdlib API surface

**Random-number API** — `#W-37(c)`. `UInt8.random(in: range)`. Standard library scope; not spec.

**Base64 encoding** — `#W-37(e)`. `.base64EncodedString()`. Foundation binding.

**Priority:** low.

---

## Category 3: Idiomatic Differences (Working as Designed)

Not bugs. Corvid differs from Swift by design; the parity translation shows the divergence but the design is intentional.

- **`expose` vs `public`** (`#W-11`, `#W-23`). Two-state visibility model per §7.
- **`category(target_class:)` vs `extension`** (`#W-39`). Category is the ObjC-derived name; arguably clearer than Swift's overloaded `extension`.
- **No argument labels** (recurred throughout). Ink log #21. Design choice per Ink findings.
- **Typed throws (per-function error types)** (`#W-5`, `#W-18`). Corvid requires an error type on the throwing signature; Swift's untyped throws is looser. Design choice per §3.5.
- **Structural conformance by default** — `class X: Protocol` is optional per reference card. Not visible in the parity translations because we followed Swift's explicit conformance style.
- **`data:` immutability discipline** (`#W-8`). Field defaults on `state:` are honest about mutability; Swift's `let`/`var` on struct fields conflates immutability with role.

---

## Category 4: Wins (Corvid Would Do It Better in the Idiomatic Pass)

Flagged inline in the parity translations. These are the payoff of the design and the primary targets for the idiomatic rewrite.

- **`#W-10` FrameCollector state-machine invariants.** Three invariants are stated as inline comments and enforced through error-returning branches. Corvid's `invariants:` clause turns them into first-class specs.
- **`#W-3` protocol contracts on optional return.** `decompress(...) -> Data?` — Corvid can distinguish "cannot" from "reject" with `ensures returns nil implies ...`.
- **`#W-38` parse() byte-count contract.** `func parse(data: Data) -> Int` returns bytes consumed. `requires` and `ensures` on the range are enforceable in Corvid.
- **Frame validity invariants** (throughout `01-Framer.parity.cor`). `process()` returns `.failed(...)` on invariant violations that could be expressed as preconditions on the incoming byte buffer.
- **Close-code range membership** (`#W-58`). Explicit range predicate; Corvid's contract system can hoist to the enum boundary.
- **CloseCode enum totality** — the paired `from_raw` accessor makes it explicit that not every UInt16 is a valid CloseCode. Corvid's typed-throws + contracts can enforce this at the boundary instead of runtime.
- **`FrameCollector.reset()` invariant re-establishment.** Every `reset()` re-establishes the class invariant `frame_count == 0 implies buffer.count == 0`. Corvid can auto-check on method exit.
- **State machine sync on FrameCollector.** `add(frame:)` is called from a queue-serialized context in Swift. In Corvid, the state-machine-as-sync mechanism (§1.9) could make single-threaded access an enforceable property without an external queue.
- **DispatchQueue as unsafe boundary.** Making DispatchQueue-use explicit via `unsafe:` (per §8) is a win — it flags the exact site where the language-level safety story stops, and code review can focus there.

---

## Overall Assessment

Three files (~622 Swift lines) → **73 friction points** clustered into ~15 families. The concentration in bit-level operations, generics on extensions, and Foundation-primitive interop is territory the Ink exercise never touched. This exercise confirms what Ink couldn't: the Corvid spec's silence on low-level and interop syntax is a real constraint on the design's readiness for real Apple-ecosystem code.

Compared to Ink:
- **Similar friction density** (~12/100 vs. Ink's ~15/100).
- **Different category mix.** Ink was mostly pattern-matching, optional, and string gaps. This exercise is mostly bit-manipulation, generics-on-extensions, and Foundation-primitive interop.
- **New load-bearing gap:** enum raw values (`#W-45`). No Ink equivalent. Broadly impactful.
- **New design tension:** DispatchQueue substitute (§1.2). Not resolvable with a spec addition alone.

**Estimated ratio for full Starscream translation** (~10 more files including Engine, Transport, Compression): if friction density holds, ~100–150 additional friction points, but most will be repeats of the same families named above. The unique friction count would probably grow by ~15–20.

## Priorities Emerging From This Pass

**Must-fix spec gaps before further translation:**

1. **Bitwise operators + compound-assignment** (`#W-54`, `#W-64`). Blocks all protocol/wire code.
2. **Enum raw values** (`#W-45`). **Resolved v0.6.0** (§3.3 enum sugar).
3. **Range literals + range-based `for`** (`#W-37(a)`, `#W-67`). Blocks all counting code.
4. **`??` nil-coalescing** (`#W-21`). **Resolved v0.7.0** (§3.6.1).
5. **`guard let` (single + multi)** (`#W-29`). **Partially resolved v0.7.0** (§3.6.1) — single-binding spec'd; multi-binding still gap.
6. **Dictionary/map type** (`#W-24`). Blocks header/config code.
7. **Numeric type conversion** (`#W-56`). Blocks any code with mixed-width integers.
8. **String interpolation with expressions** (recurring across Ink and here). Already Ink priority #1.
9. **Array literal syntax** (`#W-27`). Universal.

**Design decisions worth revisiting:**

1. **DispatchQueue equivalent** (§1.2). What's the language-level story for serial event processing? Recommend addressing before Stage 0, because it changes what `unsafe:` needs to cover.
2. **Closure syntax + capture lists** (§1.3). Ties into 1.
3. **`inout` or reference-shaped arrays** (`#W-72`). Are arrays value-shaped (needs inout) or reference-shaped (needs nothing)? Design decision.
4. **struct-as-namespace vs. dedicated namespace construct** (§1.1). Small but recurring.
5. **Generic constraints on categories** (`#W-69`). Enable this or not? If not, generic library code has an unclear story.

**Non-issues (design working as intended, confirmed):**

- Typed throws — clarified error-domain expectations at the enum-variant level.
- Categories over extensions — named after what they are.
- State machines emerging from contracts on state — the parity translation was noisy exactly where the idiomatic pass would collapse into invariants.
- `expose` visibility — no friction encountered.

## Cross-Exercise Priority Overlap

Comparing top gaps from Ink log to this pass — items appearing in both are the highest-priority fixes because they've been surfaced independently:

| Gap | Ink item | Websocket item | Status |
|---|---|---|---|
| String interpolation | #22 | #W-55, #W-36, #W-30 | pending |
| `try` semantics | #17 | (recurred but not counted) | pending |
| `guard let` / `if let` | #9 (if let) | #W-29, #W-40, #W-51 | resolved v0.7 (single-binding) |
| Argument labels | #21 | recurring | resolved v0.5 |
| Static/class methods on struct | #16a | #W-26, #W-28 | resolved v0.6 |
| Protocol conformance syntax | #13 | (resolved by reference card) | resolved |
| Optional chaining | #11 | #W-16, #W-32, #W-50 | **retired v0.7** — `?.` removed, `.` nil-flows |

**Six items appear in both exercises.** Prioritize the remaining pending ones first — they're validated across independent codebases.

## Next Steps

**Path A — Fix spec gaps.** Focus on §2.1 (bitwise), §2.2 (enum raw values), §2.4 (collection/range), §2.3 (optional handling). Estimated ~300 lines of spec revision. Would unblock 40+ friction points immediately.

**Path B — Idiomatic pass on the three translated files.** Refactor with `invariants:`, `requires`/`ensures`, state-machine sync. Similar shape to Ink's idiomatic pass. Would validate whether Corvid's contract system actually pays off on state-machine-heavy code (the primary hypothesis this exercise was designed to test).

**Path C — Extend translation to the concurrency layer.** Bring in `Engine/` or `Transport/` to test channel/signal fit on real network I/O.

**Recommendation:** Path B first — same reasoning as Ink's log ("understanding what our design enables over a small controlled sample is more valuable than covering more surface area"). Then Path A once the idiomatic pass has surfaced any additional gaps the parity round missed. Path C is a distinct exercise; defer until the concurrency-related design decisions in §1.2/§1.3 above are settled.
