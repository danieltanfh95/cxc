# Corvid v0.5 Validation — Path B: Idiomatic Pass

**Exercise:** Take the three files translated line-by-line from Ink (Markdown, Blockquote, Reader) and rewrite them using Corvid's design idioms. Compare against the parity version. See what the design actually enables.

**Method:** Prose walks you through each design decision. Code blocks show the result. Comparison notes explain what changed and why.

**Key question:** Does the design earn its keep, or are we just moving verbosity around?

---

## What "idiomatic" means here

Parity translation asked: *can we express what Swift expresses?* The answer surfaced 30 friction points — mostly gaps, some genuine wins.

Idiomatic asks a different question: *if we stop pretending to be Swift, what should this code actually look like?*

That means making real choices — struct or class, sections for organization, contracts on state, extents where they help — instead of mechanically preserving Swift's structure. Some Swift patterns are workarounds for Swift's constraints. If we don't have those constraints, we shouldn't inherit the workarounds.

Three files, three different verdicts on what to do with them.

---

## Reader — class, unchanged

The parity pass already made Reader a class because Swift's `inout Reader` pattern doesn't survive our struct discipline. That decision holds in the idiomatic pass. Reader is fundamentally a mutable cursor over a string; struct semantics would fight its nature.

What the idiomatic pass adds: contracts and invariants that were sketched in the parity version get treated as first-class design, not decoration.

```Corvid
module com.ink.internal

class Reader:
    data:
        string: String
    
    state:
        current_index: StringIndex
    
    invariants:
        # Reader's position is always within the string bounds.
        # Enforced at every method boundary. No API can leave it invalid.
        current_index >= string.start_index
        current_index <= string.end_index
    
    methods:
        function init(string: String) -> Reader:
            return Reader(
                string: string,
                current_index: string.start_index
            )
        
        # Read-only query methods.
        # Pure — no state read beyond `self`, no state mutation.
        function did_reach_end(self) -> Bool:
            return self.current_index >= self.string.end_index
        
        function current_character(self) -> Character
            requires !self.did_reach_end():
            # Precondition documents that callers must check first.
            # Runtime-enforced in Stage 1; compile-time-checkable in Stage 3+.
            return self.string.character_at(self.current_index)
        
        # State transitions — atomic under §1.9.
        # If concurrent access ever matters, we get safety for free.
        function advance_index(self)
            requires !self.did_reach_end()
            ensures self.current_index > old(self.current_index):
            self.current_index = self.string.index_after(self.current_index)
        
        # Match-and-consume with typed error.
        function read(self, expected: String) throws ParseError
            ensures self.current_index >= old(self.current_index):
            # Ensures the reader either advances or throws.
            # Callers know: no partial-consumption on failure? 
            # (Actually, this DOES leave the reader partially advanced 
            # on mid-string mismatch. Real Swift Reader has the same
            # behavior. Documenting it is progress even if not fixing it.)
            for expected_char in expected.characters:
                if self.did_reach_end():
                    throw ParseError.unexpected_end
                if self.current_character() != expected_char:
                    throw ParseError.mismatch(
                        expected: expected_char,
                        found: self.current_character()
                    )
                self.advance_index()
        
        function read_whitespaces(self):
            # Note: no `throws` clause. Zero whitespace is fine.
            # Parity version marked it throws for Swift-parity;
            # idiomatic removes the empty throws.
            while !self.did_reach_end() 
                  and self.current_character().is_whitespace():
                self.advance_index()

sealed protocol ParseError:
    case unexpected_end
    case mismatch(expected: Character, found: Character)
```

**What changed from parity:**

The `read_whitespaces` function dropped its `throws` clause. In Swift, `readWhitespaces()` is `throws` because it's part of a uniform interface — many read methods throw. In Corvid with typed throws, an empty throws clause is dishonest. Zero whitespace is a valid outcome, not an error.

The `mismatch` variant of `ParseError` now carries data — expected and found characters. Swift's Reader probably has similar; being explicit here shows the design supports it.

**What the design bought us:**

The invariants block is worth its weight. Two lines guarantee that Reader's position is always valid. No API can leave it in a corrupt state. This is enforceable at runtime in Stage 1 and analyzable at compile time in Stage 3+. Swift depends on discipline; Corvid documents and enforces.

The contract on `advance_index` is more subtle. `ensures self.current_index > old(self.current_index)` says advancing actually moves forward. Combined with the invariant that we're within bounds, we get: after `advance_index()`, we've made real progress toward the end. Parser termination becomes provable at some point.

The state-machine-as-synchronization property (§1.9) is essentially free here. If two async tasks share a Reader, calls to `advance_index()` serialize atomically. Swift's Reader would corrupt under concurrent access; Corvid's is safe by construction.

---

## Blockquote — two types, not one

The parity pass flagged Blockquote as unrepresentable-in-struct because Swift mutates `text` via `text.append(...)`. My first idiomatic attempt kept Blockquote as a struct but used an accumulator pattern with `text = text.appending(...)` — treating the mutation as a construction-phase detail.

That was wrong in an interesting way. I anchored on "Blockquote is one type" — same as Swift, `struct Blockquote` is what's parsed and what's returned. Working through the accumulator, I noticed the O(n²) construction cost and hand-waved it as a library concern. That was the tell: I was fighting the natural pattern by forcing "under construction" into struct-shape.

**The unlock: `read` doesn't have to return `Self`.** The parse result and the parsing machinery can be two types. Blockquote (the value) is a struct. BlockquoteReader (the builder) is a class. `read` lives on the class, returns the struct.

This is the StringBuilder → String pattern in a different guise. It's what most languages do when construction and consumption have genuinely different runtime shapes.

```Corvid
module com.ink.internal

# The parse result. Immutable data. Value semantics.
# What callers hold, pass around, and render from.
record Blockquote:
    conforms:
        Fragment
    
    data:
        text: FormattedText
    
    methods:
        function modifier_target() -> ModifierTarget:
            return .blockquotes
        
        # Factory delegates to the builder class.
        # Fragment protocol requires this on the value type; 
        # implementation is an internal detail.
        class function read(reader: Reader) throws ParseError -> Blockquote:
            return BlockquoteReader.read(reader: reader)
        
        function html(self, urls: NamedURLCollection,
                     modifiers: ModifierCollection) -> String:
            var body = self.text.html(urls: urls, modifiers: modifiers)
            return "<blockquote><p>${body}</p></blockquote>"
        
        function plain_text(self) -> String:
            return self.text.plain_text()


# The parsing machinery. Mutable state, in-place accumulation.
# Ephemeral — one instance per parse, dropped after build().
# Users don't see this type; it's an implementation detail.
class BlockquoteReader:
    state:
        text: FormattedText
    
    methods:
        class function read(reader: Reader) throws ParseError -> Blockquote:
            try reader.read(">")
            reader.read_whitespaces()
            
            var builder = BlockquoteReader(
                text: FormattedText.read_line(reader: reader)
            )
            
            while !reader.did_reach_end():
                switch reader.current_character():
                    case c where c.is_newline():
                        return builder.build()
                    case '>':
                        reader.advance_index()
                    default:
                        break
                
                # Direct in-place mutation. text is state: on a class.
                # No `appending` workaround, no O(n²) cost.
                builder.text.append(FormattedText.read_line(reader: reader))
            
            return builder.build()
        
        function build(self) -> Blockquote:
            return Blockquote(text: self.text)
```

**What this actually is:**

Two types, each honest about what it does. The struct is the value. The class is the machinery. Neither pretends to be both.

The struct pays no extent or ARC cost — parse trees with thousands of blockquotes are cheap. The class pays those costs but only during parsing — one builder allocated per parse, dropped after `build()`. Total cost: one class allocation per parsed fragment, briefly. Result: one struct value per fragment, forever.

**Why I missed this initially:**

I treated "Blockquote" as a single semantic unit because Swift did. Swift bundles "the value" and "the factory" into `struct Blockquote` with `static func read` on it — the type IS the factory. Under Swift's mutating-methods-on-value-types machinery, this works.

Our alternative isn't "use struct with workarounds" or "use class with overhead." It's **two types when the runtime shape of construction and consumption genuinely differ.** The pattern was hiding in plain sight; I anchored on Swift's structure and missed it.

**Which type conforms to Fragment?**

The struct. Fragment describes what a parsed fragment IS — `html`, `plain_text`, `modifier_target` operate on the value. `read` is required by Fragment (so generic parser code can do `T.read(reader:)`) and lives on the struct as a factory that delegates to the class.

BlockquoteReader is an implementation detail. It's `internal`, not exported. Users of Ink see Blockquote; the reader is invisible.

**What the design gives us:**

The value/reference distinction earns its keep concretely. Struct for the value, class for the mutable machinery. Both roles served by their honest types. No workarounds in either direction.

Cost is honest too. Parse trees are cheap (structs). Builders are expensive (classes) but ephemeral. Total overhead scales with parse operations, not with parse output size.

**FormattedText probably follows the same pattern.**

`FormattedText.append(...)` in the class BlockquoteReader body assumes FormattedText has a mutable class variant. Following the pattern: `struct FormattedText` (the value) + `class FormattedTextBuilder` (the machinery). Every Fragment type in Ink likely splits this way.

Fifteen Fragment types = fifteen struct/class pairs. More types than Swift has. Whether that's worth it comes down to whether the honest separation is clearer than Swift's clever unification. My view: yes. Each type does one thing. Reading code, you know which type is the value and which is the builder from the name.

---

## Markdown — class, not struct, and the design pays off

The parity pass documented the TitleStorage workaround as unrepresentable. Idiomatic pass just deletes the workaround because it was fixing a Swift problem we don't have.

Swift's Markdown is a struct because Swift favors value types. But it needs mutable state (cached title) so it uses a reference-typed inner class. This is a pattern in Swift codebases: struct-with-embedded-reference-storage-for-mutability.

Our Markdown should just be a class. Reference semantics, real state, no workarounds.

```Corvid
module com.ink.api

# A parsed Markdown value.
# Class in Corvid (vs Swift's struct) because it has genuine 
# reference-typed state (cached title, ARC'd Heading reference).
expose class Markdown:
    data:
        # Immutable per-instance identity fields.
        # Constructed once during parsing; never change.
        html: String
        metadata: Dict<String, String>
        title_heading: Heading?
    
    state:
        # Mutable derived state — the cached title.
        # Swift's TitleStorage exists to enable this in a struct.
        # In Corvid, state: fields on a class handle this directly.
        cached_title: String? = nil
    
    invariants:
        # If we've cached a title, it's not empty.
        # Empty titles come from bugs, not real docs.
        cached_title == nil or cached_title.length > 0
    
    methods:
        # Auto-generated constructor from data: fields.
        # (See §6.2/§6.3 — needs to be spec'd explicitly.)
        # 
        # Users get: Markdown(html: ..., metadata: ..., title_heading: ...)
        # cached_title defaults to nil from the state: declaration.
        
        # Title access with lazy computation.
        # Swift used a computed property with getter/setter; here we split.
        function title(self) -> String?:
            if let cached = self.cached_title:
                return cached
            
            var computed = self.title_heading?.plain_text()
            if computed != nil:
                self.cached_title = computed
            
            return computed
        
        # Explicit override. Swift's `title` setter had this behavior
        # buried in the property syntax; Corvid makes it a named method.
        function override_title(self, new_title: String?):
            self.cached_title = new_title
```

**What changed from parity:**

`TitleStorage` is gone. It was fixing a Swift problem; we don't have that problem. The cached title lives directly as `state:` on the class.

The `title` computed property with getter and setter got unwrapped into two methods: `title()` for read, `override_title(new_title:)` for write. This is more verbose but more explicit. Callers see clearly which operation they're doing.

The invariant caught a real bug potential: what if someone overrides with an empty string? Now we can't. Swift's code doesn't check this; the runtime would happily return `""` as a title. Corvid's does.

**What the design bought us:**

Under `class`, every Markdown instance goes into a universal extent (§6.4). This means `Markdown.query()`, `Markdown.count()`, `Markdown.by_id()` all work automatically. If we ever want to know "how many Markdown docs are alive with metadata['author'] == 'X'", we get it for free:

```Corvid
authored_docs = Markdown.query(where: 
    self.metadata["author"] == "X"
)
```

Swift needs an external registry to answer this. Corvid has it built in.

Is this useful for a Markdown parser? Probably not directly. But it's a small example of a general property: turning something into a class in Corvid gives us the query surface at no cost. In a document management system built on top of Ink, this becomes valuable immediately.

The state-machine synchronization applies here too. If a Markdown instance has its title overridden concurrently, the atomic transition semantics of `override_title` prevent torn writes. Swift needs a lock; Corvid has it built in.

**One more thing:**

Notice we didn't need to make `override_title` async. `requires`/`ensures` clauses on it would give atomic semantics. Even sync mutation is safe. That's the payoff of §1.9 — synchronization comes from contracts, not from `async`/`await` machinery.

---

## Line count and structural comparison

Rough numbers:

| File | Swift original | Parity Corvid | Idiomatic Corvid |
|---|---|---|---|
| Reader.swift | ~150 (est.) | ~140 (inferred) | 60 |
| Markdown.swift | 58 | 173 | 45 |
| Blockquote.swift | 35 | 171 | 65 (struct 20 + class 45) |
| **Total** | **~243** | **~484** | **~170** |

Parity was ~2x Swift because of friction annotations. Idiomatic is ~0.70x Swift — shorter even though Blockquote became two types. The struct is small (data + three consumption methods); the class is a bit larger (state + accumulation loop). Together they're comparable to Swift's single type; separately they're each clearer.

Structural comparison:

- **Reader:** class in both parity and idiomatic. Idiomatic added contracts and invariants as first-class features rather than annotations.
- **Blockquote:** parity treated it as unrepresentable-in-struct; idiomatic splits into a struct (the value) and a class (the machinery). Each type does one thing.
- **Markdown:** parity tried to preserve Swift's struct; idiomatic makes it a class with clean state and gets universal extents as a bonus.

---

## What surfaced in the idiomatic pass

**Design wins that concretely showed up:**

1. **Contracts on Reader operations.** Precondition checks are documented and enforceable. Swift relies on convention; Corvid makes them part of the type.

2. **Invariants on state.** Reader's position bounds, Markdown's non-empty title. Both prevent classes of bugs Swift can't prevent structurally.

3. **State-machine synchronization free.** Any class with `requires`/`ensures` on state gets atomic transitions. Reader's advance_index, Markdown's override_title — both safe for concurrent access without any concurrency machinery in the code.

4. **Universal extents on classes.** Markdown-as-class means `Markdown.query()` works everywhere. Small win for a parser, large win for anything built on it.

5. **Typed error variants with data.** `ParseError.mismatch(expected:, found:)` carries diagnostic info. Swift's typed throws can do this too; the syntax is comparable.

6. **The two-type pattern for construction-vs-consumption.** When "under construction" and "constructed" have genuinely different runtime shapes, split them. Struct for the value, class for the builder. Swift bundles them via mutating-methods-on-value-types; we separate them via honest types. Each has one job.

**The two-type pattern deserves its own note:**

The Blockquote redesign surfaces a general pattern the language enables. Wherever Swift uses value-type-with-mutating-methods, Corvid has two natural options:

- **One type, class-shaped.** Reference semantics, mutable state. Fine when identity or sharing matters.
- **Two types, struct + class.** Immutable value + mutable builder. Fine when the value is truly ephemeral-after-construction.

Neither is universally better. The choice is about what the type semantically IS. Reader is one thing (a cursor) so it's one class. Blockquote is two things — the parse result and the parsing machinery — so it's two types.

This is more type declarations than Swift needs. It's also more honest. Reading `struct Blockquote` you know: value. Reading `class BlockquoteReader` you know: machinery. No mutating markers to hunt for; no "is this a mutation or a copy" question at call sites.

**Spec gaps that still matter:**

The idiomatic pass didn't eliminate the gaps from the parity pass:

- String interpolation syntax — used in Blockquote's html method
- `try` keyword semantics — used throughout
- Protocol conformance syntax — Fragment on Blockquote
- `old()` in ensures — Reader's advance_index
- Auto-generated constructors — Markdown, Blockquote both rely on them
- Leading-dot enum shorthand — Blockquote's modifier_target

These need spec resolution before any real compiler could accept this code. The design decisions are all clear; the syntax choices aren't.

**A design question I raised then resolved:**

I initially wondered whether the language should provide a builder pattern for struct construction — something like a `builder:` section that generates a mutable builder class from a struct's data fields. The two-type pattern makes this unnecessary. Users write two type declarations. The struct declares the value; the class declares the machinery; the class's `build()` method returns the struct. Explicit, no magic.

The pattern might still be worth generating for structs with many fields (fifteen-field structs would have fifteen-field builders that mirror them), but that's a Stage 2+ ergonomic addition, not a v1 blocker.

**FormattedText follows the same pattern.**

I punted on FormattedText's internal design in the parity pass. Under the two-type framing, the answer is clear: `struct FormattedText` (the value, immutable) + `class FormattedTextBuilder` (the machinery, mutable). BlockquoteReader's `builder.text.append(...)` becomes coherent because `builder.text` is a `FormattedTextBuilder`, not a struct.

This isn't a stdlib performance workaround; it's the same design applied consistently. Every Fragment type in Ink likely splits struct + class. Same pattern, same reasoning.

---

## Overall assessment of Path B

The idiomatic pass delivered what I hoped, plus a design pattern I didn't see coming until pushed to look at Blockquote again. Concretely:

**The design shortens code when it fits.** ~170 lines idiomatic vs ~243 Swift for the same three files. Not because we're playing golf — because contracts replace defensive checking, invariants replace comments, sections replace scaffolding, and we don't need Swift's mutating-methods-on-value-types workaround.

**The gaps are syntactic, not architectural.** Every gap surfaced can be fixed by specifying syntax. None of them require rethinking the design.

**The wins are real.** Contracts on Reader, invariants on Markdown, state-machine sync on both — these aren't marketing points. They'd be enforced by the actual compiler. Swift codebases pay for these with runtime asserts, careful discipline, or bugs.

**The two-type pattern is the biggest concrete finding.** I initially wrote Blockquote as a struct with an accumulator workaround, treating the mutation as a construction-phase detail. That was wrong: it forced "under construction" and "constructed" into one type shape. The real answer is two types — struct for the value, class for the builder. This pattern probably applies to every Fragment type in Ink, and it's the natural resolution of the value-vs-reference question whenever construction and consumption differ in shape.

**The struct discipline holds under stress.** My earlier worry — that struct-forbids-state would break real code — was overwrought in both directions. Structs don't need state because the two-type pattern handles construction. Swift's struct-with-mutating-methods was a workaround for missing a good reference type; we don't need the workaround because we have honest classes.

**A methodological finding:**

I got Blockquote wrong twice — in the parity pass ("unrepresentable in struct") and in the first idiomatic pass (accumulator workaround, hand-waving the O(n²) cost). Both errors came from anchoring on "Blockquote is one type" because Swift's `struct Blockquote` bundles the value and the factory. It took a specific push to see the two-type option.

This suggests something about how to translate Swift code more broadly: Swift's clever unifications (struct-with-mutating-methods, value-type-with-embedded-reference-storage) are worth pausing over. When they appear, ask whether the runtime shapes of construction and consumption genuinely differ. If they do, two types are usually cleaner than one type that pretends to be both.

---

## Recommendations for the spec

Based on this pass, priorities for the next spec revision:

**Must specify (blocking any real code):**

1. **String interpolation syntax.** Pick one: `\(expr)`, `${expr}`, or `{expr}`. I'd lean `\(expr)` for Swift-parity, but any works.

2. **`try` keyword semantics.** Explicit or implicit for throwing calls in throws contexts. I'd lean explicit for legibility.

3. **Protocol conformance syntax.** Resolve the `class X: Protocol` vs `class X` `conforms:` ambiguity with section syntax.

4. **`old()` in ensures clauses.** State transition contracts need pre-state reference. Pick a syntax.

5. **Auto-generated constructors.** Document explicitly that `Type(field: value, ...)` is generated for structs and classes with `data:` and default-initialized state.

6. **String position/index API in stdlib.** Even minimal string processing needs it.

7. **Leading-dot enum shorthand.** Simple decision, real ergonomic impact.

**Worth considering:**

1. **Argument labels.** Swift's `func html(usingURLs urls: X)`. Does Corvid want this? If we're an ObjC successor, worth serious thought — ObjC's selector components serve a similar role.

2. **Predicate-based pattern matching.** Swift's `case \.isNewline:`. Extending §3.7 to support this?

3. **Extension-like organization.** Swift's `extension` for grouping. We rely on sections + visibility. Might want a dedicated organizational mechanism for large types.

**Design decisions to confirm:**

The idiomatic pass reinforced these — not changes, just confirmations:

- **Struct discipline is right.** Real code fits into "value type or reference type" cleanly, sometimes as two types when construction and consumption differ.
- **The two-type pattern (struct + builder class) is the natural response to Swift's mutating-methods idiom.** No language machinery needed; two type declarations suffice.
- **Contracts pay off immediately.** Reader's contracts are the first thing that made me want Corvid over Swift.
- **State-machine synchronization is a real property.** Not just a design claim.
- **Universal extents are surprisingly useful.** Even for a parser.

**A pattern worth documenting in the spec (or a design guide):**

Somewhere — probably §6 or a companion "Design Idioms" document — the two-type pattern deserves explicit treatment. Not as a language feature, but as the recommended translation of Swift's value-type-with-mutating-methods idiom. Users coming from Swift will hit this repeatedly; giving them the pattern name and a canonical example prevents everyone from re-deriving it.

---

## Where I'd go next

Two natural continuations:

**Continue translating Ink.** FormattedText (probably 300+ lines, complex parser with backtracking), List (recursive, exercises sealed enums), MarkdownParser (top-level orchestration). Each new file surfaces more spec gaps and shows more of what idiomatic looks like.

**Fix the top spec gaps first.** String interpolation, try, protocol conformance, auto-generated constructors. Then continue translating with a solid syntactic foundation.

I'd lean spec-gap fixes first at this point. The three current files have taught us most of what a first round of validation can teach. Continuing without fixing the gaps means annotating "FRICTION: string interpolation" a hundred more times.

Third option: **do the AI harness** (the original secondary candidate) as a different-shape stress test. Markdown exercised parsers, enums, and struct/class discipline. AI harness would exercise concurrency, extents, channels, and signals — the axes markdown left unexplored.

I'd rank: spec gaps first, then AI harness for the concurrency axes, then continue Ink translation if we still want more data.
