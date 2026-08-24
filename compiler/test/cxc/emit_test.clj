(ns cxc.emit-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [cxc.emit :as emit]))

(defn- hello-program-ast []
  {:node     :objc-program
   :includes ["<stdio.h>"]
   :structs  []
   :classes  []
   :main     {:body [{:node :call :fn "printf"
                      :args [{:node :str :value "%s\n"}
                             {:node :str :value "hello, world"}]}
                     {:node :return :value {:node :int :value 0}}]}})

(deftest hello-emit-shape
  (let [s (emit/emit (hello-program-ast))]
    (is (str/includes? s "#import <stdio.h>"))
    (is (str/includes? s "int main(int argc, char *argv[]) {"))
    (is (str/includes? s "printf(\"%s\\n\", \"hello, world\");"))
    (is (str/includes? s "return 0;"))
    (is (str/ends-with? s "}"))))

(deftest c-string-escaping
  (testing "backslash, quotes, newline are C-escaped"
    (let [ast (assoc-in (hello-program-ast)
                        [:main :body 0 :args 1 :value]
                        "line1\nline2\t\"quoted\\slash")
          s   (emit/emit ast)]
      (is (str/includes? s "\"line1\\nline2\\t\\\"quoted\\\\slash\"")))))

(deftest binop-emits-parenthesized
  (testing "binops always emit with parens around the whole expression + operands"
    (let [ast (assoc-in (hello-program-ast) [:main :body 0]
                        {:node :call :fn "printf"
                         :args [{:node :str :value "%d\n"}
                                {:node :binop :op "+"
                                 :left {:node :int :value 3}
                                 :right {:node :binop :op "*"
                                         :left {:node :int :value 4}
                                         :right {:node :int :value 2}}}]})
          s   (emit/emit ast)]
      (is (str/includes? s "(3 + (4 * 2))")))))

(deftest field-access-emits-dot
  (let [ast (assoc-in (hello-program-ast) [:main :body 0]
                      {:node :call :fn "printf"
                       :args [{:node :str :value "%d\n"}
                              {:node :field
                               :target {:node :name :sym "v"}
                               :field  "x"}]})
        s   (emit/emit ast)]
    (is (str/includes? s "v.x"))))

(deftest struct-lit-emits-designated-initializer
  (let [ast (assoc-in (hello-program-ast) [:main :body 0]
                      {:node :call :fn "printf"
                       :args [{:node :str :value "%d\n"}
                              {:node :field
                               :target {:node :struct-lit :type "V"
                                        :fields [{:name "x" :value {:node :int :value 3}}
                                                 {:name "y" :value {:node :int :value 4}}]}
                               :field  "x"}]})
        s   (emit/emit ast)]
    (is (str/includes? s "((V){.x = 3, .y = 4}).x"))))

(deftest struct-typedef-emits
  (let [ast (assoc (hello-program-ast)
                   :structs
                   [{:node :c-struct :name "V"
                     :fields [{:name "x" :c-type "int"}
                              {:name "y" :c-type "int"}]}])
        s   (emit/emit ast)]
    (is (str/includes? s "typedef struct {\n    int x;\n    int y;\n} V;"))))

(deftest objc-alloc-init-emits
  (let [ast (assoc-in (hello-program-ast) [:main :body 0]
                      {:node :call :fn "printf"
                       :args [{:node :str :value "%d\n"}
                              {:node :field
                               :target {:node :objc-alloc-init :class "Counter"}
                               :field  "n"}]})
        s   (emit/emit ast)]
    (is (str/includes? s "[[Counter alloc] init].n"))))

(deftest objc-class-emits
  (let [ast (-> (hello-program-ast)
                (assoc :classes
                       [{:node :objc-class :name "Counter" :superclass "NSObject"
                         :properties [{:name "n" :c-type "int" :attrs ["assign"]}]
                         :init-body [{:node :assign
                                      :lhs  {:node :name :sym "_n"}
                                      :rhs  {:node :int :value 42}}]}]))
        s   (emit/emit ast)]
    (is (str/includes? s "@interface Counter : NSObject"))
    (is (str/includes? s "@property (assign) int n;"))
    (is (str/includes? s "@implementation Counter"))
    (is (str/includes? s "self = [super init];"))
    (is (str/includes? s "_n = 42;"))
    (is (str/includes? s "return self;"))
    (is (str/includes? s "@end"))))

(deftest autoreleasepool-wraps-main-when-flagged
  (let [ast (assoc-in (hello-program-ast) [:main :autorelease?] true)
        s   (emit/emit ast)]
    (is (str/includes? s "@autoreleasepool {"))))

(deftest c-function-emits
  (let [ast (assoc (hello-program-ast) :functions
                   [{:node :c-function :name "V_plus" :return-type "V"
                     :params [{:name "self" :c-type "V"}
                              {:name "other" :c-type "V"}]
                     :body [{:node :return
                             :value {:node :struct-lit :type "V"
                                     :fields [{:name "x" :value {:node :int :value 1}}
                                              {:name "y" :value {:node :int :value 2}}]}}]}])
        s   (emit/emit ast)]
    (is (str/includes? s "V V_plus(V self, V other) {"))
    (is (str/includes? s "return ((V){.x = 1, .y = 2});"))))

;; ---------------------------------------------------------------------------
;; F35 L5 — synchronous end-cascade-child emission.
;; ---------------------------------------------------------------------------

(deftest f35-l5-end-cascade-child-emits-sync-direct-call
  (testing "F35 L5 — `:end-cascade-child` emits a synchronous direct call to the child's `-transitionToEnd`, not `cxc_send_async`. Post-order sync teardown per spec §1.11 line 1283."
    (let [ast {:node :objc-program
               :includes []
               :structs []
               :classes []
               :main {:body [{:node :end-cascade-child :slot-name "helper"}]}}
          s   (emit/emit ast)]
      (is (str/includes? s "if (_helper != nil) { [(id<CXCAtom>)_helper transitionToEnd]; }")
          "sync direct call with nil-guard")
      (is (not (str/includes? s "cxc_send_async(_helper"))
          "no async cascade dispatch"))))

(deftest f35-l6-end-cascade-parent-emits-sync-direct-call
  (testing "F35 L6 — `:end-cascade-parent` emits a synchronous direct call to the parent's `-nilSlot:`, not `cxc_send_async(p, ...)`. Teardown path no longer has any 'cleanup runs later' primitive; L7's spec §1.11 line 1284 auto-nil-visible-before-next-parent-guard guarantee falls out of the sync call."
    (let [ast {:node :objc-program
               :includes []
               :structs []
               :classes []
               :main {:body [{:node :end-cascade-parent}]}}
          s   (emit/emit ast)]
      (is (str/includes? s "[(id<CXCAtom>)p nilSlot:sn];")
          "sync direct call to parent's nilSlot:")
      (is (not (str/includes? s "cxc_send_async(p"))
          "no async parent-notify dispatch")
      (is (str/includes? s "__strong id p = _parent;")
          "parent still snapshotted to a strong local (weak race)"))))

;; ---------------------------------------------------------------------------
;; F33 L2.a — `<Atom>_State` typedef emission (dead-code phase).
;; ---------------------------------------------------------------------------

(deftest f33-l2a-atom-state-typedef-emitted-for-data-fields
  (testing "F33 L2.a — every atom with user `data:` fields gets a companion `typedef struct <Atom>_State { <c-type> _<name>; ... } <Atom>_State;` alongside its @interface. Dead-code phase: nothing reads through it yet, but downstream sub-landings (L2.b snapshot init, L2.c read reroute) need the type to exist. Includes ONLY user data fields — never compiler-injected slots (`_phase`, `_own_queue`, `_parent`, stream subscribers, owned-resources array)."
    (let [ast {:node :objc-program
               :includes []
               :structs []
               :classes [{:node :objc-class
                          :name "Counter"
                          :superclass "NSObject"
                          :phase-constants []
                          :data-fields [{:name "n" :c-type "long long"}
                                        {:name "max" :c-type "long long"}]
                          :properties [{:name "phase" :c-type "NSString *" :attrs []}
                                       {:name "n" :c-type "long long" :attrs []}
                                       {:name "max" :c-type "long long" :attrs []}]
                          :init-params []
                          :init-body []
                          :methods []}]
               :main {:body []}}
          s   (emit/emit ast)]
      (is (str/includes? s "typedef struct {")
          "state-struct typedef opens")
      (is (str/includes? s "long long _n;")
          "field n present with underscore prefix")
      (is (str/includes? s "long long _max;")
          "field max present")
      (is (str/includes? s "} Counter_State;")
          "typedef closes with <Atom>_State name")
      (is (not (str/includes? s "NSString * _phase"))
          "phase (compiler-injected) is NOT in the state struct")
      (is (not (str/includes? s "dispatch_queue_t _own_queue"))
          "own_queue is NOT in the state struct"))))

(deftest f33-l2a-atom-state-typedef-omitted-when-no-data-fields
  (testing "F33 L2.a — an atom with zero user `data:` fields gets no `<Atom>_State` typedef (empty struct would still be legal C but is pointless). Guards against noise in the emitted output for trivial atoms."
    (let [ast {:node :objc-program
               :includes []
               :structs []
               :classes [{:node :objc-class
                          :name "Trivial"
                          :superclass "NSObject"
                          :phase-constants []
                          :data-fields []
                          :properties [{:name "phase" :c-type "NSString *" :attrs []}]
                          :init-params []
                          :init-body []
                          :methods []}]
               :main {:body []}}
          s   (emit/emit ast)]
      (is (not (str/includes? s "Trivial_State"))
          "no state typedef for a data-less atom"))))

(deftest f33-l2a-atom-state-typedef-splices-block-name-inside-carat
  (testing "F33 L2.a — ObjC block-typed data fields (`Block` in cxc source lowers to `void (^)(void)` c-type) need the field name spliced INSIDE the `(^…)`, mirroring `@property`'s handling. Otherwise the struct field would be malformed: `void (^)(void) _cb;` is a C syntax error, `void (^_cb)(void);` is legal."
    (let [ast {:node :objc-program
               :includes []
               :structs []
               :classes [{:node :objc-class
                          :name "CxcMain"
                          :superclass "NSObject"
                          :phase-constants []
                          :data-fields [{:name "cb" :c-type "void (^)(void)"}]
                          :properties [{:name "cb" :c-type "void (^)(void)" :attrs []}]
                          :init-params []
                          :init-body []
                          :methods []}]
               :main {:body []}}
          s   (emit/emit ast)]
      (is (str/includes? s "void (^_cb)(void);")
          "block-typed field: name spliced inside (^…), semicolon at end")
      (is (not (str/includes? s "void (^)(void) _cb"))
          "malformed C shape (name after the closing paren) NOT emitted"))))

;; ---------------------------------------------------------------------------
;; F32 L2 — shutdown drain. Under future concurrent mode (F32 L1 flips
;; atom queues to the global pool), exit(0) can race with pool blocks
;; that a departing atom's `-> end do:` fired via cxc_send_async. The
;; barrier drain fires only when !CXC_SINGLE_MAIN_QUEUE; under Layer 4
;; single-queue mode (default) it's guarded out — the sync-cascade F31/
;; F35 L5-L7 already covers the ordering, and no blocks live on the
;; global pool to drain.
;; ---------------------------------------------------------------------------

(deftest f32-l2-shutdown-drain-emits-guarded-barrier
  (testing "F32 L2 — cxc_shutdown carries a dispatch_barrier_sync on the global concurrent queue, guarded on !CXC_SINGLE_MAIN_QUEUE so it's a no-op under Layer 4 single-queue mode but fires the drain when F32 L1 flips atom queues to global."
    (let [ast {:node :objc-program
               :includes []
               :needs-runtime? true
               :structs []
               :classes []
               :main {:body []}}
          s   (emit/emit ast)]
      (is (str/includes? s "static inline void cxc_shutdown(void) {")
          "cxc_shutdown fn emitted from runtime helpers block")
      (is (str/includes? s "#if !CXC_SINGLE_MAIN_QUEUE")
          "guard opens before the barrier drain")
      (is (str/includes? s "dispatch_barrier_sync(dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0), ^{});")
          "barrier drain on the global concurrent queue"))))

;; ---------------------------------------------------------------------------
;; F32 L3 — runtime `|>` cycle detector. Per-thread NSMutableSet tracks
;; atoms currently in the sync-send path on this thread. Re-entry (a
;; nested `|> A.foo()` while A is already awaiting) panics with a
;; targeted message before the underlying dispatch_sync would deadlock
;; (concurrent mode) or the recursion would blow the stack (single-
;; queue mode). Fires on BOTH modes for uniform behavior; complements
;; the F31 L3 compile-time cycle warning.
;; ---------------------------------------------------------------------------

(deftest f32-l3-runtime-cycle-detector-emits-in-cxc-wait-for-processing
  (testing "F32 L3 — cxc_wait_for_processing carries the per-thread sending-set push/pop with re-entry panic. Detector is unguarded (fires under both single-queue and concurrent modes)."
    (let [ast {:node :objc-program
               :includes []
               :needs-runtime? true
               :structs []
               :classes []
               :main {:body []}}
          s   (emit/emit ast)]
      (is (str/includes? s "static inline void cxc_wait_for_processing(id target, void (^block)(void))")
          "cxc_wait_for_processing signature unchanged")
      (is (str/includes? s "NSMutableSet *__sending = __td[@\"_cxc_sending_to\"];")
          "per-thread sending set stored in -threadDictionary under `_cxc_sending_to` key")
      (is (str/includes? s "if ([__sending containsObject:__key])")
          "cycle check via containsObject on NSValue-wrapped target pointer")
      (is (str/includes? s "cxc: `|>` cycle:")
          "panic diagnostic mentions `|>` cycle")
      (is (str/includes? s "[__sending addObject:__key];")
          "push on entry")
      (is (str/includes? s "[__sending removeObject:__key];")
          "pop on exit (@finally)")
      (is (str/includes? s "@try {")
          "push/pop wrapped in try/finally so panic paths still pop"))))

(deftest f32-l3-runtime-cycle-detector-preserves-mode-dispatch
  (testing "F32 L3 — the underlying single-queue vs concurrent dispatch is preserved inside the try block. Detector wraps but doesn't replace the mode-dispatch."
    (let [ast {:node :objc-program
               :includes []
               :needs-runtime? true
               :structs []
               :classes []
               :main {:body []}}
          s   (emit/emit ast)]
      (is (str/includes? s "#if CXC_SINGLE_MAIN_QUEUE")
          "single-queue guard still emitted")
      (is (str/includes? s "block();")
          "single-queue direct block invocation preserved")
      (is (str/includes? s "dispatch_sync([(id<CXCAtom>)target own_queue], block);")
          "concurrent-mode dispatch_sync preserved"))))

;; ---------------------------------------------------------------------------
;; F32 L1 — atom own_queue target selection. Under CXC_SINGLE_MAIN_QUEUE=1
;; (Layer 4 default) every atom's queue targets main — deterministic single-
;; threaded behavior, unchanged from Layer 4. Under CXC_SINGLE_MAIN_QUEUE=0
;; (F32 concurrent mode) non-main atoms target the global concurrent pool at
;; QOS_CLASS_DEFAULT; the CxcMain atom stays main-targeted so dispatch_main()
;; still drives its blocks. Landing is forward-compatible; concurrent target
;; selection activates only when the runtime flag is redefined.
;; ---------------------------------------------------------------------------

(deftest f32-l1-queue-init-emits-guarded-target-selection
  (testing "F32 L1 — :queue-init emits the queue creation + preprocessor-guarded target selection. Single-queue branch keeps the existing main retarget; concurrent branch runtime-checks the class name and picks main-queue for CxcMain vs global concurrent pool for everyone else."
    (let [ast {:node :objc-program
               :includes []
               :structs []
               :classes [{:node :objc-class
                          :name "Counter"
                          :superclass "NSObject"
                          :phase-constants []
                          :data-fields []
                          :properties [{:name "own_queue" :c-type "dispatch_queue_t" :attrs ["strong"]}]
                          :init-params []
                          :init-body [{:node :queue-init :ivar "_own_queue"}]
                          :methods []}]
               :main {:body []}}
          s   (emit/emit ast)]
      (is (str/includes? s "_own_queue = dispatch_queue_create([cxc_qlabel UTF8String], DISPATCH_QUEUE_SERIAL);")
          "queue is always created serial (spec §1.11 per-atom serial invariant)")
      (is (str/includes? s "#if CXC_SINGLE_MAIN_QUEUE")
          "single-queue guard opens the target-selection block")
      (is (str/includes? s "dispatch_set_target_queue(_own_queue, dispatch_get_main_queue());")
          "single-queue branch retargets to main — Layer 4 behavior preserved")
      (is (str/includes? s "#else")
          "concurrent branch present")
      (is (str/includes? s "[NSStringFromClass([self class]) isEqualToString:@\"CxcMain\"]")
          "concurrent branch runtime-checks whether this atom is the main atom")
      (is (str/includes? s "dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0)")
          "non-main atoms target the global concurrent pool at default QoS")
      (is (str/includes? s "dispatch_set_target_queue(_own_queue, __cxc_qtarget);")
          "concurrent branch retargets to the runtime-selected queue")
      (is (str/includes? s "#endif")
          "guard closes"))))

(deftest f15-l2c-iii-a-named-tuple-typedef-block-placement
  (testing "F15 L2.c.iii.a — the emit renders the program's `:named-tuple-typedefs` string between structs and function prototypes. Programs that don't set the field (today: all programs — L2.c.iii.b wires it up later) leave the block absent, so the emit is byte-identical to pre-L2.c.iii.a. Programs that DO set it see the typedef block inserted at the correct C-level position (after structs, before functions that might reference them)."
    (let [ast {:node :objc-program
               :includes []
               :structs []
               :classes []
               :named-tuple-typedefs (str "typedef struct {\n"
                                          "    int64_t x;\n"
                                          "    int64_t y;\n"
                                          "} _Cxc_NT_x_Int_y_Int;")
               :main {:body []}}
          s   (emit/emit ast)]
      (is (str/includes? s "typedef struct {\n    int64_t x;\n    int64_t y;\n} _Cxc_NT_x_Int_y_Int;")
          "the typedef block appears in the emit"))))

(deftest f15-l2c-iii-a-absent-field-emits-no-block
  (testing "F15 L2.c.iii.a — when the program AST doesn't set `:named-tuple-typedefs`, the emit produces zero named-tuple output. Backward-compat guarantee: existing programs (which never set the field) see byte-identical emit."
    (let [ast {:node :objc-program
               :includes []
               :structs []
               :classes []
               :main {:body []}}
          s   (emit/emit ast)]
      (is (not (str/includes? s "_Cxc_NT_"))
          "no named-tuple typedef surfaces when the field is absent"))))

(deftest f32-l4a-cxcatomstate-wrapper-emits-guarded
  (testing "F32 L4.a — the `_CxcAtomState` ARC wrapper class is emitted in the runtime helpers under a `#if !CXC_SINGLE_MAIN_QUEUE` guard. Under Layer 4 default the whole block preprocessor-guards out (zero cost); under concurrent mode the class is available for L4.b's per-atom `_state` ivar to reference. Wrapper carries a raw `void *state` pointer and a `size_t size`; `-dealloc` frees the underlying malloc'd struct so ARC handles cross-reader lifetime (old wrapper releases when no in-flight reader retains). Dead code today: nothing references `_CxcAtomState` yet."
    (let [ast {:node :objc-program
               :needs-runtime? true
               :includes []
               :structs []
               :classes []
               :main {:body []}}
          s   (emit/emit ast)
          ;; Scope the block to the wrapper class alone — the trailing
          ;; L4.b.α helpers (`_cxc_state_load` / `_cxc_state_commit`)
          ;; extend the same guarded region; L4.b's own test covers
          ;; that half.
          i   (str/index-of s "@interface _CxcAtomState")
          end (str/index-of s "@end" (str/index-of s "@implementation _CxcAtomState" i))
          block (subs s i (+ end (count "@end")))]
      (is (str/includes? s "#if !CXC_SINGLE_MAIN_QUEUE")
          "wrapper region opens under !CXC_SINGLE_MAIN_QUEUE guard")
      (is (str/includes? block "@property (nonatomic, assign) void *state;")
          "wrapper carries the raw state pointer")
      (is (str/includes? block "@property (nonatomic, assign) size_t size;")
          "wrapper carries the struct size (for L4.c commit memcpy)")
      (is (str/includes? block "- (void)dealloc {")
          "dealloc is implemented so ARC frees the malloc'd buffer")
      (is (str/includes? block "if (_state) { free(_state); _state = NULL; }")
          "dealloc frees underlying buffer and nils the pointer"))))

(deftest f32-l4d-beta-beta-cross-atom-field-read-routes-through-state
  (testing "F32 L4.d.β.β — a `:field` node carrying `:cross-atom-cls-name` emits guarded C: under `!CXC_SINGLE_MAIN_QUEUE` it routes through `_cxc_atom_state_ptr` for an acquire-ordered atomic load + pointer projection; under the default preprocessor flag it stays on the ObjC property access path (byte-identical to pre-L4.d.β.β). L4.d.β.α's initial-state-commit guarantees `_state` is populated on the first read."
    (let [node {:node :field
                :target {:node :name :sym "counter"}
                :field "n"
                :cross-atom-cls-name "Counter"}
          s (#'cxc.emit/emit-expr node)]
      (is (str/includes? s "#if !CXC_SINGLE_MAIN_QUEUE")
          "guarded region opens")
      (is (str/includes? s "((Counter_State *)_cxc_atom_state_ptr(&counter->_state))->_n")
          "under concurrent mode routes through _cxc_atom_state_ptr")
      (is (str/includes? s "#else\ncounter.n")
          "under default flag stays on ObjC property access path")
      (is (str/includes? s "#endif")
          "guarded region closes"))))

(deftest f32-l4d-beta-beta-self-field-read-stays-on-ivar-path
  (testing "F32 L4.d.β.β — `self.field` reads (source `:self` node) do NOT get the `:cross-atom-cls-name` marker; they stay on the ObjC property access path even at emit time. Rationale: F33 L2 snapshot discipline handles intra-transition consistency for self, and self writes go directly to ivars during commit."
    (let [node {:node :field
                :target {:node :name :sym "self"}
                :field "n"}
          s (#'cxc.emit/emit-expr node)]
      (is (= "self.n" s)
          "self-field access emits as plain ObjC property syntax")
      (is (not (str/includes? s "CXC_SINGLE_MAIN_QUEUE"))
          "no guarded routing for self-reads"))))

(deftest f32-l4d-beta-alpha-initial-state-commit-emits-guarded
  (testing "F32 L4.d.β.α — `:initial-state-commit` emits a `!CXC_SINGLE_MAIN_QUEUE`-guarded compound-literal + `_cxc_state_commit` to seed the concurrent-mode `_state` slot from freshly-populated ivars at the end of `cxc_stateInit`. Under the default preprocessor flag the whole block guards out (byte-identical modulo the `#if …#endif` markers). Under concurrent mode this seed guarantees the first cross-atom reader sees a populated `_state` — L4.d.β.β's guarded read path depends on this."
    (let [node {:node :initial-state-commit
                :class-name "Counter"
                :data-fields [{:name "n"} {:name "phase"}]}
          s (#'cxc.emit/emit-stmt node)]
      (is (str/includes? s "#if !CXC_SINGLE_MAIN_QUEUE")
          "commit is preprocessor-guarded to concurrent mode")
      (is (str/includes? s "Counter_State __cxc_init = { ._n = _n, ._phase = _phase };")
          "compound literal populates every data-field from its ivar")
      (is (str/includes? s "_cxc_state_commit(&_state, &__cxc_init, sizeof(Counter_State));")
          "commit references the atom's `<Cls>_State` typedef")
      (is (str/includes? s "#endif")
          "guard closes"))))

(deftest f32-l4d-beta-alpha-initial-state-commit-uses-class-name-not-atom-name
  (testing "F32 L4.d.β.α + L4.c fix — the state-struct typedef name is the ObjC class name (`CxcMain` for `def main`), not the raw atom name. Both `:initial-state-commit` and `:commit-next-state` prefer `:class-name` on the node, falling back to `:atom-name` for older shapes. Verifies the fix for `sizeof(main_State)` bug that would surface only under concurrent mode."
    (let [initial-node {:node :initial-state-commit
                        :atom-name "main"
                        :class-name "CxcMain"
                        :data-fields [{:name "console"}]}
          commit-node  {:node :commit-next-state
                        :atom-name "main"
                        :class-name "CxcMain"
                        :data-fields [{:name "console"}]}]
      (is (str/includes? (#'cxc.emit/emit-stmt initial-node)
                         "CxcMain_State __cxc_init")
          "initial-state-commit uses class name for main atom")
      (is (str/includes? (#'cxc.emit/emit-stmt initial-node)
                         "sizeof(CxcMain_State)")
          "initial-state-commit sizes against class-name state struct")
      (is (str/includes? (#'cxc.emit/emit-stmt commit-node)
                         "sizeof(CxcMain_State)")
          "commit-next-state sizes against class-name state struct too"))))

(deftest f32-l4d-alpha-atom-state-ptr-helper-emits-guarded
  (testing "F32 L4.d.α — `_cxc_atom_state_ptr(_Atomic id *slot)` runtime helper is emitted inside the same `!CXC_SINGLE_MAIN_QUEUE` region as the L4.a wrapper + L4.b.α helpers. It projects the ARC wrapper returned by `_cxc_state_load` down to the underlying `<Atom>_State *` buffer with a nil-safe guard: pre-first-commit reads return NULL rather than dereferencing a nil-messaged wrapper. Callers cast the `void *` result to the atom's own `<Atom>_State *`. Dead code today — L4.d.β wires cross-atom `.field` reads to route through this helper."
    (let [ast {:node :objc-program
               :needs-runtime? true
               :includes []
               :structs []
               :classes []
               :main {:body []}}
          s   (emit/emit ast)]
      (is (str/includes? s "static inline void *_cxc_atom_state_ptr(_Atomic id *slot)")
          "helper is declared with the expected signature")
      (is (str/includes? s "_CxcAtomState *w = _cxc_state_load(slot);")
          "delegates to _cxc_state_load for the atomic-load step")
      (is (str/includes? s "return w ? w.state : NULL;")
          "nil-safe projection so pre-first-commit reads yield NULL")
      ;; Guard check — helper sits between the L4.b.α helpers and the
      ;; matching #endif (same guarded region).
      (let [guard-start (str/index-of s "#if !CXC_SINGLE_MAIN_QUEUE\n#include <stdatomic.h>")
            helper-idx  (str/index-of s "_cxc_atom_state_ptr" guard-start)
            endif-idx   (str/index-of s "#endif" helper-idx)]
        (is (some? guard-start))
        (is (< guard-start helper-idx endif-idx)
            "helper sits between the opening guard and its #endif")))))

(deftest f32-l4e-beta-commit-next-state-partitions-ivar-and-state-commit
  (testing "F32 L4.e.β terminal shape — `:commit-next-state` emits per-field ivar writes and the `_cxc_state_commit` call as MUTUALLY-EXCLUSIVE branches on `CXC_SINGLE_MAIN_QUEUE` (was dual-write under L4.c). Under the default preprocessor flag (`CXC_SINGLE_MAIN_QUEUE=1`) only the per-field ivar writes fire — byte-identical to F33 L3.b modulo the `#if …#endif` markers. Under concurrent mode (`CXC_SINGLE_MAIN_QUEUE=0`) only `_cxc_state_commit(&_state, &__cxc_next, sizeof(<Cls>_State))` fires; the ivars go stale but no reader touches them (L4.d.β.β + L4.e.β.1/.2.α/.3 flipped every read path to route via `_state` snapshots). Slot-setter fields (subscription lifecycle) are excluded upstream by the lower site's `field-needs-slot-setter?` filter."
    (let [commit-node {:node :commit-next-state
                       :atom-name "Counter"
                       :data-fields [{:name "n"}]}
          s (#'cxc.emit/emit-stmt commit-node)]
      (is (str/includes? s "#if CXC_SINGLE_MAIN_QUEUE\n_n = __cxc_next._n;\n#else\n")
          "per-field ivar assignment guards under CXC_SINGLE_MAIN_QUEUE (single-queue-only path)")
      (is (str/includes? s "#else\n_cxc_state_commit(&_state, &__cxc_next, sizeof(Counter_State));\n#endif")
          "state-commit fires in the #else branch under concurrent mode with the atom's state-struct size")
      (is (< (str/index-of s "_n = __cxc_next._n;")
             (str/index-of s "_cxc_state_commit"))
          "ivar assignments precede the state-commit textually (guard branches are mutually exclusive at preprocess time)")
      (is (not (str/includes? s "#if !CXC_SINGLE_MAIN_QUEUE\n_cxc_state_commit"))
          "L4.c dual-write shape (state-commit under !CXC_SINGLE_MAIN_QUEUE AFTER unguarded ivar writes) is retired"))))

(deftest f32-l4b-beta-atom-class-emits-guarded-state-ivar
  (testing "F32 L4.b.β — every class emitted through `emit-interface` carries a `_Atomic id _state` ivar in an ivars block guarded on `!CXC_SINGLE_MAIN_QUEUE`. Under the default preprocessor flag the block resolves to `{ }` (empty ivars — identical shape to pre-L4.b.β classes, since ObjC auto-synthesizes ivars for @properties). Under concurrent mode, the ivar is present so L4.c can `_cxc_state_commit(&_state, ...)` and L4.d can `_cxc_state_load(&_state)`. The block is dead code today — no reader or writer references it."
    (let [ast {:node :objc-program
               :needs-runtime? true
               :includes []
               :structs []
               :classes [{:name "Foo"
                          :superclass "NSObject <CXCAtom>"
                          :properties []
                          :init-params []
                          :methods []}]
               :main {:body []}}
          s   (emit/emit ast)
          i   (str/index-of s "@interface Foo")
          e   (str/index-of s "@end" i)
          block (subs s i e)]
      (is (str/includes? block "{\n#if !CXC_SINGLE_MAIN_QUEUE\n    _Atomic id _state;\n#endif\n}")
          "ivars block declares _state under the concurrent-mode guard")
      (is (str/includes? block "@interface Foo : NSObject <CXCAtom>\n{")
          "the ivars block appears immediately after the class declaration"))))

(deftest f32-l4b-alpha-state-load-commit-helpers-emit-guarded
  (testing "F32 L4.b.α — `_cxc_state_load` and `_cxc_state_commit` runtime helpers ship in the same `!CXC_SINGLE_MAIN_QUEUE` region as the L4.a wrapper. Load does an acquire-ordered atomic load; commit wraps a fresh copy of the caller-owned bag (memcpy'd into a malloc'd buffer so ARC's dealloc reclaims it) and release-ordered atomic-exchanges it into the slot, so the previous wrapper's refcount drops when no reader retains. `stdatomic.h` pulls in under the same guard so default builds don't need the C11 atomic header. Dead code today — L4.c wires the commit and L4.d wires the load."
    (let [ast {:node :objc-program
               :needs-runtime? true
               :includes []
               :structs []
               :classes []
               :main {:body []}}
          s   (emit/emit ast)]
      (is (str/includes? s "#include <stdatomic.h>")
          "stdatomic.h is pulled in for the atomic primitives")
      (is (str/includes? s "static inline _CxcAtomState *_cxc_state_load(_Atomic id *slot)")
          "state-load helper is declared with the expected signature")
      (is (str/includes? s "atomic_load_explicit(")
          "load uses acquire-ordered atomic_load_explicit")
      (is (str/includes? s "memory_order_acquire")
          "load is acquire-ordered")
      (is (str/includes? s "static inline void _cxc_state_commit(_Atomic id *slot, const void *src, size_t size)")
          "state-commit helper is declared with the expected signature")
      (is (str/includes? s "memcpy(buf, src, size);")
          "commit memcpy's the caller-owned bag into a heap buffer")
      (is (str/includes? s "atomic_exchange_explicit(")
          "commit atomically swaps in the fresh wrapper and returns the previous")
      (is (str/includes? s "memory_order_release")
          "commit is release-ordered")
      (is (str/includes? s "__bridge_transfer")
          "the exchanged-out previous wrapper is transferred into ARC for release")
      ;; Guard scope check: helpers appear between #if !CXC_SINGLE_MAIN_QUEUE
      ;; and its matching #endif — verified by proximity to _CxcAtomState
      ;; (which the L4.a test already confirms is guard-scoped).
      (let [guard-start (str/index-of s "#if !CXC_SINGLE_MAIN_QUEUE\n#include <stdatomic.h>")
            load-idx    (str/index-of s "_cxc_state_load" guard-start)
            commit-idx  (str/index-of s "_cxc_state_commit" guard-start)
            endif-idx   (str/index-of s "#endif" commit-idx)]
        (is (some? guard-start)
            "the guarded region opens with the stdatomic.h include")
        (is (< guard-start load-idx endif-idx)
            "the load helper sits between the opening guard and its #endif")
        (is (< guard-start commit-idx endif-idx)
            "the commit helper sits between the opening guard and its #endif")))))

(deftest f32-l5-cxcstream-init-emits-guarded-main-target
  (testing "F32 L5 — CxcStream's -init retargets its own_queue to main only under CXC_SINGLE_MAIN_QUEUE. Under concurrent mode the stream inherits libdispatch's default target (default-QoS global pool) so fan-out doesn't bottleneck through main. Queue stays SERIAL either way; subscriber delivery via cxc_send_async still respects each subscriber's own_queue."
    (let [ast {:node :objc-program
               :needs-runtime? true
               :includes []
               :structs []
               :classes []
               :main {:body []}}
          s   (emit/emit ast)
          i   (str/index-of s "@implementation CxcStream")
          end (str/index-of s "@end" i)
          impl (subs s i end)]
      (is (str/includes? impl "_own_queue = dispatch_queue_create(\"cxc.stream\", DISPATCH_QUEUE_SERIAL);")
          "stream queue is always created serial")
      (is (str/includes? impl "#if CXC_SINGLE_MAIN_QUEUE")
          "main-target retarget is guarded")
      (is (str/includes? impl "dispatch_set_target_queue(_own_queue, dispatch_get_main_queue());")
          "single-queue branch keeps Layer 4 main-target behavior")
      (is (str/includes? impl "#endif")
          "guard closes"))))
