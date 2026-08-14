(ns cxc.prelude
  "Compiler-shipped prelude: records + capability shapes that every
   cxc program can assume exist without importing. Prepended verbatim
   to the user's source text before parsing so a single parse pass
   sees both together and lowering resolves prelude references from
   the same top-level nodes vector.

   Kept minimal — anything here becomes part of the language's stable
   surface for callers. Grows conservatively.

   Naming discipline (Layer 4b provisional): prelude types carry a
   `Cxc` prefix to avoid colliding with Cocoa/CoreFoundation typedefs
   that atom programs pick up via Foundation.h — e.g. bare `Duration`
   clashes with CoreServices' SInt32 typedef of the same name. Layer
   5 will introduce a module system with proper namespacing; at that
   point users write `Duration` and the compiler mangles it under the
   hood.")

(def source
  "Prelude source text spliced ahead of user source. Indentation is
   canonical cxc (4-space blocks); keep flush-left at column 0."
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

")
