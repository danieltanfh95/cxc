# Corvid Language Specification — v0.11 (Working Draft)

**Status:** Working draft. v0.11 lands two pieces: (1) **canonical bytes as a value-type base-class property** (§3.3 extended, §3.4.6 new, §3.10.10 new — canonical wire format spec) — records/enums/primitives carry `.as() -> Bytes` and `class function from(source: Bytes)` synthesized from field layout, the same way records already carry synthesized `==` and `hash`. Structural conformance to `As<Bytes>` / `From<Bytes>` follows per §3.4; no separate "auto-conformance" mechanism, no compiler awareness of specific target-type names; (2) **removal of the double-colon header form** (`class Foo: A, B:` and `class Foo: BaseClass:`) — v0.10.2's grace period ends. Migration is mechanical: `class Foo(A, B):` for conformance, `metadata: extends: BaseClass` (body-form) for ObjC inheritance.

**Change log from v0.10.2 → v0.11 (Value-type byte property + double-colon removal):**

**§3.3 extended — Value-type base-class properties enumerated.** `kind: record` and `kind: enum` classes have four base-class properties the compiler synthesizes from field/case layout: structural equality (`==`), structural hashing (`hash(self)`), copy-on-assign, and canonical bytes (`.as() -> Bytes`, `class function from(source: Bytes) -> DecodeOutcome<Self>`). The prior spec listed only the first three (implicit via "structural equality" wording); v0.11 makes canonical bytes explicit in the same list. `kind: regular` classes get none of these automatically — mutable state, potential cycles, identity make mechanical synthesis unsafe.

**§3.4.6 new — Canonical Bytes as a Value-Type Base-Class Property.** Detail spec for the byte methods: what the compiler synthesizes (`.as` + `.from`), when (records/enums; primitives get prelude-shipped methods per §3.11.4 since they have no field layout), how structural conformance to `As<Bytes>` and `From<Bytes>` (§3.4) falls out as a *consequence* of the methods existing, and the override rule (user-written `.as() -> Bytes` shadows the synthesized one per §3.3.2). Missing `.as() -> Bytes` on a field type → compile error at the containing record's declaration. Compiler does NOT special-case any specific protocol name or target type; the machinery is per-class field-layout synthesis plus ordinary structural conformance.

**§3.10.10 new — Canonical Value-Type Encoding.** Wire-format spec for the synthesized byte methods. **Network-order (big-endian) for multi-byte primitives** — silent breakage across architectures is a footgun; performance-critical same-host use cases can opt out by writing their own `.as() -> Bytes`. Records concatenate fields in declaration order. Enums emit discriminator byte + associated data. Strings emit `length: UInt32 || utf8_bytes`. Optionals emit presence byte + payload. Collection literals emit `length: UInt32 || elements`.

**Prelude ships `As<Bytes>` / `From<Bytes>` on all primitives** (§3.11.1 extended). `Int8`/`Int16`/`Int32`/`Int64`/`UInt*` in network-order; `Float`/`Double` in IEEE 754 big-endian; `Bool` as single byte (0/1); `Char` as grapheme's UTF-8 bytes. Also ships `.as_bytes(order: ByteOrder) -> Bytes` per primitive for explicit-endian access (ordinary method, does not participate in `As<Bytes>` dispatch).

**Double-colon header form removed.** `class Foo: A, B:` → **compile error** in v0.11 (was warning in v0.10.2). `class MyViewController: UIViewController:` → **compile error**. Migration was mechanical during v0.10.2's grace period; any lingering uses need updating. Grammar `conformance_clause` is now the sole path for header-declared conformance.

**`mutating` effect retired (§3.5.2).** Dead code surfaced by post-v0.11 audit. Carried over from a pre-v0.6.0 design that had `struct` as a live construct; v0.6.0 unified struct/enum/protocol/class under `kind:` metadata. No live use site: `kind: record` forbids `state:` so records can't mutate; `kind: regular` methods that mutate are inferred as writers by §8.4.1 reader/writer classification, no author declaration needed. Removed from §3.5.2 effect list and from grammar `method_decl` / `effect_atom` productions. Same reason `throws` was retired v0.8.5 — no live semantics under current design.

**§3.4.5 updated** — the retirement subsection reframed from "deprecated in v0.10.2, removed in v0.11" to "removed in v0.11." §10 grammar's deprecation note becomes a historical-record note.

**Only bytes get the base-class property.** JSON, MsgPack, CBOR, and other format-specific target types do NOT have compiler-synthesized methods on value types — they have per-record author choices (field naming, null handling, number precision) with no defensible default, and silent choice would be a footgun. Users write `function as(self) -> Json:` on their records explicitly via ordinary `extend class Foo:` (§3.3.2). Third-party format packages (`corvid_json`, `corvid_msgpack`, etc.) need zero special language support — they ship their target type and users author conformances the same way they'd author any protocol method.

**Reframe from v0.11 initial draft.** Earlier working drafts of v0.11 framed this as "the compiler auto-conforms value types to `As<Bytes>` and `From<Bytes>`" — a target-type carve-out inside a general protocol (design-coherence tension 2, audit 2026-08-10). Reframed at 2026-08-11 to remove the carve-out: the compiler doesn't know `Bytes` is a special target type or `As<Bytes>` is a special protocol; it synthesizes the two methods on every value type as a base-class property (§3.3 extension of what records already do for `==` / `hash`), and structural conformance to `As<Bytes>` / `From<Bytes>` falls out per §3.4 because the methods exist. Same runtime behavior, honest layering: the base-class property is the language commitment; conformance is the consequence.

**Second reframe (2026-08-11) — primitives as records.** The initial tension-2 reframe left a residual: §3.4.6 claimed "primitives have no field layout for the compiler to synthesize from" and §3.11.4 shipped per-primitive `extend class Bool: methods: function as() -> Bytes: ...` blocks in prelude, framed as "concrete implementation of the base-class property." That was still primitive-specialness under a different label. Second reframe removes the residual: primitives are `kind: record` with a single `data: bytes: Bytes where length == N` field, shipped in prelude the same way user records are declared. Byte methods synthesize from the field layout via the same pathway as user records; §3.10.10's wire format gets a refinement-driven length-prefix specialization (`where length == N` → no runtime prefix, `where length <= N` → smallest prefix width fitting N) so synthesis produces the correct primitive encoding without per-primitive overrides. Arithmetic, comparison, IEEE 754 semantics remain compiler intrinsics — the compiler recognizes primitives by name for machine-instruction lowering. Storage remains inline machine words (compiler specialization). §3.11.4 per-primitive prelude blocks retired. §3.1 rewritten to reflect the composition-honest framing.

**Consistency with prior decisions.**

- **§3.3 records + §3.4 protocols** — records already carry synthesized `==` and `hash` as base-class properties. Canonical bytes extend the same list — one more base-class property, no new mechanism.
- **§3.10.4 Bytes as universal substrate** — v0.10.0's substrate commitment was that any type with `As<Bytes>` participates in the serialization protocol. Value types now carry the required methods as base properties, so every value type participates by default via ordinary structural conformance.
- **§3.3.2 rule 6** — synthesized methods are inherited defaults; user-written `.as() -> Bytes` shadows silently. Same shape as protocol-default inheritance.
- **§3.10.3 As<T>/From<T>** — no changes; the protocol/method-name convention (`.as() -> Bytes`, never `.as_bytes()`) is unchanged and unaware of the synthesis. Verified against the mechanism-invention trap memory.
- **§1.5 class-as-universal + §3.4 composition-over-inheritance** — value types get base-class properties automatic (equality, hashing, now bytes); regular classes require explicit user commitment.

**Mechanism-invention trap check.** Passes. `As<Bytes>` protocol → §3.10.3, unchanged. `From<Bytes>` protocol → §3.10.3, unchanged. Compiler synthesis of base-class methods → §3.3 pattern for records extended (same shape as existing `==` / `hash` synthesis), no new mechanism. Wire format spec → new content, not a new mechanism. Zero sibling protocols invented; zero per-target method names invented; zero compiler awareness of specific target-type names; zero "third-party format story" deferred to future versions. Third-party formats use existing extension mechanism.

**Downstream cleanups.** §3.3 extended. §3.4.6 new. §3.10.10 new. §3.11.1 extended with prelude conformances table. §3.4.5 retirement subsection updated (removed-in-v0.11 activates). §10 grammar deprecation note → historical record. PATTERNS.md serialization entry rewritten to reflect actual v0.11 landing. README status. Working-notes. File rename `spec-v0.10.md` → `spec-v0.11.md`.

---

**v0.10.2 (prior — explicit conformance syntax).** Header parens sugar `class Foo(A, B):` becomes canonical; body-form `metadata: extends:` for ObjC inheritance; double-colon deprecated (grace period ended in v0.11).

---

**v0.10.2 changelog — full detail:**

Working draft. v0.10.2 formalizes explicit protocol-conformance syntax (§3.4.5 new), replacing the previously undefined `conformance_clause?` reservation with a concrete parens-list sugar `class Foo(A, B):` plus body-form `conforms:` entries. Retires the double-colon header shape `class Foo: A, B:` (grace period through v0.11). ObjC class inheritance (the only inheritance Corvid supports, driven by interop) migrates to body-form `metadata: extends:` — treating the exceptional case as body-form and the common case (protocol conformance) as header sugar. Additive plus one deprecation; no design-mechanism changes.

**Change log from v0.10.1 → v0.10.2 (Explicit conformance syntax + ObjC-inheritance-to-body-form migration):**

- **§3.4.5 new (Explicit Conformance Declarations).** Formalizes both syntactic forms and their semantics:
  - **Header parens sugar** — `class Foo(A, B):` for the common case. Grammar production `conformance_clause := '(' protocol_type (',' protocol_type)* ')'`.
  - **Body-form entry** — `conforms: A` at class-body top level, or `metadata: conforms: A` — for extensions, multi-line lists, and consistency with extension-body forms.
  - **Semantics** — both forms produce identical conformance-set entries; explicit conformances are compile-time-validated per protocol (fail at the declaration, not at the first use-site assignment); no runtime cost difference vs structural conformance; extensions may add conformances additively.

- **§10 grammar updated.** `conformance_clause` production defined. Header ordering finalized: `class NAME <generics> ( conformances ) where <constraints> :` — visual left-to-right reads as "here's what Foo is, here's the constraint, now the body."

- **ObjC class inheritance moves to body-form.** `class MyViewController: UIViewController:` → `class MyViewController:` with `metadata: extends: UIViewController` in the body. Rationale: pure Corvid design commits to composition-over-inheritance (§1.5, §3.4); inheritance exists only for ObjC interop (subclassing UIViewController, NSObject, etc.). Making inheritance body-form treats the exception as the exception — ergonomic sugar goes to the common case (protocol conformance), not the rare one.

- **Double-colon header form deprecated.** `class Foo: A, B:` and `class MyViewController: UIViewController:` emit a `deprecated-conformance-syntax` warning at parse time in v0.10.2 with fix-it suggestion (`Foo(A, B)` or body-form). Removed entirely in v0.11. Grace period is one minor version — the migration is mechanical and the double-colon form served two purposes with the same shape (a design mistake worth undoing before v1).

- **§4.18 metadata alignment.** `superclass:` and `conforms_to:` metadata keys already existed (line 4694); §3.4.5 just formalizes how they're populated from source declarations. No metadata schema change.

- **Migrations in this spec:** 4 conformance examples (`UserList`, `Projectile`, `DefaultOrderProcessor`, `WSFramer` in validation) → parens form; 1 inheritance example (`MyViewController` in §9.3.1) → body-form `metadata: extends:`; 2 prose references at §3.4.4 (line ~1451) and §9.3.1 area (line ~3769) updated.

**Consistency with prior decisions.**

- **§3.4.4 default method inheritance** — works identically for structural and explicit conformers.
- **§3.3.2 extension composition** — extensions may add conformances via either header sugar or body-form; conflict resolution rules apply if two extensions declare conflicting conformances.
- **§1.5 class-as-universal + §3.4 composition-over-inheritance** — the design commitment is now visible in the surface syntax. Inheritance requires body-form ceremony; protocols get the sugar.
- **§9.3 ObjC interop** — subclassing continues to work; only the surface syntax changes.

**Downstream cleanups:** §3.4.5 new. §10 grammar production defined. §9.3.1 example rewritten. 4 protocol-conformance code examples migrated to parens form. 2 prose references updated. Validation file `websocket/01-Framer.parity.cor` migrated. PATTERNS.md gains conformance-syntax addendum. README status. Working-notes.

---

**v0.10.1 (prior — Pattern subsystem).** Regex/Glob/Literal via one `Pattern` protocol + three concrete conformers (§3.10.9); raw-content prefix-literal aliases `regex"..."` / `glob"..."` / `literal"..."` per new §4.16.17; named captures only. Additive stdlib session.

---

**v0.10.1 also (Convertible retirement).** `Convertible<To>` retired (§3.11.1–§3.11.4) in favor of `As<T>` for lossless conversion + three ordinary methods (`wrapping_to`, `saturating_to`, `checked_to`) for numeric narrowing, plus `ConvertOutcome<T>` domain enum replacing v0.8.5-inconsistent `Result<T, OverflowError>`.

---

**Change log from v0.10.0 → v0.10.1 (Pattern subsystem — full detail):**

- **`Pattern` protocol (§3.10.9.1 new).** Two-method contract: `first_match(source) -> Match?` and `all_matches(source) -> Iterator<Match>`. Every pattern implementation structurally conforms.
- **`Match` / `MatchRange` / `MatchGroup` records (§3.10.9.2 new).** Match carries a backreference to its source String, a MatchRange (StringPosition pair), and a Map<String, MatchGroup> of named captures. Named-only per §3.2 discipline — no positional-capture accessors.
- **Three concrete conformers (§3.10.9.3 new).** `Literal` (exact-string match, no interpretation), `Glob` (`*`, `?`, `[abc]` — filename-style patterns), `Regex` (regular expressions with `RegexOptions` §3.10.9.4). All three are `kind: regular` classes; compiled at construction time; cached when constructed from compile-time-constant source per §4.16.10 value-alias discipline.
- **Prelude pattern literals (§3.10.9.5 new + §4.16.17 new).** Three prelude aliases ship: `regex"^\d+"`, `glob"*.txt"`, `literal","`. Users author their own (`peg"..."`, `fuzzy"..."`, etc.) via `alias @name"..." = Constructor(...)` per §4.16.17 — the new raw-content prefix-literal alias shape, discussed below.
- **§4.16.17 new — raw-content prefix-literal aliases.** Prefix-literal aliases (`name"content"`) may declare `raw` in their alias body to receive the literal content unmodified — no escape processing (`\d` reaches the constructor as `\d`, not as escape-sequence failure). Required for regex source strings; useful for any pattern language that has its own escape rules.
- **Named captures only (§3.10.9.6 new).** Regex source containing unnamed groups `(...)` is a compile error at pattern-construction time; users write `(?<name>...)` always. Consistent with §3.2 named-only discipline and §3.7.1 named-only enum associated values. Enforces "named at the source of the pattern" — the reader sees capture names in the pattern string.
- **§3.10.5 (String) revised.** `split(self, separator: String)` widened to `split(self, pattern: Pattern)` — `Literal(",")` covers the pre-v0.10.1 use case with one more character at the call site. New methods: `first_position(matching: Pattern)`, `all_matches(matching: Pattern)`, `replace_all(pattern:, with:)`.

**Consistency with prior decisions.** Pattern subsystem introduces no new mechanism. Pattern protocol → §3.4 structural conformance. Records → §3.2 named-only. Concrete classes → §1.5 class-as-universal. Literal syntax → §4.16 alias mechanism (with §4.16.17 additive shape variant). No trait-qualified-dispatch games; pattern selection is ordinary polymorphism on the value passed to a String method. Passes the mechanism-invention trap check documented in ~/.claude/memory/corvid-mechanism-invention-trap.md.

**Downstream cleanups:** §3.10.5 String surface widened. §3.10.9 new. §4.16.17 new (raw-content prefix aliases). §4.20.1 prelude alias listing gains three pattern-literal entries. PATTERNS.md gains "I need regex/glob" entry. README status. Working-notes.

---

**v0.10.0 (prior — byte substrate + text/serialization).** Three interlocking changes:

1. **Extension conflict resolution revised from error to warning** (§3.3.2 rule 4). Two same-name extensions across modules no longer produce a compile error at the composing site; instead a warning + deterministic resolution (current-module > imported > original class). `replace` becomes optional annotation for suppressing the warning. Aligns with §7.7 visibility and §3.5 effect-annotation patterns ("warn, don't block").

2. **`As<T>` / `From<T>` parameterized conversion protocols** (§3.10.3). Trait-qualified dispatch permits multiple `as` methods on one class differentiated by target type; multiple `from` methods on one class differentiated by source type. Structural conformance means concrete classes need no explicit `conforms:` declaration.

3. **`Bytes` / `String` / `Char` / `StringPosition` + `Encoding` / `ByteOrder` / `DecodeOutcome<T>`** (§3.10.4–§3.10.7). String is grapheme-native — no integer indexing, no peer views; text operations return graphemes or StringPositions. Bytes is a mutable regular class (matches Array/Map/Set pattern); NSData/NSMutableData bridged. `AsBytes` (via the As<T> protocol family) becomes Corvid's universal serialization substrate — every OO language faces the serialization problem eventually; Corvid ships the primitive from day one rather than bolting it on later.

Prior sessions all preserved (v0.9.1 extension details, v0.9.0 unified extension mechanism, v0.8.7 pre/post bindings, v0.8.6 concurrency model, v0.8.5 no-throws, etc.).

**Companion:** `PATTERNS.md` gains new entries on the conversion pattern (As/From), string handling (grapheme-native, no int indexing), and serialization (universal via AsBytes).

**Name:** `Corvid`. (Historical: named `objc5` throughout the design conversation. Renamed to Corvid when the positioning clarified as "peer of Swift for Apple-ecosystem native development" rather than "next-generation Objective-C.")

**Change log from v0.9.1 → v0.10.0 (Byte substrate; As<T>/From<T> protocols; grapheme-native String; extension conflict resolution revised to warn):**

Major-version-adjacent session. The v0.10.0 bump reflects that the string/bytes/serialization decisions land the language's substrate for text and binary handling — foundational choices with ecosystem-wide effect. Also revises v0.9.1 rule 4 in response to internal review noting the rule was inconsistent with the rest of Corvid's "warn, don't block" pattern for author-intent violations.

**Rule 4 revision (§3.3.2).**

- **Before (v0.9.1):** Two extensions declaring same-name methods → compile error at composing site. `replace` modifier required to shadow.
- **After (v0.10.0):** Same-name method collision *within one class body* → compile error (author self-conflict). Same-name collision *across extensions or between extension and original class* → **warning** + deterministic resolution rule. `replace` modifier becomes optional; when present, suppresses the warning by documenting explicit intent.
- **Resolution priority (highest first):** (1) extension declared in the currently-compiling module; (2) extensions from transitively-imported modules, most-recently-imported first; (3) original class definition.
- **Rationale.** The prior rule was disproportionate to the actual risk and inconsistent with every other author-intent situation in the spec (§7.7 visibility warnings, §3.5 effect advisory-not-enforced, retracted `sealed`/`private`/`final`). Every other place says "warn, don't block"; rule 4 was the only "block." The revised rule preserves the intent (no silent ambiguity) while eliminating the friction on legitimate override cases.
- **Escalation.** Users who want stricter behavior escalate the warning to an error via build config (`-Werror`-style flag). CI can enforce strictness without the spec forcing it.

**`As<T>` / `From<T>` protocols (§3.10.3 new).**

- **Parameterized shape.** `As<T>` and `From<T>` are generic protocols; the type parameter names the target/source respectively. Matches modern OOP conversion protocols (Rust `From<T>`/`Into<T>`, Java `Comparable<T>`, C# `IEnumerable<T>`).
- **Trait-qualified dispatch.** A class conforming to `As<Bytes>` and `As<Json>` may declare both `function as(self) -> Bytes` and `function as(self) -> Json` — they're distinguished by target type at the call site. Compile error only if target type is not inferrable at the call site (diagnostic asks for annotation or explicit specific-name method).
- **Structural conformance.** No explicit `conforms: As<T>` declaration required on the concrete class; any class with `function as(self) -> T` structurally satisfies `As<T>`. Same rule for `From<T>` and `function from(source: T) -> Self`.
- **No auto-derivation between As and From.** Implementing `From<T>` on target does not automatically synthesize `As<Target>` on T. Users write both directions explicitly. Rust's blanket `impl<T, U> Into<U> for T where U: From<T>` is deliberately not adopted — the two directions have distinct semantics (projection vs construction) and should be authored explicitly.
- **Specific method names remain available.** `as_bytes()`, `from_string()`, etc. are legitimate patterns for multi-target classes or where the caller wants the target visible in the method name. The `.as()` / `.from()` convention is for the *canonical* projection/construction where there's one clear choice.

**`Bytes` (§3.10.4 new).**

- **`kind: regular`, mutable.** Matches the v0.9.0 §3.2 pattern for Array/Map/Set — collection containers are reference-shaped classes with state. Corresponds to Foundation NSMutableData at the runtime layer; toll-free bridged.
- **Read operations:** `length()`, `subscript(i)`, `slice(range)`, `concat(other)`, `starts_with(prefix)`, iteration via `Iterable<UInt8>`.
- **Write operations:** `append(byte)`, `append(bytes)`, `append<T: As<Bytes>>(value)`, `subscript_set(i, byte)`, `insert(at, byte)`, `remove(at)`, `clear()`.
- **Conversion:** `as(self) -> Bytes` (identity — returns a copy), `decoded_as(encoding) -> DecodeOutcome<String>`.
- **Construction:** standard class constructors (`Bytes()`, `Bytes(bytes: [UInt8])`, `Bytes(capacity: Int)`, `Bytes(repeating: byte, count: Int)`). Array-literal conformance: `let b: Bytes = [0x42, 0xff]`.
- **Value hygiene.** `x.as() -> Bytes` on any type returns a fresh Bytes; caller can mutate without affecting the source. Immutability guarantees for consumers come from (a) reader/writer classification (§8.4.1), (b) contracts, (c) wrapping in a record.
- **No separate `BytesBuilder` type.** Bytes itself is the builder — construction + mutation happen on the same type. Consistent with Array/Map/Set (no separate ArrayBuilder etc.).
- **`unsafe:` narrows to raw pointer access only.** In-place byte mutation (via `bytes[i] = 42`) is safe because Bytes participates in normal contract discipline. Raw pointer access (`bytes.raw_pointer()` for C interop) is the only reason to enter `unsafe:` for byte work.

**`String` (§3.10.5 new).**

- **`kind: regular`.** Corresponds to Foundation NSString at the runtime layer; toll-free bridged.
- **Grapheme-native.** Text semantics are grapheme-cluster-based. `Char` (§3.10.6) is a grapheme cluster (variable Unicode code points).
- **No integer indexing.** `String[i]` does not exist. Positional access requires a `StringPosition` token obtained via String methods.
- **No peer views on String.** `.utf16`, `.utf8`, `.bytes` are NOT properties on String. Byte-level access is via the universal `.as() -> Bytes` (encodes as UTF-8 by default) or `.as_bytes(encoding: Encoding) -> Bytes` for other encodings. This unifies byte access across all types via the same convention.
- **Methods:** `grapheme_count()`, `is_empty()`, `first_grapheme()`, `last_grapheme()`, `contains(substring)`, `position(after: pos)`, `position(before: pos)`, `start_position()`, `end_position()`, `substring(from: pos, to: pos)`, `substring(matching: Pattern)`, `first_position(matching: Pattern)`, `all_matches(matching: Pattern)`, `split(pattern: Pattern)`, `replace_all(pattern:, with:)`, iteration via `Iterable<Char>`. Pattern subsystem: §3.10.9.
- **Conversion:** `as(self) -> Bytes` (UTF-8 default), `as_bytes(encoding: Encoding) -> Bytes` (specific encoding).
- **Construction:** standard constructors; from-bytes via `Bytes.decoded_as(...)` returning `DecodeOutcome<String>`.

**`Char` (§3.10.6 new).**

- **`kind: record`** — value type, structural equality, copy-on-assign.
- **Represents a grapheme cluster** — one user-visible character, possibly spanning multiple Unicode code points (emoji, family sequences, combining marks all handled correctly).
- **Methods:** `is_whitespace()`, `is_digit()`, `is_alphabetic()`, `is_newline()`, `is_ascii()`, `.as() -> Bytes` (UTF-8), various category predicates.
- **Comparable / Equatable / Hashable.**

**`StringPosition` (§3.10.7 new).**

- **`kind: record`** — opaque position token.
- **Obtained only via String methods** — `s.start_position()`, `s.end_position()`, `s.position(after: pos)`, `s.position(before: pos)`, `s.first_position(of: substring)`, etc.
- **No integer arithmetic** — cannot do `pos + 5`; must use `s.position(after: pos)` repeatedly or `s.position(at_distance: 5, from: pos)`.
- **Ordering:** `pos1 < pos2` if pos1 refers to an earlier position than pos2 in the source string; compile error to compare positions from different String instances.

**Supporting enums (§3.10.8 new).**

```
enum Encoding:
    case utf8
    case utf16_le
    case utf16_be
    case ascii
    case latin1
    # Extensible via prelude aliases per §4.16 for uncommon encodings.

enum ByteOrder:
    case little
    case big
    case network      # alias for .big — network byte order convention
    case host         # aliased at compile time to the platform's native order

enum DecodeOutcome<T>:
    case decoded(T)
    case invalid(offset: Int, reason: String)
    # Per v0.8.5 failure-handling pattern: domain enum, caller switches.
```

**Serialization story (implicit but foundational).**

By convention, any type with an `.as() -> Bytes` method (via `As<Bytes>` conformance) can be serialized. Format-specific layers (JSON, msgpack, etc.) become users of the substrate — third-party packages ship a target type (e.g., `Json`) and users write `function as(self) -> Json:` on their records via ordinary `extend class Foo:` per §3.3.2. No new language support needed for third-party formats; they use the same extension mechanism + `As<T>` protocol (§3.10.3) as user code. Method names are always `.as()` differentiated by return type — never `as_bytes()` / `as_json()`; those are naming-variant slips that betray the committed trait-qualified-dispatch convention (see mechanism-invention trap memory). No reflection-based magic serialization (the trap Java's `Serializable` fell into); regular-class authors write `function as(self) -> Bytes:` explicitly. Value types (records/enums/primitives) get **auto-conformance** to `As<Bytes>` and `From<Bytes>` in v0.11 — not a stdlib layer, a base-class property, the same shape as automatic structural equality on records (§3.2). Compiler synthesizes `.as() -> Bytes` and `class function from(source: Bytes)` from field layout at composition time.

**Stdlib friction items dissolved:**

- **String position API** (Ink #25, `#W-30` position part). Dissolved: `StringPosition` tokens replace integer indexing; text-manipulation methods take positions.
- **Byte-array raw access** (`#W-53`). Dissolved: Bytes has `subscript(i)`, `slice(range)`, direct mutation. Raw pointer access remains in `unsafe:` for C interop only.
- **Byte slice methods** (`#W-52`, `#W-61`, `F8`). Dissolved: Bytes has `.slice(range)` and `.concat(other)`.
- **String from bytes** (`#W-14`). Dissolved: `Bytes.decoded_as(encoding: Encoding) -> DecodeOutcome<String>`.
- **Base64 encoding** (`#W-37e`). Dissolved: extend Bytes via `extend class Bytes: methods: function base64_encoded() -> String: ...` per §3.3.2.
- **Random-number API** (`#W-37c`). Dissolved: extend numeric types (`extend class UInt8: class function random() -> UInt8: ...`).

**`Convertible<To>` retired (§3.11.1–§3.11.4).**

The v0.7.1-era `Convertible<To>` protocol bundled three narrowing semantics (wrap / saturate / checked) as three methods on one protocol, with a redundant `target_type: To.Type` parameter and a `Result<T, OverflowError>` return type that had been spec-inconsistent since v0.8.5 retired `Result` from the prelude.

**Retired in v0.10.0.** Replaced with:

- **`As<T>` (§3.10.3)** for the **lossless** case only — prelude ships conformance for every widening numeric pair. Narrowing pairs have no `As<T>` conformance; `Int32(300).as() -> UInt8` is a compile error with a diagnostic pointing at the three narrowing methods.
- **Three ordinary methods per numeric primitive (§3.11.2)** — `wrapping_to::<T>()`, `saturating_to::<T>()`, `checked_to::<T>() -> ConvertOutcome<T>`. These are *not* a protocol family; the differentiator between them is semantics, not target type, and trait-qualified dispatch (§3.10.3) differentiates by target type. Sibling protocols like `WrappingAs<T>` / `SaturatingAs<T>` / `CheckedAs<T>` would have mimicked the shape of `As<T>` without using its dispatch mechanism — mechanism-duplication rather than consolidation.
- **`ConvertOutcome<T>` enum (§3.11.3)** — replaces `Result<T, OverflowError>`. Domain enum per v0.8.5.

**Design principle surfaced.** When multiple candidate methods share a return type but differ in semantics, the semantics is not a type-parameter question and does not need a protocol family. Per §1.2 (author intent, not authority), the choice belongs at the call site as a method name. Applied here: three narrowing methods on the source type, not three protocols. Same principle applies to any future case where "N semantics for the same signature" tempts a protocol family.

**Migration:** `x.into(T)` → `x.wrapping_to::<T>()`; `x.saturating_into(T)` → `x.saturating_to::<T>()`; `x.checked_into(T) -> Result<T, OverflowError>` → `x.checked_to::<T>() -> ConvertOutcome<T>`.

**Consistency with prior decisions:**

- **§1.5 class-as-universal.** Bytes/String/Char are all classes. No special primitive treatment.
- **§1.9 contracts as coordination.** Bytes' mutation participates in reader/writer classification; §8.4.1 handles concurrent access naturally.
- **§3.4 structural conformance.** As<T>/From<T> conformance is structural; concrete classes need no boilerplate declaration.
- **§3.4.4 default method inheritance.** As<T>/From<T> can carry default implementations; conformers inherit unless they override.
- **§3.3.2 extension mechanism.** Users add As<T>/From<T> methods to any type post-hoc via `extend class Foo:`.
- **v0.8.5 failure handling.** DecodeOutcome<T> is a domain enum per the no-throws discipline; callers `switch`.
- **v0.9.0 § 3.2.** Bytes/String/Map/Set/Array all `kind: regular` (reference-shaped); consistent container discipline.

**Downstream cleanups:** §3.3.2 rule 4 revised. §3.10.3 As<T>/From<T> new. §3.10.4 Bytes new. §3.10.5 String new. §3.10.6 Char new. §3.10.7 StringPosition new. §3.10.8 supporting enums new. §3.4.4 note on trait-qualified dispatch. §3.11.1–§3.11.4 rewritten — `Convertible<To>` retired, narrowing methods (`wrapping_to`/`saturating_to`/`checked_to`) shipped, `ConvertOutcome<T>` enum added, migration table. §3.11.5 `Bounded` renumbered from prior §3.11.2. Friction logs updated (Ink #25, #W-30, #W-53, #W-52, #W-61, #W-14, #W-37c, #W-37e marked dissolved). PATTERNS.md new entries. README status. Working-notes. File rename `spec-v0.9.md` → `spec-v0.10.md`.

**Change log from v0.9.0 → v0.9.1 (Extension conflict-resolution behavior spec):**

Not a design session — v0.9.0 shipped `extend class Foo:` as the unified extension mechanism with the rule "same-name conflict is a compile error unless `replace` is used." v0.9.1 fills in the seven detailed rules covering what a conflict is, how `replace` targets are validated, what happens with two `replace`s claiming the same original, how extension shadowing interacts with inherited protocol defaults, and what runtime visibility looks like at the load-time / compile-time boundary. Also adds an illustrative diagnostic format so implementers have a target and users have a preview.

- **§3.3.2 new subsection: Conflict resolution rules.** Seven numbered rules covering: (1) same-name collision produces compile error at composing site; (2) `replace` requires a shadowing target — no-op `replace` is rejected; (3) at most one `replace` per method per composing site; (4) name matching ignores signatures (Corvid has no overloading); (5) extensions shadow inherited protocol defaults without `replace`, but second-extension-over-first still requires it; (6) runtime method table is unified after composition; (7) load-time transitive visibility (an extension in module B affects Foo globally once B is loaded, even for consumers that reach B transitively).
- **§3.3.2 illustrative diagnostic format.** Example error message showing both conflicting sites, containing modules, and the suggested `replace` fix.
- **§3.3.2 "what is not a conflict" list.** Different methods on same class; same-named methods on different classes; extensions adding invariants (which stack additively per §3.4.2).
- **§3.4.4 cross-reference.** The "interaction with extensions" paragraph now explicitly points at §3.3.2 rule 5 for the shadowing-inherited-defaults case; makes clear that `replace` is not needed to shadow a protocol default, only to shadow another explicit declaration.
- **Ink friction entry (namespace question) closed.** Websocket friction-log #295 marked resolved by existing mechanisms — `class:` section on records covers the struct-as-namespace pattern; modules cover module-level namespacing; no separate `namespace` construct needed.

**Consistency with prior decisions:**

- **v0.9.0 conflict rule.** v0.9.1 elaborates without changing the top-line rule: same-name conflict is a compile error unless `replace` is used.
- **§1.2 author intent, not authority.** Rule 2 (`replace` requires a target) upholds the principle — `replace` claiming a target that doesn't exist would be intent without effect; the compiler enforces the claim's truthfulness.
- **§3.4 structural conformance.** Rule 5 keeps the priority order (own definition > extension > inherited default) intact; extensions shadow defaults without ceremony because "extension is a form of own definition" for the purposes of resolution.
- **§5.1 dispatch.** Rule 6 confirms the runtime picture: after compile-time conflict resolution, the method table has one entry per selector; dispatch is unchanged from any other class.

**Downstream cleanups:** §3.3.2 conflict-resolution subsection + diagnostic example + "what is not a conflict" list. §3.4.4 cross-reference update. Websocket friction #4 (namespace) marked resolved. Working-notes + README status.

**Change log from v0.8.7 → v0.9.0 (Unified extension mechanism; Category retracted; default-method inheritance for structural conformers):**

Major-version bump. Not a "dissolves into existing mechanism" session — this is a **retraction and unification**. The `category(...)` section class was Corvid importing ObjC's specific vocabulary for what is structurally just "declaring more members on this class." Corvid's own class-body syntax already covers that job; the section-class framing paid ceremony (invocation form, `ClassSpec` parameter, section-class machinery) for a job that fits at the class declaration level.

- **§3.3.2 new (Extension Declarations).** `extend class Foo:` is a declaration-position modifier that opens a class body targeting an existing class named `Foo`. Body content follows the same rules as any class body: `data:` (with restriction — see below), `methods:`, `invariants:`, `accepts:`, behavioral sections. Generic parameters and where-clauses at declaration position: `extend class Array<T> where T: UnsignedInteger:` composes with the existing generic-parameter machinery. Multiple `extend class Foo:` blocks across modules stack — the composed member set is the union of all extensions visible in the current compilation unit.
- **§3.4.4 new (Default Method Inheritance for Structural Conformers).** A class that structurally conforms to protocol `P` **inherits P's default methods** for messages the class does not define itself. This makes structural conformance load-bearing rather than a purely static-checking device. Diamond ambiguity (class conforms to two protocols, both provide defaults for the same message, class defines neither) is a compile error at the ambiguous call site — resolvable by defining the method on the class or by upcasting to a specific protocol.
- **§4.10.4.3 (Category) retracted.** The Category section class is removed. Cross-references now point at §3.3.2. Existing `category(target_class: X):` syntax is no longer legal; migration is mechanical (`category(target_class: NSString):` → `extend class NSString:`).
- **Conflict rules.** Two `extend class Foo:` blocks that add methods with the same name to the same class are a compile error at the composing site. To opt into shadowing, one extension declares the method with the `replace` modifier: `replace function foo(self):`. Consistent with §1.2 (author intent, not authority) — the shadowing intent must be visible in source, not implicit in link order (ObjC's fragile "last loaded wins" is retired).
- **State fields still cannot be added via extension.** Adding a `state:` field would change memory layout, which §4.18.3 fixes at compile time. Extensions can add invariants over *existing* state fields, but not declare new ones. Same rule as under the previous `category(...)` mechanism.
- **§5.1 note.** Method-table population is uniform: original definition members and extension members feed the same runtime method table. At dispatch time there is no distinction between "defined here" and "extended in."
- **§7.7 note.** Extension visibility follows module imports. Extensions declared in module B become visible when B is imported. Extensions themselves may carry `expose` on individual members exactly as a definition body can.
- **§10 grammar.** `class_decl` production extended with an optional `extend` prefix. Generic-parameter and where-clause productions unchanged — they compose at the declaration position where they already worked for classes and functions.
- **Ink #W-69, #W-70 resolved by unification.** See friction logs.

**Cost / benefit.**

- **One vocabulary.** `class Foo:` for definition, `extend class Foo:` for extension. Same class-body syntax, same section discipline (`data:` / `state:` / `methods:` / `invariants:` / `accepts:`). No `ClassSpec`, no invocation form.
- **Structural conformance actually pays off.** Before v0.9.0, "conformance is structural" was true but toothless — it enabled static checks but conformers didn't gain the protocol's affordances. §3.4.4 makes conformance carry default-method inheritance, which is what users expect from protocols with defaults.
- **Trade-off: default-method inheritance introduces the diamond problem.** Corvid resolves it via compile-time detection + explicit disambiguation (define the method on the class, or upcast). Users writing broadly-conforming classes need to be aware of this; the compiler surfaces the ambiguity at the call site rather than silently picking one.
- **Trade-off: `extend class` allows extensions from anywhere.** Module discipline (§7.7) constrains visibility — extensions in module B are only visible where B is imported — but the "extension can happen anywhere" property is genuinely more open than a system where classes are closed after declaration. Corvid was already this open (§1.5 runtime as substrate; categories existed); the change is that the mechanism is now integrated with the class-body vocabulary rather than routed through section-class ceremony.

**Consistency with prior decisions:**

- **§1.5 class-as-universal.** Everything is a class; class-body syntax is the primary shape. `extend class` extends that principle rather than fighting it.
- **§1.2 author intent, not authority.** Explicit `replace` for shadowing surfaces the intent; the retired "ObjC last-loaded-wins" hid it in link order.
- **§4.11 sugar keywords.** `record`, `enum`, `protocol`, `section` are class-body keyword sugar; `extend class` fits the same shape family — a declaration-position modifier layered on the `class` primitive.
- **§3.4 structural conformance.** Reading A (conformers inherit defaults) makes the "structural" commitment load-bearing at runtime, not just at type-checking time.

**Downstream cleanups:** §3.3.2 new. §3.4.4 new. §4.10.4.3 retracted (kept as pointer to §3.3.2). §5.1 method-table note. §7.7 extension visibility. §10 grammar. Multiple example rewrites throughout §4 (any example using `category(...)` gets migrated). Friction logs #W-69, #W-70 marked dissolved. `PATTERNS.md` Swift/ObjC/Rust extension entries rewritten. Working-notes + README status.

**Change log from v0.8.6 → v0.8.7 (Pre-state and post-state bindings in ensures; no `old()`):**

Seventh "would-be mechanism dissolves into existing tools" session. A proposal to add `old(expr)` (Dafny/JML style) to the contract system was drafted, then dissolved when the recursion/method-as-transformation reading made it visible that the pre-state was never lost — it's the receiver-and-parameters as inputs. The runtime already snapshots the receiver on method entry to make ensures checkable at all; `old()` was routing that snapshot through a needlessly special-cased name.

- **§3.4.3 new (Pre-state and Post-state Bindings in Ensures Clauses).** Formalizes: inside an `ensures` expression, `self` and parameter identifiers refer to the entry-state snapshot; `result` refers to the post-state (return value for value-returning methods; receiver-at-exit for void methods). No `old(...)` form.
- **§8.4.1 note updated.** Removed reference to `old(...)` as a documentation aid. Reader/writer classification is unchanged; the note now references the pre/post binding rule.
- **Ledger example (§8.4.1) rewritten.** `ensures accounts[from] == old(accounts[from]) - amount` becomes `ensures result.accounts[from] == self.accounts[from] - amount`. Same semantics; new binding form.
- **§10 grammar.** No production added or removed. A grammar note near `method_decl` records the binding rule inside ensures scope.
- **Ink #28 resolved by dissolution.** Not resolved by feature addition. See friction log.
- **`PATTERNS.md` extended.** New entry under Java/C# section (Dafny/JML/Eiffel adjacent): pre-state references in postconditions.
- **Reader.inferred.cor updated.** `advance_index` postcondition rewritten to the new form.

**Consistency with prior decisions:**

- **§1.9 contracts as coordination.** Load-bearing. The runtime already had to snapshot pre-state to enforce state-machine transitions atomically; exposing that snapshot as `self`/params in ensures scope is a naming decision, not a new capability.
- **v0.8.6 reader/writer classification.** Unchanged. A method is a writer if its body assigns to state; the contract's naming of pre-state is not part of classification.
- **Six-session dissolution pattern.** Extended to seven — same shape: check whether existing machinery covers the case before adding a mechanism. Here the machinery is snapshot-on-entry, which was already required for checkability.

**Cost / benefit.**

- **Cleaner.** One binding rule replaces a special form.
- **Deep-vs-shallow capture non-issue.** `self` inside ensures IS the receiver snapshot at entry; whatever "receiver snapshot" means in Corvid's memory model (handle capture for reference fields, value capture for value fields — same as any ordinary assignment) is what ensures sees. No separate `old()` semantics to specify.
- **Mutation syntax unchanged in method bodies.** `self.field = value` in the body still mutates in place. This decision only affects what names mean inside `ensures` expressions.
- **Trade-off.** Users writing methods where `self` in the body clearly refers to the mutating receiver need to remember that `self` in the *ensures* clause refers to the pre-state. Ink #28 required a mechanism regardless; this is the smaller mechanism.

**Downstream cleanups:** §3.4.3 new. §8.4.1 note update + Ledger example rewrite. §10 grammar note near `method_decl`. `PATTERNS.md` entry added. `validation/00-friction-log.md` (Ink) #28 marked resolved. `validation/03-Reader.inferred.cor` `advance_index` rewritten. README status + working-notes.

**Change log from v0.8.5 → v0.8.6 (DispatchQueue equivalent + execution metadata + RWLock-style dispatch):**

Sixth "language mechanism dissolves into existing tools" session. DispatchQueue's role in ObjC/Swift codebases splits into (a) serial mutex for a single owner's state and (b) cross-object synchronization. Corvid's §1.9 (contracts as coordination) already handles (a) implicitly; (b) reduces to "if two objects share an invariant, they weren't really two objects — put them in one class." Nothing new needed for the coordination question. The remaining gaps are thread affinity, QoS hints, and ObjC-side dispatch — filled by a small `execution:` metadata section on classes.

- **`execution:` section on classes (§8.4.2 new).** Declarative metadata: `thread:` (main / any), `qos:` (user_interactive / user_initiated / utility / background), `pool:` (implicit / dedicated). Class-level default with per-method override via nested `execution:` on the method. Runtime uses metadata to place the dispatch pool and enforce thread affinity. Inspectable via §4.18 class metadata.
- **`@main_actor` retired.** Replaced by `execution: thread: main` at class or method level. Same runtime behavior; consistent with metadata-first style.
- **Reader/writer method classification (§1.9 extension + §8.4.1 new).** Compiler infers from method body: reader = does not assign to any `state:` field and does not call any writer method; writer = does. Runtime uses RWLock-style dispatch: multiple readers concurrent, writers exclusive. No user-visible annotation required. `data:` fields (immutable after construction) skip coordination entirely.
- **DispatchQueue mapping documented (§8.4.3 new).** Three-row table: serial-mutex-for-a-class → contracts + `execution:`; off-caller-thread scheduling → `spawn` / `async_scope`; main-thread affinity → `execution: thread: main`. QoS hints via `execution: qos:`. Cross-class pipelines via channels (§8.6, unchanged). Anything that would have needed cross-object coordination reduces to "one class owns the shared state."
- **`coordination_scope` construct considered and retracted.** Every case where separate objects need shared invariants collapses to a single class owning all the state. The class boundary IS the coordination boundary. No new user-facing coordination construct.
- **ObjC bridge extended (§9.3).** Corvid class instances expose a `dispatch_pool` property at the ObjC bridge. ObjC callers use standard `dispatch_async(instance.dispatch_pool, ^{ [obj method]; })`; the pool routes through the class's contract-guarded serialization transparently. Instances declared `execution: thread: main` bind their pool to the ObjC main queue.
- **`PATTERNS.md` created at project root.** Onboarding catalog: coming from Swift/ObjC, Rust, Python/Ruby, Java/C#, FP-family languages, each with a "you'd reach for X; Corvid does Y" mapping and cross-references to spec sections. Closes the accumulated-implicit-knowledge onboarding hazard from six sessions of dissolutions.

**Trade-off worth naming.**

Contracts-as-coordination is a compiler-and-runtime feature — the runtime must enforce the serialization implied by contracts on state. That's non-trivial (Stage 1: runtime checks; Stage 3+: compile-time analysis). Users writing performance-critical code may want to inspect generated serialization to confirm it matches expectations. Compare to DispatchQueue where the mechanism is dead-simple and readable at the call site.

The trade-off is legibility-at-declaration (Corvid: contracts + `execution:`) vs legibility-at-site (DispatchQueue: `queue.async { ... }`). Corvid picks declaration for the same reasons it picks declarative sections over imperative helpers throughout — but it's a real trade-off, not a free win.

**Six-session pattern consolidated:**

| Version | Dissolved | Replacement |
|---|---|---|
| v0.8.3 | `for` keyword, tuples, destructuring | prelude alias over `.for_each`; named-field records |
| v0.8.4 | nested type declarations | flat naming + leading-dot |
| v0.8.5 | `throws`, `try/catch/finally`, prelude `Result<T, E>` | nil-punning; domain enums; switch |
| v0.8.6 | `DispatchQueue`, `@main_actor`, would-be `coordination_scope` | contracts (§1.9); `execution:` metadata; class boundary IS coordination boundary |

**Consistency with prior decisions:**

- **§1.9 contracts as coordination.** Load-bearing. Reader/writer inference is a natural extension.
- **§4.10.7 class kinds.** `execution:` is a class-level section; reuses the sections mechanism.
- **§4.18 class metadata.** `execution:` metadata is inspectable at runtime; matches the metadata-first philosophy.
- **v0.8.5 no-throws.** ObjC bridge lowers domain enums with `.failed(NSError)` cases; `dispatch_pool` interop is orthogonal to failure handling.

**Downstream cleanups:** §1.9 extension (reader/writer classification note). §8.4.1 new (Reader/Writer classification). §8.4.2 new (Execution Metadata). §8.4.3 new (DispatchQueue mapping). §8.9 update (dispatch_pool at ObjC bridge; `@main_actor` retirement note). `PATTERNS.md` new file. README status + working-notes.

**Change log from v0.8.4 → v0.8.5 (Retire `throws`; no `try/catch`; no prelude Result):**

Fifth session in the "language mechanism dissolves into class-based existing tools" pattern. Corvid's structural commitments — class-as-universal (§1.5), nil-messaging inversion (§3.6.1, §5.7), universal method dispatch (§5.1), section-based state machines (§1.9, §4) — already cover the failure-handling ground that `throws` / `try` / `catch` / `finally` were going to occupy in earlier drafts.

- **`throws` effect retired (§3.5 rewrite).** Removed from advisory effects. Functions that can fail return either:
  1. `T` (with nil signalling failure), when the caller only needs to know success-or-not — nil-punning (§3.6.1) handles the rest.
  2. A **domain-specific enum** with a case per failure mode, when the caller needs to discriminate between failure modes for different handling.
- **No `try` / `catch` / `finally` construct.** Not a keyword; not a section class; not a prelude alias. `switch` on the returned enum is the dispatch mechanism, and `switch` was already spec-committed (§3.7). The scoped-cleanup case is handled by `With` section (§4.10.4.5, RAII).
- **No `Result<T, E>` in the prelude.** Generic wrapper adds a layer of abstraction between the caller and the actual failure modes; domain-specific enums with descriptive case names and per-case associated data read better and inspect better (metadata §4.18). If a user writes generic middleware that needs a common failure carrier, they can define their own `Result` enum — but stdlib doesn't push everyone toward it.
- **`panic(msg)` remains the invariant-violation escape.** Halts the process. Not a recoverable failure — a program bug that shouldn't be caught. Existing usage preserved.
- **Failure-handling model documented (§3.5 rewrite).** Table: uniform failure → nil + nil-punning; discriminated failure → domain enum + `switch`; invariant violation → `panic`. Style guide: nesting depth > 3 in a switch chain is a design signal to decompose the function.
- **ObjC interop translated explicitly (§9.3).** ObjC `NSError**` out-param methods are imported as returning a domain enum with `.completed(T)` and `.failed(NSError)` cases; the mapping is per-imported-method rather than blanket. Corvid methods exposed to ObjC that return a domain enum are lowered with automatic `NSError**` when a `.failed(NSError)`-shaped case is present.
- **Ink #17 (`try` semantics) resolved by dissolution.** No `try`. Use `switch`.
- **Ink #29 (`throws` in ensures) dissolves entirely.** No `throws`. Ensures apply on `switch` case discrimination as ordinary contract clauses.
- **Grammar (§10).** `throws` removed from effect_clause. `throw` removed from exit-verb lists in `guard let` / `guard` semantics (§3.6.1 note updated — exits are `return` / `break` / `continue` / `panic`).
- **Example sweep.** All spec examples using `throws E` rewritten to return a domain enum. Same for aliases that expand to `throws`. Worked-example (§11) rewritten. Parity translations updated in a follow-up sweep (validation/*.cor).

**Consistency with prior decisions:**

- **v0.8.4 no nested types, dissolved by convention.** Same pattern: prefer existing tools + convention over a new mechanism.
- **v0.8.3 no destructuring, no tuples.** Same discipline: names on classes over positional syntax.
- **v0.8.3 `for` as prelude alias.** Same: control-flow that could be a keyword is method dispatch on a class instead.
- **v0.7.0 nil-messaging inversion.** Foundation for the "nil is the uniform-failure signal" half of the model.
- **§1.9 contracts as coordination.** Domain enums with `switch` discrimination are a state-machine dispatch — contracts pin down what each state means and what methods transition between them.

**What Corvid gives up.**

Two things worth naming.

1. **Cross-cutting effect propagation.** Rust's `?` and Swift's `try` let a single glyph propagate an error through several layers of function calls without ceremony. Corvid's model requires each layer to explicitly `switch` on the outcome enum and either handle or repackage. This is more code at each layer; also more visible dispatch and no hidden control-flow jumps.
2. **Generic middleware over "any failable computation."** Without `Result<T, E>`, a generic retry combinator can't say "give me any `Result<T, E>` and I'll retry it." The Corvid answer: define a `Failable<T>` protocol conformed to by domain enums, write middleware over that protocol. Slightly more setup; equivalent expressive power.

Both trade-offs are consistent with Corvid's preferences (§1.6 legibility, visible dispatch, class-based over control-flow-based) and the last four sessions' direction.

**Downstream cleanups:** §3.5 rewrite (Effects — remove throws, add failure-handling-model subsection). Multiple example rewrites throughout §3, §4, §5, §7, §8, §11. §9.3 ObjC interop rewrites. §10 grammar production removals. Ink #17 and #29 marked resolved. Working-notes + README status.

**Change log from v0.8.3 → v0.8.4 (Nested types dissolved by convention + leading-dot shorthand):**

Two paired decisions that close `#W-2`, `#W-48`, and Ink #14a.

- **Nested type declarations dissolved by convention (§3.3 note).** Corvid does not add in-body type nesting (`class Foo: enum Bar: ...`). Types conventionally associated with a class are named `<ClassName><Role>` at module scope — `FrameCollectorEvent`, `FrameCollectorState`, `WSFrameOpCode`. Consumers who want brevity in a specific file use module or type aliases: `alias @Event = com.example.ws.FrameCollectorEvent`, then `@Event` per v0.8.2. Cost analysis: nested types force every consumer to pay per-file aliasing overhead (the C# `using Event = FrameCollector.Event;` problem) in exchange for a small ownership-visibility win at declaration; flat naming pushes cost to declaration site (one longer name) and leaves consumers free. Corvid takes the flat side.
- **Leading-dot enum shorthand adopted (§3.7.2 new).** `.name(...)` and `.name` at expression position resolve to `<InferredType>.name(...)` when the compiler can unambiguously infer the target type from context. Contexts: switch scrutinee type, function parameter declared type, function return type, field declared type, variable declared type, comparison with a typed expression. Ambiguous contexts (untyped `let x = .some(3)`, generic-parameter-uninferable positions) produce a compile error asking for explicit type qualification. This is the ergonomic partner of the flat-naming decision — without leading-dot, `FrameCollectorEvent.text(...)` at every switch arm reads noisy; with it, `case .text(let s): ...` reads clean regardless of whether `Event` is nested or flat.
- **`self.` and `Self.` shorthand affirmed in the same spirit.** Inside a class body, `.name` in a context where `Self` is the inferred type is `Self.name`. This is not a new rule — it falls out of leading-dot's existing "target type inferred from context" mechanism — but worth documenting for completeness.

**Consistency with prior decisions:**

- **v0.8.3 no-destructuring, no tuples.** Same discipline family: convention over language mechanism when convention suffices. Nested types could be added as a feature; convention plus module aliases handles the same job at lower ecosystem cost.
- **v0.8.2 `@` marker discipline.** Module aliases (`alias @Event = ...`) already provide the per-file brevity escape hatch; nested types would duplicate that mechanism.
- **v0.8.1 named-only enum associated values.** Leading-dot shorthand for named associated values reads clean because names are already the discipline: `case .closed(reason: let r, code: let c):` — no positional-vs-named ambiguity.
- **§1.2 intent-not-authority.** Convention-based ownership signal is intent; nested-type-syntax would be authority. Corvid consistently picks intent.
- **§4.20 library-first sugar.** Leading-dot is a language-committed sugar; it lives in bootstrap because it's a target-directed name-resolution rule, not a prelude alias — resolution happens during type checking, not before parsing.

**Downstream cleanups:** §3.3 (naming-convention note for class-associated types), §3.7.2 new (leading-dot shorthand), §10 grammar (leading-dot expression production). Friction log `#W-2` and `#W-48` marked dissolved; Ink #14a marked resolved.

**Change log from v0.8.2 → v0.8.3 (Class-based iteration + no destructuring):**

Follows directly from Corvid's structural commitments: class-as-universal (§1.5), universal method dispatch (§5.1), library-first sugar (§4.20). In a class-based OOP model, iteration is a message send to the collection, not a language primitive. C-style `for` is imported convention that should sit in the prelude, not the bootstrap grammar.

- **`for` becomes a prelude alias (§4.20.1).** `for <var> in <collection>: <body>` expands to `collection.iter().for_each { var -> body }`. Grammar production `for_expr` retires from §10 bootstrap. `while` and `loop` stay bootstrap — they are the primitives stdlib uses to implement iteration methods without infinite regress or a TCO commitment. Style guide: user-facing code should reach for `.for_each` / `.map` / `.filter` / `.reduce` on collections; `while` and `loop` are for stdlib and for algorithms that genuinely don't fit iterator abstractions.
- **`Iterable<T>` / `Iterator<T>` stdlib protocols (§3.10 extension).** `Iterable<T>` requires `iter() -> Iterator<T>`. `Iterator<T>` requires `for_each(f: (T) -> Void)` and `next() -> T?` (return-nil-when-exhausted; interacts cleanly with the nil-messaging model from v0.7). Prelude ships conformances for `Array<T>`, `Set<T>`, `Map<K, V>`, `Range<T>`, and any string type via `Iterable<Char>`.
- **`Pair<A, B>` and `Entry<K, V>` stdlib classes (§3.10 extension).** `Pair<A, B>` is the general two-value carrier with `.first` / `.second` accessors. `Entry<K, V>` is the map-iteration carrier with `.key` / `.value` accessors — semantic naming for the map-specific case. Both are `kind: record` (value types). `Map.iter()` yields `Iterator<Entry<K, V>>`.
- **No destructuring bindings (§3.7 extension).** Corvid does not add destructuring in `let` / `if let` / `guard let` / `for` / function parameters. Multi-value extraction always uses named accessors on the returning class. The rule matches v0.8.1's named-only enum associated values — names beat positions, uniformly. `let (a, b) = pair` is not spec'd and will not be. Users write `let a = pair.first, let b = pair.second` or (more commonly) skip the intermediate binding and use `pair.first` / `pair.second` at the reference site.
- **Tuple family friction items dissolved.** `#W-35` (tuple destructuring in `for`), the tuple-type sub-question in `#W-6`, and any request for `(A, B, C)` type syntax are marked "dissolved by design" in the friction logs — not "resolved by feature." Multi-value returns use classes (`Pair<A, B>`, `Result<T, E>`, or domain-specific types like `Position`, `Range`). Dictionary iteration yields `Entry<K, V>`.
- **Grammar (§10).** `for_expr` production removed from bootstrap. New `for` alias production noted at §10 with a cross-reference to §4.20.1. `while_expr` and `loop_expr` remain bootstrap with a note explaining why (stdlib primitive).
- **`Callable<(Args,), R>` remains as-is.** The tuple-shaped syntax in `(Args,) -> R` was already a synthetic parameter-list carrier, not a first-class tuple. Nothing to change; §5.6.1 note reaffirmed.

**Consistency with prior decisions:**

- **v0.8.1** rejected positional associated values on enum cases (named-only). v0.8.3 extends the same discipline: no positional destructuring anywhere in the language.
- **v0.8.0** made collection literals prelude aliases over conformance protocols. v0.8.3 makes `for` a prelude alias over an iteration protocol — same shape.
- **v0.7.0** nil-messaging inversion. `Iterator.next() -> T?` returning nil-on-exhaustion is a natural fit; the auto-lift and nil-flow-through behavior handles termination without exceptions.
- **§1.5** runtime-as-substrate. Iteration via message dispatch matches the ObjC runtime substrate directly; C-style `for` was fighting that grain.

**Downstream cleanups:** §3.7 (no-destructuring discipline note), §3.10 (Iterable/Iterator/Pair/Entry protocols added alongside ExpressibleAs*), §4.20.1 (`for` alias added to prelude contents), §4.20.3 (bootstrap-vs-prelude rationale extended to explain why `while`/`loop` stay bootstrap), §10 grammar (`for_expr` retired). Friction log entries `#W-35`, `#W-6` (positional tuple portion) marked dissolved.

**Change log from v0.8.1 → v0.8.2 (Alias namespace discipline + prelude scope commit):**

Small but load-bearing refinement to the alias mechanism. Two decisions:

- **`@` marker for user-defined aliases (§4.16.16 new).** Every user-defined alias — in every position (delimited, prefix, suffix, infix, class-kind, section-composition, type, value, module) — requires `@` at both the declaration signature's trigger and every use site. Prelude aliases (§4.20) remain unmarked; they are committed surface syntax and the unmarked form is what users expect to read. This gives a clean binary at the reader's eye: unmarked = bootstrap or prelude (language-level); `@`-marked = project-local surface addition (look for a declaration).
- **Parser simplification.** The alias table becomes a distinct namespace resolved via a dedicated lookup path. Pass 1 gathers alias declarations; Pass 2's alias-trigger recognition is deterministic — only `@`-prefixed forms consult the user alias table. The two-pass compilation strategy (§4.16.15) is preserved in structure; the fan-out at Pass 2 shrinks to a single self-flagged escape.
- **No collision with imports.** A user's `@Vec` alias cannot collide with an imported `Vec`; they are separate namespaces. `Vec` remains the import; `@Vec` is the alias. Resolution order questions disappear.
- **Uniform across substitution and surface aliases.** Substitution aliases (`alias @Vec = std.math.Vector3`) follow the same rule as surface aliases (`alias @entity Name: ...`). The uniformity trades a one-character cost at every use site for a globally consistent legibility signal. Corvid's class-based extension model already covers the "brevity via method chaining" ground; alias brevity is not the load-bearing win.
- **`|>` (pipe) rejected from prelude.** In an FP setting where data and behavior are separate, `|>` earns its keep by threading values through module-of-functions. In Corvid — universal method dispatch, cheap extensions/sections, open runtime — method chaining `x.f().g().h()` is the native pipeline shape and covers every case pipe would. Shipping `|>` would create two idioms for the same operation and encourage the wrong extension pattern (free functions instead of extension methods). Projects that genuinely want pipe can declare `alias <lhs> @|> <rhs> ...` locally.
- **§4.16 subsection edits.** Class-kind, expression-position literal, infix, prefix/suffix, and string-interpolation examples updated to show user aliases with `@`. Prelude aliases stay unmarked. Common Rules (§4.16.12) restated with the namespace-separation invariant.
- **§4.20.4 User Extensions.** All examples updated to `@`-marked form.
- **§10 Grammar.** `alias_trigger` production added. `alias_signature` extended with an `@`-prefixed variant for user aliases. Bootstrap operator table unchanged (no `|>`). Prelude alias declarations remain unmarked in signature and use.

Rationale: Corvid's philosophy — user-led extension inside a stable language surface — needs the reader to be able to tell "committed surface" from "project-local surface" at a glance. Without `@`, every unknown identifier could be a prelude construct, an import, a type, or a user alias, and the reader has to grep to know which. With `@`, the reader knows immediately: unmarked names resolve through normal name resolution (imports, locals, prelude); `@`-marked names resolve through the alias table. The `@` cost is one character per use — smaller than the legibility win and smaller than the parser-simplification win combined.

Downstream cleanups: §4.16.4, §4.16.6, §4.16.7, §4.16.8, §4.16.12 (invariant note), new §4.16.16, §4.20.3 (prelude discipline restated), §4.20.4 (user examples updated), §10 grammar productions.

**Change log from v0.8.0 → v0.8.1 (Session 3 — numeric conversion + parameter defaults + multi-binding unwrap):**

Additive session — small, mostly mechanical spec fills for constructs the parity translations depended on. No design-mechanism changes.

- **Numeric conversion (§3.11 new).** `Convertible<From, To>` protocol. `.into(T)` for wrapping conversion (matches §3.9 arithmetic overflow discipline — signed/unsigned narrowing wraps by default, consistent with two's-complement modular arithmetic). `.checked_into(T) -> Result<T, OverflowError>` for callers who want to handle out-of-range. `.saturating_into(T)` clamps to `[T.min, T.max]`. Prelude ships conformances for all numeric primitive pairs.
- **Bounded protocol (§3.11).** `T.max`, `T.min` for numeric types. `FloatingPoint` protocol extends with `.infinity`, `.nan`, `.epsilon`. Small stdlib formalization.
- **Parameter defaults (§3.8.5 new).** Constructor and function parameters may carry `= <expr>` defaults where `<expr>` is a compile-time constant expression: primitive literal, `nil`, or a value-alias reference (§4.16.10). Compiler substitutes the constant at each call site that omits the argument — static insertion, no runtime cost, no shared-mutable-state footgun. Collection literals as defaults are rejected (they allocate); use `nil` + coalesce in the body for runtime-computed defaults.
- **Multi-binding `guard let` / `if let` (§3.6.1 extension).** Comma-separated bindings: `if let x = a, let y = b:` binds both or takes the else branch on first failure. `guard let x = a, let y = b else: ...` same shape. Standard from Swift; small grammar extension.
- **Enum associated values remain named-only.** Retracted earlier proposal to add positional form. Consistency with the rest of Corvid (named args, named record fields, named `accepts`) wins over Swift-familiarity. Style guide notes: when translating positional Swift enums, add names.
- **Grammar (§10).** Parameter default production added. Multi-binding `if_let` / `guard_let` productions added. `Convertible` and `Bounded` are stdlib protocols, no grammar impact.

Rationale: fills concrete parity-translation gaps without touching design mechanisms. `.into(T)` inherits §3.9's wrap discipline for consistency. Parameter defaults use the same compile-time-constant discipline as value aliases (§4.16.10) — one rule for "what counts as a static substitution."

Downstream cleanups: §3.6.1 (multi-binding), §3.8.5 new (parameter defaults), §3.11 new (numeric conversion + Bounded), §10 grammar productions.

**Change log from v0.7.2 → v0.8.0 (Level 2 aliases: pattern-matching + prelude + Session 2 items):**

Aliases become Corvid's syntactic metaprogramming primitive — the mechanism by which surface syntax is defined. Class-kind sugars (v0.7.2) were the first step; v0.8.0 completes the move by extending aliases to expression-position patterns (literals, infix operators, string interpolation) and retiring the language-committed grammar rules for the standard sugars.

**Mechanism (§4.16 rewrite, §4.17 boundaries update, new §4.20 The Prelude):**

- **Pattern-matching alias signatures.** An alias signature can be an identifier, an identifier + body-capturing form, a delimited pattern (`[<items>]`, `{<pairs>}`, `{<items>}`), an infix operator pattern (`<lhs> op <rhs>`), a suffix or prefix operator pattern, or a string-embedded pattern (`${<expr>}`). Match variables in `<>` bind to matched content.
- **Position markers.** `in expression_position`, `in type_position`, `in section_header_position`, `in declaration_position`, `in string_interpolation_position` — declare where the alias applies. Inferred where unambiguous; explicit when the pattern could match multiple positions.
- **Precedence and associativity declarations.** Infix, prefix, and suffix aliases carry `precedence: N` and `associativity: left|right|none`. Precedence integers; higher binds tighter.
- **Content guards.** `where <var> is <TypePattern>` narrows a match — e.g., disambiguating `{<items>}` (Set) from `{<pairs>}` (Map).
- **Bootstrap set formalized.** The compiler ships a minimal grammar sufficient to parse the prelude. Everything user-facing that CAN be a stdlib alias, IS.

**The prelude (new §4.20):**

Spec-committed stdlib aliases shipped with the language distribution. Users get these without imports.

- **Section-composition** (from v0.6.0, unchanged shape): `data:`, `state:`, `methods:`, `static:`
- **Class-kind** (from v0.7.2, unchanged shape): `record`, `enum`, `protocol`, `section`
- **Type-expression aliases** (v0.8.0 — retired from language grammar, now prelude): `T?` (value-type Optional sum), `T!` (reference-type non-nil contract), `[T]` (Array), `{K:V}` (Map), `{T}` (Set), `(A,B) -> C` (Callable)
- **Expression-position literal aliases** (v0.8.0 new, from Session 2): `[e1, e2, ...]` (Array literal), `{k1:v1, ...}` (Map literal), `{e1, e2, ...}` (Set literal)
- **Range operator aliases** (v0.8.0 new, from Session 2): `<lhs> ..< <rhs>` (half-open Range), `<lhs> ... <rhs>` (closed Range)
- **String interpolation alias** (v0.8.0 new, from Session 2): `${<expr>}`
- **Bitwise operator aliases** (v0.8.0 — retired from bootstrap, now prelude): `&`, `|`, `^`, `~`, `<<`, `>>` with C99 precedence
- **Nil-coalescing alias** (v0.8.0 — retired from bootstrap): `??`

**What remains bootstrap (compiler-committed, cannot be user-overridden):**

- Lexical: identifiers, base integer/float/bool/nil literals, base string literals (no interpolation), comments, indentation-based layout
- Base expression grammar: identifier reference, call `f(...)`, member access `x.y`, subscript `x[i]`, arithmetic (`+`, `-`, `*`, `/`, `%`), comparison (`==`, `!=`, `<`, `<=`, `>`, `>=`), logical (`and`, `or`, `not`), assignment (`=` and compound forms per bootstrap operators)
- Base type grammar: identifier + generic args
- Control flow keywords + structure: `if`, `switch`, `for`, `while`, `loop`, `break`, `continue`, `return`
- Section syntax: `label:` followed by indented block; `metadata:` section
- Class/function/module declaration syntax
- The `alias` mechanism itself: signature pattern language, expansion body, `...` placeholder, precedence/associativity/position markers, guards

**Session 2 items land as prelude aliases from the start:**

Collection literals, range operators, and string interpolation are declared in the prelude as alias declarations, not as language-committed grammar. Users can inspect them, and the design decisions from Session 2 (conformance protocol, Swift-shape ranges, `${expr}` delimiter) are encoded in the prelude alias definitions.

- **Collection literal conformance protocol.** Prelude ships `ExpressibleAsArrayLiteral`, `ExpressibleAsMapLiteral`, `ExpressibleAsSetLiteral` protocols. The literal aliases lower to `T.from_literal(items)` where `T` is inferred from context; user types conforming to the protocols accept the literal syntax.
- **Ranges: Swift shape.** `..<` half-open, `...` closed. `Range<T>` type provided by prelude.
- **String interpolation: `${expr}`.** Chosen for consistency with `quote:` block splice points (§4) and for unambiguous escape semantics.

**Retired from prior drafts:**

- Language-committed type-expression grammar (§3.2 in v0.7): `T?`, `T!`, `[T]`, `{K:V}`, `{T}`, `(A,B) -> C` as parser rules. Now prelude aliases.
- Bitwise operators as bootstrap grammar (§3.9 in v0.7.1): `&`, `|`, `^`, `~`, `<<`, `>>` as compiler-hardcoded operator productions. Now prelude aliases with precedence declarations.
- Nil-coalescing `??` as bootstrap: now prelude alias.
- Any remaining "spec-committed sugar" mentions outside the prelude/bootstrap split.

**Rationale.**

Corvid's design philosophy — libraries not language features (Clojure-influenced), runtime as substrate not ceiling (§1.5), one mechanism where possible (§4.17) — pushes toward user-extensible syntax. v0.7.2 unified declaration sugar under aliases; v0.8.0 completes the move for expression-position sugar. The costs (parser coupling to alias resolution, legibility fragmentation risk from user-defined literals, bootstrap discipline) are tractable: parser coupling handled by two-pass compilation with alias caching (§4.16.11), legibility addressed by mandatory tooling support for expansion visualization, bootstrap discipline documented in §4.17.

The philosophical alignment justifies the cost. Users writing domain-specific projects can add domain-specific literal syntax (regex, SQL, quaternions, vector notation) via aliases without waiting for a language-committee decision. Stdlib sugar becomes inspectable rather than compiler-hardcoded. The compiler is smaller; the language distribution (compiler + prelude) provides the standard surface syntax users expect.

**Downstream cleanups.**

§2.7 (Literals) — base literals bootstrap, interpolation prelude. §3.2 (Composite Types) — type-expression forms redirected to prelude. §3.6.1 (Nullability) — semantics unchanged; syntactic `T?`/`T!` become prelude aliases. §3.9 (Operators) — arithmetic/comparison/logical bootstrap, bitwise/ranges/nil-coalesce prelude. §4.16 (Aliases) — rewritten with pattern language, position markers, precedence, guards. §4.17 (Metaprogramming Boundaries) — grammar-extension discipline. §4.20 new (The Prelude). §10 (Grammar) — bootstrap grammar only; note that surface syntax is extended by prelude and user aliases.

**Change log from v0.7.1 → v0.7.2 (sugar unification under aliases):**

Aliases become the single sugar mechanism in Corvid. Previously the language had two sugar systems: `alias` (for section-composition, type, value, module names) and a separate class-kind sugar layer (§4.19 in prior drafts) for `record`, `enum`, `protocol`, `section` keywords. v0.7.2 collapses them — class-kind sugar is now expressed as stdlib alias declarations under the same `alias` mechanism, using a new `...` placeholder to carry body content from the use site through to the expansion.

- **Unified alias shape (§4.16).** One construct: `alias <input>: <expansion>` where the expansion may contain `...` to bind the use-site's body content. Category (section-composition, class-kind, type, value, module) emerges from the shape of the input pattern and expansion, not from a keyword-level taxonomy.
- **`...` body placeholder (§4.16.1).** New syntactic element. Marks the hole in the expansion template where the alias use-site's block content is substituted verbatim. Single hole per alias in v1.
- **Class-kind aliases (§4.16.3).** Named identifier + body form. `alias record Name: class Name: metadata: kind: record; ...` — expands `record Point: ...` to `class Point: metadata: kind: record; ...`. Stdlib ships `record`, `enum`, `protocol`, `section` in the prelude (§4.16.8).
- **User-defined declaration sugar allowed.** Reversing v0.6 §4.19's "no user-defined sugar" stance. Users can define their own class-kind aliases (`alias entity Name: class Name: metadata: kind: regular, ecs_role: entity; ...`) for domain-specific declaration keywords. Tooling MUST surface alias expansion on hover / goto-definition to preserve legibility.
- **§4.19 retired.** Content moved into §4.16.3 (class-kind aliases) and §4.16.8 (prelude declarations). §4.19 becomes a short redirect.
- **Parser: two-pass with alias resolution (§4.16.11).** Name-resolution pre-pass gathers alias declarations from prelude + imports + current source into a resolution table; final parse re-runs with aliases in scope. Aliases cache across incremental compilations.
- **Grammar (§10).** `sugar_class_decl` and `sugar_kind` productions retired. `alias_decl` generalized with `alias_signature` (identifier / parameterized / class-kind forms) and `alias_expansion` (line or indented-body-with-`...`).

Rationale: v0.6 introduced class-kind sugar as a language-committed layer separate from aliases, explicitly rejecting user extension. The v0.7 nil-messaging drill-down surfaced a broader question — "is `alias` the sugar system?" — and working through multi-line sugar showed that class-kind sugars naturally fit under aliases with one syntactic addition (`...` for body content). Consolidating the two systems reduces the mechanism count from two to one, makes the four class-kind sugars stdlib-inspectable rather than compiler-hardcoded, and opens the door for domain-specific declaration keywords consistently with the existing user-defined section/type/value/module alias capability.

Downstream cleanups: §4.16 rewritten (unified shape, `...` placeholder, five subsections replaced with role-based grouping), §4.19 retired (redirect stub), §4.10.7 cross-reference to §4.16.3 for surface syntax, §10 grammar rewrite for alias productions.

**Change log from v0.7.0 → v0.7.1 (Tier 1 lexical + operator sweep):**

Session 1 of the post-nil Category 2 backlog. Direct inheritance from C — zero design decisions, filling spec gaps surfaced by the Starscream parity translations. Unblocks bit-level protocol code, arithmetic, and module-level constant declarations.

- **Numeric literal bases (§2.7).** Decimal (`42`), hexadecimal (`0xFF`), binary (`0b1010`), octal (`0o17`). Underscore separators legal within a literal (`1_000_000`, `0xFF_FF`).
- **Character literals (§2.7, §3.1).** `'a'` produces a `Char` (Unicode scalar). Standard C escape sequences (`'\n'`, `'\t'`, `'\\'`, `'\''`, `'\0'`) plus Unicode escape `'\u{1F600}'`.
- **Bitwise operators (§3.9).** `&`, `|`, `^`, `~`, `<<`, `>>` with C precedence. Compound-assignment forms `&=`, `|=`, `^=`, `<<=`, `>>=`. Legal on integer types (including unsigned); rejected on floats.
- **Modulo operator (§3.9).** `%` for integer remainder (C99 truncated semantics — result sign matches dividend). `%=` compound form. Not defined on floats (use `Double.remainder(_:)` for float modulo).
- **`let` at module scope (§7.1).** Top-level immutable binding: `let PI: Double = 3.14159`. Same shape as function-scope `let`. Evaluated at module-load time; type annotation required.
- **`sizeof` (§9.2).** `sizeof(T)` returns `Int` — the size in bytes of type `T` under its natural layout. For C interop only; not idiomatic in pure Corvid code. `alignof(T)` and `offsetof(Struct, field)` complete the trio.
- **`else if`** — already in v0.7 grammar (§3.8.1); §2.5 keyword note reaffirms it is written as two tokens.
- **Grammar (§10).** Literal productions extended; operator table added.

Rationale: these constructs are unremarkable inheritance from C. The parity translations assumed them because they're universal, but the spec text hadn't stated them. Codifying them here removes ~10 friction points at zero design cost, and unblocks any protocol/wire code path.

Downstream cleanups: new §2.7 (Literals), §2.5 keyword list adds `let` and `sizeof`, §3.1 Char literal note, new §3.9 (Operators), §7.1 module-level `let` note, §9.2 sizeof/alignof/offsetof, §10 grammar productions.

**Change log from v0.6.0 → v0.7.0 (nil-messaging inversion):**

Corvid inverts Swift's nil-safety model. Reference types allow nil at runtime (matches the ObjC substrate), the type system marks non-nil expectations as contracts (`T!`), and message sends on nil receivers propagate through instead of trapping. Force-unwrap and optional chaining — the Swift attractors for hidden panics and per-call syntactic tax — retire entirely. A new stdlib section (`on_nil`) lets classes declare per-method nil-response values, and Collection-protocol conformance provides sensible defaults (`count: 0`, `is_empty: true`, `iter: EmptyIterator()`) for free.

- **Reference types nil-allowed by default.** For classes of `kind: regular` (reference-typed), `T` permits nil at runtime. `T!` is contract sugar for `invariant: value != nil`. `T?` on reference types is retired (redundant). Value types (`kind: record`, `kind: enum`, primitives like `Int`, `Bool`) unchanged: `T` never nil, `T?` remains sugar for `Optional<T>` sum type.
- **Force-unwrap `x!` retired at value site.** No sugar for panic-on-nil. Users write `x ?? panic("reason")` when they genuinely need to fail. Verbosity is intentional — panic should look exceptional in source so it stays exceptional in behavior.
- **Optional chaining `?.` retired.** `.` operates uniformly on non-nil and nil receivers. Nil receivers return nil for reference-returning methods; the call-site expression type auto-lifts to `Return?`. For methods returning `Unit`, nil receivers are a silent no-op.
- **`on_nil` stdlib section (new §4.10.9).** Class or protocol body may declare `on_nil:` as a method-name → nil-time-value table. Compiler generates nil-receiver dispatch to consult the table before falling back to auto-lift. Precedence: class `on_nil` overrides protocol `on_nil`; unlisted methods auto-lift.
- **Collection + String protocols ship default `on_nil`.** `Collection` conformers inherit `count: 0`, `is_empty: true`, `iter: EmptyIterator()` on nil receiver. `String` similarly for `length` / `is_empty` / `chars`. Users writing their own collection types get nil-punning by protocol conformance, not per-class ceremony.
- **`T!` contract violations warn, don't panic.** Runtime observes nil in a `T!`-annotated field or parameter → logs a warning (dev) or configurable action (prod). Nil continues to flow through message sends. `T!` is a debugging aid asserting author expectations; safety comes from nil-punning being safe.
- **Arithmetic and ordering on nil are type errors.** `nil + 3`, `nil < 3` don't propagate — must unwrap or use `??`. Equality (`==`, `!=`) punning is safe: `nil == nil` is true, `nil == x` is false. Ordering with nil has no coherent semantics.
- **ObjC nullability mapping updated (§9.3.2).** ObjC `nullable` maps to Corvid `T`. ObjC `nonnull` maps to `T!`. Un-annotated ObjC types map to `T` (nil-allowed default; matches ObjC runtime truth without warning noise).
- **Grammar (§10).** Type production adds `!` suffix (reference-type contract). `?` suffix narrows to value-type Optional-sum only. Value expressions: `?.` and value-position `!` productions removed.
- **Rationale.** Swift's nil-safety model paid two visible taxes: `?.` at every optional call site (readers stop seeing it) and `!` at value site (attractor for hidden panics as codebases evolve). Corvid inherits the ObjC runtime, which already allows nil for every reference — Swift's type system was fighting this truth and paying the tax. v0.7.0 reverses the direction: nil is the baseline for references, non-nil is the exception marked by contract. The Clojure-style pun-on-nil semantics (via ObjC runtime + `on_nil` for user code) removes the safety motivation for the taxes, so the taxes go too.

Downstream cleanups: §3.1.1 (`Any?` note removed — `Any` allows nil directly), §3.2 (Optionals split into reference vs value cases), §3.4.2 (Connection example — retire `Socket?` and `socket!.send`), new §3.6.1 (Nullability and Nil-Punning), new §4.10.9 (`on_nil` section), new §5.7 (Nil-Receiver Dispatch), §6.14 (Reference Modifiers — retire redundant `?` on `weak`), §8.6 (channel section field types), §9.3.2 (ObjC nullability round-trip), §10 (grammar). Roughly 15-20 substantive edits across the spec.

**Change log from v0.5.2 → v0.6.0 (structural unification):**

Consolidates struct, enumeration, and protocol into a single declaration form: `class + metadata: kind: X`. Sugar keywords (`record`, `enum`, `protocol`) desugar losslessly to the unified core. Extends the concurrency model with channel modes and formalizes closures.

- **Class-as-universal.** Retired `struct` and `enumeration` as top-level type-declaration keywords. All user types declare via `class`, with `metadata: kind:` selecting the variant (`regular` default, `record`, `enum`, `protocol`, `section`). `protocol` was already sugar in v0.5 and remains so; `struct`/`enumeration` disappear entirely as core forms.
- **`metadata:` section (new).** First-class user-writable section on any class declaration. Must appear first if present. Contains spec-reserved keys (`kind:`, `raw_type:`, `mode:`, `capacity:`, `on_full:`) and user-defined keys. Populates the runtime-introspectable class metadata described in §4.18.
- **`record` sugar.** `record Foo:` desugars to `class Foo: metadata: kind: record`. Records opt out of universal extents, disallow `state:`, get structural equality and copy-on-assign.
- **`enum` sugar with raw values.** `enum Foo: case a = 1; case b = 2` desugars to `class + metadata: kind: enum, raw_type: <inferred>` with auto-generated `raw_value(self)` and `class function from_raw(value:)`. Raw type inferred from case values (smallest fit); overridable via `metadata: raw_type:`. Resolves the widest single interop gap in v0.5.
- **`protocol` sugar clarified.** Under `kind: protocol`, `accepts:` section holds required signatures, `methods:` section holds default implementations. `accepts foo(...)` per-method form remains legal as sugar for single-requirement protocols.
- **Type-expression sugar formalized.** `T?` = `Optional<T>`, `[T]` = `Array<T>`, `{K: V}` = `Map<K, V>`, `{T}` = `Set<T>`, `(A, B) -> C` = `Callable<(A,B), C>`. Sugar is lossless; expanded forms always legal. New sugar requires spec commitment.
- **Everything is an expression.** `if`, `switch`, block, and expression-position `do:` produce values. Field/parameter/return initializers accept control-flow expressions directly. Blocks return the value of their final expression unless an explicit `return` is present.
- **Loops return Bool.** `while` and `for` produce `Bool` — `true` if the loop exhausted its condition/iteration naturally, `false` if exited via `break`. Break-with-value is not supported on `while`/`for`; use `loop { ... break value }` (Rust-style infinite loop) if a T-typed return is genuinely needed.
- **Closures are anonymous classes.** A closure literal desugars to an anonymous `class` with a single `call` method. Sugar hierarchy: `function(x: T) -> R: ...` (explicit) → `x => x + 1` (arrow, inferred types) → method reference (`self.foo`). Strong capture by default; `[weak self, ...]` prefix opts into weak capture per variable. Compiler warns on closures capturing `self` strongly and stored in a field of `self` (classic retain-cycle pattern).
- **Channel modes (§8.6).** `channel:` section extends with `metadata: mode:` — `work` (default, CSP-style competing consumers), `broadcast` (all subscribers see all items, slowest sets pace), `pub` (topic-partitioned, requires `topic_fn:`). Direct field entries (`input: Bytes`) declare channel items; composition with nested `state:` retains the v0.5 state-change auto-publish behavior. Unbounded by default; opt into `metadata: capacity: N` + `on_full:` for bounded.
- **Grammar (§10).** `struct_decl` and `enumeration_decl` productions retired. Sugar keywords `record`, `enum` added and specified as desugaring rules; `protocol` sugar clarified. `metadata:` section usage documented alongside `data:`/`state:`/`methods:`. Loop expressions typed `Bool`.
- **Rationale.** The v0.5 spec carried three separate declaration keywords for what is structurally the same construct: a named collection of members with different capability sets. Under kind-as-metadata, one construct handles all four (record, enum, protocol, section) plus regular classes uniformly, and users write the sugar they already recognize. The interop payoff is immediate — enum raw values (the top-frequency interop gap in the Starscream translation pass) collapses to a single spec-committed feature.

Downstream cleanups: §3.3 (defers to §6), §3.4 (protocol via kind), §3.7 (expression form extended), new §3.8 (loop expressions), §4.7 (class body includes `metadata:`), §4.10 (built-in kind section classes), new §4.11 (sugar layer), §6.1 (rewrites type categories as class kinds), §6.2 (record/enum runtime models), §8.6 (channel modes), new §5.6 (closures), §10 grammar. Roughly 40-60 substantive edits across the spec.

**Change log from v0.5 → v0.5.1 (authority cleanup):**

Sharpened §1.2 (intent, not authority) into a strict discipline. Every keyword that expressed enforcement the runtime doesn't provide was removed:

- **Removed `sealed`** — as sum-type marker (§3.4.1) and as dispatch hint (§5). Enums are closed by construction; protocols are open. Compiler devirtualizes automatically where safe.
- **Removed `dynamic`** — meaningless once `sealed` is gone. All methods are semantically dynamic; devirtualization is an optimization the compiler makes without source hints.
- **Removed `final`, `open`** — same problem. "No override allowed" was authority the runtime doesn't enforce.
- **Removed `private`, `package`, `internal`** — as compile-time enforcement. Under intent-not-authority, absence of `expose` communicates internal-by-convention; compiler warns on external access rather than erroring.
- **Renamed `public` → `expose`** — verb form matches the intent-based vocabulary (`accepts`, `requires`, `ensures`). Emphasizes the deliberate act of exposure.
- **Removed module-level dispatch policy** (`sealed module`, `dynamic module`). Optimization policy is a build-tool concern.

Downstream cleanups: §2.5 keyword list, §3.4.1 (closed sum types from enums, not sealed protocols), §3.7 pattern matching, §4.10.1 primitive sections (dropped Sealed/Dynamic/Private/Public/Package, renamed Public→Expose), §4.10.2 built-in aliases (no longer include visibility by default), §5 dispatch model, §7.7 visibility (two states, not three levels), §10 grammar sketch, §11 worked example, §14 Stage 1 primitives list. Roughly 60-80 substantive edits across the spec.

Rationale: §1.2 committed to intent, not authority — but the language kept authority-flavored keywords that pretended to enforce restrictions the runtime blows through anyway. Users writing sealed/private/final got a false sense of protection while any runtime consumer could work around them via metadata queries, categories, or swizzling. Removing them makes the language honest about what it does.

**Change log from v0.4:**

**Metaprogramming unification (§4):**
- All compile-time user-definable constructs are section classes. Primitives, aliases, and behavioral sections differ only in which capabilities they use.
- Aliases are now section classes with `expands_to:` capability. `alias name: ...` syntax is preserved as sugar for the common case.
- Section classes gain **parameters** — type parameters, effect parameters, value parameters. Parameters bound at use site.
- Section class capability vocabulary formalized: configuration, parameters, expands_to, state, generators, processors, validators, describers, invariants, runtime methods.
- Explicit boundary rules: no higher-order sections, no section-generates-section, no cross-section inspection, no arbitrary compile-time computation.
- Bootstrap set: `Data`, `State`, `Methods` sections plus member kinds. Everything else expressible.

**Class metadata as shared artifact (§1.7, §4.18):**
- Class metadata is a first-class compilation output, generated once at compile time.
- Three consumers share the metadata: LSP/IDE tooling (Consumer 1), runtime metadata queries (Consumer 2), external tools via AI-manifest JSON (Consumer 3).
- Reflection is reframed as metadata query, not runtime discovery. Class structure is compile-time-fixed; the runtime exposes but does not create structure.
- Section describers (new capability §4.5.10) declare what each section contributes to the metadata.
- Compiler is architecturally incremental from Stage 1; LSP server ships with the compiler.
- Explicit non-goals: no runtime class creation, no `eval`, no arbitrary structural modification of instances.

**Memory model resolved (§6):**
- Three type categories: struct, enumeration, class. All share the section mechanism.
- One-bit distinction: struct/enumeration disallow `state:` sections; class allows them. Everything else (value vs. reference semantics, static vs. dynamic dispatch, extent participation, ARC) follows from this restriction.
- **Universal extents** for all user-declared classes. No opt-in required. `Class.query()`, `Class.all()`, `Class.count()` universally available.
- **Handle-based references** for all class instances. Compiler optimizes to direct pointers for scope-local references. FilC-style validation on cross-scope references.
- **Tags (`Tag<T>`)** own contiguous storage regions within a class's extent. Iteration is cache-optimal. `dense_storage:` section enables SoA layout.
- **Snapshots (`Snapshot<T>`)** are materialized handle arrays for query results and cross-tag operations.
- ARC as reference lifetime mechanism; `weak`/`unowned` for cycle breaking. Extent-scoped cycle detection as Stage 2+ optional library.
- ECS emerges as a natural pattern from tags + protocol queries. No language-level ECS commitment.
- Explicit non-goals: no SQL, no persistence, no ACID, no mandatory GC, no user opt-out from extent tracking (use struct instead).

**Concurrency resolved (§1.9, §8):**
- New design premise §1.9: **Contracts as coordination primitives.** Methods with `requires`/`ensures` on mutable state have atomic check-and-transition semantics. Correctness contracts, synchronization primitives, and state machine semantics unify into one mechanism.
- No committed concurrency model. Language provides primitives (state machines, channels, signals, async, threads); users compose.
- **Channel section** (Stage 1 built-in) — asynchronous event streams. Instance-level and class-level subscription. Buffered/unbuffered, cancellable, backpressure-aware.
- **Signal section** (Stage 1 built-in) — one-shot completion notifications. Set once; awaiters unblock.
- **Observable section removed.** Users wanting synchronous callbacks use delegate protocols; users wanting change notification use channels; users wanting one-shot completion use signals.
- **State machine synchronization** — `requires`/`ensures` on mutable state get atomic transitions (runtime-enforced Stage 1, compile-time-analyzed Stage 3+). Async methods wait on unmet preconditions; sync methods fail immediately.
- **Structured concurrency** — `async_scope`, `spawn`, structured cancellation, error propagation.
- **Sendable types** — inferred from shape (value types, class handles, function values with sendable captures).
- **Threads and parallelism** — explicit threads via `unsafe:` (Stage 1); `parallel_for` for row-level parallelism (Stage 3+).
- **ObjC interop** — GCD queue bridging, completion handler ↔ async conversion, `execution: thread: main` for main-thread affinity (§8.4.2; supersedes prior `@main_actor` marker).

**Module system resolved (§7, §4.16 generalized):**
- Three distinct levels: **source** (single file), **module** (compilation unit, possibly multi-file, with unified metadata), **package** (distribution unit, versioned).
- **Package section** (Stage 1 built-in) — manifest as a section. Three forms: full `package.cor` file, embedded in-script header, implicit for scripts without dependencies.
- **Aliases generalized** (§4.16 rewrite) — one construct for all four categories: section-composition, type, value, module. Single mechanism for compile-time text substitution.
- **Implicit resolution with optional explicit imports.** Compiler resolves names against manifest-declared packages; `import` statements optional for disambiguation or readability.
- **Explicit source declaration via `from` clause** in dependencies and imports (local paths, URLs, git refs, private registries).
- **Standard library implicit.** No imports needed for stdlib.
- **Two visibility states:** `expose` (part of the module's stable interface) or absent (internal by convention). Compiler warns on external access to non-exposed members; users configure strictness. No `public`, `private`, `internal`, `fileprivate`, or `public(package)` keywords — see §7.7.
- **Single-file scripts** work with zero ceremony: `oc5 hello.cor` runs a script; embedded `package:` for scripts with dependencies.
- **Deferred to post-v1:** package manager tooling, registry protocol, version resolution algorithm, workspace concept.

**Pre-v1 design stance (§1.8):**
- Explicit commitment that pre-v1 versions may break to simplify or unify the design.
- Post-v1: additions welcome, removals require major version bumps, semantic changes require migration paths.

**Class-level contracts (§3):**
- New `invariants:` section for class and struct bodies. Contains predicate expressions that must hold at every observable point.
- Compiler-enforced in Stage 3+ where analyzable; runtime-enforced in Stage 1.
- Section classes get invariants (they ARE classes).

**State machines emerge from contracts:**
- Session types / state machines are not a separate feature. They emerge from class-level invariants + method-level `requires`/`ensures`.
- Classes with state fields + invariants over those fields + preconditions on methods effectively encode state machines.
- Compile-time state tracking deferred to Stage 3+; runtime tracking in Stage 1.

**Pattern matching / switch (§3):**
- `switch` keyword for pattern matching (not `match`). Extends C/ObjC `switch` with pattern support, exhaustiveness for closed sums, expression form, no fallthrough.
- Closed sum types come from enumerations, not from a `sealed` keyword on protocols. Protocols are always open interfaces; enumerations are always closed.

**Effect polymorphism (§3.5.3):**
- Generic parameters can range over effects (`E: Effect`).
- Explicit form: `function map<T, U, E: Effect>(items: [T], f: (T) -> U with E) -> [U] with E`.
- Inference sugar: when a function takes function-typed parameters and doesn't specify effects, effects are inferred from callback usage.

**RAII (§4.10.4.5):**
- `With` section for scoped resource management. Deinitializers run in reverse declaration order on scope exit.

**Design premises (§1.6 new):**
- **Legibility over brevity** — code is optimized for readers, not authors. Especially load-bearing given AI-authored code and human review workflows.

**Previously-deferred items resolved:**
- Section versioning: semver on section classes (§4.15 expanded).
- Flat composition sugar: removed from deferred; nesting handles it.
- User-alias export: aliases participate in module import/export system.
- Consumer-side effect strictness: `--strict-effects` flag and `#strict` pragma.
- `static:` at module scope removed — bare declarations at module level instead.
- `Computed` no auto-caching. Users memoize with `state:` or use library caching sections.
- Cooperative-only threading for v0.1; preemptive Stage 3+.
- Reflection API surface formalized (§4.17 new).
- Error message and provenance commitments formalized (§4.18 new).
- Blocks, closures, `instancetype` interop rules (§9.3 expanded).
- AI-manifest JSON format shipped in Stage 1 (§9.7 new).

**Change log from v0.3:** *[see v0.4 for details; superseded by unification above]*

---

## 1. Design Premises

Five commitments, from which the rest derives.

### 1.1 Message passing as the semantic primitive

Method calls are messages sent to objects. The runtime knows how to route messages, register handlers, and introspect the message table. Every method is semantically dispatchable at runtime. Static dispatch is a compilation strategy, not a semantic model.

### 1.2 Author intent, not authority

Library authors express intent about how their code will be used. Annotations that describe intent are welcome; annotations that enforce restrictions on downstream users are not.

**What's intent:**
- `expose` — this is part of the interface I designed for external use. Depend on it.
- `async` — this method may suspend. Await it.
- `requires`/`ensures`/`invariants` — these predicates document and enforce correctness at method boundaries.
- Failure is expressed in the return type (nil or a domain enum), not as an effect (§3.5.4).
- Section membership — this declaration participates in the sections named.

**What we removed as authority:**
- No `sealed` or `final` — "you can't extend this" was a lie. The runtime always allows extension (categories, swizzling, dynamic proxies). If the compiler couldn't enforce it, we shouldn't pretend it does.
- No `dynamic` as a source annotation — meaningless without `sealed`. Everything is dispatched dynamically; the compiler devirtualizes when it can prove safety, and deopt machinery handles the surprises.
- No `private` — "you can't touch this" is the same authority pattern. Absence of `expose` communicates internal-by-convention. The compiler warns on external access; users choose whether to respect the warning.

**Downstream code always retains full capability** — to hook, override, extend, introspect, or modify — via the runtime. Metadata queries (§4.18) return everything the compiler knows regardless of `expose`. Categories add methods to any class. Swizzling replaces implementations. This is Objective-C's runtime philosophy carried forward honestly.

**Non-consequence:** intent-not-authority does not apply to semantics that change compilation itself. `async` changes the calling convention; mutability annotations change struct storage semantics; type shapes determine memory layout. These are semantic in the sense that different values would be produced under different rules, so they cannot be "just hints." See §3.5.

### 1.3 Contracts replace nominal types

A "type" is a named contract: a set of accepted messages with signatures, preconditions, postconditions, effects, and (optionally) session constraints. Conformance is structural. Named contracts (`protocol Foo`) reserve identity when the author wants to disambiguate structurally identical concepts.

**Types-as-atoms unification (v1.0 target).** Under the atom model (§1.11), **types ARE atoms**. Every construct that was called a "type" in the pre-atom framing is a named atom shape: `Int` is an atom (a record with `data: bytes: Bytes where length == 4`); `String` is an atom; `Point` declared by the user is an atom; `List<T>` is a parameterized atom; a protocol is an atom signature (I/O + contracts, no transition body). Type-checking is atom-shape-conformance — structural, no refinement propagation, no dependent-type-adjacent verification.

**Predicates on values are invariants and contracts, not refined types.** Corvid does not ship refinement types at the type-signature level. There is no `atom Nat: Int where value >= 0` — no floating type-name that abstracts a predicate away from the state or transition it constrains. Predicates on values live where they matter:

- **Invariants on atoms** carry single-atom or cross-atom constraints (§3.4.2). Bare field names are in scope inside `invariants:` (no `atom.` pronoun required):

    ```
    atom Counter:
        state:
            count: Int = 0
        invariants:
            0 <= count <= 100                # chained comparison — reads mathematically
    ```

    Corvid supports Python-style chained comparisons: `0 <= count <= 100` is sugar for `0 <= count and count <= 100`, with `count` evaluated once. This is the reading closest to standard mathematical notation for range predicates; it composes naturally with `and` / `or` for compound conditions.

- **Preconditions and postconditions on transitions** carry use-site constraints:

    ```
    atom sqrt(x: Float) -> Float
        requires x >= 0
        ensures result * result == x
    ```

- **Reusable predicates as boolean-returning atoms** carry named predicate expressions (predicate atoms — leaf atoms with Bool output; invoked from invariants and contracts):

    ```
    atom is_percentage(n: Float) -> Bool:
        return 0.0 <= n <= 1.0
    
    atom Foo:
        state: rate: Float
        invariants:
            is_percentage(rate)
    ```

**Why the ban.** Refined types (`Nat`, `BoundedNat`, `Email`, `Percent` as distinct type-names carrying predicates) proliferate names in the type space and abstract constraints away from where they hold. The constraint on Counter's count is *Counter's* constraint — putting it in Counter's `invariants:` keeps the predicate co-located with the state it constrains. Reusability via predicate atoms costs one line at the invariant (`is_percentage(rate)`) instead of one type declaration + one signature site + refinement-inference machinery. Types-as-atoms stays the unification; predicates stay contracts. Two systems, each first-class, composable — but one is about shape, the other about predicates. Not conflated.

**What's traded away.** Named types that carry predicates at the signature level (`atom foo(n: Nat)` reading "n must be non-negative"). Compile-time refinement inference (`nat1 + nat2 : Nat` proved from `nat1, nat2 : Nat`). The Liquid-Haskell / Whiley / dependent-type direction. In its place: contracts on transitions (`requires n >= 0`), invariants on atoms, and predicate atoms for reusable predicates. Constraints are more localized (co-located with what they constrain); type-checking stays a shape-conformance question (simpler compiler, faster feedback); users familiar with Swift / Kotlin / Rust find the model familiar (predicates as assertions, not as types).

See §3.4.2 for the invariant mechanism. §1.11 for the atom model that this unification lives inside.

### 1.4 First-class primitives for what dispatch was faking

Observation, interception, extension, delegation, factories each have dedicated language constructs — many of them expressible as user-defined sections. Runtime dispatch is available but rarely load-bearing.

### 1.5 Runtime as substrate, not as ceiling

The language compiles to native code targeting `libobjc2` (GNUstep's Objective-C runtime, MIT-licensed, cross-platform). The runtime provides dynamic dispatch, method registration, introspection, and call-site invalidation on swizzling. Interop with C, Objective-C, and (via runtime bridges) Swift is first-class.

### 1.6 Legibility over brevity

The language optimizes for readers, not authors. Code that is easy to review, verify, and reason about locally takes priority over code that is minimally typed.

This premise is especially load-bearing given that a substantial portion of Corvid code will be AI-authored. Humans are the primary readers of code they didn't write, and the language's structural signals — sections, contracts, explicit types, invariants, effect annotations — exist to make review tractable. Verbosity at the declaration site is a small cost paid once; opacity at the review site is a cost paid every time.

**Consequences:**
- Public API types are explicit (no inference across module boundaries).
- Sections are explicit at class scope; alias sugar available for common cases.
- Effects are explicit on function types; inference only within a compilation unit.
- Contracts are explicit; runtime enforcement documents intent.
- Section-generated code carries provenance information visible to tooling.

**Non-consequences:**
- Not "verbose everywhere" — local reasoning benefits from concision when the meaning is clear.
- Not "no inference" — inference within a function body is fine; inference at API boundaries is not.

### 1.7 Metadata as shared artifact

Class metadata — the structural description of a class including its sections, generated members, contracts, and provenance — is a first-class compilation output. It exists once, generated by the compiler, and is consumed by three separate systems:

1. **Tooling at authoring time.** Language servers (LSP), IDEs, IntelliSense, AI code assistants. These need to know what methods a class has, what the section instances expose, what contracts must be satisfied — all while the user is editing, before compilation completes.

2. **The runtime itself.** Cross-module lookups ("give me the metadata for class X in module Y"), dynamic dispatch, category application, subclass creation. All of these read metadata generated at compile time; the runtime does not create new class structure.

3. **External tools.** AI-manifest JSON exports, documentation generators, static analyzers, migration tools. Systems that read the metadata to reason about code without executing it.

**Consequence:** the reflection/introspection API is not "look at what exists at runtime" but "query compile-time metadata that the runtime holds in memory." Class structure is compile-time-fixed; nothing structural is created at runtime. This is both a design commitment and a substantial simplification — most reflection APIs collapse together "runtime discovery" and "metadata query" and pay the cost of both.

**Consequence:** the compiler is architecturally an incremental system with a language-server mode from Stage 1. It holds state between invocations, exposes metadata queries against that state, and emits metadata artifacts alongside object files. Batch compilation is a mode; language-server operation is another mode. Both use the same underlying pipeline.

**Consequence:** sections are the mechanism by which metadata is generated. Section describers (§4.5.11) declare what a section contributes to the class's metadata. Built-in sections have known describers; user-definable sections gain describers as a capability in Stage 2+.

This premise justifies the incremental compiler architecture, the unified metadata format, and the framing of "reflection" as metadata query rather than runtime discovery.

### 1.8 Pre-v1 design stance

Until v1.0 ships, the language is pre-stable. Any design decision may be revisited if a bolder or more unifying alternative emerges. Simplification wins over compatibility with earlier drafts. Sections marked *[TENTATIVE]* or *[UNRESOLVED]* are the most likely to change, but no part of the spec is off-limits for reconsideration.

**Once v1.0 ships:**
- Additions are welcome (new sections, new library types, new syntactic sugars).
- Removals require major version bumps and formal deprecation cycles.
- Semantic changes require deliberate migration paths, translation tools, and lead time.

**Consequence for tool developers:** treat pre-v1 spec versions as intentionally unstable. Tools targeting v0.x may need updates between versions. This is the trade-off for getting the design right; the language will hold still once we've earned it.

**Consequence for design work:** if a new insight would improve the design at the cost of breaking earlier decisions, we take the insight. Version numbers are cheap; getting the design wrong is expensive.

### 1.9 Contracts as coordination primitives

The contract system (preconditions, postconditions, class-level invariants) is not solely a documentation or verification feature. It is also the language's concurrency coordination mechanism.

Methods with `requires`/`ensures` clauses on mutable state have atomic check-and-transition semantics: the precondition check, method execution, and postcondition establishment happen as a single atomic operation with respect to other threads. Threads calling a method whose precondition is not yet met either wait (if the method is `async`) or fail (if the method is synchronous and the caller doesn't want to wait).

This unifies three concerns that most languages treat separately:

1. **Correctness contracts** — the type-system-adjacent statement of what a method requires and guarantees.
2. **Synchronization primitives** — locks, condition variables, semaphores, monitors.
3. **State machine semantics** — an object's state determines what operations are valid on it.

Under this design, all three are the same mechanism. `requires connection_state == .opened` documents intent, verifies at runtime, and synchronizes concurrent callers. `ensures status == .shipped` documents the postcondition, provides the state machine transition, and unblocks waiters observing the state change.

**Consequences:**
- Concurrency safety comes from writing contracts, not from separate synchronization annotations.
- Class-level `invariants:` (§3.4.2) hold across concurrent modification.
- Compiler generates lock/CAS machinery to enforce atomicity of contract-carrying methods.
- Optimizer eliminates synchronization for methods whose contracts don't reference mutable state (pure methods, methods on data-only fields).
- Runtime cost is bounded and proportional to contract density in mutating code paths.
- Compile-time race detection is possible where contract-carrying methods can be analyzed statically (Stage 3+).

**Non-consequences:**
- Not "everything is automatically thread-safe." Methods without contracts on mutable state get no synchronization guarantees. Users write contracts where thread safety matters.
- Not a replacement for careful concurrent design. The mechanism prevents data races on contract-guarded state; it does not prevent deadlocks, livelocks, or logical errors.

**Reader/writer classification (v0.8.6).** Methods on a class are classified by the compiler as **readers** (do not assign to any `state:` field and do not call any writer method) or **writers** (assign to state fields or call writer methods). Runtime uses RWLock-style dispatch:

- Multiple readers execute concurrently — state is not changing.
- A writer executes exclusively — blocks all readers and other writers.
- Methods on `data:` fields only (records; classes with no `state:`) skip coordination entirely.

The classification is inferred from method bodies, not user-annotated. `ensures` clauses that reference `result.<field>` (post-state) versus `self.<field>` (pre-state) per §3.4.3 are a documentation aid — the assignment in the body is what actually classifies the method as a writer. See §8.4.1 for the classification algorithm and its interaction with private methods and callback closures.

This premise justifies the concurrency model in §8 and shapes how sections that manage cross-thread coordination (`channel:`, `signal:`, `execution:`) integrate with the rest of the language.

### 1.10 Controlled mutability

Mutability exists at every level of the language — primitive value storage, class state fields, program effects on the world. Corvid's discipline is that every mutation is:

- **Bounded** — scoped to one atom's local state, or a wire's payload transformation. Never ambient.
- **Typed** — the transition contract declares what changes and under what precondition.
- **Explicit** — no ambient `self.x = y` inside method bodies (v1.0 target). State changes only through declared transitions that produce new state.
- **Observable** — effects are emitted as values the runtime can log, replay, mock, or dispatch. Not hidden inside opaque method bodies.

No smuggled imperative. Reasoning about a mutation is local to the atom that performs it; nothing outside the atom sees the state change except through the atom's declared emissions.

**Contrast with adjacent positions.**

- **Pure FP** (Haskell): eliminate mutability. Successful in the type system, at the cost of runtime tricks (State monad, IO monad) that carry the mutability invisibly.
- **Free imperative OOP** (Java/Swift/Python): unrestricted mutation inside methods; controlled only by convention. Entry points scale with class age; invariants leak; concurrency is hard.
- **Actor systems** (Erlang, Akka): inter-actor communication is disciplined; intra-actor implementation is traditional imperative.
- **CSP** (Go, core.async): no mutation, only message-passing. Loses ergonomics of internal mutable state.
- **Corvid** (controlled mutability via atoms): mutation lives inside atoms, disciplined by transitions; composition is via wiring. Middle position with the payoffs of both sides.

**What this makes possible.**

- **Reasoning locality.** Read one atom's transition contract to know what it mutates.
- **Concurrency simplification.** Atoms don't share mutable state (contained state is private); wires carry values by copy; runtime schedules transitions freely.
- **Testability.** Mock an atom's inbound environment; verify its outbound environment. Same at every scale from single atom to whole program.
- **Time-travel debugging.** Emissions are values; record them, replay from any point.
- **Compositional operators for free** (RxJS-style). `debounce`, `throttle`, `batch`, `distinct_until_changed` are just atoms inserted in wires; contracts propagate.
- **Capability-based security.** An atom's environment is exactly what's wired to it; no ambient authority. If a network handle wasn't wired in, the atom cannot make network calls. The wiring IS the capability.

### 1.11 Direction — the atom model as v1.0 target

The premises §1.1–§1.10 point at a specific primitive: **the atom**. An atom is a reactive node with typed inputs, typed outputs, optional private state (immutable = data, mutable = state), event handlers, emissions to other atoms, and optionally contained atoms wired together. Every construct in the current spec — `class`, `record`, `enum`, `protocol`, `section`, `function`, method call, state field, data field — reduces to a shape of atom with different fields populated. The v1.0 target: atom as the sole primitive; all other constructs as sugar for specific atom shapes.

**Shape of the reduction:**

| Construct | Atom shape |
|---|---|
| function | Leaf atom: 1 input, 1 output, no state, pure transition |
| record | Compound atom containing only data atoms (immutable payload) |
| class (regular) | Compound atom with state atoms + entrypoints + observables |
| enum | Atom whose state is a sum type; handlers dispatch on variant |
| protocol | Atom signature (I/O shape + contracts) without transition |
| section | Compile-time atom (runs at compile time, produces program text) |
| program | Top-level compound atom with environment = OS |
| method call | Route message to an atom's entrypoint |
| state field | State atom (mutable payload) contained in a compound atom |
| data field | Data atom (immutable payload) contained in a compound atom |
| type (nominal or structural) | An atom (its named shape) |
| generic type | A parameterized atom (`atom List<T>: ...`) |

**Types are atoms; predicates are contracts (not refined types).** Every type-name is an atom-name; every value is an atom-instance; type-checking is atom-shape-conformance only — pure structural. Predicates on values (bounds, ranges, format requirements) live in `invariants:` on atoms, in `requires`/`ensures` on transitions, or in reusable predicate atoms (Bool-returning atoms invoked from invariants/contracts). Corvid does NOT ship refinement types at the type-signature level — no `atom Nat: Int where value >= 0`. Two systems, both first-class: atoms for shapes; contracts/invariants/predicate-atoms for predicates. See §1.3 for the rationale for banning refined types at the signature level.

**Composition.** Atoms compose by **wiring** — connecting one atom's output channel to another's input channel. Wires may carry ordinary values (common case), or be decorated by inline transformer atoms (`debounce`, `batch`, `throttle`, `filter`, `map`, `distinct_until_changed`, etc.) providing RxJS-style reactive operators as first-class syntactic elements. **The class is the graph.**

**Env → Env at every level.** Every atom is `Env → Env` at its transition — a pure function from local environment to new local environment. Emissions are values in the outbound Env; the runtime one level up (the containing atom, or the operating system at the outermost level) interprets emissions as dispatch. This is Elm's Model-Update-View pattern and Erlang OTP's `(state, msg) → (state, effects)` shape, lifted to the language substrate. Testable in isolation; replayable; time-travel debugging trivial; no ambient effects.

**Controlled mutability, structurally enforced.** State atoms hold mutable data; transitions produce new state, swapped atomically at the atom boundary. Data atoms hold immutable data. Neither is reachable from outside the containing atom — external code sees only entrypoints and observables. The four discipline principles from §1.10 (bounded, typed, explicit, observable) are enforced by construction under the atom substrate, not by convention.

**Wiring glyphs (minimal set).**

| Glyph | Meaning |
|---|---|
| `->` | Route event to atom (wire from source to sink), OR return type in a signature — position disambiguates |
| `:<` | Observable definition (this value is *drawn from* the atom / expression on the right) |
| `\|>` | Pipe through operator (RxJS-style chain) |
| `~>` | Async / deferred wire (debounce, delay, throttle — wires with time) |

Everything else uses keywords: `emit`, `route to`, `broadcast to`, `guard`, `after`. Glyphs are for the four operations users perform most often in atom code.

**Disambiguation for `->`.** The single-arrow `->` serves two roles: return-type marker in atom signatures (`atom sqrt(x: Float) -> Float`) and routing wire in entrypoint bodies (`click() -> debounce(200ms) |> increment`). The parser distinguishes by position: `->` between an argument-list-close and an atom-shape reference is a return type; `->` between two atom expressions inside an entrypoint body is a wire. Readers distinguish by context: the token following `->` is either a type-position atom name (return type) or a routing target (wire). Same shape as how `:` serves both "starts a body" and "declares a shape" in Corvid — one glyph, position-driven meaning, no ambiguity to the parser.

**Rationale for `:<` on observables.** Routing (`->`) is a wire that carries dispatched messages between atoms — imperative-flavored in feel even though semantically it's an emission. Observables are declarations: `value :< state.count` says "the observable `value` IS DEFINED as drawn from `state.count`" — closer to a binding than to a message. The colon prefix carries the definition-flavor Corvid already uses (`x: Int` = "x is defined to have shape Int"); the left-pointing arrow carries the data-flow direction (source on right, exposed name on left). Together they read as "define left, sourced from right" — the honest semantics of an observable declaration.

**Where the spec sits now.**

v0.11 is not the atom substrate. It's an approximation:
- Records approximate stateless compound atoms.
- Regular classes approximate stateful compound atoms.
- Enums approximate sum-type-state atoms.
- Methods approximate atom entrypoints.
- Method bodies remain imperative (`self.x = y` allowed) rather than pure transitions with emitted state.
- Type annotations are split from invariants at the surface (see §1.3) — the unification is target, not committed.

Each v0.x → v0.(x+1) step tightens the approximation. Full commitment lands at v1.0.

**Incremental moves already committed at v0.11:**

- Records as functional-swap (methods return new selves; no in-place mutation of records).
- Primitives as records over Bytes (unified with user records; §3.1).
- `data:` field usage-site advisory (writer calls emit `data-field-mutation` diagnostic; §3.3).
- Canonical bytes as value-type base-class property (§3.4.6).
- §1.3 tightened — types-as-contracts unification named as v1.0 target.

**Incremental moves planned for v0.12+:**

- `kind: cell` variant of regular class — functional-swap for stateful classes without full atomic-swap-boundary machinery.
- Types-as-atoms unification (`: Type` reads as "satisfies atom X's shape"; type-checking is atom-shape-conformance; predicates on values stay in `invariants:` / `requires` / `ensures` / predicate atoms per §1.3).
- Method-call desugaring reframed as atom messaging (implementation-level, surface preserved).
- Contract-first spec discipline — contracts describe atom transitions.
- Wiring glyphs enter the grammar as reserved forms.

**v1.0 substrate:**

- `atom` as sole declaration primitive.
- `class`, `record`, `enum`, `protocol`, `section`, `program` as sugar keywords for common atom shapes.
- `function` retired — write `atom name(x) -> y: ...` for the trivial atom shape.
- `type` and `contract` keywords retired — types ARE atoms; predicates are contracts/invariants/predicate-atoms; there is one declaration form (`atom`) and one predicate mechanism (invariants/contracts/predicate-atoms).
- Types-as-atoms fully unified: type-checking is atom-shape-conformance (structural). No refinement types at the signature level — no `atom Nat: Int where value >= 0`.
- Predicates on values live in `invariants:` on atoms (bare field names in scope), in `requires`/`ensures` on transitions, or in reusable predicate atoms (Bool-returning leaf atoms).
- Compiler is a dataflow compiler: fuses adjacent atoms, schedules independent subgraphs, computes buffer sizes, propagates contracts across the graph.
- Wiring glyphs (`-->`, `<--`, `\|>`, `~>`) canonical in the grammar.
- Runtime interprets top-level program-atom emissions as OS effects (syscalls, stdout, network dispatch).

**Positioning.**

Before v1.0: "Swift competitor for the Apple ecosystem with Clojure-inspired value/reference discipline and Objective-C interop."

At v1.0: "Erlang-substrate reactive language with Swift-shaped surface, CSP + core.async composition, contract-based verification, and dataflow compilation. Class = bounded atom graph. Functions = the trivial atom shape. Every construct is a shape of atom."

The Swift-shaped surface stays (ObjC interop, familiar syntax, gradual migration). The atom substrate is the v1.0 commitment.

---

## 2. Lexical Structure *[TENTATIVE]*

### 2.1 Encoding
Source is UTF-8. Identifiers may contain non-ASCII characters per Unicode UAX #31.

### 2.2 Comments
- Line: `# comment to end of line`
- Block: `#| block comment |#`
- Documentation: `## doc comment` (attaches to following declaration)

### 2.3 Identifiers
- `snake_case` for methods, functions, variables, module names, section names.
- `PascalCase` for classes, protocols, sum types, effect names, section classes.
- `SCREAMING_SNAKE_CASE` for constants.

### 2.4 Layout
Indentation-delimited blocks (Python-style). Indent must be consistent within a block. No tab/space mixing.

### 2.5 Keywords
```
class protocol accepts requires ensures
async allocates blocks
mutable immutable let
function constructor destructor generator processor validator
expander invariants
self Self return
section alias
category objc_exposed
if else switch case for in while break continue
import from as expose module
true false nil
Any Effect
with
sizeof alignof offsetof
```

Removed from prior drafts: `sealed`, `dynamic`, `open`, `final`, `public`, `private`. See §1.2, §5, §7.7 for rationale.

Note: `else if` is written as two tokens, not one; there is no `elif`.

Note: `let` binds an immutable local or module-level constant (§7.1). `var` is not a keyword — mutable bindings use bare identifier assignment (see §6.15).

### 2.6 Reserved Syntactic Space

The syntactic form `label:` followed by an indented block is reserved language-wide for section usage. This applies at every scope — class bodies, function bodies, method bodies, module bodies, expression contexts — even where the section mechanism is not yet implemented in a given language version. Future versions may enable this syntax in additional contexts without breaking existing code.

### 2.7 Literals

Literals split into **bootstrap literals** (recognized by the base lexer) and **prelude literals** (defined by prelude alias declarations, §4.20).

#### 2.7.1 Bootstrap Literals

**Numeric literals.** Corvid inherits C's numeric literal shapes:

- **Decimal:** `42`, `-17`, `1_000_000` (underscore separators legal within a literal for readability).
- **Hexadecimal:** `0xFF`, `0xDEAD_BEEF`, `0x00`. Prefix `0x` or `0X`. Digits `0-9`, `a-f`, `A-F`.
- **Binary:** `0b1010`, `0b1111_0000`. Prefix `0b` or `0B`. Digits `0`, `1`.
- **Octal:** `0o17`, `0o755`. Prefix `0o` or `0O`. Digits `0-7`. (Not C's leading-zero form — Corvid rejects `017` as ambiguous.)
- **Floating-point:** `3.14`, `1e10`, `2.5e-3`, `0x1.8p+1` (hex float, C99-compatible). Underscores legal.

Numeric literals have a target-directed type: the literal's type is inferred from context (declared field type, function parameter type, arithmetic operand type). Unqualified `42` in isolation is `Int`; `3.14` is `Double`. Suffix syntax is not part of v1 — write the target type explicitly if inference is insufficient: `let byte: UInt8 = 0xFF`.

**Character literals.** `'a'` produces a `Char` (Unicode scalar). Legal escape sequences:

- `'\n'` newline, `'\t'` tab, `'\r'` carriage return
- `'\\'` backslash, `'\''` apostrophe, `'\"'` quote
- `'\0'` NUL, `'\x1F'` hex byte, `'\u{1F600}'` Unicode scalar

**String literals.** `"..."` produces `String` (UTF-8, immutable). Base escape sequences match character literals. String interpolation is a prelude alias (§2.7.2, §4.20.1) — the base lexer produces a raw string; the extended parse resolves interpolation via prelude.

**Boolean literals.** `true`, `false`.

**Nil literal.** `nil`. Legal wherever nil-allowed context is expected (reference types §3.6.1, value-type Optionals §3.2, or the value-type-Optional `.none` explicit form).

#### 2.7.2 Prelude Literals

The following literal forms are defined by prelude alias declarations (§4.20.1):

- **Array literal:** `[1, 2, 3]` — expands via `alias [<items>] in expression_position` to `Array.from_literal(items)`.
- **Map literal:** `{"key": value, ...}` — expands via `alias {<pairs>}` guarded on key-value pair content.
- **Set literal:** `{1, 2, 3}` — expands via `alias {<items>}` guarded on plain expression content.
- **Empty `{}`:** disambiguated to empty Map (§4.20.1 convention).
- **Range literals:** `0..<n` (half-open), `0...n` (closed) — expand to `Range.half_open` / `Range.closed`.
- **String interpolation:** `"hello ${name}"` — the `${expr}` form inside string literals is a prelude alias at `string_interpolation_position`. Simple identifier form `$name` is language-committed as a shorthand for `${name}`.

Because these are prelude aliases, they are inspectable — users can look up what any literal shape expands to. Users may also define their own literal-producing aliases for domain-specific types (§4.20.4).

---

## 3. Type System (Contract System) *[TENTATIVE]*

### 3.1 Primitive Types
- `Int`, `Int8`, `Int16`, `Int32`, `Int64` (and unsigned variants: `UInt`, `UInt8`, `UInt16`, `UInt32`, `UInt64`)
- `Float`, `Double`
- `Bool`
- `Char` (Unicode grapheme cluster), `String` (UTF-8, immutable), `Bytes`
- `Unit` (the type with one value, written `()`)
- `Never` (the type with no values, for divergent functions)
- `Any` — dynamically-typed value; the type system knows nothing about it. See §3.1.1.

**Primitives are records over `Bytes`, shipped in prelude.** No separate "primitive family" with independent semantics — every primitive is a `kind: record` class with a single `data: bytes: Bytes` field constrained by a length refinement. Byte methods (`.as() -> Bytes`, `class function from(source: Bytes)`) synthesize from the field layout the same way they do for user records (§3.4.6). Arithmetic, comparison, IEEE 754 semantics, bitwise operations remain compiler intrinsics — the compiler recognizes primitives by name and lowers their methods to machine instructions; storage is specialized to inline machine words.

```
# Prelude ships (illustrative shape — arithmetic methods elided):
record Bool:
    data: bytes: Bytes where bytes.length == 1
    methods:
        # ... boolean intrinsics ...

record UInt8:
    data: bytes: Bytes where bytes.length == 1
    methods:
        # ... unsigned-arithmetic intrinsics ...

record UInt32:
    data: bytes: Bytes where bytes.length == 4
    methods:
        # ... unsigned-arithmetic intrinsics ...

record Int32:
    data: bytes: Bytes where bytes.length == 4
    methods:
        # ... two's-complement-arithmetic intrinsics ...

record Float:
    data: bytes: Bytes where bytes.length == 4
    methods:
        # ... IEEE 754 single-precision intrinsics ...

record Double:
    data: bytes: Bytes where bytes.length == 8
    methods:
        # ... IEEE 754 double-precision intrinsics ...

record Char:
    data: bytes: Bytes where bytes.length >= 1 and bytes.length <= 255
    methods:
        # ... grapheme intrinsics ...
```

**Consequences.** Value semantics falls out of `kind: record` at the record boundary (§3.3), same as any user record. Copy-on-assign, structural equality (byte-wise field comparison), structural hashing, canonical bytes — all synthesized via the record base-class properties (§3.4.6). No per-primitive prelude `extend class Foo: methods: function as(): ...` needed — the byte methods synthesize from the field. Storage (inline machine word) is a compiler specialization invisible to the surface; semantically primitives ARE records with a bytes field. Bit-pattern-carrying types like `Int` and `Bool` have no nil representation (records without nil-annotation are non-nil per §3.6.1). Reference-shaped types (`String`, `Bytes`) still get nil-allowed status when annotated as receivers because the runtime carries them via handles.

**Numeric literals infer their type from context** (§2.7). `Char` literals use `'x'` form; `String` literals use `"..."` form; both support C-style and Unicode escape sequences.

#### 3.1.1 The Any Type

`Any` represents a value whose concrete type is not known at compile time. Method calls on `Any` are dispatched through the runtime:

```
x: Any = some_dynamic_source()
x.method()   # dispatched via runtime; fails at runtime if no matching method
```

**When to use `Any`:**
- At the Objective-C interop boundary — ObjC APIs returning `id` come into Corvid as `Any` (§9.3.2).
- Reflective code that operates on unknown types.
- Explicit dynamic dispatch when the user knows the concrete type isn't statically determinable.

**Pattern matching narrows `Any`:**

```
match x:
    case Order(o):
        process(o)              # o: Order
    case String(s):
        log(s)                  # s: String
    case _:
        handle_unknown()
```

**`Any` is not a universal supertype.** There is no "everything conforms to Any" claim. Values are typed `Any` when the compiler doesn't know their type, not because they satisfy some contract.

**`Any` allows nil at runtime.** As a reference-shaped type (§3.6.1), an `Any` value may be nil. If a specific call site needs a non-nil-Any contract, write `Any!`.

**Idiomatic use is rare.** In pure Corvid code, contracts and specific types are preferred. `Any` should appear primarily at interop boundaries or in genuinely dynamic code paths. The compiler warns when `Any` is used as a field or return type in public APIs, since callers get no static guidance.

### 3.2 Composite Types

Corvid's composite types are stdlib types. The **surface syntax** for them is prelude alias declarations (§4.20) — the language grammar itself only knows `identifier<generic_args>` for types; every richer type-expression shape is an alias expansion.

| Surface syntax | Expands to (prelude alias) | Position |
|---|---|---|
| `(A, B)`, `(x: Int, y: Int)` | `Tuple<(A, B)>` / named tuple | type |
| `[T]` | `Array<T>` | type |
| `{K: V}` | `Map<K, V>` | type |
| `{T}` | `Set<T>` | type |
| `(A, B) -> C` | `Callable<(A, B), C>` | type |
| `T?` (value-type T only) | `Optional<T>` sum type | type (suffix) |
| `T!` (reference-type T only) | `T with invariant: value != nil` | type (suffix) |
| ~~`Result<T, E>`~~ | **Retired v0.8.5** — not built-in, not in prelude. Users may define their own; stdlib does not push one. See §3.5.4. | — |

The prelude declarations for these are in §4.20.1 ("Type-expression aliases"). See §3.6.1 for the nullability semantics (`T?`, `T!`) and §3.10 for collection literal semantics.

**Collection containers are reference-shaped (`kind: regular`).** `Array<T>`, `Map<K, V>`, `Set<T>` are stdlib classes with `state:` (mutable contents) and are therefore `kind: regular` (reference-typed) — the same shape as any other stateful class. Passing an `Array<T>` to a function and calling `.append(...)` or `.remove_at(...)` inside is visible to the caller; no `inout` / by-reference-parameter marker is needed. This falls out of §1.5 (class-as-universal) plus §6.1 (kind-regular = reference-typed with state) — collections have state, so they're regular classes, so they're reference-typed. The ObjC bridge mirrors this: `NSArray<T> *` in ObjC = `[T]` in Corvid (§9.3), a pointer on both sides.

Records (`kind: record`) forbid `state:` (§4.10.7); a type that mutates in place cannot be a record. If a user wants an immutable, copy-on-assign array-like value, they declare a `record` wrapping an `Array<T>` with only read-only methods exposed — copies happen at record boundaries, not on the internal Array.

**Effect on the type expression grammar.** The bootstrap type grammar (§10) recognizes only:

```
type := identifier
      | identifier '<' type (',' type)* '>'
```

All richer type expressions — brackets, braces, arrows, suffix operators — come from prelude aliases. Users cannot introduce new type-expression sugars without opt-in (though they may declare additional type-alias identifiers via §4.16.9).

### 3.3 User-Defined Types

All user types are declared via `class`. The `metadata: kind:` section entry (§4.10.6, §6.1) selects the variant:

- **`kind: regular`** (default, omitted) — reference-typed with `state:`, extent participation, weak refs, dynamic dispatch. Traditional class.
- **`kind: record`** — value-typed, no `state:`, no extent. Base-class properties: structural equality (`==`), structural hashing (`hash(self)`), copy-on-assign, canonical bytes (`.as() -> Bytes` / `.from(source: Bytes)` per §3.10.10). Replaces v0.5's `struct` keyword.
- **`kind: enum`** — sealed sum type with `cases:` section and optional integer `raw_type:`. Same base-class properties as `kind: record` (equality, hashing, canonical bytes). Replaces v0.5's `enumeration` keyword.
- **`kind: protocol`** — no instantiation; `accepts:` section holds required signatures, `methods:` holds default implementations. No value-type base properties (protocols are not instantiable).
- **`kind: section`** — §4 metaprogramming section-class.

**Value-type base-class properties are language commitments, not protocol conformances.** Records and enums have `.as() -> Bytes`, `==`, `hash`, and copy-on-assign as *fundamental properties of what value types ARE* — the compiler synthesizes these methods per class from field layout, same shape as automatic structural equality in Haskell records or Rust structs. Structural conformance to `As<Bytes>` / `From<Bytes>` / `Equatable` / `Hashable` (§3.4) falls out as a consequence — the base methods exist, so structural checks succeed with no separate "auto-conformance" mechanism. See §3.4.6 for the byte-property detail and §3.10.10 for the canonical wire format.

Primitives (`Int`, `Float`, `Bool`, `Char`, etc.) are `kind: record` and inherit the same base-class properties. Prelude ships the byte methods explicitly (§3.11.4) because primitives have no field layout for the compiler to synthesize from — the encoding is per-type (IEEE 754 for floats, big-endian for integers, etc.).

`kind: regular` classes get **none of these base-class properties automatically** — mutable state, potential cycles, and identity make mechanical equality/hashing/serialization unsafe. Regular-class authors define `==` / `hash(self)` / `.as() -> Bytes` explicitly if they want those behaviors.

**`data:` fields carry a usage-site rule, not just a declaration-site meaning.** Values obtained from a `data:` field are semantically read-only at the field-usage layer. Calling a **writer** method (§8.4.1 classification) on a value obtained from a `data:` field emits a `data-field-mutation` warning at the call site — under the record boundary's value semantics, the mutation happens on a copy (via COW under the hood), not on the record's held field. Reader methods on `data:` fields emit no diagnostic. The `data:` marker thus carries two commitments: (1) *declaration-site* — the field contributes to structural equality/hashing/canonical bytes as data, not state; (2) *usage-site* — the compiler advises against writer calls on values obtained from the field.

```
warning[W0410]: writer method `set` on value obtained from `Integer.bytes` (a `data:` field)
    |
    |     int.bytes.set(0, 99)
    |               ^^^ writer method on value from `data:` field
    |
    = mutation happens on a copy under COW; the Integer is unaffected
    = help: to produce a modified Integer, construct a new one with the intended bytes
```

Flow-tracking scope: direct calls (`int.bytes.set(...)`) and one-hop through variable assignment (`let b = int.bytes; b.set(...)` — compiler tracks `b`'s origin). Deeper chains (through function arguments, closures) rely on the writer-effect diagnostic firing at the actual mutation site inside the receiving function. No new mechanism — reuses `data:` declaration marker + §8.4.1 reader/writer classification.

Sugar keywords (`record`, `enum`, `protocol`, `section`) desugar losslessly to the corresponding `class + metadata: kind:` form. See §4.11 for sugar rules. Class bodies are described in §4.7; runtime semantics per kind are described in §6.

**Sum types** (kind: enum):
```
# Sugar (typical user-facing form):
enum Result<T, E>:
    case ok(T)
    case err(E)

enum Shape:
    case circle(radius: Float)
    case rectangle(width: Float, height: Float)

# Enum with raw values (interop-friendly form):
enum CloseCode:
    case normal = 1000
    case protocol_error = 1002
    case encoding = 1007
```

For raw-valued enums, `raw_type:` is inferred from the case values (smallest unsigned type that fits) unless overridden via `metadata: raw_type:`. The compiler auto-generates `raw_value(self) -> <raw_type>` and `class function from_raw(value:) -> Self?`.

The desugared form is:

```
class Result<T, E>:
    metadata:
        kind: enum
    cases:
        case ok(T)
        case err(E)

class CloseCode:
    metadata:
        kind: enum
        raw_type: UInt16     # inferred
    cases:
        case normal = 1000
        case protocol_error = 1002
        case encoding = 1007
```

Users rarely write the desugared form; tooling operates on it.

#### 3.3.1 Types Are Flat — Naming Convention for Class-Associated Types

Corvid does not support nested type declarations. All user-defined types (classes, records, enums, protocols, sections) live at module scope. Types conventionally associated with a specific class are named `<ClassName><Role>` at module scope:

```
expose class FrameCollector:
    state:
        delegate: FrameCollectorDelegate
        current_event: FrameCollectorEvent?
        state: FrameCollectorState = .idle
    ...

expose enum FrameCollectorEvent:
    case text(String)
    case binary(Bytes)
    case closed(reason: String, code: UInt16)

expose enum FrameCollectorState:
    case idle
    case receiving
    case draining

expose protocol FrameCollectorDelegate:
    accepts did_form(event: FrameCollectorEvent)
```

**Rationale.** Nested types (`class Foo: enum Bar: ...` with `Foo.Bar` access from outside) were considered and rejected. The ownership signal they provide is small (IDE goto-definition and grep already handle this), and the cost is real: every consumer file that references the nested type either pays per-use path noise (`FrameCollector.Event.text(...)`) or pays per-file aliasing overhead (the C# `using Event = FrameCollector.Event;` problem). Flat naming pushes the cost to one longer name at declaration and leaves consumers free — one longer symbol per import site vs. one alias per consumer file.

**Brevity when consumers want it.** A consumer file that references a class-associated type heavily can add a module or type alias locally (§4.16.9, v0.8.2 `@`-marker discipline):

```
alias @Event = com.example.ws.FrameCollectorEvent
alias @State = com.example.ws.FrameCollectorState

# Local use sites read short:
if @State.idle == handler.state: ...
event = @Event.text("hello")
```

Aliases are opt-in per consumer, not forced by the declaration.

**Leading-dot shorthand covers the common case (§3.7.2).** In target-typed contexts — switch scrutinees, function arguments, field assignments — `.name(...)` resolves to the inferred type. This is where flat naming would otherwise read verbose, and where nested-type dotted access would win most. Leading-dot neutralizes both concerns:

```
switch event:
    case .text(let s): ...              # inferred FrameCollectorEvent
    case .closed(reason: let r, code: let c): ...

set_state(.receiving)                    # inferred FrameCollectorState
handler.state = .idle                    # inferred field type
```

The naming convention and leading-dot together make module-scope types read like nested ones at the use sites where nesting would have helped most.

#### 3.3.2 Extension Declarations

`extend class Foo:` opens a class body targeting an existing class named `Foo`. Members declared inside are added to `Foo` — indistinguishable from members declared in `Foo`'s original definition at dispatch time. This is Corvid's single mechanism for extending an existing class; there is no separate "category" section class.

```
# Original definition (possibly in another module):
expose class NSString: ...

# Extension in the current module:
extend class NSString:
    methods:
        function shout(self) -> NSString:
            return self.upper_case
        
        function is_empty_or_whitespace(self) -> Bool:
            return self.trimmed.length == 0
```

Callers of `NSString` in modules that import the extending module see `.shout()` and `.is_empty_or_whitespace()` as ordinary methods on any `NSString` instance.

**Extending generic classes.** Generic parameters and where-clauses appear at the class declaration position, reusing the same machinery as class and function generics:

```
# Add methods to any Array<T> where T conforms to UnsignedInteger:
extend class Array<T> where T: UnsignedInteger:
    methods:
        function sum_all(self) -> T:
            return self.reduce(0, (a, b) => a + b)
        
        function product_all(self) -> T:
            return self.reduce(1, (a, b) => a * b)
```

`.sum_all()` and `.product_all()` are callable at call sites where the element type satisfies `UnsignedInteger`. At other call sites (e.g., `[String].sum_all()`) the compiler rejects with a where-clause-violation diagnostic.

Where-clauses compose per the same rules as elsewhere in the language:

```
extend class Array<T> where T: UnsignedInteger, T: Bounded:
    methods:
        function saturating_sum(self) -> T:
            var acc: T = 0
            self.iter().for_each(x =>
                acc = if acc > T.max - x: T.max else: acc + x)
            return acc
```

**Extending protocols.** `extend class SomeProtocol:` adds default methods to the protocol; every structural conformer inherits them (per §3.4.4) for methods the conformer does not define itself.

```
# Extend the Iterable<T> protocol with a count method:
extend class Iterable<T>:
    methods:
        function count(self) -> Int:
            var n = 0
            self.iter().for_each(_ => n += 1)
            return n
```

Any class conforming to `Iterable<T>` (Array, Set, Map's entries iterator, user types) now has `.count()` as a default. A class that defines its own `count` overrides the default — no ambiguity for that class.

**Extensions can add invariants.** Just as methods stack across extensions, invariants stack:

```
class Reader:
    data: string: String
    state: current_index: StringIndex
    invariants:
        current_index >= string.start_index

# Elsewhere:
extend class Reader:
    invariants:
        current_index <= string.end_index
```

The composed class carries both invariants; the runtime checks them at every observable point (per §3.4.2).

**What extensions cannot add.**

- **`state:` fields.** Adding a new state field would change the class's memory layout, which is fixed at compile time (§4.18.3). Extensions can declare invariants over existing state fields, and can call any existing method — but the field set is closed at definition time. This restriction is the same one the retired Category section class carried.
- **New generic parameters on the target class itself.** `extend class Array<T, U>:` is not legal if `Array` was declared with only one generic parameter. The extension inherits the class's declared parameter count and can name them per shape 2 (see below); it cannot invent new ones.
- **A different kind.** `extend class Foo:` inherits Foo's `metadata: kind:` from the original definition. An extension cannot promote a `record` to a `regular` class or vice-versa; the kind is set at definition time.

**Method-name conflict across extensions.** Two `extend class Foo:` blocks that declare the same-named method targeting the same class are a **warning** at the composing site, resolved deterministically. This aligns with §1.2 (author intent, not authority) and §7.7 (visibility as advisory, not enforcement) — Corvid warns and informs; it does not block. Users who prefer strict CI can escalate the warning to an error via build config.

The only *hard* compile error for same-name is when two same-named methods appear inside **one class body** (original definition or a single `extend` block) — this is author self-conflict, not a composition ambiguity.

To silence the warning explicitly (document intent), use the `replace` modifier:

```
extend class NSString:
    replace function shout(self) -> NSString:
        return self.upper_case + "!"     # explicit intent: shadow the prior shout
```

`replace` is *optional* — without it the composition still succeeds (with a warning), and the deterministic resolution rule picks the winner. With `replace` present, the warning is suppressed for that site (author signaled intent) and `replace` further enforces that *some* method is actually being shadowed (see rule 3 below).

Compared to ObjC's "last category loaded wins" (hidden in link order), Corvid's rule is **source-order deterministic at the composing site** rather than load-order dependent — the reader of the composing module can trace resolution without needing runtime information.

**Conflict resolution rules (v0.10.0).** Composition happens at the composing site — the compilation unit that has both extensions visible. The compiler gathers every visible `extend class Foo:` block and the original `class Foo:` definition, then applies these rules:

1. **Same-name collision across extensions → warning + deterministic resolution.** Two extensions declaring the same-named method on the same target class produce a `shadowing-extension` warning at the composing site, and the compiler picks the winner by priority (highest first):

   1. Extension declared in the currently-compiling module.
   2. Extension from a directly-imported module; if multiple, most-recently-imported wins (import-list source order, later > earlier).
   3. Extension from a transitively-imported module; if multiple, most-recently-imported at the nearest importing module wins.
   4. Original class definition.

   The diagnostic names all conflicting definitions with file:line:module and states which one was chosen. Suggested fix: add `replace` to the winning declaration to document intent explicitly and silence the warning.

   Escalation: `-Werror=shadowing-extension` (build-config flag) turns this warning into an error for CI/strict-mode projects.

2. **Same-name collision within one class body → compile error.** Two same-named methods inside a single `class Foo:` or single `extend class Foo:` body is author self-conflict and always a hard error. No `replace` opt-out — fix the source.

3. **`replace` requires a shadowing target.** A method declared with `replace` must shadow *some* method — either the original class definition, another visible extension, or an inherited protocol default. If nothing is being shadowed, the compiler emits a `replace-without-target` warning and treats the `replace` modifier as a no-op (the method is added as an ordinary declaration). Warning-not-error: the code is well-formed; the modifier is just superfluous.

4. **Multiple `replace` declarations on the same target → warning + same priority rule.** If two extensions both declare `replace function foo(self):` shadowing the same original, both are competing to be the shadower. Warning + rule 1's priority order picks the winner. `replace` is not a claim to *sole* precedence; it's a claim to *some* shadowing (rule 3). Sole precedence is inferrable at read time from priority order.

5. **No signature-based overloading.** Corvid does not have method overloading by parameter types — two same-named methods are always considered conflicting for the purposes of rule 1, regardless of parameter/return signatures. **Exception: trait-qualified dispatch for `As<T>` and `From<T>`** (§3.4.4 note, §3.10.3). A class conforming to `As<Bytes>` and `As<Json>` may declare both `function as(self) -> Bytes` and `function as(self) -> Json`; the return type is a *type parameter of the conformed protocol*, not an overload dimension. These are not considered same-name conflicts.

6. **Extensions shadow inherited protocol defaults without `replace`.** An `extend class Foo:` providing method `foo` shadows any protocol default for `foo` that Foo would have inherited via §3.4.4. No warning, no `replace` needed — per §3.4.4's priority rule, own definitions (including via extension) win over inherited defaults.

   Exception: if the class already had an explicit `foo` (original definition or another extension) that itself shadowed the protocol default, adding a *second* extension providing `foo` triggers rule 1 — you're shadowing the previous explicit declaration, not the protocol default.

7. **Runtime method table is unified after composition.** Once resolution has picked a winner at compile time, the class has one entry per selector. At dispatch time there is no per-source-of-declaration distinction; §5.1 applies uniformly.

8. **Load-time transitive visibility.** Extensions apply to the class globally at load time — once module B is loaded (whether by direct import or transitively through a dependency), B's extensions to Foo are visible to all code that touches Foo instances. This matches ObjC category semantics. Compile-time visibility (§7.7) constrains what a module can *see* to write code against; runtime visibility is class-global.

   Consequence: if module A imports B and D, both of which independently extend Foo with `foo`, the warning surfaces at A's composing site — even though A wrote no extension itself. A resolves it by (a) leaving the warning (deterministic pick applies), (b) adding its own extension with `replace` that supersedes both (rule 1 priority level 1 wins), or (c) upcasting at the call site if the two `foo`s belong to different protocols with different semantics.

**Diagnostic format (illustrative):**

```
warning[W0447]: shadowing extension `sum_all` on class `Array<T>`
  --> src/collections/sum.cor:12:9
   |
12 |         function sum_all(self) -> T:
   |         ^^^^^^^^^^^^^^^^^^^^^^^^^^^ chosen (current module: current-module rule 1.1)
   |
note: also declared in extension at
  --> deps/math_ext/src/aggregations.cor:8:9
   |
 8 |         function sum_all(self) -> T:
   |         ^^^^^^^^^^^^^^^^^^^^^^^^^^^ shadowed (imported module: rule 1.2)
   |
help: to document intent explicitly and silence this warning,
      add `replace` to the winning declaration
   |
12 |         replace function sum_all(self) -> T:
   |         +++++++
help: to reject shadowing at build time, use
   |
   |         corvid build --Werror=shadowing-extension
```

**What is not a conflict.**

- Two extensions adding *different* methods to the same class: composed freely.
- Extension adding a method on class Foo and another extension adding a same-named method on class Bar: different classes, different tables, no interaction.
- Extension adding `foo` to Foo when Foo already has `bar`: no name collision, composed freely.
- Extension adding invariants (rather than methods): invariants stack additively per §3.4.2; no notion of "conflict" applies to predicates.

**Extension visibility follows module imports.** An `extend class Foo:` declared in module B becomes visible only to modules that import B (transitively, per §7.7). This scopes extension visibility to explicit dependency edges — a module can control which extensions it sees by controlling its imports.

Individual extension members may carry `expose` exactly as members in a definition body may:

```
extend class Foo:
    methods:
        expose function public_helper(self): ...
        function internal_helper(self): ...       # module-visible only
```

**Extending Objective-C classes.** `extend class NSString:` and `extend class UIView:` work identically to extending Corvid classes. The methods land in the runtime's method table for the target class and are dispatchable from both Corvid and ObjC call sites (`[str shout]` on the ObjC side, `str.shout()` on the Corvid side). See §9.3 for interop specifics.

**Extending primitives.** `Int`, `Float`, `Bool`, `Char` are classes with `kind: record`. Extending them is legal and works the same way. The prelude uses this mechanism internally to provide instance methods on primitives (`5.hex()`, `x.clamp(0, 100)`) — no special case for primitives.

**Devirtualization interaction.** The compiler devirtualizes extension method calls under the same rules as ordinary calls (§5.2): if the target class is provably not further extended in the compilation unit, direct dispatch; otherwise dynamic. Deopt (§5.3) reverts to dynamic if a later-loaded module introduces a new extension.

### 3.4 Contracts (Protocols)

A protocol is a class with `metadata: kind: protocol` — a named set of accepted messages with contracts, plus optional default implementations. The `protocol` keyword is sugar for the unified `class + kind:` form (§4.11).

```
enum ProcessOutcome:
    case completed(receipt: Receipt)
    case rejected(reason: OrderError)
    case inventory_short(missing: [Item])

enum CancelOutcome:
    case cancelled
    case not_found
    case already_shipped

protocol OrderProcessor:
    accepts process(Order) -> ProcessOutcome
        async
        requires order.is_valid
        ensures returns .completed(_) implies inventory.contains(order.items)

    accepts cancel(OrderID) -> CancelOutcome

    # Default implementation
    function process_batch(self, orders: [Order]) -> [ProcessOutcome]:
        return orders.map { o -> self.process(o) }
```

Desugared form:

```
class OrderProcessor:
    metadata:
        kind: protocol
    accepts:
        process(Order) -> ProcessOutcome
            async
            requires order.is_valid
            ensures returns .completed(_) implies inventory.contains(order.items)
        cancel(OrderID) -> CancelOutcome
    methods:
        # methods: section on kind: protocol holds default implementations
        function process_batch(self, orders: [Order]) -> [ProcessOutcome]:
            return orders.map { o -> self.process(o) }
```

In the sugar form, `accepts foo(...)` at the class-body level and the `accepts:` section form are both legal — the compiler treats them identically. Use the section form when there are many required methods; use the per-method form when there is one.

**Conformance is structural.** Any type accepting these messages with matching contracts satisfies `OrderProcessor`. Explicit conformance is legal but not required.

#### 3.4.1 Closed Sum Types

Closed sum types come from **enumerations**, not from a keyword on protocols. An enumeration declares its cases; no external code can add cases. Exhaustive matching (§3.7) falls out from this — the switch analyzer sees all cases at declaration and enforces coverage.

```
enumeration Shape:
    case circle(radius: Float)
    case rectangle(width: Float, height: Float)
    case triangle(base: Float, height: Float)

# Switch is exhaustive over all cases.
function area(s: Shape) -> Float:
    switch s:
        case .circle(radius):
            return 3.14159 * radius * radius
        case .rectangle(width, height):
            return width * height
        case .triangle(base, height):
            return 0.5 * base * height
```

**Protocols are always open by design.** Any type in any module may conform to a protocol; that's the point of a protocol as an abstraction. If you want to express "this is a closed set of variants," use an enumeration with associated values.

Previously the spec had `sealed protocol` for enforcing single-module conformance. That's removed. Reasons:
- Enumerations already cover the closed-sum-type case with better ergonomics.
- Sealed protocols enforced a compile-time restriction via authority — matching the philosophy problem with `sealed` on classes (§5.2, §1.2).
- The visibility rules of the module (§7.7) already scope internal-by-convention protocols to their module.

For internal protocols that shouldn't be conformed to externally, just don't `expose` them. External conformance generates a warning; the module can convert conformances to enumerations if needed.

#### 3.4.2 Class-Level Contracts (Invariants)

Classes and structs can carry class-level contracts via the `invariants:` section — predicate expressions that must hold at every observable point (after construction, before/after each method call, before destruction).

```
class Connection:
    data:
        endpoint: URL
    
    state:
        socket: Socket                         # reference type, nil-allowed by default
        connection_state: ConnectionState = .closed
    
    invariants:
        connection_state == .opened implies socket != nil
        connection_state == .closed implies socket == nil
    
    function open(self)
        requires connection_state == .closed
        ensures connection_state == .opened:
        self.socket = Socket.connect(self.endpoint)
        self.connection_state = .opened
    
    function send(self, data: Bytes)
        requires connection_state == .opened:
        self.socket.send(data)                 # invariant ensures non-nil; no force-unwrap needed
```

**Enforcement:**
- **Stage 1**: runtime-enforced. Invariants are checked at method boundaries; violations raise contract errors.
- **Stage 3+**: compile-time-enforced where analyzable (state tracking through control flow for immutable-owned fields).
- Users can suppress runtime checks in release builds via `--no-runtime-invariants` flag.

**State machines emerge from this pattern.** A class with:
- State fields recording current state.
- Invariants over those state fields relating them to other fields.
- Method preconditions requiring specific states.
- Method postconditions transitioning states.

...encodes a state machine. The compiler tracks state transitions via `ensures` clauses and rejects invalid method calls via `requires` clauses. No separate "state machine" language feature is needed; the machinery falls out of contracts.

Sections benefit from this same mechanism. Since section classes ARE classes, they can carry invariants over their own state. See §4.10 for section classes with state machines.

#### 3.4.3 Pre-state and Post-state Bindings in Ensures Clauses

An `ensures` expression is evaluated at method exit, but it needs to talk about both the state that existed at method entry ("count before") and the state that exists at method exit ("count after"). The rule:

- Inside an `ensures` expression, `self` and parameter identifiers refer to the **entry-state snapshot** — the receiver and parameter values as captured at method entry.
- Inside an `ensures` expression, `result` refers to the **post-state binding**:
  - For methods with a declared return type, `result` is the returned value.
  - For methods with no declared return type (void methods that mutate `self`), `result` is the receiver at method exit.

There is no `old()` form. The pre-state was never lost — it's what `self` and the parameter names already refer to.

```
class Ledger:
    state:
        accounts: {AccountID: Money}
    
    invariants:
        accounts.values.sum == self.initial_total
    
    methods:
        expose function transfer(self, from: AccountID, to: AccountID, amount: Money):
            requires accounts[from] >= amount
            ensures result.accounts[from] == self.accounts[from] - amount
            ensures result.accounts[to]   == self.accounts[to]   + amount
            
            self.accounts[from] -= amount
            self.accounts[to] += amount
```

- `self.accounts[from]` in the ensures clause is the pre-transfer balance.
- `result.accounts[from]` is the post-transfer balance.
- `amount` is the parameter value (unchanged from entry).

The same rule applies to reader methods that return a value:

```
function next_index(self) -> Index
    requires !self.did_reach_end()
    ensures result > self.current_index:
    return self.string.index_after(self.current_index)
```

Here `self.current_index` is the (unchanged) pre-state; `result` is the returned index.

**Reference-type semantics.** `self` in ensures scope captures the receiver's field values at entry, using the same value/handle rules as any ordinary read. Reference fields capture the handle at entry, not a deep snapshot — to detect field-level mutations on the same referenced object, compare specific fields: `result.doc.title != self.doc.title`, not `result.doc != self.doc` (which would compare identity).

**Where the bindings apply.**

- `ensures` expressions: `self`/params → entry-state; `result` → post-state.
- `requires` expressions: `self`/params → entry-state; `result` is not in scope (method hasn't returned yet).
- `invariants:` expressions (§3.4.2): invariants hold at every observable point, so entry/exit distinction is meaningless; `self` refers to the current state and `result` is not in scope.
- Method bodies: `self.field = value` mutates in place. `result` is not a language-level binding in the body; use `return expr` (value-returning methods) or let the mutations to `self` stand (void methods). The `result` binding materializes only in `ensures` scope.

**Runtime obligation.** On method entry, the contract runtime snapshots the receiver's field values and parameter values — the same snapshot it already required to make any postcondition checkable. On method exit, the runtime evaluates each `ensures` expression with entry-snapshot bound to `self`/params and post-state bound to `result`. Cost is bounded by field count of the receiver plus parameter count, plus any values transitively read from `self` or params inside the ensures expression.

**Why not `old(...)`?** Considered and dissolved. `old(expr)` in Dafny/JML/Eiffel is a named capture of pre-state; Corvid's runtime already captures the pre-state to make any ensures clause checkable. Exposing the capture as the natural identifier (`self`, params) rather than routing through a wrapper is one fewer construct with equivalent expressive power. Users coming from Dafny/JML/Eiffel should read the `PATTERNS.md` entry for the mapping.

#### 3.4.4 Default Method Inheritance for Structural Conformers

Structural conformance (§3.4) is load-bearing: a class that structurally conforms to protocol `P` **inherits P's default methods** for messages the class does not define itself. This makes "conformance is structural" mean more than "the compiler will allow assignment to a `P`-typed slot" — the conforming class actually gains the protocol's affordances at the class's own use sites.

```
protocol Countable:
    accepts iter() -> Iterator<Any>
    
    # Default implementation:
    function count(self) -> Int:
        var n = 0
        self.iter().for_each(_ => n += 1)
        return n

class MyCollection:
    data: items: [Int]
    methods:
        function iter(self) -> Iterator<Int>:
            return self.items.iter()
        # No count method defined.

# MyCollection structurally conforms to Countable.
# It inherits Countable.count as its own method.
let c = MyCollection(items: [1, 2, 3])
c.count()   # legal — dispatches to Countable's default; returns 3
```

**Precedence.** Own definitions and extensions on the class win over inherited defaults. Only messages the class doesn't define at all (in its own body or via any visible extension) fall through to protocol defaults.

**Diamond ambiguity.** If a class structurally conforms to two protocols `A` and `B`, both provide default implementations for the same message `m`, and the class does not define `m` itself — the call `instance.m()` is a compile error at the call site. Diagnostic names both candidate defaults.

Resolution options:

1. **Define the method on the class or via extension.** Removes the fall-through entirely:
   ```
   extend class MyClass:
       methods:
           function m(self): ...     # class's own m; both A.m and B.m are shadowed
   ```
2. **Upcast at the call site** to select a specific protocol's default:
   ```
   let a: A = instance
   a.m()                             # dispatches to A's default
   ```

No implicit resolution rule (first-imported-wins, alphabetical, etc.) — resolution must be explicit. This is the same principle governing extension shadowing (§3.3.2 `replace` modifier): ambiguity that could be resolved silently is resolved visibly.

**Interaction with extensions.** An `extend class Foo: methods: function m(self): ...` adds `m` to Foo. Once added, `m` is an own definition for the purposes of protocol default resolution — subsequent protocol defaults for `m` don't apply to Foo. The class-plus-extensions set is what determines whether a message falls through to a protocol default.

Extensions shadow inherited protocol defaults **without requiring `replace`** — the extension is providing the class's own definition, and per the priority rule above, own definitions win over inherited defaults. `replace` (§3.3.2) is reserved for shadowing another *explicit* declaration (original definition or another extension). See §3.3.2 rule 5 for the full statement and the second-extension edge case.

**Interaction with protocol extension.** An `extend class SomeProtocol: methods: function m(self): ...` adds `m` as a default to `SomeProtocol`. Every structural conformer that doesn't define `m` itself inherits the new default retroactively — when the module containing the extension becomes visible.

**Trait-qualified dispatch (new in v0.10.0).** When a protocol is generic — `As<T>`, `From<T>`, `Comparable<T>`, etc. — the type parameter is part of the method's identity, not an overload dimension over ordinary parameters. A class may declare multiple same-named methods that differ only in the protocol's type parameter:

```
class Payload:
    data: bytes: Bytes
    methods:
        function as(self) -> Bytes:  return self.bytes                     # As<Bytes>
        function as(self) -> Json:   return json_encode(self.bytes)        # As<Json>
        function as(self) -> String: return String.decoded(self.bytes)     # As<String>
```

All three methods coexist in `Payload`'s method table without conflict; each is keyed by `(name, conformed-protocol-instantiation)` rather than by `name` alone. At the call site, the compiler infers which one to dispatch by:

1. **Return-type context.** `let b: Bytes = p.as()` → `As<Bytes>.as`.
2. **Explicit turbofish.** `p.as::<Json>()` when context is insufficient.
3. **Trait qualification.** `As<Json>.as(p)` when maximally explicit is preferred.

If none of the three yields a unique target, compile error at the call site with the ambiguous candidates listed.

**Restricted to generic-protocol-derived same-name.** This is not open-ended overloading. It applies only when the same-name methods each satisfy a distinct instantiation of a *generic protocol* to which the class conforms. Two same-named methods that don't share a generic-protocol parent still conflict per §3.3.2 rule 5 (no signature-based overloading).

**Interaction with extensions.** Extensions may add new `As<T>` / `From<T>` conformances to a class after the fact — Corvid's post-hoc conformance model (§3.3.2) applies uniformly. An `extend class NSString: methods: function as(self) -> Yaml: ...` adds `As<Yaml>` conformance to NSString without conflicting with any of NSString's existing `.as() -> T` methods.

**Interaction with §3.3.2 rule 1 shadowing.** Two extensions both providing `function as(self) -> Bytes` on the same class *is* a rule-1 shadowing situation — same-name, same-protocol-instantiation. Rule 1's warning + deterministic resolution applies. The trait-qualified exemption only expands the identity key; it does not exempt genuine same-key duplicates.

**Why not require explicit conformance?** Corvid's design (§1.5, §3.4) has committed to structural conformance since v0.6+. Requiring `class Foo(SomeProtocol):` for defaults would split conformance into two categories (structural for static checks; explicit for defaults), which contradicts the uniform structural model. Reading A (implicit inheritance for structural conformers) preserves the uniformity; diamond disambiguation is the cost.

**Runtime obligation.** Method-table population (§5.1) is extended: at class-composition time, for each visible protocol whose `accepts:` the class satisfies structurally, the compiler considers the protocol's default methods for messages the class doesn't define. Ambiguity is detected at compile time; if resolved (class defines, or single protocol contributes), the default is either shadowed or inherited into the effective method table. No runtime cost beyond what the composition already required.

#### 3.4.5 Explicit Conformance Declarations

Structural conformance (§3.4) is the default: any class whose method table satisfies protocol P's `accepts:` list is assignable to a P-typed slot and inherits P's default methods (§3.4.4) — no declaration required. **Explicit conformance** is a compile-time claim, additive on top of structural: the author states that a class is *intended* to conform to a specific protocol, and the compiler validates that claim at the declaration site.

**Two equivalent forms.**

Header parens sugar — canonical for the definition site with 1–3 conformances:

```
class Reader(Iterable<Char>):
    state: source: String
    state: position: StringPosition
    methods:
        function iter(self) -> Iterator<Char>: ...

class Projectile(Positioned, Moving):
    state: pos: Point
    state: velocity: Vector
    methods:
        function position(self) -> Point: return self.pos
        function step(self, dt: Float): self.pos = self.pos + self.velocity * dt
```

Body-form entries — canonical for extensions, long conformance lists, or when consistency with extension-body forms matters:

```
class Reader:
    conforms: Iterable<Char>
    state: source: String
    state: position: StringPosition
    methods:
        function iter(self) -> Iterator<Char>: ...

class Projectile:
    conforms: Positioned
    conforms: Moving
    state: pos: Point
    state: velocity: Vector
    ...
```

Both forms desugar to the same conformance-set entry. The parens header is sugar over the body form; grammar produces one canonical AST shape after desugaring.

**Semantics.**

1. **Compile-time validation at the declaration site.** For every declared conformance, the compiler checks that the class's method table satisfies the protocol's `accepts:` list — either via own methods, extension methods visible at composition time, or §3.4.4 default-method inheritance. Missing or wrong-signature method → compile error at the class declaration, not at the first `let x: P = ...` assignment. **This is the primary payoff over structural conformance: fail early.**

2. **Structural conformance still applies for undeclared protocols.** A class that structurally satisfies a protocol without declaring it remains assignable to that protocol's slots and inherits its defaults. Explicit conformance is *strictly additive* — it declares the author's intent for the *listed* protocols; other protocols continue to work structurally.

3. **Runtime dispatch is identical.** Structural and explicit conformances produce the same method-table entries per §5.1. No runtime cost difference. No `@protocol` metadata emitted differently.

4. **Extensions may add conformances.** Both forms are legal on extensions:

   ```
   extend class NSString(As<Yaml>):
       methods:
           function as(self) -> Yaml: ...

   # Or body form:
   extend class NSString:
       conforms: As<Yaml>
       methods:
           function as(self) -> Yaml: ...
   ```

   Extension-added conformances accumulate with definition-site conformances into the class's unified conformance set. Two extensions declaring the same conformance with conflicting method definitions → §3.3.2 rule 1 shadowing warning + deterministic resolution.

5. **Additive across the class-plus-extensions set.** The class's total conformance set is the union of (a) header-parens conformances at definition, (b) body-form `conforms:` entries at definition, (c) header-parens conformances on any visible extension, (d) body-form `conforms:` entries on any visible extension. Ordering doesn't matter; duplicates are collapsed silently (declaring the same conformance twice is not an error — extensions may redundantly reassert what the definition already declared).

**Class inheritance — separate from conformance, ObjC-only, body-form.**

Corvid commits to composition-over-inheritance for pure Corvid code (§1.5, §3.4). The one exception is ObjC interop: subclassing UIViewController, NSObject, etc. is unavoidable for framework-facing code. Inheritance is expressed via body-form `metadata: extends:` — not header parens, not header colon.

```
class MyViewController:
    metadata:
        extends: UIViewController
    state:
        table_view: UITableView?
    dynamic function viewDidLoad(self):
        super.viewDidLoad()
        self.table_view = UITableView()
```

Rules:
- **`metadata: extends:` accepts at most one class**, which must be `kind: regular` (protocol classes cannot be extended-from — use conformance instead).
- **Only ObjC-imported classes are legal targets.** Pure Corvid classes cannot be subclassed; the compiler emits `not-subclassable` error if the extends target is a Corvid-defined regular class. Composition is the intended path.
- **Extends is set at definition, not modifiable by extensions.** Same rule as `kind:` (§3.3.2).
- **`super.method()` in the class body dispatches to the extended class's implementation** per §5.7 (and the ObjC runtime's `objc_msgSendSuper` at the interop boundary).

**Header form combines with generics + where-clauses:**

```
class Cache<K, V>(Iterable<Entry<K, V>>) where K: Hashable:
    state: store: Map<K, V>
    methods:
        function iter(self) -> Iterator<Entry<K, V>>: return self.store.iter()
```

Grammar ordering: `class NAME <generics> (conformances) where <constraints> :` — reads left-to-right as "class Cache of K, V, conforming to Iterable of Entry, constrained by K: Hashable, body follows."

**Retired: double-colon header form.**

The v0.9.x-and-earlier form `class Foo: A, B:` (protocol conformance) and `class Foo: BaseClass:` (ObjC inheritance) was **deprecated in v0.10.2** with a parse-time warning and fix-it suggestion, and is **removed in v0.11** — the parser now emits a hard compile error. Rationale (unchanged from v0.10.2 deprecation):

- **Double-colon shape carried two meanings** (conformance vs inheritance) with the same syntax, resolved by RHS kind. A design mistake worth undoing before v1.
- **First-colon starting a conformance clause is subtle** — parser and reader alike had to know the rule that the first `:` doesn't open the body. Parens `(A, B)` is unambiguously delimited.
- **The design commitment (composition over inheritance) now shows in the surface syntax** — protocols get the ergonomic parens; ObjC inheritance requires body-form ceremony. The exception looks like an exception.

Migration is mechanical:
- `class Foo: A, B:` → `class Foo(A, B):`
- `class Foo: BaseClass:` (where `BaseClass` is a `kind: regular` ObjC-imported class) → `class Foo:` with `metadata: extends: BaseClass`

**Diagnostic (v0.11):**

```
error[E0448]: retired conformance-declaration syntax
  --> src/reader.cor:12:1
   |
12 | class Reader: Iterable<Char>:
   | ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ double-colon header form (retired in v0.11)
   |
help: use parens sugar for protocol conformance
   |
12 | class Reader(Iterable<Char>):
   |             +               -
   |
note: this form was deprecated in v0.10.2 with fix-it; removed in v0.11 as scheduled.
```

**§4.18 metadata alignment.** Class metadata already reserves `superclass:` and `conforms_to:` keys (line 4694). §3.4.5 formalizes when they are populated:
- `superclass: X` — from `metadata: extends: X` in class body.
- `conforms_to: [P1, P2, ...]` — union of all explicit conformance declarations across definition and visible extensions. Structural conformances that were never declared are *not* in the metadata list (they're still runtime-effective for dispatch, but not enumerated).

**Runtime obligation.** Explicit conformance adds one compile-time validation pass per declared conformance; no runtime cost. The class's method table (§5.1) is unaffected by whether a conformance was declared explicitly or inferred structurally — dispatch is identical.

#### 3.4.6 Canonical Bytes as a Value-Type Base-Class Property

Per §3.3, `kind: record` and `kind: enum` carry a small fixed set of **base-class properties** the compiler synthesizes from field/case layout: structural equality (`==`), structural hashing (`hash(self)`), copy-on-assign, and **canonical bytes** — the methods `function as(self) -> Bytes` and `class function from(source: Bytes) -> DecodeOutcome<Self>`, produced from field layout per §3.10.10's wire format. Primitives (`Int`, `Float`, `Bool`, `Char`, etc.) are `kind: record` shipped in prelude with a single `data: bytes: Bytes where bytes.length == N` field (§3.1); their byte methods synthesize from that field layout via the same pathway as user records — no separate per-primitive prelude implementation.

This section documents the byte property — the equality/hash properties are documented at §3.3.

**Not a protocol carve-out; not a compiler-special-cased target-type.** The compiler does not know that `Bytes` is a special target type or that `As<Bytes>` is a special protocol. It synthesizes the two methods `as(self) -> Bytes` and `class function from(source: Bytes)` because value types have canonical bytes as a fundamental property of what value types ARE — same shape as records having structural equality. Once those methods exist on the class, structural conformance to `As<Bytes>` and `From<Bytes>` (§3.4) succeeds *as a consequence*, exactly like structural conformance to `Equatable` succeeds because `==` is synthesized. No separate "auto-conformance" mechanism exists.

**Regular classes (`kind: regular`) get none of these base properties.** Mutable `state:` fields, potential reference cycles, and hidden identity make mechanical equality / hashing / serialization unsafe. Regular-class authors write `==`, `hash(self)`, and `.as() -> Bytes` explicitly if they want those behaviors. Same discipline as Rust (`#[derive(...)]` for structs, hand-implement for anything with interior mutability), same discipline as Haskell (`deriving (Eq, Show)` for algebraic data types).

**Example.**

```
record Point:
    data: x: Float
    data: y: Float
    # Compiler synthesizes these two methods on Point (base-class properties, per §3.3):
    #   function as(self) -> Bytes:
    #       let out = Bytes.with_capacity(8)
    #       out.append(self.x.as())    # Float → 4 bytes, network-order
    #       out.append(self.y.as())    # Float → 4 bytes, network-order
    #       return out
    #   class function from(source: Bytes) -> DecodeOutcome<Self>:
    #       switch Float.from(source.slice(0..4)):
    #           case .invalid(let o, let r): return .invalid(o, r)
    #           case .converted(let x):
    #               switch Float.from(source.slice(4..8)):
    #                   case .invalid(let o, let r): return .invalid(o + 4, r)
    #                   case .converted(let y): return .converted(Self(x: x, y: y))
    # Structural conformance to As<Bytes> and From<Bytes> follows per §3.4 — the
    # methods exist on the class, so the structural check succeeds. No separate
    # "auto-conformance" declaration required or generated.

let p = Point(x: 1.0, y: 2.0)
let b: Bytes = p.as()                                 # calls synthesized method
let p2 = Point.from(source: b)                        # returns DecodeOutcome<Point>
```

**Enum example.**

```
enum Direction:
    case north
    case south
    case east
    case west
    # Compiler synthesizes:
    #   function as(self) -> Bytes:
    #       switch self:
    #           case .north: return [0x00]
    #           case .south: return [0x01]
    #           case .east:  return [0x02]
    #           case .west:  return [0x03]
    #   class function from(source: Bytes) -> DecodeOutcome<Self>: ...

enum Shape:
    case circle(radius: Float)
    case rectangle(width: Float, height: Float)
    # Compiler synthesizes: discriminator byte + associated data recursively.
    #   circle(1.5): [0x00, <4-byte float 1.5>]
    #   rectangle(2.0, 3.0): [0x01, <4-byte 2.0>, <4-byte 3.0>]
```

**Discriminator width.** Enums with ≤256 cases use `UInt8` discriminator; enums with 257–65536 cases use `UInt16`; beyond that use `UInt32`. Discriminator width is set at composition time from case count. Adding a case to an enum that pushes it across a width boundary is a wire-incompatible change — flagged by the compiler at composition, since it would break decoding of existing serialized values.

**Field-type recursion.** A record with a field of type `T` requires `T` to have `.as() -> Bytes` and `class function from(source: Bytes)` methods, so synthesis of the containing record's byte methods can call them. If `T` is a record/enum/primitive → guaranteed by the base-class property. If `T` is a `kind: regular` class → the author must have written the methods. If `T` has neither, the record declaration fails at compile time with a diagnostic pointing at the field:

```
error[E0520]: field `T`'s type `SomeRegularClass` has no `.as() -> Bytes` method
  --> src/model.cor:12:9
   |
12 |     data: helper: SomeRegularClass
   |     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ cannot synthesize `Model.as() -> Bytes`
   |
help: either write `.as() -> Bytes` on `SomeRegularClass` explicitly
   |
   |     extend class SomeRegularClass:
   |         methods:
   |             function as(self) -> Bytes: ...
   |
help: or write `Model.as() -> Bytes` explicitly to opt out of synthesis
```

**User override.** A value-type author who wants a different wire format writes `.as() -> Bytes` explicitly on the record. Per §3.3.2 rule 6, own definitions shadow inherited defaults; the synthesized method is treated as an inherited default. Zero ceremony to override; zero ceremony to accept.

```
record Timestamp:
    data: seconds: UInt64
    data: nanos: UInt32
    methods:
        function as(self) -> Bytes:
            # Custom format: single UInt64 nanoseconds since epoch, not two fields.
            return (self.seconds * 1_000_000_000 + self.nanos.as::<UInt64>()).as()
```

**Interaction with §3.4.5 explicit conformance.** A record does not need `class Point(As<Bytes>):` header sugar — the byte methods already exist as base-class properties, so structural conformance succeeds. Declaring it explicitly is legal but redundant (compiler treats it as a no-op reaffirmation, no warning). Explicit declaration on a `kind: regular` class does what it always did — validates at declaration time that the class's method table satisfies `As<Bytes>`'s `accepts:` list (which for regular classes means the user's own `.as() -> Bytes` must be present).

**Why only bytes get the base-class property.** Bytes are Corvid's universal substrate (§3.10.4). One canonical encoding exists for a given field layout — concatenate field bytes in declaration order — with no author choice (endianness is fixed language-wide per §3.10.10). JSON, MsgPack, CBOR, and other format-specific representations are **not** base properties: they have per-record author choices (field naming — camelCase vs snake_case? null handling — omit missing or emit `null`? number precision — full float or truncated? enum spelling — case name string or integer discriminator?) with no defensible language-level default. Silent choice would be a footgun; the same principle that made narrowing `Int32(300).as() -> UInt8` a compile error rather than silent-wrap applies here.

Users needing JSON write `function as(self) -> Json:` explicitly per record. Third-party format packages (`corvid_json`, `corvid_msgpack`, etc.) ship their target type (`Json`, `MsgPack`) and users write conformances the same way they'd write any protocol method — no special language support, no compiler awareness of specific target-type names.

**Runtime obligation.** Synthesized methods populate the class's method table (§5.1) exactly as user-written methods do. No runtime cost difference; no metadata difference. §4.18 metadata lists the class as `conforms_to: [As<Bytes>, From<Bytes>, ...]` — the conformance is structural (§3.4), and the metadata records the resulting set regardless of whether the underlying method was user-written or synthesized as a base property.

**Runtime cost of synthesis.** Compile-time only. The synthesized method bodies compile to the same instructions user-written equivalents would produce. Field iteration is unrolled at compile time (field count is known); no runtime reflection.

### 3.5 Effects

Effect annotations describe what a method does beyond returning a value. Effects split into two categories under the intent-not-authority principle. **Failure is not an effect in Corvid** — it is expressed via return type (nil-punning or domain enum, §3.5.4).

#### 3.5.1 Advisory Effects

Author expresses expected behavior; compiler checks at compile time as authoring aid; **runtime does not enforce**.

- `allocates` — expected to allocate heap memory.
- `blocks` — expected to block the calling thread.

Consumer-side strictness is available via compile flag (`--strict-effects`) or per-file pragma.

#### 3.5.2 Semantic Effects

These annotations change compilation and cannot be "just hints."

- `async` — changes calling convention (suspendable).

**Retired v0.11 — `mutating`.** The `mutating` effect was carried over from a pre-v0.6.0 design that had `struct` as a live construct. v0.6.0 unified struct/enum/protocol/class under `kind:` metadata; there is no `struct` in Corvid. `kind: record` forbids `state:` (§4.10.7) so records cannot have mutating methods; `kind: regular` methods that mutate state are inferred as writers by §8.4.1 reader/writer classification (no author declaration needed). The `mutating` effect had no live use site — neither author-declared (compiler infers) nor applicable (records can't mutate). Retired for the same reason `throws` was retired in v0.8.5 — no live semantics under the current design.

#### 3.5.3 Effect Polymorphism

Generic parameters can range over effects, allowing higher-order functions to propagate the effects of their callbacks.

**Explicit form:**

```
function map<T, U, E: Effect>(items: [T], f: (T) -> U with E) -> [U] with E:
    result = []
    for item in items:
        result.append(f(item))
    return result
```

`E: Effect` declares `E` as a member of the Effect kind. `with E` on a function type includes E's effects in the type. `with E` on the return type propagates them.

Callers see effects inferred from their specific callback:

```
result = map(orders, function(o) async -> Receipt { ... })
# E is inferred to `async`.
# result: [Receipt] with async.
```

**Inference sugar for the common case:**

Functions taking function-typed parameters can omit the explicit effect polymorphism; effects are inferred from callback usage:

```
function map<T, U>(items: [T], f: (T) -> U) -> [U]:
    # Effects of map = union of effects of f's actual argument at each call site.
    ...
```

Sugar applies only within a single module (source available). Public API across module boundaries requires explicit effect polymorphism for legibility (§1.6).

**Aliases can name effect combinations:**

```
alias @networked: async, allocates
```

Parameterized aliases resolve at use site (see §4.16).

#### 3.5.4 Failure Handling — Nil-Punning + Domain Enums, No `throws`

Corvid has **no `throws` effect, no `try` keyword, no `catch` / `finally` construct, and no `Result<T, E>` in the prelude**. Failure is expressed in the return type of the failable operation. Three shapes cover every case:

| Failure shape | Mechanism | When to use |
|---|---|---|
| **Uniform failure** — "did we get a value or not" | Return `T` (nullable). Caller uses nil-punning (§3.6.1, §5.7) — `??` for defaults, `if let` to gate, `on_nil` section for typed defaults. | Simple absence: dictionary lookup, integer parsing, cache miss. Only one meaningful failure mode; no discrimination needed. |
| **Discriminated failure** — "how did we fail" | Return a **domain-specific enum** with a case per outcome (success case + one case per failure mode). Caller uses `switch` on the returned enum. | Multiple failure modes, each needing different handling: file open, network request, database write, complex parsing. |
| **Invariant violation** — "this shouldn't be possible" | `panic("...")`. Halts the process. Not a recoverable failure — a program bug that shouldn't be caught. | Corrupted internal state, contract broken by a caller, unreachable branch. |

**Uniform failure example (nil-punning):**

```
function parse_port(s: String) -> UInt16:
    # returns nil on unparseable / out-of-range
    ...

port = parse_port(config.port) ?? 8080     # simple default on failure
```

**Discriminated failure example (domain enum + switch):**

```
enum FileOpenOutcome:
    case handle(File)
    case not_found(path: String)
    case permission_denied(path: String)
    case volume_unavailable(volume: String)
    case corrupted(path: String, details: String)

function File.open(path: String) -> FileOpenOutcome:
    # ... implementation dispatches to appropriate case

# Caller:
switch File.open("/config.json"):
    case .handle(let f):
        return parse_config(f)
    case .not_found:
        return Config.default()
    case .permission_denied:
        prompt_credentials()
        return open_config()
    case .volume_unavailable(let volume):
        alert("please reconnect drive: ${volume}")
        return Config.default()
    case .corrupted(_, let details):
        panic("config corrupted: ${details}")
```

**Invariant violation example (panic):**

```
function dequeue_next(self) -> Order:
    requires not self.pending.is_empty
    
    switch self.pending.pop_first():
        case .some(let o): return o
        case .none:
            # requires said this can't happen; if it did, invariant broke
            panic("dequeue_next called with empty pending — precondition violated")
```

**Composition (chaining without try).** Because domain enums are ordinary values, chaining fallible operations is just chaining `switch` blocks. Leading-dot shorthand (§3.7.2) keeps the case syntax terse:

```
function process(input: String) -> ProcessOutcome:
    switch parse(input):
        case .value(let v):
            switch validate(v):
                case .accepted(let sanitized):
                    switch save(sanitized):
                        case .stored(let id):     return .completed(id: id)
                        case .conflict:           return .duplicate
                        case .storage_full:       return .capacity_exceeded
                case .rejected(let reason):
                    return .invalid(reason: reason)
        case .malformed(let details):
            return .parse_failed(details: details)
```

**Style guide.** Switch-chain nesting deeper than 3 is a design signal — decompose into smaller functions each returning a domain enum. Rust's `?` and Swift's `try` optimize for pipeline-style code with 4-6 fallible steps; Corvid instead pushes those apart into smaller units. Trade-off: more function boundaries, less cross-cutting propagation, more visible dispatch at each layer. Consistent with §1.6 (legibility over brevity) and Corvid's visible-dispatch preference.

**ObjC / Swift interop (§9.3).** ObjC `NSError**` out-param methods are imported as returning a domain enum with `.completed(T)` and `.failed(NSError)` cases. Swift `throws` methods appear as returning `Result<T, Error>` at the interop boundary (with `Result<T, E>` defined at the interop-shim layer, not prelude). Corvid methods exposed to ObjC are lowered with automatic `NSError**` out-param when the return type is a domain enum containing a `.failed(NSError)`-shaped case.

**What this closes:**

- `throws` effect (this section) — retired.
- `try` / `catch` / `finally` — not spec'd; not added.
- `Result<T, E>` in prelude — not shipped.
- Ink friction #17 (try semantics) — resolved by dissolution.
- Ink friction #29 (throws in ensures) — dissolves entirely; ensures apply on `switch`-discriminated cases.

### 3.6 Refinement / Precondition Contracts

Any type can carry a `requires` clause narrowing its inhabitants:

```
type Positive = Int requires self > 0
type NonEmpty<T> = [T] requires self.count > 0
```

Verification: runtime by default. Static verification via SMT is an optional backend.

### 3.6.1 Nullability and Nil-Punning

Corvid's nullability model inverts Swift's: **reference types allow nil at runtime by default; non-nil is the exception marked by contract.** This matches the ObjC runtime substrate (§1.5) — messaging nil is native, not a special case the type system fights to prevent.

**Semantics** (unchanged from v0.7.0):

| Type shape | Reference type (`kind: regular`) | Value type (`record`, `enum`, primitive) |
|---|---|---|
| `T` | nil-allowed at runtime | never nil |
| `T!` (via prelude alias) | contract-checked: invariant `value != nil` | rejected — see prelude guard (trivially true) |
| `T?` (via prelude alias) | rejected — redundant with `T` (prelude guard) | `Optional<T>` sum type |

The surface syntax `T!` and `T?` are prelude aliases (§4.20.1) — suffix-position aliases with `where T is reference_type` / `where T is value_type` guards. The guards enforce the reference/value distinction: applying `?` to a reference type is a compile error (redundant), applying `!` to a value type is a compile error (trivially true).

`T!` desugars via the prelude alias to `T with invariant: value != nil`. The compiler emits a runtime check at each observation point (field write, parameter bind, return); a violation logs a warning in development, configurable action in release. Nil continues to flow through message sends — `T!` documents author expectation, it does not enforce safety.

**Value-site semantics:**

- **Member access on nil.** `nil.method(args)` is legal. Method-return `T` auto-lifts to `T?` at the call site (message send on nil returns nil per the ObjC runtime); method-return `Unit` is a silent no-op. If the receiver's class or a conformed protocol declares `on_nil:` (§4.10.9) with an entry for this method, that entry's value is returned instead of nil-lift.
- **Nil-coalescing.** `x ?? default` returns `x` if non-nil, else `default`. Types must match; the result type is the non-optional common type.
- **Explicit panic.** `x ?? panic("reason")` is the sanctioned "die if nil" form. There is no shorter sugar. Verbosity is intentional — panic should look exceptional in source so it stays exceptional in behavior.
- **Scoped unwrap (single binding).** `if let x = maybe: ...` and `guard let x = maybe else: ...` bind unwrapped `x` for the following scope. Standard patterns from the Swift-family.
- **Scoped unwrap (multi-binding).** Comma-separated `let` bindings in a single `if let` / `guard let` — all must succeed for the body/continuation to run:

  ```
  if let url = request.url, let parts = url.get_parts():
      # both url and parts are non-nil and bound in this scope
      ...

  guard let host = self.host, let port = self.port else:
      return .failure(reason: "missing host or port")
  ```

  Semantics: bindings are evaluated left-to-right, short-circuiting on the first nil. In `if let`, the else branch (if present) runs on any failure; in `guard let`, the else branch always runs on any failure and must exit (return / break / continue / panic). No partial binding — either all names are bound in the following scope, or none are.
- **Arithmetic and ordering on nil are type errors.** `nil + 3`, `nil < 3`, and `nil % 2` do not propagate — the semantics are ambiguous. Unwrap explicitly or coalesce with `??`.
- **Equality.** `nil == nil` is `true`, `nil == x` (for non-nil `x`) is `false`. `!=` inverts. Ordering operators reject nil operands at compile time.

**Retired from prior drafts:**

- **Force-unwrap (`x!` at value site).** The v0.5–v0.6 form matched Swift; retired in v0.7. Users write `x ?? panic("reason")` when they need to fail on nil at a specific site. Removes the attractor for hidden panics as codebases evolve — every nil-panic site is verbose enough to notice in code review.
- **Optional chaining (`x?.method()`).** The v0.5–v0.6 form required `?.` at every optional-receiver call site. Retired in v0.7 — `.` operates uniformly on nil and non-nil receivers (nil-lift return, `on_nil` dispatch), so the marker was ceremony with no semantic content.

**Interaction with contracts (§1.9, §3.4.2):**

`T!` at the type site is a specific case of the general invariant mechanism. The following are equivalent:

```
class Handler:
    accepts:
        logger: Logger!

# desugars to
class Handler:
    accepts:
        logger: Logger
    invariants:
        logger != nil
```

Users needing richer nil-related contracts (e.g., "not nil AND not empty") write the invariant form directly. `T!` is optimized for the common case.

**Userspace nil-punning.** Every user method is automatically nil-safe for message sends (ObjC-inherited). For methods that should return a specific non-nil value on nil receiver — Clojure-style `(count nil)` = `0` semantics — the class or protocol declares a `on_nil:` (§4.10.9) with a method → value table. Stdlib `Collection` and `String` protocols ship default `on_nil` entries; conformers inherit.

### 3.7 Pattern Matching (`switch`)

`switch` is the pattern-matching construct. It extends C/ObjC `switch` with structural patterns, exhaustiveness for closed sums, expression form, and no fallthrough by default.

**Expression form:**

```
result = switch value:
    case .ok(x):
        process(x)
    case .err(e):
        log(e)
        default_receipt
```

`switch` is an expression; each `case` block produces a value; all values must have compatible types. This is one instance of the general "everything is an expression" commitment (§3.8).

**Pattern types:**

- **Literal patterns:** `case 0:`, `case "hello":`, `case true:`.
- **Enumeration case patterns:** `case .some(let x):`, `case .none:`.
- **Type patterns (for `Any`):** `case Order(o):`, `case String(s):`.
- **Structural patterns (records/data classes):** `case Order(id: some_id, items: []):`.
- **Guard patterns:** `case Order(o) if o.total > 100:`.
- **Wildcard:** `case _:`.

**Exhaustiveness:**

- Switching on a closed sum type (enumeration): all cases must be handled, or a `case _:` wildcard included.
- Switching on a protocol: wildcard required (protocols are open — new conformers may be added).
- Switching on `Any` or an open type: wildcard required.
- Switching on numeric or string types: wildcard required.

Non-exhaustive matches are compile errors.

**No fallthrough:**

Each `case` block runs to completion; no implicit fallthrough to the next case. Multiple values matching the same code:

```
switch color:
    case .red, .orange, .yellow:
        return "warm"
    case .blue, .purple:
        return "cool"
    case .green:
        return "neutral"
```

#### 3.7.1 No Destructuring Bindings

Corvid deliberately does **not** provide destructuring in binding positions. The following forms are not part of the language:

```
let (a, b) = pair                       # NOT SUPPORTED
let {x, y} = point                      # NOT SUPPORTED
if let (k, v) = maybe_pair: ...         # NOT SUPPORTED
guard let (a, b, c) = triple else: ...  # NOT SUPPORTED
for (k, v) in dict: ...                 # NOT SUPPORTED
function summarize((x, y): Pair): ...   # NOT SUPPORTED (parameter destructuring)
```

Multi-value extraction always uses **named accessors on the returning class**:

```
let pair = compute_pair()
process(first: pair.first, second: pair.second)

for entry in dict:
    process(key: entry.key, value: entry.value)
```

**Rationale.**

1. **Names beat positions, uniformly.** v0.8.1 committed to named-only associated values on enum cases (`case closed(code: UInt16)`, not `case closed(UInt16)`). The same discipline applies here. A `Pair<A, B>` has fields `first` and `second`; those names are what the reader sees, and they're the same names at every use site.
2. **Class shape stays authoritative.** Destructuring reifies a shape (parenthesized-two-thing) that isn't the class's shape. Under Corvid's class-as-universal model (§1.5), the shape is the class's field structure — access should go through it.
3. **Iteration is method dispatch, not language pattern.** `for entry in dict` iterating over `Entry<K, V>` via `Iterable`-based dispatch (§3.10) is the native pattern in a class-based OOP model. Positional destructuring in the `for` binding would be C-family sugar bolted onto a message-send model that doesn't need it.
4. **Legibility over brevity (§1.6).** The two-character save from `(k, v)` vs. `entry` and then `.key`/`.value` at use sites is not worth the readability cost of encoding class shape in punctuation.

**Case patterns inside `switch` are the one exception, and by design.** Pattern matching in `case` is discrimination between shapes — it *has* to look inside the value to decide which case applies. The syntax `case .ok(value: let x):` is not general destructuring; it is case-discrimination-with-name-binding, and only inside a `switch` block. Reading:

- `case .some(value: let x):` binds `x` to the wrapped value only in the branch where `.some` matched.
- `case Order(id: let some_id, items: []):` binds `some_id` and asserts `items == []`.
- Positional form `case .some(let x):` is legal as a **single-associated-value shorthand** — the one legal use of positional form in the language, because there's no ambiguity (single value) and it's inside case discrimination.

Rule: name-based extraction inside `case` patterns is allowed (discrimination requires it). Everything else uses named-accessor method dispatch on the class.

#### 3.7.2 Leading-Dot Shorthand

When the target type of an expression is unambiguous from context, `.name(...)` and `.name` resolve to `<InferredType>.name(...)` and `<InferredType>.name` respectively. The dot is a placeholder for the inferred type name.

**Target-typed contexts** (the compiler infers the type from the surrounding expression):

| Context | Example |
|---|---|
| Switch scrutinee | `switch event: case .text(let s): ...` — inferred: type of `event` |
| Function parameter (typed) | `handler.set_state(.receiving)` — inferred: parameter type of `set_state` |
| Function return value | `return .ok(result)` — inferred: function's declared return type |
| Field declared type | `state: FrameCollectorState = .idle` — inferred: field type |
| Variable declared type | `let s: FrameCollectorState = .receiving` — inferred: declared type |
| Comparison with typed expr | `if event == .none: ...` — inferred: type of `event` |
| Enum-case pattern (already legal) | `case .text(let s):` — same mechanism, formalized in §3.7 |

**Enum case shorthand:**

```
enum Result<T, E>:
    case ok(value: T)
    case err(error: E)

function parse(input: String) -> Result<Int, ParseError>:
    if input.is_empty:
        return .err(error: .empty_input)     # inferred: Result, then ParseError for inner
    ...
    return .ok(value: 42)                    # inferred: Result

switch parse("42"):
    case .ok(value: let x): ...              # inferred from scrutinee
    case .err(error: .empty_input): ...      # nested; inner .empty_input inferred from case shape
    case .err(error: .bad_digit(let c)): ...
```

**Class function shorthand.** The same rule applies to class functions (`class function name(...)`) and enum case constructors — anywhere `Type.name(...)` is a legal expression at the resolved type, `.name(...)` works if the type is inferable.

```
class Duration:
    class function seconds(n: Int) -> Duration: ...
    class function milliseconds(n: Int) -> Duration: ...

sleep(.seconds(3))                            # inferred: Duration parameter of sleep
timeout: Duration = .milliseconds(500)        # inferred: field type
```

**`Self.` shorthand inside a class body.** Inside a class's methods, `.name` at any position where `Self` is the inferred type resolves to `Self.name`. This falls out of the general rule; documented here for completeness:

```
class Order:
    state:
        status: OrderStatus = .pending      # inferred: OrderStatus, the field type

    function submit(self):
        self.status = .submitted            # inferred: OrderStatus, the field type
```

**Ambiguous contexts are errors.** When the compiler cannot infer a single unambiguous target type, leading-dot is a compile error. Common cases:

```
let x = .some(3)             # ERROR: target type undetermined
                             # fix: let x: Optional<Int> = .some(3)

func f<T>(x: T) -> T: ...
f(.some(3))                  # ERROR: T is generic; leading-dot cannot infer through it
                             # fix: f(Optional<Int>.some(3)) or f(Optional.some(3))
```

Error message points at the leading-dot expression and suggests the qualified form.

**Interaction with nested lookups.** Leading-dot resolves only the top-level type qualifier. Chains like `.a.b.c` are not shortened — the compiler resolves the leading `.a` against the inferred type and then processes `.b.c` as ordinary member access. Deeper nested resolution would require target-typing every intermediate step, which is not supported.

**Interaction with target-directed literals.** Leading-dot composes with numeric literal target-typing (§2.7), collection literal conformance (§3.10), and other target-typed constructs — all use the same "target type inferred from context" mechanism, applied at their respective grammar positions.

### 3.8 Expressions and Statements

Corvid commits to **everything is an expression** for control-flow constructs. `if`, `switch`, blocks, and (in a specific form) loops all produce values. This unifies control flow and expression composition — one construct, usable in statement position or expression position depending on context.

#### 3.8.1 If-as-expression

```
port: Int = if is_tls: 443 else: 80

max_size = if payload_length > threshold:
    2 * threshold
else:
    payload_length

# Chained else-if works uniformly:
category = if code < 400:
    "success"
else if code < 500:
    "client_error"
else:
    "server_error"
```

Both branches must have compatible types. A one-armed `if` without `else` returns `Unit`; using it in expression position is legal but rarely useful.

#### 3.8.2 Block-as-expression

A block (`do:` in expression position, or an implicit block inside another expression) evaluates to the value of its final expression:

```
payload: Bytes = do:
    header = read_header()
    body = read_body(header.length)
    Bytes.concat(header, body)     # block's value
```

If the block contains only statements with no trailing expression, its value is `Unit`. An explicit `return` inside a method-level block returns from the enclosing method as usual; inside an expression-position `do:` block, `return` is still a method-level return (not a block-level return).

#### 3.8.3 Loops as expressions — `while` and `for` return `Bool`

Loops in Corvid are expressions producing `Bool`:

- **`true`** — the loop terminated because its condition became false (`while`) or its iteration exhausted (`for`).
- **`false`** — the loop exited via `break`.

This gives a first-class idiom for the "did we complete naturally?" pattern that most languages require a flag variable for:

```
# Search — check whether we exhausted without finding a match
not_found = for item in items:
    if item.matches(target):
        break
    # (loop body may do other work)

if not_found:
    handle_absence()
else:
    handle_present()

# Drain a queue until a sentinel
drained = while queue.not_empty():
    x = queue.pop()
    if x.is_terminator():
        break
    process(x)
# drained == false means we hit the terminator; true means the queue emptied naturally
```

**Break with a value is not supported on `while` and `for`.** The return type is fixed to `Bool` for legibility — a reader seeing `let x = while ...` immediately knows `x` is `Bool`.

For the rare case where a loop needs to return a T-typed value via break, use the infinite `loop { ... }` construct with `break value`:

```
# Rust-style: infinite loop, break-with-value returns T
first_match = loop:
    item = source.next()
    if item.matches(target):
        break item                # returns Match(item)
    if source.is_exhausted():
        break not_found_default    # returns default
```

`loop:` produces the type of its `break value` expressions (all `break value` sites must have compatible types).

#### 3.8.4 Statement position

Any expression can be used as a statement — its value is discarded. Loops, `if`, `switch`, and blocks all work this way; using them in statement position simply discards the Bool/T result.

**Consequence for legibility.** Users can write code the way they think about it: `if condition: run_side_effect()` reads as a statement even though `if` is technically an expression; the discarded `Unit` value doesn't affect meaning.

#### 3.8.5 Parameter Defaults

Function and constructor parameters may carry a default-value expression, applied when the caller omits the argument at the call site.

```
function connect(host: String, port: UInt16 = 443, use_tls: Bool = true) -> Connection:
    ...

constructor WSFramer(self, is_server: Bool = false):
    self.is_server = is_server

# Call sites:
connect(host: "example.com")                             # port = 443, use_tls = true
connect(host: "example.com", port: 8080)                 # use_tls = true
connect(host: "example.com", use_tls: false)             # port = 443
```

**Rules:**

1. **Compile-time constants only.** Default expressions must be constant at compile time. Permitted forms: primitive literals (`42`, `"hi"`, `true`, `3.14`, `'a'`, `nil`, hex/bin/oct integers) and references to value aliases (§4.16.10). Collection literals (`[]`, `{}`), calls, and other runtime-computed expressions are rejected.
2. **Static insertion.** The compiler substitutes the default value at each call site that omits the argument. No runtime cost; no shared-mutable-state footgun (Python's mutable-default pattern is impossible by construction because constants can't be mutated).
3. **Defaults appear after non-defaulted parameters.** In a parameter list, all defaulted parameters must follow all non-defaulted parameters. `function f(x: Int, y: Int = 0)` is legal; `function f(x: Int = 0, y: Int)` is a compile error.
4. **Named arguments at call sites.** Callers may skip defaulted parameters in the middle of the list only by using named arguments: `connect(host: "h", use_tls: false)` skips `port`.
5. **Same discipline for constructors and functions.** Constructor parameter defaults follow the same rule set. No divergence between constructor and function forms.

**For runtime-computed defaults** — use `nil` + coalesce in the body:

```
function connect(host: String, port: UInt16 = nil) -> Connection:
    let actual_port = port ?? default_port_for(host)     # runtime computation, visible in body
    ...
```

The runtime computation is explicit in the body, not hidden in the signature. Trade-off: verbosity for legibility (§1.6) and consistency (compile-time constants everywhere, no exceptions).

**Rationale for compile-time-only discipline:**

- Matches value alias discipline (§4.16.10) — one rule for "what counts as a static substitution."
- Removes the Python mutable-default footgun (`def foo(x=[]):` where `[]` is shared across calls).
- Removes ambiguity about default-evaluation timing (per-call vs at-declaration) — with constants, both are equivalent, so the question dissolves.
- Enables direct inlining at every call site with no captured environment.

### 3.9 Operators

Corvid's operators split into two tiers: **bootstrap operators** (§10 grammar-committed, cannot be overridden) and **prelude operators** (declared as alias declarations in §4.20, unified precedence scheme in §4.20.2). Precedence and associativity match C99 to avoid surprises for C-family readers.

**Bootstrap operators (compiler-committed):**

| Operator | Category | Meaning | Legal on |
|---|---|---|---|
| `+`, `-`, `*`, `/`, `%` | Arithmetic | Standard arithmetic + integer remainder (C99 truncated) | Numeric types |
| `+=`, `-=`, `*=`, `/=`, `%=` | Compound arithmetic assign | RHS applied, assigned to LHS | Numeric types |
| `==`, `!=` | Equality | Structural for records, identity for references; nil-safe per §3.6.1 | Any type |
| `<`, `<=`, `>`, `>=` | Ordering | Lexicographic for strings; rejected on nil operands per §3.6.1 | Numeric, `Char`, `String` |
| `and`, `or`, `not` | Logical | Short-circuit; word-form for legibility (§1.6) | `Bool` |
| `=` | Assignment | Right-associative | Any lvalue |
| `.` | Member access | Nil-flows through per §3.6.1, §5.7 | Any receiver |
| `()` | Call | Function/method invocation | Callable |
| `[]` | Subscript | Container access | Types with `subscript` |

**Prelude operators (alias declarations, see §4.20):**

| Operator | Category | Meaning | Prelude alias expansion |
|---|---|---|---|
| `<<`, `>>` | Bitwise shift | Left/right shift; arithmetic for signed, logical for unsigned | `Numeric.shl(lhs, rhs)` / `Numeric.shr(lhs, rhs)` |
| `&`, `\|`, `^` | Bitwise binary | AND, OR, XOR | `Numeric.bitand` / `Numeric.bitor` / `Numeric.bitxor` |
| `~` | Bitwise unary | NOT | `Numeric.bitnot(expr)` |
| `<<=`, `>>=`, `&=`, `\|=`, `^=` | Compound bitwise assign | RHS applied, assigned to LHS | Compound form of prelude aliases |
| `..<`, `...` | Range | Half-open, closed range | `Range.half_open` / `Range.closed` |
| `??` | Nil-coalescing | LHS if non-nil, else RHS | `Optional.coalesce(lhs, rhs)` |

**Prelude alias declarations for the operators above are in §4.20.1.** Precedence values follow the unified scheme in §4.20.2.

**C's `&&`, `||`, `!` are not used** — Corvid spells logicals as words (`and`/`or`/`not`) for legibility. This reserves `&` and `|` unambiguously for bitwise (prelude), and `!` for the type-position non-nil contract (also prelude, `T!` alias).

**Integer overflow semantics.** Signed overflow is undefined behavior in C99; Corvid tightens this: signed overflow **wraps** by default (two's-complement modular arithmetic). Users wanting checked semantics use `checked_add`/`checked_sub`/etc. methods that return `ConvertOutcome<T>` (§3.11.3) — `.converted(T)` on success, `.overflow(source, target)` on out-of-range. Users wanting saturation use `saturating_*` variants. This departs from C99 UB in favor of predictable behavior consistent with the "runtime as substrate, not as ceiling" premise (§1.5). The three-method shape (wrap / saturate / checked) matches the numeric narrowing methods (§3.11.2) — same design principle: semantics is a caller-choice-per-call operation, expressed at the call site as a method name, not as a protocol family.

**Bit-shift semantics.** Shift amount must be in `0..bit_width(T)`. Shift by a value outside this range is undefined behavior in C99; Corvid rejects it as a contract violation (runtime warning; deterministic wrap in release). Matches the overflow discipline above.

**Note on the bootstrap/prelude split.** Arithmetic, comparison, and logical operators are bootstrap because the compiler itself needs them to parse alias definitions with precedence values, evaluate guards, and compute basic control flow. Bitwise operators, ranges, and nil-coalescing are not needed by the compiler internals — they can be (and are) prelude aliases. This preserves the discipline: everything user-facing that CAN be a stdlib alias, IS.

### 3.10 Collection Literal Conformance

Collection literal syntax (`[...]`, `{K: V, ...}`, `{...}`) is defined by prelude aliases (§4.20) that lower to protocol-dispatched constructors. This allows any conforming type — stdlib collections, user types, Foundation-imported types — to accept the same literal syntax.

**Protocols shipped in the prelude:**

```
protocol ExpressibleAsArrayLiteral:
    accepts from_literal(items: [Element]) -> Self
    associatedtype Element

protocol ExpressibleAsMapLiteral:
    accepts from_literal(pairs: [(Key, Value)]) -> Self
    associatedtype Key
    associatedtype Value

protocol ExpressibleAsSetLiteral:
    accepts from_literal(items: [Element]) -> Self
    associatedtype Element
```

**Dispatch model.** The prelude aliases expand to `T.from_literal(items)` where `T` is inferred from the target type at the literal's use site:

```
let numbers: [Int] = [1, 2, 3]            # T = Array<Int>; Array.from_literal([1, 2, 3])
let headers: NSDictionary = {"Host": h}   # T = NSDictionary; NSDictionary.from_literal([("Host", h)])
let cache: MyCache<Int> = [1, 2, 3]       # T = MyCache<Int> — must conform to ExpressibleAsArrayLiteral
```

If the target type is not specified and cannot be inferred, the compiler defaults to `Array<T>` / `Map<K, V>` / `Set<T>` for the three literal shapes.

**User conformance:**

```
class RingBuffer<T>:
    conforms: ExpressibleAsArrayLiteral where Element = T
    
    accepts:
        storage: [T]
    
    class function from_literal(items: [T]) -> RingBuffer<T>:
        return RingBuffer(storage: items)

# Usage:
let buf: RingBuffer<Int> = [1, 2, 3]
```

**Interop with Foundation types.** `NSArray`, `NSDictionary`, `NSSet` conform to the respective literal protocols via stdlib bindings — literal syntax works uniformly across native and imported types with no `.into(...)` conversion required at construction.

**Empty literal disambiguation.** Empty `{}` is `Map.from_literal([])` by convention (§4.20.1); empty `[]` is `Array.from_literal([])`; empty set requires explicit `Set()` construction (no literal form for it — `{}` is taken by Map).

#### 3.10.1 Iteration Protocols — `Iterable`, `Iterator`

Iteration in Corvid is method dispatch on the collection, not a language primitive. The `for` control-flow form is a prelude alias (§4.20.1) that expands to `.iter().for_each { … }` on any type conforming to `Iterable<T>`. Corvid ships two protocols:

```
protocol Iterable<T>:
    accepts iter() -> Iterator<T>

protocol Iterator<T>:
    accepts next() -> T                            # returns nil when exhausted (nil-messaging §3.6.1)
    accepts for_each(f: (T) -> Void) -> Void       # convenience; default impl uses next()
```

**Nil-on-exhaustion.** `next() -> T` returns nil when the iterator is exhausted. Under Corvid's nil-messaging model (v0.7.0, §3.6.1), the return type auto-lifts for reference types; for value types, `next() -> T?` is the sugar-desugared form. Callers writing manual loops use `if let x = iter.next(): …` (v0.8.1 multi-binding form works too).

**Default `for_each`.** The `Iterator<T>` protocol ships a default implementation of `for_each` that loops on `next()`. Conformers may override for performance (SIMD, contiguous storage, etc.).

**Prelude conformances.** The prelude ships `Iterable` conformance for:

- `Array<T>` yielding `Iterator<T>`
- `Set<T>` yielding `Iterator<T>` (order unspecified)
- `Map<K, V>` yielding `Iterator<Entry<K, V>>` — see below
- `Range<T>` yielding `Iterator<T>`
- `String` yielding `Iterator<Char>`

Any user class conforming to `Iterable<T>` accepts the `for` alias uniformly.

#### 3.10.2 Multi-Value Carriers — `Pair`, `Entry`

Corvid has no tuples and no destructuring (§3.7.1). Multi-value carriers are ordinary classes with named fields. The prelude ships two:

```
record Pair<A, B>:
    data:
        first: A
        second: B

record Entry<K, V>:
    data:
        key: K
        value: V
```

Both are `kind: record` — value types, copy-on-assign, structural equality (§4.10.7).

**When to use which.**

- `Pair<A, B>` — general-purpose "two things belonging together" where the semantics is symmetric (coordinates, zip results, before/after snapshots). Fields are `first` / `second` because there is no other meaningful name.
- `Entry<K, V>` — map-shaped "keyed value" where the semantics is asymmetric. Fields are `key` / `value` because those names carry information at the read site. `Map.iter()` yields `Iterator<Entry<K, V>>`.

**Domain-specific carriers are preferred over generic ones.** If a function returns "a position and a length," it should return a `record Slice { data: position: Int, length: Int }`, not a `Pair<Int, Int>`. `Pair` is for cases where no better name exists; when a domain word applies, use the domain word. Same rule applies to `Entry` — a purpose-built record with domain field names beats a generic `Entry`.

**Interaction with `for`.**

```
for entry in dict:
    process(key: entry.key, value: entry.value)

for pair in coordinate_pairs:
    plot(x: pair.first, y: pair.second)

for slice in slices:
    handle(position: slice.position, length: slice.length)
```

All three forms use the same shape: iterate, receive an object per step, access fields by name. No positional destructuring; no `(k, v)` binding syntax; no language-level tuple type.

#### 3.10.3 Conversion Protocols — `As<T>` and `From<T>`

Corvid's canonical conversion pattern. `As<T>` names the projection direction (self → T); `From<T>` names the construction direction (T → Self). Both are generic protocols; the type parameter is the target/source respectively.

```
protocol As<T>:
    accepts as(self) -> T

protocol From<T>:
    class function from(source: T) -> Self
```

**Structural conformance.** A class with `function as(self) -> Bytes:` structurally conforms to `As<Bytes>` without any explicit `conforms:` declaration. Same for `class function from(source: String) -> Self:` and `From<String>`. This matches Corvid's §3.4 structural-conformance discipline.

**Trait-qualified dispatch.** A class may conform to multiple `As<T>` and multiple `From<T>` instantiations simultaneously, declaring one `as`/`from` method per instantiation. Method identity is `(name, protocol-instantiation)`; see §3.4.4 for the dispatch rules.

```
class Config:
    data: raw: Bytes
    methods:
        function as(self) -> Bytes:  return self.raw                # As<Bytes>
        function as(self) -> String: return String.decoded(self.raw) # As<String>
        function as(self) -> Json:   return json_parse(self.raw)    # As<Json>

    class function from(source: String) -> Self:                     # From<String>
        return Self(raw: source.as())
    class function from(source: Json) -> Self:                       # From<Json>
        return Self(raw: json_encode(source).as())
```

**Call-site dispatch resolution.** The compiler picks the `.as()` target using (in priority order):

1. Return-type context: `let b: Bytes = config.as()`.
2. Turbofish: `config.as::<Bytes>()`.
3. Trait qualification: `As<Bytes>.as(config)`.

If none resolves uniquely, compile error with candidate list.

**No auto-derivation between `As` and `From`.** Implementing `From<T>` on Self does not synthesize `As<Target>` on T (and vice-versa). The two directions have different semantics — projection may lose information, construction may fail. Rust's blanket `Into` impl is deliberately not adopted; author both directions explicitly when both are meaningful.

**Specific-name methods remain valid.** `.as_bytes(encoding)`, `.from_json(source)`, `.from_hex(source)` are legitimate when:

- The canonical direction takes parameters (encoding, format).
- Multiple projections would collide on target type but differ on intent (`.as() -> String` vs `.as_debug() -> String` vs `.as_display() -> String`).
- The target name in the method aids readability at call sites where inference is weak.

`.as()` / `.from()` is for the *canonical* projection/construction. Specific-name methods coexist as ordinary methods.

**Default implementations via protocol extension.** `extend class As<T>:` may provide default `as` implementations for specific instantiations. Common pattern for `As<String>` returning a debug-style representation, overridable by conformers.

**Relationship to `Convertible`, `AsBytes`.** `Convertible<To>` **retired in v0.10.0** (§3.11.4). `As<T>` is the sole conversion protocol; numeric narrowing lives outside `As<T>` as three ordinary methods on the source primitive (`wrapping_to`, `saturating_to`, `checked_to`; §3.11.2). The retirement dissolved the parallel-protocol shape that Convertible carried.

There is no separate `AsBytes` protocol. `As<Bytes>` is the canonical spelling. Similarly no `AsString`, `FromBytes`, etc. — the generic form is the only form.

#### 3.10.4 The `Bytes` Type

Corvid's mutable byte-sequence type. `kind: regular` (reference-shaped), consistent with Array/Map/Set (§3.2 collection containers). Corresponds to Foundation's NSData/NSMutableData at the runtime layer; toll-free bridged (§9.3).

**Class shape (illustrative — not exhaustive):**

```
expose class Bytes:
    metadata: kind: regular
    metadata: bridge_to: NSMutableData
    
    # Construction
    class function empty() -> Self
    class function with_capacity(n: Int) -> Self
    class function repeating(byte: UInt8, count: Int) -> Self
    class function from(source: [UInt8]) -> Self          # From<[UInt8]>
    class function from(source: Bytes) -> Self            # From<Bytes> — copy
    
    # Reading
    function length(self) -> Int
    function is_empty(self) -> Bool
        ensures: result == (self.length() == 0)
    function subscript(self, i: Int) -> UInt8
        requires: 0 <= i < self.length()
    function slice(self, range: Range<Int>) -> Bytes
        requires: 0 <= range.start <= range.end <= self.length()
    function concat(self, other: Bytes) -> Bytes
    function starts_with(self, prefix: Bytes) -> Bool
    function ends_with(self, suffix: Bytes) -> Bool
    function contains(self, needle: Bytes) -> Bool
    function first_index(self, of: UInt8) -> Int?         # nil if absent
    
    # Writing
    function append(self, byte: UInt8)
    function append(self, bytes: Bytes)
    function append<T: As<Bytes>>(self, value: T)         # trait-qualified
    function subscript_set(self, i: Int, byte: UInt8)
        requires: 0 <= i < self.length()
    function insert(self, at: Int, byte: UInt8)
        requires: 0 <= at <= self.length()
    function remove(self, at: Int) -> UInt8
        requires: 0 <= at < self.length()
    function clear(self)
        ensures: self.length() == 0
    
    # Iteration
    conforms: Iterable<UInt8>
    function iter(self) -> Iterator<UInt8>
    
    # Conversion
    function as(self) -> Bytes                            # As<Bytes>: identity (copy)
        ensures: result.length() == self.length()
    function decoded_as(self, encoding: Encoding) -> DecodeOutcome<String>
```

**Array-literal conformance.** `let b: Bytes = [0x48, 0x65, 0x6c, 0x6c, 0x6f]` — Bytes conforms to `ArrayLiteral<UInt8>` (§3.10 collection literal conformance).

**Value hygiene.** `.as() -> Bytes` returns a fresh copy — the caller can mutate without affecting the source. When zero-copy is needed (parser hot paths, sub-buffer views into a larger owner), use `.slice(range)` which may share storage per implementation choice (immutable slice semantics).

**No separate builder type.** Bytes is the builder — construct, mutate, hand off. Consistent with Array/Map/Set: no ArrayBuilder, no MapBuilder, no BytesBuilder. If a caller needs immutability guarantees, they wrap the Bytes in a record or use §8.4.1 reader/writer classification.

**Raw-pointer access is `unsafe:`.** `bytes.raw_pointer()` for C interop requires the caller to enter an `unsafe:` block per §6.7. In-place byte mutation via `bytes[i] = x` is safe — Bytes participates in normal contract discipline.

**Reader/writer classification.** All read methods (length, subscript, slice, iter, etc.) are readers per §8.4.1; write methods (append, insert, clear, subscript_set) are writers. Concurrent access is governed by the RWLock-style dispatch already in place for regular classes.

#### 3.10.5 The `String` Type

Corvid's text type. `kind: regular`, corresponds to NSString at the runtime layer; toll-free bridged.

**Grapheme-native.** All text semantics are grapheme-cluster-based. `Char` (§3.10.6) is a grapheme cluster — potentially multiple Unicode code points appearing as a single user-visible character (emoji sequences, combining marks, family emoji, flag sequences).

**No integer indexing.** `s[i]` where `s: String` is a compile error with a diagnostic pointing to `StringPosition` (§3.10.7). Rationale: integer indexing over strings admits three incompatible semantics (bytes / code units / graphemes) and misleads even careful authors. Positions are opaque tokens; the invariant that "position 5 always means the character-after-4" holds only under a specific granularity choice, and Corvid refuses to pick one silently.

**No peer views (`.utf16`, `.utf8`, `.bytes` are not properties on String).** Byte-level access is via the universal `.as() -> Bytes` (UTF-8 default) or `.as_bytes(encoding: Encoding) -> Bytes` for other encodings. This unifies byte access across every type via the same convention.

**Class shape (illustrative):**

```
expose class String:
    metadata: kind: regular
    metadata: bridge_to: NSString
    
    # Construction
    class function empty() -> Self
    class function from(source: String) -> Self           # From<String>: copy
    class function from(source: Char) -> Self             # From<Char>: singleton
    class function decoded(source: Bytes) -> DecodeOutcome<Self>
                                                          # UTF-8 default
    class function decoded(source: Bytes, encoding: Encoding)
        -> DecodeOutcome<Self>
    
    # Reading
    function grapheme_count(self) -> Int
    function is_empty(self) -> Bool
    function first_grapheme(self) -> Char?
    function last_grapheme(self) -> Char?
    function contains(self, substring: String) -> Bool
    function starts_with(self, prefix: String) -> Bool
    function ends_with(self, suffix: String) -> Bool
    
    # Positional access
    function start_position(self) -> StringPosition
    function end_position(self) -> StringPosition
    function position(self, after: StringPosition) -> StringPosition?
    function position(self, before: StringPosition) -> StringPosition?
    function position(self, at_distance: Int, from: StringPosition)
        -> StringPosition?
    function first_position(self, of: String) -> StringPosition?
    function last_position(self, of: String) -> StringPosition?
    function grapheme_at(self, position: StringPosition) -> Char
        requires: position != self.end_position()
    function substring(self, from: StringPosition, to: StringPosition) -> String
        requires: from <= to
    
    # Pattern-based access (Pattern subsystem — §3.10.9)
    function first_position(self, matching: Pattern) -> StringPosition?
    function substring(self, matching: Pattern) -> String?
    function all_matches(self, matching: Pattern) -> Iterator<Match>
    function replace_all(self, pattern: Pattern, with: String) -> String
    
    # Iteration
    conforms: Iterable<Char>
    function iter(self) -> Iterator<Char>
    function graphemes(self) -> Iterator<Char>            # alias for iter
    function lines(self) -> Iterator<String>
    function split(self, pattern: Pattern) -> Iterator<String>
                                                          # split on a Pattern (Literal for the common case)
    
    # Conversion
    function as(self) -> Bytes                            # As<Bytes>: UTF-8
    function as(self) -> String                           # As<String>: identity
    function as_bytes(self, encoding: Encoding) -> Bytes
```

**Comparison.** `<`, `<=`, `>`, `>=` compare lexicographically by grapheme sequence. `==` compares canonical equivalence (NFC-normalized grapheme sequences equal). Users who need code-point-exact comparison call `.as_bytes(.utf8) == other.as_bytes(.utf8)`.

**String literals.** `"hello"` produces a `String`. String interpolation `"x = \(value)"` calls `value.as() -> String` — every interpolated type must satisfy `As<String>`.

**Pattern support.** `Pattern` is a separate class (deferred to future spec) supporting glob, regex, and structural patterns. `substring(matching:)` returns the first match; `.split(pattern:)` and `.replace(pattern:, with:)` are pattern-based operations.

#### 3.10.6 The `Char` Type

Represents a single grapheme cluster — one user-visible character, potentially spanning multiple Unicode code points.

**Class shape:**

```
expose class Char:
    metadata: kind: record
    
    # Predicates
    function is_whitespace(self) -> Bool
    function is_newline(self) -> Bool
    function is_digit(self) -> Bool
    function is_alphabetic(self) -> Bool
    function is_alphanumeric(self) -> Bool
    function is_ascii(self) -> Bool
    function is_uppercase(self) -> Bool
    function is_lowercase(self) -> Bool
    
    # Transformations
    function to_uppercase(self) -> Char
    function to_lowercase(self) -> Char
    
    # Conversion
    function as(self) -> Bytes                            # As<Bytes>: UTF-8
    function as(self) -> String                           # As<String>: singleton
    function ascii_value(self) -> UInt8?                  # nil if not ASCII
    function code_points(self) -> [UInt32]                # scalar values
    
    # Comparable/Equatable/Hashable — record defaults per §3.2
```

**Literal syntax.** `'a'` produces a `Char`. Escape sequences per §2.7 (`'\n'`, `'\u{1F600}'`, etc.). Multi-code-point graphemes require string-of-length-1 pattern: `let flag: Char = "🇸🇬".first_grapheme() ?? nil_char`.

#### 3.10.7 The `StringPosition` Type

Opaque position token into a specific `String` instance.

**Class shape:**

```
expose class StringPosition:
    metadata: kind: record
    
    # Comparison — total order within a single source String.
    function op_less_than(self, other: StringPosition) -> Bool
        requires: self.source_id == other.source_id
    function op_equal(self, other: StringPosition) -> Bool
        requires: self.source_id == other.source_id
    # No arithmetic — cannot do `pos + 5` directly.
    # Advance via String.position(after:) / position(at_distance:from:).
```

**Bound to source String.** A `StringPosition` obtained from `s1: String` is not comparable to a `StringPosition` from `s2: String` — comparison across different sources is a runtime precondition failure per §3.6. This prevents "position from string A applied to string B" bugs.

**Not serializable across process boundaries.** StringPositions are runtime handles into a specific String's grapheme table; they don't roundtrip through disk or IPC. For persistent references, use grapheme-count offsets and reconstitute via `s.position(at_distance: n, from: s.start_position())`.

**No arithmetic.** `pos + 5` is a compile error. The path is `s.position(at_distance: 5, from: pos)`, which requires the String to be present at the call site and returns `Optional<StringPosition>` (nil if 5 graphemes beyond exceeds `s.end_position()`). This makes bounds-checking part of every navigation step.

#### 3.10.8 Supporting Enums — `Encoding`, `ByteOrder`, `DecodeOutcome<T>`

```
enum Encoding:
    case utf8
    case utf16_le
    case utf16_be
    case ascii
    case latin1
    # Extend via prelude aliases per §4.16 for uncommon encodings.

enum ByteOrder:
    case little
    case big
    case network      # alias for .big — network byte order convention
    case host         # aliased at compile time to the platform's native order

enum DecodeOutcome<T>:
    case decoded(T)
    case invalid(offset: Int, reason: String)
```

**`DecodeOutcome<T>` usage** — per v0.8.5 failure-handling discipline, decoding-failure paths are switched, not thrown:

```
switch Bytes.from([0xff, 0xfe, 0x48, 0x00]).decoded_as(.utf16_le):
    case .decoded(let s):
        process(s)
    case .invalid(let offset, let reason):
        log("decode failed at byte {offset}: {reason}")
        return .parse_error
```

**Serialization substrate.** Any type conforming to `As<Bytes>` participates in Corvid's universal serialization protocol. Format-specific overlays (`As<Json>`, `As<MsgPack>`, `As<Yaml>`) become ordinary trait-qualified conformances added by user code or third-party packages via `extend class Foo:` (§3.3.2) — no special language support required; third-party format packages ship a target type and users write `function as(self) -> ThatType:` methods, exactly like user code. Method name is always `.as()` differentiated by return type per §3.10.3 trait-qualified dispatch; never `as_bytes()` / `as_json()`. No reflection-based auto-serialization (the trap Java's `Serializable` fell into); regular-class authors write `.as() -> Bytes` explicitly.

**Value types have canonical bytes as a base-class property.** Per §3.3 and §3.4.6, records, enums, and primitives carry `.as() -> Bytes` and `class function from(source: Bytes) -> DecodeOutcome<Self>` as base methods — the compiler synthesizes them from field/case layout per §3.10.10's canonical wire format, the same way it synthesizes structural equality. Structural conformance to `As<Bytes>` and `From<Bytes>` (§3.4) then succeeds because the required methods exist; no separate "auto-conformance" mechanism, no compiler awareness of specific target-type names. `kind: regular` classes get no synthesis — mutable state, potential cycles, and identity require explicit user-written serialization. User-written `.as() -> Bytes` on a value type shadows the synthesized one per §3.3.2 (own definitions win over inherited defaults). Format-specific representations (JSON, MsgPack) are user-written per record because they have per-record author choices (field naming, null handling, number precision) with no defensible language-level default.

#### 3.10.9 The `Pattern` Subsystem — Regex, Glob, Literal

String methods that take a `matching:` or `pattern:` parameter (§3.10.5) dispatch through the `Pattern` protocol. Concrete conformers ship in prelude for regex, glob, and literal-string matching. Users add more (PEG, fuzzy, Levenshtein, etc.) via `extend` + structural conformance — no changes to `String` or the prelude required.

**Design commitment.** Pattern is one protocol, three concrete conformers. Pattern selection is ordinary polymorphism on the value passed to a String method — not trait-qualified dispatch (§3.10.3), not sibling protocols. The differentiator between conformers is genuine structural difference (compiled regex NFA vs raw glob string vs verbatim needle), not semantics-for-the-same-signature. Passes the mechanism-invention trap check.

##### 3.10.9.1 The `Pattern` Protocol

```
protocol Pattern:
    accepts first_match(self, source: String) -> Match?
    accepts all_matches(self, source: String) -> Iterator<Match>
```

Two methods, structural conformance. Any class with these two methods is a Pattern; no `conforms: Pattern` declaration required (§3.4).

**Iteration contract.** `all_matches` returns an Iterator per §3.10.1 — non-overlapping matches in source-order. Overlapping-match semantics (looking for repeated overlaps) is outside Pattern's contract; a caller who needs it constructs a custom iterator.

##### 3.10.9.2 `Match`, `MatchRange`, `MatchGroup` Records

```
record Match:
    data: source: String                       # the String this Match came from
    data: range: MatchRange                    # extent of the match
    data: captures: Map<String, MatchGroup>    # named captures only
    methods:
        function substring(self) -> String:
            return self.source.substring(from: self.range.start, to: self.range.end)
        function group(self, name: String) -> MatchGroup?:
            return self.captures.get(name)

record MatchRange:
    data: start: StringPosition
    data: end: StringPosition
        # start <= end; both are positions in Match.source

record MatchGroup:
    data: range: MatchRange
    data: value: String
```

**Named-only captures.** The `captures:` map is keyed by `String` — the name declared in the pattern source. Positional captures are not exposed via numeric index. Consistent with §3.2 named-only record fields, §3.7.1 named-only enum associated values, §3.10.2 named-only multi-value carriers. Corvid does not offer positional access anywhere; Pattern doesn't invent an exception.

**Backreference to source.** `Match.source` is the String the match was found in. `Match.substring()` slices the source using the match's positions (§3.10.7 StringPosition semantics — positions are source-bound). This means passing a Match around does not lose the original text.

##### 3.10.9.3 Concrete Conformers — `Literal`, `Glob`, `Regex`

All three are prelude classes with `kind: regular`.

**`Literal` — exact-string match, no interpretation.**

```
expose class Literal:
    metadata: kind: regular
    data: needle: String
    methods:
        function first_match(self, source: String) -> Match?
        function all_matches(self, source: String) -> Iterator<Match>
```

Matches the needle character-for-character (grapheme-cluster-for-grapheme-cluster per §3.10.5). No escape processing, no interpretation. Constructor: `Literal(",")`, `Literal("http://")`. No captures.

**`Glob` — filename-style patterns (`*`, `?`, `[abc]`).**

```
expose class Glob:
    metadata: kind: regular
    data: source: String
    methods:
        function first_match(self, source: String) -> Match?
        function all_matches(self, source: String) -> Iterator<Match>
```

Compiled at construction time. Metacharacters:
- `*` — zero-or-more of any grapheme (except `/`, if configured for path glob).
- `?` — exactly one grapheme.
- `[abc]` — grapheme class; `[a-z]` for range; `[!abc]` for negation.
- `\` — escape the next metacharacter.

Constructor: `Glob("*.txt")`, `Glob("[Ll]og_[0-9]?.txt")`. No captures.

**`Regex` — regular expressions with configurable options.**

```
expose class Regex:
    metadata: kind: regular
    data: source: String
    data: options: RegexOptions
    methods:
        function first_match(self, source: String) -> Match?
        function all_matches(self, source: String) -> Iterator<Match>
```

Compiled at construction time. Corvid's regex flavor is PCRE2-adjacent — features specified per §3.10.9.4 below. Captures are always named (§3.10.9.6).

**Compilation caching.** All three constructor variants (Literal, Glob, Regex) cache their compiled representation when the source string is a compile-time constant per §4.16.10 value-alias discipline. `regex"^\d+$"` compiles once at parse time and is shared across all use sites; `Regex(user_input)` compiles at runtime and is not cached. No language feature — this is a constructor optimization on classes that hold compile-time-constant state.

##### 3.10.9.4 `RegexOptions`

```
record RegexOptions:
    data: case_insensitive: Bool = false
    data: multiline: Bool = false                  # `^` / `$` match line boundaries, not source boundaries
    data: dot_matches_newline: Bool = false        # `.` matches newlines
    data: extended: Bool = false                   # allow whitespace + comments in pattern source
```

Default constructor `Regex(source)` uses `RegexOptions()` (all false). Options-taking constructor: `Regex(source, options: RegexOptions(case_insensitive: true))`.

**Regex feature set (Corvid-specific decisions).**

- **Named captures required, unnamed rejected** (§3.10.9.6).
- **Backreferences to named captures** — `\k<name>`.
- **Character classes** — `\d`, `\w`, `\s`, `[abc]`, `[^abc]`, `[[:alpha:]]` POSIX classes.
- **Anchors** — `^`, `$`, `\b`, `\A`, `\z`.
- **Quantifiers** — `*`, `+`, `?`, `{n}`, `{n,}`, `{n,m}`, with `?` suffix for non-greedy.
- **Lookaround** — `(?=...)`, `(?!...)`, `(?<=...)`, `(?<!...)`.
- **No embedded flags** in-pattern (`(?i)...`) — flag setting is via `RegexOptions`, not pattern source. Enforces one location per flag.
- **Unicode-aware by default** — grapheme-cluster boundaries, `\d` matches Unicode digits, `\w` matches Unicode word characters.

##### 3.10.9.5 Prelude Pattern Literals — `regex"..."`, `glob"..."`, `literal"..."`

Prelude ships three prefix-literal aliases per §4.16.17 (raw-content prefix-literal alias shape):

```
# In prelude:
alias regex"<raw content>"   = Regex(<raw content>)
alias glob"<raw content>"    = Glob(<raw content>)
alias literal"<raw content>" = Literal(<raw content>)
```

Usage:

```
let digits = regex"^\d+"                                    # parses as Regex("^\d+")
let logs = glob"*.log"                                       # parses as Glob("*.log")
let comma = literal","                                       # parses as Literal(",")

s.substring(matching: regex"^\s*//.*$")
s.split(pattern: literal(","))                              # or `literal","` — both work
s.replace_all(pattern: regex"\s+", with: " ")
```

**Raw content.** The `<raw content>` between the double quotes is passed unmodified to the constructor — no escape processing, so `\d` reaches the regex engine as two characters `\` + `d`, not as an escape-sequence attempt. This is what §4.16.17 (below in §4.16) formalizes.

**User-defined pattern literals.** Users define their own with one prelude-alias-style declaration in their module:

```
# In some user module:
alias @peg"<raw content>" = PegParser(<raw content>)
alias @fuzzy"<raw content>" = FuzzyPattern(<raw content>)

# Use:
let parser = peg"expr <- term ('+' term)*"
s.first_match(matching: fuzzy"apple")
```

`@`-marker discipline per §4.16.16 keeps user aliases distinguishable from prelude aliases.

##### 3.10.9.6 Named Captures Only

Regex source containing an unnamed group `(...)` is a compile error at pattern-construction time — both for compile-time-constant regex literals and runtime `Regex(source)` calls (checked when the pattern compiles). Users write `(?<name>...)` for every capture group:

```
# Compile error:
regex"(\d+)"                                                 # error: unnamed capture group

# Correct:
regex"(?<year>\d+)"                                          # named
```

Non-capturing groups `(?:...)` remain legal and unrestricted — they group without capturing, so no name is needed.

**Diagnostic:**

```
error[E0503]: unnamed capture group in Regex source
  --> src/parse.cor:12:23
   |
12 |     let m = regex"^(\d+)-(\d+)$"
   |                    ^^^^^ ^^^^^  unnamed groups
   |
help: use named capture groups
   |
12 |     let m = regex"^(?<major>\d+)-(?<minor>\d+)$"
   |                    +++++++++     +++++++++
help: or make groups non-capturing if names aren't needed
   |
12 |     let m = regex"^(?:\d+)-(?:\d+)$"
   |                    +++     +++
```

**Rationale.** Positional captures require positional access to results (`match.group(1)`, `match.group(2)`), which contradicts §3.2 named-only discipline. Making capture names mandatory pushes the name into the pattern string — the reader sees `(?<year>\d+)` and knows what the capture represents without cross-referencing consumer code. This is the same discipline that put named-only associated values on enum cases (v0.8.1) and named-only fields on records (§3.2).

**Cost.** Regex sources translated from Perl/Python/Ruby/Java need mechanical `(...)` → `(?<name>...)` conversion. Small friction; consistent with every other named-only surface in Corvid.

#### 3.10.10 Canonical Value-Type Encoding

Wire-format specification for the byte methods that §3.4.6 documents as value-type base-class properties. Every value type's compiler-synthesized `.as() -> Bytes` and `class function from(source: Bytes)` produces / consumes bytes per this format. Deterministic across compilations, across architectures, across runtime instances. Users overriding the synthesis are free to depart from this format; the guarantees below apply only to synthesized methods.

**Endianness — network-order (big-endian) for multi-byte primitives.** Silent breakage across architectures is a footgun; the encoding is one canonical form the compiler can synthesize without needing per-record author choice. Performance-critical same-host cases opt out by writing their own `.as() -> Bytes` on the record and using `.as_bytes(order: ByteOrder.host)` on individual field encodings (§3.11.1).

**Per-type encoding:**

| Type | Encoding | Size |
|---|---|---|
| `Bool` | `0x00` for `false`, `0x01` for `true` | 1 byte |
| `UInt8` | raw byte | 1 byte |
| `Int8` | raw byte (two's-complement) | 1 byte |
| `UInt16` | big-endian 2 bytes | 2 bytes |
| `Int16` | big-endian 2 bytes (two's-complement) | 2 bytes |
| `UInt32` | big-endian 4 bytes | 4 bytes |
| `Int32` | big-endian 4 bytes (two's-complement) | 4 bytes |
| `UInt64` | big-endian 8 bytes | 8 bytes |
| `Int64` | big-endian 8 bytes (two's-complement) | 8 bytes |
| `Int` / `UInt` | platform-fixed width (64-bit on 64-bit targets), big-endian | 4 or 8 bytes |
| `Float` | IEEE 754 single-precision, big-endian | 4 bytes |
| `Double` | IEEE 754 double-precision, big-endian | 8 bytes |
| `Char` | grapheme cluster's UTF-8 byte sequence, length-prefixed by `UInt8` | 1 + N bytes (N ≤ 255) |
| `String` | `length: UInt32 (network-order)` `||` UTF-8 bytes | 4 + N bytes |
| `Bytes` (unrefined) | `length: UInt32 (network-order)` `||` raw bytes | 4 + N bytes |
| `Bytes where length == N` (fixed) | raw N bytes, no length prefix | N bytes |
| `Bytes where length <= N` (bounded) | length-prefixed by smallest UInt type fitting N `||` raw bytes | 1/2/4 + N bytes |
| `T?` (Optional) | presence byte (0x00 = nil, 0x01 = present) `||` `T` encoding if present | 1 or 1+sizeof(T) bytes |
| `[T]` (Array) | `length: UInt32` `||` element encodings concatenated | 4 + N × sizeof(T) bytes |
| `{K: V}` (Map) | `length: UInt32` `||` (`K` encoding `||` `V` encoding) × length | 4 + N × (sizeof(K) + sizeof(V)) bytes |
| `{T}` (Set) | `length: UInt32` `||` element encodings concatenated (source-order iteration) | 4 + N × sizeof(T) bytes |
| Record | fields concatenated in declaration order, each per its type's encoding | Σ field sizes |
| Enum (≤256 cases) | discriminator `UInt8` `||` associated-value encodings recursively | 1 + associated-data size |
| Enum (257–65536 cases) | discriminator `UInt16 (network-order)` `||` associated-value encodings | 2 + associated-data size |
| Enum (>65536 cases) | discriminator `UInt32 (network-order)` `||` associated-value encodings | 4 + associated-data size |

**Field declaration order determines record encoding.** Reordering `data:` fields in a record's source is a **wire-incompatible change** — old serialized bytes cannot be decoded by the new definition. Adding a field is also wire-incompatible (decoder expects the old byte count; new field appears as extra bytes or truncated data). The compiler flags wire-incompatible changes when the record is used in a context declaring wire stability (deferred stdlib section, v0.12+).

**Case declaration order determines enum discriminator.** Case at index 0 in source = `0x00` discriminator, case at index 1 = `0x01`, etc. Reordering cases is wire-incompatible. Adding cases is compatible if the new case is appended (existing discriminators remain valid); inserting mid-list shifts subsequent discriminators and is wire-incompatible.

**Length prefix width — `UInt32` (4 bytes) — accommodates up to 4 GiB per collection / string / bytes.** Sufficient for on-disk caching, IPC, snapshot use cases. Users needing larger require explicit chunking or a user-authored encoding.

**Refinement-driven length prefix specialization.** When a `Bytes` field carries a length refinement, wire-format synthesis uses that refinement to eliminate or narrow the runtime length prefix — the refinement already carries the length information; writing it into the wire redundantly is wasted bytes.

- `Bytes where length == N` — length is compile-time-known; synthesized encoding emits raw N bytes with no prefix. Enables primitives: `record UInt32: data: bytes: Bytes where length == 4` synthesizes `.as() -> Bytes` that produces 4 raw big-endian bytes with no header.
- `Bytes where length <= N` — length is runtime-variable but bounded; synthesized encoding uses the smallest unsigned integer type that fits `N` as the prefix. `N ≤ 255` → `UInt8` prefix (1 + up-to-N bytes); `N ≤ 65535` → `UInt16` prefix (2 + up-to-N bytes); `N ≤ 2³²-1` → `UInt32` prefix. Enables `record Char: data: bytes: Bytes where length <= 255` synthesizing to `UInt8-prefix || raw bytes` (matches Char's canonical spec above).
- Unrefined `Bytes` (no length constraint) — uses `UInt32` prefix per the default table entry.

This is not primitive-special-casing; it's the refinement type carrying its usual meaning through to wire-format synthesis. User records with refined-length Bytes fields (`data: mac: Bytes where length == 6`, `data: guid: Bytes where length == 16`) get the same specialization — no length prefix at 6 or 16 bytes. Symmetrical: any type-level length constraint drops or narrows the runtime prefix.

**Nested type recursion.** Records/enums with fields/cases holding other records/enums recurse per this table. Termination: primitives at the leaves. `kind: regular` classes as fields require user-written `.as() -> Bytes`; format is under user control (may or may not match this canonical spec).

**Decoding failure modes.** `class function from(source: Bytes) -> DecodeOutcome<Self>` returns `.invalid(offset: Int, reason: String)` on:
- **Short input** — fewer bytes than the field/case demands. `reason: "expected N bytes, source has M"`.
- **Invalid enum discriminator** — discriminator value ≥ case count. `reason: "unknown discriminator D"`.
- **Invalid UTF-8** in a `String` or `Char` field — `reason: "invalid UTF-8 at offset X"`.
- **Invalid `Bool` byte** — value neither `0x00` nor `0x01`. `reason: "invalid Bool byte 0xNN"`.
- **Optional presence byte** neither `0x00` nor `0x01`. `reason: "invalid Optional presence byte 0xNN"`.

Offset in `.invalid` is byte-position in the original `source`, not offset into a nested field.

**Guaranteed properties.**

1. **Deterministic.** Same value produces same bytes across compilations, runs, architectures.
2. **Roundtripping.** `x.as().from() == .converted(x)` for every value `x` of a value type. (Modulo user-written overrides where the author chose a different encoding.)
3. **Length-recoverable.** Decoder can determine total consumed bytes from the source, allowing framing without external length metadata for record/enum-typed messages of known type.
4. **Zero-copy where practical.** `Bytes` and `String` fields' payload bytes are shared with the source `Bytes` when the source is retained and mutable positions permit (implementation detail).

**Non-goals.**

- **Cross-language interop.** The canonical encoding is Corvid-specific. Cross-language contexts use format-specific target types (`Json`, `MsgPack`, `CBOR`, `Protobuf`), which are user-authored per record via `function as(self) -> Json:` etc.
- **Schema evolution.** Field addition, removal, reordering all break wire compatibility. Users needing evolvable formats use tagged formats (msgpack, protobuf) with explicit versioning.
- **Human debuggability.** Bytes are opaque. For debug representation, use `.as() -> String` (author-defined per record) or a hex-dump helper.
- **Compression.** No compression at the encoding level. Users compress the resulting `Bytes` externally if needed.

**Rationale for the choice set.**

- **Big-endian over little-endian** — network-order is the traditional interchange convention; makes the format immediately usable for any wire context without conversion overhead when it matters. Same-host little-endian users pay a byte-swap cost on multi-byte primitives, which is a single CPU instruction on modern hardware — negligible for the common case.
- **UInt32 length prefix over VarInt** — fixed width means decoders can seek directly; VarInt saves bytes on small collections but complicates the decoder. Corvid picks decoder-simplicity over one-byte-per-small-collection.
- **Discriminator width scales with case count** — 256 cases fits UInt8, avoids the 4-byte-per-enum tax the naive UInt32-always design would impose.
- **Optional as presence-byte-prefix, not sentinel value** — sentinel-value encoding (e.g., all-zeros = nil) fails for types where all-zeros is a valid value (`Int32(0)`, `Float(0.0)`). Presence byte is unambiguous.

### 3.11 Numeric Conversion and Bounded Types

Corvid numeric primitives (`Int`, `Int8`..`Int64`, `UInt`..`UInt64`, `Float`, `Double`) participate in the universal conversion protocol `As<T>` (§3.10.3) for lossless conversions, plus a small family of narrowing methods for the three semantics that arise when a source value may not fit its target.

#### 3.11.1 Lossless Numeric Conversion via `As<T>`

Prelude ships `As<T>` conformance for every **widening** numeric pair — pairs where the source range is a subset of the target range:

```
UInt8(100).as() -> Int32        # widening: safe, lossless
Int32(100).as() -> Int64        # widening: safe, lossless
Int16(50).as() -> Float         # widening: safe, lossless
UInt8(100).as() -> UInt16       # widening: safe, lossless
```

Prelude does **not** ship `As<T>` for narrowing pairs. `Int32(300).as() -> UInt8` is a compile error — no `As<UInt8>` conformance exists on `Int32`. Diagnostic:

```
error[E0501]: no lossless conversion from `Int32` to `UInt8`
  --> src/foo.cor:12:9
   |
12 |         let byte: UInt8 = value.as()
   |                           ^^^^^^^^^^ narrowing is not lossless
   |
help: pick a narrowing semantics explicitly
   |
12 |         let byte: UInt8 = value.wrapping_to::<UInt8>()
12 |         let byte: UInt8 = value.saturating_to::<UInt8>()
12 |         let outcome = value.checked_to::<UInt8>()
```

**Why no `As<T>` on narrowing.** `As<T>` promises canonical, lossless projection. A narrowing operation on an out-of-range value is inherently lossy — three legitimate answers exist (wrap, saturate, fail) and no one of them is "the" canonical projection. Making `.as()` silently pick one would be a footgun; refusing it entirely and requiring the author to spell the semantics out is per §1.2 (author intent, not authority).

#### 3.11.2 Narrowing Methods

For every numeric primitive, prelude ships three methods parameterized by the narrowing target type. These are **ordinary methods, not a protocol family** — the differentiator between them is semantics, not target type, and semantics is a caller-choice-per-call operation that belongs at the call site as a method name.

```
function wrapping_to<To>(self) -> To
    # Two's-complement modular arithmetic. Matches §3.9 arithmetic overflow discipline.
function saturating_to<To>(self) -> To
    # Clamp to [To.min, To.max]. For signal-processing and image code.
function checked_to<To>(self) -> ConvertOutcome<To>
    # Domain enum. Caller switches on .converted / .overflow.
```

**Usage:**

```
Int32(300).wrapping_to::<UInt8>()      # -> UInt8(44)    — 300 mod 256
Int32(300).saturating_to::<UInt8>()    # -> UInt8(255)   — clamp to UInt8.max
Int32(300).checked_to::<UInt8>()       # -> ConvertOutcome.overflow(source: "Int32(300)", target: "UInt8")
Int32(100).checked_to::<UInt8>()       # -> ConvertOutcome.converted(UInt8(100))
```

The turbofish `::<UInt8>` is required — the return type carries the target and there is no receiver-side context to infer from. Sugar aliases in call-site-annotated contexts:

```
let byte: UInt8 = big.wrapping_to()    # target inferred from binding annotation
```

**Float-integer narrowing** truncates toward zero (C99 semantics) under `wrapping_to`. `checked_to` returns `.overflow` if the truncated value doesn't fit or if the source is NaN / infinity.

**User types can define narrowing methods.** A wrapper type (e.g., `Percentage` around `Float`) may ship its own `wrapping_to`, `saturating_to`, `checked_to` methods with the same signatures. These are conventional method names, not a protocol contract — the compiler does not enforce that they exist together, and does not enforce that they behave per the wrap / saturate / checked spec. Author responsibility. Rationale: making these a protocol would push us back toward `WrappingAs<T>` / `SaturatingAs<T>` / `CheckedAs<T>` sibling protocols, which the design rejects (§3.10.3 trait-qualified dispatch differentiates by target type, and the three narrowing semantics share a target type — they are not a protocol-family problem).

#### 3.11.3 The `ConvertOutcome<T>` Enum

```
enum ConvertOutcome<T>:
    case converted(T)
    case overflow(source: String, target: String)
```

Ships in the prelude. Caller switches on the outcome per v0.8.5 no-throws discipline:

```
switch big.checked_to::<UInt8>():
    case .converted(let byte):
        buffer.append(byte)
    case .overflow(let source, let target):
        log("truncation refused: {source} does not fit in {target}")
        return .overflow_error
```

**Not a `Result<T, E>`.** Per v0.8.5, Corvid does not ship a generic `Result<T, E>` in the prelude. `ConvertOutcome<T>` is a domain enum with a fixed error case, consistent with the pattern used everywhere else (`DecodeOutcome<T>` §3.10.8, `SendOutcome`, etc.).

#### 3.11.4 Byte Methods on Primitives

Primitives are `kind: record` shipped in prelude with a `data: bytes: Bytes where length == N` field (§3.1); byte methods synthesize from that field layout via the same pathway as user records (§3.4.6). Under §3.10.10's refinement-driven length-prefix specialization, a `Bytes` field with `where length == N` synthesizes to raw N bytes with no runtime prefix — so `record UInt8: data: bytes: Bytes where length == 1` synthesizes to `.as() -> Bytes` producing 1 raw byte (matches §3.10.10's UInt8 spec); `record UInt32: data: bytes: Bytes where length == 4` synthesizes to 4 raw big-endian bytes (matches UInt32 spec); and so on for every fixed-length primitive. Structural conformance to `As<Bytes>` and `From<Bytes>` (§3.4) follows because the synthesized methods exist on the class.

**No per-primitive prelude implementation.** Prior to the (A) reframe, prelude shipped a hand-written `extend class UInt8: methods: function as() -> Bytes: ...` block per primitive — under the "primitives have no field layout" claim in early v0.11 drafts. That claim was false (primitives DO have field layout, one field: `bytes: Bytes where length == N`), and the reframe removed the per-primitive prelude blocks: the same synthesis pathway that gives a user's `record Point: data: x: Float, y: Float` its `.as() -> Bytes` gives `record UInt32` its `.as() -> Bytes`. One pathway; no hand-writes; no framing-fudge about "prelude extend blocks that are actually language-provided."

**Explicit-endian access.** Alongside the synthesized `.as() -> Bytes` (fixed to network-order per §3.10.10), each multi-byte primitive gets an ordinary `.as_bytes(order: ByteOrder) -> Bytes` method for callers who need explicit control:

```
# Shipped in prelude on record UInt32 (analogous for other multi-byte primitives):
record UInt32:
    data: bytes: Bytes where bytes.length == 4
    methods:
        function as_bytes(self, order: ByteOrder) -> Bytes:
            switch order:
                case .little:
                    let raw = self.bytes                # big-endian storage
                    return Bytes.from([raw[3], raw[2], raw[1], raw[0]])
                case .big:      return self.bytes                   # canonical
                case .network:  return self.bytes                   # alias for .big
                case .host:     return self.as_bytes(order: platform_native_order)
        # ... arithmetic/comparison intrinsics ...
```

`.as_bytes(order:)` is a specific-name method, NOT participating in `As<Bytes>` trait-qualified dispatch (only `.as() -> Bytes` does that). Users who need host-order encoding for same-host performance write their record's `.as() -> Bytes` explicitly using `.as_bytes(order: .host)` on each field, shadowing the synthesized one.

**Roundtrip guarantee.** For every primitive `T` and every value `v: T`:

```
T.from(source: v.as()) == .converted(v)
```

Verified by prelude test suite; users may rely on this in contracts.

**Interaction with `Convertible` migration.** The `wrapping_to::<T>()` / `saturating_to::<T>()` / `checked_to::<T>()` narrowing methods (§3.11.2) are ordinary methods — they do not participate in `As<T>` dispatch. Narrowing is orthogonal to byte encoding; a value can be narrowed (`Int32(300).saturating_to::<UInt8>() → 255`) and then encoded (`.as() → [0xff]`) or encoded and then narrowed. Callers write the sequence they need.

#### 3.11.5 Retirement Note — `Convertible<To>`

Prior versions of this section (v0.7.1 through v0.9.1) shipped `Convertible<To>` — a single protocol bundling the three narrowing semantics as three methods (`.into`, `.checked_into`, `.saturating_into`), plus a redundant `target_type: To.Type` parameter. **Retired in v0.10.0.** Two reasons:

1. **Structural duplication with `As<T>` (§3.10.3).** Both were parameterized generic conversion protocols with trait-qualified dispatch on target type. Keeping both meant two shapes for what was structurally one thing.
2. **`Result<T, OverflowError>` in `.checked_into` was inconsistent with v0.8.5** (retired `Result` from the prelude in favor of domain enums). The bug was never caught until this session's audit.

The retirement replaces `Convertible<To>` with: `As<T>` (§3.10.3) for the lossless case, three ordinary methods for narrowing (§3.11.2), and `ConvertOutcome<T>` (§3.11.3) for the checked-narrow outcome. Structural payoff: one conversion protocol in the language, no parallel mechanisms.

**Migration:**

```
# Old (v0.9.1 and earlier):
Int32(300).into(UInt8)                # -> UInt8(44)
Int32(300).saturating_into(UInt8)     # -> UInt8(255)
Int32(300).checked_into(UInt8)        # -> Result<UInt8, OverflowError>

# New (v0.10.0):
Int32(300).wrapping_to::<UInt8>()     # -> UInt8(44)
Int32(300).saturating_to::<UInt8>()   # -> UInt8(255)
Int32(300).checked_to::<UInt8>()      # -> ConvertOutcome<UInt8>
```

Mechanical migration. Same three semantics; different method names + explicit target-type turbofish; no `Result` wrapper.

#### 3.11.6 The `Bounded` Protocol

```
protocol Bounded:
    class function max() -> Self
    class function min() -> Self
```

Prelude ships conformance for all numeric primitive types:

```
UInt8.max()     # 255
UInt16.max()    # 65535
Int32.min()     # -2147483648
Int.max()       # platform-dependent — Int64.max() on 64-bit targets
```

Sugar accessors `T.max` and `T.min` (without parens) are prelude value aliases that expand to the class-function calls.

**FloatingPoint protocol** extends Bounded with float-specific extrema:

```
protocol FloatingPoint: Bounded:
    class function infinity() -> Self
    class function nan() -> Self
    class function epsilon() -> Self       # smallest x such that 1 + x != 1
```

Sugar accessors: `Double.infinity`, `Double.nan`, `Double.epsilon`.

**Notes on use:**
- `Bounded` is generic-code friendly — `function clamp<T: Bounded>(x: T, lo: T, hi: T) -> T` works uniformly.
- Types without a natural bound (arbitrary-precision integers, strings) do not conform to `Bounded`.

---

## 4. Sections

The **section mechanism** is the language's general form for labeled semantic blocks. A section is a labeled, indented block whose contents are governed by a section class. Sections appear across scopes:

- **Class and struct bodies** — sections organize fields and methods, contribute cross-cutting behaviors.
- **Function and method bodies** — sections modify how contained statements are compiled or executed.
- **Module bodies** — sections gate or transform declarations.
- **Expression contexts** — expression-form sections produce values (e.g., `quote:` produces `Code`).

Sections unify what other languages handle piecemeal: annotations, attributes, property wrappers, decorators, aspect-oriented weaving, effect-scoping keywords, DSL builders, and compile-time metaprogramming. In Corvid, all of these are expressed via section classes.

### 4.1 The Section Form

```
section_name:
    ...contents...
```

`section_name` names a section class. The compiler resolves the name in the current scope and applies the section class's processing to the contents.

Sections compose by nesting — see §4.6.

Sections can take parameters:

```
platform(linux):
    function get_kernel_version() -> String:
        ...
```

The parameter is passed to the section class as configuration.

### 4.2 Content Type Taxonomy

Each section class declares what its block contains via a `contains:` metadata slot. The vocabulary of content types is fixed by the language:

| Content type | Description | Example sections |
|---|---|---|
| `fields` | Sequence of `name: Type = default` declarations | `data:`, `state:`, `channel:` |
| `methods` | Sequence of method declarations (`function`, `constructor`, etc.) | `methods:`, `traced:` |
| `declarations` | Sequence of top-level items (functions, types, constants) | `pure:`, `test:`, `interop:` |
| `statements` | Sequence of executable statements | `pure:`, `unsafe:`, `validate:` |
| `ast_fragment` | Arbitrary AST, treated as data | `quote:` |
| `expressions` | Single expression (not a sequence) | *(reserved for future use)* |

The compiler enforces that a section's contents match its declared content type. Using `data:` around methods, for example, is a compile error.

### 4.3 Scope Taxonomy

Each section class declares where it can appear via a `scope:` metadata slot. The vocabulary of scopes is fixed by the language:

- `class_body`
- `struct_body`
- `enumeration_body`
- `protocol_body`
- `function_body`
- `method_body`
- `generator_body`
- `module_body`
- `expression_context`

A section can declare multiple scopes: `scope: [function_body, method_body, module_body]` means the section applies in all three. Using a section outside its declared scope is a compile error.

### 4.4 Section Class Definition

A section class is defined with `section class`:

```
section class SectionName:
    data:
        # Configuration constants (compile-time, per-host-class)
        contains: <content_type>
        scope: [<scope>, ...]
        # ... other configuration fields
    
    state:
        # Runtime state (per host class instance)
        # ...
    
    # Compile-time capabilities
    generator name(field: FieldSpec) -> Code: ...
    processor name(contents: Block) -> Block: ...
    validator name(contents: Block): ...
    
    # Runtime methods (available on self.sections.name.method())
    function name(self, ...) -> ...: ...
```

The section class body is itself composed of sections (`data:`, `state:`) and member declarations (`generator`, `processor`, `validator`, `function`). This is **self-hosting**: the section mechanism defines its own definition syntax.

Bootstrap set: the compiler ships with hardcoded understanding of `Data` and `State` section classes as they appear in section class bodies. Users can inspect and use these but not override them.

### 4.5 Capability Vocabulary

The set of capabilities a section class can use is fixed by the language version. Users compose these capabilities but do not invent new ones.

#### 4.5.1 Configuration

The `data:` section inside a section class holds compile-time constants read by the compiler when processing a host. These include mandatory metadata (`contains`, `scope`) and section-specific defaults.

Standard configuration fields (available to any section):
- `contains: <content_type>` — required.
- `scope: [<scope>, ...]` — required.
- `composition_mode: inline | nested` — required. Determines how this section composes with other sections of the same content type. See §4.6.
- `default_mutability: mutable | immutable` — for field-containing sections.
- `default_visibility: exposed | absent` — for field-containing sections.
- `default_existence: runtime | compile_time` — for field-containing sections.
- `contributes_to_equality: bool` — for field-containing sections.
- `contributes_to_hash: bool`
- `contributes_to_copy: bool`
- `contributes_to_display: bool | redacted`
- `contributes_to_serialization: bool`
- `incompatible_with: [SectionClass, ...]` — sections this refuses to compose with.

Section-class-specific configuration fields are declared in the section class's `data:` block and read via generators or processors.

#### 4.5.2 State

The `state:` section inside a section class holds runtime state per host instance. Each instance of the host class gets one instance of the section class as a hidden field, accessible via `self.sections.<name>`.

Sections with empty `state:` blocks are compile-time-only; the compiler elides the runtime instance.

#### 4.5.3 Generators

Generators emit code at specific extension points. They run once per host class at compile time.

Standard generators:
- `field_getter(field: FieldSpec) -> Code?` — override default getter.
- `field_setter(field: FieldSpec) -> Code?` — override default setter.
- `host_init_hook() -> Code?` — emit constructor prologue.
- `host_deinit_hook() -> Code?` — emit destructor epilogue.
- `method_wrapper(method: MethodSpec) -> Code?` — wrap methods (for `methods`-containing sections).

Generators return `Code` or `null` (use default). Code is constructed via `quote:` expressions.

#### 4.5.4 Processors

Processors transform the contents of a block. They run once per section usage at compile time and return the transformed contents.

```
processor name(contents: Block) -> Block: ...
```

Used for sections like `quote:` (that transform contents into an AST value) or `pure:` (that validate and pass through).

#### 4.5.5 Validators

Validators check the contents of a block for compliance without transforming. They emit compile errors on violation.

```
validator name(contents: Block):
    for item in contents:
        if item.has_effect(.io):
            @compile_error("pure section cannot contain I/O")
```

Semantically equivalent to a processor that returns contents unchanged after checking.

#### 4.5.6 Methods

Regular methods on the section class. Called at runtime via `host.sections.<name>.method(...)`. These implement the section's runtime behavior (subscription APIs, storage access, etc.).

#### 4.5.7 Parameters

Section classes can declare compile-time parameters, bound at use site. Three parameter kinds:

- **Type parameters:** `<T>`, `<T: Comparable>`.
- **Effect parameters:** `<E: Effect>`.
- **Value parameters:** `<N: Int>`, `<Format: SerializationFormat>` (compile-time constants).

Parameters are declared on the section class header and referenced in generators, processors, expansions, invariants, and configuration:

```
section class Effect_Aware<E: Effect>:
    data:
        contains: type_signature
        composition_mode: inline
    
    expands_to: with ${E}

section class Bounded<Max: Int>:
    data:
        contains: fields
        composition_mode: nested
    
    invariants:
        self.count <= ${Max}
```

Used with parameter values at the section header:

```
class MyQueue:
    bounded<100>:
        items: [Item]
```

Parameter references in generators use `${param_name}` splicing (same syntax as quote-based code generation).

#### 4.5.8 Expands_to

A section class with `expands_to:` performs textual substitution at parse time. The section is replaced with the specified composition of other sections. Sections with `expands_to:` typically have no state, no generators, no runtime methods — they exist purely to name a composition.

```
section class Data:
    data:
        contains: fields
        composition_mode: inline
    
    expands_to: runtime immutable identity
```

When `data:` appears in a class body, the compiler substitutes `runtime immutable identity:` before further processing. Expansion is applied recursively until no expandable sections remain.

**Expansion boundaries:**
- Expansion runs at parse time, before generator/processor invocation.
- Expansions cannot reference the host class's contents (they operate on section headers, not fields).
- Circular expansion (A expands to B, B expands to A) is a compile error.
- Expansions can reference parameters: `expands_to: with ${E}`.

#### 4.5.9 Invariants

Section classes are classes; they get class-level contracts (§3.4.2). Section class invariants apply to the section instance's state:

```
section class Channel:
    data:
        contains: fields
        composition_mode: nested
    
    state:
        subscribers: [Subscriber] = []
        active: Bool = false
    
    invariants:
        subscribers.count >= 0
        active == false implies subscribers.is_empty()
    
    function subscribe(self, callback)
        requires active:
        subscribers.append(callback)
```

Section invariants are checked at the same points as class invariants (§3.4.2) — Stage 1 runtime-enforced, Stage 3+ compile-time-enforced where analyzable.

#### 4.5.10 Describers

A section can declare a describer that runs at compile time and returns structural metadata about what the section contributed to the host class. Describers feed the class metadata artifact (§4.18) and are consumed by the language server, runtime metadata queries, and external tools.

```
section class Channel:
    data:
        contains: fields
        composition_mode: nested
    
    state:
        subscribers: [Subscriber] = []
    
    generator field_setter(field: FieldSpec) -> Code:
        return quote:
            function set_${field.name}(self, new_value):
                old = self._${field.name}
                self._${field.name} = new_value
                self.sections.channel.notify(name: "${field.name}", old: old, new: new_value)
    
    describer describe(host: ClassMeta) -> SectionMeta:
        wrapped_fields = host.fields_in_section(Channel)
        return SectionMeta:
            section_name: "channel"
            wraps_fields: wrapped_fields
            generated_methods: [
                MethodMeta(
                    name: "set_${f.name}",
                    signature: "(${f.type}) -> Unit",
                    contract: "notifies subscribers on change",
                    provenance: source_location(Channel, "field_setter", f))
                for f in wrapped_fields
            ]
            section_instance_methods: [
                MethodMeta(name: "subscribe", signature: "((String, Any, Any) -> Unit) -> Unit"),
                MethodMeta(name: "unsubscribe", signature: "((String, Any, Any) -> Unit) -> Unit"),
            ]
            section_instance_state: [
                FieldSpec(name: "subscribers", type: "[Subscriber]", visibility: .private)
            ]
```

**Describer semantics:**
- Runs at compile time, after generators have produced code but before code emission.
- Returns a `SectionMeta` structured value describing the section's contribution.
- Contributes to the enclosing class's `ClassMeta` — the class metadata artifact.
- Cannot inspect other sections' generated code (§4.17 boundary).
- Cannot produce code — only metadata.
- Provenance in the returned metadata links back to the section definition and specific generator invocations.

**Built-in sections have hardcoded describers** in Stage 1. `Data`, `State`, `Static`, `Methods`, `Channel`, `Signal`, `Computed`, `Deprecated`, `Category`, `ObjC_Exposed`, `With`, `Package` all describe themselves. Users writing section usages benefit from complete metadata immediately.

**User section classes gain describers as a capability in Stage 2**, when section classes become user-definable. A user-defined section without a describer contributes only its bare structural fact (name and composition mode) to the metadata; a section with a describer contributes full detail.

**Describers are pure compile-time computation.** They can compute over their inputs (the section usage, the host class's public interface) but cannot perform I/O, invoke the runtime, or produce side effects. Same discipline as generators.

#### 4.5.11 Capability Combinations

A section class uses any subset of the capabilities above. Common combinations:

- **Primitive section:** only configuration.
- **Alias section:** configuration + expands_to.
- **Parameterized alias:** configuration + parameters + expands_to.
- **Simple behavioral:** configuration + generators + describer.
- **Stateful behavioral:** configuration + state + generators + methods + describer.
- **State-machine section:** configuration + state + generators + invariants + methods with pre/postconditions + describer.

The compiler processes uniformly; users see only the capabilities each specific section actually uses.

### 4.6 Composition Rules

Sections compose in two modes, determined by the section class's declared `composition_mode`:

- **Inline composition** — for sections that describe properties without wrapping. Multiple sections are named in the same header, separated by whitespace. Order does not affect semantics. Sections applied inline commute.
- **Nested composition** — for sections that wrap behavior. Sections are stacked as nested indented blocks. Outer sections wrap inner sections.

Each section class declares its composition mode; the compiler enforces it.

#### 4.6.1 Inline Composition

A single header may name multiple sections separated by whitespace:

```
class HttpClient:
    compile_time immutable:
        DEFAULT_TIMEOUT_MS: Int = 30000
        MAX_RETRIES: Int = 3
```

The header composes `Compile_time` and `Immutable`. Each contributes its configuration to the enclosed fields. Order is not semantically meaningful — `compile_time immutable:` means the same as `immutable compile_time:`.

Sections composed inline must all declare `composition_mode: inline`. Attempting to compose a nested-mode section inline is a compile error.

Contradictions (`immutable mutable:`, `runtime compile_time:`) are compile errors regardless of order.

#### 4.6.2 Nested Composition

Sections that wrap behavior compose via nesting. Top-to-bottom in source is outer-to-inner in wrapping:

```
class UserPreferences:
    channel:
        persistent:
            mutable:
                theme: Theme = .light
```

Setter execution order when `preferences.theme = .dark`:
1. `channel`'s setter runs (outermost — listed first in the source).
2. Captures old value, delegates inward.
3. `persistent`'s setter runs.
4. Writes to backing store.
5. Returns.
6. `channel`'s setter notifies subscribers.
7. Returns.

The visual structure of the source IS the semantic structure of the wrapping.

Sections composed via nesting must declare `composition_mode: nested`. Nested-mode sections cannot appear in inline composition.

#### 4.6.3 Mixing Modes

Inline-mode sections may appear inside nested-mode sections at the innermost level, applied to the field or method declarations:

```
class UserPreferences:
    channel:
        persistent:
            mutable:                    # inline composition — properties of the field
                theme: Theme = .light
```

The outer nested sections handle wrapping; the inline composition handles the field's descriptive properties. Reader parses it as: `theme` is a public, mutable field, wrapped by persistent behavior, wrapped by channel behavior.

Nested-mode sections cannot appear inside inline composition — nesting is the only expression of wrapping order.

#### 4.6.4 Section Repetition

Sections can repeat in the same class body. Each occurrence governs the fields directly inside it; the section instances merge at the runtime level:

```
class UserPreferences:
    channel:
        persistent:
            mutable:
                theme: Theme = .light
                language: String = "en"
    
    channel:
        mutable:
            connection_state: ConnectionState
    
    persistent:
        mutable:
            last_backup: Timestamp
```

At runtime, this class has one `sections.channel` instance tracking `theme`, `language`, and `connection_state`, and one `sections.persistent` instance tracking `theme`, `language`, and `last_backup`.

#### 4.6.5 Compatibility Checking

The compiler enforces compatibility at parse time:

- **Content type mismatch** — attempting to compose sections that contain different things (e.g., a field-containing section around a method-containing section) is a compile error identifying the mismatch.
- **Semantic incompatibility** — sections declare `incompatible_with: [SectionClass, ...]`; the compiler rejects invalid compositions with a clear message.
- **Mode mismatch** — attempting to compose inline-mode and nested-mode sections in the same header (or nested-mode in inline position) is a compile error.
- **Getter conflicts** — if two composed sections both try to override the same field's getter, the compiler errors. Setter conflicts don't arise because nested setters compose via delegation.

### 4.7 Class and Struct Bodies

Class bodies are sequences of sections. Members appearing directly in a class body without an enclosing section are implicitly wrapped in a `methods:` section.

```
class Order:
    data:
        id: OrderID
        items: [Item]
        placed_at: Timestamp
    
    # Implicit methods: section — these methods appear as if in `methods:`
    function is_valid(self) -> Bool:
        return not self.items.is_empty and self.total() > 0
    
    function total(self) -> Money:
        return self.items.sum(by: .price)
```

Equivalent explicit form:

```
class Order:
    data:
        id: OrderID
        items: [Item]
        placed_at: Timestamp
    
    methods:
        function is_valid(self) -> Bool: ...
        function total(self) -> Money: ...
```

A class body may contain any combination of sections. It may have no `data:` (pure coordinator class), no methods (pure record class), or both.

Struct bodies follow the same structure as class bodies but with value semantics.

### 4.8 Function, Method, and Module Bodies

Sections in function/method bodies modify how contained statements are compiled or executed:

```
function compute(x: Int) -> Int:
    validate:
        require x > 0
        require x < 1000
    
    pure:
        result = x * 2 + 1
    
    return result
```

Sections in module bodies gate or transform declarations:

```
module com.example.utils

pure:
    function factorial(n: Int) -> Int: ...
    function fibonacci(n: Int) -> Int: ...

test:
    function test_factorial(): ...

platform(linux):
    function get_kernel_version() -> String: ...
```

### 4.9 Expression-Form Sections

Some sections appear in expression contexts and evaluate to values:

```
generator field_setter(field: FieldSpec) -> Code:
    return quote:
        function set_${field.name}(self, new_value: ${field.type}):
            self._storage.${field.name} = new_value
```

The `quote:` block is an expression evaluating to a `Code` value. `${expr}` splices values from the surrounding scope into the AST being constructed.

### 4.10 Built-in Section Classes

These section classes ship with the language and are available in every module. They fall into three categories, described below.

#### 4.10.0 Taxonomy — Primitives, Aliases, and Behavioral Sections

The built-in section vocabulary is organized into three tiers:

**Primitive sections** are the atoms of the vocabulary. Each expresses a single characteristic (mutability, visibility, existence, dispatch mode, etc.) with `composition_mode: inline`. They have no runtime state and no wrapping semantics. Their configuration contributes to the enclosing member's properties. Users compose them freely.

**Aliases** are language-provided names for common primitive compositions. `data:` is an alias for `runtime immutable identity`; `state:` is an alias for `runtime mutable package coordination`; `static:` is an alias for `compile_time immutable public`. Aliases are pure naming — they carry no additional semantics beyond the composition they expand to. Users may define their own aliases at module level (§4.16).

**Behavioral sections** have runtime state, wrapping semantics, or content transformation. They compose via nesting (`composition_mode: nested`). `Channel`, `Persistent`, `Computed`, `Deprecated`, `Pure`, `Unsafe`, `Validate`, `Quote`, `Interop`, `Test`, `Platform` all fall in this category.

**Why this structure.** The compile-time/runtime and immutable/mutable dimensions form an orthogonal grid; every combination is meaningful. Rather than pre-blessing specific combinations as sections (`Data`, `State`, `Static`), primitives let users express any combination, and aliases name the common ones. Class-level mutable state (`compile_time mutable`) — previously a "degenerate cell" in v0.4's initial taxonomy — is expressible via composition without needing its own alias:

```
class RequestCounter:
    compile_time mutable:
        total_requests: Int = 0
```

The primitive-and-alias model matches how programmers already read declarations: `public static final int MAX = 100` in Java is characteristic composition of orthogonal properties (visibility, storage, mutability). Corvid makes that composition uniform across the language's section vocabulary — visibility is `expose`, storage is `runtime`/`compile_time`, mutability is `mutable`/`immutable`.

#### 4.10.1 Primitive Sections *(Stage 1)*

All primitives declare `composition_mode: inline`. They may be freely composed with each other in a single header, subject to compatibility (contradictions are rejected).

**Existence:**

- `Runtime` — member exists per-instance at runtime. `default_existence: runtime`.
- `Compile_time` — member exists per-type at compile time, allocated once. `default_existence: compile_time`.

**Mutability:**

- `Immutable` — binding cannot be reassigned after initial value. `default_mutability: immutable`. Provides `field_setter` generator that emits a compile error on assignment.
- `Mutable` — binding may be reassigned. `default_mutability: mutable`.

**Visibility:**

- `Expose` — declaration is part of the module's intended external interface. `default_visibility: exposed`. Absent, declarations are internal-by-convention (§7.7).

No `Private`, `Public`, `Package`, `Sealed`, or `Dynamic` primitives. Reasons:
- `Public` renamed to `Expose` (§7.7).
- `Private` and `Package` removed — under intent-not-authority, absent-`expose` covers the internal-by-convention case; the compiler warns on external access. Type-scoped restriction is convention within a module.
- `Sealed` and `Dynamic` removed as source annotations (§5.2). Devirtualization is a build-tool concern.

**Reference strength:**

- `Weak` — reference does not retain; nils out when target deallocates.
- `Unowned` — non-owning strong reference.

Both apply to `contains: fields` sections where the field type is a class.

**Equality contribution:**

- `Identity` — field participates in equality, hashing, copy-with-modifications, and default serialization. `contributes_to_equality: true`, etc.
- `Coordination` — field is excluded from equality, hashing, and copy. `contributes_to_equality: false`, etc.

**Compatibility.** The compiler enforces obvious contradictions: `immutable mutable`, `runtime compile_time`, `identity coordination`. Composing these produces a clear error.

Some compositions are legal but semantically meaningless: `compile_time weak`, `identity weak` on a class field. The compiler warns rather than errors, on the principle that intent-not-authority extends to the compiler's judgments about "good" combinations.

#### 4.10.2 Aliases *(Stage 1)*

Aliases are language-provided names for common primitive compositions. They are declared using the `alias` construct (§4.16). **Aliases are pure textual expansion** — they add no semantics beyond the primitives they name.

**Built-in aliases:**

```
alias data: runtime immutable identity
alias state: runtime mutable coordination
alias static: compile_time immutable
alias methods: runtime  # implicit container for method declarations
```

Notice none of the built-in aliases include `expose`. Visibility is a separate axis from existence, mutability, and identity contribution. Users add `expose` at the section header when they want the members visible to importers.

Usage:

```
class Order:
    expose data:               # runtime immutable identity, exposed
        id: OrderID
        items: [Item]
        placed_at: Timestamp
    
    data:                      # runtime immutable identity, internal-by-convention
        internal_hash: Bytes
    
    expose state:              # runtime mutable coordination, exposed
        status: OrderStatus
    
    state:                     # runtime mutable coordination, internal
        cached_total: Money?
    
    static:                    # compile_time immutable, internal
        MAX_ITEMS: Int = 100
    
    expose methods:
        function ship(self) -> ShipOutcome: ...
    
    methods:
        function _recompute_total(self): ...
```

**Aliases do not override.** Composing an alias with additional primitives is equivalent to composing the alias's expansion with those primitives. Contradictions produce compile errors, not silent overrides. Since visibility is separate from the data alias's expansion, `expose data:` composes cleanly — it means "runtime immutable identity, plus exposed."

Users who prefer shorter forms can define composition aliases for their common patterns:

```
alias exposed_data: runtime immutable identity expose
alias exposed_state: runtime mutable coordination expose

class Order:
    exposed_data:
        id: OrderID
    
    data:                    # absent expose — internal by convention
        internal_signature: Bytes
```

#### 4.10.3 Methods Section

The implicit section for undecorated method declarations in class and struct bodies:

```
section class Methods:
    data:
        contains: methods
        scope: [class_body, struct_body]
        composition_mode: nested    # methods can be wrapped by traced:, logged:, etc.
```

Applies no transformations — an identity container. Methods appearing directly in a class body without an enclosing section are implicitly wrapped in a `methods:` section (§4.7).

#### 4.10.4 Behavioral Sections *(Stage 1)*

Sections with runtime state, generators, or transformation power. Compose via nesting.

##### 4.10.4.1 Computed

Read-only derived properties computed from other fields on access.

```
section class Computed:
    data:
        contains: fields
        scope: [class_body, struct_body]
        composition_mode: nested
        default_visibility: public
        contributes_to_equality: false
        contributes_to_hash: false
        contributes_to_copy: false
        incompatible_with: [Mutable, Persistent, Channel]
    
    generator field_getter(field: FieldSpec) -> Code:
        return quote:
            function ${field.name}(self) -> ${field.type}:
                return ${field.default_expression}
    
    generator field_setter(field: FieldSpec) -> Code?:
        return @compile_error_setter(
            "cannot assign to computed field '${field.name}'")
```

Usage:

```
class Circle:
    data:
        radius: Float
    
    computed:
        area: Float = 3.14159 * self.radius ** 2
        circumference: Float = 2 * 3.14159 * self.radius
```

Computed fields recompute on every access. Users who want caching wrap with a library `Cache` section or memoize manually.

##### 4.10.4.2 Deprecated

Marks contained members as deprecated with migration guidance and tooling integration.

```
section class Deprecated:
    data:
        contains: methods
        scope: [class_body, struct_body, module_body]
        composition_mode: nested
        parameters:
            reason: String
            since_version: String
            removed_in_version: String?
    
    generator method_wrapper(method: MethodSpec) -> Code:
        return quote:
            @deprecation_warning(
                reason: ${self.parameters.reason},
                since: ${self.parameters.since_version},
                removed_in: ${self.parameters.removed_in_version})
            function ${method.name}(${method.parameters}) -> ${method.return_type}:
                ${method.body}
```

Usage:

```
class OldAPI:
    methods:
        function new_method(self):
            ...
    
    deprecated(reason: "Use new_method instead", since_version: "2.0"):
        function old_method(self):
            ...
```

Callers of `old_method` receive compiler warnings. Tooling surfaces the deprecation reason and migration path.

##### 4.10.4.3 Category *(retired v0.9.0)*

The Category section class was retired in v0.9.0. Extending an existing class — Corvid class, ObjC class, generic parameterization, protocol — uses the unified extension mechanism at declaration position: `extend class Foo:` (§3.3.2). Default-method inheritance for structural protocol conformers is specified in §3.4.4.

**Migration (mechanical):**

```
# Before v0.9.0:
category(target_class: NSString):
    function shout(self) -> NSString:
        return self.upper_case

# After v0.9.0:
extend class NSString:
    methods:
        function shout(self) -> NSString:
            return self.upper_case
```

Semantics unchanged — methods land in the runtime method table for the target class; both Corvid and ObjC call sites dispatch through the same mechanism. The v0.9.0 form additionally supports generic parameters and where-clauses on the extension target (`extend class Array<T> where T: UnsignedInteger:`), which the Category mechanism could not express.

Devirtualization (§5.2) and reflection (`class_getMethodList` returning all methods including extensions) are preserved. `class_addMethod` remains available as a runtime API for programmatic addition (mocking, tooling, dylib-loaded categories); `extend class` is the source-level mechanism.

##### 4.10.4.4 ObjC_Exposed

Explicit control over which methods are exposed to Objective-C callers, and in what form. Alternative to auto-derivation (§9.3.3) for public APIs.

```
section class ObjC_Exposed:
    data:
        contains: methods
        scope: [class_body, struct_body]
        composition_mode: nested
    
    generator method_wrapper(method: MethodSpec) -> Code:
        # Method is emitted with ObjC-compatible signature in generated header.
        # Corvid callers see the original signature.
        return quote:
            @objc_visible
            @generate_header_entry(signature: ${method.objc_signature()})
            function ${method.name}(${method.parameters}) -> ${method.return_type}:
                ${method.body}
```

Usage:

```
expose class OrderProcessor:
    data:
        id: ProcessorID
    
    # Auto-derived for ObjC — visible with default ObjC translation
    methods:
        function get_status(self) -> String: ...
    
    # Explicitly ObjC-exposed with controlled signature
    objc_exposed:
        function processOrder(self, order: Order, error: NSError**) -> Bool: ...
        function cancelOrder(self, orderID: OrderID) -> Bool: ...
```

Methods in `objc_exposed:` sections replace their auto-derived ObjC signature with the explicitly written form. Useful when the auto-derived signature doesn't match ObjC calling conventions (e.g., preferring `NSError **` out-params over domain-enum returns for consistency with existing ObjC APIs).

Methods NOT in `objc_exposed:` are still auto-derived from the class's normal `methods:` section unless the user explicitly opts out via a class-level annotation (deferred to Stage 2).

##### 4.10.4.5 With

Scoped resource management. Contents are statements including resource-binding declarations; the section ensures deinitializers run in reverse declaration order when the scope exits (RAII pattern).

```
section class With:
    data:
        contains: statements
        scope: [function_body, method_body]
        composition_mode: nested
    
    processor process(contents: Block) -> Block:
        # For each variable declaration whose type conforms to Resource,
        # register a scope-exit handler that calls the destructor.
        # Handlers run in reverse declaration order.
        # Handlers run even if the block exits via error propagation.
        return contents.with_scope_exit_handlers()
```

Usage:

```
enum FileProcessOutcome:
    case processed(String)
    case open_failed(FileOpenOutcome)
    case read_failed(reason: String)

function process_file(path: String) -> FileProcessOutcome:
    with:
        f = File.open(path)             # File conforms to Resource
        buffer = Buffer.allocate(1024)
        
        contents = f.read()
        buffer.append(contents)
        return .processed(buffer.to_string())
    # buffer's destructor runs, then f's destructor runs
    # Cleanup runs even if the block returns .open_failed / .read_failed
```

Resources bound in a `with:` block are automatically cleaned up on scope exit. Unlike Python's `with` (which requires context manager objects) or Java's try-with-resources, Corvid's `with:` works with any type that has a destructor, and multiple resources bind naturally in one block. Cleanup runs regardless of which branch the block exits through — the `With` section installs scope-exit handlers that fire on any exit path (§4.10.4.5).

#### 4.10.5 Behavioral Sections *(Stage 3)* — Statement-Scope

##### 4.10.5.1 Pure

Restricts contained code to pure computation — no I/O, no mutation of external state, no runtime dispatch of unknown methods.

```
section class Pure:
    data:
        contains: statements
        scope: [function_body, method_body]
        composition_mode: nested
        incompatible_with: [Unsafe]
    
    validator check(contents: Block):
        for statement in contents:
            if statement.has_effect(.io, .external_mutation, .unknown_dispatch):
                @compile_error(
                    "pure section cannot contain '${statement.effect_name}' at ${statement.location}")
```

Usage:

```
function compute(x: Int) -> Int:
    pure:
        result = x * 2 + 1
        result = result * result
    return result
```

##### 4.10.5.2 Unsafe

Enables restricted operations: raw pointer dereferencing, uninitialized memory access, calling unsafe functions.

```
section class Unsafe:
    data:
        contains: statements
        scope: [function_body, method_body]
        composition_mode: nested
        incompatible_with: [Pure]
    
    processor process(contents: Block) -> Block:
        contents.enable_capability(.raw_pointer_access)
        contents.enable_capability(.uninitialized_memory)
        contents.enable_capability(.unsafe_function_calls)
        return contents
```

##### 4.10.5.3 Validate

Precondition and assertion blocks at method prologues.

```
section class Validate:
    data:
        contains: statements
        scope: [method_body, function_body]
        composition_mode: nested
    
    processor process(contents: Block) -> Block:
        return quote:
            for check in contents:
                if not ${check.expression}:
                    @raise_contract_violation(
                        "${check.expression_source}",
                        location: ${check.location})
```

Usage:

```
function process(self, order: Order) -> Receipt:
    validate:
        require order.is_valid()
        require order.total > 0
    
    # ... method body ...
```

##### 4.10.5.4 Quote

AST construction. Available inside generator bodies from Stage 1; generalized to any expression context in Stage 3.

```
section class Quote:
    data:
        contains: ast_fragment
        scope: [expression_context]
        composition_mode: nested
        returns: Code
    
    processor process(contents: AST) -> Code:
        return Code.from_ast(contents)
```

Splice values from the surrounding scope via `${expression}`.

#### 4.10.6 Behavioral Sections *(Stage 4)* — Module-Scope

##### 4.10.6.1 Interop

Enables C ABI features within the module.

```
section class Interop:
    data:
        contains: declarations
        scope: [module_body]
        composition_mode: nested
    
    processor process(contents: Block) -> Block:
        contents.enable_capability(.c_abi_functions)
        contents.enable_capability(.raw_type_layout)
        return contents
```

##### 4.10.6.2 Test

Test-only declarations, excluded from release builds.

```
section class Test:
    data:
        contains: declarations
        scope: [module_body]
        composition_mode: nested
    
    processor process(contents: Block) -> Block:
        if compile_mode == .release:
            return Block.empty()
        return contents
```

##### 4.10.6.3 Platform

Conditional compilation by platform.

```
section class Platform:
    data:
        contains: declarations
        scope: [module_body]
        composition_mode: nested
        parameters:
            target: PlatformSpec
    
    processor process(contents: Block) -> Block:
        if compile_target != self.parameters.target:
            return Block.empty()
        return contents
```

#### 4.10.7 Kind Section Classes *(Stage 1)* — `Regular`, `Record`, `Enum`, `Protocol`, `Section`

Class kinds are implemented as built-in section classes. The `metadata: kind: X` entry on a class dispatches to the corresponding section class, which validates the class body and installs kind-specific compilation behavior.

**`Regular`** — the default kind, applied when no `metadata: kind:` is present. Permits all sections. Produces reference-typed classes with universal extents, weak references, dynamic dispatch, and ARC (§6.3).

**`Record`** — value-typed data records.

- **Permits:** `data:`, `methods:`, `class:`, `invariants:`, `computed:`, `metadata:`.
- **Forbids:** `state:`, `channel:`, `signal:`, and any behavioral section requiring per-instance mutable state.
- **Generates:** structural `==` from `data:` fields; `with(...)` modification method; copy-on-assignment.
- **Opts out of:** universal extent registration; weak refs; ARC.
- **Runtime shape:** stack-allocated when scope-local; inline in containers; passed by value.

**`Enum`** — sealed sum types.

- **Permits:** `cases:` (required), `methods:`, `class:`, `metadata:`.
- **Forbids:** `state:`, `data:` at class scope (variants carry their own associated data inside `cases:`).
- **Reads from `metadata:`:** optional `raw_type:` for integer-backed enums.
- **Generates:** for each `case name(field: Type, ...)`, a constructor `EnumName.name(field: value, ...)`. For raw-valued enums (all cases have `= value`), auto-generates `raw_value(self) -> raw_type` and `class function from_raw(value: raw_type) -> Self?`.
- **Requires:** if any case has `= value`, all cases must; the raw type is inferred as the smallest unsigned or signed type that fits all values unless `metadata: raw_type:` overrides.
- **Runtime shape:** discriminated union; tag + associated values.

**`Protocol`** — named contracts, no instantiation.

- **Permits:** `accepts:` (declares required signatures with their contracts), `methods:` (default implementations), `class:`, `metadata:`.
- **Forbids:** `data:`, `state:`, `cases:` (protocols hold no data).
- **Behavior:** structural conformance by default; explicit `class Foo(SomeProtocol):` header sugar or body-form `conforms:` entry legal but optional (§3.4.5). Runtime is `@protocol` at the ObjC boundary.
- **`methods:` semantics under `kind: protocol`:** each method is a *default implementation* — used when a conforming class does not provide the message. Method signatures alone (without bodies) may also appear in `methods:` and are equivalent to entries in `accepts:` (the compiler folds them).

**`Section`** — §4 metaprogramming section-classes.

- **Permits:** whatever the specific section class's role requires — typically `data:` (for configuration/parameters), `state:` (for section instance state), `generator:`, `processor:`, `validator:`, `describer:`.
- **Runtime shape:** compile-time only. Section classes have no runtime instances at the user-code level; their state exists inside the compiled class metadata (§4.18).
- **Detail:** section-class internals are documented throughout §4 and are the same forms already used for built-in and user-defined sections. `metadata: kind: section` is the surface-level marker; `section Foo:` (prelude alias) or `section class Foo:` (equivalent legacy form) are the sugar for it.

**Surface syntax for kinds.** The five kinds above are addressed at declaration position via stdlib alias declarations (§4.16.3, §4.16.8) — `record`, `enum`, `protocol`, `section`. `Regular` is the default and has no keyword sugar; write `class Foo:` for it. Users may define their own class-kind aliases (§4.16.3) that expand to `class + metadata: kind: ...` with additional metadata entries — this is the extension point for domain-specific declaration sugar.

**Reserved metadata keys.** The following keys under `metadata:` are reserved by the language:

| Key | Applies to | Meaning |
|---|---|---|
| `kind` | Any class | Selects the kind section class; values from the spec-defined set above |
| `raw_type` | `kind: enum` | Integer type backing enum cases; inferred if omitted |
| `mode` | `channel:` section (§8.6) | `work` \| `broadcast` \| `pub` — coordination semantics |
| `capacity` | `channel:` section | Positive int; if present, channel is bounded |
| `on_full` | `channel:` section | `block` \| `error` \| `drop_newest` \| `drop_oldest`; required when `capacity` is set |
| `topic_fn` | `channel:` section with `mode: pub` | Function from item to topic identifier |

Users may add arbitrary keys under `metadata:` for tool/documentation use (e.g. `since:`, `platforms:`, `stability:`). Non-reserved keys are ignored by the compiler and surfaced via §4.18 class-metadata queries.

#### 4.10.8 The `metadata:` Section *(Stage 1)*

`metadata:` is a **class-header declaration prelude with section-shaped syntax** — it carries compile-time metadata about the enclosing class. Written like a section for visual consistency with the rest of the class body, but governed by rules that deviate from §4.6 section composition semantics: exactly one per class, must appear first, no runtime instance, cannot repeat, compile-time only. Consider it a first-class declaration slot that happens to look like a section rather than a section-in-the-general-sense.

**Why the section-shape reuse.** Readers already parse `data:` / `state:` / `channel:` / etc. as class-body labeled blocks. Reusing that shape for `metadata:` avoids introducing a second header-level block syntax. The cost is the deviation from §4.6 rules noted below; the benefit is one visual vocabulary for the class body.

**Composition:**

```
class Foo:
    metadata:
        kind: record
        since: "0.6.0"
        platforms: [macos, ios]
    data:
        x: Int
```

**Rules:**

1. **Position.** If present, `metadata:` must appear first in the class body. This is enforced at parse time. Rationale: subsequent sections' legality depends on `kind:` (§4.10.7), so the compiler must see it before processing the rest of the body.
2. **Uniqueness.** At most one `metadata:` section per class.
3. **Reserved keys.** See table in §4.10.7. The compiler validates reserved-key values; unknown values are compile errors. Non-reserved keys are accepted verbatim.
4. **Compile-time only.** `metadata:` entries do not create runtime fields on instances. They populate the class-metadata artifact (§4.18) and are queryable via `Class.by_qualified_name(...).metadata_entry(name)`.
5. **Not required.** A class with no `metadata:` section defaults to `kind: regular`. Explicit `metadata: kind: regular` is legal but rarely written.

**Distinction from §4.18 Class Metadata.** §4.18 describes the runtime-introspectable class-metadata *artifact* that the compiler generates for every class. The `metadata:` section is one of several sources feeding that artifact — user-written declarative entries. Other sources include field types, method signatures, generated members from behavioral sections, provenance, and conformance information. `metadata:` is where the user contributes; §4.18 is where consumers read.

#### 4.10.9 The `on_nil` Section *(Stage 1)*

`on_nil:` is a user-writable declaration section that specifies per-method return values when the receiver is nil. It participates in nil-receiver dispatch (§5.7).

**Syntax normalization (v0.11 cleanup).** Prior spec drafts wrote this section as `section on_nil:` with a `section` keyword prefix, which was inconsistent with every other stdlib section (`data:`, `state:`, `methods:`, `channel:`, `metadata:`, etc.) — all use bare `<name>:` at usage sites. The `section` keyword is reserved for section-class *definitions* (§4.11 `section class Channel:`), not for usage. Post-cleanup, `on_nil:` is the sole spelling.

**Composition:**

```
class UserList:
    accepts:
        entries: [User]
    methods:
        function count(self) -> Int:
            return self.entries.length
        function is_empty(self) -> Bool:
            return self.entries.length == 0
        function first(self) -> User:
            return self.entries[0]
    on_nil:
        count: 0
        is_empty: true
        first: nil          # explicit — otherwise auto-lifts to User? = nil
```

**Rules:**

1. **Entries.** Each entry is `method_name: value_expression`. The `method_name` must match a method declared on the class or one of its conformed protocols. The `value_expression` must be a compile-time constant, and its type must be assignable to the method's declared return type.
2. **Applies to nil-receiver call sites only.** The generated dispatch consults `on_nil` only when the receiver is nil at call time. Non-nil receivers dispatch to the method body as normal.
3. **Legal on any class kind that permits nil.** `kind: regular` classes (reference-typed) may declare `on_nil`. `kind: record`, `kind: enum` (value-typed) reject `on_nil` — value types cannot be nil, the section is meaningless.
4. **Legal on protocols.** A protocol may declare `on_nil` entries. Conformers inherit the entries unless they override them at the class level. Precedence rules per §5.7.
5. **Unlisted methods auto-lift.** Methods not listed in `on_nil` follow default nil-receiver semantics — return-type `T` auto-lifts to `T?`, return-type `Unit` no-ops.
6. **Composition.** `on_nil` composes inline like other sections. A class may declare at most one `on_nil` section directly; multiple protocol-inherited `on_nil` entries for the same method are a compile error unless resolved by a class-level entry (§5.7).

**Stdlib protocols shipping default `on_nil`:**

- **`Collection`** — `count: 0`, `is_empty: true`, `iter: EmptyIterator()`.
- **`String`** — `length: 0`, `is_empty: true`, `chars: EmptyIterator()`.
- **`Numeric`** — no `on_nil` entries. Arithmetic on nil is a type error (§3.6.1); no punning.

**Rationale.** ObjC's nil-messaging gives Corvid safe nil-propagation for free. `on_nil` is the mechanism for the Clojure-style extension: instead of "nil.count() returns nil-lifted-to-Int?", authors can declare "nil.count() returns 0" and get the collection-punning idiom. Because the mechanism is a section (§4), users write it once per class or protocol; it composes with everything else via the standard section rules.

### 4.11 User-Defined Section Classes

Users define section classes using the same syntax as built-in ones. Example — an `Channel` section:

```
section class Channel:
    data:
        contains: fields
        scope: [class_body]
        default_mutability: mutable
        contributes_to_equality: false
    
    state:
        subscribers: [Subscriber] = []
    
    generator field_setter(field: FieldSpec) -> Code:
        return quote:
            function set_${field.name}(self, new_value: ${field.type}):
                old = self._storage.${field.name}
                self._storage.${field.name} = new_value
                self.sections.channel.notify(
                    field: ${field.name.as_string},
                    old: old,
                    new: new_value)
    
    function subscribe(self, callback: (String, Any, Any) -> Unit) -> Subscription:
        subscription = Subscription(callback)
        self.subscribers.append(subscription)
        return subscription
    
    function notify(self, field: String, old: Any, new: Any):
        for sub in self.subscribers:
            sub.callback(field, old, new)
```

### 4.12 Runtime Access to Sections

Every host class instance has an implicit `sections` namespace:

```
counter = Counter()
counter.sections.channel.subscribe(function(name, old, new):
    print("$name: $old → $new")
)
counter.value = 5  # triggers notification via generated setter
```

Sections without runtime state (empty `state:`) are still accessible via `sections` for introspection, but there is no per-instance section object.

The `sections` namespace supports reflection:

```
for section_name in counter.sections.list():
    print(section_name)

fields_in_data = counter.sections.data.fields()  # [FieldSpec]
```

### 4.13 Author Intent, Not Authority

Sections express intent about how code should be structured and processed. They do not create restrictions the runtime enforces.

- A field in a `data:` section is compile-time immutable, but can be modified via reflection (`class_setIvar`) at runtime.
- A section's generated setter can be bypassed by direct reflection access; the observation doesn't fire, but the write succeeds.
- Users can subscribe to a section instance's stream even if the host author didn't expose subscription publicly.
- Runtime code can add methods to section instances via the standard runtime API.

Sections are a code-organization and code-generation mechanism, not a security boundary. The runtime capability principle from §1.2 applies uniformly.

### 4.14 Error Messages and Tooling

Sections generate code; when generated code has errors, the compiler must attribute errors to:
1. The user's usage site (where the section label appears).
2. The specific field or content that caused the error.
3. The section class's generator that produced the error.
4. The specific error in the generated code.

Every generated AST node carries provenance (source location in the generator that produced it plus source location in the user's code that triggered generation). Compiler errors show both the "expanded" view (what the compiler saw after generation) and the "source" view (what the user wrote), with clear navigation between them.

Tooling (IDEs, formatters, linters) understands sections. IDEs can load user-defined section classes to provide accurate autocompletion and diagnostics. This affects the tooling architecture: sections aren't just a compiler feature.

### 4.15 Versioning

Section classes shipped in libraries participate in downstream compilation. Changes to a section class's generated code are breaking changes for host classes that use it.

Section classes declare semantic version compatibility explicitly. Breaking changes require major version bumps. Compilers verify version compatibility at compile time.

*[UNRESOLVED]:* precise versioning scheme.

### 4.16 Aliases

Aliases bind a syntactic pattern to a compile-time expansion. They are Corvid's syntactic metaprogramming primitive — the mechanism by which surface syntax is defined. Every user-facing sugar in the language (class-kind keywords, section-composition names, type-expression forms, collection literals, range operators, string interpolation, bitwise operators) is a stdlib alias declaration shipped in the prelude (§4.20). Users write their own aliases for domain-specific syntax.

**One shape.** All aliases follow: `alias <signature>: <expansion>`. The signature is a pattern the compiler matches against source syntax; the expansion is the template to substitute. Match variables in `<name>` form bind to matched content; the `...` placeholder captures an indented body when the alias appears with `:` and following block.

```
alias <signature> [precedence: N] [associativity: left|right|none] [in <position>] [where <guards>]:
    <expansion>
```

**Categories emerge from shape.** The compiler classifies an alias by its signature form:

- **Identifier signature** — a bare name (e.g., `alias data:`). Matches at section-header, type-name, value-name, or module-name position depending on expansion shape.
- **Identifier + body-capturing form** — a keyword with a following identifier and body (e.g., `alias record Name:`). Matches at declaration position.
- **Parameterized signature** — an identifier with generic parameters (e.g., `alias StringDict<V>:`). Matches at type position.
- **Delimited pattern** — brackets or braces around a match variable (e.g., `alias [<items>]:`). Matches at expression or type position depending on `in` marker.
- **Infix pattern** — two operand slots around a fixed token (e.g., `alias <lhs> ..< <rhs>`). Matches at expression position.
- **Prefix / suffix pattern** — operator token before / after an operand slot (e.g., `alias <T> ?`). Matches at expression or type position.
- **String-embedded pattern** — a pattern inside string literal context (e.g., `alias ${<expr>}`). Matches within string literals when interpolation is active.

Position markers (`in <position>`) disambiguate when the pattern shape could match multiple positions. Precedence and associativity apply to infix, prefix, and suffix patterns. Guards (`where <var> is <TypePattern>`) narrow the match — critical for distinguishing patterns like `{<items>}` (Set) from `{<pairs>}` (Map).

#### 4.16.1 The Signature Pattern Language

Alias signatures use a small pattern language. Names in `<>` are **match variables** — bound to matched syntax and referenceable in the expansion.

**Pattern forms:**

| Pattern | Matches |
|---|---|
| `name` | The literal identifier `name` at its syntactic position |
| `<var>` | A single expression, type, identifier, or other syntactic unit |
| `<var>` in a sequence context | A comma-separated list, bound as a sequence |
| `<key>: <value>` in a sequence context | A key-value pair; sequence of these = list of pairs |
| `[<items>]` | Bracketed comma-separated content |
| `{<items>}` | Brace-wrapped comma-separated content (Set-shaped) |
| `{<pairs>}` | Brace-wrapped comma-separated key-value pairs (Map-shaped) |
| `(<items>)` | Paren-wrapped comma-separated content (tuple-shaped) |
| `<lhs> op <rhs>` | Infix binary operator pattern (`op` is a fixed token) |
| `<expr> op` | Suffix unary operator pattern |
| `op <expr>` | Prefix unary operator pattern |
| `keyword <name>` | Keyword followed by identifier (declaration form) |
| `${<expr>}` | String-embedded pattern; matches inside string literals |

**Position markers** (`in <position>`):

- `expression_position` — the alias applies where an expression is expected
- `type_position` — where a type is expected
- `section_header_position` — where a section header appears
- `declaration_position` — at top-level declaration keyword position
- `string_interpolation_position` — inside string literals during interpolation scanning

Position is inferred where the signature shape unambiguously implies it. `alias record Name:` implies declaration position; `alias <lhs> ..< <rhs>` implies expression position (infix operators don't appear at type position). Explicit markers are used when the pattern shape appears in multiple positions — e.g., `[<items>]` is ambiguous between expression-position (Array literal) and type-position (`Array<T>` sugar), so the two aliases carry explicit `in expression_position` and `in type_position` respectively.

**Precedence and associativity** apply to infix, prefix, and suffix patterns:

- `precedence: N` — integer; higher binds tighter. Prelude operators use spec-committed precedence values (see §4.20 and §3.9).
- `associativity: left | right | none` — for infix operators. Prefix and suffix are always right-associative and left-associative respectively; the declaration is a documentation hint.

Precedence integers are compared across all in-scope aliases; the compiler resolves conflicting precedence declarations at name-resolution time (§4.16.13).

**Guards** narrow a match:

```
alias {<items>} in expression_position where items is [Expression]:
    Set.from_literal(items)

alias {<pairs>} in expression_position where pairs is [KeyValuePair]:
    Map.from_literal(pairs)
```

Guard predicates test the shape of matched content: `is [Expression]`, `is [KeyValuePair]`, `is value_type`, `is reference_type`, `is Numeric`, etc. Predicates are drawn from the type system and metadata queries (§4.18) — users cannot define new predicates in v1.

#### 4.16.2 The `...` Body Placeholder

When an alias's use site carries an indented body — that is, when the alias appears with `:` and following block — the `...` placeholder in the expansion template is bound to that block's content. The compiler substitutes the block verbatim.

For aliases whose expansion is a single line with no body (type / value / module aliases), `...` does not appear and the use site has no body:

```
alias UserID: UInt64                          # no body form
```

For aliases whose expansion wraps a body (section-composition aliases, class-kind aliases), `...` marks where the body goes:

```
alias data:                                   # body form
    runtime immutable identity:
        ...                                   # body from use site
```

`...` is a single hole. Aliases requiring multiple named body captures (a hypothetical future need not present in v1) would use named placeholders; v1 commits to `...` alone.

#### 4.16.3 Section-Composition Aliases

Name a composition of section primitives. The alias's expansion is another section header (possibly composed); the body passes through:

```
alias data:
    runtime immutable identity:
        ...

alias state:
    runtime mutable coordination:
        ...
```

Used in section headers:

```
class Order:
    data:              # expands to: runtime immutable identity:
        id: OrderID
```

The expanded form is what the compiler processes; `data` is the surface name.

#### 4.16.4 Class-Kind Aliases

Name a declaration keyword that expands to `class` plus prepended body content. The alias captures an identifier parameter (the class name written after the keyword) and the body:

```
alias record Name:
    class Name:
        metadata:
            kind: record
        ...

alias enum Name:
    class Name:
        metadata:
            kind: enum
        ...

alias protocol Name:
    class Name:
        metadata:
            kind: protocol
        ...

alias section Name:                # `section Foo:` sugar; equivalent to `section class Foo:`
    class Name:
        metadata:
            kind: section
        ...
```

Used at declaration position:

```
record Point:                       # expands to: class Point: metadata: kind: record; ...body
    x: Int
    y: Int
```

Users may define their own class-kind aliases for domain-specific declaration sugar. User-defined aliases require the `@` marker at both declaration and use (§4.16.16):

```
alias @entity Name:
    class Name:
        metadata:
            kind: regular
            ecs_role: entity
        ...

# Usage:
@entity Player:
    position: @Vec3
    velocity: @Vec3
```

The four language-shipped class-kind aliases (`record`, `enum`, `protocol`, `section`) live in the prelude and are unmarked (§4.20). Users cannot redefine them (name conflict per §4.16.12).

**On user-defined declaration sugar.** Earlier drafts (v0.6 §4.19) rejected user-defined class-kind sugar with a legibility argument: project-local declaration keywords fragment reading conventions. v0.7.2 accepts them, because the same argument would forbid user-defined section-composition, type, value, and module aliases — which the language already permits. The legibility burden shifts to tooling: IDEs and code-review tools MUST surface the alias expansion on hover, in inline decorations, and via goto-definition. When reading unfamiliar code, users query the alias to see what it expands to. Stdlib-shipped aliases are the authoritative "standard" sugar; project aliases are domain conventions users opt into by importing the module that declares them.

#### 4.16.5 Type-Expression Aliases

Delimited or suffix patterns at type position, matching structural shapes of composite types. These aliases (shipped in the prelude, §4.20) are what makes `T?`, `[T]`, `{K:V}`, `(A,B) -> C` legal Corvid type expressions — the language grammar itself only knows `identifier<generic_args>` for types; every richer shape is an alias.

**Delimited type-expression aliases:**

```
alias [<T>] in type_position:
    Array<T>

alias {<K>: <V>} in type_position:
    Map<K, V>

alias {<T>} in type_position:
    Set<T>

alias (<Args>) -> <R> in type_position:
    Callable<(Args,), R>
```

Match variables in `<>` at type position bind to type expressions. The `Args` in `(<Args>) -> <R>` binds to a comma-separated list of types.

**Suffix type-expression aliases** — for nullability markers:

```
alias <T> ? in type_position where T is value_type:
    Optional<T>

alias <T> ! in type_position where T is reference_type:
    T with invariant: != nil
```

Guards restrict `T?` to value types (matching §3.6.1's rule that reference types are nil-allowed by default, so `?` on a reference type is redundant) and `T!` to reference types (value types can't be nil, so the invariant is trivially true).

#### 4.16.6 Expression-Position Literal Aliases

Delimited patterns at expression position, producing collection values. The prelude ships literal aliases that lower to conformance-protocol dispatch — see §3.10 for the `ExpressibleAsArrayLiteral` / `ExpressibleAsMapLiteral` / `ExpressibleAsSetLiteral` protocols.

```
alias [<items>] in expression_position:
    Array.from_literal(items)

alias {<pairs>} in expression_position where pairs is [KeyValuePair]:
    Map.from_literal(pairs)

alias {<items>} in expression_position where items is [Expression]:
    Set.from_literal(items)
```

Guards disambiguate `{...}` between Map and Set — Map when content is key-value pairs, Set otherwise. Empty `{}` is ambiguous by content; the prelude commits to `{}` → empty Map (matching Swift/Python conventions).

Users may define their own literal-producing aliases for domain-specific types. User-defined aliases require the `@` marker (§4.16.16):

```
alias @v[<x>, <y>, <z>] in expression_position:
    Vec3(x: x, y: y, z: z)

# Usage:
position = @v[1.0, 2.0, 3.0]
```

#### 4.16.7 Infix Operator Aliases

Two-operand patterns around a fixed operator token, with precedence and associativity. The prelude ships range operators, bitwise operators, and nil-coalescing as infix aliases:

```
# Ranges
alias <lhs> ..< <rhs> precedence: 5, associativity: none in expression_position:
    Range.half_open(from: lhs, to: rhs)

alias <lhs> ... <rhs> precedence: 5, associativity: none in expression_position:
    Range.closed(from: lhs, to: rhs)

# Bitwise (precedence values match C99 — §3.9, §4.20)
alias <lhs> & <rhs> precedence: 8, associativity: left in expression_position:
    Numeric.bitand(lhs, rhs)

alias <lhs> | <rhs> precedence: 10, associativity: left in expression_position:
    Numeric.bitor(lhs, rhs)

# Nil-coalescing
alias <lhs> ?? <rhs> precedence: 13, associativity: right in expression_position:
    Optional.coalesce(lhs, rhs)
```

Precedence integers are drawn from the prelude's precedence-value scheme (documented in §4.20). Users defining their own infix operators MUST declare precedence explicitly; there is no inferred default.

**Prefix and suffix operator aliases** follow the same shape:

```
alias ~ <expr> precedence: 2 in expression_position:
    Numeric.bitnot(expr)

alias not <expr> precedence: 2 in expression_position:
    Bool.not(expr)         # `not` also declared in bootstrap; prelude alias documents behavior
```

Prefix and suffix aliases carry precedence for parser disambiguation but their associativity is fixed (prefix = right, suffix = left).

**User-defined infix, prefix, and suffix operators require `@` prefixed to the operator token** (§4.16.16). The `@` is part of the operator glyph itself, both in the declaration signature and at every use site:

```
# User-defined pipe in a project that wants it despite the prelude declining to ship it:
alias <lhs> @|> <rhs> precedence: 100, associativity: left in expression_position:
    Callable.apply(rhs, lhs)

# Usage:
result = data @|> filter_valid @|> normalize

# User-defined prefix:
alias @! <expr> precedence: 2 in expression_position:
    MyLogic.strong_negate(expr)

# Usage:
if @!condition: ...
```

Bootstrap operators (`+`, `-`, `*`, `/`, `%`, `<`, `<=`, `>`, `>=`, `==`, `!=`, `and`, `or`, `not`) and prelude-declared operators (`&`, `|`, `^`, `~`, `<<`, `>>`, `??`, `..<`, `...`) are unmarked. Only user-defined operators carry `@`.

**`|>` is deliberately not in the prelude.** Method chaining (`x.f().g().h()`) covers every case pipe would in Corvid's class-based, universally-dispatched, openly-extensible model; shipping `|>` would create two idioms for the same operation and pull users toward free functions instead of extension methods (§4.20.3). Projects that want pipe can declare it locally as shown above; the `@` cost per use makes the project-local nature visible.

#### 4.16.8 String Interpolation Aliases

Patterns embedded inside string literals. The base string literal syntax is bootstrap (§2.7); interpolation is a prelude alias that the string scanner consults when parsing string contents:

```
alias ${<expr>} in string_interpolation_position:
    String.interpolate_expr(expr)
```

When the base string scanner encounters `$`, it checks in-scope aliases at `string_interpolation_position`. If a match applies (in the prelude case, `${...}` wrapping an expression), the scanner captures the expression, applies the alias expansion, and continues scanning the string.

Users may define domain-specific interpolation forms. User interpolation aliases require the `@` marker at declaration and inside the string at every use site (§4.16.16):

```
alias @%<name>% in string_interpolation_position:
    Config.lookup(name)          # `@%database.url%` looks up config values

alias @mention<user_id> in string_interpolation_position:
    User.mention(user_id)        # `@mention42` renders as a mention
```

Inside a string literal:

```
let msg = "Connecting to @%database.url% for @mention42"
```

Users adding new interpolation patterns face heightened legibility responsibility — string literals are read densely and unexpected interpolation forms confuse readers. The `@` marker inside strings is a hard visual cue that a project-local interpolation form is at work; tooling SHOULD highlight custom interpolation patterns distinctly on top of that.

#### 4.16.9 Type Aliases

Identifier signatures with type-expression expansions, optionally parameterized. No body:

```
alias Order: com.example.orders.Order
alias StringDict<V>: Dict<String, V>
alias Callback<T>: (T, Error) -> Void
```

Used in type positions:

```
class MyController:
    state:
        current_order: Order              # expands to com.example.orders.Order
        cache: StringDict<CachedValue>    # expands to Dict<String, CachedValue>
        handler: Callback<Receipt>        # expands to (Receipt, Error) -> Void
```

#### 4.16.10 Value Aliases

Name a compile-time constant expression. No body:

```
alias BUFFER_SIZE: 4096
alias DEFAULT_TIMEOUT_MS: 30_000
alias GREETING: "Hello, world"
```

Used in expression positions:

```
data:
    buffer: [Byte] = [Byte].filled(size: BUFFER_SIZE)

function timeout() -> Duration:
    return Duration.milliseconds(DEFAULT_TIMEOUT_MS)
```

Value aliases must be compile-time constants. Runtime-computed values are not permissible in aliases.

#### 4.16.11 Module Aliases

Shorten a qualified module path. No body:

```
alias orders: com.example.orders
alias core: com.example.core
```

Used to qualify names without writing the full module path:

```
class MyController:
    state:
        client: core.HttpClient
        current: orders.Order
```

#### 4.16.12 Common Rules

All alias shapes share these properties:

- **Compile-time substitution.** The compiler replaces the alias use with its expansion during processing, then re-parses under normal Corvid rules.
- **Recursive expansion.** Aliases may reference other aliases; the compiler expands until no aliases remain.
- **Circular aliases are errors.** Cycles are detected at compile time.
- **No runtime effect.** Aliases exist only during compilation. There is no runtime cost.
- **Parameterizable.** All shapes support type, effect, and value parameters.
- **Exportable.** Aliases marked `expose` are visible to importers of the containing module.
- **User aliases live in a distinct namespace.** User-defined aliases require the `@` marker at both declaration signature and every use site (§4.16.16). `@Name` is not the same name as `Name` — the alias table and the ordinary name-resolution table are disjoint. This means user aliases never collide with imports, locals, top-level declarations, or prelude names; there is nothing to shadow and nothing to be shadowed by. Two `@`-marked user aliases with the same signature shape in the same scope conflict with each other (compile error).
- **Post-expansion validation.** Section legality, class-body legality, and other structural rules are checked on the expanded form. Error messages point to the user's original source location with a note showing the alias expansion.
- **Prelude aliases are protected and unmarked.** Aliases shipped in the prelude (§4.20) are unmarked in signature and use — they are committed surface syntax that users read as language. They cannot be overridden or shadowed by user definitions. Users cannot declare an unmarked alias (a bare `alias record Name: ...` without `@` is a compile error unless it matches a prelude entry, which is itself reserved).
- **User-defined aliases are lexically scoped.** Visible in the module they're declared in and modules that import them. Not global.

The complete list of prelude aliases — the ones the language ships as its standard surface syntax — is in §4.20.

Users may define aliases at module level (all shapes) or inside class bodies (section-composition aliases only, for local naming).

#### 4.16.13 Semantics of Alias Composition

Aliases behave exactly as their expansion. Composing an alias with additional primitives, modifiers, or references is equivalent to composing the expansion with those elements — no override precedence, no warnings.

```
data expose:    # expands to: runtime immutable identity expose
                # composes cleanly — visibility is orthogonal to the alias.
```

Users who need a variant of a common alias define their own alias rather than override at usage sites. This keeps aliases transparent shortcuts, not magical substitutions with hidden rules.

#### 4.16.14 Diagnostic Quality

When alias expansion produces an error, the compiler shows both the alias's expansion and the additional context, so users see exactly what went wrong:

```
error: contradictory composition — `immutable` and `mutable` cannot both apply
  --> Order.cor:14:5
   |
14 |     data mutable:
   |     ^^^^^^^^^^^^^
   |
note: `data` expands to `runtime immutable identity`
note: composing with `mutable` produces `runtime immutable identity mutable`
help: define a specialized alias for this pattern:
      alias data_only:
          runtime immutable identity:
              ...
```

For class-kind aliases, expansion errors reference both the user's declaration and the alias definition:

```
error: section `state:` not permitted on `kind: record`
  --> Point.cor:8:5
   |
 8 |     state:
   |     ^^^^^
   |
note: `record` expands to `class ... metadata: kind: record` (see prelude)
note: kind: record forbids state:; use `class Point:` explicitly if per-instance mutable state is required
```

#### 4.16.15 Parser and Name Resolution

Aliases extend the base grammar. The compiler runs a **two-pass compilation** strategy:

**Pass 1 — bootstrap parse + alias collection.** The compiler parses each source using only the bootstrap grammar (§10) — enough to recognize declarations, section headers, and alias definitions but NOT enough to parse type-expression sugars, collection literals, or user-defined operators. It gathers all alias declarations from the prelude, imported modules, and the current source into a resolution table indexed by position and pattern shape.

**Pass 2 — extended parse.** The compiler re-parses each source with the extended grammar (bootstrap + all in-scope aliases). Signature patterns become grammar productions at their declared position and precedence. The parser tries alias productions before base productions at ambiguous points.

Alias tables cache across incremental compilations — Pass 1 runs only for sources whose alias declarations changed. The added cost is negligible for the incremental compiler (§4.18).

**Alias resolution is lexical.** Aliases visible at the use site are the ones considered. An alias imported from module `M` is visible in sources that import `M`. Prelude aliases are visible everywhere without import.

**Precedence conflicts.** When multiple aliases in scope declare infix/prefix/suffix patterns at overlapping precedence, the compiler uses the following rules:

1. Prelude precedence values are the reference. User-defined aliases MUST NOT use the exact precedence of a prelude operator without explicit `override` markers (reserved for future spec revision).
2. User-defined operator aliases use precedence values in a designated user range (documented in §4.20).
3. If two in-scope user aliases declare identical precedence with different associativity, the compiler emits a warning and picks left-associativity as the tie-breaker.
4. If precedence values conflict such that an expression parses ambiguously, the compiler emits an error at the use site with both alias definitions cited.

**Pattern shape conflicts.** When two aliases declare the same pattern shape at the same position (e.g., two aliases claiming `[<items>]` in expression_position), the compiler emits a name conflict error at the second declaration. This applies whether both aliases come from user code, or one from user code and one from an import.

**Base grammar can always disambiguate.** The bootstrap grammar (§10) is always parseable without aliases. When alias resolution fails (missing alias, ambiguity, expansion error), the compiler falls back to the bootstrap grammar interpretation with a diagnostic.

#### 4.16.16 The `@` Marker for User-Defined Aliases

**Rule.** Every user-defined alias — in every position — requires the `@` marker prefixed to its trigger at both the declaration signature and every use site. Prelude aliases (§4.20) are unmarked in both declaration and use; they are committed surface syntax.

**Positions and forms.** The `@` prefix attaches to whatever the trigger token is:

| Position | Prelude form (unmarked) | User form (`@`-marked) |
|---|---|---|
| Section-composition | `alias data:` → use `data:` | `alias @audit_log:` → use `@audit_log:` |
| Class-kind | `alias record Name:` → use `record Foo:` | `alias @entity Name:` → use `@entity Player:` |
| Type-expression delimited | `alias [<T>] in type_position:` → use `[Int]` | `alias @Bag<<T>> in type_position:` → use `@Bag<Int>` |
| Type-expression suffix | `alias <T> ?` → use `T?` | `alias <T> @!!` → use `T @!!` |
| Expression-position delimited | `alias [<items>] in expression_position:` → use `[1,2,3]` | `alias @v[<x>,<y>,<z>] in expression_position:` → use `@v[1,2,3]` |
| Infix operator | `alias <l> ..< <r> ...` → use `0 ..< n` | `alias <l> @\|> <r> ...` → use `data @\|> f` |
| Prefix operator | `alias ~ <e> ...` → use `~x` | `alias @! <e> ...` → use `@!x` |
| String interpolation | `alias ${<expr>} in string_interpolation_position:` → use `"${x}"` | `alias @%<name>% in string_interpolation_position:` → use `"@%config.host%"` |
| Type alias (identifier) | *(prelude does not ship type aliases)* | `alias @Vec = std.math.Vector3` → use `@Vec` |
| Value alias (identifier) | *(prelude does not ship value aliases)* | `alias @MAX_CONN = 1024` → use `@MAX_CONN` |
| Module alias (identifier) | *(prelude does not ship module aliases)* | `alias @orders = com.example.orders` → use `@orders.Order` |

The `@` is part of the trigger's syntactic form. For operators, `@` fuses with the operator glyph (e.g., `@|>`, `@!`). For identifiers and delimiters, `@` prefixes the identifier or the opening delimiter. Inside string literals, `@` marks the start of an interpolation trigger.

**Why the marker.**

- **Reader legibility.** A reader sees `@Vec` or `@[…]` and knows immediately that the meaning is defined by a project-local `alias` declaration — grep for `alias @Vec` finds it. A reader sees `Vec` or `[…]` and knows the meaning comes from imports, locals, or prelude — normal name resolution applies. This is a stable, binary signal at every use site with no lookup required to know which world you are in.
- **Namespace separation.** User aliases and ordinary names live in disjoint namespaces (§4.16.12). `@Vec` never collides with an imported `Vec`, a local `Vec`, or a class `Vec`. There is no shadowing, no resolution-order rule to memorize, no accidental capture.
- **Parser simplification.** The bootstrap parser recognizes `@` as an alias-trigger sigil. Every alias use is self-flagged; the alias table is a single dedicated lookup path consulted only when `@` appears. The two-pass compilation strategy (§4.16.15) is preserved in structure, but Pass 2's alias-recognition fan-out shrinks to one deterministic escape rather than probing every identifier / delimiter / operator position against the alias table.

**Why prelude is unmarked.**

The prelude is Corvid's committed surface syntax (§4.20). Users read `record Foo:`, `[1, 2, 3]`, `"hello ${name}"`, `0 ..< n` as language, not as sugar-that-happens-to-be-implemented-as-alias. The `@` marker would contradict that framing at every use site and would impose per-character noise on constructs that are already Corvid's basic vocabulary. The prelude carve-out is intentional; it reinforces the boundary from the other direction — unmarked = language, marked = project.

**Why substitution aliases also require `@`.**

Substitution aliases (`alias @Vec = std.math.Vector3`) could ergonomically get away without `@` — they are purely identifier-for-identifier renames and the parser doesn't strictly need a disambiguator for them. But the reasoning that argues against the marker (brevity) is not the reasoning the marker earns its keep on. The wins that justify `@` are provenance visibility and namespace separation, and those wins apply just as strongly to `@Vec` as to `@[…]`. A rule that carves substitution aliases out of the marker discipline would ship two rules where one suffices, and would let the substitution alias name secretly collide with an import — exactly the failure mode the discipline prevents. Uniform `@` costs one character per use and buys a globally consistent legibility signal; the trade favors uniformity.

**Why bare bootstrap operators stay unmarked.**

Bootstrap operators (§3.9, §10) — `+`, `-`, `*`, `/`, `%`, `<`, `<=`, `>`, `>=`, `==`, `!=`, `and`, `or`, `not` — are compiler-committed, not aliases. They carry no `@`. Prelude-declared operators (`&`, `|`, `^`, `~`, `<<`, `>>`, `??`, `..<`, `...`) are aliases in principle but unmarked in practice because they live in the prelude. Only user-defined operators use `@` — and the `@` fuses with the glyph (e.g., `@|>`), which conveniently keeps the alias namespace disjoint from the bootstrap/prelude operator table.

**Interaction with `expose`.**

Whether an alias is `expose`d controls cross-module visibility, orthogonal to `@`. The declaration form is `expose alias @Name ...`; the use form is `@Name`. Marking never varies with visibility.

**Legibility policy.**

The `@` marker is the language-level answer to "is this project-specific surface syntax?" Style guides for individual projects SHOULD still exercise restraint on how many user aliases they introduce — one hundred `@` markers per file is legible in the "look up the declaration" sense but exhausting in the "read the code" sense. Prefer methods and extensions where they suffice; reach for `@`-marked aliases when a genuine syntactic gain is available.

#### 4.16.17 Raw-Content Prefix-Literal Aliases

An alias whose signature has the shape `name"<content>"` (identifier followed immediately by a double-quoted content region, no intervening whitespace) is a **prefix-literal alias**. Its content is captured as a single string parameter and passed to the alias body's constructor call. The captured content may declare either **cooked** semantics (default — escape processing per §2.7) or **raw** semantics (backslashes and other characters pass through unmodified).

**Declaration form.**

```
# Cooked (default) — escape processing per §2.7:
alias @cooked"<content>" = SomeConstructor(<content>)

# Raw — content passes through unchanged:
alias @regex"<content>" raw = Regex(<content>)
alias @glob"<content>"  raw = Glob(<content>)
```

The `raw` marker sits between the signature and the `=` body. Without it, escapes are processed at parse time — `\n` becomes a newline, `\\` becomes a single backslash, etc. With it, the content between the quotes is captured verbatim: `regex"^\d+"` passes the four characters `^`, `\`, `d`, `+` to `Regex`, not a parse-time-escaped variant.

**Prelude prefix-literal aliases (§4.20.1 additions):**

```
alias regex"<content>"   raw = Regex(<content>)          # §3.10.9.3
alias glob"<content>"    raw = Glob(<content>)           # §3.10.9.3
alias literal"<content>" raw = Literal(<content>)        # §3.10.9.3
```

Prelude aliases are unmarked (no `@`) per §4.16.16.

**Composition with `@` marker.** User-defined prefix-literal aliases follow the standard `@` discipline:

```
alias @peg"<content>"   raw = PegParser(<content>)
alias @fuzzy"<content>" raw = FuzzyPattern(<content>)

# Use sites:
let parser = peg"expr <- term ('+' term)*"               # prelude alias — no @
let m = @peg"expr <- ..."                                # user alias — @ required at use site
                                                          # (wait — user's own alias, marked)
```

Correction on the above: user prefix-literal aliases require `@` at both declaration and use (per §4.16.16 uniformity). The `regex"..."` / `glob"..."` / `literal"..."` forms without `@` are ship-with-language surface syntax; anything else takes `@`.

**Why raw is opt-in, not default.** Cooked content is the more common case — string interpolation, config values, human-readable messages. Raw content is specific to pattern languages (regex, PEG, path globs, format strings with their own escape rules) where the constructor's grammar overlaps with the host language's escape syntax. Making cooked the default preserves consistency with §2.7 string literals; the `raw` marker makes the deviation visible at the alias declaration site.

**Rejected alternative.** A separate `r"..."` prefix syntax (Python-style) would require grammar-level recognition of `r` as a special identifier — a per-language keyword. The alias-flag approach keeps everything in §4.16's alias mechanism with one added flag; no new grammar. Consistent with the design invariant: prefer library-level extensibility to new grammar features.

**Diagnostic on raw-mode escape attempts.** With `raw` in effect, backslash-anything is passed through unchanged. There is no "invalid escape" diagnostic — the constructor receives the raw text and is responsible for its own parsing errors. `Regex("^\q")` reports the invalid regex escape, not a Corvid-side escape error.

### 4.17 Metaprogramming Boundaries

Corvid has **two orthogonal metaprogramming mechanisms**:

- **Sections (§4.1-§4.15)** — body-level metaprogramming. A section class processes the content of its indented block: generates methods and fields on the host class, validates the block against constraints, wraps or transforms declarations.
- **Aliases (§4.16)** — syntactic metaprogramming. An alias declaration extends the language grammar with new signature patterns at specified syntactic positions. Every user-facing sugar in the language is a stdlib alias in the prelude (§4.20).

The two mechanisms cover different domains — sections handle "what happens inside a block," aliases handle "what surface syntax means" — and compose cleanly. A user-defined class-kind alias (`entity Name:`) expands to `class Name: metadata: kind: regular, ecs_role: entity; ...body`, and the body is then processed by whichever sections apply to the resulting class.

**Explicit boundaries — what sections CAN do:**

- Emit code at declared extension points (accessors, lifecycle hooks, method wrappers).
- Transform block contents (statements, declarations, AST fragments).
- Validate block contents against constraints.
- Substitute themselves for other sections via expansion.
- Have runtime state per host instance.
- Have runtime methods.
- Have compile-time parameters (type, effect, value).
- Carry invariants over their own state.

**Explicit boundaries — what sections CANNOT do:**

- **No higher-order sections.** A section cannot take another section as a parameter. Section parameters are types, effects, or values — not sections. (Syntactic space reserved for future extension.)
- **No section-generates-section.** Section generators produce methods and fields on the host class; they cannot produce new section classes.
- **No cross-section inspection.** A section can see its own contents and the host class's public interface; not other sections' internals.
- **No arbitrary compile-time computation.** Sections compute over their declared inputs (fields, block contents, parameters). There is no general-purpose compile-time execution environment.
- **No section-defines-language-primitive.** Sections cannot introduce new class kinds, protocol kinds, or fundamental language forms. New surface syntax is the aliases' domain.

**Explicit boundaries — what aliases CAN do:**

- Define new syntactic patterns at declared positions (declaration, section-header, type, expression, string-interpolation).
- Bind match variables to matched syntax and reference them in the expansion.
- Declare precedence and associativity for infix/prefix/suffix operators.
- Constrain matches with guards (`where <var> is <TypePattern>`).
- Ship as stdlib prelude (spec-committed) or module-scoped user extension.

**Explicit boundaries — what aliases CANNOT do:**

- **No arbitrary compile-time computation.** Aliases substitute syntax; they don't execute code. The expansion is a template, not a program.
- **No prelude override.** Users cannot redefine or shadow prelude aliases. The standard surface syntax is stable.
- **No cross-alias inspection.** An alias's expansion cannot query other alias definitions to reason about them.
- **No new position categories.** The five position markers (declaration, section-header, type, expression, string-interpolation) are the fixed set. New categories require spec-level extension.
- **No user-defined guard predicates.** Guards use spec-provided predicates (`is value_type`, `is [Expression]`, etc.); users cannot define new predicates in v1.

**Why these boundaries.**

Metaprogramming systems that fail — C++ templates evolving into template metaprogramming, Java reflection + annotations + AspectJ, Python descriptors + decorators + metaclasses — fail by accretion. Each new mechanism tries to fix limitations of the previous. Overlapping features multiply. The result is a system that's hard to reason about, hard to debug, and hard to remove features from.

Corvid's answer: **design the boundaries once**, ship the two mechanisms that fit within them, and refuse to expand incrementally. Future needs that don't fit within sections (body-level behavior) or aliases (surface syntax) require an explicit language-level decision, not a library workaround that stretches either mechanism.

**Grammar extensibility discipline.** Aliases extend the language grammar; the compiler parses `bootstrap + prelude + in-scope user aliases`. This makes the effective grammar user-configurable per module. The safeguards:

1. **Bootstrap grammar (§10) is fixed.** Users cannot change how identifiers, base literals, or control flow parse.
2. **Prelude is spec-committed.** Standard surface syntax is stable across projects.
3. **User aliases are lexically scoped.** Only visible in modules that declare or import them.
4. **Tooling MUST expose expansions.** IDEs surface alias expansion on hover, goto-definition, and inline decoration. Readers can always see what any syntactic form means by querying tools.
5. **Style discipline is cultural.** The language enables user-defined syntax; community style guides recommend restraint (domain-specific literals for domain projects; general libraries should not introduce novel syntax).

This is the Clojure/Racket tradeoff — power with discipline vs. restriction with safety — applied consistently with Corvid's other design choices (§1.2 intent-not-authority, §1.5 runtime-as-substrate).

**The bootstrap set** (what the compiler ships with hardcoded knowledge of):

- `Data` and `State` section classes (for use inside section class definitions).
- `Methods` section class (implicit container in class bodies).
- Member kinds: `generator`, `processor`, `validator`, `expander`, `function`, `constructor`, `destructor`.
- Block forms: `expands_to:`, `invariants:`, `parameters:`.

Every other section class, including all primitives and behavioral sections, is expressible in the bootstrap.

### 4.18 Class Metadata

Class metadata is a structural description of every class in a compilation unit — its fields, methods, section usages, contracts, invariants, provenance — generated at compile time and consumed by three separate systems:

- **Language server / IDE tooling** at authoring time (LSP protocol).
- **Runtime metadata queries** at program execution time.
- **External tools** (AI assistants, documentation generators, static analyzers) via manifest export.

All three consume the same underlying artifact. The compiler generates it once; each consumer reads it through an interface appropriate to its use case.

#### 4.18.1 Metadata Contents

For each class, the metadata artifact contains:

**Structural information:**
- Fully qualified class name.
- Module and source file.
- Superclass and protocol conformances (declared and inferred).
- Sections used on the class, with parameters if any.
- Fields per section: name, type, mutability, visibility, contribution flags.
- Methods: name, signature, effects, contracts (pre/postconditions).
- Class-level invariants.

**Generated content (from section describers):**
- Methods added by each section, attributed to the generating section.
- Fields added by each section (backing fields, section instance state).
- Section instance methods (what's available at `instance.sections.name`).
- Contracts inherited or synthesized.

**Provenance:**
- Source location of each declaration (file, line, column).
- Source location of each generated member, linking to the generator and the section usage.
- Version information (which section class version produced each generated member).

**Cross-references:**
- Modules this class depends on.
- Classes referenced in this class's field types or method signatures.
- Sections and section classes used.

#### 4.18.2 Consumer 1 — Language Server / IDE

The compiler operates in language-server mode by exposing an LSP interface backed by its incremental compilation state. When a client (VS Code, Zed, Neovim, Xcode) needs information about a class, it queries the compiler; the compiler returns metadata from its current state.

Standard LSP queries answered from metadata:
- **Hover** — type of an identifier, method signatures, contracts.
- **Completion** — methods available on an expression's type, including section instance methods.
- **Go-to-definition** — source location including generated members (jumps to the generator or the field declaration, per user configuration).
- **Find-references** — usages across the workspace.
- **Rename** — safe renames aware of generated code (renaming a field renames the section-generated accessors).
- **Diagnostic** — errors, warnings, contract violations detected at compile time.

Because metadata includes provenance, IDE features can distinguish user-written code from section-generated code, showing generated members with attribution ("added by channel section") and offering navigation to the generator source.

#### 4.18.3 Consumer 2 — Runtime Metadata Queries

At link time, metadata artifacts are loaded as data segments into the compiled binary. The runtime holds an in-memory representation queryable via a typed API:

```
# Class lookup
cls: Class = OrderProcessor.class_metadata
cls_by_name: Class? = Class.by_qualified_name("com.example.orders.OrderProcessor")
all_classes_conforming: [Class] = Class.all_conforming_to(OrderProcessor.protocol_metadata)

# Class structure
sections: [SectionMeta] = cls.sections()
data_fields: [FieldSpec] = cls.section("data").fields()
methods: [MethodMeta] = cls.methods()
invariants: [Invariant] = cls.invariants()

# Section instance access on a specific class instance
channel_instance: SectionInstance = instance.sections.channel
channel_methods: [MethodMeta] = channel_instance.available_methods()

# Method invocation via metadata
method_meta: MethodMeta = cls.method_named("process")
result: Any = method_meta.invoke(instance, [order])

# Contract introspection
conformed: [Protocol] = cls.conformed_protocols()
protocol_methods: [MethodMeta] = protocol.required_methods()
```

**Key property:** the runtime does not generate class structure at runtime. It exposes structure that was baked in at compile time (possibly in a different module, loaded from a different object file). Queries lookup structure; they never create it.

**Method invocation via `method_meta.invoke`** dispatches through the runtime's method table — the same mechanism as any dynamic message send. The method's implementation was compiled; metadata invocation is a typed wrapper over `objc_msgSend`.

**Dynamic method addition** (`class.add_method(selector, implementation)`) remains available as a runtime API for advanced use cases (mocking, tooling, categories loaded from dylibs). The `implementation` argument is a function pointer to compiled code; the runtime associates it with the class. This is class registration, not code generation.

**Explicit non-goals:**
- No runtime creation of new classes with novel field layouts.
- No runtime creation of new method implementations from source strings.
- No `eval` or equivalent.
- No arbitrary structural modification of instances after construction.

These are excluded not because they're impossible on libobjc2 (some are; some aren't) but because they violate the "class structure is compile-time-fixed" principle that makes metadata coherent.

#### 4.18.4 Consumer 3 — External Tools (AI-Manifest Export)

The compiler emits a JSON representation of the metadata as a build artifact (`ModuleName.cor-manifest.json`). This is the metadata in a language-agnostic format for external tools that don't speak LSP or don't want to embed a compiler.

Structure (illustrative):

```json
{
  "module": "com.example.orders",
  "classes": [
    {
      "name": "OrderProcessor",
      "qualified_name": "com.example.orders.OrderProcessor",
      "source_file": "OrderProcessor.cor",
      "source_line": 12,
      "superclass": null,
      "conforms_to": ["OrderProcessor"],
      "sections": {
        "data": {
          "fields": [
            {"name": "id", "type": "ProcessorID", "mutability": "immutable"}
          ]
        },
        "state": {
          "fields": [
            {"name": "pending", "type": "[Order]", "mutability": "mutable"}
          ]
        },
        "channel": {
          "wraps_fields": ["connection_state"],
          "generated_methods": [
            {"name": "set_connection_state", "signature": "(ConnectionState) -> Unit",
             "provenance": "Channel/field_setter"}
          ],
          "section_instance_methods": [
            {"name": "subscribe", "signature": "((String, Any, Any) -> Unit) -> Unit"}
          ]
        }
      },
      "methods": [
        {"name": "process", "signature": "(Order) async -> ProcessOutcome",
         "contracts": {"requires": ["order.is_valid"]}}
      ],
      "invariants": [
        "connection_state == .opened implies socket != nil"
      ]
    }
  ]
}
```

**Uses:**
- AI code assistants query the manifest to understand code structure without parsing Corvid source.
- Documentation generators produce API docs from the manifest.
- Static analyzers reason about types and contracts without integrating with the compiler.
- Cross-language tooling (e.g., generating TypeScript bindings for a web frontend calling into Corvid backend).

**Format guarantee:** the JSON schema is versioned and stable within a major version. Additive changes (new fields) are minor version bumps; removals or renames are major.

#### 4.18.5 Incremental Compilation

The compiler is architecturally incremental from Stage 1. It holds state between invocations: parsed ASTs, resolved types, expanded sections, generated code, produced metadata. When a source file changes, only affected units recompile.

**Consequences:**
- Language-server queries are cheap. When the user types a `.`, the LSP asks the compiler for available methods on the receiver's type; the compiler answers from cached metadata without re-parsing.
- Section expansion is cached. Changing a class's field list re-expands its sections; changing an unrelated class doesn't.
- Metadata artifacts are updated in place. Consumers see fresh data without full rebuilds.
- Batch compilation is a mode: `compile all sources fresh, no incremental state`. Same code paths, different scheduling.

**Compilation state persistence:**
- In-memory during a language-server session.
- On-disk cache for build systems that reuse state across invocations (analogous to Rust's incremental cache directory, Kotlin's `build/kotlin-classes/incrementalCache`).
- Invalidation: file mtime + content hash. Cross-file dependencies tracked explicitly.

**Stage 1 delivers:** incremental compilation for Corvid sources, LSP server, metadata artifact emission, AI-manifest export. All shipped together — the tooling story is not a Stage 2 concern.

#### 4.18.6 Class Metadata and Author Intent

Metadata surfaces all structural information regardless of visibility annotations. Private fields appear in metadata. Sealed methods appear. Section internal state appears.

This is consistent with the intent-not-authority premise (§1.3): visibility annotations express intent for consumers to respect; they do not gate runtime access. Metadata queries return everything the compiler knows.

Consumers of metadata are expected to respect intent even though the compiler doesn't enforce. An LSP might hide private members from completion by default; a debugger shows them. Both read the same metadata; each uses it appropriately for its context.

### 4.19 Class Kinds (Retired as Separate Section)

*As of v0.7.2, class-kind sugar is unified under the alias mechanism (§4.16.4). The four class-kind keywords (`record`, `enum`, `protocol`, `section`) are stdlib alias declarations shipped in the prelude (§4.20). Body-level semantics of each kind — permitted sections, generated members, raw-value handling for enum, protocol conformance — are documented in §4.10.7 (kind section classes). This subsection is retained as a redirect for readers following older cross-references.*

**Retired keywords** (v0.5 → v0.6): `struct` and `enumeration`. Their v0.5 semantics are recovered by `record` and `enum` respectively. Grammar §10 does not parse them.

**User-defined class-kind sugar** — as of v0.7.2, users may define their own declaration aliases (§4.16.4). Earlier drafts rejected this on legibility grounds; v0.7.2's revised position treats it consistently with user-defined section/type/value/module aliases. Tooling MUST surface the alias expansion.

### 4.20 The Prelude

The **prelude** is a set of alias declarations shipped with the language distribution. Users get these without imports; they are the standard surface syntax of Corvid. The prelude is spec-committed — any change to a prelude alias is a language-level breaking change subject to the same versioning discipline as changes to bootstrap grammar (§4.15, §7.11).

**Why the prelude is a language artifact, not a library.**

The bootstrap grammar (§10) is small — sufficient to parse alias declarations, class/function/module declarations, base literals, base operators, control flow, and sections. What users think of as "Corvid syntax" — `record Foo:`, `[1, 2, 3]`, `T?`, `x + y ?? default`, `"hello ${name}"` — is the bootstrap grammar plus the prelude. Without the prelude, source code that looks like ordinary Corvid would not parse. The prelude is therefore part of the language distribution and part of what the spec commits to.

Users can inspect every prelude alias to understand what standard syntax means. Users cannot override or shadow prelude aliases; the standard surface syntax is stable across projects.

#### 4.20.1 Prelude Contents

**Section-composition aliases:**

```
alias data:
    runtime immutable identity:
        ...

alias state:
    runtime mutable coordination:
        ...

alias static:
    compile_time immutable:
        ...

alias methods:
    runtime:
        ...
```

**Class-kind aliases:**

```
alias record Name:
    class Name:
        metadata: kind: record
        ...

alias enum Name:
    class Name:
        metadata: kind: enum
        ...

alias protocol Name:
    class Name:
        metadata: kind: protocol
        ...

alias section Name:
    class Name:
        metadata: kind: section
        ...
```

**Type-expression aliases:**

```
alias [<T>] in type_position:
    Array<T>

alias {<K>: <V>} in type_position:
    Map<K, V>

alias {<T>} in type_position:
    Set<T>

alias (<Args>) -> <R> in type_position:
    Callable<(Args,), R>

alias <T> ? in type_position where T is value_type:
    Optional<T>

alias <T> ! in type_position where T is reference_type:
    T with invariant: != nil
```

**Expression-position literal aliases:**

```
alias [<items>] in expression_position:
    Array.from_literal(items)

alias {<pairs>} in expression_position where pairs is [KeyValuePair]:
    Map.from_literal(pairs)

alias {<items>} in expression_position where items is [Expression]:
    Set.from_literal(items)
```

The empty `{}` literal is disambiguated to `Map.from_literal([])` by convention (matching Swift/Python).

**Range operator aliases:**

```
alias <lhs> ..< <rhs> precedence: 5, associativity: none in expression_position:
    Range.half_open(from: lhs, to: rhs)

alias <lhs> ... <rhs> precedence: 5, associativity: none in expression_position:
    Range.closed(from: lhs, to: rhs)
```

**Bitwise operator aliases** (precedence values from C99, §3.9):

```
alias <lhs> << <rhs> precedence: 5, associativity: left in expression_position:
    Numeric.shl(lhs, rhs)

alias <lhs> >> <rhs> precedence: 5, associativity: left in expression_position:
    Numeric.shr(lhs, rhs)

alias <lhs> & <rhs> precedence: 8, associativity: left in expression_position:
    Numeric.bitand(lhs, rhs)

alias <lhs> ^ <rhs> precedence: 9, associativity: left in expression_position:
    Numeric.bitxor(lhs, rhs)

alias <lhs> | <rhs> precedence: 10, associativity: left in expression_position:
    Numeric.bitor(lhs, rhs)

alias ~ <expr> precedence: 2 in expression_position:
    Numeric.bitnot(expr)
```

**Nil-coalescing alias:**

```
alias <lhs> ?? <rhs> precedence: 13, associativity: right in expression_position:
    Optional.coalesce(lhs, rhs)
```

**String interpolation alias:**

```
alias ${<expr>} in string_interpolation_position:
    String.interpolate_expr(expr)
```

**Control-flow alias (`for`):**

```
alias for <var> in <collection>: in expression_position:
    collection.iter().for_each { var -> ... }
```

`for` is not bootstrap grammar — it is a prelude alias that expands to iteration-method dispatch on any type conforming to `Iterable<T>` (§3.10.1). The `...` body-placeholder rule (§4.16.2) captures the loop body; the closure passed to `for_each` receives one element per iteration and executes the body with `var` bound to that element. Users write:

```
for entry in dict:
    process(key: entry.key, value: entry.value)
```

Which expands to:

```
dict.iter().for_each { entry ->
    process(key: entry.key, value: entry.value)
}
```

No language-level `for` production exists. `Iterable<T>` conformance is what makes a class iterable — collections, ranges, strings, and any user class can conform.

**Why `while` and `loop` remain bootstrap.** `while` and `loop` are language primitives, not prelude aliases. They stay in bootstrap because stdlib needs a primitive control-flow loop to *implement* iteration methods (`.for_each`, `.map`, `.filter`) without either infinite regress or a Tail Call Optimization commitment. `while cond: body` and `loop: body` are the low-level tools; user-facing code should prefer method dispatch on collections. See §4.20.3 for the discipline note.

#### 4.20.2 Precedence Value Scheme

Precedence integers used across the prelude follow the C99 precedence order, mapped to values 1-13:

| Precedence | Operators (prelude) |
|---|---|
| 1 | Member access `.`, call `()`, subscript `[]` (bootstrap) |
| 2 | Unary `-`, unary `not` (bootstrap), unary `~` (prelude) |
| 3 | `*`, `/`, `%` (bootstrap) |
| 4 | `+`, `-` binary (bootstrap) |
| 5 | `<<`, `>>` (prelude); `..<`, `...` (prelude) |
| 6 | `<`, `<=`, `>`, `>=` (bootstrap) |
| 7 | `==`, `!=` (bootstrap) |
| 8 | `&` (prelude) |
| 9 | `^` (prelude) |
| 10 | `\|` (prelude) |
| 11 | `and` (bootstrap) |
| 12 | `or` (bootstrap) |
| 13 | `??` (prelude) |
| 14 | Assignment `=` and compound forms (bootstrap; right-associative) |

Precedence values 1-14 are reserved for bootstrap and prelude operators. User-defined operator aliases MUST use precedence values 100+ (documented reserved range for user extensions).

#### 4.20.3 Prelude Discipline

Constraints on the prelude:

1. **Unmarked at declaration and use.** Prelude aliases carry no `@` marker (§4.16.16). They are Corvid's committed surface syntax and read as language, not as project-local sugar. The unmarked form is reserved for the prelude — users cannot declare an unmarked alias.
2. **No overrides.** Users cannot redefine prelude aliases. Attempting to declare an alias whose expansion would replace a prelude signature is a compile error.
3. **No shadowing.** Prelude aliases are always in scope; no import can hide them. User aliases live in a disjoint `@`-marked namespace and never collide with prelude names.
4. **Spec-committed.** Changes to prelude alias signatures or expansions are language-version-affecting changes subject to the same versioning discipline as bootstrap grammar changes.
5. **Minimal by design.** Aliases are added to the prelude only when their utility is universal across Corvid codebases AND when there is no cleaner way to express the same construct in Corvid's paradigm. Domain-specific sugar belongs in libraries (as `@`-marked user aliases), not the prelude.
6. **Paradigm-mismatch exclusions.** Some constructs common in adjacent languages are deliberately kept out of the prelude because they do not fit Corvid's class-based, universally-dispatched, openly-extensible model:
   - **`|>` (pipe operator).** Pipe threads values through free functions — a shape native to FP languages where data and behavior are separate. Corvid has universal method dispatch and cheap extensions/sections; the native pipeline shape is method chaining (`x.f().g().h()`), which is left-to-right, top-down, and integrated with tooling (autocomplete on `.`, jump-to-definition). Shipping `|>` would create two idioms for the same operation and pull users toward free functions instead of extension methods. Projects that genuinely want pipe can declare `alias <lhs> @|> <rhs> ...` locally; the `@` marker makes the project-local nature visible at every use site.

7. **Iteration boundary — `for` is prelude, `while` and `loop` are bootstrap.** `for x in y: body` is a prelude alias (§4.20.1) that expands to `y.iter().for_each { x -> body }` on any `Iterable<T>` conformer (§3.10.1). This is the class-based OOP model applied to iteration: iteration is a message send to the collection, not a language primitive. `while` and `loop` stay bootstrap because stdlib needs a primitive control-flow loop to *implement* iteration methods without infinite regress or requiring a Tail Call Optimization commitment. The discipline:
   - **User-facing code prefers method dispatch.** `.for_each`, `.map`, `.filter`, `.reduce`, `.find`, `.any`, `.all`. `for` sugar is legal and recommended for the common "run body per element" case because it reads cleanly; the underlying dispatch is the same.
   - **`while` and `loop` are the tools stdlib uses.** They are legal in user code too, but reaching for them signals either "I'm writing an iterator primitive" or "this algorithm genuinely doesn't fit `.for_each` shape." Both are legitimate; both are rare.
   - **No `.for_each` for `while`.** There is no `alias while` — condition-based looping doesn't naturally correspond to a method on a collection (there's no collection). If needed, a project can define `alias @while_true <cond>: <body>` locally expanding to a closure-based helper.

#### 4.20.4 User Extensions

Users define their own aliases at module level following the mechanism in §4.16. All user aliases carry the `@` marker at declaration signature and every use site (§4.16.16). Common patterns:

**Domain-specific literals:**

```
# In a graphics module
alias @v[<x>, <y>, <z>] in expression_position:
    Vec3(x: x, y: y, z: z)

# In a config module
alias @%<name>% in string_interpolation_position:
    Config.lookup(name)
```

Use sites:

```
position = @v[1.0, 2.0, 3.0]
message  = "Connecting to @%database.url%"
```

**Project-specific declaration keywords:**

```
# In an ECS framework
alias @entity Name:
    class Name:
        metadata: kind: regular, ecs_role: entity
        ...

alias @system Name:
    class Name:
        metadata: kind: regular, ecs_role: system
        ...
```

Use sites:

```
@entity Player:
    position: Vec3
    velocity: Vec3

@system Physics:
    function step(dt: Duration): ...
```

**Domain-specific operators:**

```
# In a quaternion library
alias <lhs> @** <rhs> precedence: 105, associativity: left in expression_position:
    Quaternion.multiply(lhs, rhs)

# In a project that wants pipe despite the prelude declining to ship it
alias <lhs> @|> <rhs> precedence: 100, associativity: left in expression_position:
    Callable.apply(rhs, lhs)
```

Use sites:

```
composed = a @** b @** c
result   = data @|> filter_valid @|> normalize
```

**Substitution aliases (type / value / module):**

```
alias @Vec = std.math.Vector3
alias @MAX_CONN = 1024
alias @orders = com.example.orders
```

Use sites:

```
position: @Vec = @Vec.zero
if active < @MAX_CONN: ...
current: @orders.Order = @orders.Order.new(...)
```

Legibility responsibility for user-defined syntax rests with the author. The `@` marker gives the reader an unambiguous signal that project-local surface is in play; style guides SHOULD still recommend restraint on how many aliases a project introduces. Prefer methods and extensions where they suffice; reach for `@`-marked aliases when a genuine syntactic gain is available.

---

## 5. Dispatch Model

### 5.1 Semantic Dispatch (Foundational)

All methods are semantically dynamically dispatchable at runtime. The runtime maintains method tables for every class. Any method can be observed, replaced, extended, or hooked via the runtime API. This is the ObjC runtime substrate carried forward without modification.

**Method table population is uniform across definition and extension.** A class's runtime method table is populated from (a) the original `class Foo:` body, (b) every visible `extend class Foo:` block per §3.3.2, and (c) inherited protocol defaults for structurally-conformed protocols per §3.4.4. At dispatch time there is no distinction between these sources — the method table entry for a selector points at a single implementation regardless of where the method was declared. Ambiguity across sources is detected at compile time (§3.3.2 `replace` for extension conflict; §3.4.4 diamond disambiguation for protocol defaults); by the time the runtime table exists, the resolution is unique.

### 5.2 Compilation Strategy

The compiler may lower a method call to direct dispatch or dynamic dispatch. The choice is a performance optimization; semantics are identical provided runtime state matches compile-time assumptions.

The compiler devirtualizes automatically wherever it can prove safety:
- Same-module calls where the target is unambiguous.
- Whole-module optimization when configured.
- Cross-module calls in Stage 3+ via deopt-backed optimistic compilation.

**No source-level dispatch annotations.** The current spec had `sealed:` and `dynamic:` as author-facing hints. Both are removed. Reasons:

- `sealed` claimed "no subclassing / no overriding" — a claim the runtime doesn't enforce. Categories, swizzling, and dynamic proxies all remain available regardless. The keyword named an unenforceable prohibition, which under §1.2 (intent-not-authority) is dishonest naming for what was really an optimization request.
- `dynamic` was meaningful only in contrast to `sealed`. Without sealed, everything is already dispatched dynamically at the semantic level; the keyword is redundant.

Devirtualization is the compiler's responsibility, not the author's declaration. Where authors want to communicate "this class isn't designed for extension," they write documentation comments.

### 5.3 Deopt Machinery

When the compiler emits an optimistic direct call and runtime state later diverges (a method is swizzled, a category adds a competing implementation, a subclass overrides), the runtime patches affected call sites back to dynamic dispatch. The user-visible semantics are preserved; the performance changes locally.

Deopt operates on individual call sites, not whole methods or classes. A method may be devirtualized at one call site and dispatched dynamically at another. The compiler's optimism at each site is independent.

### 5.4 Optimization Policy

Optimization policy (how aggressively to devirtualize, how much whole-program analysis to perform, how much deopt overhead to accept) is a build-tool concern, not a source-language concern. Users configure this via build flags or package manifest settings; source code doesn't carry annotations for it.

Rationale: optimization policy is a deployment decision, not a semantic property of the code. Development builds might prefer flexibility (all dynamic dispatch, no devirtualization); release builds might prefer speed (aggressive devirtualization with deopt backing). The same source code compiles both ways.

### 5.5 Guarantees Summary

| Property | Guaranteed |
|---|---|
| A user can always hook any method | Yes |
| A user can always add methods to any class | Yes |
| A user can always subclass any class | Yes |
| A user can always introspect any class or method | Yes |
| Devirtualization is preserved when no runtime modification occurs | Yes (v0.5+) |
| Runtime modification triggers deopt on affected call sites | Yes (v1.0+) |
| Compiled code is safe from author-declared "sealing" | N/A — no such annotation exists |
| Members marked non-exposed are unreachable | No — external access generates warnings, not errors |

### 5.6 Closures and Callable Values

A **closure** in Corvid is an anonymous class with a single `call` method. Closures are first-class values; they can be passed as arguments, returned from functions, stored in fields, and dispatched dynamically like any other class instance. The runtime representation at the ObjC interop boundary is an ObjC block; the compiler generates the block wrapper automatically.

Semantically, closures are not a separate construct — they inherit all the properties of the class-as-universal design (§6.1). Kind is `regular` (the default) for closures that need to escape their construction site; the compiler is free to specialize to `kind: record` or eliminate the class entirely when analysis proves the closure does not escape.

#### 5.6.1 Type Expression — `Callable<Args, Return>`

Function types (§3.2) desugar to the wrapper type `Callable<Args, Return>`:

```
handler: (Bytes) -> Int                 # sugar
handler: Callable<(Bytes,), Int>        # expanded

comparator: (String, String) -> Int     # sugar
comparator: Callable<(String, String), Int>  # expanded
```

`Callable<Args, Return>` is a standard-library type; `(...) -> R` is spec-committed sugar. Effects on the callable use the same `with` clause as method types (§3.5.3):

```
f: (Order) -> Receipt with async
f: Callable<(Order,), Receipt> with async
```

Failure is expressed via the return type — a callable that may fail returns a domain enum like `(Order) -> ProcessOutcome`, not `(Order) -> Receipt with throws`. See §3.5.4.

#### 5.6.2 Sugar Hierarchy for Closure Literals

**Explicit function-literal form** — full types, unambiguous.

```
array.map(function(x: Int) -> Int:
    return x + 1
)
```

**Arrow form** — inferred parameter and return types, single-line body.

```
array.map(x => x + 1)
array.sorted((a, b) => a.count > b.count)
first_upper = names.first(where: n => n.starts_with_uppercase())
```

Multi-parameter form uses parentheses on the left of `=>`. Zero-parameter form: `() => value`. Ignored parameter: `_ => 42`.

**Method reference** — pass an existing method by name.

```
class Processor:
    methods:
        function transform(self, x: Int) -> Int: return x * 2
        function transform_all(self):
            self.numbers.map(self.transform)   # method reference
```

Method references bind `self` at the reference site — `self.transform` is a `Callable<(Int,), Int>` with `self` already captured.

**Trailing-block form is not supported.** Swift's `array.map { $0 + 1 }` is not part of the sugar layer. Rationale: the arrow form is as terse without the parsing complexity of "closure body outside parens." If a hard-line preference emerges, it can be added later as additive sugar.

#### 5.6.3 Desugaring — Closure Literals to Anonymous Classes

Every closure literal desugars, at compile time, to an anonymous class whose only method is `call`. The compiler generates one class per unique closure body at a source location; different literals at different sites are different classes.

Sugar:
```
array.map(x => x + 1)
```

Desugared (illustrative; the anonymous class name is compiler-generated):

```
class __closure_at_L47:
    methods:
        function call(self, x: Int) -> Int:
            return x + 1

array.map(__closure_at_L47())
```

Closures with captured environments carry those captures as `data:` fields on the anonymous class:

```
# Source:
threshold = 5
filtered = array.filter(x => x > threshold)

# Desugared:
class __closure_at_L52:
    data:
        threshold: Int
    methods:
        function call(self, x: Int) -> Bool:
            return x > self.threshold

filtered = array.filter(__closure_at_L52(threshold: threshold))
```

Stateful closures (rare) use `state:` on the anonymous class:

```
# Source — counter closure:
counter = do:
    n: Int = 0
    () => do:
        n = n + 1
        n

# Desugared:
class __closure_at_L60:
    state:
        n: Int = 0
    methods:
        function call(self) -> Int:
            self.n = self.n + 1
            return self.n

counter = __closure_at_L60()
```

#### 5.6.4 Capture Rules

**Strong capture by default.** A closure captures any free variable it references, with a strong reference. This is the common case — the closure keeps its captured environment alive.

```
config = load_config()
handler = event => process(event, config)   # config captured strongly
# handler holds config alive as long as handler exists
```

**Explicit capture list for non-default capture modes.** Prefix the closure literal with `[weak name, unowned name, name: mode]` when a variable should be captured differently:

```
# Weak self capture — closure does not keep self alive:
subscription = channel.subscribe([weak self] event => self.handle(event))
# When self has been deallocated (weak → nil), the message send is a no-op (§3.6.1).
```

Multiple entries are comma-separated. Legal modes: `weak`, `unowned`, `copy` (for value types).

**Rules:**

1. **Non-escaping closures never need `[weak]`.** A closure passed to `map`/`filter`/`sorted`/etc. and consumed synchronously by the callee cannot outlive the current frame. The compiler recognizes non-escaping closures and elides the capture-list requirement.
2. **Escaping closures may.** A closure stored in a field, returned from a function, or passed to a callee that stores it (e.g., channel subscribers, delegates) can outlive its construction context. `[weak self]` is required to avoid retain cycles when the closure captures `self` and is stored on `self` transitively.
3. **The compiler warns on likely retain cycles.** If a closure captures `self` strongly AND is stored in a `state:` field of `self` (directly or through a channel subscription), the compiler emits a warning. Not an error — sometimes intentional — but flagged.

#### 5.6.5 Compilation and Runtime Cost

The compiler is free to specialize closure representations under the "as if" rule — semantic behavior is preserved, runtime shape is optimized:

| Situation | Runtime cost |
|---|---|
| Closure passed to an inlinable function (map/filter/sorted/etc.), no capture | Zero — inlined at call site |
| Closure with capture, inlinable call site | Captures unpacked into local vars; closure object elided |
| Escaping closure (stored, returned, passed to opaque callee) | One anonymous-class allocation + virtual `call` dispatch |
| Closure passed across ObjC boundary | Wrapped as an ObjC block; standard block calling convention |

Users write the sugar; the compiler chooses the representation. The `Callable<Args, Return>` wrapper type is what appears in type signatures; the concrete anonymous class is a compiler artifact.

### 5.7 Nil-Receiver Dispatch

Message sends on nil receivers are legal (§3.6.1). This section specifies how the runtime resolves them.

**Lookup order for a call `receiver.method(args)` when `receiver` is nil:**

1. **Class `on_nil` section (§4.10.9).** If the receiver's static type declares `on_nil:` with an entry for `method`, that entry's value is returned. No method body runs.
2. **Conformed protocols' `on_nil` sections.** If no class-level entry matches, walk the receiver type's conformed protocols in declaration order; the first protocol declaring `method` in its `on_nil` section supplies the return.
3. **Auto-lift default.** If no `on_nil` entry is found, the runtime returns nil, and the call-site expression type auto-lifts from `Return` to `Return?`. For methods returning `Unit`, the call is a silent no-op (expression type remains `Unit`).

**Class overrides protocol.** A class-level `on_nil` entry shadows the same entry inherited via protocol conformance. This mirrors normal method dispatch precedence.

**Multiple protocols supplying the same entry.** Ambiguity is a compile error at conformance-declaration time — the class must declare a class-level `on_nil` entry to disambiguate. The compiler surfaces which protocols conflicted and the offending method name.

**Codegen consequences.**

- If no `on_nil` entry exists anywhere in the receiver type's hierarchy for the called method, no runtime branch is generated. Message send goes through the ObjC runtime directly; runtime returns nil natively (no Corvid-generated branch).
- If an `on_nil` entry exists, the compiler generates a nil-check-and-substitute at the call site (fast path) or a runtime consultation (slow path, when dynamic dispatch precludes static resolution).
- Auto-lift is a type-system operation only, not runtime. The bytecode is identical to the non-nil case; the type checker adjusts the observed return type.

**Interaction with `!` methods.** A method declared to return `T!` (non-nil reference) on nil receiver returns nil regardless — but this observation triggers the `T!` invariant warning at the call site. Authors declaring `T!`-return methods should also declare `on_nil` entries to preserve the invariant on nil-receiver call sites.

**Example dispatch trace:**

```
protocol Collection:
    accepts count() -> Int
    accepts is_empty() -> Bool
    on_nil:
        count: 0
        is_empty: true

class UserList(Collection):
    accepts:
        entries: [User]
    methods:
        function count(self) -> Int:
            return self.entries.length
        function is_empty(self) -> Bool:
            return self.entries.length == 0
    on_nil:
        count: 0        # matches protocol; explicit for clarity
        # is_empty: not declared → inherits protocol's `true`

users: UserList = maybe_get_users()   # may be nil

n = users.count()        # nil → class on_nil returns 0. type: Int
empty = users.is_empty() # nil → protocol on_nil returns true. type: Bool
name = users.first_name  # no on_nil entry; auto-lift. type: String?
```

---

## 6. Memory Model

The memory model organizes around **class kinds** — variants of a single unified `class` construct differentiated by the `metadata: kind:` entry — plus a universal handle-based reference model for identity-typed classes, extent-based storage with tag-organized regions, and ARC as the reference lifetime mechanism. This chapter details each kind and how they compose.

### 6.1 Class Kinds

All user types are declared via `class`. The `metadata: kind:` section entry (§4.10.6) selects the variant. Five kinds are spec-defined:

| `kind:` value | Purpose | `state:` allowed? | Extent? | Semantics |
|---|---|---|---|---|
| `regular` (default) | Reference-typed identity object | Yes | Yes | Reference on assignment; dynamic dispatch; ARC-managed |
| `record` | Value-typed data record | No | No | Copy on assignment; structural equality; static dispatch |
| `enum` | Sealed sum type | No | No | Discriminated union of `cases:`; exhaustive matching |
| `protocol` | Named contract, no instantiation | N/A | N/A | Structural conformance; `accepts:` required, `methods:` defaults |
| `section` | §4 metaprogramming section-class | Per kind's own rules | No | Compile-time only, no runtime artifact |

Sugar keywords (`record`, `enum`, `protocol`, `section`) desugar losslessly to the corresponding `class + metadata: kind:` form (§4.11). Users typically write the sugar; tooling and generated code operate on the desugared form.

**The "no `state:`" restriction on record and enum is the source of their value semantics.** Without mutable state, there's nothing to share across bindings — value semantics falls out. Without runtime-varying behavior, methods can dispatch statically. Without runtime identity, no extent tracking is meaningful. Without shared references, no ARC is needed. The kind selector is the switch that opts in or out of all four properties together.

**Kind determines section legality.** The compiler validates that only sections legal for a class's kind appear in its body:
- `kind: protocol` + `state:` → error (protocols have no runtime state)
- `kind: record` + `state:` → error (records forbid mutable state)
- `kind: enum` + `state:` → error; `enum` uses `cases:` for its variants
- `kind: regular` + `cases:` → error (cases belong to `kind: enum`)
- `kind: section` — validated per the section-class rules in §4

Validation occurs at compile time (§4 section-class implementations enforce their kind's rules). Error messages name the kind and the offending section.

### 6.2 Value-Typed Class Runtime Models — `kind: record` and `kind: enum`

Classes with `kind: record` and `kind: enum` are value types with these properties:

**Storage:**
- Stack-allocated when scoped to a function.
- Inline in containers (an `[Vec2]` array holds Vec2 values directly, packed).
- Inline in class fields (a class with `record` fields holds them contiguously in the class's storage).
- Passed by value on function calls (may be optimized to pass-by-reference for large records at ABI level; semantic model is copy).

**Assignment:**
- Copies the entire value.
- Two bindings of the same record value are independent; mutating one does not affect the other.

**Mutation:**
- Records are immutable to their bindings by default when `immutable` is applied to the binding.
- A `mutable` binding permits reassigning the binding to a new record value.
- Individual fields of a record cannot be modified in place through a binding; users construct a new record with the desired changes.

**Auto-generated constructor.** A record class receives an auto-generated constructor that takes one named parameter per `data:` field, in declaration order. Parameter names match field names; parameter defaults on the constructor come from field default values where present (§3.8.5 discipline — compile-time constants only).

```
record Vec2:
    data:
        x: Float
        y: Float

# Auto-generated:
# constructor Vec2(x: Float, y: Float)

v = Vec2(x: 1.0, y: 2.0)
```

Users can declare an explicit `constructor` in a `methods:` section to override the auto-generated one (validation of invariants, derived fields, etc.). Explicit constructor suppresses the auto-generated form entirely; if both are wanted, the explicit constructor calls the memberwise form via `Self.init_memberwise(...)`.

**"Modification" pattern:**
```
p1: Vec2 = Vec2(x: 1.0, y: 2.0)
p2: Vec2 = p1.with(x: 5.0)     # new Vec2, p1 unchanged
```

Records may expose a `with(field: value, ...)` method (auto-generated by the compiler from the class's data fields) that returns a new instance with the specified fields replaced. This is the primary modification pattern.

**Dispatch:**
- Record methods dispatch statically. No method table, no `objc_msgSend`.
- Compiler generates direct calls, allowing inlining and optimization.
- Record methods cannot be swizzled (no runtime method table exists for records).

**Identity:**
- Records have only structural identity — `==` compares field values.
- There is no `===` (identity comparison) for records; the concept doesn't apply.

**Memory management:**
- No ARC. Records live where they're stored; when their scope ends or the containing object is freed, they go with it.
- No heap allocation implied by the type. A record held in a class field lives in the class's heap allocation; a record in a local variable lives on the stack.

**`kind: enum`** additionally provides:
- Variants declared in the `cases:` section: `Result.ok(value: x)`, `Result.err(error: e)`.
- Exhaustive matching via `switch` (§3.7).
- Discriminated storage: the runtime representation is a tag + associated values.
- Optional integer backing via `metadata: raw_type:` — when specified (or inferred from `case name = value` syntax), the compiler auto-generates `raw_value(self) -> <raw_type>` and `class function from_raw(value:) -> Self?`. See §3.3 and §4.11 for sugar.

### 6.3 Class Runtime Model

Classes are reference types with these properties:

**Storage:**
- Heap-allocated in per-class extents (§6.4).
- Instances persist as long as any strong reference (handle) points to them.
- Storage layout is runtime-managed within the extent.

**Assignment:**
- Copies the handle, not the target. Two bindings to the same class instance share state.
- Mutation through one binding is visible through the other.

**Mutation:**
- `state:` fields are mutable through the reference.
- `data:` fields are immutable after construction.
- Handle indirection means the compiler cannot assume field values remain stable across method calls (aliasing is possible).

**Dispatch:**
- Methods dispatch dynamically through the runtime method table (`objc_msgSend`).
- Compiler devirtualizes automatically where safe; call sites can be deopted on runtime modification (§5).

**Identity:**
- Class instances have runtime identity independent of field values.
- `==` compares equality (per the class's semantics, typically field-based); `===` compares identity (same instance).
- Two classes with identical field values may still be different instances.

**Memory management:**
- ARC: strong references (handles) increment a reference count on the target. Dropping a reference decrements. Reaching zero triggers destruction.
- `weak` and `unowned` references (§6.7) do not participate in the ref count.
- Cycles remain possible; users mark cycle-breaking references as `weak`.

### 6.4 Extents and the Query API

Every user-declared class has an **extent**: the set of all live instances of that class. Extents are implicit; users do not declare them.

Compiler-generated classes (closures, iterator states, generator frames, section internal state) do not participate in extents. These are compiler machinery, not user constructs.

**Extent structure:**

An extent is the union of contiguous storage regions:
- A **default region** for untagged instances.
- One region per **tag** (§6.5) referencing this class.

Each region maintains its own storage layout. Iteration walks a region contiguously.

**Query API on class metadata:**

```
# Every user class supports these operations:
Order.all() -> Snapshot<Order>                            # every live Order instance
Order.count() -> Int                                       # number of live instances
Order.query(where: (Order) -> Bool) -> Snapshot<Order>    # filtered snapshot
Order.query(where: ..., sort_by: ...) -> Snapshot<Order>  # filtered and sorted
Order.by_id(id: OrderID) -> Order?                        # lookup by identity field
Order.exists(id: OrderID) -> Bool                          # existence check
```

**Query API on protocol metadata:**

```
# Protocols support queries over all conformers:
Renderable.query() -> Snapshot<Renderable>                    # every instance of any conforming class
Renderable.query(where: ...) -> Snapshot<Renderable>          # filtered
(Position, Velocity).query() -> Snapshot<...>                 # multi-protocol query
```

Multi-protocol queries iterate all classes conforming to all listed protocols. The resulting snapshot holds handles typed as the intersection contract (usable as any of the queried protocols).

### 6.5 Handles

Every class reference is a **handle**: an indexed reference into the target's extent. Handles carry:

- The target's extent (implicit via the handle type).
- The target's identity (position in the extent).
- A generation counter for use-after-free detection.

**Handle operations:**

- **Dereference:** access fields or call methods. The runtime resolves the handle to the current storage location, validates the generation counter, and performs the access. On mismatch, raises a use-after-free error (in checked builds).
- **ARC:** creating a strong handle increments the target's reference count; dropping it decrements.
- **Equality:** two handles compare `===` equal if they refer to the same instance.

**Compiler optimizations:**

For handles whose lifetime is scope-local and provably valid, the compiler eliminates:
- Generation checks (validation).
- Extent lookup indirection (uses a direct pointer).
- ARC operations (when the reference count would remain positive throughout the scope).

The result is that scope-local reference access has zero overhead beyond a normal pointer dereference. Cross-scope references pay the full handle cost.

**FilC-style safety:**

Handle validation catches:
- Use after the target has been destroyed.
- Access to instances relocated within the extent (rare, but possible under storage layout changes).
- Cross-extent references where the target extent has been freed.

Users can disable validation in `unsafe:` sections (§4.10.5.2) when profiling shows it as overhead in hot paths.

### 6.6 Tags and Snapshots

Two collection types organize class instances:

**`Tag<T>` — named persistent subset of T's extent.**

Tags own a contiguous storage region within their target class's extent. Instances tagged with a `Tag<Order>` live in that tag's storage region.

```
pending: Tag<Order> = Tag.new("pending")
shipped: Tag<Order> = Tag.new("shipped")

order = Order(id: OrderID.new(), items: [...])
pending.insert(order)          # order now lives in pending's storage region

# Later, transition the order
pending.remove(order)
shipped.insert(order)          # order physically relocated to shipped's region

# Tag iteration is contiguous
for o in pending.query():
    process(o)                 # cache-optimal iteration
```

**Tag semantics:**
- Tag membership counts as a strong reference for ARC purposes.
- Removing from a tag drops the reference; if it was the last, the instance is destroyed.
- Moving between tags relocates storage physically (handles remain valid via indirection).
- Tags can be composed via set operations: `pending.union(shipped)`, `pending.intersect(customer_a)`. Results are snapshots.

**Contiguous storage benefit:**
- Iteration over a tag is a linear walk of contiguous memory.
- SoA layout (§6.7) enables SIMD-friendly access patterns.
- Cache locality is excellent for tag-scoped operations.

**`Snapshot<T>` — materialized query result.**

Snapshots hold handles into wherever their instances live (default region, various tags, other classes' extents via protocol queries).

```
top_orders: Snapshot<Order> = Order.query(where: self.total > 1000)
                                   .sort_by(self.total)
                                   .take(10)

for o in top_orders:
    process(o)
```

**Snapshot semantics:**
- Handles held in a snapshot count as strong references.
- Dropping the snapshot drops those references.
- Snapshots do not have contiguous storage; iteration follows handles to their actual storage locations.
- Snapshots are typically used for local, ephemeral queries.

**When to use which:**
- Persistent grouping with lifetime implications → `Tag<T>`.
- Cache-critical iteration over a stable subset → `Tag<T>` (contiguous storage).
- One-off queries, filters, ordered results → `Snapshot<T>`.
- Cross-class or cross-tag queries → `Snapshot<T>` (can't be contiguous by design).

**Array literals `[T]`:**

For non-class T (structs, primitives, enumerations), `[T]` is a packed value array.

For class T, `[T]` is `Snapshot<T>`. The literal syntax constructs a snapshot from the given handles. This preserves the "snapshot for locals, tag for persistence" distinction while giving users familiar syntax.

### 6.7 Storage Layout Hints

The `dense_storage:` section (Stage 1 built-in) hints that a class should use Structure-of-Arrays layout when stored in tags:

```
class Particle:
    dense_storage:
    data:
        color: Color
        radius: Float
    state:
        position: Vec3
        velocity: Vec3
```

Under SoA layout in a tag:
```
Tag<Particle>:
    color:    [Color, Color, Color, ...]     # packed color array
    radius:   [Float, Float, Float, ...]     # packed float array
    position: [Vec3,  Vec3,  Vec3,  ...]     # packed vec3 array
    velocity: [Vec3,  Vec3,  Vec3,  ...]     # packed vec3 array
```

Iteration walks the arrays in parallel; field access loads from the appropriate array. SIMD operations become natural. Cache behavior is optimal for field-parallel processing.

**Default layout:**
Without `dense_storage:`, the runtime uses AoS (Array of Structures) — each instance's fields laid out contiguously in memory, with instances packed sequentially. AoS is better for whole-instance operations; SoA is better for field-parallel operations.

**Runtime decides:**
Both layouts are always available. The runtime picks based on the hint plus observed access patterns (Stage 3+ may include adaptive layout switching). Users write the same code either way.

**Non-tagged instances** (in the default region) use AoS by default. `dense_storage:` primarily affects tagged instances.

### 6.8 Cycles and Cycle Detection

ARC does not automatically collect cycles. Two classes referencing each other via strong handles form a leak.

**Manual cycle breaking:**

Users mark cycle-breaking references as `weak` or `unowned`:

```
class Parent:
    state:
        children: [Child]      # strong

class Child:
    state:
        parent: weak Parent?   # weak — does not retain
```

Weak references become `nil` when the target is destroyed. Unowned references assume the target outlives the reference; violating this assumption is undefined behavior.

**Query-based alternative to bidirectional storage:**

Many cycles in traditional OOP come from bidirectional relationships stored redundantly. The extent model offers an alternative: store one direction, query the other.

```
class Node:
    state:
        parent: Node?           # one direction stored

# Query children instead of storing
function children_of(node: Node) -> Snapshot<Node>:
    return Node.query(where: self.parent === node)
```

No cycle possible — there's no children field to complete the cycle. This pattern generalizes to many owner-owned and category-member relationships.

**Extent-scoped cycle detection (Stage 2+):**

Optional runtime feature. Users mark object graphs as "may cycle"; the collector runs periodically on those graphs. Because it operates per-extent rather than heap-wide, cost is bounded and predictable.

The collector is a library, not a built-in runtime feature. Users who don't need it pay nothing.

### 6.9 Cross-Language Interop

**ObjC classes:**
- Do not participate in Corvid extents. The ObjC runtime does not provide per-class instance tracking suitable for extent-style queries.
- `NSString.query()` and similar calls on ObjC-imported classes return an error at compile time.
- ObjC classes bridged into Corvid are handle-referenced for consistency; the handle wraps the ObjC pointer with validation metadata added by the Corvid runtime layer.
- ARC semantics interoperate: ObjC's ARC and Corvid's ARC operate on the same reference count for shared instances.

**Structs to/from C:**
- Structs with only `data:` fields use standard C layout (identical to a C `struct` with the same field types).
- Passable to C functions directly.
- Bidirectional interop: C structs imported into Corvid as Corvid structs; Corvid structs exported to C as C structs.

**Structs with references to classes (in `data:` fields):**
- Struct copy semantics apply to the struct portion; class references are shared.
- Passing to C: the class reference becomes an opaque pointer (ObjC object).
- Users should be aware that struct value semantics and class reference semantics coexist.

**Enumerations to/from C:**
- Enumerations with only simple cases (no associated values) map to C enums.
- Enumerations with associated values do not have a direct C representation; they're Corvid-only unless the user provides an explicit bridge.

### 6.10 The `with:` Section (RAII)

Deterministic resource cleanup via scoped destructors (§4.10.4.5). Recap for memory-model context:

- Resources bound in a `with:` block are automatically destroyed on scope exit.
- Cleanup runs in reverse declaration order.
- Cleanup runs regardless of which branch the block exits through (early return with a failure case, normal completion, panic).
- Combines with ARC: if the resource is a class instance, `with:` ensures the last strong reference is dropped when the scope ends, triggering destruction deterministically.

```
function process_file(path: String) -> FileProcessOutcome:
    with:
        f = File.open(path)
        buffer = Buffer.allocate(1024)
        return process(f, buffer)
    # buffer destroyed, then f destroyed
```

`with:` is the standard pattern for file handles, network connections, transactions, locks, and any resource requiring deterministic release.

### 6.11 The `unsafe:` Section

Opt-out from runtime safety checks (§4.10.5.2). Recap for memory-model context:

- Disables handle validation (no generation checks).
- Enables raw pointer operations.
- Permits accessing uninitialized memory.
- Allows calling functions marked `unsafe`.

`unsafe:` is for interop code, hot paths where profiling shows validation overhead, and low-level runtime implementation. Regular application code does not need it.

### 6.12 ECS as an Emergent Pattern

The extent + tag + query mechanism supports Entity-Component-System patterns as a natural coding style, not as a distinct language feature.

**Traditional ECS terminology mapped to Corvid:**

| ECS concept | Corvid mechanism |
|---|---|
| Entity | Class instance |
| Component | Protocol or field-carrying protocol |
| Archetype | Class with specific protocol conformances |
| System | Function taking a protocol query and operating on results |
| Component storage | Tag<T> with dense_storage |
| Archetype query | Protocol query |

**Example:**

```
protocol Positioned:
    accepts position(self) -> Vec3
    accepts set_position(self, p: Vec3)

protocol Moving:
    accepts velocity(self) -> Vec3

class Projectile(Positioned, Moving):
    dense_storage:
    state:
        pos: Vec3
        vel: Vec3
    methods:
        function position(self): return self.pos
        function set_position(self, p): self.pos = p
        function velocity(self): return self.vel

# System: physics update over all moving-positioned entities
function update_physics(dt: Float):
    for entity in (Positioned, Moving).query():
        entity.set_position(entity.position() + entity.velocity() * dt)
```

The compiler statically knows Projectile conforms to both protocols; the query resolves to iterate Projectile's extent (and any other conforming class's extent) in dense storage. Iteration is cache-optimal, SIMD-friendly, and requires no ECS library.

Users writing business logic get the same benefits transparently — cross-cutting queries over domain entities work without opting into ECS-specific patterns.

### 6.13 Non-Goals

Explicit non-goals for the memory model:

- **No SQL parser.** Queries are method calls in Corvid syntax, not a query DSL.
- **No persistence.** Extents live in memory; process termination destroys them. Persistence is library territory.
- **No ACID transactions.** Concurrent mutation during query iteration is implementation-defined. Users needing transaction semantics use library facilities.
- **No garbage collector as default.** ARC is the reference lifetime mechanism. Optional cycle collector as a library (Stage 2+).
- **No arbitrary structural modification at runtime.** Class layout is compile-time-fixed. `class_addMethod` and category loading remain available as runtime APIs, but do not create new field layouts (§4.18.3).
- **No mandatory ownership annotations.** ARC handles the common case. Ownership types (Rust-style borrow/move) available as Stage 3+ opt-in via a section.
- **No user opt-out from extent tracking for user classes.** All user-declared classes are queryable. Struct is the escape hatch for zero-overhead value semantics.
- **No user opt-out from handle indirection.** All class references are handles. Compiler optimizes to direct pointers where scope permits.

These non-goals bound the design and prevent scope creep into database-like functionality that would be better served by dedicated libraries.

### 6.14 Reference Modifiers

- `weak` — weak reference (nils out when target deallocates). Does not participate in ARC.
- `unowned` — non-owning strong reference. Assumes target outlives reference; undefined behavior if violated.

Applied to fields. `weak` implies nil-observability regardless — the type is a reference type and reference types are nil-allowed by default (§3.6.1), so no `?` suffix is needed or accepted.

```
class Child:
    state:
        parent: weak Parent
```

Applied to locals (rare). Under nil-punning semantics (§3.6.1), `if let` is used only when the unwrapped value must be bound for repeated use in the following scope; single-call cases just dispatch through:

```
function callback():
    weak self_ref = self
    schedule_later(function():
        self_ref.do_thing()    # no-op if self was deallocated (weak → nil)
    )
```

### 6.15 Mutability

Mutability applies to bindings, not referenced objects.

- **Fields** — default mutability follows the enclosing section's `default_mutability` configuration. `data:` fields are immutable; `state:` fields are mutable. Individual fields can override with `mutable` or `immutable`.
- **Locals** — mutable by default. First assignment declares. `immutable` opts into single-assignment.

For class instances (reference types), an `immutable` binding still permits mutating the referenced object. For struct instances (value types), `immutable` prevents any modification through that binding.

### 6.16 Local Declaration Rules

Locals are function-scoped. First assignment declares; subsequent assignments reassign. Blocks (`if`, `for`, etc.) do not introduce new scopes for name resolution. Shadowing requires explicit `immutable`.

---
## 7. Modules, Packages, and Sources

The language distinguishes three levels of code organization:

- **Source** — a single `.cor` file.
- **Module** — a named compilation unit that may span multiple sources, produces a unified metadata artifact, and is the boundary for public/internal visibility.
- **Package** — a distribution unit containing one or more modules, versioned, and the boundary for external dependencies.

Users write sources. The compiler assembles sources into modules based on module declarations. Modules assemble into packages via manifests. Each level exists for a different concern: sources are for humans (files they edit), modules are for compilation (units the compiler processes together), packages are for distribution (units published, versioned, depended upon).

### 7.1 Sources

A source is a single `.cor` file. Sources contain declarations: classes, structs, enumerations, protocols, functions, aliases, section usages.

Sources are the granularity of editing but not the granularity of compilation. Multiple sources can share a module; the compiler processes them together.

**Single-file scripts** are sources that compile and run without any surrounding structure:

```
# hello.cor
function main():
    print("Hello, world")
```

Invoked as `oc5 hello.cor`. No manifest required; no module declaration required (the source is treated as its own implicit module). Standard library available implicitly.

**Module-level declarations.** In addition to class, function, and section declarations (§4), sources may contain top-level immutable bindings:

```
let PROTOCOL_VERSION: UInt16 = 13
let DEFAULT_TIMEOUT: Double = 30.0
let WHITELIST: [String] = ["ws", "wss", "http", "https"]

function handshake(url: URL) -> Result<Response, Error>:
    ...
```

**Rules for `let`:**

1. **Initializer required at declaration.** `let x: T = <expr>`. No declare-then-assign pattern. For conditional initialization, use the expression form of `if` / `switch` / `do:` as the initializer — everything-is-an-expression (§3.8) means branched initializers fold into a single expression naturally:

   ```
   let payload: Bytes = if self.needs_decompression:
                           self.delegate.decompress(...) ?? frame.payload
                        else:
                           frame.payload
   ```

   No separate "definite assignment analysis" is spec'd or needed — the expression form provides the same "assigned exactly once, then immutable" guarantee.

2. **Immutable.** No reassignment. `let` at module scope is the only form; `var`-equivalent module-level mutable state is not supported. Mutable module-level state uses a class with `state:` and a singleton pattern, so that access is scoped and metadata-queryable.
3. **Type annotation required at module scope.** Module-level `let` must specify the type — no type inference across module boundaries. Ensures the exposed interface is deterministic without full-program analysis. Function-scope `let` allows type inference from the initializer.
4. **Evaluated at module load** (module scope) or **at binding site** (function scope). Module-level initializers run at module load time, in declaration order within the source. Cross-source ordering follows dependency order (§7.2).
5. **Constant-expression preferred.** If the initializer is a compile-time constant, the compiler evaluates it at build time and inlines the value. Otherwise it becomes a module-load-time (or binding-time) expression.
6. **`expose` legal at module scope.** A module-level `let` is internal by convention; `expose let PI: Double = 3.14159` includes it in the module's exposed interface.

### 7.2 Modules

A **module** is a named compilation unit. Modules:

- Have a reverse-DNS name (`com.example.orders`).
- May span multiple sources declaring the same module name.
- Produce a unified metadata artifact (§4.18) shared across the module.
- Define the boundary for `internal` visibility (§7.7).
- Compile as a unit — cross-file references within the module resolve during a single compilation pass.

**Declaration:**

```
# In every source file that belongs to this module:
module com.example.orders
```

All source files declaring `module com.example.orders` compose into that module. Their order of processing is determined by dependency (a source that references types defined in another source of the same module compiles after the defining source).

**Single-source modules** need no explicit declaration if the source is compiled standalone; the compiler treats the source as an implicit module named after the file.

**Multi-source modules** benefit from shared metadata (LSP sees the whole module structure), cross-source visibility of internal-by-convention declarations, and unified exposed-interface export.

### 7.3 Packages

A **package** is a distribution unit containing one or more modules. Packages:

- Have a name (typically the top-level portion of the qualified module names within, e.g. `com.example.myapp`).
- Have a version (semver).
- Declare their dependencies on other packages.
- Serve as the unit of publishing, caching, and versioning.
- Contain metadata declaring their modules, entry points, and settings.

Packages are declared via a manifest section (§7.4).

### 7.4 The Package Section

Package manifests are declared using the `package:` section — a Stage 1 built-in section for module-body scope. Manifests may appear in three forms.

**Section class:**

```
section class Package:
    data:
        contains: manifest_data
        scope: [source_file_top, module_body]
        composition_mode: inline  # appears once, not composed
    
    processor process(contents: ManifestData):
        # Validates the manifest structure.
        # Registers the package with the compilation context.
        # Feeds dependency information to the resolver.
        register_package(contents)
```

**Form 1: Full manifest in package.cor.**

Real projects place a `package.cor` file at the package root:

```
# package.cor
package:
    name: com.example.myapp
    version: 1.0.0
    
    modules:
        - com.example.myapp
        - com.example.myapp.orders
        - com.example.myapp.core
    
    dependencies:
        - com.example.data_tools ^= 1.2
        - com.mystartup.private_lib from "https://internal.mystartup.com/pkg"
    
    entry_point: com.example.myapp.main
```

The `package.cor` file declares one package. Its `modules:` list declares which modules this package provides. Its `dependencies:` list declares external packages required.

**Form 2: Embedded manifest in a script.**

Single-file scripts with dependencies embed the manifest at the top of the source:

```
# analyze.cor
package:
    name: com.local.analyze
    version: 0.0.1
    dependencies:
        - com.example.data_tools ^= 1.2

import com.example.data_tools

function main():
    ...
```

The `package:` section appears at the top of the source. Everything after it is the script body. Compiler processes the manifest, resolves dependencies, then compiles the script.

**Form 3: Implicit manifest.**

Sources with no dependencies beyond the standard library need no manifest:

```
# hello.cor
function main():
    print("Hello")
```

The compiler assumes an implicit manifest: name derived from filename, version `0.0.0`, no dependencies. Standard library available.

### 7.5 Dependency Sources

Package dependencies may specify their source explicitly for cases where default resolution is insufficient:

**In the manifest:**

```
package:
    dependencies:
        - com.example.data_tools ^= 1.2                                    # default source (registry)
        - com.mystartup.private from "https://internal.mystartup.com/pkg"  # private URL
        - com.local.experiment from "/Users/me/dev/experiment"             # local directory
        - com.example.legacy from "git+https://github.com/x/legacy@v3"     # git ref
```

The `from` clause specifies where the compiler finds the package. Supported source types:
- **Registry URL** (default when omitted) — the compiler's configured package registry.
- **HTTPS URL** — direct URL to a package archive.
- **Local path** — filesystem path to a package directory (for local development).
- **Git URL with ref** — git repository at a specific commit, tag, or branch.

**In imports (per-file override):**

Individual source files can override the manifest's source resolution:

```
import com.example.data_tools from "https://internal-mirror.example.com/data_tools/1.2.0"
```

Per-file overrides are rare — they exist for cases where a specific source needs a different version or origin than the rest of the module. Most files rely on manifest resolution.

### 7.6 Imports and Resolution

**Implicit resolution.** By default, the compiler resolves references implicitly against all packages declared in the manifest (plus the standard library, always implicit).

```
# No explicit imports needed
class MyController:
    state:
        orders: [Order]   # compiler resolves Order against available packages
        client: HttpClient
```

If `Order` is unambiguously defined in exactly one dependency, the compiler uses it. If ambiguous, compile error asking the user to disambiguate.

**Explicit imports.** Users can add explicit `import` statements for disambiguation, per-file source override, or readability:

```
import com.example.orders                    # brings all exposed names into resolution scope
from com.example.orders import Order         # brings only Order
import com.example.orders as orders          # brings module accessible as `orders.Order`
import com.example.orders from "..."         # explicit source
import objc "Foundation"                     # ObjC framework
import c "stdio.h"                           # C header
```

Explicit imports do not add functionality beyond what implicit resolution provides. They exist for clarity and disambiguation.

**Standard library resolution.** The standard library is always implicit. Users never need to import it. Standard library modules include core types (`String`, `Array`, `Dict`), I/O (`File`, `Print`), and common utilities.

**Ambiguity handling.** When a name resolves to multiple candidates, the compiler emits an error and suggests explicit disambiguation:

```
error: ambiguous reference to 'Order'
  --> Controller.cor:14:12
   |
14 |     orders: [Order]
   |             ^^^^^ 
   |
note: 'Order' is defined in both:
      com.example.orders.Order
      com.otherlib.orders.Order
help: qualify the reference:
      com.example.orders.Order
      or add an import:
      import com.example.orders
      or an alias:
      alias Order: com.example.orders.Order
```

### 7.7 Visibility

Two visibility states, not levels:

- **`expose`** — this declaration is part of the interface I designed for external use. Depend on it.
- **Absent (default)** — internal to the module by convention. Not part of the exposed interface.

**No `public`, no `private`, no `internal` keyword.** The previous spec had three levels with hierarchical access control. That was authority framing: `private` said "you can't touch this," `public` said "you may touch this." Both were enforced at compile time, then blown open by runtime metadata queries (§4.18).

Under intent-not-authority (§1.2), the compiler doesn't enforce access restrictions. It enforces intent:

- `expose` on a declaration adds it to the module's stable public surface. Metadata artifacts (§4.18) list it as intended-for-external-use. IDE completions surface it prominently.
- Absent `expose`, the declaration is internal to the module by convention. The compiler emits a warning when external code references it. The warning can be suppressed per-reference (for legitimate boundary-crossing) or upgraded to an error via build config (for stricter enforcement).

**Runtime access is unrestricted.** Metadata queries return everything the compiler knows about a class regardless of `expose`. KVC-style access reaches non-exposed fields. `extend class Foo:` (§3.3.2) adds members to any visible class. This is the Objective-C runtime philosophy carried forward honestly: capability is preserved; intent is documented.

**Extension visibility.** An `extend class Foo:` block declared in module B is visible to a consumer C only if C imports B (transitively per §7.2). Extensions do not leak across independent import paths. This scopes extension visibility to explicit dependency edges — a consumer chooses which extensions it sees by choosing which modules it imports. Individual members inside an extension may carry `expose` exactly as members in a definition body may; the enclosing extension itself is not marked `expose` (visibility of the extension follows from module-import visibility).

**Type-scoped visibility (like Java's `private`) doesn't exist as a keyword.** Members inside a class declaration that aren't `expose`d are internal to the module — and by convention to the type. Same warning behavior for external access. If real usage shows need for type-scoped enforcement, we add it later; current position is that the convention is enough.

**Scope of `expose`:**
- On a class or struct: the type name is exposed as part of the module's interface.
- On a method or field within a type: the member is exposed even when the containing type's members are otherwise internal.
- On a function, alias, or constant at module scope: same as class — added to the exposed surface.

**Example:**

```
class Order:
    expose data:
        id: OrderID
        placed_at: Timestamp
    
    data:
        internal_hash: Bytes    # not exposed; external code gets a warning
    
    expose methods:
        function ship(self) -> ShipOutcome: ...
    
    methods:
        function _validate(self): ...  # not exposed; convention indicates internal
```

Consumers reading this see the intended interface (`id`, `placed_at`, `ship`) clearly. They technically can reach `internal_hash` or `_validate` but on notice that they've stepped off the stable surface.

### 7.8 Public Interface

A module's exposed interface consists of all declarations marked `expose`. This includes:

- Public types (classes, structs, enumerations).
- Public functions and constants.
- Public aliases (all four categories).
- Public sections and section classes.

Section usages on exposed classes are part of the exposed interface (§4.18). Consumers see the class's structure including sections. This means adding a section to an exposed class is a semver minor change if the section is externally observable; non-exposed sections don't affect the interface.

Public interfaces have compatibility guarantees per semver (§7.11).

### 7.9 Compilation and Dependency Model

Modules compile in dependency order. A module's dependencies (other modules within the same package or external packages) compile first.

**Within a package:**
- Modules are compiled in topological order of dependencies.
- Circular dependencies between modules are compile errors.
- Circular references between types within a single module are permitted.

**Across packages:**
- Dependency packages compile independently.
- Their metadata artifacts and object files are cached.
- Dependent packages consume the cached outputs.

**Incremental compilation (§4.18.5):**
- Modules are compilation units. Incremental state is per-module.
- Cross-source changes within a module reprocess only affected sources.
- Cross-module changes trigger recompilation of dependent modules.

### 7.10 Cross-Language Modules

Sources may mix `.cor` and `.m` (Objective-C) files within the same module. Both compile to libobjc2-compatible object files that link together (§9.4).

**Metadata coverage:**
- Corvid sources produce rich metadata (sections, contracts, invariants).
- ObjC sources produce basic runtime class info (via libobjc2's format).
- The module's metadata artifact contains both.

**Cross-language references:**
- Corvid sources reference ObjC classes via `import objc "Framework"`.
- ObjC sources reference Corvid classes via auto-generated `-Corvid.h` headers.
- Runtime class lookup works uniformly (both languages register with libobjc2's class table).

### 7.11 Version Compatibility

Packages follow semver:

- **Major (X.0.0)** — breaking changes to exposed interface. Consumers may need code changes.
- **Minor (1.X.0)** — additive changes. New exposed declarations; no removals or signature changes.
- **Patch (1.0.X)** — internal changes only. No exposed interface change.

**What counts as breaking:**
- Removing an exposed declaration.
- Changing an exposed function's signature (parameters, return type, effects).
- Removing an exposed field from a class.
- Changing a section class's generator output (affects all consumers of that section).
- Adding a required protocol method.

**What is additive:**
- New public declarations.
- New optional parameters with defaults.
- New protocol methods with default implementations.
- New sections on a public class (if the section's contribution to the public interface is additive).

**Section class versioning:** section classes ship as part of a module. Section class changes follow the containing module's semver. Because section class changes affect generated code in consumer modules, section class semver is strict — any generator output change is a major bump.

### 7.12 Dylib Loading and Runtime Extension

libobjc2 supports dylib loading at runtime. Corvid modules compile to dylibs (or static libraries); loading a dylib registers its classes and metadata with the runtime.

**Runtime plugin patterns work:**
- `dlopen("plugin.dylib")` loads the plugin.
- Plugin's classes register with the shared libobjc2 class table.
- Plugin's metadata artifact loads into the runtime metadata registry.
- Runtime queries (§4.18.3) can now find plugin classes.

**Limitations:**
- Cross-dylib section class version compatibility is checked at load time; mismatches error before loading completes.
- Handle validation across dylib boundaries requires the shared metadata registry.
- Unloading a dylib whose classes have live instances is undefined behavior (matches libobjc2's model).

### 7.13 Circular Dependencies

**Between modules within a package:** compile error. Users refactor into a shared module or split the circularity via protocols.

**Between packages:** compile error. Cannot occur in practice because packages are DAG-linked.

**Between types within a module:** permitted. Two classes may reference each other via handles, protocols, or types.

**Between sources within a module:** permitted. The compiler resolves cross-source references during module compilation.

### 7.14 Non-Goals and Deferred

**Non-goals:**

- **No header files.** All declarations are in `.cor` sources; no separate interface files.
- **No preprocessor imports.** `import` is semantic, not textual.
- **No global namespace at the source level.** Types are qualified by module.
- **No runtime module unloading beyond libobjc2's dylib model.**
- **No distributed modules or URL-referenced imports at compile time** (imports may specify HTTPS URLs, but only for fetching packages, not for referencing types across the network at compile time).

**Deferred to post-v1:**

- **Package manager as a tool.** v1 ships with a working compilation model and manifest support; the package manager tool (fetching, caching, dependency resolution) comes as a separate tool in v1.0 or later.
- **Registry protocol.** Specification for the package registry that clients speak.
- **Version resolution algorithm.** How the tool picks versions when multiple constraints exist.
- **Workspace concept.** Multiple related packages developed together (Cargo workspaces).
- **Fileprivate visibility.** Add if real usage shows need.

---

## 8. Concurrency

Corvid does not commit to a single concurrency model. It provides coordination primitives (state machines via contracts, channels, signals, structured async) that support multiple threading and communication styles. Users pick per-task; the language ensures the coordination primitives compose safely.

### 8.1 Concurrency Primitives, Not a Model

Traditional language design picks one concurrency model — actors (Erlang, Akka), CSP (Go, Clojure core.async), async futures (Rust, JavaScript), threads with locks (C++, Java) — and makes everything else awkward. Corvid rejects the premise. Different problems benefit from different models; a real system often uses several.

The language provides:

- **State machines as synchronization** (§8.4) — contract-carrying methods on mutable state are atomic transitions. This is where actor-like semantics come from.
- **Channels** (§8.6) — asynchronous event streams between producer and consumer contexts. This is where CSP comes from.
- **Signals** (§8.7) — one-shot completion notifications. This is where futures/promises come from.
- **Structured async** (§8.3) — scoped task lifetimes with automatic cancellation.
- **Threads and parallelism** (§8.8) — explicit OS threads (advanced use); `parallel_for` for row-level parallel iteration (Stage 3+).

Users compose these to build actors, pipelines, event loops, work queues, reactive systems, or whatever their problem needs.

### 8.2 Async Effect (Cross-Reference)

`async` is a semantic effect (§3.5.2) that changes calling convention. Async functions can suspend and resume; sync functions cannot. Async functions can only be called from other async functions or from an async context (structured scope, task spawner).

```
enum FetchOutcome:
    case ok(body: Bytes)
    case network_error(reason: String)
    case timeout
    case bad_status(code: Int)

async function fetch_data(url: URL) -> FetchOutcome:
    switch await http.get(url):
        case .response(let r) where r.status.is_success:
            return .ok(body: r.body)
        case .response(let r):
            return .bad_status(code: r.status)
        case .timed_out:
            return .timeout
        case .connection_failed(let reason):
            return .network_error(reason: reason)
```

`await` is only valid inside `async` functions. `async` is not advisory — a method's async-ness is part of its calling convention and cannot be "just a hint."

### 8.3 Structured Concurrency

Async tasks live in structured scopes. A scope guarantees all tasks spawned within it complete (or are cancelled) before the scope exits.

```
async function process_batch(orders: [Order]):
    async_scope:
        for order in orders:
            spawn process_one(order)
        # scope exit: waits for all spawned tasks to complete
    
    # By here, all process_one tasks have finished
```

**Cancellation:** if the scope exits abnormally (error propagation, explicit cancellation), all still-running tasks are cancelled. Cancellation is cooperative — tasks check cancellation points at await sites and abort cleanly.

**Return values:** `spawn` returns a `Task<T>` handle. `await task` retrieves the result:

```
async function fetch_and_combine():
    async_scope:
        task_a = spawn fetch(url_a)
        task_b = spawn fetch(url_b)
        result_a = await task_a
        result_b = await task_b
        return combine(result_a, result_b)
```

**Error handling:** if a spawned task raises, the error propagates out through the scope. Other tasks in the scope are cancelled. Structured error propagation.

**Scope nesting:** scopes can nest. Inner scope errors propagate to outer scope. Cancellation cascades down.

### 8.4 State Machines as Synchronization

Under §1.9, methods with `requires`/`ensures` on mutable state get atomic check-and-transition semantics. This is the language's primary synchronization mechanism.

**Semantics:**

For a method:
```
async function ship(self)
    requires status == .pending
    ensures status == .shipped:
    ...
    self.status = .shipped
```

The runtime guarantees:
1. The precondition check and the postcondition establishment are atomic with respect to other threads accessing `self.status`.
2. If the precondition is not met, the caller either waits (if awaiting) or fails immediately (if not).
3. Once the precondition is verified, no other thread can invalidate it before the postcondition is established.

**Implementation:**

- **Stage 1**: runtime-enforced via CAS or lightweight locks per contract-carrying method. The runtime maintains a lock per instance for methods that need synchronization; methods without contracts on mutable state skip the lock entirely.
- **Stage 3+**: compile-time analysis eliminates locks where the runtime state can be proven not to have concurrent access. Optimistic concurrency where profitable.

**Waiting on preconditions:**

Async methods with unmet preconditions wait on a state change signal (§8.7). When the state field is next modified (through any method), waiters re-check their preconditions. This means `requires` clauses on mutable state serve as condition variables.

```
class Buffer:
    state:
        items: [Item] = []
    
    invariants:
        items.count <= max_size
    
    methods:
        async function pop(self) -> Item
            requires items.count > 0:
            item = self.items[0]
            self.items = self.items.drop_first()
            return item
        
        async function push(self, item: Item)
            requires items.count < max_size:
            self.items.append(item)

# Producer
async function producer():
    for i in 0..1000:
        await buffer.push(Item(i))  # waits if buffer full

# Consumer
async function consumer():
    while true:
        item = await buffer.pop()  # waits if buffer empty
        process(item)
```

The precondition semantics automatically implement a bounded queue with backpressure. No explicit locks, condition variables, or synchronization primitives written by the user.

**Non-async methods with unmet preconditions fail immediately** rather than waiting. This preserves the sync/async distinction — sync code doesn't unexpectedly block.

#### 8.4.1 Reader/Writer Method Classification

Under §1.9's contract-based coordination, methods on a class are classified by the compiler as **readers** or **writers**:

- **Reader** — does not assign to any `state:` field, and does not call any writer method (either on `self` or on other class instances whose state affects `self`'s invariants). Read-only with respect to observable state.
- **Writer** — assigns to at least one state field or calls at least one writer method.

Runtime uses **RWLock-style dispatch** per instance:

- Multiple readers execute concurrently — state is not changing.
- A writer executes exclusively — blocks all readers and other writers until it completes.
- Methods on `data:` fields only (record classes; regular classes with no `state:` at all) skip coordination entirely — always safe to read, no writer possible.

Reader/writer classification also composes with `data:`/`state:` at the field level (§3.3): a value obtained from a `data:` field of a record, when passed to a writer method, triggers the `data-field-mutation` advisory diagnostic — the mutation happens on a COW copy under value semantics; the record is unaffected. The classification is what makes the diagnostic possible.

**Classification is inferred, not annotated.** No `@reader` or `@writer` markers. The compiler analyzes method bodies:

```
class Ledger:
    state:
        accounts: {AccountID: Money}
    
    invariants:
        accounts.values.sum == self.initial_total
    
    methods:
        expose function balance(self, id: AccountID) -> Money:      # READER (no assignment)
            return self.accounts[id] ?? 0
        
        expose function total(self) -> Money:                        # READER
            return self.accounts.values.sum
        
        expose function transfer(self, from: AccountID, to: AccountID, amount: Money):   # WRITER
            requires accounts[from] >= amount
            ensures result.accounts[from] == self.accounts[from] - amount
            ensures result.accounts[to]   == self.accounts[to]   + amount
            
            self.accounts[from] -= amount
            self.accounts[to] += amount
```

Multiple threads can call `.balance(id)` or `.total()` concurrently. Any `.transfer(...)` blocks until all in-flight readers complete, then executes exclusively. When it completes, waiting readers resume.

**Callback closures.** A method that accepts a closure argument is classified by whether it invokes the closure with a writer's contract. If the closure could mutate state (e.g., `.for_each` on a mutable collection), the containing method is treated as a writer. If the closure is guaranteed read-only (compiler-inferred from closure body or closure-parameter effect polymorphism §3.5.3), the containing method may remain a reader.

**Cross-instance calls.** A method on class `A` that calls a writer on class `B` is only classified as a writer for `A` if `A`'s invariants depend on `B`'s state. In the common case (unrelated invariants), calling `B.mutate()` from `A.read_something()` does not force `A.read_something()` to become a writer.

**Diagnostics.** The compiler surfaces the reader/writer classification in generated metadata (§4.18). Tooling can display each method's classification on hover; users can query at runtime via `Class.method("name").classification`.

#### 8.4.2 Execution Metadata

A class may declare an `execution:` section carrying scheduling metadata: which thread its methods run on, what QoS class they get, whether they share a dispatch pool with other instances. The section is a class-level default; per-method overrides are legal via nested `execution:` on the method.

**Class-level:**

```
class UIController:
    execution:
        thread: main
        qos: user_interactive
    
    state:
        current_view: View
        pending_updates: Int = 0
    
    methods:
        expose async function refresh(self): ...          # runs on main thread
        expose function pending_count(self) -> Int:       # main thread; reader
            return self.pending_updates
```

**Per-method override:**

```
class MixedController:
    execution:
        thread: main
        qos: user_interactive
    
    methods:
        expose async function refresh(self): ...
        
        function precompute(self) -> [Item]:
            execution: thread: any, qos: utility          # off main thread
            return heavy_computation()
```

**Fields:**

| Field | Values | Meaning |
|---|---|---|
| `thread:` | `main` \| `any` (default) | `main`: runtime enforces main-thread execution; ObjC bridge routes to main queue. `any`: no thread constraint. |
| `qos:` | `user_interactive` \| `user_initiated` \| `utility` (default) \| `background` | Kernel scheduler hint. Not a hard priority. |
| `pool:` | `implicit` (default) \| `dedicated` | `implicit`: shares the runtime's default pool. `dedicated`: instance gets its own pool (rare; for latency-sensitive isolation). |

The class-level `execution:` section acts as a default; the method-level override refines specific methods. Both use the same field vocabulary.

**Runtime behavior:**

- `thread: main` — every method invocation is routed to the main dispatch queue. Sync calls from off-main return synchronously if already on main; otherwise the runtime enqueues and blocks the caller (or fails, if the method is sync and the caller isn't willing to wait).
- `qos:` — passed to the underlying dispatch primitive (GCD on Apple platforms). Kernel uses it to prioritize scheduling.
- `pool: dedicated` — instance gets its own single-thread dispatch pool. Useful for tight-loop workers where sharing the default pool would introduce cache contention or latency variance.

**Metadata inspectability.** `execution:` metadata is part of the class's compile-time metadata artifact (§4.18). Tooling can query "which classes require main thread" or "which methods have `qos: background`" without reading source.

**`@main_actor` retired.** Prior drafts (pre-v0.8.6) referenced `@main_actor` as an annotation. `execution: thread: main` supersedes it. Same runtime behavior, consistent with Corvid's metadata-first declarative style.

#### 8.4.3 DispatchQueue in Corvid: What Dissolves, What Stays

DispatchQueue in ObjC/Swift bundles several structurally-different jobs as one primitive. Under Corvid's model, each job maps to an existing or v0.8.6-added mechanism.

| DispatchQueue role | Corvid mechanism |
|---|---|
| **Serial mutex for a class's state** | Contracts on `state:` fields (§1.9). Runtime serializes writer methods; readers concurrent (§8.4.1). No queue field, no `queue.async { }` boilerplate in user code. |
| **Off-caller-thread scheduling** | `spawn { }` for fire-and-forget, `async_scope` for structured lifetimes (§8.3). |
| **Main-thread affinity** | `execution: thread: main` on the class or method (§8.4.2). |
| **Priority / QoS hints** | `execution: qos: <class>` (§8.4.2). |
| **Cross-object pipelines** | Channels (§8.6). Producer / consumer as separate classes, coordinated by channel. |
| **Cross-object shared invariants** | Put them in one class. The class boundary IS the coordination boundary. |

**On the "one class owns shared state" pattern.** Prior drafts sketched a `coordination_scope` construct for grouping instances that share invariants. That construct was retracted before v0.8.6 landed: every case where separate objects need shared invariants collapses cleanly into "one class owns all the shared state." If two objects share an invariant, they weren't really two objects — they were one class artificially split. Bank ledger, cache + store, producer + queue + consumer — all express as a single class with the joint state as its `state:` fields and the joint invariants as its `invariants:`.

**When you truly need separate classes** — different lifecycles, independent reuse, distributed ownership — use channels (§8.6) to pipeline between them, and accept that the classes don't share invariants (only messages).

**What Corvid gives up.** Cross-cutting queue-based coordination across pre-existing separate objects. ObjC codebases sometimes coordinate object A and object B via a shared external queue; Corvid says the coordination should be reified as a class (`AB_Coordinator`) that owns both. More design effort at the class-boundary decision; less coordination-scattering. Consistent with §1.6 legibility.

### 8.5 Sendable Types

Types that can safely cross task boundaries. Determined by shape, not annotation:

- **Value types** (struct, enumeration) — always Sendable. Copied on transfer.
- **Class handles** — always Sendable. The extent lives globally; handles point into it. Access is coordinated by the extent's state machine.
- **Function values** — Sendable if their captured environment is Sendable.
- **Types with mutable global state** — not Sendable. Rarely encountered because Corvid has no true globals (module-level constants are immutable; module-level state doesn't exist).

The runtime enforces Sendable at task boundaries. Attempting to pass a non-Sendable value into a spawned task raises a compile error (Stage 3+) or runtime error (Stage 1).

### 8.6 The Channel Section

The `channel:` section is Stage 1 built-in behavioral, providing CSP-style asynchronous coordination. A channel is a first-class coordination primitive with two composition patterns and three distribution modes.

**Two composition patterns:**

1. **Direct field entries** — declare typed channel items that consumers `send` and receive. Analogous to Go/Clojure channels used as work queues, broadcasters, or topic streams.
2. **Nested `state:` composition** — wrap declared state fields so that setting them auto-publishes `StateChange` events. This is the v0.5 pattern preserved.

Both patterns coexist under the same section; a class may use either or both.

**Three distribution modes** (via `metadata: mode:`):

- **`work` (default)** — CSP-style competing consumers. Each item is received by exactly one subscriber. FIFO delivery. Analogous to Go's `chan T`, Clojure's `chan`.
- **`broadcast`** — every subscriber receives every item. Slowest subscriber sets the pace (synchronous fan-out). Analogous to Clojure's `mult`/`tap`.
- **`pub`** — topic-partitioned broadcast. Requires `metadata: topic_fn:` mapping items to topic identifiers. Subscribers pick specific topics. Analogous to Clojure's `pub`/`sub`.

```
section class Channel:
    data:
        contains: fields
        composition_mode: nested
        parameters:
            mode: DistributionMode = .work
            capacity: Int? = nil          # Int is value-type; T? is Optional<Int> sum. nil = unbounded (default).
            on_full: OverflowPolicy = .block   # required when capacity is set
            topic_fn: TopicFn = nil       # TopicFn is a Callable (reference); nil-allowed by default. Required when mode = .pub.
    
    state:
        instance_subscribers: [Subscriber] = []
        buffer: RingBuffer<Item> = RingBuffer.new(bounded: capacity)
    
    generator field_setter(field: FieldSpec) -> Code:
        # Used when the channel composes with a nested `state:` section.
        # Publishes StateChange events on set. Semantics as in v0.5.
        ...
    
    function send(self, item) -> ...
    function subscribe(self) -> AsyncIterator<Item>: ...
    class function subscribe() -> AsyncIterator<Item>: ...
```

#### 8.6.1 Direct-Entry Channels

Declare typed channel items as fields inside `channel:`. The compiler generates `send`/`subscribe` machinery for each entry.

```
class WSFramer:
    data:
        is_server: Bool
    state:
        buffer: Bytes = Bytes()
    channel:
        input: Bytes                              # work mode (default)
        output: Frame
            metadata:
                mode: broadcast                   # every subscriber sees every frame
        errors: WSError
            metadata:
                capacity: 32                      # bounded
                on_full: drop_oldest
        events: DomainEvent
            metadata:
                mode: pub
                topic_fn: e => e.kind             # topic-partitioned
    methods:
        expose function add(self, data: Bytes):
            self.sections.channel.input.send(data)
        
        async function process_loop(self):
            for data in self.sections.channel.input.subscribe():
                self.buffer.append(data)
                ...
```

**Backpressure defaults.** Direct-entry channels default to **unbounded** capacity. This matches DispatchQueue behavior at the Apple-ecosystem substrate layer — unbounded → memory grows on lagging consumer, but memory pressure is diagnosable with standard Apple tooling (Instruments, xctrace). Users needing bounded semantics opt in via `metadata: capacity:` + `on_full:`.

**Consumer lifecycle.** Consumer loops are bound to their owning entity:

- **Per-instance loops** — spawn from the constructor: `spawn self.process_loop()`. When the instance is deallocated, the channel closes; the `for ... in .subscribe()` loop terminates naturally; the task ends. No `[weak self]` dance required — the channel-closure semantics handle instance lifetime.
- **Per-program loops** — spawn from the program entry point; terminate at program exit via structured cancellation.

#### 8.6.2 State-Composition Channels

The v0.5 pattern — wrapping declared state fields for auto-publish — is preserved via section composition:

```
class Order:
    state:
        status: OrderStatus = .pending
    channel:
        state:
            status                # setting order.status auto-publishes StateChange event

# Consumers subscribe to the state-change stream:
for change in specific_order.sections.channel.subscribe():
    handle(change)               # StateChange { instance, field, old, new }
```

The channel's `field_setter` generator wraps assignments to nested-`state:` fields, producing `StateChange` events on set. Distribution mode defaults to `broadcast` for state-composition channels (state changes are inherently pub/sub) but can be overridden via `metadata: mode:`.

#### 8.6.3 Buffering and Backpressure

Bounded channels use `metadata: capacity: N` with an `on_full:` policy:

| `on_full:` | Behavior when buffer full |
|---|---|
| `block` (default when bounded) | Sender blocks (async) or returns `.buffer_full` (sync) until space available |
| `fail_fast` | Sender returns `.buffer_full` immediately (no blocking) |
| `drop_oldest` | Oldest buffered item is discarded to make room |
| `drop_newest` | Incoming item is discarded |

Sender's return type on a bounded channel is a domain enum `SendOutcome { case sent, case buffer_full }`; callers `switch` on it. No exceptions.

Buffering interacts with state-machine synchronization: if a setter blocks on channel backpressure, its `ensures` postcondition is not yet established. Other threads waiting on the state remain waiting until the channel makes progress.

#### 8.6.4 Cancellation and Multiple Subscribers

**Cancellation.** Subscribers cancel by dropping the iterator. The channel deregisters them. If all subscribers drop and the mode is `work`, the channel accumulates items in the buffer (up to capacity if bounded); if the mode is `broadcast` or `pub`, items are dropped when no subscribers exist (Clojure `mult` semantics).

**Multiple subscribers by mode:**

- `mode: work` — subscribers compete; each item goes to exactly one subscriber. Under contention, distribution is FIFO across arrivals but items are consumed exclusively.
- `mode: broadcast` — every subscriber receives every item synchronously. Slowest subscriber determines throughput.
- `mode: pub` — every subscriber sees items whose `topic_fn(item)` matches its subscribed topic. Multiple subscribers to the same topic each receive every matching item (broadcast within topic).

### 8.7 The Signal Section

The `signal:` section is Stage 1 built-in behavioral, providing one-shot completion notifications.

```
section class Signal:
    data:
        contains: fields
        composition_mode: nested
    
    state:
        set: Bool = false
        value: SignalValue?
        waiters: [Continuation] = []
    
    generator field_setter(field: FieldSpec) -> Code:
        return quote:
            function set_${field.name}(self, new_value):
                if self.sections.signal.set:
                    @compile_or_runtime_error("signal '${field.name}' already set")
                self._${field.name} = new_value
                self.sections.signal.set = true
                self.sections.signal.value = new_value
                for waiter in self.sections.signal.waiters:
                    waiter.resume(new_value)
                self.sections.signal.waiters = []
    
    function await(self) -> SignalValue:
        if self.set:
            return self.value
        return suspend { cont in
            self.waiters.append(cont)
        }
```

**Semantics:**

- Signal-marked fields can be set exactly once. Subsequent sets are errors.
- Awaiters block until the signal is set; then all receive the value simultaneously.
- After being set, awaiting a signal returns immediately with the value.

**Usage:**

```
class Server:
    signal:
        state:
            ready_endpoint: URL

# One task sets it
async function start_server():
    endpoint = await bind_socket()
    server.ready_endpoint = endpoint  # signals completion

# Other tasks wait for it
async function client_connect():
    endpoint = await server.ready_endpoint  # blocks until set
    await connect(endpoint)
```

**When to use signal vs channel:**

- **Signal**: one-shot events. Task completion, resource initialization, ready flags, cancellation tokens.
- **Channel**: recurring events. State changes, message streams, event logs.

### 8.8 Threads and Parallelism

**Stage 1 — Explicit Threads (Restricted):**

Direct OS thread creation is available but restricted to `unsafe:` contexts:

```
unsafe:
    thread = spawn_thread(function() {
        # runs on a new OS thread
        long_running_work()
    })
    thread.join()
```

The restriction is deliberate: explicit threads bypass the async runtime's scheduling and can introduce subtle bugs. Users needing OS threads must acknowledge the risk.

**Stage 3+ — Row-Level Parallelism:**

`parallel_for` iterates a query result in parallel across worker threads:

```
parallel_for particle in Particle.query():
    particle.position = particle.position + particle.velocity * dt
```

The runtime distributes iterations across a thread pool. Each iteration operates on one row; the extent guarantees no two iterations touch the same row simultaneously.

**Constraints on parallel_for bodies:**
- The body cannot reference mutable state outside the iterated row (compile error).
- Method calls with `requires`/`ensures` on other objects are permitted but may introduce serialization if contended.
- Side effects (I/O, logging) are permitted but not ordered.

**Data-parallel operations:**

For dense-storage tags (§6.7), the runtime can vectorize simple parallel_for bodies to SIMD instructions where applicable. Stage 3+ optimization.

### 8.9 ObjC Interop

Objective-C's concurrency primitives (GCD, `NSOperation`, completion handlers) bridge to Corvid as follows:

**Dispatch queues → task executors:**

```
main_queue = DispatchQueue.main  # Corvid-typed
async function update_ui():
    await main_queue.run:
        # runs on main thread
        label.text = "updated"
```

**Completion handlers ↔ async functions:**

ObjC methods with completion handlers are imported as `async` functions returning a domain enum with `.completed(T)` and `.failed(NSError)` cases:

```
# ObjC declaration:
# - (void)fetchDataWithCompletion:(void (^)(NSData *, NSError *))completion;

# Corvid usage:
switch await client.fetch_data():
    case .completed(let data): process(data)
    case .failed(let err):     handle(err)
```

The compiler wraps completion-handler methods automatically. The second callback parameter (`NSError *`) becomes the `.failed` case of the enum; the first parameter becomes the `.completed` payload. Nil-in-both is mapped to a domain-specific case per import annotation, or defaults to `.completed(nil)` under nil-messaging (§3.6.1).

**Corvid async methods exposed to ObjC:**

Auto-derivation (§9.3.3) exposes Corvid async methods to ObjC via completion-handler convention. A Corvid method returning a domain enum with a `.failed(NSError)`-shaped case is lowered to the two-parameter completion form:

```
# Corvid:
async function process(order: Order) -> ProcessOutcome:
    # ProcessOutcome has .completed(Receipt), .failed(NSError), and other cases

# ObjC sees (compiler flattens .completed → success param, .failed → error param;
# other cases are lowered to a domain-specific error code):
- (void)processOrder:(Order *)order completion:(void (^)(Receipt *, NSError *))completion;
```

**Main-thread affinity (v0.8.6):**

UIKit/AppKit callbacks must run on the main thread. Corvid expresses this via the `execution:` section (§8.4.2) — class-level default with per-method override:

```
class ViewController:
    execution:
        thread: main
        qos: user_interactive
    
    methods:
        async function refresh(self):
            data = await fetch()
            self.label.text = data.title  # guaranteed main-thread
```

Under the hood, `execution: thread: main` schedules the class's methods on the main dispatch queue. The runtime enforces main-thread execution: sync calls from off-main return synchronously if already on main; otherwise the runtime enqueues to the main queue and blocks (or fails, if the method is sync and the caller isn't willing to wait).

Prior drafts (pre-v0.8.6) referenced `@main_actor` as an annotation. Retired; `execution: thread: main` supersedes it — same behavior, consistent with metadata-first declarative style.

**Per-instance dispatch pool for ObjC callers (v0.8.6):**

Corvid class instances expose a `dispatch_pool` property at the ObjC bridge. ObjC callers submit blocks via standard GCD APIs:

```
// ObjC calling into a Corvid class:
Order *order = /* ... */;
dispatch_async(order.dispatch_pool, ^{
    [order processWithCompletion:^(Receipt *r, NSError *e) {
        // handles result
    }];
});
```

The pool routes through the class's contract-guarded serialization (§8.4.1, §1.9) transparently. ObjC callers get Corvid's reader/writer concurrency guarantees without knowing anything about contracts. Instances declared `execution: thread: main` bind their `dispatch_pool` to the ObjC main queue directly — same address, same identity — so ObjC code that hardcodes `dispatch_get_main_queue()` continues to work.

Instances declared `execution: pool: dedicated` get their own single-thread dispatch queue exposed as `dispatch_pool`; every ObjC call to the instance routes through that queue.

### 8.10 Non-Goals

Explicit non-goals for the concurrency model:

- **No committed concurrency model.** The language does not pick actors, CSP, or futures as "the model." Users choose.
- **No built-in transactions.** Multi-object atomic updates are not a language feature. Users compose them via explicit intermediate states or library-provided STM.
- **No distributed protocols.** Networked coordination is a library concern.
- **No lock-free data structures as language primitives.** Standard library may provide some; language provides the state-machine mechanism.
- **No mandatory `Sendable` annotations.** The compiler infers sendability from shape (Stage 1 runtime-checked; Stage 3+ compile-time).
- **No preemptive multithreading in Stage 1 outside `unsafe:`.** Cooperative async on a work-stealing pool covers most needs.
- **No shared mutable global state.** Module-level state does not exist; static fields are compile-time constants (§4.10.1).

These non-goals bound the design and prevent scope creep into distributed-systems-in-the-language territory.

---
## 9. Interop

Corvid's interop story is grounded in a specific compilation model: Corvid shares its runtime substrate (`libobjc2`) with Objective-C. Both languages produce the same class metadata, use the same method dispatch, and interoperate at the runtime level rather than through a bridge. This makes Objective-C interop **native** rather than mediated.

### 9.1 Compilation Model

**Primary compilation target: LLVM IR.** The Corvid compiler produces LLVM IR that speaks the libobjc2 ABI directly. Object files are standard LLVM target format, compatible with any linker that handles Clang output.

**Alternative compilation target: Corvid → Objective-C source.** For bootstrap purposes and specific interop scenarios (debugging generated code, working with ObjC-only toolchains, platforms without LLVM), the compiler can emit `.m` files that any Objective-C compiler can process. This path is preserved as a permanent alternative output, not just a bootstrap artifact.

**No Clang fork.** Corvid's compiler is standalone. It reuses none of Clang's front-end code. It emits object files that the standard linker combines with output from Clang, GCC-ObjC, or any other libobjc2-compatible compiler.

**Rationale.** LLVM IR direct gives us maximum flexibility (not tied to Clang's evolution), acceptable engineering cost (libobjc2 ABI is well-documented and stable), and clean integration with the LLVM ecosystem (LTO across languages, standard debug info, shared object format). Forking Clang would couple us to Clang's release cycle and front-end assumptions permanently for no compensating benefit — we don't need Clang's ObjC codegen because we can emit libobjc2-compatible IR directly.

### 9.2 C Interop

Direct via `import c "header.h"`. The compiler parses C headers, exposes declared types and functions to Corvid code.

```
import c "stdio.h"

function main():
    c.printf("hello\n")
```

C types map to Corvid types via standard equivalences (`int` → `Int32`, `char *` → `String` or `RawPointer` depending on context, etc.). Full mapping table in the standard library documentation.

**Layout primitives.** For C-interop layout queries — `struct` field offsets, natural type sizes, alignment requirements — Corvid provides three compile-time primitives:

```
sizeof(T)              # Int — size in bytes under natural layout
alignof(T)             # Int — alignment requirement in bytes
offsetof(S, field)     # Int — byte offset of `field` within class/record `S`
```

**Rules:**

1. **Compile-time constants when possible.** For types whose layout is fully known at compile time (primitives, records, C-imported structs), these evaluate at build time. For types whose layout depends on runtime (e.g., generics not yet monomorphized), they evaluate at first use.
2. **C-interop use only.** These primitives are not idiomatic in pure Corvid code — the memory model (§6) handles layout automatically. Use them at C boundaries: reading raw buffers, computing serialization sizes, pointer arithmetic in `unsafe:` blocks.
3. **`offsetof` only on record-like layouts.** Legal on `kind: record` classes and C-imported structs. Rejected on `kind: regular` (handle-based, no stable layout guarantee) and value types without fields.

### 9.3 Objective-C Interop

**Native — same runtime, direct calls, no bridge layer.**

#### 9.3.1 Importing Objective-C

```
import objc "Foundation"
import objc "UIKit"

class MyViewController:
    metadata:
        extends: UIViewController
    state:
        table_view: UITableView?
    
    dynamic function viewDidLoad(self):
        super.viewDidLoad()
        self.table_view = UITableView()
```

Objective-C classes are usable as Corvid classes. Method calls compile to the same `objc_msgSend` invocations Clang would emit. Subclassing an ObjC class produces a subclass the ObjC runtime treats as any other. Categories work uniformly. KVO works. Swizzling works.

**ObjC subclassing uses `metadata: extends:`** — body-form only per §3.4.5. This is the sole place Corvid supports class inheritance; pure Corvid classes use composition + protocol conformance. The body-form ceremony makes the exception look like an exception at every subclass declaration.

#### 9.3.2 Type Boundary Rules

Objective-C's type system is less precise than Corvid's. Values crossing the boundary follow these rules:

**`id` → `Any`.** ObjC APIs returning `id` come into Corvid as `Any`. Method calls on `Any` are runtime-dispatched:

```
result: Any = some_objc_api()
# result.method() dispatches through the runtime; runtime error if method not found.

match result:
    case String(s): use_string(s)
    case Int(i): use_int(i)
    case _: handle_unknown()
```

Users destructure explicitly when concrete types are needed. `Any` is the honest representation of "the type system doesn't know."

**`NSError **` ↔ domain enum with `.completed(T)` / `.failed(NSError)` cases.** ObjC's error out-param convention bridges to Corvid's failure-handling model (§3.5.4). ObjC methods declared with `NSError **` are imported as returning a per-method domain enum whose success case wraps the original return type and whose failure case carries the `NSError`. Corvid methods that return a domain enum containing a `.failed(NSError)`-shaped case are lowered to ObjC as `NSError **` out-param methods; other cases in the enum are lowered to `NSError` codes under a domain-specific error code assigned by the compiler. Users who need explicit ObjC signature control use `objc_exposed:` sections (§4.10.4.4).

**Nullability round-trip.** ObjC nullability annotations map to Corvid reference-type annotations (§3.6.1): ObjC `nullable` → Corvid `T` (nil-allowed by default). ObjC `nonnull` → Corvid `T!` (non-nil contract, warns on runtime violation). Un-annotated ObjC types come into Corvid as `T` (nil-allowed) — the safe default that matches ObjC runtime truth without adding warning noise. Callers who want stricter contracts add `T!` at the Corvid call site.

**Type equivalences.** Standard mappings:
- `NSInteger` = `Int`
- `NSUInteger` = `UInt`
- `CGFloat` = `Float` (or `Double`, platform-dependent)
- `BOOL` = `Bool`
- `NSString *` = `String` (references implicit)
- Blocks (`^returnType(paramTypes)`) = function types `(paramTypes) -> returnType`
- `NSArray<T> *` = `[T]` (generics preserved where expressible)

#### 9.3.3 Exposing Corvid to Objective-C

Public Corvid APIs are **auto-derived** for Objective-C consumption. The compiler emits `-Corvid.h` files describing Corvid classes in Objective-C syntax, using the ObjC-expressible subset of the API.

Auto-derivation rules:

- Public classes with public methods are exposed as ObjC classes.
- Contracts (protocols) map to `@protocol` declarations. Default implementations become `@optional` methods.
- Sum types degrade to `NSObject` subclasses with tagged discrimination, or to `NSInteger`-tagged enums for simple sums.
- Generics preserve where expressible (single type parameter, no constraints); otherwise degrade to `id`.
- Contract preconditions/postconditions become runtime checks in method bodies (not visible in the header).
- Methods returning a domain enum with a `.failed(NSError)`-shaped case expose as `NSError **` out-param (§9.3.2).
- `async` methods expose via completion-handler convention.

**Warnings on non-round-tripping features.** When a public API uses features that don't cleanly express in ObjC, the compiler warns:

```
warning: method `process(order:)` uses sum type `OrderError`
         ObjC callers will receive this as `NSError *` with domain-encoded case
```

Users can accept the warning (auto-derivation continues), suppress with an explicit acknowledgment, or use the `objc_exposed:` section (§4.10.4.4) for fine-grained control over the ObjC-visible API surface.

#### 9.3.4 Explicit Control

For fine-grained control, use the `objc_exposed:` section:

```
expose class OrderProcessor:
    data:
        id: ProcessorID
    
    state:
        internal_state: ProcessorState
    
    # Auto-derived — visible to ObjC via generated header
    methods:
        function process(self, order: Order) -> Result<Receipt, OrderError>: ...
    
    # Only these methods are visible to ObjC, in the specified form
    objc_exposed:
        function processOrder(self, order: Order, error: NSError**) -> Bool: ...
```

The `objc_exposed:` section replaces auto-derivation for its enclosed methods, giving users precise control over ObjC signatures.

### 9.4 File-Level Mixing

**Corvid and Objective-C files can coexist in the same project.**

Build system integration:
- `.m` files compile via any Objective-C toolchain (typically Clang).
- `.cor` files compile via the Corvid compiler.
- Both produce libobjc2-compatible object files.
- Standard linker combines them.
- Runtime is shared (`libobjc2`); all interop happens through the runtime.

Cross-file references:
- `.cor` files `import objc "path/to/header.h"` to consume ObjC classes.
- `.m` files `#import "path/to/-Corvid.h"` to consume Corvid classes (auto-generated header).
- No bridging headers, no thunks, no ABI translation.

**Runtime transparency:**
- ObjC categories added to Corvid classes: work.
- Corvid extensions of ObjC classes (via `extend class NSFoo:` — §3.3.2): work.
- ObjC KVO on Corvid fields: works.
- Swizzling across the language boundary: works.
- Subclassing across the boundary (either direction): works.

The runtime does not distinguish between the languages. Both produce the same class metadata, register in the same method tables, participate in the same dispatch mechanism.

### 9.5 Swift Interop

Via C ABI bridge, similar to how Objective-C interops with Swift. Not first-class in v0.1.

Swift/Corvid interop is bounded by Swift's own ABI stability and the C ABI's expressiveness. Where Swift and Corvid share ObjC as a substrate (both descend from `NSObject`, both use `@objc` machinery), interop is straightforward. Beyond that (pure Swift structs, Swift generics, Swift protocols with associated types), no direct interop is planned.

### 9.6 Migration Paths

**Adding Corvid to a legacy ObjC project:**
1. Install Corvid compiler alongside existing ObjC toolchain.
2. Add `.cor` files to the project. They can import and be imported by existing ObjC code.
3. Optionally translate specific `.m` files to `.cor` using the translation tool (§12).
4. Existing `.m` files remain unchanged.

**Starting a new project using ObjC frameworks:**
1. Write all code as `.cor`.
2. Import ObjC frameworks via `import objc "Foundation"` etc.
3. Framework classes are usable directly. No wrappers needed.
4. Final binary links `libobjc2` and framework binaries.

**Full migration from ObjC to Corvid:**
1. Run translation tool on all `.m` files.
2. Review generated `.cor` files. Translator handles mechanical bits (syntax, headers merging, `@property` to field declarations); users refine (which fields become `data:` vs `state:`, which methods should carry contracts, which declarations to `expose`).
3. Delete original `.m` files when translations are validated.
4. Codebase is now pure Corvid.

None of these paths require rewriting existing code. Corvid adoption is additive.

---

## 10. Grammar Sketch (Bootstrap Subset)

**Scope.** This section documents the **bootstrap grammar** — the grammar the compiler always knows, sufficient to parse alias declarations, class/function/module declarations, base literals, base operators, control flow, and sections. The **effective grammar** at any point in a source is `bootstrap + in-scope aliases`. Prelude aliases (§4.20) extend the bootstrap grammar with the standard surface syntax users expect (type-expression sugars, collection literals, range operators, string interpolation, bitwise operators, nil-coalescing). User aliases (§4.16) extend further at module scope.

Everything not documented here as bootstrap grammar comes from aliases. See §4.20 for the prelude declarations that define standard surface syntax.

```
program := (module_decl)? import* top_level_item*

module_decl := 'module' qualified_name

top_level_item := section_usage | declaration | alias_decl | alias_use

# Alias declarations (§4.16). One unified shape; category emerges from expansion.
alias_decl := 'expose'? 'alias' alias_signature ':' alias_expansion

alias_signature := identifier                                        # section/type/value/module alias name
                 | identifier generic_params                         # parameterized type alias
                 | identifier identifier                              # class-kind alias: `<keyword> <Name>`
                 | identifier identifier generic_params               # class-kind with generics

# The expansion is either a single line (type/value/module) or an indented body
# containing the target header and a `...` placeholder for the use-site body.
alias_expansion := line_expr                                         # single-line expansion (no body)
                 | INDENT expansion_body DEDENT                       # multi-line with body form

expansion_body := ( statement | placeholder | section_usage | 'class' identifier ':' INDENT expansion_body DEDENT )*

placeholder := '...'                                                 # body-content hole (§4.16.1)

# Aliases are used wherever their bound position permits identifiers of the appropriate kind.
# The compiler resolves alias use during a name-resolution pre-pass (§4.16.11).

# `sugar_class_decl` retired in v0.7.2 — class-kind sugar is now alias-driven.
declaration := class_decl
             | function_decl | constant_decl | type_decl

section_usage := section_header ':' INDENT section_contents DEDENT

section_header := section_name (WS section_name)*
                # Multiple section names in one header = inline composition.
                # All named sections must have composition_mode: inline.
                # Nested composition is expressed via indented nested section_usage.

section_name := identifier ('(' arg_list ')')?
              # identifier may name a section class OR an alias.

section_contents := field_decl* | method_decl* | statement* | declaration*
                  | expression | ast_content
                  | metadata_entry* | case_decl* | accepts_decl*

class_decl := 'expose'? 'extend'? 'class' identifier
              generic_params? conformance_clause? where_clause? ':' INDENT
                  class_member*
              DEDENT
              # `extend` prefix (§3.3.2, v0.9.0) marks the declaration as an
              # addition to an existing class of the same name. `extend` cannot
              # coexist with `metadata: kind: <kind>:` (kind is fixed at definition)
              # and cannot declare new `state:` fields (memory layout is fixed).
              # `expose` is legal on either form; on an extension it exposes the
              # extension's members, not the target class itself.
              #
              # Header ordering (v0.10.2): generic_params, then conformance_clause,
              # then where_clause. Reads left-to-right as
              #   "class NAME <generics> (conformances) where <constraints>:"

# conformance_clause (§3.4.5, v0.10.2): parens-delimited list of protocol types
# the class explicitly conforms to. Compile-time-validated at the declaration site.
# Body-form equivalent: `conforms: <protocol_type>` entries at class-body top level,
# or under `metadata:`. Both forms desugar to the same conformance-set entry.
# See §3.4.5 for semantics, extension composition, and interaction with
# structural conformance.
conformance_clause := '(' protocol_type (',' protocol_type)* ')'
protocol_type      := type_expression     # per §3.10 type-expression grammar
                                          # must resolve to `kind: protocol` at composition time

# Removed in v0.11 (deprecated in v0.10.2 with grace period): the double-colon
# header form
#   class Foo: A, B:           (protocol conformance — use `class Foo(A, B):`)
#   class Foo: BaseClass:      (ObjC inheritance — use `metadata: extends: BaseClass`)
# v0.11 parser emits `retired-conformance-syntax` error with fix-it. See §3.4.5
# retirement subsection for migration.

# where_clause (§3.3.2, §3.3, v0.9.0): generic-parameter constraints at declaration
# position. Composes with `generic_params?` and reuses the same constraint syntax
# that appears on `function <T: Bounded>(...)`. Extensions on generic classes
# use this to express conformance-conditional applicability:
#   extend class Array<T> where T: UnsignedInteger, T: Bounded: ...
where_clause := 'where' where_atom (',' where_atom)*
where_atom   := identifier ':' identifier             # type-parameter conformance

class_member := metadata_section | section_usage
              | method_decl | constructor_decl | destructor_decl
              | case_decl | accepts_decl
              # methods without enclosing section are in implicit `methods:` section
              # metadata_section must appear first if present (§4.10.8)
              # method_decl may carry the `replace` modifier (§3.3.2) to shadow
              # a same-named method from the original definition or another extension.

metadata_section := 'metadata' ':' INDENT metadata_entry+ DEDENT

metadata_entry := identifier ':' metadata_value
metadata_value := expr | identifier | list_expr    # e.g. `record`, `[macos, ios]`, `"0.6.0"`

# `cases:` section content and `accepts:` section content.
case_decl := 'case' identifier ( '(' associated_data ')' )? ( '=' expr )?
             # `case name` | `case name(field: Type, ...)` | `case name = 1000`

accepts_decl := 'accepts' identifier '(' parameter_list ')' ('->' type)?
                effect_clause? contract_clause?
              # inside `kind: protocol`. Body-less; contracts allowed.

member_decl := generator_decl | processor_decl | validator_decl
             | method_decl | constructor_decl | destructor_decl

generator_decl := 'generator' identifier '(' parameter_list ')' '->' type
                  ':' INDENT statement* DEDENT

processor_decl := 'processor' identifier '(' parameter_list ')' '->' type
                  ':' INDENT statement* DEDENT

validator_decl := 'validator' identifier '(' parameter_list ')'
                  ':' INDENT statement* DEDENT

method_decl := 'expose'? ('replace')? 'function' identifier
               generic_params? '(' parameter_list ')' ('->' type)?
               effect_clause? contract_clause?
               ':' INDENT statement* DEDENT
              # `replace` (§3.3.2, v0.9.0) is legal only inside an `extend class`
              # body and marks explicit intent to shadow a same-named method from
              # the target class's original definition or another extension.
              # Without `replace`, a same-name conflict is a compile error.
              #
              # Removed in v0.11: the `mutating` modifier — see §3.5.2 retirement.
              # Method mutation is inferred from body assignments by §8.4.1
              # reader/writer classification; no author declaration needed.

# Binding rule for `ensures` expressions inside `contract_clause` (v0.8.7, §3.4.3):
# Within an ensures expression, `self` and parameter identifiers refer to the
# entry-state snapshot (values captured at method entry). `result` refers to the
# post-state: the returned value for methods with a declared return type, or the
# receiver at method exit for methods with no declared return type. `requires`
# expressions see `self`/params as entry-state; `result` is not in scope. No
# `old(...)` form — dissolved in v0.8.7 in favor of the natural naming above.

# Effect list (§3.5). Failure is NOT an effect in v0.8.5+ — failable functions
# express failure via return type (nil-punning or domain enum, §3.5.4).
effect_clause := effect_atom (',' effect_atom)*
effect_atom   := 'async' | 'allocates' | 'blocks' | identifier
              # `identifier` supports effect-polymorphism parameters (§3.5.3)
              # and user-declared effect aliases via `alias @name: async, allocates`.
              # `mutating` removed v0.11 — see §3.5.2 retirement (reader/writer
              # classification §8.4.1 infers mutation from body assignments).

# Parameter defaults (§3.8.5). Default expression must be a compile-time constant:
# primitive literal, nil, or value-alias reference. Collection literals and calls
# are rejected. Defaulted parameters must appear after non-defaulted ones.
parameter_list := parameter (',' parameter)*
parameter      := identifier ':' type ('=' const_expr)?
const_expr     := primitive_literal | 'nil' | value_alias_reference

field_decl := 'expose'? ('immutable' | 'mutable')? ('weak' | 'unowned')?
              identifier ':' type ('=' expr)?

# Expression forms (§3.8).
expr := ... | if_expr | switch_expr | block_expr | while_expr | for_expr | loop_expr
      | closure_literal | function_literal | arrow_literal
      | if_let_expr | guard_let_stmt

if_expr := 'if' expr ':' expr_body ('else if' expr ':' expr_body)* ('else' ':' expr_body)?
switch_expr := 'switch' expr ':' INDENT case_arm+ DEDENT
block_expr := 'do' ':' INDENT statement* DEDENT
while_expr := 'while' expr ':' expr_body                # returns Bool; bootstrap primitive (§4.20.3)
loop_expr := 'loop' ':' INDENT statement* DEDENT         # returns type of `break value`; bootstrap primitive (§4.20.3)

# `for_expr` retired from bootstrap in v0.8.3. `for x in y: body` is a prelude alias
# expanding to `y.iter().for_each { x -> body }` on any Iterable<T> conformer (§3.10.1, §4.20.1).

# Scoped nil-unwrap (§3.6.1). Multi-binding form: all bindings must succeed.
let_binding    := 'let' identifier '=' expr
let_binding_list := let_binding (',' let_binding)*
if_let_expr    := 'if' let_binding_list ':' expr_body ('else' ':' expr_body)?
guard_let_stmt := 'guard' let_binding_list 'else' ':' INDENT statement* DEDENT
                # else branch must exit (return / break / continue / panic)

# Closures (§5.6). All desugar to anonymous classes with a `call` method.
closure_literal := capture_list? function_literal
                 | capture_list? arrow_literal
                 | method_reference

capture_list := '[' capture_entry (',' capture_entry)* ']'
capture_entry := ('weak' | 'unowned')? identifier
               | identifier ':' capture_mode              # e.g. `x: copy`

function_literal := 'function' '(' parameter_list ')' ('->' type)?
                    effect_clause? ':' expr_body

arrow_literal := ( identifier | '(' identifier (',' identifier)* ')' | '_' )
                 '=>' expr

method_reference := identifier ('.' identifier)+   # e.g. `self.foo`, `MyType.bar`

quote_expr := 'quote' ':' INDENT ast_content DEDENT

ast_content := <arbitrary AST with ${expr} splice points>

# Bootstrap type expressions (§3.2 for full type-expression sugar via prelude).
# Only identifier types with optional generic args are bootstrap; brackets, braces,
# arrows, and suffix nullability are prelude aliases (§4.20.1).
type := qualified_name generic_args?

# Bootstrap expression grammar. Member access, call, subscript, base literals,
# bootstrap operators only. Richer literals (collections, ranges, interpolated
# strings) and prelude operators (bitwise, ??, ..<, ...) are prelude aliases.
member_access := expr '.' identifier
              # `.` operates on both non-nil and nil receivers (§3.6.1, §5.7).
              # Nil receivers auto-lift return type or dispatch via on_nil section.

# Leading-dot shorthand (§3.7.2). Resolved during type checking: `.name` and
# `.name(args)` resolve to `<InferredType>.name` when the compiler can
# unambiguously infer the target type from context (switch scrutinee, function
# parameter type, return type, field/variable declared type, comparison target).
# Ambiguous contexts produce a compile error.
leading_dot_expr := '.' identifier ( '(' arg_list ')' )?

# Base literals (§2.7.1). Prelude literals (arrays, maps, sets, ranges, interpolation)
# are prelude aliases, not bootstrap.
literal := int_literal | float_literal | char_literal | string_literal_raw
        | bool_literal | nil_literal

int_literal   := dec_int | hex_int | bin_int | oct_int
dec_int       := digit ('_' | digit)*
hex_int       := ('0x' | '0X') hex_digit ('_' | hex_digit)*
bin_int       := ('0b' | '0B') bin_digit ('_' | bin_digit)*
oct_int       := ('0o' | '0O') oct_digit ('_' | oct_digit)*
float_literal := dec_int '.' dec_int (('e' | 'E') ('+' | '-')? dec_int)?
              | ('0x' | '0X') hex_digit+ ('.' hex_digit+)? ('p' | 'P') ('+' | '-')? dec_int
char_literal  := "'" (char_atom | escape_seq) "'"
string_literal_raw := '"' (char_atom | escape_seq)* '"'
              # Prelude string interpolation alias operates on this raw form.
bool_literal  := 'true' | 'false'
nil_literal   := 'nil'
escape_seq    := '\\' ('n' | 't' | 'r' | '\\' | "'" | '"' | '0'
                       | 'x' hex_digit hex_digit
                       | 'u' '{' hex_digit+ '}')

# Bootstrap operators (§3.9). Prelude operators (bitwise, ??, ranges) come from prelude aliases.
expr := ... | unary_expr | binary_expr | literal

unary_expr  := ('-' | 'not') expr
binary_expr := expr binop expr
binop := '*' | '/' | '%'
       | '+' | '-'
       | '<' | '<=' | '>' | '>='
       | '==' | '!='
       | 'and' | 'or'

assignment_expr := lvalue assignop expr
assignop := '=' | '+=' | '-=' | '*=' | '/=' | '%='

# Module-level immutable bindings (§7.1).
module_let := 'expose'? 'let' identifier ':' type '=' expr

# Layout primitives (§9.2) — C-interop only.
layout_primitive := 'sizeof'   '(' type ')'
                  | 'alignof'  '(' type ')'
                  | 'offsetof' '(' type ',' identifier ')'
```

**Alias-signature pattern grammar** (§4.16.1, §4.16.16). The alias mechanism extends the base grammar with new productions at the position and precedence declared. User aliases require the `@` marker on the trigger; prelude aliases are unmarked (§4.16.16, §4.20).

```
alias_decl := 'expose'? 'alias' alias_signature alias_modifiers ':' alias_expansion

alias_signature := prelude_signature | user_signature
                # `prelude_signature` may appear only in prelude source (`stdlib/prelude/`);
                # user code MUST use `user_signature` (compile error otherwise).

# Prelude signatures — unmarked. Reserved for prelude sources.
prelude_signature := identifier_pattern
                   | infix_pattern
                   | prefix_pattern
                   | suffix_pattern
                   | delimited_pattern
                   | keyword_pattern
                   | interpolation_pattern

# User signatures — every trigger carries `@`.
user_signature    := user_identifier_pattern
                   | user_infix_pattern
                   | user_prefix_pattern
                   | user_suffix_pattern
                   | user_delimited_pattern
                   | user_keyword_pattern
                   | user_interpolation_pattern

user_identifier_pattern    := '@' identifier (generic_params)?
user_keyword_pattern       := '@' identifier identifier (generic_params)?     # e.g. `@entity Name`
user_infix_pattern         := '<' identifier '>' '@' operator_token '<' identifier '>'
user_prefix_pattern        := '@' operator_token '<' identifier '>'
user_suffix_pattern        := '<' identifier '>' '@' operator_token
user_delimited_pattern     := '@' identifier? '[' '<' identifier '>' ']'
                            | '@' identifier? '{' '<' identifier '>' '}'
                            | '@' identifier? '{' '<' identifier '>' ':' '<' identifier '>' '}'
                            | '@' identifier? '(' '<' identifier '>' ')'
user_interpolation_pattern := '@' identifier '<' identifier '>'                              # e.g. `@mention<id>`
                            | '@' operator_token '<' identifier '>' operator_token           # e.g. `@%<name>%`

# Prelude versions (unmarked) — same shapes without the '@'.
identifier_pattern    := identifier (generic_params)?
keyword_pattern       := identifier identifier (generic_params)?
infix_pattern         := '<' identifier '>' operator_token '<' identifier '>'
prefix_pattern        := operator_token '<' identifier '>'
suffix_pattern        := '<' identifier '>' operator_token
delimited_pattern     := '[' '<' identifier '>' ']'
                       | '{' '<' identifier '>' '}'
                       | '{' '<' identifier '>' ':' '<' identifier '>' '}'
                       | '(' '<' identifier '>' ')'
interpolation_pattern := '$' '{' '<' identifier '>' '}'

alias_modifiers := ('precedence' ':' integer)?
                   ('associativity' ':' ('left' | 'right' | 'none'))?
                   ('in' position_marker)?
                   ('where' guard_expr)?

position_marker := 'expression_position' | 'type_position'
                 | 'section_header_position' | 'declaration_position'
                 | 'string_interpolation_position'

guard_expr := '<' identifier '>' 'is' type_pattern
type_pattern := 'value_type' | 'reference_type' | 'Numeric' | 'Bool'
              | '[' type_pattern ']' | '[' 'KeyValuePair' ']' | '[' 'Expression' ']'
              | identifier

alias_expansion := line_expr                                  # single-line, no body
                 | INDENT expansion_body DEDENT                # multi-line with body form

expansion_body := ( statement | placeholder | section_usage | class_decl_form )*
placeholder := '...'                                          # body-content hole (§4.16.2)
```

**Alias-trigger productions at use sites** (§4.16.16). The parser recognizes `@` as an alias-trigger sigil in the bootstrap grammar. Every use of a user alias is self-flagged; the alias table is consulted only when `@` appears.

```
# `@` is a reserved bootstrap sigil; it does not appear as a bare operator or identifier
# outside of alias declarations and alias use sites.

alias_use := user_alias_expr | user_alias_type | user_alias_section | user_alias_decl

user_alias_expr     := '@' identifier ( '(' arg_list ')' )?                       # substitution / call form
                     | '@' identifier? delimited_content                          # delimited literal
                     | expr '@' operator_token expr                               # user infix
                     | '@' operator_token expr                                    # user prefix
                     | expr '@' operator_token                                    # user suffix

user_alias_type     := '@' identifier ( generic_args )?                           # type substitution
                     | type '@' operator_token                                    # user type suffix

user_alias_section  := '@' identifier ':' INDENT section_contents DEDENT          # user section-composition

user_alias_decl     := '@' identifier identifier ':' INDENT class_member* DEDENT  # user class-kind

# String-interpolation alias use — recognized by the string scanner when it encounters '@'
# inside a string_literal_raw. Prelude interpolation uses '$'; user interpolation uses '@'.
```

**Retired in v0.8.5** (v0.8.4 → v0.8.5): `throws` removed from `effect_clause`. `throw` removed from `guard` / `guard let` exit-verb list (exits: `return` / `break` / `continue` / `panic`). No `try_expr` / `catch_clause` / `finally_clause` productions added. No `Result<T, E>` in prelude. Failure expressed via return type (§3.5.4).

**Added in v0.8.4** (v0.8.3 → v0.8.4): `leading_dot_expr` production. `.name` and `.name(args)` resolve to `<InferredType>.name` in target-typed contexts (§3.7.2). Nested type declarations remain unsupported (§3.3.1); types associated with a class use `<ClassName><Role>` naming convention at module scope.

**Retired in v0.8.3** (v0.8.2 → v0.8.3): `for_expr` production removed from bootstrap grammar. `for x in y: body` is now a prelude alias (§4.20.1) expanding to `y.iter().for_each { x -> body }` on any `Iterable<T>` conformer (§3.10.1). `while_expr` and `loop_expr` remain bootstrap — they are the primitives stdlib uses to implement iteration methods (§4.20.3). No destructuring bindings added (§3.7.1); multi-value carriers use named-accessor classes `Pair<A, B>` / `Entry<K, V>` from the prelude (§3.10.2).

**Added in v0.8.2** (v0.8.1 → v0.8.2): `@` reserved as alias-trigger sigil in bootstrap. `user_signature` and `alias_use` productions added; user-defined aliases require `@` in declaration and use (§4.16.16). Prelude aliases remain unmarked and reserve the unmarked forms. `|>` explicitly rejected from the prelude on paradigm-mismatch grounds (§4.20.3); projects wanting pipe declare it as `alias <lhs> @|> <rhs> ...` locally.

**Retired in v0.8.0** (v0.7.2 → v0.8.0): grammar productions for type-expression sugar (`T?`, `T!`, `[T]`, `{K:V}`, `{T}`, `(A,B) -> C`) removed from bootstrap. Bitwise operators (`&`, `|`, `^`, `~`, `<<`, `>>`) and their compound-assignments removed from bootstrap. Nil-coalescing `??` removed from bootstrap. All now defined as prelude alias declarations (§4.20). Bootstrap grammar is intentionally minimal — sufficient to parse the alias mechanism itself, plus prelude aliases add the standard surface syntax.

**Retired in v0.7.2** (v0.7.1 → v0.7.2): `sugar_class_decl` and `sugar_kind` productions removed. Class-kind sugar (`record`, `enum`, `protocol`, `section`) expressed via `alias_decl` mechanism (§4.16.4), sugars live in prelude (§4.20).

**Retired in v0.7** (v0.6 → v0.7): value-position `!` (force-unwrap) and `?.` (optional chaining) productions removed. `.` is the only member-access operator. Explicit panic on nil uses `x ?? panic("...")`.

**Retired from prior drafts** (v0.5 → v0.6): `struct`, `enumeration`. Class-kind sugar was introduced as language-committed keywords in v0.6 and re-expressed as stdlib aliases in v0.7.2.

**Retired earlier** (v0.5.0 → v0.5.1): `sealed`, `dynamic`, `open`, `public`, `private`, `package` visibility. Modules no longer carry dispatch policy annotations (§5.4).

---

## 11. Worked Example

A complete small program demonstrating sections at multiple scopes:

```
module com.example.orders

from com.example.inventory import Inventory, InventoryError

## An order is data — pure data class.
class Order:
    data:
        id: OrderID
        items: [Item]
        placed_at: Timestamp
    
    function is_valid(self) -> Bool:
        return not self.items.is_empty and self.total() > 0
    
    function total(self) -> Money:
        return self.items.sum(by: .price)

enum ProcessOutcome:
    case completed(receipt: Receipt)
    case invalid_order
    case out_of_stock(item: Item)
    case payment_failed(reason: String)

enum CancelOutcome:
    case cancelled
    case not_found
    case already_shipped

protocol OrderProcessor:
    accepts process(Order) -> ProcessOutcome
        async
        requires order.is_valid()

## OrderProcessor is behavior — mixed class with data and state.
## `expose` on the class adds the type name to the module's public surface;
## members opt in individually.
expose class DefaultOrderProcessor(OrderProcessor):
    expose data:
        id: ProcessorID
        created_at: Timestamp
    
    state:
        inventory: Inventory              # internal by convention (no `expose`)
        weak delegate: OrderProcessorDelegate?   # exposed via the `expose state:` header below
        pending: [Order] = []
    
    expose state:
        # Alternative shape: an entire section header exposed.
        # Fields defined here are all exposed.
        # In this example we've kept inventory internal; delegate could move here.
        current_load: Int = 0
    
    function constructor(self, id: ProcessorID, inventory: Inventory):
        self.id = id
        self.created_at = Timestamp.now()
        self.inventory = inventory
    
    # Implicit methods: section for these methods.
    
    expose async function process(self, order: Order) -> ProcessOutcome
            requires order.is_valid():
        self.notify_will_process(order)
        
        switch await self.inventory.reserve(order.items):
            case .unavailable(let item):
                return .out_of_stock(item: item)
            case .reserved:
                receipt = await self.charge_and_ship(order)
                self.notify_did_process(order, receipt)
                return .completed(receipt: receipt)
    
    expose function cancel(self, id: OrderID) -> CancelOutcome:
        return self.inventory.release_reservation(id)
    
    # Not exposed — internal implementation detail. External callers get a
    # warning if they reference this from another module.
    async function charge_and_ship(self, order: Order) -> Receipt:
        ...
    
    function notify_will_process(self, order: Order):
        self.delegate.order_processor(self, will_process: order)
    
    function notify_did_process(self, order: Order, receipt: Receipt):
        self.delegate.order_processor(self, did_process: order, receipt: receipt)

expose protocol OrderProcessorDelegate:
    accepts order_processor(Self, will_process: Order)
    accepts order_processor(Self, did_process: Order, receipt: Receipt)
```

Example with primitive composition (expanded from aliases):

```
# `data:` is an alias for `runtime immutable identity`. These are equivalent:

class Order:
    data:
        id: OrderID
        items: [Item]

class Order:  # equivalent to above
    runtime immutable identity:
        id: OrderID
        items: [Item]

# Non-standard compositions use primitives directly:

class RequestCounter:
    compile_time mutable:              # class-level mutable counter
        total_requests: Int = 0
        active_requests: Int = 0
    
    static:                            # class-level constants (compile_time immutable)
        MAX_CONCURRENT: Int = 100

class SecureVault:
    # Internal-by-convention identity fields.
    # No `expose` — external code gets a warning on reference.
    runtime immutable identity:
        internal_seed: Bytes
        vault_id: VaultID
```

Example with user-defined alias:

```
module com.example.myapp

alias exposed_data: runtime immutable identity expose

expose class PublicRecord:
    exposed_data:
        display_name: String
        record_id: UUID

class InternalRecord:
    data:                    # no `expose` — internal by convention
        secret: Bytes
        internal_id: UUID
```

Example with user-defined sections:

```
from com.example.observability import Channel

class Counter:
    data:
        id: CounterID
    
    channel:
        value: Int = 0
        max: Int = 100

# Usage:
counter = Counter(id: CounterID.generate())
counter.sections.channel.subscribe(function(name, old, new):
    print("$name: $old → $new")
)
counter.value = 5  # publishes notification via generated setter
```

Example with function-body sections (implemented in later stages):

```
function compute_something(x: Int) -> Int:
    validate:
        require x > 0
        require x < 1000
    
    pure:
        result = x * 2 + 1
        result = result * result
    
    return result
```

Example section class definition:

```
section class Channel:
    data:
        contains: fields
        scope: [class_body]
        default_mutability: mutable
        contributes_to_equality: false
    
    state:
        subscribers: [Subscriber] = []
    
    generator field_setter(field: FieldSpec) -> Code:
        return quote:
            function set_${field.name}(self, new_value: ${field.type}):
                old = self._storage.${field.name}
                self._storage.${field.name} = new_value
                self.sections.channel.notify(
                    field: ${field.name.as_string},
                    old: old,
                    new: new_value)
    
    function subscribe(self, callback: (String, Any, Any) -> Unit) -> Subscription:
        subscription = Subscription(callback)
        self.subscribers.append(subscription)
        return subscription
    
    function notify(self, field: String, old: Any, new: Any):
        for sub in self.subscribers:
            sub.callback(field, old, new)
```

---

## 12. Objective-C Compatibility

**Answer:** New language with **file-level compatibility**, backed by shared runtime substrate and a translation tool.

The intent-not-authority principle makes deep interop clean. Objective-C's runtime semantics are already fully dynamic; Corvid's are also fully dynamic. Both languages target the same runtime (`libobjc2`), produce the same class metadata, and share the same dispatch mechanism. There is no bridge layer.

**Practical shape:**

- **Same project, mixed files.** `.m` and `.cor` files coexist. Each is compiled by the appropriate toolchain; both produce libobjc2-compatible object files; the standard linker combines them.
- **No runtime translation cost.** Cross-language calls are just runtime method dispatch. There is no boxing, no unboxing, no type marshalling, no ABI bridging.
- **Auto-generated headers.** Corvid classes are exposed to ObjC via auto-generated `-Corvid.h` files. Public APIs are visible to ObjC consumers automatically, with warnings when features don't round-trip.
- **Translation tool available.** Users who want to convert `.m` to `.cor` run a translator that handles the mechanical work. Not required for adoption.

**Rejected alternatives:**

- **Full source-level superset of Objective-C.** Would preserve `[[NSMutableArray alloc] init]`, `@interface`/`@implementation` split, header files, and `@property` storage-attribute soup. Constrains syntax irreversibly.
- **Corvid → ObjC source as the primary compilation strategy.** Used as bootstrap and alternative output path but not primary; LLVM IR direct is the production target.
- **Corvid → ObjC downgrade for library consumers without Corvid compiler.** Would let ObjC consumers use libraries written in Corvid without adopting the compiler, but creates ecosystem fragmentation and disincentivizes adoption. Libraries requiring Corvid features require the Corvid compiler.

**Section-mediated translation.** The translation tool has non-trivial semantic decisions to make when converting `.m` files:

- **`@property` decomposition.** ObjC properties are mutable, public, sometimes copy-on-set. Translator emits them as `state:` section fields unless the user hints otherwise.
- **`_ivar` fields.** Direct instance variables (not property-backed) become `state:` section fields marked as `private` unless the source file marked them `@public`.
- **Class methods (`+` prefixed).** Become methods in a `static:` section.
- **Categories.** Become `extend class TargetClass:` declarations (§3.3.2).
- **`@protocol` declarations.** Become `protocol` declarations. Optional methods become default implementations returning appropriate defaults, with a warning noting the semantic shift.
- **Nullability.** Preserved where annotated; warned where implicit.
- **Method families (`init`, `copy`, `mutableCopy`).** Translated to constructor and copy protocols where possible.

The translator produces output that compiles cleanly under the Corvid compiler but is not necessarily idiomatic. Users review and refine — typically moving stable-identity fields from `state:` to `data:`, adding contracts to methods, marking the module's stable interface with `expose`.

---

## 13. Explicitly Deferred *[UNRESOLVED]*

**Resolved in v0.5** *(previously deferred, now specified):*
- Session types → emerge from class-level invariants + method contracts (§3.4.2). Not a separate feature.
- Effect polymorphism → generic parameters with `E: Effect` (§3.5.3). Explicit form + inference sugar.
- Pattern matching → `switch` with structural patterns and exhaustiveness (§3.7). Sealed protocols enable exhaustive matching over conformers.
- User-alias export → aliases participate in module import/export (§4.16). Public aliases visible to importers.
- Flat composition sugar → removed from deferred; nesting handles the semantic space.
- Reflection API surface → resolved as class metadata (§4.18) with three consumers (LSP, runtime queries, AI-manifest).
- Memory model → resolved as extent-based storage with tags, snapshots, universal handles, ARC baseline, FilC-style safety (§6).

**Still deferred:**

1. **Full comptime metaprogramming** — beyond section generators. Sections + contracts cover the common metaprogramming needs. Full comptime (Zig-style arbitrary compile-time execution) is additive if future needs demand it. Deferring costs nothing given the boundary in §4.17.

2. **Ownership types** — beyond ARC. Rust-like `borrow`/`move` for zero-overhead cases could come in Stage 3+ as an opt-in section (`borrowed:` restricting field access). ARC handles the common case.

3. **Standard library scope** — built-in vs. package boundary. Post-language-ship decision. `core` package always available; everything else via package manager. `Tag<T>` and `Snapshot<T>` in core.

4. **Package manager and build system** — implementation concern; steal from Cargo/npm when we ship v1.0.

5. **`Self` in protocols** — nominal (Rust-style) recommended (§10.2 recommendation); confirmation pending.

6. **Preemptive threading beyond `unsafe:`** — Stage 1 restricts explicit threads to `unsafe:` contexts. Stage 3+ may add safe explicit-thread APIs. Cooperative async covers most needs.

7. **Section versioning scheme** — semver on section classes; precise mechanics pending.

8. **Higher-order sections** — sections taking other sections as parameters. Deliberately excluded from v0.5 (§4.17 boundary). Syntactic space reserved.

9. **Compile-time state tracking for invariants** — Stage 1 uses runtime enforcement; compile-time enforcement Stage 3+ (§3.4.2).

10. **Extent-scoped cycle detection** — Stage 2+ optional library. Precise scheduling and API design pending real usage patterns.

11. **Adaptive storage layout switching** — Stage 3+ optimization. Runtime picks AoS/SoA based on observed access patterns, not just `dense_storage:` hint.

---

## 14. Implementation Stages

The language ships usable at each stage. Programs written for earlier stages benefit from later stages without source changes.

### Stage 0 (Bootstrap) — Corvid → Objective-C Source
- Compiler is written in Objective-C, produces `.m` files from `.cor` input.
- No LLVM IR generation yet.
- Sections implemented for the class-body-only subset. Primitives, aliases, and stage-1 behavioral sections.
- Output `.m` files are ugly but semantically correct. Users compile them with Clang for actual execution.
- Purpose: bootstrap a working compiler quickly so we can write the production compiler in Corvid itself.
- Not shipped as a production tool. Alternative output path preserved permanently (for debugging, ObjC-only-toolchain environments).
- Estimated engineering: 3-6 months for a small team.

### Stage 1 (v0.1) — All Dynamic, LLVM IR Direct, Incremental with LSP
- Compiler is rewritten in Corvid using the Stage 0 bootstrap.
- **Architecturally incremental** — holds state between invocations, supports language-server mode as first-class operation (not a Stage 2+ afterthought).
- Compiler produces LLVM IR that speaks libobjc2 ABI directly.
- Compiler emits **class metadata artifacts** alongside object files (§4.18). Both the runtime metadata format and AI-manifest JSON export.
- **LSP server** shipped as part of the compiler. VS Code, Zed, Neovim, Xcode, JetBrains all get support via LSP client.
- All methods compile to message sends by default. The compiler devirtualizes automatically where safe (§5.2).
- Runtime uses `libobjc2` unmodified plus a small Corvid-metadata wrapper layer.
- Built-in **primitive sections**: `Runtime`, `Compile_time`, `Immutable`, `Mutable`, `Expose`, `Weak`, `Unowned`, `Identity`, `Coordination`. All inline-composable. (Removed from earlier drafts: `Public`, `Private`, `Package`, `Sealed`, `Dynamic` — see §1.2, §5, §7.7.)
- Built-in **aliases**: `data`, `state`, `static`, `methods`. Language-provided; user aliases also supported at module level.
- Built-in **behavioral sections**: `Computed`, `Deprecated`, `Category`, `ObjC_Exposed`, `With`, `Channel`, `Signal`, `Package`. Class-body scope for most; `Package` at source-file-top and module-body scope.
- Built-in sections have hardcoded describers (feed metadata artifacts).
- Section classes not user-definable in Stage 1; built-ins are hardcoded.
- `label:` syntactic space reserved at all scopes (compile error if used outside class bodies).
- Class-level `invariants:` supported; **runtime-enforced** in Stage 1. Contract-carrying methods on mutable state get atomic transition semantics (§1.9, §8.4).
- **Structured async** — `async_scope`, `spawn`, cancellation, error propagation. Work-stealing thread pool for scheduling.
- **State machine synchronization** — `requires`/`ensures` on mutable state enforce atomicity via runtime locks. Async methods wait on unmet preconditions; sync methods fail immediately.
- **Explicit threads via `unsafe:` only** — no safe explicit-thread API in Stage 1.
- File-level ObjC compatibility working (import, auto-derived headers, category support).
- ObjC concurrency interop: GCD dispatch queue bridging, completion-handler ↔ async conversion, `@main_actor` for main-thread affinity.
- Translation tool (`.m` → `.cor`) shipped as separate binary.
- Runtime metadata query API (§4.18.3) for cross-module lookups.
- Estimated engineering: 18-24 months (incremental compilation and LSP are load-bearing additions to earlier 12-18 month estimate).

### Stage 2 (v0.5) — Whole-Module Devirtualization + User-Definable Sections
- Compiler devirtualizes methods with whole-module analysis; call sites deopt on runtime modification.
- **User-definable section classes** for class-body scope. `Data`, `State`, `Methods` reimplemented as ordinary section classes.
- **User-definable describers** — user section classes can contribute to class metadata artifacts. LSP/AI tooling gains full support for user-defined sections.
- `quote:` available inside generators as a specific block form (not yet a general section).
- Ecosystem sections (`channel:`, `persistent:`, etc.) become writable by library authors.
- Estimated engineering: additional 6–9 months.

### Stage 3 (v1.0) — Cross-Module Deopt + Function/Method Body Sections
- Runtime call-site invalidation machinery. Sealed call sites patched on swizzle.
- Function-body and method-body sections. Built-in: `pure:`, `unsafe:`, `validate:`, `quote:` (generalized from generator-body use).
- Section-class-to-section-class composition (self-hosting fully enabled).
- Performance parity with Swift for whole-module-optimized workloads (equivalent of Swift's whole-module optimization pass).
- Estimated engineering: additional 9–15 months.

### Stage 4 (v1.5+) — Module-Body Sections, Optimizations
- Module-body sections. Built-in: `interop:`, `test:`, `platform(...):`.
- Devirtualization across generic instantiations.
- Inline caching improvements.
- Optional SMT-backed contract verification.

The critical property: **user code doesn't change between stages.** The semantic model is fixed at v0.1; later stages only add capabilities and optimize.
