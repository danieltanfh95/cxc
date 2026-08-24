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
