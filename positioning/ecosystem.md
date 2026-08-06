# Corvid and the Apple Ecosystem

## Corvid's relationship to existing Apple-ecosystem languages

Corvid targets the same runtime as Objective-C and Swift (libobjc2 on Linux, objc4 on Apple platforms). Classes compile to the same metadata format, dispatch through the same `objc_msgSend`, and participate in the same class registry. Categories added by any of the three languages are visible to the others.

This is a design commitment, not a coincidence. Corvid is not a competitor to Swift or ObjC that happens to share runtime concepts. Corvid is a peer language that intentionally targets the same substrate so mixed-language codebases work without bridge overhead.

## Interop matrix

### Corvid ↔ Objective-C

**File-level coexistence.** `.m` and `.cor` files can live in the same module (§9.4). The Corvid compiler produces LLVM IR that links with clang-produced object files.

**Type mapping:**
- Corvid `class` ↔ ObjC `@interface` class. Both register with the same class table.
- Corvid `struct` ↔ C `struct` (via §6.9). Direct memory-layout compatibility.
- Corvid `enumeration` (simple cases) ↔ ObjC `NS_ENUM`. With associated values, no direct C mapping; Corvid-only.
- Corvid `protocol` ↔ ObjC `@protocol`. Bidirectional conformance possible.
- Corvid `Any` ↔ ObjC `id`. At interop boundary.

**Method calls:**
- Corvid → ObjC: direct dispatch through `objc_msgSend`. No wrapping.
- ObjC → Corvid: works via auto-generated `-Corvid.h` headers (§9.3.3). Corvid classes appear as ObjC classes with method signatures translated.

**Contracts at the boundary:**
- Corvid contracts (requires/ensures) are runtime-enforced. If a Corvid method with `requires x > 0` is called from ObjC with `x == 0`, the runtime check triggers.
- ObjC methods have no contracts; Corvid callers get no contract info.
- The auto-generated headers document Corvid contracts as comments for ObjC readers.

**Effects at the boundary:**
- Corvid `throws E` methods become ObjC methods with an `NSError**` parameter (§9.3.3).
- Corvid `async` methods expose completion-handler-based ObjC signatures.
- ObjC methods can be called from Corvid without effect annotations; they're treated as potentially-throwing per ObjC convention.

### Corvid ↔ Swift

**Not via file-level coexistence.** Swift and Corvid produce different intermediate representations. Interop happens through:
1. The shared runtime: Swift classes and Corvid classes both register with libobjc2.
2. ObjC as a bridge language: Corvid → auto-generated ObjC header → Swift import.

This is the same interop story Swift has with ObjC today, just running through Corvid on one side.

**Type mapping:**
- Corvid `class` ↔ Swift `class` (via ObjC bridge).
- Corvid `struct` ↔ Swift `struct` for POD types; requires bridging for reference-carrying structs.
- Corvid `enumeration` ↔ Swift `enum` for simple cases; associated-value cases require bridging.
- Corvid `protocol` ↔ Swift `@objc protocol` (Swift's non-@objc protocols aren't visible to ObjC dispatch).

**Practical implication for Swift developers:**
Adopting Corvid alongside Swift is possible but similar in structure to adopting ObjC alongside Swift. The bridge exists; specific patterns may need adaptation. Not "just add a file to your project" the way Swift-adjacent code sometimes is.

### Corvid ↔ Foundation

**Direct.** Foundation is an ObjC framework. Corvid imports it via `import objc "Foundation"` and calls its methods natively.

Types that Foundation provides (NSString, NSArray, NSDictionary, NSDate, URLSession, etc.) are usable directly in Corvid code. The type appears as its ObjC class name; Corvid's type inference handles bridging where semantically clean.

**Async wrapping:**
Foundation's completion-handler-based APIs (URLSession, dataTask) auto-wrap to Corvid `async` functions via the interop layer (§9.3.3):

```
# ObjC declaration:
# - (void)dataTaskWithURL:(NSURL *)url completion:(void (^)(NSData *, NSURLResponse *, NSError *))completion;

# Corvid usage:
data = await session.data_task(url: URL(from: "https://..."))  # async, throws NSError
```

### Corvid ↔ AppKit and UIKit

**Direct via ObjC.** Both frameworks are ObjC. Corvid imports them and can subclass NSView, UIViewController, etc.

**Main-actor discipline (§8.9):**
UI callbacks must run on the main thread. Corvid's `@main_actor` marker on class methods ensures they run on the main dispatch queue. Applied to view controllers, delegates, and any class that receives UI callbacks.

```
class ViewController:
    @main_actor
    methods:
        async function refresh(self):
            data = await fetch()
            self.label.text = data.title  # guaranteed main-thread
```

### Corvid ↔ SwiftUI

**Complex.** SwiftUI is Swift-only; its declarative view protocol depends on Swift's type system features (result builders, opaque return types, property wrappers) that don't have direct Corvid equivalents.

**Practical answer for now:**
- Use AppKit/UIKit directly from Corvid. Skip SwiftUI.
- If SwiftUI interop becomes essential, wrap SwiftUI views in AppKit/UIKit host views (the standard SwiftUI-in-AppKit pattern) and drive them from Corvid via bridging classes.

SwiftUI interop is a candidate for a future design pass. Not blocking for v1.0.

## The Xcode question

Xcode doesn't know about Corvid. Options:

**Option A: Corvid outside Xcode.** Command-line compilation. Corvid produces standard Mach-O binaries, dylibs, and framework bundles. Users package into `.app` bundles manually or via Homebrew-style scripts.

**Option B: Xcode plugin.** Post-Stage-1 tooling effort. Register Corvid as a language in Xcode, integrate the LSP server, provide project templates.

**Option C: Own IDE.** Substantial effort; probably wrong. VS Code + LSP is more likely a working IDE story than building our own.

**Recommendation:** Ship Option A for Stage 1. Ship Option B for v1.0 if adoption warrants. Skip Option C.

The LSP server ships as part of the Stage 1 compiler (§4.18.5), so VS Code + Corvid extension is a workable IDE story from Stage 1 forward.

## The App Store question

App Store submissions require:
- Code signing.
- App transport security compliance.
- Info.plist with specific keys.
- Icon assets, launch screens.
- App Store Connect metadata.

None of these are language-level concerns. Corvid produces standard `.app` bundles that go through the same signing and submission flow as ObjC or Swift apps. The build tooling (Stage 1+) must know how to produce compliant bundles; this is engineering work, not design work.

## The dependency management question

For v1.0, Corvid has no package manager (see §7.14). Users manage dependencies via:
- Manifest declaration (`package.cor` with dependency URLs).
- Manual dylib installation.
- Homebrew-style formulas for common libraries.

CocoaPods and SwiftPM integration would come post-v1.0 if adoption warrants. Neither is likely to be first-class — the design bet is that direct URL-based dependency declaration (Deno-style) works well enough for early adopters.

## The migration story for Swift developers

**What Swift developers gain:**
- Simpler concurrency mental model (contracts as coordination primitives; no actor-isolation ceremony).
- Universal queryability (`MyClass.query()` for any class).
- Explicit contracts as language-level features (not `precondition()` calls).
- Two-type pattern instead of mutating-methods-on-value-types.

**What Swift developers give up:**
- SwiftUI (in the short term).
- Codable/JSONEncoder (needs library equivalent in Corvid stdlib).
- Combine (Corvid's Channel section is comparable but not identical).
- Swift's specific approach to generics (Corvid's is more contract-based).

**Learning curve:**
- Syntax is Python-like (indentation-based). Non-trivial adjustment for Swift developers.
- Section mechanism is new. Learning curve comparable to learning result builders in Swift 5.4.
- Contracts are new. Learning curve for effective use is real but bounded.
- Two-type pattern is a habit change. Not a language feature to learn; a habit to develop.

Rough estimate: a proficient Swift developer becomes productive in Corvid in 1-2 weeks. Idiomatic Corvid code takes 2-3 months to write naturally.

## The migration story for ObjC developers

**What ObjC developers gain:**
- Modern syntax (no header/impl split, no square brackets, generic types).
- Type safety without giving up runtime dynamism.
- Contracts as first-class features.
- Structured async instead of GCD/blocks.

**What ObjC developers give up:**
- Header-based interface documentation (Corvid uses `expose` on declarations).
- Direct `id` usage (Corvid has `Any` at interop boundaries only).
- Ability to mix Corvid syntax with ObjC syntax within one file.

**Learning curve:**
- Larger than Swift developers face. ObjC developers must learn the type system, the section mechanism, and modern syntax simultaneously.
- Rough estimate: 3-4 weeks to productivity, 4-6 months to idiomatic.

## The path forward for cross-language codebases

Realistic teams building on Corvid will have mixed codebases:
- Existing Swift and ObjC code they can't rewrite.
- New Corvid code for new features.
- Shared libraries in one language called from others.

Corvid's runtime interop story is designed for this. Adding Corvid to a Swift/ObjC codebase is analogous to adding ObjC to a Swift codebase today: possible, requires some bridge headers, mostly works. The runtime substrate is shared; the source languages coexist.

Teams adopting Corvid should expect:
- 1-2 weeks getting the toolchain integrated with existing build systems.
- Ongoing friction at the Corvid-Swift boundary that's mostly avoidable by keeping Corvid classes ObjC-friendly (auto-derived headers).
- Genuine productivity wins in Corvid-only modules (concurrency, contracts, universal extents).

The goal is not "rewrite everything in Corvid." The goal is "Corvid works alongside what you have."
