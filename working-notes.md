# Corvid Working Notes — Design Rationale and Open Items

**Purpose:** consolidate the reasoning behind the current spec (v0.4) and enumerate what's still open. This document captures *why* decisions were made, not just what they are. The spec is the specification; this is the thinking behind it.

**Companion document:** `spec/spec-v0.10.md` (was `spec-v0.9.md` through v0.9.x; `spec-v0.8.md` through v0.8.x; before that `spec-v0.7.md` through v0.7.x; before that `spec-v0.6.md`; before that `corvid-spec-v0.5.md`).

## Session note — `data:` field usage-site rule; value-typed containers over regular fields (2026-08-11, no version bump)

**Continuation of tension #2 reframe.** After settling that value types compose over Bytes (Integer literally has `bytes: Bytes` as a field, per composition-is-honest reading), user surfaced a real subtlety: if Integer has a `Bytes` field where Bytes is `kind: regular` (mutable, referenced), how does Integer preserve value semantics?

**Wrong first framing (mine).** I claimed "Integer contains a reference — value semantics breaks; would need FixedBytes<N> as a new type." User pushed back: "but why isn't the value semantics defined from the Integer class itself?"

**Correct framing.** Value semantics is defined at the record class boundary (§3.3 `kind: record`), not by the field types. Records can hold `kind: regular` fields; the record boundary handles the semantics via COW/deep-copy. This is the standard VTCRT (Value Type Containing Reference Type) pattern — Swift's `struct String` internally holds a reference to a buffer, is nonetheless a value type. Same mechanism works for `record Integer: bytes: Bytes`. No new type needed.

**Then a second subtlety surfaced.** Under COW, mutation on a Bytes obtained from an Integer field triggers a copy — the Integer is unaffected. But this is silent: `int.bytes.set(0, 99)` looks like mutation, actually happens on a copy. Developer could be surprised.

**User's dissolution.** "Anything placed in `data:` should be assumed to reader only, which is what was originally intended anyway — remember, structs is class with data only."

That's the fix. `data:` isn't just a declaration marker; it's an advisory at the usage-site too. Writer calls on values obtained from a `data:` field emit a warning. No new annotation invented (`@readers_only` was mechanism-invention — sibling naming, rule 5 of the trap memory). The existing `data:` marker + existing reader/writer classification (§8.4.1) compose to give the diagnostic.

**Design chosen: (b) `data:` marker + writer diagnostic.** Not (a) immutable view type (adds `Bytes.View` as a new type, duplicates reader method surface); not (c) COW alone (silent, too implicit).

**Spec changes.**

- `spec/spec-v0.11.md` §3.3 — new paragraph after value-type base-class-property discussion: "`data:` fields carry a usage-site rule, not just a declaration-site meaning." Documents the two commitments (declaration-site data-vs-state; usage-site writer-call diagnostic). Includes example warning text (W0410 `data-field-mutation`). Flow-tracking scope: direct calls + one-hop through variable assignment; deeper chains rely on writer-effect diagnostic at the actual mutation site.
- `spec/spec-v0.11.md` §8.4.1 — added cross-reference paragraph noting that reader/writer classification composes with `data:`/`state:` at field level to enable the diagnostic.

**Mechanism-invention trap check.** Passes. Zero new mechanisms. `data:` — existing (§3.3). Reader/writer classification — existing (§8.4.1). COW under value-semantics-at-boundary — existing behavior. Diagnostic — new content, uses existing composition of mechanisms.

**Dissolution pattern.** Thirteenth session. The `data:` marker was carrying less weight than it always meant to. Making it load-bearing at the field-usage layer requires no new mechanism, only spec text acknowledging the composition of existing labels.

**No version bump.** Small clarifying additions to §3.3 and §8.4.1. Semantics were arguably always intended (pre-v0.6.0 `struct` = class with data only); v0.11 makes it explicit.

**Composition thread continues.** The `data:` usage-site rule closes the "value-type containing regular field" layering question. The composition reading (Integer literally has `bytes: Bytes`) now holds without needing FixedBytes<N> or a distinct immutable view type. Next issue in the design-coherence audit queue: to be surfaced.

---

## Session note — v0.11 auto-conformance reframed as base-class property (2026-08-11, no version bump)

**Follow-up to design-coherence audit tension #2.** The audit surfaced that v0.11's initial framing — "compiler auto-conforms value types to `As<Bytes>` and `From<Bytes>`" — was a specific-instantiation carve-out inside a general protocol (§3.4.6 vs §3.10.3). The compiler was knowing about a specific target-type name (`Bytes`) and a specific protocol instantiation (`As<Bytes>` / `From<Bytes>`); the general shape of `As<T>` says protocols are user-target-agnostic, so a compiler special-case per target name was a layering leak.

**User pushback stopped the janky "auto_derive:" metadata block proposal.** The first-cut structural fix reached for a metadata block letting protocols declare `derivable: from_field_layout` — but this would let user protocols demand compiler synthesis they can't guarantee is correct, and reintroduced compiler awareness of arbitrary protocol names. User: "this seems really janky tbrh."

**Second cut — base-class property.** Records already carry `==` / `hash` / copy-on-assign as base-class properties, synthesized from field layout at composition time. Canonical bytes is the same shape: one more base-class property on value types. The compiler synthesizes `.as() -> Bytes` and `class function from(source: Bytes) -> DecodeOutcome<Self>` because value types have canonical bytes as a fundamental property (§3.10.10 wire format); structural conformance to `As<Bytes>` and `From<Bytes>` (§3.4) falls out as a *consequence* — the required methods exist, structural check succeeds, no separate auto-conformance mechanism. The compiler stops knowing target-type names entirely; it just knows how to synthesize field-layout methods on value types.

**Documentation changes.**

- `spec/spec-v0.11.md` §3.3 (User-Defined Types) — extended `kind: record` and `kind: enum` entries to explicitly list canonical bytes alongside equality/hashing/copy-on-assign as value-type base-class properties. Added a paragraph clarifying that base-class properties are language commitments (not protocol conformances); structural conformance is the consequence.
- `spec/spec-v0.11.md` §3.4.6 — title changed from "Auto-Conformance for Value Types" to "Canonical Bytes as a Value-Type Base-Class Property." Rewrote opening: no more "compiler synthesizes conformance to As<Bytes>"; new framing is "compiler synthesizes byte methods on the class; conformance follows per §3.4." Rewrote all "auto-derived" language to "synthesized." Removed "Only As<Bytes> / From<Bytes> get auto-conformance" heading (was reifying the carve-out); replaced with "Why only bytes get the base-class property" — reframed as extending the base-property list, not a protocol-specific rule.
- `spec/spec-v0.11.md` §3.10.10 — opening reframed to "wire-format specification for the byte methods that §3.4.6 documents as value-type base-class properties."
- `spec/spec-v0.11.md` §3.10.8 — serialization substrate paragraph unchanged; "auto-conformance for value types" follow-up paragraph reframed as "Value types have canonical bytes as a base-class property" — points to §3.3 and §3.4.6.
- `spec/spec-v0.11.md` §3.11.4 — title changed from "Byte Conformances on Primitives" to "Byte Methods on Primitives"; opening explains primitives inherit the base-class property but need explicit prelude methods since they have no field layout for the compiler to synthesize from.
- `spec/spec-v0.11.md` v0.11 changelog block — rewrote status paragraph and change-log entries to reflect new framing; added a "Reframe from v0.11 initial draft" paragraph documenting the tension-2 audit and the resolution. Consistency-with-prior-decisions block updated to reference §3.3 pattern rather than §3.2. Mechanism-invention trap check block updated: "compiler synthesis of base-class methods → §3.3 pattern for records extended (same shape as existing == / hash synthesis)."
- `PATTERNS.md` "I need Codable / JSONEncoder / auto-serialization" — reframed from "auto-conformance to As<Bytes>" to "canonical bytes as a base-class property"; "auto-derived" → "synthesized"; "auto-conformance is only for As<Bytes>" heading → "only bytes get the base-class property."
- `~/.claude/memory/corvid-mechanism-invention-trap.md` — appended rule 7 (method-family shape check): distinguish strategies-competing-for-same-result-slot (→ method names, e.g., wrapping/saturating/checked) from single-canonical-operation-with-parameter-axis (→ single method with parameter, e.g., as_bytes(order:)). Meta-observation updated: trap has surfaced four times now, adding "choosing the wrong axis of variation" as the closely-related failure mode.

**Design-coherence tension #3 addressed by memory rule 7.** The tension: `.wrapping_to::<T>()` / `.saturating_to::<T>()` / `.checked_to::<T>()` got three method names for what could-in-principle be one method with a parameter; `.as_bytes(order:)` got one method with a parameter for what could-in-principle be three method names. Rule 7 gives the decision procedure: strategies (mutually-exclusive, algebraic-identity-differs-per-strategy) → method names; parameters (varies inside a single operation) → parameter. Narrowing strategies produce different values (`Int32(300).wrapping_to<UInt8>() = 44` vs `.saturating_to<UInt8>() = 255`) — different algebraic identity, strategies compete. Endianness varies which bytes come out for a single "encode this value" operation — same identity, one method, parameter axis. Both resolutions are correct under the rule.

**Composition question deferred.** User's next thread: "lets discuss if really strings, integers etc have a byte component (composition)." Open question — does the "value types HAVE canonical bytes" framing rest on a genuine composition/decomposition relation, or is it a mapping asserted at the language boundary without composition backing? To be worked next.

**No version bump.** Framing corrections in live docs + memory refinement + working-notes addendum only. The spec content lands identically at the compiled-code level; only the layering/framing/audit-narrative changes. v0.11 remains the version that introduced canonical bytes as a value-type base-class property.

---

## Session note — v0.11 Value-type auto-conformance + double-colon header form removed (2026-08-10)

Sixth same-day session in the post-v0.10 chain. User picked "1+2" from the remaining open list — v0.11 spec sections for auto-conformance direction that was framing-corrected in the previous addendum, bundled with the scheduled double-colon header form removal.

**Two things landed together in v0.11.**

**Thread 1 — §3.4.6 Auto-Conformance for Value Types (new).** Every `kind: record` and `kind: enum` automatically conforms to `As<Bytes>` and `From<Bytes>`. Compiler synthesizes `function as(self) -> Bytes` and `class function from(source: Bytes) -> DecodeOutcome<Self>` from field layout at composition time. Primitives get the same conformances shipped explicitly in prelude via `extend class Float: methods: function as(self) -> Bytes: ...` etc. (§3.11.4 new). `kind: regular` classes do NOT auto-conform — mutable state, potential cycles, identity require explicit user-written serialization. User-written `.as() -> Bytes` on a value type shadows the auto-derived one per §3.3.2 (own > inherited default).

**Thread 2 — §3.10.10 Canonical Value-Type Encoding (new).** Wire-format spec for the auto-conformance output. Deterministic, roundtrippable, byte-position-recoverable.

- **Endianness: network-order (big-endian).** Silent breakage across architectures is a footgun; native-endian users opt out by writing their own `.as() -> Bytes` on the record using each primitive's `.as_bytes(order: ByteOrder.host)` method (§3.11.4). Explicit-endian access is a specific-name method, not part of `As<Bytes>` dispatch.
- **Length prefix width: UInt32 (4 bytes).** Fixed width for decoder simplicity; up to 4 GiB per collection/string is sufficient for on-disk cache, IPC, snapshot use cases.
- **Discriminator width scales with case count.** ≤256 cases → UInt8; 257–65536 → UInt16; beyond → UInt32. Avoids the 4-byte-always-per-enum tax that a naive UInt32 discriminator would impose.
- **Optional as presence byte + payload**, not sentinel value — sentinel encoding fails for types where the sentinel value is meaningful (`Int32(0)`, `Float(0.0)`).
- **Field / case declaration order determines encoding.** Reordering is wire-incompatible; adding a mid-list case is wire-incompatible; adding a trailing case is compatible.

**Thread 3 — double-colon header form removed.** `class Foo: A, B:` and `class MyViewController: UIViewController:` now hard compile errors. v0.10.2's parse-time warning + fix-it grace period ended. Migration was mechanical (`Foo(A, B)` for conformance; `metadata: extends:` for ObjC inheritance).

**Mechanism-invention trap check.** Passes. `As<Bytes>` / `From<Bytes>` → §3.10.3, not new. Auto-derivation as base-class property → §3.2 pattern for records, not new. Wire format spec → new content but not a new mechanism. Method names uniformly `.as()` / `.from()` — never `as_bytes()` / `as_json()`; never `derive: as_bytes`. Zero sibling protocols invented; zero per-target method names invented; zero "third-party format story" deferred. Third-party formats use existing extension mechanism as previously agreed.

**Only `As<Bytes>` gets auto-conformance** — because bytes are the universal substrate with one canonical encoding. JSON/MsgPack/etc. have per-record author choices (field naming — camelCase vs snake_case? null handling? number precision? enum spelling?) with no defensible language-level default; silent choice would be a footgun. Users write `function as(self) -> Json:` explicitly.

**Design questions resolved in-session (called out in changelog):**

- **Endianness default: network-order over native-order.** Correctness across architectures wins over per-primitive byte-swap cost on little-endian hosts (single CPU instruction, negligible for the common case). Same-host performance users write their own encoding.
- **Length prefix width: fixed UInt32 over VarInt.** Decoder simplicity + seekability wins over saving 1 byte on small collections.
- **Discriminator scales with case count.** Avoids per-enum overhead tax.
- **Sentinel-Optional rejected in favor of presence byte.** Sentinel would fail for types where the sentinel value is meaningful.

**Files touched this session:**

- `spec/spec-v0.10.md` → renamed to `spec-v0.11.md`.
- Preamble bump v0.10.2 → v0.11; changelog block for both threads.
- `§3.4.6` new (~150 lines) — Auto-Conformance for Value Types.
- `§3.10.10` new (~120 lines) — Canonical Value-Type Encoding with per-type table + failure modes + guarantees + non-goals + rationale.
- `§3.11.4` new (~90 lines) — Byte Conformances on Primitives, with `Bool` / `UInt8` / `UInt32` illustrated in detail; `.as_bytes(order: ByteOrder)` explicit-endian method spec.
- `§3.11.5` renumbered from prior `§3.11.4` (Convertible retirement note).
- `§3.11.6` renumbered from prior `§3.11.5` (Bounded protocol).
- `§3.4.5` retirement subsection updated — deprecation → removal; diagnostic upgraded from warning to hard error.
- `§10` grammar deprecation note → historical-record note.
- `PATTERNS.md` — Codable / serialization entry rewritten to reflect v0.11 landing (before was "deferred to v0.11+ stdlib layer"; now shows actual auto-conformance + code example + wire format summary).
- `README.md` — status line bumped to v0.11 with three-paragraph history (v0.11, v0.10.2, v0.10.1).
- `working-notes.md` — this note.

**Remaining post-v0.11 open items:**

- Interop/stdlib deferred (F1 conditional compilation, F3/F4 CF↔Foundation bridging, F5 direct C-function invocation).
- Implementation-planning items (runtime cost model, package registry, stdlib scope).
- **Validation exercise against v0.11 surface** — translate a new file (Ink or WebSocket) to exercise auto-conformance in real code; would surface anything the spec missed.

**Dissolution-pattern update.** Twelfth session. This one shipped substantial new content (§3.4.6 + §3.10.10 + §3.11.4 = ~350 lines of new spec) but the underlying mechanism inventory is unchanged — the mechanisms `As<T>`, records, field-layout metadata, prelude extensions, method shadowing rules were all already in place. What v0.11 added was the *rule* saying "value types get this automatic" plus the *wire format* specifying what "this" produces. Pattern holds even as the language matures — new capability, no new mechanism.

---

## Session note — Value-type auto-conformance direction + framing corrections (2026-08-10, addendum, no version bump)

Continuation of the post-v0.10.2 open-items sweep. User picked "Value-type auto-derivation of `.as() -> Bytes` — deferred v0.11+" from the remaining open list. Session surfaced two mechanism-invention slips that had been carrying forward in the spec and PATTERNS.md unnoticed.

**First slip — naming variant of the trap.**

Proposed a `derive:` section with entries named `as_bytes` / `as_json` — per-target-name pattern. User caught: "what do you mean by derive here? again, this is the wrong thing, i said that we wanted `as -> bytes` or `As<Bytes>` not `as_x`." The v0.10.0 commitment: one method name `.as()` differentiated by return type via trait-qualified dispatch. Derivation entries must be `As<Bytes>` (protocol-parameterized), never `as_bytes`. Same shape as the WrappingAs slip during Convertible retirement — inventing per-target names when the committed convention has one name.

**Second slip — "third-party formats need future stdlib support" was reaching for structure.**

Flagged that third-party format packages (JSON, msgpack) would be a v0.12+ story requiring new stdlib mechanism. User pushed: "whats difficult about defining or rewriting their own? or just returning whatever As<String> means?" Answer: nothing. Third-party format packages ship their own target type + user-writable `.as() -> ThatType:` conformances via ordinary `extend class Foo:` (§3.3.2). No new language support required. My "deferred story" flag was imagined-future-mechanism.

**Both slips have the same root.** Reaching for structure the committed mechanism already provides. Sibling protocols (Convertible retirement) → sibling method names (this session, first) → imagined-future-mechanism (this session, second). Three costumes, one trap.

**Framing corrections applied.**

- **Value-type auto-derivation is not "a stdlib layer, deferred to v0.11+."** It's **auto-conformance at the base-class level for value types**, planned for v0.11 as a language rule (same shape as automatic structural equality on records per §3.2). Compiler synthesizes `.as() -> Bytes` and `class function from(source: Bytes) -> DecodeOutcome<Self>` from field layout at composition time. `kind: regular` classes still require explicit user-written serialization.
- **Third-party format story does not exist as a deferred item.** Format packages use existing extension mechanism + `As<T>` protocol, exactly like user code. Not a v0.12+ blocker; not blocked at all.
- **Only `As<Bytes>` gets auto-conformance.** Because bytes are the universal substrate with one canonical encoding. `As<Json>` / `As<Csv>` / etc. have per-record author choices (field naming, null handling, precision) — no defensible default, silent choice would be a footgun. Same principle as compile-error-on-narrowing (v0.10.1 Convertible retirement).

**Files touched this addendum.**

- `~/.claude/memory/corvid-mechanism-invention-trap.md` — added the naming-variant slip (§second-slip) and the third-party-support non-story (§third-slip); refined "how to apply" with two new checks (rule 5: naming-variant; rule 6: third-party non-story); meta-observation that the trap surfaced three times in one day at user's push.
- `spec/spec-v0.10.md` line 151 (v0.10.0 changelog serialization substrate) — reframed from "future stdlib layer deferred to v0.11+" to "auto-conformance at base-class level for v0.11"; naming-variant note (`as_bytes` / `as_json` are slips) added.
- `spec/spec-v0.10.md` §3.10.8 line 2596 — same reframing; added explicit paragraph "Auto-conformance for value types (v0.11 planned)" explaining what the direction is + why only Bytes gets it.
- `PATTERNS.md` line 218 (Codable / JSONEncoder / auto-serialization entry) — same reframing.
- `working-notes.md` — this addendum.

**Not touched (intentional).** Historical open-items lists in prior session notes (lines 42, 91, 144, 211) that carried "deferred to v0.11+ as stdlib layer" — those are session-time-of-writing snapshots and rewriting them retroactively distorts the design conversation. Corrected framing lives in this addendum and in the current live spec/PATTERNS.md.

**No version bump.** Framing corrections in live docs + memory refinement + working-notes addendum only. The v0.11 auto-conformance direction is *planned* but not shipped; when it lands (§3.4.6 new + §3.10.10 canonical wire format spec), that gets its own version bump and full changelog.

**Remaining post-v0.10.2 open items (unchanged):**

- **v0.11 auto-conformance implementation** — direction agreed, spec sections not yet authored (§3.4.6 Auto-Conformance for Value Types; §3.10.10 Canonical Value-Type Encoding).
- **Double-colon header form removal** — carried to v0.11 (v0.10.2 grace period).
- Interop/stdlib deferred items (F1, F3–F5).
- Implementation-planning items (runtime cost model, package registry, stdlib scope).

**Meta-observation for future sessions.** Every one of the trap's three surfacings this day was caught by the user, not by me running the check proactively. The memory file now carries rules 5 + 6 to catch the naming-variant and non-story flavors before proposing. Standing check for every design proposal: does the committed convention already cover this? If yes, don't restate it as a new mechanism / a new naming pattern / a new deferred story.

---

## Session note — v0.10.2 Explicit conformance syntax; parens sugar; ObjC inheritance to body-form; double-colon retired (2026-08-10)

Fifth same-day session in the post-v0.10 audit chain (v0.10.0 landing → Convertible retirement → open-items verification → Pattern subsystem → explicit conformance). User picked "Explicit `conforms: Protocol` syntax?" from the remaining post-v0.10.1 open-items list.

**Framing.** §3.4 committed "explicit conformance is legal but not required" without specifying syntax. Two shapes had appeared in the spec — header-colon `class Foo: A, B:` (5 code uses, worked example §11) and body-form `conforms: A` (used in prelude classes Bytes/String). Grammar §10 reserved `conformance_clause?` but never defined the production. Also `class MyViewController: UIViewController:` used the same header shape for ObjC subclassing (§9.3.1) — one syntax, two semantics resolved by compiler kind-check.

**Complication surfaced during migration audit.** The header-colon form was overloaded — inheritance AND conformance with the same shape. Retiring it required a decision on where inheritance goes.

**Design choice (user's, "parens sugar for class Foo(A,B)" over my initial recommendation of keeping the double-colon form).** The user pushed to retire the double-colon shape entirely in favor of parens. My follow-on analysis surfaced two viable placements for ObjC inheritance under parens:

- **Option (b):** parens holds both — first entry is base class if `kind: regular`, rest are protocols. Compiler infers roles.
- **Option (a):** parens for protocols only; ObjC inheritance moves to body-form `metadata: extends:`. Treats the exception as the exception.

**Recommended and accepted: option (a).** Rationale: pure Corvid commits to composition-over-inheritance (§1.5, §3.4); class inheritance exists only for ObjC interop. Putting the ergonomic sugar on the common case (protocol conformance) and moving inheritance to body-form makes the design commitment visible at every subclass declaration — writing `metadata: extends: UIViewController` in the body feels like the exception it is.

**Decisions in this session:**

- **§3.4.5 new (Explicit Conformance Declarations).** Both header parens sugar `class Foo(A, B):` and body-form `conforms: A` are legal and equivalent (desugar to the same conformance-set entry). Compile-time validation at declaration site is the primary payoff over structural conformance — fail at the declaration, not at the first use. Additive across extensions.
- **§10 grammar — `conformance_clause` production defined.** `conformance_clause := '(' protocol_type (',' protocol_type)* ')'`. Header ordering: `class NAME <generics> (conformances) where <constraints> :` — reads left-to-right.
- **ObjC class inheritance moves to body-form `metadata: extends:`.** Rules: one class, must be `kind: regular` + ObjC-imported, not modifiable by extensions. Pure Corvid classes cannot be subclassed.
- **Double-colon header form deprecated in v0.10.2, removed in v0.11.** Parse-time warning with fix-it suggestion. One-minor-version grace period; migration is mechanical.
- **§4.18 metadata alignment.** No schema change — existing `superclass:` and `conforms_to:` keys just get formal population semantics.

**Mechanism-invention trap check.** Passes. `conformance_clause` grammar production is a formal definition of a previously-reserved-but-undefined slot. `metadata: extends:` uses the existing metadata mechanism (§4.18) with a new key. No new mechanism invented. The retirement of the double-colon form eliminates a dual-purpose shape (mistake worth undoing) rather than adding one.

**Files touched this session:**

- `spec/spec-v0.10.md` — preamble bump to v0.10.2 + full changelog block; §3.4.5 new (~150 lines); §10 grammar `conformance_clause` production defined + header ordering reordered (`conformance_clause?` moved before `where_clause?`); §9.3.1 example rewritten for body-form; §3.4.4 line 1487 prose reference updated; §9 line 3943 prose reference updated; 4 code examples migrated to parens form; 1 code example migrated to body-form.
- `validation/websocket/01-Framer.parity.cor` — line 164 migrated to parens.
- `PATTERNS.md` — new "I need `class Foo extends Bar` / inheritance" entry; interfaces entry updated to note §3.4.5; Pattern extensibility entry updated to reflect explicit-conformance path.
- `README.md` — status line bumped to v0.10.2.
- `working-notes.md` — this note.

**Open items still standing** (v0.10.1 open list minus explicit conformance):

- **Value-type auto-derivation of `.as() -> Bytes`** — deferred to v0.11+ as stdlib layer.
- **Deprecation removal for double-colon header form** — carried to v0.11.
- Interop/stdlib deferred items (F1, F3–F5).
- Implementation-planning items (runtime cost model, package registry, stdlib scope).

**Dissolution-pattern update.** Eleventh session, but this one is different in shape — it formalized something previously left as "resolved in principle, undefined in detail" (Ink #13). The audit found an overloaded shape (double-colon = conformance OR inheritance), and the resolution split the shapes to match Corvid's design commitment. Not a dissolution — a formalization + a design-commitment surfacing. Adjacent pattern.

---

## Session note — v0.10.1 `Pattern` subsystem lands; raw-content prefix aliases (2026-08-10)

Follow-on to v0.10.0 and the Convertible retirement. Same session — user picked "Pattern class?" as the next item from the post-v0.10.0 open-items list.

**Framing.** §3.10.5 (String) forward-referenced `substring(matching: Pattern)`, `.split(pattern:)`, `.replace(pattern:, with:)` but never defined `Pattern`. Open item: what is Pattern, structurally?

**Design commitments (data-first).**

- **`Pattern` is a protocol, three concrete conformers ship in prelude.** `Literal` / `Glob` / `Regex`. Users add more (`Peg`, `Fuzzy`, `Levenshtein`) via `extend` + structural conformance.
- **Match record carries source backreference + range + named-captures map.** `Match.source` is the String; `Match.range: MatchRange` is a start/end StringPosition pair; `Match.captures: Map<String, MatchGroup>` is keyed by capture name.
- **Named captures only.** Unnamed group `(...)` in Regex source is a compile error. Users write `(?<name>...)` always. Consistent with §3.2 named-only records, §3.7.1 named-only enum associated values, §3.10.2 named-only multi-value carriers. Positional capture access would require positional Match accessors — Corvid doesn't offer positional access anywhere.
- **Prelude literal syntax via `regex"..."` / `glob"..."` / `literal"..."`** — new §4.16.17 raw-content prefix-literal alias shape. Backslashes and other characters pass through unmodified so `regex"^\d+"` reaches the constructor as `^\d+`, not as an escape-sequence failure. Cooked semantics remain the default for prefix-literal aliases; `raw` is opt-in.
- **Compilation caching via §4.16.10 value-alias discipline.** Compile-time-constant pattern source → compile once, share across use sites. No `Regex.compile(...)` boilerplate.

**Mechanism-invention trap check applied.** Every element of the Pattern subsystem uses an existing mechanism:

- Pattern protocol → §3.4 structural conformance (no `conforms: Pattern` declaration needed).
- Match/MatchRange/MatchGroup records → §3.2 named-only.
- Concrete Regex/Glob/Literal classes → §1.5 class-as-universal.
- Pattern literals → §4.16 alias mechanism (with §4.16.17 additive shape variant for raw-content).
- User-defined pattern literals → §4.16.16 `@`-marker discipline.

The ONE new thing is §4.16.17's `raw` flag on prefix-literal aliases. That's genuinely additive — a new shape variant within the existing §4.16 alias mechanism, not a new mechanism. Justified by regex source needing raw content; generalizes to any pattern language with its own escape rules (PEG, path globs, format strings).

**Not the same trap as WrappingAs/SaturatingAs.** Pattern's three concrete conformers (Literal/Glob/Regex) differ in structural behavior — different match algorithms, different compilation, different metacharacters. This is ordinary polymorphism where the differentiator is a real structural distinction. The trap would be if they were three sibling protocols differing only in method name; they're one protocol with three concrete implementations.

**Two design commitments called out to user for signoff (both accepted):**

1. Named captures only (option A over "generated `_1` `_2` names for unnamed").
2. Raw content for pattern literals via new §4.16.17 shape variant (option A over "double-backslash every regex").

**Files touched this session:**

- `spec/spec-v0.10.md` — preamble bump to v0.10.1 + Pattern changelog block; §3.10.5 (String) surface widened (`split(pattern: Pattern)`, `first_position(matching:)`, `all_matches(matching:)`, `replace_all(pattern:, with:)`); §3.10.9 new (Pattern protocol + Match records + Literal/Glob/Regex + RegexOptions + prelude literals + named-captures rule); §4.16.17 new (raw-content prefix-literal aliases).
- `PATTERNS.md` — new entry "I need NSRegularExpression / Regex / re.compile" with regex/glob/literal usage, named-captures rationale, extensibility example.
- `README.md` — status line bumped to v0.10.1.
- `working-notes.md` — this note.

**Open items still standing** (v0.10.0 open list minus Convertible and Pattern):

- **Value-type auto-derivation of `.as() -> Bytes`** — deferred to v0.11+ as stdlib layer.
- **Explicit `conforms: Protocol` syntax** — low priority.
- Interop/stdlib deferred items (F1, F3–F5).
- Implementation-planning items (runtime cost model, package registry, stdlib scope).

**Dissolution-pattern update.** Tenth dissolution-adjacent session. This one was mostly additive (Pattern is a real subsystem needing real spec), but the design still landed by using existing mechanisms rather than inventing new ones — §3.4 for structural conformance, §3.2 for named-only records, §4.16 for the literal syntax. The one addition (§4.16.17 raw flag) is a shape variant within an existing mechanism, not a new mechanism. Consistent with the pattern's spirit even when new stdlib genuinely has to ship.

---

## Session note — v0.10.0.1 `Convertible<To>` retired; narrowing methods; mechanism-invention trap surfaced (2026-08-10)

Small addendum session immediately after v0.10.0 landing. Started as verification pass on the post-v0.10.0 open-items list; discovered the "`Convertible` vs `As<T>` consolidation" item was actually two problems bundled:

1. **A pre-existing v0.8.5 spec inconsistency.** `Convertible<To>.checked_into` returned `Result<T, OverflowError>`, but v0.8.5 retired `Result` from the prelude in favor of domain enums. The bug had lived in §3.11.1 unnoticed since v0.8.5 shipped.
2. **Structural duplication with `As<T>`.** Both were parameterized-generic conversion protocols with trait-qualified dispatch on target type. Keeping both meant two shapes for structurally one thing.

**The mistake that surfaced the meta-lesson.**

My first proposal: retire `Convertible<To>`, replace with **three sibling protocols** — `WrappingAs<T>` / `SaturatingAs<T>` / `CheckedAs<T>` — each with a distinct method name (`.wrapping_as`, `.saturating_as`, `.checked_as`). Framed as "consolidation into the As-family."

User caught it: *"i thought we are going with polymorphic `as -> Bytes` etc? or just `as<Bytes>`?"* — pointing out that inventing three sibling protocol names betrayed the mechanism `As<T>` was defined by.

The check that would have caught it independently: **for narrowing `Int32 → UInt8`, all three semantics produce the same T (UInt8). Trait-qualified dispatch differentiates by T. Therefore trait-qualified dispatch cannot distinguish the three semantics.** The moment I noticed this, the correct move was: the differentiator is a naming question, not a protocol question. Ship three methods on `Int32`; don't invent three protocols.

**Why this slip is a distinct pattern from "add feature X" traps.**

Standard dissolution pattern: "add feature X" → "existing Y covers it." This slip: "dissolve X into Y" → but the dissolution invented Y-alike sibling structure alongside Y. Worse than an addition — an addition at least admits it's an addition. Sibling-protocol invention looks like consolidation but is mechanism-duplication.

**The corrected shape:**

- **`As<T>` remains the sole conversion protocol.** Prelude ships conformance for widening numeric pairs only (lossless).
- **Narrowing is a compile error on `As<T>`.** `Int32(300).as() -> UInt8` diagnostic points at three narrowing methods.
- **Three ordinary methods on numeric primitives**, not a protocol family: `wrapping_to::<T>()`, `saturating_to::<T>()`, `checked_to::<T>() -> ConvertOutcome<T>`.
- **`ConvertOutcome<T>` enum** replaces `Result<T, OverflowError>` per v0.8.5.
- **§3.9 arithmetic `checked_add`/etc.** also fixed — same debt, same reuse of `ConvertOutcome<T>`.

**Design principle written down for future sessions.**

*"When multiple candidate methods share a return type but differ in semantics, the semantics is not a type-parameter question and does not need a protocol family. Per §1.2 (author intent, not authority), the choice belongs at the call site as a method name."*

Saved as `~/.claude/memory/corvid-mechanism-invention-trap.md` (auto-memory, cross-session persistent) so future work catches this before repeating it.

**Files touched this addendum:**

- `spec/spec-v0.10.md` — §3.11.1 rewritten (lossless As<T>-only); §3.11.2 new (narrowing methods); §3.11.3 new (ConvertOutcome<T>); §3.11.4 new (retirement note + migration table); §3.11.5 renumbered from prior §3.11.2 (Bounded); §3.10.3 "Relationship to Convertible" updated (was "coexist for v0.10.0," now points at §3.11.4 retirement); §3.9 integer-overflow paragraph updated (`Result<T, OverflowError>` → `ConvertOutcome<T>`); §2.7 keyword-table Result row marked retired; v0.10.0 changelog gains Convertible-retirement subsection.
- `PATTERNS.md` — As/From entry gains narrowing-story addendum with the design invariant.
- `~/.claude/memory/corvid-mechanism-invention-trap.md` — new memory file capturing the meta-lesson.
- `~/.claude/memory/MEMORY.md` — index entry.
- `working-notes.md` — this addendum.

**Open items still standing** (v0.10.0 open list minus this):

- **`Pattern` class** — deferred future spec work.
- **Value-type auto-derivation of `.as() -> Bytes`** — deferred to v0.11+ as stdlib layer.
- **Explicit `conforms: Protocol` syntax** — low priority.
- Interop/stdlib deferred items (F1, F3–F5).
- Implementation-planning items.

The `Convertible` vs `As<T>` item is now fully closed.

---

## Session note — v0.10.0 Byte substrate; As<T>/From<T> conversion; grapheme-native String; extension conflict warn-not-error (2026-08-10)

Long session. Three interlocking landings. The path getting here was more instructive than the endpoints — the user re-framed the problem three times and each re-frame consolidated the design one level up.

**Thread 1 — Extension composition: rule 4 revised warn-instead-of-error.**

Started as a review question ("v0.9.1 §3.3.2 <- what was this about initially? seems backwards"). v0.9.1 rule 4 said two extensions declaring same-name methods → compile error, `replace` required to shadow. But every other author-intent situation in the spec — §7.7 visibility, §3.5 effect annotations, retracted `sealed`/`private`/`final` — says warn, don't block. Rule 4 was the only exception.

User's follow-on: "wait, extend doesnt override then? why shouldnt it override? maybe we just need a dev warning?" This is the general shape of the fix — same-name across extensions becomes a warning + deterministic resolution (current-module > imported > original), `replace` becomes optional signaling intent. Escalate to error via `-Werror=shadowing-extension` for CI-strict projects. Same-name self-conflict *within one class body* stays hard-error (author self-conflict is a different category).

The consistency-through-time principle: when rule N is out of line with the other rules the language already commits to, that's the signal to fix rule N, not to defend rule N.

**Thread 2 — Stdlib/interop gaps → substrate-level rethink.**

Started as "what can't we inherit or bridge from objc". Focused on String indexing semantics; user reframed with OOP + GoF: "what gof design pattern does this problem meet?" — Iterator/Bridge/Strategy/Facade candidates surfaced. Then user pushed one level higher: "everything is made of bytes, right? so every atom is technically a byte sequence, except that we dont generally allow folks to edit them?"

That reframe changed the design. Instead of "add a StringPosition API" and "add byte-slice methods" and "add Base64" and "add random" separately, ask: what universal substrate makes all of these fall out?

Answer: a mutable `Bytes` regular class + `String` as grapheme-native regular class + `.as() -> Bytes` as the universal convention. Then:
- StringPosition dissolves into an opaque token bound to a source String, with all navigation via `s.position(...)` methods.
- Byte access dissolves into universal `.as() -> Bytes` on any type conforming to `As<Bytes>`. No peer views on String (`.utf16`, `.utf8`, `.bytes` are not properties).
- Base64/random/etc. dissolve into `extend class Bytes:` and `extend class UInt8:` — the extension mechanism from v0.9.0 handles them without language changes.

**Thread 3 — Naming convention → protocol design.**

User noticed "there's a common pattern with Bytes and AsBytes as X, asX ?" and pushed toward `As<T>` and `From<T>` as *parameterized* protocols rather than method-name conventions. My initial resistance ("Rust normally routes this through conformance declaration `impl As for Foo`, not through method-name matching") was wrong — user pushed again: "no i want to do this: class String: methods: function as(self) -> Bytes:  without needing to conform to a protocol". Corvid's design has structural conformance built in since v0.6; the class with `function as(self) -> Bytes` structurally conforms to `As<Bytes>` automatically. No boilerplate.

Then: trait-qualified dispatch. User's key move: "i mean, if we specify the output type, cant this be matched to the right type? like as->bytes vs as->json". Yes — the return type is a *type parameter of the conformed protocol*, not an overload dimension. A class conforming to both `As<Bytes>` and `As<Json>` may declare both `function as(self) -> Bytes` and `function as(self) -> Json`; dispatch at the call site is by return-type context, turbofish, or trait qualification. This dodges the "no overloading" rule cleanly because same-name-different-return isn't overloading in the parameter-types sense — it's protocol-instantiation dispatch.

**Decisions in this session:**

- `As<T>` / `From<T>` protocols (§3.10.3). Trait-qualified dispatch (§3.4.4). No auto-derivation between the two directions.
- `Bytes` class (§3.10.4) — mutable, regular, NSData-bridged. No separate builder type. Array-literal conformance.
- `String` class (§3.10.5) — grapheme-native, no int indexing, no peer views, `.as() -> Bytes` for byte access.
- `Char` class (§3.10.6) — record, grapheme cluster.
- `StringPosition` class (§3.10.7) — opaque token, source-bound, no arithmetic.
- `Encoding` / `ByteOrder` / `DecodeOutcome<T>` enums (§3.10.8).
- §3.3.2 rule 1 revised to warn + deterministic resolution; rule 5 adds trait-qualified exemption for generic-protocol-derived same-name.

**Serialization by convention, not by reflection.** Any type conforming to `As<Bytes>` participates in the universal serialization substrate. Format-specific overlays (`As<Json>`, `As<MsgPack>`, `As<Yaml>`) become trait-qualified conformances added by users or third-party packages via `extend class Foo:`. No `Codable`-style reflection auto-magic — the Java `Serializable` trap doesn't compound. Value types (records, enums, primitives) may auto-derive via §4.18 metadata as a future stdlib layer (deferred).

**Why v0.10.0 and not v0.9.2.** Major-version-adjacent bump because the string/bytes/serialization decisions land the language's substrate for text and binary handling — foundational choices with ecosystem-wide effect on future stdlib design and third-party packages. Rule 4 revision, though smaller, ships alongside because it's part of the same "consistency pass" and touches semantics rather than fill-in.

**Files touched this session:**

- `spec/spec-v0.9.md` (renamed to `spec-v0.10.md`) — preamble bump; §3.3.2 rule 1 revised to warn + deterministic resolution, rule 4 adds trait-qualified exemption; §3.4.4 adds trait-qualified dispatch note; new §3.10.3 (As/From), §3.10.4 (Bytes), §3.10.5 (String), §3.10.6 (Char), §3.10.7 (StringPosition), §3.10.8 (Encoding/ByteOrder/DecodeOutcome).
- `PATTERNS.md` — extension entry revised for warn-not-error; new entries "I need String.Index", "I need .utf8/.utf16/data.count", "I need Data/NSMutableData/[UInt8]", "I need Codable/JSONEncoder"; Rust `From<T>`/`Into<T>` entry added.
- `validation/00-friction-log.md` — #25 String.Index / position API marked dissolved v0.10.0.
- `validation/websocket/00-friction-log.md` — #W-30 position part, #W-14, #W-53, #W-52, #W-61, `F8`, #W-37c, #W-37e all marked dissolved; cross-exercise table adds five new rows.
- `README.md` — status line bumped to v0.10.0; spec filename updated; String position API removed from Undecided list.
- `working-notes.md` — this note.

**Open items post-v0.10.0** (verified against friction logs and spec; entries marked "still open" here have been confirmed not already dissolved):

*Language design — deferred future spec work:*

- **`Pattern` class** — referenced from §3.10.5 (`substring(matching: Pattern)`, `.split(pattern:)`, `.replace(pattern:, with:)`) but the class itself is deferred. Scope: glob, regex, structural patterns. Needed before any serious text-processing stdlib.
- **`Convertible` vs `As<T>` consolidation** — §3.11.1 predates §3.10.3; overlap in numeric conversions (both offer projection from source type to target type). Future refactor may unify or explicitly document why both stay (e.g., `Convertible` retains the three-semantics narrowing split, `As<T>` is the general form).
- **Value-type auto-derivation of `.as() -> Bytes`** — records/enums/primitives could auto-synthesize via §4.18 metadata since their layout is closed and predictable. Deferred to v0.11+ as a stdlib layer, not a spec change.
- **Explicit `conforms: Protocol` syntax** — structural conformance covers 95% of cases (Ink #13 body-resolved), but the explicit declaration form is under-specified. Rare in practice; low priority.

*Interop / stdlib — deferred implementation choices, no language design change needed:*

- **Conditional compilation** — `F1`. Swift's `#if os(watchOS)`. Multi-target build-system concern.
- **CF↔Foundation bridging** — `F3`, `F4`. `as CFString`, `.takeRetainedValue()` and CF memory management. CoreFoundation-adjacent; not idiomatic Corvid.
- **Direct C-function invocation** — `F5`. `import c "..."` full workflow beyond the reference-card sketch. Needs §9 expansion once C interop matters.

*Implementation-planning items — meaningful only once implementation starts:*

- Runtime cost model for state-machine synchronization under load.
- Package registry protocol.
- Stdlib scope decisions (what ships with the compiler vs. separate packages).

Everything from prior "open items" lists that was actually already resolved has been re-verified and marked in the friction logs this session: `try` semantics (dissolved v0.8.5), String position API (dissolved v0.10.0), enum raw values (resolved v0.6.0), byte access / slice (dissolved v0.10.0), character-literal-to-byte (dissolved v0.10.0), string interpolation (resolved v0.8.0), `guard let` / `if let` (resolved v0.7/v0.8.1), optional chaining (retired v0.7), tuples / destructuring (dissolved v0.8.3), nested types (dissolved v0.8.4), definite assignment (dissolved v0.8.7), `old()` (dissolved v0.8.7), throws-in-ensures (dissolved v0.8.5), argument labels (resolved v0.5), extension mechanism (resolved v0.9.0), predicate patterns (dissolved v0.8.6), type aliases (resolved v0.6+/v0.8.2), bit-level ops (resolved v0.7.1), inout arrays (dissolved v0.8.7), namespace (resolved by existing mechanisms), generic constraints on categories (dissolved v0.9.0), MemoryLayout (resolved v0.7.1), range literals (resolved v0.8.0/v0.8.3).

**Dissolution-pattern update.** Ninth dissolution session. The re-framing chain here (stdlib gaps → GoF patterns → universal substrate → naming convention → trait-qualified dispatch) is the fullest example so far of the "one level up" move. Each step made the previous alternative look smaller. What could have been ten stdlib additions and a language feature became four types + two protocols, all falling out of existing structural commitments (§1.5 class-as-universal, §3.4 structural conformance, §3.3.2 extensions).

---

## Session note — v0.9.1 Extension conflict-resolution behavior spec; namespace friction closed (2026-08-08)

Housekeeping-plus session. Two threads:

**Thread 1 — Namespace friction (websocket #4) closed.** User pushed back on why "struct-as-namespace vs. module-level namespace construct" was still marked open, noting that record + `class:` section should cover it. Correct. The friction entry bundled two questions: (a) struct-as-namespace pattern (Swift's `enum Foo { static let PI = ... }`), resolved via `class:` section on records (§4.10.7); (b) module-level namespace construct (C++/C# `namespace Foo { ... }` block), not needed — one module = one namespace, and within-module sub-grouping uses (a). Marked resolved by existing mechanisms.

**Thread 2 — v0.9.1 fill-in for extension conflict resolution.** v0.9.0 shipped the top-line rule (same-name collision is compile error unless `replace`) but left seven behavioral questions implicit. This session fills them in without changing the design.

**Decisions in this session (rules 1–7 in §3.3.2):**

1. **Same-name collision without `replace` is a compile error at the composing site** — the compilation unit that has both extensions visible. This is where the conflict surfaces, even if that unit didn't write either extension.
2. **`replace` requires a shadowing target** — if there's no method with that name in the original definition or another visible extension, `replace-without-target` diagnostic. Prevents `replace` from being a copy-paste no-op.
3. **At most one `replace` per method per composing site** — if two extensions each `replace` the same method, both claim precedence, neither has grounds. Compile error naming both.
4. **Name matching ignores signatures** — Corvid has no overloading; two same-name methods always conflict regardless of parameter types.
5. **Extensions shadow inherited protocol defaults without `replace`** — extension is providing the class's own definition; per §3.4.4 priority (own > extension > inherited default), it wins over the default without ceremony. `replace` is reserved for shadowing explicit prior declarations. Edge case: second extension over first extension still needs `replace`.
6. **Runtime method table is unified after composition** — after compile-time conflict resolution, one entry per selector; dispatch is unchanged from any other class per §5.1.
7. **Load-time transitive visibility** — extensions apply globally at load time (ObjC category semantics); compile-time source visibility (§7.7) constrains what a module can *write against*, but any loaded module's extensions are runtime-visible on class instances. The consequence: if A imports both B and D, both of which independently extend Foo with `foo` without knowing about each other, the conflict surfaces at A. A resolves by dropping an import, writing its own `replace`, or upcasting per protocol.

Also added an illustrative diagnostic format (`error[E0447]: duplicate method \`foo\` on class \`Bar\`` with source location + suggested fix) so implementers have a target and users have a preview, and a "what is not a conflict" list clarifying that different methods, methods on different classes, and additive invariants all compose freely.

§3.4.4 (Default Method Inheritance) got a cross-reference update pointing at §3.3.2 rule 5 for the shadowing-inherited-default case.

**Why v0.9.1 and not v0.9.0 amendment.**

Behavior spec that fills in specifics for a landed rule. No design change; no retraction; no new construct. Sits at the same level as previous ".x" bumps (v0.8.1 filling in Session-3 gaps, v0.8.7 clarifying let-expression form). The top-line rule from v0.9.0 stands; v0.9.1 makes it implementable.

**Files touched this session:**

- `spec/spec-v0.9.md` — preamble bump to v0.9.1 + changelog block; §3.3.2 Conflict Resolution subsection (rules 1–7 + diagnostic example + not-a-conflict list); §3.4.4 cross-reference update to §3.3.2 rule 5.
- `validation/websocket/00-friction-log.md` — #4 (namespace) marked resolved by existing mechanisms.
- `README.md` — status line bumped to v0.9.1.
- `working-notes.md` — this note.

**Open items post-v0.9.1:**

- **String position API in stdlib** — Ink #25 / websocket #W-30. Stdlib scope, not language spec. Blocks parser translation work.
- **Interop/stdlib gaps** — byte-array raw access (`#W-53`), byte slice methods (`#W-52`, `#W-61`, `F8`), Foundation-primitive interop details (`#W-14`, `F6`), random-number API (`#W-37c`), base64 (`#W-37e`), CF↔Foundation bridging (`F3`, `F4`), conditional compilation (`F1`). All real, none touching language design.
- **Implementation-planning items** — runtime cost model for state-machine synchronization, package registry protocol, stdlib scope. Only meaningful once implementation starts.
- **Validation-driven exploration** — translate more Ink files, extend idiomatic pass to more Fragment types. Would surface new friction if the design isn't ready for it.

---

## Session note — v0.9.0 Unified class extension; Category retracted; default-method inheritance (2026-08-08)

Major-version bump. Not a "dissolves into existing mechanism" session in the strict sense — this is a **retraction and unification**. Started as v0.8.8 (small fix for Ink #W-69/#W-70: add generic constraints to `category(...)`). User pushed twice, each push moving the frame up a level.

**First push — "why can't guard/invariants cover this?"** — reframed the type-constraint question. Type-parameter conformance is a compile-time-checkable claim about the receiver, same as any `requires` clause; extending the requires machinery to accept `conforms_to` predicates dissolves the "add where clauses to categories" problem into the existing contract system. That was going to be the v0.8.8 draft: parameterized `target_class`, `conforms_to` predicate, section-level `requires:`.

**Second push — "perhaps the larger problem here is how do we monkey patch classes?"** — reframed everything. #W-69/#W-70 is one dimension of a bigger under-specified area. Categories were Corvid importing ObjC's vocabulary for what is structurally just "declaring more members on this class." That framing paid section-class ceremony for a job that fits at the class declaration level.

**Third push — "we can just extend some class by saying x modifier to `class NSString:`"** — nailed the shape. `extend class Foo:` at declaration position. One vocabulary, one class-body syntax for both definition and extension, generic-parameter machinery reused where it already lives.

The v0.8.8 draft was retracted mid-planning; v0.9.0 took its place.

**Decisions in this session:**

- **§3.3.2 new (Extension Declarations).** `extend class Foo:` is a declaration-position modifier. Body follows normal class-body rules. Generic parameters and where-clauses at declaration position. Extensions across modules stack. State fields still cannot be added (memory layout is fixed).
- **§3.4.4 new (Default Method Inheritance for Structural Conformers).** Reading A committed: structural conformers inherit protocol default methods for messages they don't define themselves. Diamond ambiguity resolved via compile-time detection + explicit disambiguation (define the method or upcast).
- **§4.10.4.3 (Category) retracted.** Section class removed; cross-reference points at §3.3.2. Migration is mechanical.
- **Conflict rules committed.** Same-named method across extensions is a compile error; opt-in `replace` modifier for explicit shadowing. Consistent with §1.2 (author intent, not authority) — no ObjC-style link-order-dependent silent resolution.
- **§5.1 note.** Method-table population is uniform across definition, extension, and inherited defaults.
- **§7.7 note.** Extension visibility follows module imports.
- **§10 grammar.** `class_decl` extended with `extend?` prefix; `where_clause` production added; `method_decl` extended with `replace?` prefix.
- **Ink #W-69, #W-70 dissolved by unification.**

**What this session confirms about the design philosophy.**

The "dissolves into existing mechanisms" pattern was extended one level up: not just "dissolve a would-be construct," but "**retract an existing construct** when the structural commitments (class-as-universal, structural conformance, section discipline) make it redundant." Category was inherited from ObjC's vocabulary; Corvid's class-body syntax covers its job; the retraction is the same shape as retiring `throws` in v0.8.5 or retiring `coordination_scope` before it shipped in v0.8.6. The difference: Category had actually landed in a prior spec version, so retracting it is a real breaking change for anyone who had internalized the v0.8.x shape. Migration is mechanical; the tradeoff is worth it.

The default-method inheritance decision (§3.4.4) is worth naming separately. Corvid has committed to structural conformance since v0.6+, but the spec left the question of "do conformers inherit defaults" ambiguous. Reading A (yes) makes structural conformance load-bearing at runtime — you don't just get "type-check passes"; you get the protocol's affordances. Reading B (no, only via upcast) would have made structural conformance a purely static-checking device, effectively toothless. Reading A carries the diamond problem; Corvid solves it with compile-time detection + explicit disambiguation rather than any implicit-resolution rule.

**Eight-session pattern table:**

| Version | Dissolved / Retracted | Replacement |
|---|---|---|
| v0.8.3 | `for` keyword, tuples, destructuring | prelude alias over `.for_each`; named-field records |
| v0.8.4 | nested type declarations | flat naming + leading-dot |
| v0.8.5 | `throws`, `try/catch/finally`, prelude `Result<T, E>` | nil-punning; domain enums; switch |
| v0.8.6 | `DispatchQueue`, `@main_actor`, would-be `coordination_scope` | contracts (§1.9); `execution:` metadata; class boundary IS coordination boundary |
| v0.8.7 | would-be `old(expr)` in ensures | `self`/params = entry-state, `result` = post-state (§3.4.3) |
| v0.9.0 | `category(target_class: X):` section class; ambiguous conformer-inheritance semantics | `extend class Foo:` at declaration position (§3.3.2); structural conformers inherit defaults (§3.4.4) |

**Files touched this session:**

- `spec/spec-v0.9.md` (renamed from `spec-v0.8.md` during this session's housekeeping pass) — preamble bump to v0.9.0 + big changelog block; §3.3.2 new; §3.4.4 new; §4.10.4.3 retracted (redirect to §3.3.2); §5.1 method-table note; §7.7 extension visibility note; §10 grammar (`class_decl` extend prefix, `where_clause`, `method_decl` replace prefix); two stale-reference fixups (§9 line 6104, §14 line 6721).
- `validation/websocket/00-friction-log.md` — §2.6 rewritten; #W-69/#W-70/marker-protocols marked dissolved/resolved; summary list item 5 updated.
- `PATTERNS.md` — Rust `impl<T: Trait> for Foo<T>` entry added; `traits with where clauses` entry rewritten; new Swift/ObjC extension entry (`I need extension Foo { ... }`).
- `README.md` — status line bumped to v0.9.0.
- `working-notes.md` — this note.

**Open items post-v0.9.0:**

- Predicate-based pattern matching sugar beyond `case x if pred(x):` — deferred (guard patterns cover the semantic need; Swift's `case \.foo:` syntax is a keypath projection that has no direct equivalent by design; friction #19 marked dissolved).
- ~~**File rename** `spec-v0.8.md` → `spec-v0.9.md`.~~ **Completed** — housekeeping pass folded into this session; all cross-references (README, PATTERNS, working-notes itself) updated to the new filename.
- **Example sweep across spec** — some downstream examples in §4/§5 that used `category(...)` were retired at the source-of-truth (§4.10.4.3); a full grep for any remaining stale `category(...)` in prose examples should confirm zero remain. Deferred to next session.

---

## Session note — v0.8.7 `old(expr)` dissolved; pre/post bindings in ensures (2026-08-08)

Seventh "dissolves into existing mechanisms" session. Started as a straightforward "add `old(expr)` to the contract system" proposal for Ink friction #28 — state-transition postconditions like `ensures self.current_index > old(self.current_index)` need pre-state reference; Dafny/JML/Eiffel all have some flavor of this.

**The pivot.** User asked "explain why isnt this just recursion." That reframed the problem. In a pure OOP/message-passing model where methods are transformations `Self → Self`, the pre-state IS the input (the receiver-as-argument); the post-state IS the output (the returned Self). Postconditions naturally reference both because both are already in scope. `old()` is a wart specific to mutation-heavy imperative programming where the pre-state isn't captured anywhere.

The dissolution: expose the entry-state snapshot the contract runtime already had to capture (to make any ensures checkable) as the natural identifier — `self` and parameter names inside ensures scope. Post-state gets a new binding: `result` (return value for value-returning methods; receiver-at-exit for void methods).

**Why `result` over alternatives.**

- `result` — familiar from Eiffel/Dafny for return values; extends cleanly to "resulting self" for void methods; single word.
- `self'` — prime notation from Alloy/Z; reads as "self-after"; punctuation-heavy.
- `after` / `post` — English-y; reads well but adds a keyword.

Picked `result` because it matches the method-as-transformation reading exactly — the post-state IS the result of the transformation. For value-returning methods it IS the return value already. For void methods it's the "resulting self" — the receiver considered as the output of the transformation.

**Decisions in this session:**

- **§3.4.3 new (Pre-state and Post-state Bindings in Ensures Clauses).** Formalizes the binding rule: inside ensures scope, `self`/params → entry-state; `result` → post-state. No `old(...)` form.
- **Ledger example (§8.4.1) rewritten.** `ensures accounts[from] == old(accounts[from]) - amount` → `ensures result.accounts[from] == self.accounts[from] - amount`.
- **§8.4.1 note updated.** Removed the `old(...)` reference; classification is inferred from body assignments as always.
- **§10 grammar note added.** No new production; a note near `method_decl` documents the binding rule.
- **Reader.inferred.cor `advance_index` rewritten.** `ensures result.current_index > self.current_index`. #28 marked resolved by dissolution.
- **`PATTERNS.md` extended.** New entry mapping "coming from Dafny/JML/Eiffel/Ada/Spec#/Alloy — I need `old(expr)`" to the pre/post binding rule.

**Reference-type semantics settled.** `self` in ensures scope captures the receiver's field values at entry using the same value/handle rules as any ordinary read — no separate "deep snapshot" semantics needed. To detect mutations on a referenced object, compare specific fields (`result.doc.title != self.doc.title`), not identity. This falls out of the ordinary read model; no new specification needed.

**What this session confirms about the design philosophy.**

The user's "why isn't this just recursion" was the trigger for the dissolution — same shape as previous sessions where a proposed mechanism collapsed once the user pointed at an existing primitive that already covered it. The recognition pattern is stable: **if the runtime already has to do something to make the feature checkable, expose the existing machinery via natural naming rather than a special-form wrapper.**

Contract system machinery (snapshot on entry, evaluate on exit) was already load-bearing for any postcondition on a mutable receiver. `old()` was routing that snapshot through a specially-named wrapper; the pre/post binding rule exposes it under `self`/params directly. Zero new capability. One less construct.

**Seven-session pattern table** (extending v0.8.6):

| Version | Dissolved | Replacement |
|---|---|---|
| v0.8.3 | `for` keyword, tuples, destructuring | prelude alias over `.for_each`; named-field records |
| v0.8.4 | nested type declarations | flat naming + leading-dot |
| v0.8.5 | `throws`, `try/catch/finally`, prelude `Result<T, E>` | nil-punning; domain enums; switch |
| v0.8.6 | `DispatchQueue`, `@main_actor`, would-be `coordination_scope` | contracts (§1.9); `execution:` metadata; class boundary IS coordination boundary |
| v0.8.7 | would-be `old(expr)` in ensures | `self`/params = entry-state, `result` = post-state (§3.4.3) |

**Files touched this session:**

- `spec/spec-v0.9.md` (was `spec-v0.8.md` at edit time; renamed in v0.9.0 housekeeping) — preamble version bump + changelog block; §3.4.3 new; §1.9 line 514 note update; §8.4.1 Ledger example rewrite; §10 grammar note near `method_decl`.
- `validation/03-Reader.inferred.cor` — VERSION NOTE (v0.8.7) added; `advance_index` postcondition rewritten; `read` postcondition rewritten; friction comment marked resolved.
- `validation/00-friction-log.md` — #28 marked dissolved.
- `PATTERNS.md` — new entry for Dafny/JML/Eiffel/etc.
- `README.md` — status line bumped; `old()` removed from undecided list.
- `working-notes.md` — this note.

**Open items post-v0.8.7:**

- Generic constraints on categories (`#W-69`, `#W-70`) — medium; matters for library authors.
- `inout` or reference-shaped arrays (`#W-72`) — likely dissolves (arrays are already reference-shaped).
- Predicate-based pattern matching (Ink) — extension to §3.7.

---

## Session note — v0.8.6 DispatchQueue equivalent + execution metadata + PATTERNS.md (2026-08-08)

Sixth "dissolves into existing mechanisms" session. Path had two phases.

**Phase 1 — DispatchQueue framing.** Started as "how does Corvid express DispatchQueue." I initially sketched two constructs: `execution:` metadata on classes (for thread/QoS/pool) plus a new `coordination_scope` construct (for multi-class shared invariants). User pushed back sharply on `coordination_scope`: "why cant Pattern B just be a class? many-reads-single-write?"

That collapsed the design. Every case where separate objects need shared invariants can be re-expressed as one class owning the shared state. Bank/Ledger, Producer/Consumer/Queue, Cache/Store — all become one class per shared state cluster. The class boundary IS the coordination boundary. `coordination_scope` retracted.

Many-reads-single-write also fell out naturally: methods that don't modify state (readers) can run concurrently; methods that do (writers) serialize. Compiler infers classification from method body. Runtime uses RWLock-style dispatch. No user-visible annotation.

**Phase 2 — PATTERNS.md.** After the retraction, user made a meta-point: "corvid already offers the right primitive here. however, we might need to start collecting these patterns so future agents and humans dont get confused."

That's exactly right. Six sessions of dissolutions have built up an implicit knowledge tree — every dissolution has a "here's how Corvid does it" answer that's obvious once known and invisible until it is. Anyone coming from Swift/ObjC/Rust/Java/Python will predictably reach for constructs Corvid dissolved and hit walls.

Created `PATTERNS.md` at project root. Organized by-source-language ("coming from Swift you'd reach for X; Corvid does Y") plus a Corvid-native-patterns section that names the recurring principles. Not spec-normative; onboarding guidance.

**Decisions in this session:**

- **`execution:` section on classes (§8.4.2).** Metadata: `thread:` (main/any), `qos:` (four levels), `pool:` (implicit/dedicated). Class-level default with per-method override via nested section. Replaces `@main_actor` annotation.
- **Reader/writer classification (§8.4.1).** Compiler infers from method body. Multiple readers concurrent; writers exclusive. `data:` fields skip coordination entirely. No user-facing annotation.
- **DispatchQueue mapping (§8.4.3).** Three-row table: serial-mutex → contracts + execution; scheduling → spawn/async_scope; main-thread → execution: thread: main. Cross-object coordination → one class owns the state. Cross-object pipelines → channels (§8.6, unchanged).
- **`coordination_scope` retracted before shipping.** Every case collapses to one class.
- **`@main_actor` retired.** Replaced by `execution: thread: main`. Documentation updated across §1 preamble and §8.9.
- **ObjC bridge exposes `dispatch_pool`.** Per-instance property. ObjC callers use standard `dispatch_async(instance.dispatch_pool, ^{ ... })`. Routes through contract-guarded serialization transparently. `execution: thread: main` binds to the actual ObjC main queue (same identity).
- **`PATTERNS.md` new file.** Onboarding catalog for the accumulated dissolutions.

**Two philosophical points landed in this session:**

1. **The class boundary is the coordination boundary.** If two "objects" share an invariant, they were one class artificially split. Deep insight: Corvid's class-as-universal commitment (§1.5) has coordination consequences beyond just "everything is a class." The class IS the unit of shared-state consistency.
2. **Design pattern documentation is load-bearing.** Six sessions of dissolutions leave onboarding hazards. The primitives are right; the mental-model translation is missing. PATTERNS.md is the answer — not spec-normative, but explicitly the "here's how this maps from what you know" document. Future agents and humans get to walk into the design with a map instead of re-deriving each dissolution.

**Six-session pattern table** (in v0.8.6 changelog):

| Version | Dissolved | Replacement |
|---|---|---|
| v0.8.3 | `for` keyword, tuples, destructuring | prelude alias over `.for_each`; named-field records |
| v0.8.4 | nested type declarations | flat naming + leading-dot |
| v0.8.5 | `throws`, `try/catch/finally`, prelude `Result<T, E>` | nil-punning; domain enums; switch |
| v0.8.6 | `DispatchQueue`, `@main_actor`, would-be `coordination_scope` | contracts (§1.9); `execution:` metadata; class boundary = coordination boundary |

The pattern is stable enough to be a design principle in its own right (noted in PATTERNS.md's "Corvid-native patterns" section): **before adding a mechanism, check whether Corvid's structural commitments already cover the case via convention or composition.**

## Session note — v0.8.5 throws retired, no try/catch, no prelude Result (2026-08-08)

Fifth consecutive session in the "language mechanism dissolves into existing tools" pattern. Started as engagement with Ink #17 (try semantics); user pushed the reduction all the way down: "im not even sure why we need Result at all given that we have used nil-punning?"

That question forced the honest answer. Working through it:

- Nil-punning (v0.7.0) covers the "did we get a value or not" case completely. No Result needed there.
- For discriminated failure ("how did we fail"), a domain-specific enum with a case per failure mode is more expressive than a generic `Result<T, E>` — cases are domain-named, contain per-case associated data, are metadata-inspectable, and interact with v0.8.4 leading-dot cleanly.
- File.open example demonstrated the case where discrimination matters: `.not_found` / `.permission_denied` / `.volume_unavailable` / `.corrupted` need different handling; nil throws them all into one bucket.
- No `Result<T, E>` in prelude. User-authored generic Result acceptable for middleware if genuinely needed, but stdlib doesn't push toward it.
- No `try` / `catch` / `finally` construct. Considered `Try` as a prelude section class (would fit the sections mechanism naturally per user framing "try is a class with a state machine"). Concluded: switch + domain enums cover the ground; adding Try invites reaching for it when decomposition is the right answer.
- `throws` effect retired entirely from §3.5. Failure is not an effect; it's a return-type discipline.

**Decisions in this session:**

- **`throws` effect retired.** §3.5 rewritten — no throws in advisory or semantic effects. `effect_clause` grammar updated to exclude `throws`. `throw` removed from `guard let` exit-verb list (exits: return / break / continue / panic).
- **No `try` / `catch` / `finally`.** Not a keyword. Not a section class. Not a prelude alias. `switch` handles dispatch; existing `with:` section (§4.10.4.5) handles the RAII/cleanup case that `finally` would cover.
- **No `Result<T, E>` in prelude.** Domain-specific enums per failure family. Generic middleware conforms to `Failable<T>` protocol over any domain enum (rare use case; users can author).
- **ObjC interop translates per-method.** `NSError**` out-param maps to a domain enum with `.completed(T)` / `.failed(NSError)` cases. Not a blanket `throws NSError` binding.
- **Ink #17 (try semantics) resolved by dissolution.** Ink #29 (throws in ensures) dissolves entirely — `throws` doesn't exist; ensures apply on switch-case discrimination via `returns .completed(_) implies ...` shape.
- **Example sweep across the spec.** ~12 example rewrites from `throws E` shape to domain-enum-return shape. §3.4 protocol example, §4.10 RAII, §5.6 callable types, §7 visibility examples, §8.2 async example, §8.6 channel backpressure, §9.3 ObjC interop mapping, §11 worked example.

**What Corvid gives up:**

1. **Cross-cutting effect propagation.** Rust `?` and Swift `try` propagate errors across several layers without ceremony. Corvid requires each layer to explicitly `switch` on the outcome enum. More code per layer; more visible dispatch; no hidden control-flow jumps.
2. **Generic middleware over "any failable computation."** Without `Result<T, E>`, retry combinators need to be either domain-specific or work over a `Failable<T>` protocol. Slight setup cost; same expressive power.

Both trade-offs consistent with §1.6 legibility, visible dispatch, class-based over control-flow-based — and the last five sessions' pattern.

**Five-session pattern consolidated:**

| Version | Dissolved | Reason |
|---|---|---|
| v0.8.3 | `for` keyword | prelude alias over `.iter().for_each` on `Iterable<T>` |
| v0.8.3 | tuples + destructuring | classes with named fields, no positional forms |
| v0.8.3 | positional enum values (retracted) | named-only, consistent with rest of Corvid |
| v0.8.4 | nested type declarations | flat naming + leading-dot at use sites |
| v0.8.5 | `throws` effect | failure in return type via nil-punning or domain enum |
| v0.8.5 | `try / catch / finally` | switch on domain enum + with: for cleanup |
| v0.8.5 | `Result<T, E>` in prelude | domain-specific enums beat generic wrapper |

Each closes a common friction item by re-examining whether Corvid's structural commitments already handle the case with existing tools. Each time, they do. Pattern is stable enough to be worth naming as a design principle: **before adding a mechanism, check whether the existing structural commitments already cover the case via convention or composition.**

## Session note — v0.8.4 nested types dissolved + leading-dot shorthand (2026-08-08)

Short paired-decision session. Started as engagement with `#W-2` / `#W-48` (nested type declarations); user pushed back on Reading 1 (nested types as feature) with a C# comparison: "won't it mean if someone imports this in the future they have to alias it (had this issue in c#)". That reframed the trade-off.

**Decisions in this session:**

- **Nested type declarations dissolved by convention (§3.3.1 new).** No in-body type nesting added. Types associated with a class use `<ClassName><Role>` naming at module scope. Convention over language mechanism, matching v0.8.3's tuple/destructuring dissolution and Corvid's broader "intent-not-authority" (§1.2) discipline.
- **Leading-dot enum shorthand adopted (§3.7.2 new).** `.name(...)` and `.name` resolve to `<InferredType>.name(...)` when target type is inferable from context. Contexts documented: switch scrutinee, function parameter, return, field/variable declared type, comparison target. Ambiguous contexts produce compile error with suggestion.
- **The two decisions are ergonomically paired.** Flat naming would read verbose at switch arms and function args if written as `FrameCollectorEvent.text(...)` everywhere; leading-dot makes it read `.text(...)` at the sites that matter most. Nesting was proposed to give the same use-site brevity; leading-dot gives it without the consumer-side aliasing cost of nesting.

**Cost analysis that drove the call:**

- **Nested types:** every consumer pays either per-use path noise (`FrameCollector.Event.text(...)`) or per-file aliasing (`using Event = FrameCollector.Event;` in C#). Small ownership-visibility win at declaration.
- **Flat + convention:** one longer symbol per import site; no consumer-side overhead. Ownership signal from naming pattern is convention-enforced, not language-enforced.
- **Verdict:** consumers outnumber declarations. Push cost to declaration. Confirmed by user's C# experience.

**Precedent for the pattern:**

This is the third session in a row where design pressure resolved to "convention + existing tools" rather than "add language feature":

1. **v0.8.3 no tuples, no destructuring.** Multi-value carriers = classes with named fields. No `(a, b)` type, no `let (a, b) = pair`.
2. **v0.8.3 `for` is prelude sugar.** Iteration = method dispatch on `Iterable<T>`. `for` retires from bootstrap.
3. **v0.8.4 no nested types.** Class-associated types = flat naming + optional module aliases. Leading-dot handles use-site ergonomics.

Each closes a common friction item by re-examining whether Corvid's structural commitments (§1.5 class-as-universal, §5.1 universal dispatch, §1.2 intent-not-authority) already handle the case with existing tools. Each time, they do.

**Related resolutions:**

- Ink #14a (leading-dot shorthand) resolved by feature — same v0.8.4 §3.7.2.
- `#W-2` / `#W-48` (nested types) resolved by convention — v0.8.4 §3.3.1.

## Session note — v0.8.3 class-based iteration + no destructuring (2026-08-08)

Session followed from friction-log audit. Ten items marked resolved across the two logs, surfacing the true pending set. Tuples were named as the next natural session target — but user framing pushed deeper: "consider that the `for` loop is also sugar? how does one actually iterate in oop? won't it be recursion? a object sending message to itself?"

That question re-scoped the work entirely. Path taken:

**Iteration in pure OOP is message dispatch on the collection.** Smalltalk / ObjC-lineage: `collection do: [block]` — collection controls how it walks itself, caller supplies per-element behavior. Fundamentally reducible to recursion via self-send. C-style `for` is imported convention, doesn't belong in bootstrap under Corvid's class-as-universal + universal-method-dispatch commitments.

**Decisions in this session:**

- **`for` retires from bootstrap; becomes prelude alias.** Expansion: `for x in y: body` → `y.iter().for_each { x -> body }`. Parses as an ordinary alias (§4.20.1). `Iterable<T>` conformance is what makes any class iterable.
- **`Iterable<T>` and `Iterator<T>` stdlib protocols.** `iter() -> Iterator<T>` on the collection; `next() -> T` (nil-on-exhaustion, integrates with v0.7 nil-messaging) plus `for_each(f)` default impl on the iterator.
- **`Pair<A, B>` (general) and `Entry<K, V>` (map-shaped) stdlib carriers.** Both `kind: record` with named fields. `Map.iter()` yields `Iterator<Entry<K, V>>`; user reads `entry.key` / `entry.value`. Domain-specific records (`Slice { position, length }`) preferred over generic `Pair` when a domain word applies.
- **No destructuring bindings.** `let (a, b) = pair` explicitly not spec'd. `for (k, v) in dict` not spec'd. Multi-value extraction is always named-accessor method dispatch on the returning class. Rule extends v0.8.1's named-only enum associated values discipline uniformly across the language.
- **Case-pattern extraction inside `switch` is the sole exception.** `case .some(value: let x):` and `case Order(id: let some_id, items: []):` are legal — pattern matching in `case` is discrimination-with-name-binding, which requires looking inside the value. Positional single-value form `case .some(let x)` also legal (no ambiguity with one associated value). Everything else uses accessors.
- **`while` and `loop` stay bootstrap.** They're the primitives stdlib uses to implement iteration methods without infinite regress or a TCO commitment. Discipline note in §4.20.3: user-facing code should prefer `.for_each` / `.map` / `.filter`; `while` / `loop` are for stdlib and for algorithms that genuinely don't fit iterator abstractions.
- **Tuple-family friction items dissolved.** `#W-35` (tuple destructuring in `for`), the tuple portion of `#W-6`, tuple types generally — all marked "dissolved by design" rather than "resolved by feature." No language-level tuples added.

**What this closed off:**

1. The tuples question. Corvid does not have tuples. This is intentional and consistent with the class-based model.
2. Positional destructuring anywhere. Names beat positions, always.
3. Any future proposal to add `for (k, v) in dict`-style shorthand. It contradicts the class-based iteration model.

**Two philosophical points worth remembering:**

1. **Structural commitments have surface-syntax consequences.** Committing to "class as universal" (§1.5) + "universal method dispatch" (§5.1) means `for` doesn't belong in bootstrap. Making that consequence explicit strengthens the design's internal coherence.
2. **The right answer to "how do I do X?" is sometimes "you don't — you do Y instead."** Tuples felt like an obvious language feature. The class-based frame reveals: they're an obvious *feature*, not an obvious *need*. Multi-value returns with named accessors are cleaner, more consistent, and more legible. The friction was in the framing, not in Corvid's expressiveness.

**User's own words on the accessor style:** "yes `for entry in dict: process(key: entry.key, value: entry.value)` is much cleaner and expected." Confirmed that the named-accessor form is the *expected* idiom, not a compromise.

## Session note — v0.8.2 alias namespace discipline + `|>` rejection (2026-08-08)

Short refinement session sharpening the alias mechanism from v0.8.0. Two decisions.

**Decision 1 — user aliases require `@` marker at declaration and use, in every position.**

Path to it: user asked whether alias triggers should use `@` at use sites to make it clear a construct is coming from an alias (with the parser simplification as bonus). I drafted a position-selective proposal — `@` for delimited/prefix/suffix/class-kind but not for substitution aliases (types, values, modules) or infix. User pushed back: "why not `@Vector`? it's a smaller price to pay" and then "just stick with one uniform consistent way to do things."

I was defending "brevity" as if it were the load-bearing win for substitution aliases; it isn't. The load-bearing wins are provenance visibility (reader sees `@X` and knows to grep for `alias @X` in this project) and namespace separation (user's `@Vec` never collides with an imported `Vec`; disjoint tables, no shadowing puzzles). Both apply equally to substitution and surface aliases. Uniformity costs one character per use and buys a globally consistent legibility signal.

Prelude aliases stay unmarked. They are Corvid's committed surface syntax and read as language. The `@` split reinforces the "prelude = language, `@` = project-local" binary from the other direction.

Infix carve-out considered and rejected once `|>` was dropped from prelude (see below); with `|>` gone, the strongest awkward example (`x @|> f`) becomes rare (only projects that actively want pipe), and uniformity is worth more than protecting one aesthetic case.

Grammar: `@` reserved as bootstrap sigil. `alias_trigger` production added at every use position. `user_signature` variant in `alias_signature` requires `@` on the trigger. Prelude sources use `prelude_signature` (unmarked); user code MUST use `user_signature` (compile error otherwise). Two-pass compilation preserved but Pass 2 alias-recognition collapses to "if `@` present, consult alias table; else bootstrap+prelude only."

**Decision 2 — `|>` (pipe) explicitly kept out of the prelude.**

User asked "do we really need `|>` ? what does it actually do for class-based programming."

Working through it: `|>` earns its keep in FP languages where data and behavior are separate (Elixir, F#, OCaml) — it threads values through modules of free functions. Corvid has universal method dispatch, cheap extensions/sections, and open runtime; method chaining (`x.f().g().h()`) is the native pipeline shape and covers every case pipe would. Shipping `|>` would create two idioms for the same operation and pull users toward free functions instead of extension methods (fighting Corvid's core extension model).

Documented in §4.20.3 as a "paradigm-mismatch exclusion." Projects that genuinely want pipe can declare `alias <lhs> @|> <rhs> ...` locally; the `@` marker makes the project-local nature visible at every use site. Swift has resisted `|>` for the same reason.

**Prelude-discipline principle worth remembering.** Constructs common in adjacent languages are excluded from the prelude when they don't fit Corvid's specific paradigm. The prelude should ship what Corvid's model *specifically* wants, not what's popular elsewhere. Worth applying to any future prelude candidate imported from a different paradigm.

**Two philosophical points that landed in this session:**

1. **Uniformity beats special cases when the underlying rule is coherent.** I initially proposed a position-selective `@` rule because I was optimizing local cost (identifier substitutions look ugly with `@`). User's push toward uniformity was right — the rule "all user aliases are `@`-marked" is easier to teach, easier to parse, easier to read, and the per-use cost is smaller than the ecosystem-level cost of a special-case rule.
2. **The prelude is the language's paradigm expressed as syntax.** Excluding `|>` isn't a syntactic decision, it's a paradigm decision. The prelude is a committed statement about how Corvid wants users to compose behavior. Method chaining is the answer; pipe would be a competing answer.

## Session note — v0.8.1 Session 3 fills (2026-08-08)

Small additive session. Filled concrete parity-translation gaps that didn't warrant Level-2-scale mechanism work.

**Decisions in this session:**

- **Narrowing conversion wraps by default.** User pushed on the "trap in dev, wrap in release" recommendation I made initially, and it was inconsistent with §3.9 arithmetic overflow discipline (which wraps in both dev and release). Corrected to: `.into(T)` wraps, `.checked_into(T)` returns Result, `.saturating_into(T)` clamps. One consistent rule across arithmetic and conversion.
- **Parameter defaults are compile-time constants, statically inserted.** User asked "why can't parameter defaults be statically inserted" — the answer is they can, if we restrict defaults to compile-time constants. Same discipline as value aliases (§4.16.10). Removes Python's mutable-default footgun by construction (constants can't be mutated shared state). Runtime-computed defaults use `= nil` + coalesce in body.
- **Enum associated values stay named-only.** User pushed back ("sure?") on my initial proposal to add positional form. Retracted — named-only is more consistent with the rest of Corvid (named args, named record fields, named `accepts`). Style guide note: when translating positional Swift enums, add names.
- **Collection literals rejected as parameter defaults.** They allocate; compile-time-constant discipline requires no allocation. Users needing `= []` default write `= nil` + coalesce in body.

Resolved friction points: `#W-47` (constructor defaults), `#W-56` / `#W-59` / `#W-65` (numeric conversion), `#W-66` (type extrema), `#W-29` multi-binding extension.

**Design pattern worth remembering:** the compile-time-constant discipline is now used in two places — value aliases (§4.16.10) and parameter defaults (§3.8.5). Both restrict to primitive literals + nil + value-alias references. This unifies the "what counts as static substitution" question across the language. If we add more static-substitution positions later, use the same rule.

## Session note — v0.8.0 library-first sugar (Level 2 aliases, 2026-08-08)

Following v0.7.2's unification of class-kind sugar under aliases, user asked: "if `alias` is the sugar system, what if we extend aliases to handle expression-position patterns like `[...]`?" Working through it — this session ("Session 2" originally scoped as collection literals + ranges + interpolation + random) revealed that the aliases-as-sugar-system move had a natural extension into Level 2: pattern-matching alias signatures covering delimited patterns, infix operators, string interpolation.

User pushed for the full commitment. Committed to Level 2 with the framing "sugar is a library" — the compiler ships a minimal bootstrap grammar; the prelude declares standard surface syntax as alias declarations. Version bumped to v0.8.0 (mechanism change, not additive).

**Design decisions in this session:**

- **Bootstrap/prelude split.** Compiler-committed: identifiers, base literals, arithmetic/comparison/logical operators, base expression grammar, control flow keywords, section syntax, class/function/module declarations, the alias mechanism itself. Prelude: type-expression sugars (`T?`, `T!`, `[T]`, etc.), collection literals, range operators (`..<`, `...`), string interpolation (`${expr}`), bitwise operators, nil-coalescing (`??`), class-kind aliases (moved from v0.7.2 hardcoded), section-composition aliases (moved from v0.6.0 hardcoded).
- **Prelude is spec-committed and part of language distribution.** Any change to a prelude alias is a language-level breaking change. Users cannot override or shadow prelude aliases; standard surface syntax is stable across projects.
- **Alias signature pattern language.** Delimited patterns (`[<items>]`, `{<pairs>}`, `{<items>}`), infix/prefix/suffix patterns with precedence and associativity, string-embedded patterns (`${<expr>}`), position markers (five categories), guards (`where <var> is <TypePattern>`).
- **Two-pass compilation with alias resolution.** Pass 1 gathers aliases with bootstrap grammar; Pass 2 re-parses with extended grammar. Aliases cache across incremental compilations.
- **Precedence value scheme.** Unified 1-14 range for prelude+bootstrap operators (following C99 order). User-defined operators MUST use precedence 100+.
- **Grammar-extensibility discipline documented in §4.17.** Bootstrap is fixed; prelude is spec-committed; user aliases are lexically scoped; tooling MUST expose expansions; style discipline is cultural. This is the Clojure/Racket tradeoff applied consistently with §1.2 (intent-not-authority) and §1.5 (runtime-as-substrate).
- **Collection literal conformance protocol.** New §3.10 — `ExpressibleAsArrayLiteral`, `ExpressibleAsMapLiteral`, `ExpressibleAsSetLiteral`. Prelude literal aliases lower to protocol-dispatched constructors; any conforming type (native or Foundation-imported) accepts the same literal syntax.
- **Session 2 items land as prelude declarations from the start.** No language-committed grammar for collection literals, ranges, or interpolation — they're prelude aliases immediately.
- **Random deferred.** Was in original Session 2 scope but it's a stdlib method (`UInt8.random(in: ...)`), not sugar. Not affected by this refactor; can be added as part of a later stdlib session.

**Two things worth remembering:**

- **Corvid the compiler vs Corvid the language.** The compiler is smaller than "the language" — the language distribution is compiler + prelude. Without the prelude, source that looks like ordinary Corvid wouldn't parse. This distinction is real and load-bearing.
- **The style-discipline burden.** The language enables user-defined syntax at all supported positions. Culture and code review are the guardrails against DSL abuse. Style guides SHOULD recommend restraint (domain-specific literals for domain projects; general libraries should not introduce novel syntax).

Resolved friction points: `#W-24` (Map type), `#W-27` (Array literal), `#W-30`/`#W-36`/`#W-55` (string interpolation), `#W-37(a)` (range half-open), `#W-67` (range-based for). Ink cross-exercise gap #22 (string interpolation) resolved as prelude alias.

## Session note — v0.7.2 sugar unification (2026-08-07)

Before starting Session 2 (collection literals + type aliases + random), a drill-down on the alias system surfaced that Corvid had two parallel sugar mechanisms: `alias` (for section-composition/type/value/module names) and §4.19's class-kind sugar layer (for `record`/`enum`/`protocol`/`section` declaration keywords). User asked: "can we go one level higher and say `alias` is the sugar system?"

Working through it revealed that class-kind sugars have a specific shape — header substitution + body prefix injection + body passthrough — that fits naturally under aliases if we add a body-content placeholder. Introduced `...` as the placeholder marker. `alias record Name: class Name: metadata: kind: record; ...` reads as "when `record Name:` appears with a body, expand to `class Name:` with the metadata prepended, then the original body."

Design decisions in this session:

- **Add `...` as body-content placeholder.** Single hole per alias. Multi-hole named placeholders deferred until real need surfaces.
- **Reverse v0.6 §4.19's "no user-defined sugar" stance.** Rationale for reversal: user-defined section/type/value/module aliases were already permitted; refusing user-defined class-kind aliases just for legibility was inconsistent. Legibility responsibility shifts to tooling (mandate: IDEs surface alias expansion on hover and goto-definition).
- **Retire §4.19 as a separate section.** All sugar lives under §4.16. §4.19 becomes a short redirect for readers following old cross-references.
- **Two-pass compilation with alias resolution.** Documented in §4.16.11. Aliases cache across incremental compilations.
- **Keep type-expression sugar language-committed.** `T?`, `T!`, `[T]`, `{K:V}`, `(A,B) -> C` remain compiler-committed grammar rules — pattern-matching over syntax rather than identifier substitution. Lifting them to aliases (via hypothetical `alias suffix '?': ...` forms) would couple parser to name resolution and open the door for user-defined type sugar hurting legibility. Not worth the payoff.
- **Keep closure sugar language-committed.** Closures rewrite the body structurally (into `call` methods) and generate fresh anonymous names — doesn't fit the "body passthrough" model that aliases handle.

Resulting coherent story:
- **`alias`** — pattern-based substitutions at declaration-keyword, section-header, type-name, value-name, module-name positions. `...` for body-content holes.
- **Section classes** — body-level behaviors (§4). What happens inside a section body.
- **Language-committed sugar** — type-expression forms (§3.2) and closures (§5.6). Fixed grammar rules, not user-extensible.

No user-visible behavior change for existing code. All four class-kind sugars work identically; they're just declared in stdlib now rather than hardcoded in the compiler.

## Session note — v0.7.1 Tier 1 sweep (2026-08-07)

Session 1 of the post-nil Category 2 backlog. Direct C inheritance — zero design decisions, filling spec gaps surfaced by the Starscream parity translations. Added §2.7 (Literals — dec/hex/bin/oct integers, floats, char/string literals with C escapes), §3.9 (Operators — arithmetic, bitwise, comparison, logical, precedence table with C99 discipline), §7.1 module-level `let` note, §9.2 `sizeof`/`alignof`/`offsetof` for C interop. Also updated §2.5 keyword list, §3.1 primitive types (added unsigned variants explicit list, primitive-as-value-type note), and §10 grammar productions to match.

Design notes worth remembering:
- **Integer overflow is defined behavior** in Corvid — signed overflow wraps (two's-complement modular), against C99's UB. Users wanting trap or saturation use `checked_*` / `saturating_*` methods returning `Result` / clamped values. Consistent with §1.5 (runtime as substrate, not as ceiling) — Corvid tightens C's slack in favor of predictable behavior.
- **Bit-shift out-of-range is a contract violation** — runtime warning + deterministic wrap in release. Same discipline as overflow.
- **Logical operators are word-form** (`and`/`or`/`not`), not C's `&&`/`||`/`!`. Reserves `&`/`|` unambiguously for bitwise; matches §1.6 legibility premise.
- **Module-level `let` requires type annotation** — no cross-module inference. Determinism at the interface boundary.
- **`var` is not a keyword** — mutable-by-default with `immutable` opt-in per §6.15. Parity translations use `var` inconsistently (Swift habit); noted as drift to sweep in a later idiomatic pass.

Resolved friction points: `#W-17` (`else if`), `#W-54` (bitwise), `#W-64` (compound bit-assign), `#W-68` (`%`), `#W-73` (runtime shift amount).

## Session note — v0.7.0 nil-messaging inversion (2026-08-07)

Following the Starscream parity exercise (Category 2 spec gaps), a drill-down on nil handling surfaced that Corvid v0.6.0 had inherited Swift's optional-explicit model wholesale — `T?` everywhere, `?.` chaining required, `!` force-unwrap available. The parity translations showed this creating substantial ceremony (`?.` at every delegate call, nested `if let` for cookie iteration) that the underlying ObjC runtime doesn't require.

The design shift: **reference types allow nil by default (runtime-honest); the type system marks non-nil expectations via contract sugar `T!`.** `?.` retires; `.` operates on nil receivers uniformly (auto-lift return, or dispatch via new `on_nil` section for punned returns). Force-unwrap `x!` retires at value site — explicit panic via `x ?? panic("...")` is the sanctioned form, verbosity intentional to remove the Rust-style attractor for hidden panics.

Value-type Optional (`Int?` = `Optional<Int>` sum) is preserved — value types can't be nil at runtime, so `T?` retains its sum-type role for them. `T!` on value types is rejected (trivially true).

The `on_nil` section is a stdlib-provided section (built on §4 metaprogramming primitives). Users declare a method-name → nil-time-value table; the compiler generates nil-receiver dispatch. Stdlib `Collection` and `String` protocols ship default `on_nil` entries (`count: 0`, `is_empty: true`, `iter: EmptyIterator()`), so any conforming user type gets Clojure-style nil-punning by conformance rather than per-class ceremony.

Rationale in one line: Swift's nil-safety model paid ceremony taxes (`?.` at call sites, `!` as panic attractor) to fight the ObjC runtime substrate that Corvid targets — inverting the annotation direction removes the taxes and makes the type system honest about runtime truth.

Impact: retired `T?` for reference types (~15 sites in parity translations), retired `?.` (~22 sites), reduced nested `if let` chains, added §3.6.1 (nullability), §4.10.9 (on_nil section), §5.7 (nil-receiver dispatch). Charter unchanged — the shift descends from §1.5 (runtime as substrate) and §1.9 (contracts as coordination primitives) already committed.

---

## Part 1 — The Reasoning Chain

The design emerged from a sequence of framings, each of which reshaped what came before. Reading the design as a static specification obscures the moves that produced it. This section walks through the framings in order.

### 1.1 Origin — comparing Vala, D, and Objective-C

The exploration started as a comparison of three "C with objects" languages, each of which took a different approach:

- **Vala** — transpiles to C, uses GObject as the object system substrate. The language is a syntactic layer over an existing runtime.
- **D** — replaces C entirely with a new language that has C ABI compatibility. Full metaprogramming, algebraic types, comptime.
- **Objective-C** — preserves C exactly, adds Smalltalk-style messaging as a second orthogonal language. Dynamic dispatch as first-class primitive.

The framing observation: these are three different answers to "how do you add objects to C." Each is defined by what it *refuses to change*.

Objective-C's design decision — dynamic dispatch as language primitive, preserved runtime, C interop by identity — turned out to be the most interesting to modernize. Vala is trapped by GObject. D solved the wrong problem (a better C++). Objective-C's semantic model is genuinely distinctive and hasn't been properly modernized.

### 1.2 OOP evolution and the encapsulation axis

The next framing came from thinking about how OOP has evolved: inheritance → composition → actors. What's monotonic across this evolution isn't "OOP getting better" — it's the encapsulation boundary tightening and what crosses that boundary getting more restricted.

| | Boundary | Shared | Crosses |
|---|---|---|---|
| Inheritance | Class (leaky) | Code, memory layout | Method calls, direct field access |
| Composition | Object | Nothing structurally | Method calls (sync) |
| Actors | Actor (mailbox) | Nothing | Messages (async) |

Related patterns don't fit the linear evolution — ECS, prototypes, mixins, traits are variations on how encapsulation is drawn. This surfaced that **classes conflate six things**: state bundling, behavior binding, subtyping, code reuse, dispatch, identity/lifetime. Modern language work is about unbundling these.

### 1.3 Deconstructing OOP as six axes

Any modern OOP language works by unbundling the six axes above. Different languages unbundle differently — Rust unbundles state (structs) from behavior (impl) from subtyping (traits); Swift unbundles value types from reference types; Zig unbundles almost everything via comptime.

This was the setup for the Corvid design: pick which axes to unbundle and how.

### 1.4 The Objective-C successor question

Rather than build a new language from scratch, the question became: **what does Objective-C become if you deconstruct it properly?**

Objective-C already had two key properties nothing else has combined:
1. Fully dynamic dispatch as a language primitive (message send).
2. Runtime as a separate C library (`libobjc`) with stable ABI, cross-platform-portable.

What Objective-C got wrong:
- Every method goes through `objc_msgSend`. No opt-out for hot paths.
- `id` gives up static checking entirely.
- No sum types, pattern matching, comptime.
- Bracket syntax alienates C-family users.
- Header/impl split.

The design bet: **Objective-C's dispatch model, unbundled from Objective-C's ergonomics, targeting the cross-platform native-app space that Swift ceded by staying Apple-tied.**

Runtime substrate: **libobjc2 (GNUstep's runtime)** rather than Apple's objc4. MIT-licensed, cross-platform, modifiable.

### 1.5 Dispatch — sealed vs dynamic vs "cake and eat it"

The first substantive design question was dispatch: sealed-by-default (Swift/Rust style) or dynamic-by-default (Objective-C tradition)?

**Sealed-by-default problem:** library authors have to predict every extension point. Users can't hook what wasn't marked open. This is the Swift `open` problem — framework authors under-mark.

**Dynamic-by-default problem:** every call site pays for capability nobody uses. Compiler can't devirtualize without whole-program analysis.

**Neither fully worked. The resolution:** decouple semantics from performance.

**Committed model:**
- All methods are semantically dynamic. Everything hookable via runtime API.
- `sealed` is a **performance hint**, not an authority claim.
- Compiler assumes sealed methods aren't hooked; if they are, runtime patches call sites (deopt).
- Library authors can't lock users out; users pay cost only where they invalidate assumptions.

This is Option C from the earlier analysis. Requires deopt machinery in libobjc2, but the mental model is clean and it preserves the intent-not-authority principle uniformly.

### 1.6 Author intent, not authority — as foundational premise

The dispatch resolution generalized into a **foundational design premise**: library authors express *intent* about how their code will be used; downstream code always retains capability.

This applies uniformly:
- Sealed methods still swizzlable; sealed call sites deopt.
- Private members still reachable via reflection.
- Sealed classes still subclassable via runtime API.
- Advisory effects (`throws`, `allocates`, `blocks`) checked at compile time; runtime doesn't enforce.

**Non-consequence:** doesn't apply to semantics that change compilation (`async` calling convention, `mutating` struct storage). Those must be enforced because they produce different values under different rules.

This premise made the language philosophically coherent and shaped several subsequent decisions.

### 1.7 Contracts replace nominal types

Given message-passing as the primitive, nominal type systems don't fit well. The natural question at a call site isn't "what type is this object" but "does this object accept these messages."

**Committed:** types become **contracts** — sets of accepted messages with signatures, preconditions, postconditions, effects, and (optionally) session constraints. Conformance is structural.

This subsumes: interfaces, generic constraints, refinement types, effect polymorphism, session types. Not five separate features; one contract language expressed at different verification depths.

### 1.8 Mutability defaults — the split by declaration site

Multiple false starts here.

**First attempt:** immutable by default (Rust/Swift style). Rejected because Objective-C's world is mutable objects.

**Second attempt:** mutable by default everywhere. Ended up with everything getting `immutable` marks, so the wrong case was default.

**Third attempt (from the reframe):** split by declaration site. Fields default immutable (identity is stable); locals default mutable (accumulators are common). Marked exceptions in each case.

**Fourth attempt (reframe again):** the per-declaration-site split is really about **compile-time vs runtime**, not "fields vs locals." Constants are compile-time immutable; class-level counters are compile-time mutable; identity fields are runtime immutable; coordination fields are runtime mutable.

### 1.9 Records vs classes — and the fold into `data:` sections

The record-vs-class question surfaced: should the language have separate `record` and `class` constructs (Kotlin-style)?

**Rejected because of the preemptive-optimization problem:** users can't know at declaration time whether something is "data" or "behavior." Refactoring cost when the shape changes is high.

**Committed:** single `class` construct with **declared regions** for data and state. This became the `data:` and `state:` section syntax — you don't pick at construct level; you organize fields into roles inside the class.

### 1.10 Sections as general mechanism

The `data:` and `state:` sections looked initially like class-body organization sugar. Extending the question — "why not more sections?" — led to the discovery that sections could be a **general language mechanism**.

Enumeration of labeled-block constructs across scopes:
- Class body: `data:`, `state:`, `observable:`, `persistent:`, `secrets:`
- Function body: `quote:`, `pure:`, `unsafe:`, `validate:`, `atomic:`, `checked:`
- Module body: `pure:`, `interop:`, `test:`, `platform(...)`
- Expression: `quote:` (produces `Code`)

**Audit revealed:** not everything fits. `checked:` and `unchecked:` want expression-level rewriting (would make it a full macro system). `catch:` interacts with control flow. `atomic:` has runtime dependencies. Sections work for **block-level compile-time processing of contents, with optional runtime state.**

**Committed model:** sections as general mechanism, cross-scope, with `contains:` (content type) and `scope:` metadata. Class-body-first implementation, other scopes staged.

### 1.11 Sections are classes

The question "should section definitions be class definitions" led to substantive design work. Working through it:

**Reading 1:** section declarations ARE class declarations.
**Reading 2:** section instances are attached to host classes as hidden fields.
**Reading 3:** sections are composed into hosts.

All three are true simultaneously. Sections have **configuration** (compile-time constants), **state** (runtime storage per host instance), **generators** (compile-time code emission), and **methods** (runtime behavior). This is elegantly expressed as a class with self-hosting section-class-body syntax:

```
section class Observable:
    data:
        contains: fields
        composition_mode: nested
    
    state:
        subscribers: [Subscriber] = []
    
    generator field_setter(field: FieldSpec) -> Code:
        return quote: ...
    
    function subscribe(self, callback): ...
```

The section mechanism defines itself. `data:` and `state:` are used in section class bodies with the same meaning they have in user class bodies.

### 1.12 Composition — nesting, then hybrid

**First attempt:** comma composition (`persistent, observable:`). Ambiguous — nobody knows which direction wraps.

**Second attempt:** nesting only. Top-to-bottom = outer-to-inner. Clean but verbose for combining characteristics.

**Third attempt (from the reframe):** **hybrid — inline for characteristics, nested for behavior**. Characteristics commute; behavior wraps. This matches traditional programming syntax (`public static final int MAX`) where modifiers compose inline in any order.

**Fourth attempt (from the final reframe):** all of these are sections. Sections declare `composition_mode: inline | nested`. Characteristics like `public`, `private`, `mutable`, `immutable` are inline-mode sections that compose commutatively. Behavior like `observable:`, `persistent:` are nested-mode sections that wrap.

### 1.13 Primitives and aliases

Committing to sections-are-everything raised: what happens to `data:`, `state:`, `static:`?

**Committed:** they become **aliases** for common primitive compositions.

```
alias data: runtime immutable public identity
alias state: runtime mutable package coordination
alias static: compile_time immutable public
```

Aliases are **pure textual expansion**. No override semantics — composing `data private:` is a contradiction (public + private) and produces a compile error. Users who need variants define their own aliases.

This preserves aliases as transparent shortcuts and pushes users to name their patterns explicitly.

### 1.14 Built-in section vocabulary

Three tiers of built-ins:

**Primitives (13, Stage 1):** `Runtime`, `Compile_time`, `Immutable`, `Mutable`, `Public`, `Private`, `Package`, `Sealed`, `Dynamic`, `Weak`, `Unowned`, `Identity`, `Coordination`.

**Aliases (4, Stage 1):** `data`, `state`, `static`, `methods`.

**Behavioral sections:**
- Stage 1: `Computed`, `Deprecated`.
- Stage 3: `Pure`, `Unsafe`, `Validate`, `Quote`.
- Stage 4: `Interop`, `Test`, `Platform`.

**Deliberately not built-in:** `Observable`, `Persistent`, `Inject`, `Secret`, `Traced`, `Cache`. These have competing designs, aren't universal enough, or are contested territory. Library ecosystem, not language commitment.

---

## Part 2 — Foundational Premises (Committed)

These are the design commitments that shape everything else. They are stable.

### 2.1 Message passing as the semantic primitive

Method calls are messages sent to objects. Every method is semantically dispatchable at runtime. Static dispatch is a compilation strategy, not a semantic model. The runtime maintains method tables for every class; introspection works on everything.

### 2.2 Author intent, not authority

Library authors express intent via annotations (`sealed`, `public`, `private`, effect declarations, section membership). These express expectations, not restrictions. Downstream code always retains full runtime capability.

The compiler acts on intent to optimize the default case; the runtime preserves user power at the cost of local deoptimization when assumptions are invalidated.

### 2.3 Contracts replace nominal types

Types are named contracts — sets of accepted messages with signatures and semantic guarantees. Conformance is structural. Nominal types exist as named contract references but don't gate conformance.

### 2.4 First-class primitives for what dispatch was faking

Observation, interception, extension, delegation, factories — each gets dedicated language constructs, often expressible as user-defined sections. Runtime dispatch is available but rarely load-bearing.

### 2.5 Runtime as substrate, not ceiling

Compiles to native code targeting `libobjc2` (GNUstep's runtime — MIT-licensed, cross-platform). The runtime is a small C library with stable ABI, not a whole VM.

### 2.6 Sections as general mechanism

Labeled semantic blocks are a first-class language feature, uniform across scopes. Sections have `contains:` (content type), `scope:` (applicability), and `composition_mode:` (inline or nested). User-definable via the fixed capability vocabulary.

### 2.7 Compile-time vs runtime as taxonomic axis

The distinction between "per-class" and "per-instance" is compile-time vs runtime existence. This organizes the section vocabulary — primitives compose along orthogonal dimensions (existence × mutability × visibility × dispatch × reference × contribution).

---

## Part 3 — Committed Design Decisions

Concrete, decided, in-spec.

### 3.1 Syntax and lexical structure

- Full words instead of abbreviations (`function`, `dynamic`, `public`, `private`, `enumeration`, `constructor`, `destructor`).
- Explicit `return` (no implicit last-expression return).
- Indentation-delimited blocks (Python-style).
- Keywords: `class open sealed dynamic protocol accepts requires ensures throws async allocates blocks mutable immutable function constructor destructor generator processor validator self Self return section alias if elif else match case for in while break continue import from as public private module true false nil`.

### 3.2 Object model

- `class` — reference type, heap-allocated, ARC.
- `struct` — value type, stack-allocated, copied on assignment.
- `enumeration` — value type, tagged union.
- Single inheritance, structural protocol conformance.
- Class bodies composed of sections.

### 3.3 Dispatch

- All methods semantically dynamic. Runtime maintains method tables.
- `sealed` and `dynamic` are compiler hints for lowering strategy.
- Sealed methods can still be swizzled at runtime; runtime patches sealed call sites (deopt).
- Module-level defaults: `sealed module` or `dynamic module`; default is `dynamic`.

### 3.4 Type system / contracts

- Nominal contracts (`protocol Foo`) name a set of accepted messages.
- Conformance is structural. Explicit `class Foo: MyProtocol` is optional but preferred for public API (early-fails).
- Contracts carry: signatures, preconditions, postconditions, advisory effects, default implementations.
- Sum types (`enumeration`), pattern matching, refinement types via `requires` clauses.

### 3.5 Effects

- **Advisory effects** (compile-time-checked, runtime-permissive): `throws`, `allocates`, `blocks`.
- **Semantic effects** (compile-time-enforced): `async`, `mutating`.
- Strictness (whether advisory become errors) is consumer-side, not library-side (compile flag or per-file pragma).

### 3.6 Memory / mutability

- ARC for classes. Value semantics for structs. `weak`/`unowned` for cycle breaking.
- Mutability applies to bindings, not referenced objects (Swift model).
- Locals default mutable; first-use declares; `immutable` opts into single-assignment.
- Field mutability governed by the section they're in (via primitive composition).

### 3.7 Sections — the general mechanism

- Labeled semantic blocks (`label:` followed by indented content).
- Each section class declares `contains:` (content type), `scope:` (applicability), `composition_mode:` (inline or nested).
- Six content types: `fields`, `methods`, `declarations`, `statements`, `ast_fragment`, `expressions`.
- Nine scopes: `class_body`, `struct_body`, `enumeration_body`, `protocol_body`, `function_body`, `method_body`, `generator_body`, `module_body`, `expression_context`.
- Composition: inline (whitespace-separated names, commuting) or nested (indented blocks, wrapping).
- Section classes self-host — they're defined with `data:` and `state:` sections and `generator`/`processor`/`validator`/`function` members.

### 3.8 Section vocabulary

- **13 primitive sections** (inline-composable characteristics).
- **4 aliases** (`data`, `state`, `static`, `methods`) as pure textual expansion of primitive compositions.
- **User-defined aliases** at module level.
- **Behavioral sections** (nested-composable, with generators and state): `Computed`, `Deprecated` in Stage 1; `Pure`, `Unsafe`, `Validate`, `Quote` in Stage 3; `Interop`, `Test`, `Platform` in Stage 4.

### 3.9 Runtime access

- Every host class instance has an implicit `sections` namespace.
- Section instances accessed via `instance.sections.<name>.method(...)`.
- Introspection via `sections.list()` and per-section `fields()`.

### 3.10 Interop

- **C interop**: direct via `import c "header.h"`.
- **Objective-C interop**: native — same runtime, ObjC classes accessible directly.
- **Swift interop**: via C ABI bridge; not first-class in v0.1.

### 3.11 Concurrency

- `async`/`await` with structured concurrency.
- `async` is a semantic effect (changes calling convention).
- Actors are library patterns, not language primitives.

### 3.12 Implementation stages

- **Stage 1 (v0.1)**: all-dynamic runtime, class-body sections only, built-ins hardcoded.
- **Stage 2 (v0.5)**: whole-module devirtualization, user-definable section classes.
- **Stage 3 (v1.0)**: cross-module deopt machinery, function/method-body sections.
- **Stage 4 (v1.5+)**: module-body sections, further optimizations.
- Semantic model fixed at v0.1; later stages only optimize and expand capabilities.

---

## Part 4 — Open Items

Enumerated by category. Numbers correspond to spec §13 where applicable, plus additional items from working notes.

### 4.1 Highest-priority

**A. Objective-C compatibility strategy** — **RESOLVED (spec §9, §12).**

Committed: file-level compatibility with shared runtime substrate (`libobjc2`). `.m` and `.cor` files coexist in the same project. Native ObjC interop (no bridge layer). Auto-generated ObjC headers for Corvid public APIs. Compilation strategy: LLVM IR direct as primary target; Corvid → ObjC source as bootstrap phase (Stage 0) and permanent alternate output path. No Clang fork. New Stage 1 built-in sections: `Category` for extending classes, `ObjC_Exposed` for explicit ObjC-visible API control. `Any` type formalized for the dynamic boundary.

**B. Reflection API surface** — **RESOLVED (spec §1.7, §4.18).**

Committed: reframed as **class metadata** rather than "reflection." Metadata is a first-class compilation artifact with three consumers: LSP/IDE tooling, runtime metadata queries, external tools via AI-manifest JSON. Class structure is compile-time-fixed; the runtime exposes metadata but does not create new structure. Section describers (new capability §4.5.10) declare each section's contribution to the metadata. Compiler is architecturally incremental from Stage 1 with LSP server as first-class operational mode.

Key reframe: what looks like "runtime discovery" is really "cross-module compile-time metadata query." A serialization framework querying unknown types is really querying types that WERE statically known in some other module. This lets the API be structured around lookups rather than free-form discovery, and lets tooling share the same underlying artifact as the runtime.

**C. Memory model** — **RESOLVED (spec §6).**

Committed: three type categories (struct, enumeration, class) sharing the section mechanism, with a one-bit distinction (state: allowed or not). Struct/enumeration disallow state; class allows it. All downstream properties (value vs reference semantics, static vs dynamic dispatch, extent participation, ARC) follow from this restriction.

Universal extents for all user-declared classes (no opt-in). Class references are universal handles with generation validation (FilC-style safety). Tags own contiguous storage regions within an extent; snapshots are materialized handle arrays. `dense_storage:` section hints SoA layout for cache-optimal iteration.

ARC as reference lifetime mechanism; `weak`/`unowned` for cycle breaking. Extent-scoped cycle detection as Stage 2+ optional library. ECS emerges naturally from tags + protocol queries; no ECS-specific language feature.

Explicit non-goals: no SQL, no persistence, no ACID, no mandatory GC, no user opt-out from extent tracking (use struct instead), no arbitrary structural modification at runtime.

Key reframe: "collections as tags" — traditional OOP conflates ownership with grouping. Under the extent model, extents own storage; tags name subsets; snapshots capture query results. Ownership contribution vs. non-contribution is visible in the type. Bidirectional relationships (parent-child, publisher-subscriber) often simplify: store one direction, query the other. Cycles dissolve because they were artifacts of redundant storage.

### 4.2 Structural design gaps

**D. Session types** (spec §13 item 1)

Contracts could carry message ordering constraints (`open` before `send` before `close`). Attractive because it fits the contract model naturally. Complex to design and implement. Deferred.

**E. Effect polymorphism** (spec §13 item 2)

Generic functions that inherit effects from their arguments. `map` should be `throws` if the mapping function throws. Currently deferred; may constrain what contracts can express if left too long.

**F. Full comptime metaprogramming** (spec §13 item 3)

Beyond section generators. Currently sections cover most of the metaprogramming need. Full comptime (Zig-style) is not committed; might be added later if section generators prove insufficient.

**G. Ownership types** (spec §13 item 4)

Beyond ARC. Could add `borrow`/`move` for zero-overhead cases. Currently deferred as ARC covers the common case.

**H. Pattern matching completeness** (spec §13 item 5)

Under structural conformance, exhaustive matching interacts with open sum types. Design work needed.

### 4.3 Language features

**I. Threading model** (spec §13 item 10)

Cooperative-only? Preemptive? OS threads? Structured concurrency scope rules? Unresolved.

**J. `Self` in protocols** (spec §13 item 9)

Nominal (Rust-style) or existential (Swift-style)? Different implications for what contracts can express.

**K. Standard library scope** (spec §13 item 7)

What ships with the language vs. what's a package? Affects positioning and the "batteries included" story.

**L. Package manager and build system** (spec §13 item 8)

Not a language design question but load-bearing for adoption. Deferred as implementation concern.

### 4.4 Section mechanism specifics

**M. Section versioning scheme** (spec §13 item 11, also §4.15)

Sections shipped in libraries participate in downstream compilation. Changes to a section class's generated code are breaking changes for host classes. Semantic versioning is committed in principle; precise scheme unresolved.

**N. Flat composition sugar** (spec §13 item 12)

Whether to add `>` or similar syntax for multi-section nested composition on one line. Currently deferred; nesting handles the semantic space. Revisit if real usage shows frequent deep compositions.

**O. User-alias export semantics** (spec §13 item 13)

Whether user-defined aliases should be exportable via `import` and how versioning interacts with alias evolution. Related to M.

**P. `strict module` semantics** (previously flagged)

Removed from v0.3. Strictness for advisory effects is consumer-side (compile flag). But: which advisory effects become errors, which stay warnings, how per-file pragmas work — not fully specified.

### 4.5 Stage 3+ built-in sections

Each of these has a spec sketch but needs implementation-level design before Stage 3 lands:

**Q. `Pure` semantics** — precise definition of what effects it prohibits. Different codebases mean different things by "pure."

**R. `Unsafe` capabilities** — precise list of what capabilities it enables. Intersects with memory model.

**S. `Validate` details** — what `require`/`assert` statements look like, contract violation propagation.

**T. `Test` details** — compile-mode detection, test discovery, test running.

**U. `Platform` details** — supported platforms and spellings.

### 4.6 Tooling and ecosystem

**V. Error message infrastructure**

Sections that produce bad error messages will be unusable. Source location tracking through generator emission is committed in principle but is nontrivial engineering.

**W. IDE support**

User-defined sections need to be loadable by IDEs for accurate diagnostics and completion. Affects tooling architecture — sections aren't just a compiler feature.

**X. Debugger provenance**

Generated code needs to be debuggable with clear attribution to the section that produced it.

### 4.7 Working-session items

Items raised in conversation that we haven't fully resolved:

**Y. `static:` at module scope** — currently allowed for module-level compile-time-existing declarations. But module bodies also allow bare declarations (implicit `methods:`). Two ways to write module-level functions. Redundancy might mean `static:` should only apply inside class/struct/enumeration bodies.

**Z. `Computed` interaction with dependencies** — if a computed field references a mutable field, does the compiler track the dependency for invalidation, or does the user manage it? Currently no auto-caching; every access recomputes. Real usage may show this is unacceptable.

---

## Part 5 — What's Been Rejected

Important negative space — decisions we've made *not* to do, and why.

- **Full comptime metaprogramming** as first-class (like Zig). Sections cover the need; general comptime is a bigger commitment we're not making now.
- **Ownership types by default**. ARC is simpler and matches Objective-C mental model.
- **Actors as language primitive**. Library pattern instead.
- **Full source-level Objective-C compatibility.** Rejected in v0.4 §12. But *file-level* compatibility (mixed projects) is open.
- **Homoiconicity / Lisp syntax**. Considered as part of the "sections are classes" reasoning; rejected as too big a commitment for what it buys.
- **Section inheritance**. Sections are flat; composition happens at the host class level.
- **Section method injection into host class** (v0.4 scope). Sections can't add methods to the host; users access section methods via `host.sections.name.method()`.
- **`strict module` construct.** Removed in v0.4. Strictness is consumer-side.
- **Alias override semantics.** Removed in favor of strict composition. Contradictions are compile errors, not warnings.
- **Comma-separated composition on section headers.** Removed in favor of nesting for wrapping and whitespace for inline characteristics.
- **`Constants` and `Companion` as distinct built-in sections.** Folded into `Static` alias, then further folded into primitive composition (`compile_time immutable public`).
- **Method injection via sections** (v0.4). Sections modify accessors and hooks; they don't add methods to host classes.

---

## Part 6 — Where the Design Is Distinctive

Positioning statement — what makes this language unlike others.

**Combines four unusual commitments:**

1. Dynamic dispatch as first-class semantic primitive (Objective-C heritage).
2. Compile-time performance via deopt-backed sealed hints (novel implementation of "cake and eat it").
3. Sections as general labeled-block mechanism (no mainstream language has this).
4. Contracts as replacement for nominal types (Erlang/Elixir-adjacent, but statically-checked).

**No mainstream language has this combination.** Rust has 2 and pieces of 4. Swift has 2 and 4. Objective-C has 1. Kotlin/Scala have pieces of 3 via DSL builders (runtime only). Nobody has all four.

**Section mechanism, specifically**, collapses what other languages handle piecemeal:
- Attributes / annotations (Java, Rust, Python).
- Property wrappers (Swift).
- Decorators (Python).
- Aspect-oriented weaving (Java AspectJ).
- Effect-scoping keywords (Rust `unsafe`, C# `checked`).
- DSL builders (Kotlin, Groovy).
- Compile-time metaprogramming (Zig, Rust macros).

If sections work well, this is the language's headline feature — not "modern Objective-C" but "the language where cross-cutting concerns are first-class and composable."

---

## Part 7 — Next Steps (Working Sequence)

From the current conversation:

1. **§4.6 composition rules** — RESOLVED. Nesting for behavioral wrapping; inline for characteristics.
2. **Additional built-in sections** — RESOLVED. Three-tier built-in vocabulary specified.
3. **File-level Objective-C compatibility** — RESOLVED. Shared runtime, LLVM IR direct, `.m` and `.cor` files coexist, auto-generated ObjC headers, Stage 0 bootstrap via Corvid → ObjC.
4. **Reflection API surface** — RESOLVED. Reframed as class metadata (§4.18) with three consumers (LSP, runtime queries, AI-manifest). Incremental compiler with LSP as Stage 1 commitment.
5. **Memory model** — RESOLVED. Three type categories with one-bit distinction. Universal extents. Handles. Tags with contiguous storage. Snapshots for query results. FilC-style safety.
6. **Concurrency model** — RESOLVED (§1.9, §8). Contracts as coordination primitives. No committed concurrency model. Channel and Signal sections. State machines as synchronization. Structured async.
7. **Module system** — RESOLVED (§7, §4.16 generalized). Three levels: source, module, package. Package section for manifest. Aliases generalized to four categories. Implicit resolution with explicit imports for overrides. Single-file scripts work with zero ceremony.
8. **Empirical validation** — later. Take real Objective-C code, translate to v0.5, see what breaks.

Additional items surfaced during working sessions:

- Working through Stage 3 built-ins (Pure, Unsafe, Validate, Quote) at implementation-level detail.
- Ecosystem section examples (Persistent, third-party sections) as library sections — writing full section-class definitions to prove the mechanism can handle them.
- **AI-authoring implications** — spec now commits to incremental compiler with LSP server in Stage 1. AI tooling can query class structure, contracts, and sections via LSP or via AI-manifest JSON. Legibility-over-brevity is a foundational design premise (§1.6).
- **Package manager tooling** — post-v1 concern. v1 ships with compilation model and manifest support; the package manager tool (fetching, caching, dependency resolution) comes as a separate later effort.

---

## Appendix — Version History

- **v0.1** (initial spec): sealed-by-default, `let`/`var`, `def` for methods, `record` vs `class` construct question, `@interface`/`@implementation` alternatives.
- **v0.2**: "author intent, not authority" elevated to foundational premise. Dispatch model rewritten. Effects split into advisory/semantic.
- **v0.3**: full words instead of abbreviations. Mutable by default (fields). Removed `strict module`. Explicit return.
- **v0.4**: sections as general mechanism. Cross-scope reserved. Content type and scope taxonomies. Primitives + aliases + behavioral three-tier vocabulary. Alias composition strict (no override). ObjC compatibility resolved (file-level mixing, shared libobjc2, LLVM IR + Corvid→ObjC, auto-derived headers). `Any` type. `Category` and `ObjC_Exposed` sections. Stage 0 bootstrap phase. AI-authoring implications.
- **v0.5.1** (authority cleanup): every keyword expressing enforcement the runtime doesn't provide was removed. `sealed`, `dynamic`, `final`, `open`, `private`, `package`, `internal` gone. `public` renamed to `expose`. Closed sum types come from enumerations, not from sealed protocols. Compiler devirtualizes automatically without source hints. Two visibility states (expose / absent) replacing three levels. Ties §1.2 (intent, not authority) into strict discipline across the entire spec.
- **v0.5**: metaprogramming unified — all compile-time user constructs are section classes. Aliases become section classes with `expands_to`. Section classes gain parameters (type/effect/value). Class-level contracts via `invariants:`. State machines emerge from invariants + method contracts (not a separate feature). `switch` for pattern matching with sealed protocols enabling exhaustiveness. Effect polymorphism via generic parameters. `With` section for RAII. Legibility-over-brevity design premise. Explicit metaprogramming boundaries (§4.17): no higher-order sections, no section-generates-section, no cross-section inspection. Class metadata as shared artifact (§1.7, §4.18) — three consumers (LSP, runtime queries, AI-manifest). Incremental compilation with LSP as Stage 1 architectural commitment. Memory model (§6): three type categories with one-bit distinction (struct/enum disallow state, class allows it). Universal extents for classes. Universal handle-based references with FilC-style validation. Tags own contiguous storage regions; snapshots are materialized queries. `dense_storage:` for SoA. ECS emerges from tags + protocol queries. Pre-v1 design stance (§1.8) — bold changes okay until v1.0. Concurrency model (§1.9, §8): contracts as coordination primitives. No committed concurrency model. `Channel` section for async event streams (unifies observables). `Signal` section for one-shot completions. State machines as synchronization via `requires`/`ensures`. Structured async with scopes. Observable section removed — users pick channel, signal, or delegate protocols. **Module system (§7, §4.16 generalized)**: three distinct levels — source, module, package. Package section for manifest (full, embedded, or implicit). Aliases generalized to four categories (section/type/value/module) via one construct. Implicit resolution with explicit imports and `from` clauses for overrides. Standard library implicit. Single-file scripts work with zero ceremony.
