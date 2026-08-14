(ns cxc.prelude
  "Compiler-shipped prelude: records + capability shapes that every
   cxc program can assume exist without importing. Prepended verbatim
   to the user's source text before parsing so a single parse pass
   sees both together and lowering resolves prelude references from
   the same top-level nodes vector.

   Prelude atoms are tree-shaken by cxc.lower: only those actually
   referenced from user code are emitted, so shipping capability
   stubs like CxcClock doesn't pull their fanout selectors into
   programs that never subscribe to them.

   Kept minimal — anything here becomes part of the language's stable
   surface for callers. Grows conservatively.

   Naming discipline (Layer 4b provisional): prelude types carry a
   `Cxc` prefix to avoid colliding with Cocoa/CoreFoundation typedefs
   that atom programs pick up via Foundation.h — e.g. bare `Duration`
   clashes with CoreServices' SInt32 typedef of the same name. Layer
   5 will introduce a module system with proper namespacing; at that
   point users write `Duration` and the compiler mangles it under the
   hood.")

(def atom-names
  "Names of def-atom entries in the prelude. Tree-shaking in
   cxc.lower drops any atom in this set that user code doesn't
   reference, so shipping capability stubs (like CxcClock) is free
   for programs that don't use them."
  #{"CxcClock"})

(def source
  "Prelude source text spliced ahead of user source. Indentation is
   canonical cxc (4-space blocks); keep flush-left at column 0.

   Contents:
   - CxcDuration / CxcInstant: value-typed millisecond records
   - CxcClock: capability stub emitting `tick` N times then `done`.
     Not a real periodic timer (would need dispatch_source_t at
     Layer 4c multi-thread); an async self-send loop drives the
     tick count under Layer 4 single-thread. `every(rate)` returning
     a Stream of Instant is Layer 5 (needs Stream-as-value)."
  "record CxcDuration:
    data:
        millis: Int

    methods:
        function to_seconds(self) -> Float:
            return self.millis / 1000.0

record CxcInstant:
    data:
        millis: Int

    methods:
        function plus(self, delta: CxcDuration) -> CxcInstant:
            return CxcInstant(millis: self.millis + delta.millis)

def CxcClock(ticks: Int = 3):
    data:
        remaining: Int = ticks

    -> running
        do: self :> beat

    -> end
        do: :> done

    running <: beat -> running
        when: remaining > 0
        then: remaining => remaining - 1
        do:
            :> tick
            self :> beat

    running <: beat -> end
        when: remaining == 0

")
