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
