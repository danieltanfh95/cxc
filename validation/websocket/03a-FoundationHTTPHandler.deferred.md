# FoundationHTTPHandler.swift — deferred from parity pass

**Status:** not translated. Documented instead.

**Rationale.** The other three files (Framer, FrameCollector, HTTPHandler) exercise state machines, contracts, byte-level work, and pure-Swift Foundation types. FoundationHTTPHandler.swift is different: it's a thin adapter that wraps Core Foundation's `CFHTTPMessage*` C API through bridging (`as CFString`, `as CFURL`, `.takeRetainedValue()`, etc.). A full parity translation would primarily surface CF-bridging friction, most of which is one-shot and repetitive at each callsite.

The Ink exercise handled Reader.swift the same way — inferred from usage rather than fully translated — for a comparable reason (Reader wasn't the main object of study).

## Friction categories this file would add if translated

Distinct from anything the other three files surfaced. Each is a real spec gap for Corvid; enumerating them here lets the idiomatic pass or a follow-up exercise pick them up without needing to re-read the source.

### F1. Conditional compilation

```swift
#if os(watchOS)
public typealias FoundationHTTPHandler = StringHTTPHandler
#else
public class FoundationHTTPHandler: HTTPHandler { ... }
#endif
```

- **Corvid spec:** no `#if`, no `#else`, no build-target predicates shown. §9 ObjC interop and §7 modules don't cover conditional compilation. Reference card gives nothing.
- **Real gap.** Multi-target support (Apple TV / watchOS / macOS / iOS) is a first-class Apple-ecosystem concern; positioning/game-of-life.md commits to it. If Corvid can't gate declarations by target, per-target code has to live in per-target modules with build-system-level selection.
- **Related open question:** is the answer "no `#if`, use module-per-target" (like Rust's `#[cfg]`-adjacent but heavier), or "build-tool-scope conditional inclusion at the source-file level," or does the language need a first-class construct?

### F2. Type aliases

```swift
public typealias FoundationHTTPHandler = StringHTTPHandler
```

- **Corvid spec:** no `typealias` keyword shown. §4 sections might cover this as an alias primitive but the reference card doesn't confirm.
- **Frequency:** common in Foundation-interop code for platform-specific type substitution and for name shortening.
- **Estimated fix:** small — probably a one-line grammar addition.

### F3. Core Foundation bridging via `as` casts

```swift
request.httpMethod! as CFString
request.url! as CFURL
data as CFData
cfHeaders.takeRetainedValue() as NSDictionary
value as? String
```

- **Corvid spec:** no `as` cast operator, no `as?` conditional cast operator shown. Reference card mentions "id → Any at boundary" with switch-based type narrowing (§9), which is the Corvid-idiomatic answer for `as?`. For `as` (unconditional cast), spec is silent.
- **Real gap.** CF↔Foundation bridging is a common shape in any real Apple codebase. If Corvid says "use switch for narrowing," that's an ergonomic hit at every CF-bridging site (of which there are many even in this small file).
- **Related:** `as?` for conditional downcast; `as!` for force downcast. Neither shown.

### F4. Core Foundation memory management

```swift
CFHTTPMessageCreateRequest(...).takeRetainedValue()
CFHTTPMessageCopyAllHeaderFields(response)  // returns Unmanaged<CFDictionary>?
cfHeaders.takeRetainedValue() as NSDictionary
```

- Swift uses `Unmanaged<T>` for CF types created with `Create`/`Copy` (retained) vs. `Get` (unretained). `.takeRetainedValue()` transfers ownership to ARC; `.takeUnretainedValue()` reads without transfer.
- **Corvid spec:** ObjC interop is mentioned in §9; Core Foundation is not called out separately. If CF types are surfaced identically to ObjC (as class handles under libobjc2), the retain-transfer semantics need to be spec'd — is it automatic on function signature? Explicit `retained`/`unretained` at the call site? Unspecified.
- **Frequency:** every CF call in an Apple codebase.

### F5. Direct C-function invocation

```swift
CFHTTPMessageCreateRequest(kCFAllocatorDefault, ..., kCFHTTPVersion1_1)
CFHTTPMessageAppendBytes(response, pointer, data.count)
CFHTTPMessageIsHeaderComplete(response)
```

- These are C functions in the CoreFoundation framework. Swift imports them directly with the C ABI.
- **Corvid spec:** §9.1 mentions `import objc "..."`; C-header imports are hinted (`import c "sys/socket.h"` in reference card) but not detailed. Whether the CF namespace (which is C at the ABI level) comes in via `import c "CoreFoundation.h"` or piggybacks on `import objc "Foundation"` is unclear.
- **Frequency:** most Apple-native code that touches the frameworks below AppKit/UIKit uses CF directly.

### F6. Byte-array constants with ASCII coding

```swift
let endBytes = [UInt8(ascii: "\r"), UInt8(ascii: "\n"), UInt8(ascii: "\r"), UInt8(ascii: "\n")]
```

- `UInt8(ascii: character)` is a Swift stdlib constructor that returns the character's ASCII code as a byte, guaranteed only for ASCII-range strings.
- **Corvid spec:** no shown syntax for character-literal-to-byte conversion. `[Byte]` construction from literal characters is a common shape for parser/protocol code.

### F7. Compound-assignment increment

```swift
k += 1
```

- **Corvid spec:** compound assignment operators (`+=`, `-=`, `*=`, `/=`, `%=`) not shown. Already flagged as friction #W-64 (bitwise compound-assigns); the arithmetic variants are the same family. Not repeating the count in the consolidated friction log.

### F8. Data.subdata slicing

```swift
data.subdata(in: 0..<offset)
```

- Foundation-specific: `Data.subdata(in:)` returns a copy of the byte range. Related to #W-52 (`Data.advanced(by:)`) and #W-61 (array slicing), but distinct: `subdata` copies, `advanced` returns a view.
- **Corvid spec:** the Bytes primitive (§3.1) has no shown slicing or copying API.

## Estimated additional friction if fully translated

Rough count:
- F1 (`#if`): 1 gap, high impact
- F2 (`typealias`): 1 gap, small
- F3 (`as`/`as?`/`as!` casts): 3 gaps, high frequency
- F4 (CF memory management): 1 gap, high frequency
- F5 (C-function import): 1 gap, needs §9 expansion
- F6 (ASCII byte literals): 1 gap, small
- F7 (compound assign, arithmetic): merged with #W-64
- F8 (Data slicing methods): 2 gaps, mid frequency

**Net new friction points:** ~10 categories, most of them one-per-family (encountered many times each in the source). Would not meaningfully shift the friction-log density picture but would substantially expand the "CF/interop story" category, which the current parity pass barely touches.

## Recommendation

If a future exercise wants to close the CF-interop story specifically, translating this file (or a smaller CF-heavy piece) is the right target. For the current parity pass, leaving it deferred keeps the friction log focused on the load-bearing categories from the other three files.
