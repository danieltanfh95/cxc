(ns cxc.prelude
  "Compiler-shipped prelude: records + capability shapes that every
   cxc program can assume exist without importing. Prepended verbatim
   to the user's source text before parsing so a single parse pass
   sees both together and lowering resolves prelude references from
   the same top-level nodes vector.

   Prelude atoms are tree-shaken by cxc.lower: only those actually
   referenced from user code are emitted, so shipping capability
   stubs like Clock doesn't pull their fanout selectors into
   programs that never subscribe to them.

   Kept minimal — anything here becomes part of the language's stable
   surface for callers. Grows conservatively.

   Naming (Layer 5 minimum). Users write bare `Duration`, `Instant`,
   `Clock`. Post-parse, `rewrite-prelude-names` mangles every
   occurrence to `_Cxc_Duration`, `_Cxc_Instant`, `_Cxc_Clock` so the
   emitted C/ObjC source dodges CoreServices' `Duration` SInt32
   typedef and any other Cocoa/CoreFoundation collision that
   Foundation.h drags in. This replaces Layer 4b's user-visible
   `Cxc` prefix — the mangling is now internal-only. Layer 6's full
   module system supersedes the rename table with proper
   namespace-scoped resolution."
  (:require [clojure.walk :as walk]))

(def rename-table
  "User-visible → mangled internal name. Applied post-parse to every
   `:name`, `:type`, `:outer` string in the AST. Table entries are
   specific enough that a user program that doesn't touch prelude
   types is a no-op; a user program that redefines a prelude name
   (e.g. `def Duration:`) gets silently rewritten too, which is a
   collision the compiler doesn't diagnose in Layer 5 minimum."
  {"Duration"           "_Cxc_Duration"
   "Instant"            "_Cxc_Instant"
   "Clock"              "_Cxc_Clock"
   "Random"             "_Cxc_Random"
   "TestKeyboardInput"  "_Cxc_TestKeyboardInput"
   "Ticker"             "_Cxc_Ticker"
   "Key"                "_Cxc_Key"
   "Position"           "_Cxc_Position"
   ;; Layer 5c Step 8: Stream is the user-visible type for
   ;; method-returned reactive streams. Maps to CxcStream, the
   ;; runtime helper class defined inline in emit's helpers block
   ;; (see cxc.emit/cxc-runtime-helpers). Registered in atom-env at
   ;; lower time as a runtime-provided kind so type-refs resolve
   ;; but no class synthesis runs on it.
   "Stream"             "CxcStream"})

(defn rename-if
  "Rename `s` via `rename-table` if `s` is a string that appears in
   the table; otherwise return `s` unchanged. Non-string inputs pass
   through so callers can apply this uniformly across AST fields
   whose values may be strings, maps, or nil."
  [s]
  (if (and (string? s) (contains? rename-table s))
    (get rename-table s)
    s))

(def atom-names
  "Names of def-atom entries in the prelude, in their POST-RENAME
   (mangled) form. Tree-shaking in cxc.lower compares against these
   after the rewrite pass runs, so entries here match what the
   lowered AST carries — not what the prelude source spelled."
  #{"_Cxc_Clock" "_Cxc_Random" "_Cxc_TestKeyboardInput" "_Cxc_Ticker"})

(def source
  "Prelude source text spliced ahead of user source. Indentation is
   canonical cxc (4-space blocks); keep flush-left at column 0.
   Users write bare names; `rewrite-prelude-names` mangles them
   post-parse.

   Contents:
   - Duration / Instant: value-typed millisecond records
   - Clock: capability stub emitting `tick` N times then `done`.
     Not a real periodic timer (would need dispatch_source_t at
     Layer 4c multi-thread); an async self-send loop drives the
     tick count under Layer 4 single-thread. Real
     `every(rate) -> Stream of Instant` returning a first-class
     Stream value is Layer 5 (needs Stream-as-value)."
  "record Duration:
    data:
        millis: Int

    methods:
        function to_seconds(self) -> Float:
            return self.millis / 1000.0

record Instant:
    data:
        millis: Int

    methods:
        function plus(self, delta: Duration) -> Instant:
            return Instant(millis: self.millis + delta.millis)

record Position:
    data:
        x: Float
        y: Float

    methods:
        function plus(self, other: Position) -> Position:
            return Position(x: self.x + other.x, y: self.y + other.y)

def Random:
    next_u32 :< native_random_u32()

    methods:
        function below(self, n: Int) -> Int:
            return native_random_below(n)

enum Key:
    arrow_up
    arrow_down
    arrow_left
    arrow_right
    space
    escape
    f

def TestKeyboardInput(events: Int = 0):
    data:
        remaining: Int = events

    -> running
        do: self :> beat

    -> end
        do: :> done

    running <: beat -> running
        when: remaining > 0
        then: remaining => remaining - 1
        do:
            :> pressed(key: remaining)
            self :> beat

    running <: beat -> end
        when: remaining == 0

def Ticker(rate_ms: Int, count: Int = 3):
    data:
        remaining: Int = count

    -> running
        do: native_schedule_periodic_beat(self, rate_ms)

    -> end
        do: :> done

    running <: beat -> running
        when: remaining > 0
        then: remaining => remaining - 1
        do: :> tick

    running <: beat -> end
        when: remaining == 0

def Clock(ticks: Int = 3):
    data:
        remaining: Int = ticks

    now :< native_millis_now()

    methods:
        function every(self, rate_ms: Int) -> Stream:
            return native_clock_every(self, rate_ms)

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

(def duration-units
  "Recognized unit suffixes on numeric literals. Value = multiplier
   into `millis` — the canonical unit for `Duration`. Layer 5.4
   scope: time only; other physical dimensions (bytes, distance) are
   spec-committed but wait for a program that needs them.

   Fractional multipliers (e.g. `1.5s` at :float-unit) are handled
   by the same table via double arithmetic before construction."
  {"ms"  1
   "s"   1000
   "min" 60000})

(defn- desugar-unit-literal
  "Rewrite a `:lit-int-unit` or `:lit-float-unit` node into an
   equivalent `Duration(millis: N)` construction if the unit is one
   of the recognized time units. Unrecognized units drop through
   (lower still fails on them, keeping the diagnostic tied to a
   real user surface).

   Post-rename note: this runs INSIDE `rewrite-prelude-names`'s
   walker, which has already mapped `Duration` → `_Cxc_Duration`
   at the type-ref level — but the synthesized ctor callee here is
   a fresh `:ident` node we insert *after* renaming has traversed
   the tree, so we spell the mangled name directly."
  [node]
  (let [unit (:unit node)
        mult (get duration-units unit)]
    (if-not mult
      node
      (let [raw   (:value node)
            millis (if (= :lit-float-unit (:node node))
                     (long (Math/round ^double (double (* raw mult))))
                     (* raw mult))]
        {:node   :call
         :callee {:node :ident :name "_Cxc_Duration"}
         :args   [{:kind  :named
                   :name  "millis"
                   :value {:node :lit-int :value millis}}]}))))

(def ^:private class-member-desugars
  "Static-like accessors on prelude record types. Rewrite
   `<Type>.<member>` (post-rename mangled) to an equivalent value
   construction. Small, closed set — general record-class-members
   are Layer 5.5+ machinery, but a handful of designated static
   constants are enough for the common cases (`Instant.epoch`,
   `Position.origin`)."
  {["_Cxc_Instant" "epoch"]
   {:node   :call
    :callee {:node :ident :name "_Cxc_Instant"}
    :args   [{:kind :named :name "millis"
              :value {:node :lit-int :value 0}}]}
   ["_Cxc_Position" "origin"]
   {:node   :call
    :callee {:node :ident :name "_Cxc_Position"}
    :args   [{:kind :named :name "x" :value {:node :lit-float :value 0.0}}
             {:kind :named :name "y" :value {:node :lit-float :value 0.0}}]}})

(defn- desugar-class-member
  "If `node` is a `:dot` whose target is a bare ident naming a
   prelude type with a known static member, replace it with the
   equivalent constructor call. Otherwise return unchanged."
  [node]
  (if (and (= :dot (:node node))
           (map? (:target node))
           (= :ident (get-in node [:target :node])))
    (or (get class-member-desugars
            [(get-in node [:target :name]) (:field node)])
        node)
    node))

(defn rewrite-prelude-names
  "Post-parse walk that mangles every occurrence of a user-visible
   prelude name to its internal form. Walks the parsed top-nodes
   vector; renames strings at map keys that carry type/identifier
   names (`:name`, `:type`, `:outer`). Other :inner values are
   maps (nested type nodes) and get renamed recursively by
   postwalk's own descent, not by this fn's direct value check.

   Also desugars unit-suffixed numeric literals (`1000ms`, `1.5s`)
   into `_Cxc_Duration(millis: N)` calls at the same walk — same
   post-parse timing, same postwalk mechanism.

   Safe to run over any parsed AST — the rename table only contains
   specific prelude names, so a program that never mentions them is
   an identity transform."
  [nodes]
  (walk/postwalk
    (fn [x]
      (if (map? x)
        (cond
          (contains? #{:lit-int-unit :lit-float-unit} (:node x))
          (desugar-unit-literal x)

          (= :dot (:node x))
          (desugar-class-member x)

          :else
          (cond-> x
            (contains? x :name)  (update :name  rename-if)
            (contains? x :type)  (update :type  rename-if)
            (contains? x :outer) (update :outer rename-if)))
        x))
    nodes))
