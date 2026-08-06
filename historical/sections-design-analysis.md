# Sections as a General Language Mechanism — Design Analysis

**Purpose:** determine whether "labeled semantic block" (which we've been calling "section") is a real general mechanism or a class-body-only construct that superficially resembles other labeled-block constructs in other languages.

**Method:** work through concrete cases across scopes, identify what's genuinely shared vs. superficially similar, examine tensions honestly, look at how existing languages handle each case, decide whether unification earns its complexity.

---

## 1. What we're actually testing

The claim: `label:` followed by an indented block is a general syntactic and semantic form. A section class (or similar) governs each label; the compiler processes the block according to that governance; the mechanism works uniformly at multiple scopes.

If true, this is a distinctive language feature that collapses several concepts other languages handle piecemeal.

If false, we have a good class-body organization mechanism that happens to look like some other labeled-block constructs but shouldn't be unified with them.

The test isn't "can we make it work syntactically." Syntax is cheap. The test is: **does the mechanism carry consistent semantics across scopes, or do we end up with the same syntax meaning different things in different places?**

---

## 2. Enumeration of labeled-block constructs

Working list of labeled blocks that could plausibly become sections. For each: what does it do, what scope does it appear in, what semantic weight does it carry?

### 2.1 Class-body sections (our starting point)

| Section | Scope | What it does |
|---|---|---|
| `data:` | class body | Groups identity fields, marks immutable, contributes to equality/hash/copy |
| `state:` | class body | Groups mutable coordination fields |
| `observable:` (user-defined) | class body | Fields publish on change |
| `persistent:` (user-defined) | class body | Fields backed by storage |
| `secrets:` (user-defined) | class body | Fields redacted from logs/display |
| `inject:` (user-defined) | class body | Fields resolved from container at construction |

**Semantic commonality:** each governs a set of field declarations. Governance affects: default mutability, generated accessors, participation in cross-cutting concerns, lifecycle behavior.

**Compile-time behavior:** section class's generators run at compile time to produce accessors and hooks.

**Runtime behavior:** section class's instance holds runtime state per host class instance; section class's methods are callable via `host.sections.name.method()`.

### 2.2 Function/method-body sections

| Section | Scope | What it does |
|---|---|---|
| `quote:` | generator body | Contents treated as AST, not code |
| `pure:` | function/method body | Contents restricted to pure code (compile-time check) |
| `unsafe:` | function/method body | Enables restricted operations within block |
| `atomic:` | function/method body | Contents execute atomically (compile-target dependent) |
| `checked:` | function/method body | Arithmetic traps on overflow |
| `unchecked:` | function/method body | Arithmetic wraps silently |
| `validate:` | method body (prologue) | Precondition assertions |
| `catch:` (as section) | function body | Error handling scope |

**Semantic commonality:** each affects how the code inside the block is compiled. Some restrict what code is legal (`pure:`, `checked:`); some enable additional operations (`unsafe:`); some transform the code entirely (`quote:`); some inject additional checks (`validate:`).

**Compile-time behavior:** section class's processor operates on the block's AST, either validating it, transforming it, or emitting additional code.

**Runtime behavior:** for most function-body sections, no runtime state — they're purely compile-time constructs. Exception: `atomic:` might need runtime coordination.

### 2.3 Module-body sections

| Section | Scope | What it does |
|---|---|---|
| `pure:` | module body | All functions in this module are pure |
| `interop:` | module body | C ABI features enabled |
| `nostd:` | module body | Standard library not available |
| `test:` | module body | Test-only code, not shipped in release |
| `platform(linux):` | module body | Only compiled on Linux |

**Semantic commonality:** each affects the compilation of declarations within the block. Some restrict (`pure:`, `nostd:`); some enable (`interop:`); some conditionally include (`platform(...)`, `test:`).

**Compile-time behavior:** section class's processor operates on the declarations, either validating or gating them.

**Runtime behavior:** typically none — module-level sections are compile-time-only.

### 2.4 Expression-level "sections"?

Could we have sections at expression level? `checked: (a * b + c)` as an expression?

Probably not. Expression-level constructs are too fine-grained for the section mechanism — you'd end up with sections in every arithmetic expression. Existing language mechanisms (typed expressions, effect annotations) handle this better. Sections are for *blocks* of code or declarations, not individual expressions.

**Decision:** sections do not exist at expression level. Sections are for indented blocks only.

---

## 3. What varies across scope

The crucial question: is the *mechanism* the same across scopes, or does it merely *look* the same?

### 3.1 What section classes do at each scope

**Class-body sections:**
- Receive: field declarations.
- Produce: accessor code, lifecycle hooks, runtime state instance, methods on the section instance.
- Effect on host: adds fields, hidden storage, accessor methods, section instance.

**Function-body sections:**
- Receive: statements (code AST).
- Produce: transformed statements, validation errors, or new statements.
- Effect on enclosing function: replaces the block's code with processed code, potentially injects surrounding code.

**Module-body sections:**
- Receive: declarations (functions, types, constants).
- Produce: transformed declarations, validation errors, or none.
- Effect on module: gates or transforms declarations.

These are three genuinely different things. They share a syntactic form (labeled block) and a compile-time processing model (section class processes contents), but what "processing" means differs.

**Is this a problem?** Not necessarily. Rust traits are the same form (`trait Name { ... }`) but different traits do very different things (mark capability, provide default methods, enable operator overloading, express object safety). Users learn "traits" as a form and learn what specific traits do case-by-case.

Sections could work the same way. Users learn "labeled block with semantics" as a form and learn what specific sections do case-by-case. The uniformity is in the *pattern*, not in the *behavior*.

### 3.2 What the section class needs to declare

For the mechanism to work uniformly, section classes need to declare *where they apply* and *what they process*. Sketch:

```
section class Data:
    applies_to: [class_body]
    processes: field_declarations
    
    # ... generators, configuration, etc.

section class Quote:
    applies_to: [generator_body, function_body]
    processes: statements
    
    # ... processor definition

section class Pure:
    applies_to: [function_body, method_body, module_body]
    processes: statements_or_declarations
    
    # ... validator definition
```

The section class carries metadata about where it can appear. The compiler checks this — if you try to use `data:` inside a function body, the compiler errors "Data section applies to class_body only."

This suggests a taxonomy of what sections process. Sketch:

- **`field_declarations`** — a sequence of `name: Type = default` forms.
- **`statements`** — a sequence of executable statements.
- **`declarations`** — a sequence of top-level items (functions, types, constants).
- **`ast_fragment`** — arbitrary AST, treated as data (this is `quote:`'s case).

Different section classes work with different content types. The mechanism is: "labeled block, contents of some type, section class processes contents according to its declared behavior."

### 3.3 Where the pattern breaks down

Let me look for cases where the uniformity is fake.

**Case: `atomic:` needs runtime support.** Most function-body sections are pure compile-time. `atomic:` is different — it needs the runtime to support atomic execution (either via locks, transactional memory, or a specific compile target). This runtime dependency isn't part of the "section" abstraction; it's an implementation detail of the specific section.

**Is this a problem?** Not necessarily. `atomic:` is just a specific section class that happens to require runtime support to implement. Its interface as a section is the same as other sections. The runtime dependency is inside its implementation.

**Case: `unsafe:` grants capabilities that aren't otherwise available.** In Rust, `unsafe { ... }` isn't just a marker; it enables specific language features (raw pointer dereferencing, calling unsafe functions, mutable static access). This isn't just processing the block — it's changing what's legal inside the block.

**Is this a problem?** Sections can express this via their processor: the `Unsafe` section's processor sets a compilation flag on the block's context that enables specific operations. The mechanism supports it.

**Case: `checked:` and `unchecked:` need to affect operator lowering.** When arithmetic expressions inside `checked:` are lowered, they use checked arithmetic operators. This is deeper than "processing the block" — it's rewriting how individual expressions compile.

**Is this a problem?** This is where the mechanism starts to strain. Rewriting expressions inside a block is more invasive than validating or transforming statements. Two ways to handle:

1. **Sections have deep code-rewriting power.** A section processor can traverse the AST and rewrite any node. This is essentially a full macro system.

2. **Sections operate at block granularity; expression-level rewriting is a language feature.** `checked:` isn't a section; it's a language keyword. Sections handle coarser-grained transformations.

I think **option 2 is honest**. If we open sections to expression-level rewriting, we've built a macro system with all its downsides (readability, tooling, debugging). Better to keep sections at block granularity and admit that some concerns (`checked`/`unchecked` if we want them) are language-level features, not sections.

**Revised list:** `checked:` and `unchecked:` come off the sections list. If we want them, they're language keywords, not sections. Same for anything else that needs to rewrite individual expressions.

**Case: `catch:` interacts with control flow.** Error handling has structural constraints — where the catch block lives relative to the throwing code matters. A section approach would need to model these constraints.

**Is this a problem?** Maybe. But probably better to keep error handling as a first-class language construct (`try`/`catch` or `match` on `Result`) rather than trying to force it into the section mechanism.

**Revised list:** `catch:` comes off. Sections don't do error handling.

**Case: `test:` for module-level test code.** A `test:` section inside a module contains code that's only compiled in test builds. This is straightforward — the section's processor conditionally includes or excludes the contents.

**Not a problem.** This works cleanly.

**Case: `platform(linux):` for conditional compilation.** Sections with parameters — the label carries an argument that governs behavior. This is a new syntactic wrinkle.

**Design decision:** sections can take parameters. `label(args):` is legal; the section class receives the args. This is a small but real extension to the section mechanism.

### 3.4 What survives after the audit

Sections that fit the mechanism cleanly:

**Class body:**
- `data:`
- `state:`
- User-defined: `observable:`, `persistent:`, `secrets:`, `inject:`

**Function/method body:**
- `quote:` (inside generators)
- `pure:` (validation only)
- `unsafe:` (capability enabling)
- `validate:` (prologue assertions)

**Module body:**
- `pure:`
- `interop:`
- `nostd:`
- `test:`
- `platform(...):`

Sections that don't fit and should be language features instead:
- `checked:` / `unchecked:` (expression-level lowering)
- `catch:` (control flow structure)
- `atomic:` (arguably — depends on how deep the atomic guarantees go)

That's a real winnow. The mechanism doesn't do everything; it does a specific class of things: **block-level compile-time processing of contents, with optional runtime state.**

---

## 4. Prior art

Which languages have tried to unify constructs like these?

### 4.1 Lisp macros

Lisp collapses essentially all syntax into function calls on data. `(if x y z)` is a function call to `if` on three arguments. Macros are functions that receive syntax and return syntax. Any construct can be expressed as a macro.

This is maximal unification. Sections are a much more restricted version — labeled blocks, specific processing hooks, no arbitrary syntax transformation.

**Lesson:** the Lisp model is powerful but comes at the cost of syntax that's uniformly parenthesized and hard for non-Lispers to read. Sections keep familiar block syntax and give up general syntactic transformation in exchange.

### 4.2 Rust attributes and procedural macros

Rust has `#[attribute]` on individual items (functions, structs, fields) and `macro_rules!` / procedural macros for syntactic transformations. Attributes are like our sections but more targeted (one item at a time) and less structured (just annotations).

**Lesson:** Rust's per-item attribute model is honest about "this attribute affects this item." Sections group items and process them together, which is a different granularity. Rust also uses `unsafe { ... }` as a language keyword (not an attribute), which matches our decision to keep some concerns as language features rather than sections.

### 4.3 Elixir modules with special forms

Elixir has `def`, `defp`, `defmodule`, `defprotocol`, `defimpl`, `defmacro`, `defstruct`, etc. Each is a form with specific semantics. They're not unified into a "labeled block" mechanism — each is a distinct construct.

**Lesson:** Elixir chose consistency-within-purpose (all `def*` forms follow a similar pattern) rather than a general labeled-block mechanism. Fine choice; different from ours.

### 4.4 Racket languages and hash-lang

Racket lets modules declare their language via `#lang name`. The language handles parsing, evaluation, everything. This is generalization at a different level — whole-file granularity rather than block granularity.

**Lesson:** Racket's model is elegant for large-scale customization (write a language, use it) but heavier than sections. Sections are for small-scale customization within a mostly-standard language.

### 4.5 Kotlin's function types with receivers / DSL builders

Kotlin lets you write DSL-like code where blocks are lambdas with a specific receiver type. This gives you labeled-block-like syntax where the "label" is a function name and the block is executed against a receiver.

```kotlin
class Person { ... }
html {
    body {
        div { ... }
    }
}
```

This is close to our sections in spirit — labeled blocks with governed semantics. Difference: Kotlin's DSLs run at runtime; our sections process at compile time.

**Lesson:** the labeled-block-with-governed-semantics pattern is a proven ergonomic win (Kotlin DSLs are widely liked). Sections are a compile-time version of the same pattern.

### 4.6 Rebol / Red

Rebol has dialects — languages defined by their evaluation function. A block `[...]` in Rebol can be evaluated by different dialects, producing different results. This is maximal generalization at the syntactic level.

**Lesson:** interesting but too general for our purposes. Sections should have well-defined semantics per label, not "the interpretation depends on how you evaluate them."

### 4.7 What emerges from prior art

Sections sit in a specific niche in the design space:

- More structured than Lisp macros (labeled, typed content, specific hooks).
- More scoped than Rust attributes (governs blocks, not individual items).
- More compile-time than Kotlin DSLs (processing happens at compile time).
- More constrained than Racket's `#lang` (per-block, not per-file).
- More typed than Rebol dialects (contents have known types).

Nobody has this exact combination. Whether that's because nobody thought of it or because it doesn't work is what we're testing.

---

## 5. Design tensions

Real tensions I've identified while working through this.

### 5.1 Uniformity vs. specificity

The mechanism is more elegant if all sections work the same way. It's more powerful if different sections can do different things.

Example: `data:` sections need generators (produce accessor code); `pure:` sections need validators (check contents); `quote:` sections need transformers (rewrite contents as AST); `observable:` sections need both generators AND runtime state.

If sections are uniform, we need a rich enough vocabulary to cover all these cases in one mechanism. If sections are specific, we lose the uniformity that makes the mechanism a "selling point."

**Resolution:** the section class declares which capabilities it uses. A section class can have any combination of: generators, processors, validators, runtime state, methods. Different section classes use different combinations. The mechanism is uniform (same declaration syntax) but capabilities are opt-in per section class.

### 5.2 Scoping and nesting

Can sections nest? What happens when they do?

```
class Foo:
    observable:
        value: Int = 0
        
    quote:
        # is this legal in a class body? probably not — quote applies to statements.
        # compiler would error: "Quote section does not apply to class_body."
```

Nesting within a scope is legal as long as each section's `applies_to` covers that scope. Cross-scope nesting is a category error and rejected.

```
function process(self):
    quote:
        pure:
            # inside a quote block, we're building an AST.
            # inside that AST, we have a pure: section.
            # This is legal — the pure: section will apply when the AST is spliced.
            do_thing()
```

Nested sections inside `quote:` are quoted along with the rest of the AST. The nesting is preserved and applies when the AST is spliced into a real context.

### 5.3 Section interaction and ordering

When multiple sections apply to the same content, order matters. We addressed this for class-body sections (top-to-bottom = outer-to-inner wrapping). For function-body sections?

```
function process(self):
    pure:
        unsafe:
            # Contradiction: pure says no side effects, unsafe enables side effects.
            do_dangerous_thing()
```

The sections are in conflict. How does the compiler handle it?

**Options:**

1. **Innermost wins** (`unsafe` overrides `pure`). Simple but potentially surprising.
2. **Compile error** — the compiler detects contradiction and refuses.
3. **Sections declare compatibility** — `pure:` says "cannot contain `unsafe:`"; compiler enforces.

I'd propose **option 3**. Section classes declare which other sections they're compatible with (or can't contain). This gives section authors control over composition without hardcoding rules in the compiler.

### 5.4 Compile-time boundaries

Sections process contents at compile time. This raises: when does that processing happen, and what can it see?

For class-body sections, processing happens when the class is compiled. The section can see the class's declared interface, its fields (within the section), and the section class's own state.

For function-body sections, processing happens when the function is compiled. The section can see... what? The enclosing scope's variables? The enclosing type? Other statements in the function?

**Design decision:** function-body sections see the block's contents (the statements), the immediately enclosing function's signature, and the section class's own state. They do NOT see other statements outside their block, other sections in the same function, or details of the enclosing type beyond its interface.

This constrains what function-body sections can do but keeps them tractable. If a section needs more context, it's probably trying to do too much.

### 5.5 Error messages

When a section fails (produces invalid code, detects a violation, contradicts another section), the error needs to point to:

1. The section usage (where the label appears in the user code).
2. The section class definition (where the rule that was violated is declared).
3. The specific content that violated the rule.

This is nontrivial engineering. Sections that produce bad error messages will be unusable in practice. Any spec commitment to sections needs to include a commitment to good error messages, which affects the implementation cost significantly.

### 5.6 Debugging and tooling

Generated code needs to be debuggable. When a user steps through `counter.value = 5` and hits the auto-generated setter from `observable:`, the debugger should show meaningful information — either the source code of the generator or the resulting generated code, with clear provenance.

Sections that generate code need to carry source-location information through the generation process. This is standard for compilers with macro systems but adds real implementation cost.

Tooling (IDEs, formatters, linters) needs to understand sections. If sections are user-definable, IDEs need to be able to load user-defined sections to provide accurate hints. This affects tooling architecture — sections aren't just a compiler feature, they're an ecosystem feature.

### 5.7 Versioning

If sections are user-definable and shipped in libraries, changes to a section class can break user code. The `Observable` section might change what code it generates in v2.0, breaking every class that used it.

Standard library ecosystem problem, but sections make it worse because sections govern more than a normal library dependency does — they participate in the very compilation of the code that uses them.

**Solution:** sections have explicit semantic versioning. Changes to a section's generated code are breaking; sections declare compatibility explicitly.

---

## 6. Concrete syntax proposal

Bringing it together. Here's what section usage looks like across scopes:

### 6.1 Section class definition

```
section class Observable:
    applies_to: [class_body]
    processes: field_declarations
    
    configuration:
        default_mutability: mutable
        contributes_to_equality: false
    
    state:
        subscribers: [Subscriber] = []
    
    generator field_setter(field: FieldSpec) -> Code:
        return quote:
            function set_${field.name}(self, new_value: ${field.type}):
                old = self._storage.${field.name}
                self._storage.${field.name} = new_value
                self.sections.observable.notify(
                    field: ${field.name.as_string},
                    old: old,
                    new: new_value)
    
    function subscribe(self, callback): ...
    function notify(self, field, old, new): ...


section class Pure:
    applies_to: [function_body, method_body, module_body]
    processes: statements_or_declarations
    
    compatible_with: [validate, quote]
    incompatible_with: [unsafe]
    
    processor:
        function process(contents: Block) -> Block:
            for item in contents:
                if item.has_effect(.io, .mutation, .allocation):
                    @compile_error("pure section cannot contain '${item.effect_name}'")
            return contents


section class Quote:
    applies_to: [generator_body, function_body]
    processes: ast_fragment
    
    processor:
        function process(contents: AST) -> Code:
            return Code.quote(contents)
```

### 6.2 Section usage across scopes

**Class body:**
```
class Counter:
    data:
        id: CounterID
    
    observable:
        value: Int = 0
```

**Function body:**
```
function compute_something(x: Int) -> Int:
    pure:
        result = x * 2 + 1
        result = result * result
    
    return result
```

**Module body:**
```
module com.example.utils

pure:
    function factorial(n: Int) -> Int: ...
    function fibonacci(n: Int) -> Int: ...

test:
    function test_factorial(): ...
    function test_fibonacci(): ...
```

**Section with parameter:**
```
module com.example.linux_specific

platform(linux):
    function get_kernel_version() -> String:
        return c.uname().release
```

**Nested sections:**
```
function compute(x: Int) -> Int:
    validate:
        require x > 0
        require x < 1000
    
    pure:
        result = x * 2
    
    return result
```

### 6.3 The section class body itself

Note: the section class body uses `configuration:`, `state:`, `generator:`, etc. These look like sections. **Are they sections?**

Two options:

**Option A: They're sections all the way down.** `configuration:`, `state:`, `generator:` are built-in section classes that apply to section-class bodies. Elegant recursion.

**Option B: They're syntactic slots in the section class definition, not sections themselves.** Section class definitions have specific structure; those labels are keywords, not section names.

I lean **Option B** for simplicity. Making sections self-hosting is elegant but adds bootstrap complexity. The section class body has a fixed shape; the labels inside are language keywords. If we later want to make it self-hosting, we can. Not urgent.

---

## 7. Decision framework

Whether to commit to sections-as-general-mechanism depends on three questions:

### 7.1 Is the mechanism actually uniform?

The audit in §3 showed that sections share a syntactic form and a processing model (section class governs contents), but the content types differ (field declarations vs. statements vs. declarations), and the processing capabilities differ (generation vs. validation vs. transformation).

**Verdict:** the mechanism is uniform *at the level of "labeled block processed by a section class."* It's not uniform at the level of "sections all do the same thing." This is a real distinction — users learn the pattern once, learn specific sections case-by-case.

Comparable to Rust's `trait` — same syntactic form for very different capabilities. The uniformity is in the *interface*, not in the *behavior*.

I think this is a real form of uniformity. Not maximal, but real.

### 7.2 Is the mechanism distinctive?

No mainstream language has exactly this combination of properties:
- Labeled blocks (like Kotlin DSLs but compile-time).
- Governed by classes (like Rust traits but for blocks, not types).
- Extensible by users (like Lisp macros but with structured contents).
- Cross-scope (like nothing I've found).

The specific combination is distinctive. Whether it's *useful* enough to be a selling point depends on whether the use cases it enables are real.

The strongest cases are:
- `data:` and `state:` in classes (clean organization, replaces attribute mess).
- `observable:` and `persistent:` as user-definable (replaces framework annotations).
- `quote:` for compile-time metaprogramming (replaces separate macro system).
- `pure:` and `unsafe:` for effect discipline (replaces separate language keywords).

That's a real set. Not a Rust-traits-scale unification, but real.

### 7.3 Can we ship it?

Implementation costs, roughly:

**Class-body sections only (v0.4):**
- Section class syntax and semantics.
- Compiler support for a few built-in sections.
- User-definable sections at v0.5.
- Cost: several months of compiler work.

**Full cross-scope sections (v0.4):**
- Everything above, plus:
- Section class metadata for `applies_to`, `processes`.
- Compiler support for section processing at each scope.
- Interaction rules between sections.
- Error message infrastructure.
- Tooling support.
- Cost: probably a year of compiler work before it's usable.

The gap is substantial. Full cross-scope sections are a much bigger commitment than class-body sections.

---

## 8. Recommended path

Based on the analysis:

**Design the mechanism generally in v0.4** so we don't paint ourselves into corners. The spec describes sections as a general mechanism with `applies_to` metadata. This includes:

- Section class declaration syntax (with `applies_to`, `processes`, `configuration:`, generators, methods).
- The vocabulary of processing capabilities.
- The interaction and composition rules.
- The error message and tooling expectations.

**Implement in stages:**

- **v0.1 (Stage 1):** built-in `data:` and `state:` in class bodies. Section classes not yet user-definable. Compiler has hard-coded handling.

- **v0.5 (Stage 2):** user-definable section classes for class-body scope. Ecosystem sections (`observable:`, `persistent:`, etc.) become writable. `quote:` available inside generators as a specific block form (not yet a general section).

- **v1.0 (Stage 3):** function/method-body sections. `pure:`, `unsafe:`, `validate:`, `quote:` (generalized). Deopt machinery from earlier design lands here too.

- **v1.5+ (Stage 4):** module-body sections. `test:`, `platform(...):`, `interop:`, `nostd:`.

This lets us commit to the design without committing to a year of implementation before the language is usable.

**One thing worth committing to now:** the syntax for `label:` as a section reference is reserved language-wide from v0.1. No other feature will use that syntax at any scope. This preserves the design space for later stages without breaking existing code when we generalize.

---

## 9. What this means for the pitch

If we do this well, the language's positioning changes.

**Without sections:** "Objective-C's dynamic dispatch with modern type system and contracts."

**With sections:** "Objective-C's dynamic dispatch with a compile-time mechanism that collapses cross-cutting concerns, framework annotations, effect systems, and metaprogramming into one coherent extensibility model."

The second pitch is meaningfully different. It's a language-level bet on a specific design idea rather than incremental improvement over an existing language.

Is that bet right? The analysis says: the mechanism is real, distinctive, and probably worth the implementation cost, but only if we commit to designing it carefully and staging it responsibly. Rushing sections into v0.4 would produce a half-designed feature that constrains everything after it.

---

## 10. Open questions before spec revision

1. **Section class body: sections all the way down (self-hosting) or fixed structure?** My lean: fixed structure. Sections are a language feature; section class definitions have specific slots.

2. **How much of the vocabulary is fixed vs. extensible?** The processing capabilities (`generator`, `processor`, `validator`) — are they hardcoded in the language, or can users define new kinds of processors? My lean: hardcoded. Users define sections; sections use language-defined processing capabilities.

3. **Naming.** "Section" is fine but generic. Alternatives: `region`, `block_class`, `directive`, `annotation`. I'd stick with "section" but the naming affects perception.

4. **The `applies_to` vocabulary.** What are the exact scopes? `class_body`, `struct_body`, `function_body`, `method_body`, `generator_body`, `module_body` — are these final, or is there a more principled set?

5. **Compile-time evaluation power.** How much of the language runs at compile time for generators and processors? My lean: pure functions, arithmetic, string manipulation, AST manipulation, `FieldSpec`/`Block` APIs. No I/O, no runtime dispatch, no long-lived state. This is enough for realistic sections without opening full comptime.

6. **Do we commit to user-definable sections in v0.4 spec even if implementation is v0.5+?** Yes — commit in spec, stage in implementation. Users can rely on the design being final; the compiler catches up over time.
