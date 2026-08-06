The three languages form a triangle because they're three different answers to the same question: how do you add object orientation to C?

Vala: don't touch C. Sit above it. Use GObject (an existing C-level object system) as the substrate and be a nicer syntax over it. Vala's compiler is a Vala-to-C transpiler.
Objective-C: preserve C exactly. Bolt on a second, orthogonal language — Smalltalk-style message passing — that coexists with C. [obj method] is not C; it lowers to objc_msgSend(obj, sel, …) at runtime.
D: give up on preserving C. Design a whole new systems language that treats C ABI compatibility as an interop feature, not a constraint on semantics.

That premise-level difference propagates into every other axis.

Origin and design premise

Vala (2006, Jürg Billeter/Raffaele Sandrini) exists to make GNOME development pleasant without introducing a runtime. The language is defined by what it refuses: no VM, no separate runtime, no ABI incompatibility with C. Everything it does is a syntactic shortening of GObject C code. If GObject can't express it, Vala usually can't either.

Objective-C (1984, Brad Cox / Tom Love; adopted by NeXT then Apple) was Smalltalk-in-C. The design commits to two coexisting paradigms: C for anything static and performance-critical, dynamic messaging for anything object-oriented. It never tried to unify them.

D (2001, Walter Bright) was Bright rewriting C++ the way he thought it should have been. Every C++ mistake he'd catalogued at Zortech/Digital Mars became a design decision. D therefore commits to being a superset of goals (systems performance, generic programming, direct memory access) rather than a superset of any actual language.

Object model and dispatch

Vala has classes with single inheritance and multiple interfaces, properties, signals, and virtual methods — but all of it is the GObject model in a trench coat. Every Vala class inherits from GLib.Object, dispatch goes through GObject vtables, signals are GObject signals, properties are GObject properties. This buys automatic introspection, D-Bus support, and language-binding compatibility (Python, JavaScript via GJS, Rust, etc. can all consume Vala classes because they're just GObjects). It costs you: no value-type classes, no zero-overhead abstractions, everything's on the heap.

Objective-C has classes with single inheritance and protocols (interfaces). Every method call is a dynamic message send — the runtime looks up the method by selector at call time. This is why categories work (add methods to existing classes at runtime), why method swizzling works, why key-value coding works, why the responder chain works. It also means the compiler can't inline object method calls the way C++ or D can. id is the universal object type; anything can be sent any message; type checking is cooperative rather than enforced at the object level. Objective-C++ additionally lets you mix in C++ freely — the same file can have .mm extension and use both.

D has both value types (structs) and reference types (classes). Classes get Java-style single inheritance plus interfaces and virtual dispatch; structs are C-style POD-ish with methods, operator overloading, and destructors, no vtable. The compiler can devirtualize aggressively. There's no runtime message send — everything's resolved at compile time or through a normal vtable. No categories, no swizzling. final on methods enables further devirtualization.

The dispatch difference matters concretely: in Objective-C you can add methods to NSString from any file at runtime; in Vala you can't (would require a new GObject class); in D you use UFCS (uniform function call syntax) to fake the syntax but it's a free function, not a method.

Memory management

Vala uses GObject reference counting. owned and unowned annotations control ownership transfer at boundaries. Weak references are supported via weak. Cycles are the classic ref-counting problem and you break them by hand.

Objective-C uses ARC: the compiler inserts retain/release/autorelease calls at compile time based on ownership annotations (strong, weak, unsafe_unretained, copy). Weak references are zeroing weak references — they nil out when the target deallocates. Cycles same story.

D defaults to tracing garbage collection. You can opt out with @nogc (compiler enforces no GC allocations in a function's transitive body), use manual allocation, use RC via library types, or use -betterC mode which strips out the runtime and GC entirely, giving you something closer to C-with-templates. The GC default is D's most controversial choice for systems programming — it's why some potential D users bounce off.

The relevant structural point: Vala and Objective-C picked one memory model and committed. D tried to support several and pays for it in the form of a fragmented ecosystem (GC libraries vs. @nogc libraries vs. -betterC libraries don't always interoperate cleanly).

C interop

Vala: essentially free at the codegen level (Vala is C), but you need .vapi binding files to describe a C library's API to the Vala compiler. VAPIs exist for most GObject libraries and many others; writing one for a new library is a real task.

Objective-C: superset of C. Paste any C code in, it compiles. Include any C header, call any C function directly. Objective-C++ extends this to C++. This is the tightest C interop of the three — it's not interop, it's identity.

D: extern(C) declarations import C functions; extern(C++) for C++ (with restrictions). No header parser is built in — you either write declarations by hand, use DStep/dpp to translate, or use ImportC (a C compiler front-end built into DMD that lets you import .c files directly, added a few years ago). Solid but not seamless.

Metaprogramming

This is where D pulls away from the other two, hard.

D has templates (Turing-complete, with static if, static foreach, template constraints), compile-time function execution (CTFE — a substantial subset of D runs at compile time, including string manipulation, so you can generate D source at compile time and mix it in), string mixins, template mixins, and traits (__traits(allMembers, T) and friends for introspection). You can write a JSON serializer that reflects over a struct's fields at compile time and generates specialized code with zero runtime overhead. This is D's signature capability.

Vala has essentially no compile-time metaprogramming. Some codegen via GObject-Introspection, but that's a runtime facility. Generics exist but are limited (they mostly work through GObject's dynamic type system with casts).

Objective-C has no compile-time metaprogramming worth naming. Its metaprogramming story is runtime: introspection, dynamic method resolution, NSInvocation, method swizzling, KVC/KVO. Powerful in a completely different direction — you can do things at runtime that D can't, in exchange for doing nothing at compile time that D can.

Type system

Vala: static, nominal, single inheritance + interfaces, nullable types with ? suffix, generics (constrained by GObject, so limited compared to C++ or D).

Objective-C: static for C types, dynamic for object types (id is any object). Lightweight generics on collections (NSArray<NSString *> *) added around 2015 primarily to improve Swift interop — they're erased at runtime. Nullability annotations (nullable, nonnull) added around the same time, also mainly for Swift.

D: static with type inference (auto), templates for parametric polymorphism, algebraic effects via @safe/@system/@trusted and pure/nothrow, contracts (in/out/invariant), and a well-developed range-based standard library. Probably the most sophisticated type system of the three, though also the most complex.

Ecosystem and where each is actually used

Vala: GNOME stack and GObject-based development. Real applications: elementary OS's default apps, Geary, Shotwell (historically), various GTK utilities. Community remains active in 2026, but it's a small ecosystem — you're writing GNOME software or you're mostly on your own. Outside of GTK/GNOME, Vala has little pull. 
GNOME
Programming Helper

D: general-purpose but small. Historical anchor tenants: Sociomantic (ad tech, acquired by Adobe), Weka.io (storage), Symmetry Investments (finance), some game dev, embedded work via -betterC. Package ecosystem via dub. It's viable for greenfield systems work if you already like it, but you'll write more bindings and hit more "no library exists" moments than in Rust or Go.

Objective-C: was Apple's platform language until Swift arrived in 2014. Now: "specialized maintenance" phase reserved for low-level C++ interoperability and legacy codebases. Legacy codebases, enterprise applications with deep historical roots, and specific scenarios requiring intricate C/C++ interoperability. Outside Apple platforms: GNUstep exists as a cross-platform runtime, but it's a niche. Nobody starts a new project in Objective-C in 2026 unless they're extending an existing one. 
iSpeech
LetDigitalFly

Current status (as of mid-2026)
Vala: 20th anniversary in 2026, active development, docs consolidation project ongoing. Latest LTS branches 0.48 and 0.56; reference manual updated March 2026. 
Programming Helper
GNOME
D: Stable release 2.112.0 in January 2026; three actively maintained compilers (DMD reference, LDC LLVM-based, GDC GCC-based). LDC is what you'd actually ship. 
Wikipedia
Objective-C: Apple stopped adding features that don't improve interoperability with Swift around 2014. Still compiled by clang, still shipping, no roadmap. 
Quora
Where they actually compete

They mostly don't. If you're building a GNOME app, Vala vs. D vs. Objective-C isn't a real choice — it's Vala vs. C vs. Rust vs. C++. If you're on Apple platforms, it's Swift vs. Objective-C vs. C++. If you want a modern C++ alternative for systems work, it's D vs. Rust vs. Zig vs. Nim, not Vala or Objective-C.

The one axis where they're genuinely comparable is "language that transpiles cleanly to something else with C-level performance and no runtime overhead." Vala transpiles to C. Objective-C essentially is C plus a runtime call. D compiles to native but doesn't produce readable C. If your specific requirement is "output C source that a C toolchain can consume" — for example, targeting a platform where only a C compiler is available — Vala is the only one of the three that fits, and that's a real (if narrow) niche it occupies alone.

The deeper structural note: Objective-C and Vala both accept an existing object system as given (Smalltalk semantics; GObject) and design the syntax around it. D refuses that constraint and defines its own. This is why D has the richest type system and metaprogramming, and also why D has the smallest install base of the three at Apple/GNOME scale — it doesn't get to ride an existing runtime's coattails.

as i understand it, OOP programms evolve (within the same language, often in a timeline ) inheritance->composition->actors ? inheritance is "quick to prototype", more flexible and shares code/memory? composition is more complicated and requires planning or extraction from inheritance, and composition often pre-empts or assumes domain level modelling to bottom-up design. actors are similar to microservices in complete isolation of logic modularity and only assumes interfaces? did i miss anything

Examined paradigm progression through constraint shifts and encapsulation boundaries
Examined paradigm progression through constraint shifts and encapsulation boundaries

The arc is real. What's actually monotonic across it isn't "OOP evolving" — it's the encapsulation boundary tightening and what crosses that boundary getting more restricted.

	Boundary	What's shared	What crosses
Inheritance	Class (leaky — subclasses see internals via protected)	Code (vtable), memory layout, sometimes fields	Method calls, direct field access from subclasses
Composition	Object	Nothing structurally; behavior via delegation	Method calls (synchronous)
Actors	Actor (mailbox)	Nothing	Messages (asynchronous)

Each step drops something from "shared" and constrains "crosses." That's the underlying movement your arc traces.

A few things to extend or complicate:

Chronology vs. adoption. The evolution is industrial adoption, not idea development. Hewitt's actor model is 1973 — earlier than "composition over inheritance" as a slogan (Design Patterns, 1994) and contemporaneous with Smalltalk's inheritance. Erlang shipped 1986. The arc looks like a timeline because constraints changed and different ideas won at different times: single-threaded object graphs made inheritance/composition tractable; multicore and distribution made shared state the bottleneck, so actors won. The ideas coexisted the whole time.

Inheritance's flexibility inverts with scale. In the small: extend, override, ship — the "quick to prototype" observation is right. In the large it becomes the least flexible of the three. A class can only be in one hierarchy, base class changes ripple through subclasses you don't own (the fragile base class problem), and the coupling is to implementation, not just interface. The industry moved to composition because inheritance-based systems calcified in a way composition-based ones didn't. The flexibility claim holds at the individual class level; it reverses at the system level.

"Composition requires planning" — the deeper thing there. Bad inheritance produces a superficially clean tree that only fails when you modify the base. Bad composition produces obvious spaghetti — object graphs with no clear layering. Same underlying cause (no domain model), but composition's failure surfaces earlier. So the "planning" observation is really that composition can't fake domain understanding the way inheritance can. Not intrinsically harder to prototype; harder to hide the absence of a model with.

Actor "interfaces" are heavier than method signatures. A method signature specifies types in and out. A message protocol includes: delivery guarantees (at-most-once / at-least-once / exactly-once), ordering (per-sender FIFO, no ordering, causal), backpressure (bounded mailbox behavior on overflow), timeouts, and failure modes (supervision, restart semantics). You get isolation and inherit a distributed-systems problem inside your program. Your microservices analogy is exact — they're the same structure at different scales.

Missing points on the arc:

Mixins and traits — Ruby modules, Scala traits, Rust traits, Kotlin interfaces with defaults. These sit between inheritance and composition. They give inheritance's code-reuse without single-inheritance rigidity — orthogonal capabilities composed into a type. Modern languages have mostly chosen traits as the code-reuse mechanism and left inheritance for interface conformance. Rust in particular has only traits, no inheritance at all.

Prototypes — JavaScript's original model is neither classical inheritance nor composition. It's delegation: obj.__proto__ is a runtime chain of fallback lookups. Same "code reuse without a class hierarchy" goal, different mechanism. Separate branch of the tree that got mostly hidden by ES6's class syntax sugar.

Data-oriented / ECS — Games abandoned "object owns data and behavior" around 2010–2015 for Entity-Component-Systems: entities are IDs, components are pure data stored in contiguous arrays by type, systems are functions iterating over components. Inverts OOP — behavior centralized, data decentralized — for cache locality the classical model can't touch. Not on your arc but a real alternative that high-performance code migrated to.

Functional core, imperative shell — Between composition and actors there's a widely adopted idiom that separates pure data (records) from behavior (module-level functions). Rust structs + impl blocks, Elixir records + module functions, Clojure, F#. Keeps composition's decoupling but drops mutable-object-with-methods entirely. Actors often build on top of this — an Elixir GenServer is a module of pure functions wrapped in a mailbox.

What's after actors? Unresolved. Candidates:

CSP/channels (Go, occam) — the channel is the primitive, not the mailbox. Senders and receivers rendezvous; different failure modes than actor mailboxes.
Effect systems (Koka, some Scala, Haskell mtl) — orthogonal to the arc; controls what side effects code can perform.
Capability-based models (Pony, E, some object-capability systems) — code can only do what it holds capabilities for. Scales the encapsulation logic from "what can code see" to "what is code authorized to do."

No dominant successor. If your arc is about tightening the encapsulation boundary, effect systems and capabilities are the natural continuation — they tighten what code is allowed to do, not just what it's allowed to see. Pony is worth looking at specifically because it combines actors with a capability-based type system that statically proves data-race freedom — which is roughly what "after actors" would look like if the trend continues.

Real question. Let me think about the actual tradeoffs rather than default to the Anthropic-product recommendation.

What Claude Code on Mac would give us

Concrete advantages for this project specifically:

Persistent file structure. You'd have /Users/you/corvid/spec/, /Users/you/corvid/validation/, /Users/you/corvid/positioning/ as real directories. Version control (git) is trivial. Grep, find, and shell tools work naturally.
Larger effective context. Claude Code manages its own context differently — it can work with entire codebases rather than reasoning within a single conversation's token budget. For a 3900-line spec plus growing validation docs plus new positioning documents, this matters.
Terminal integration. When we get to Stage 0 bootstrap (objc5→objc translator written in Objective-C), you need a compiler, a runtime, Xcode. Web chat can't touch those; Claude Code can invoke them.
File edits are direct. No str_replace intermediation. Faster iteration on documents.
Multi-file operations. "Update the design charter in premises.md, then propagate the naming change through the spec, then update the validation README" — trivially one command in Claude Code, several separate operations here.

Concrete disadvantages:

Setup cost. You need to install Claude Code, set up authentication, get the project structure onto disk. Not zero.
Different UX. Web chat is a single flowing conversation; Claude Code is more session-based, more command-oriented. Different mental model.
Loses conversation history. Everything we've discussed is in the transcript of this session. Moving to Claude Code means either (a) starting fresh with just the artifacts, or (b) manually seeding context. Some of the design reasoning that lives in our conversation but not in the spec would be lost.
What web chat continues to give us

Advantages:

Continuity. We've built up shared understanding across dozens of exchanges. That's a real asset. Starting Claude Code fresh means either losing it or spending significant tokens re-establishing it.
The transcript is a record. Not just the artifacts — the reasoning for every design decision is captured in what we said to each other. That's valuable for the handoff docs (you can literally quote design conversations into the positioning documents).
You can read on mobile. Positioning documents, especially, benefit from being reviewable on the go. Web chat + literate-programming markdown works well for that. Claude Code assumes you're at the keyboard.

Disadvantages:

Context pressure. This session has been long. Each additional turn costs more tokens as the transcript grows. Big multi-file operations get expensive.
File operations are slower. Everything goes through str_replace, present_files, etc.
Can't invoke tools. When we get to actually building anything runnable, we hit a wall.
The actual decision criterion

The right answer depends on what we're doing next, not on abstract properties.

If the next work is preparing handoff documents — positioning statement, Game of Life scope, ecosystem alignment doc, design charter extraction — that's mostly writing and light editing. Web chat handles this fine. The advantage of continuity outweighs the tooling gap. Mobile-readable literate-programming markdown, which we've been producing well.

If the next work is starting the Game of Life implementation — actual code, actual builds, actual multi-file coordination — Claude Code on Mac is the honest answer. Web chat can't invoke a compiler; Claude Code can.

If the next work is renaming to Corvid across all documents — a mechanical mass edit — Claude Code is faster (one command over the whole tree), but web chat can do it too (Python script through bash_tool).

Given your stated plan — "prepare 1" (positioning docs) — the answer is: stay here for the positioning work, then move to Claude Code when we start building.

Practical recommendation

For the positioning documents (this next session):

Stay in web chat. Write:

Positioning statement (~500 words).
Game of Life scope document (~1000 words with architecture).
Ecosystem alignment doc — how Corvid relates to Swift, ObjC, Foundation, AppKit.
Rename objc5 → Corvid across the spec and validation docs.
Extract the design charter (§1.1-§1.9) as standalone document for handoff.

This is maybe 2-3 focused turns. Well within web chat's comfort zone.

For implementation work (later):

Move to Claude Code on Mac. Set up a git repo. Structure:

corvid/
├── spec/
│   ├── charter.md          (extracted §1.1-§1.9)
│   ├── spec-v0.5.md         (main spec)
│   └── vocabulary.md        (cheatsheet)
├── validation/
│   └── ink-translation/    (existing)
├── positioning/
│   ├── overview.md         (positioning statement)
│   ├── game-of-life.md     (scope doc)
│   └── ecosystem.md        (Apple-ecosystem alignment)
├── bootstrap/
│   ├── translator/         (Stage 0 objc5→objc)
│   └── README.md
└── examples/
    └── game-of-life/        (the actual demo, split by target)
        ├── shared/
        ├── mac/
        ├── ios/
        └── server/

Claude Code + git + Xcode + a Linux box for the server component gives you everything the implementation needs.

One caveat worth naming

Moving to Claude Code loses the conversational quality of this session. We've been in a specific mode — I push back, you push back, we work through design tensions together. That's harder to reproduce in Claude Code, which is more command-oriented. If the next work has substantial design questions (not just execution), consider staying in web chat longer than "just the positioning docs."

For positioning specifically, the design questions are largely settled. So web chat for positioning, then Claude Code for building, is the honest split.

What I'd do right now

Given confirmed direction — Corvid, Apple ecosystem, Game of Life example, positioning docs first — the next concrete step is:

Rename first, then write positioning docs. Rename is cheap and clarifies naming across everything else we produce. Positioning docs benefit from being written with the final name in mind rather than swapping objc5→Corvid at the end.

Rough plan for this session (or next):

Verify no naming conflicts for "Corvid" as a programming language name.
Rename across spec + validation docs. Mechanical sweep.
Extract design charter (§1.1-§1.9 as standalone).
Write positioning statement.
Write Game of Life scope document.
Write ecosystem alignment doc.

Estimating 2 focused sessions to do all this cleanly.

Ready to start on the naming verification and rename? Or do you want to think about naming further before committing? Corvid was a suggestion, not a requirement — happy to consider other candidates if you have preferences.

please prepare a zip bundle with a readme as a handoff . i will copy paste the entire transcript as well, but please make sure the zip bundle is as complete as possible.

Orchestrated comprehensive rename to Corvid and structured complete handoff bundle
Orchestrated comprehensive rename to Corvid and structured complete handoff bundle

Going. Substantial task — let me be efficient.

Set up handoff staging directory
Set up handoff staging directory

Now the objc5 → Corvid rename across all files:

Orchestrating documentation assembly and bundling completion
Orchestrating documentation assembly and bundling completion

Now extract §1 as the design charter, and write the positioning documents:

Extract §1 design charter
Extract §1 design charter

Now write the three positioning documents:

Created 3 files
Created 3 files

Now write the top-level handoff README:

Handoff README as the entry point
Handoff README as the entry point

Now zip it up:

Ran 2 commands, read a file
Ran 2 commands, read a file
Bundle prepared

corvid-handoff.zip — 144KB, 22 files, 408KB uncompressed.

Structure
corvid-handoff/
├── README.md                    # entry point, reading order, decided vs undecided
├── working-notes.md             # decision log
├── spec/
│   ├── charter.md              # §1.1-§1.9 extracted as standalone premises
│   └── spec-v0.5.md            # full 3900+ line specification
├── positioning/
│   ├── overview.md             # audience, competitive frame, technical bets
│   ├── game-of-life.md         # reference implementation scope
│   └── ecosystem.md            # Swift/ObjC/Foundation/AppKit interop
├── validation/
│   ├── README.md
│   ├── 00-friction-log.md      # 38 friction points cataloged
│   ├── 01-05 files             # Ink translation exercise (parity + idiomatic)
└── historical/
    └── sections-design-analysis.md
What was done in this session
Renamed objc5 → Corvid across every file. .oc5 extension became .cor. About 80 replacement patterns applied to catch every phrase variation (objc5's, objc5 code, in objc5, etc.). Historical name preserved in a note at the top of the spec.
Extracted the design charter — §1.1-§1.9 as spec/charter.md, with a brief framing header explaining that everything downstream descends from these premises.
Wrote three positioning documents:
overview.md (~1800 words) — what Corvid is, who it's for, competitive framing, technical bets, timeline.
game-of-life.md (~2100 words) — the reference implementation scope. Concrete about what each tier tests and what the code structure looks like.
ecosystem.md (~2300 words) — how Corvid relates to Swift, ObjC, Foundation, AppKit, SwiftUI. The Xcode question, the App Store question, the dependency management question.
Wrote a comprehensive handoff README with:
One-paragraph summary.
File tree with explanations.
Reading order for 15min / 1hr / half day / full day.
Decided vs undecided list.
What the empirical validation tested vs. didn't.
Specific next steps for evaluators, implementers, and designers.
Naming history.
What I want to flag honestly

One thing I punted on: I didn't write a vocabulary cheatsheet even though I suggested one earlier. The spec has enough vocabulary that this would be genuinely useful for handoff. If it turns out to matter, it's cheap to add later — probably 200-300 lines listing every keyword, primitive, alias, and section with a one-line description each.

The rename might have missed edge cases. I applied ~80 replacement patterns to catch variations, and the final grep showed no remaining objc5 mentions in the bundle. But mixed-case variants or unusual phrasings might have slipped through. Worth a quick review pass by whoever receives this.

The transcript will carry more context than the bundle. As you noted, you'll copy-paste the transcript too. That's important — the bundle is self-contained enough to work with, but the reasoning behind specific decisions lives in the conversation. The handoff README explicitly notes this.

No compiler exists. Everything here is design. If the next agent is expecting to run Corvid code, they'll be disappointed. The README is clear on this but worth reiterating.

One suggestion for whoever picks this up next

If they're building in Claude Code on Mac, the first thing to do is git init this directory and commit the current state as v0.5.2. Then every subsequent change has provenance. Design work benefits enormously from version control even when there's no code to compile yet.

Ready for handoff.