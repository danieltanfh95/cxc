(ns cxc.parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.parse :as p]))

(defn- first-node [source]
  (first (p/parse source)))

(defn- node-kind [n] (:node n))

(deftest module-decl
  (is (= {:node :module :name "com.example.foo"}
         (first-node "module com.example.foo\n"))))

(deftest from-import
  (let [n (first-node "from com.cxc.stdlib import Clock, Random\n")]
    (is (= :from-import (node-kind n)))
    (is (= "com.cxc.stdlib" (:from n)))
    (is (= ["Clock" "Random"] (:names n)))))

(deftest empty-record
  (testing "record with just data: section, one field"
    (let [n (first-node "record V:\n    data:\n        x: Float\n")]
      (is (= :record (node-kind n)))
      (is (= "V" (:name n)))
      (let [ds (first (:sections n))]
        (is (= :data-section (node-kind ds)))
        (is (= 1 (count (:fields ds))))
        (is (= "x" (-> ds :fields first :name)))))))

(deftest record-with-methods
  (testing "Vector2 — data + methods + a return expression"
    (let [src "record Vector2:
    data:
        x: Float
        y: Float

    methods:
        function plus(self, other: Vector2) -> Vector2:
            return Vector2(x: self.x + other.x, y: self.y + other.y)
"
          n (first-node src)]
      (is (= :record (node-kind n)))
      (is (= 2 (count (:sections n))))
      (let [ms (second (:sections n))]
        (is (= :methods-section (node-kind ms)))
        (let [f (first (:methods ms))]
          (is (= :function (node-kind f)))
          (is (= "plus" (:name f)))
          (is (= 2 (count (:params f))))
          (is (= "Vector2" (get-in f [:return-type :name])))
          (let [ret (first (:stmts (:body f)))]
            (is (= :return (node-kind ret)))
            (is (= :call (node-kind (:expr ret))))))))))

(deftest expr-binops-and-dots
  (testing "self.x + other.x — dot access + binary op"
    (let [n   (first-node "record R:\n    methods:\n        function f(self) -> Float:\n            return self.x + other.x\n")
          ret (-> n :sections first :methods first :body :stmts first)
          expr (:expr ret)]
      (is (= :binop (:node expr)))
      (is (= "+" (:op expr)))
      (is (= :dot (get-in expr [:left :node])))
      (is (= :self (get-in expr [:left :target :node])))
      (is (= "x" (get-in expr [:left :field]))))))

(deftest expr-nested-call-with-named-args
  (let [n   (first-node "record R:\n    methods:\n        function f(self) -> V:\n            return V(x: 1, y: 2)\n")
        ret (-> n :sections first :methods first :body :stmts first)
        call (:expr ret)]
    (is (= :call (:node call)))
    (is (= 2 (count (:args call))))
    (is (= :named (-> call :args first :kind)))
    (is (= "x" (-> call :args first :name)))))

(deftest field-with-default
  (let [n  (first-node "record R:\n    data:\n        n: Int = 42\n")
        f  (-> n :sections first :fields first)]
    (is (= "n" (:name f)))
    (is (= "Int" (get-in f [:type :name])))
    (is (= 42 (get-in f [:default :value])))))

(deftest def-atom-with-use-data-invariants
  (let [n (first-node "def A:
    use:
        cap: Capability

    data:
        x: Int = 0

    invariants:
        x >= 0

    obs :< x

    -> idle then: x => 0
")]
    (is (= :def-atom (:node n)))
    (is (= "A" (:name n)))
    (let [kinds (mapv :node (:body n))]
      (is (= [:use-section :data-section :invariants-section :observable :transition] kinds)))))

(deftest transition-receive-with-when-and-body
  (let [n (first-node "def A:
    alive <: attack{amount: Int}
    -> alive
        when: hp > amount
        then: hp => hp - amount
")
        t (-> n :body first)]
    (is (= :transition (:node t)))
    (is (= :phase (get-in t [:source :kind])))
    (is (= "alive" (get-in t [:source :name])))
    (is (= "<:" (get-in t [:receive :glyph])))
    (is (= "attack" (get-in t [:receive :msg :source :name])))
    (is (= "alive" (:target t)))
    (is (= 2 (count (:body t))))
    (is (= :when-clause (:node (first (:body t)))))
    (is (= :then-clause (:node (second (:body t)))))))

(deftest emit-statements
  (let [n (first-node "def A:
    -> alive
        do:
            :> died
            other :> tick(dt: rate)
")
        do-clause (-> n :body first :body first)
        stmts (:stmts do-clause)]
    (is (= :do-clause (:node do-clause)))
    (is (= 2 (count stmts)))
    (is (= :emit (:node (first stmts))))
    (is (nil? (:target (first stmts))))
    (is (= "died" (:msg (first stmts))))
    (is (= :emit (:node (second stmts))))
    (is (= "other" (get-in (second stmts) [:target :name])))
    (is (= "tick" (:msg (second stmts))))))

(deftest unit-suffixed-numbers
  (let [n (first-node "def A:
    data:
        d: Duration = 200ms
        f: Float = 2.5s
")
        [d f] (-> n :body first :fields)]
    (is (= :lit-int-unit (get-in d [:default :node])))
    (is (= 200 (get-in d [:default :value])))
    (is (= "ms" (get-in d [:default :unit])))
    (is (= :lit-float-unit (get-in f [:default :node])))
    (is (= 2.5 (get-in f [:default :value])))
    (is (= "s" (get-in f [:default :unit])))))

(deftest nested-atom-slot
  (let [n (first-node "def Game:
    def paddle: Paddle
    def player: Player(max_hp: 100)
")
        [p1 p2] (:body n)]
    (is (= :nested-atom (:node p1)))
    (is (= "paddle" (:name p1)))
    (is (= "Paddle" (get-in p1 [:type :name])))
    (is (nil? (:ctor-args p1)))
    (is (= :nested-atom (:node p2)))
    (is (= "player" (:name p2)))
    (is (= 1 (count (:ctor-args p2))))))

(deftest game-toys-parses
  (testing "the full game-toys.cxc example parses end-to-end — entry point is `def main`"
    (let [src (slurp "../design/examples/game-toys.cxc")
          nodes (p/parse src)]
      (is (= 10 (count nodes)))
      (is (= [:module :from-import :record
              :def-atom :def-atom :def-atom :def-atom :def-atom :def-atom :def-atom]
             (mapv :node nodes)))
      (is (= ["Vector2"] (mapv :name (filter #(= :record (:node %)) nodes))))
      (is (= ["Paddle" "ActionMachine" "Player" "Enemy" "EnemySpawner" "Game" "main"]
             (mapv :name (filter #(= :def-atom (:node %)) nodes)))))))

(defn- for-call
  "Helper: extract the `xs.for_each(e => body)` :call node from a
   `def main` with a single `-> running` transition whose body's
   first do-clause statement is the for-sugar rewrite."
  [nodes]
  (let [main   (first (filter #(= :def-atom (:node %)) nodes))
        txn    (first (filter #(= :transition (:node %)) (:body main)))
        do-cl  (first (filter #(= :do-clause  (:node %)) (:body txn)))]
    (get-in do-cl [:stmts 0 :expr])))

(deftest for-sugar-inline
  (testing "`for e in xs: <stmt>` desugars at parse time to `xs.for_each(e => <stmt>)`; Step 2 parse-time rewrite only, runtime support arrives at Step 5"
    (let [nodes (p/parse "def main:\n    -> running\n        do:\n            for e in xs: e :> tick\n")
          call  (for-call nodes)]
      (is (= :call (:node call)))
      (is (= :dot (get-in call [:callee :node])))
      (is (= "for_each" (get-in call [:callee :field])))
      (is (= "xs"       (get-in call [:callee :target :name])))
      (let [lambda (get-in call [:args 0 :value])]
        (is (= :lambda (:node lambda)))
        (is (= "e"     (get-in lambda [:params 0 :name])))
        (is (= :emit   (get-in lambda [:body :node])))))))

(deftest for-sugar-block
  (testing "`for e in xs:` newline indented body wraps as `{:node :block :stmts [...]}` inside the lambda; multi-statement bodies preserved"
    (let [nodes (p/parse "def main:\n    -> running\n        do:\n            for e in xs:\n                e :> tick\n                e :> ping\n")
          call  (for-call nodes)
          body  (get-in call [:args 0 :value :body])]
      (is (= :block (:node body)))
      (is (= 2 (count (:stmts body))))
      (is (every? #(= :emit (:node %)) (:stmts body))))))

(deftest extern-section-retired
  (testing "`extern <Name>:` as a top-level section is retired at v1.0 (spec §1.12, §2335). Parser emits a targeted diagnostic pointing at the migration path (prelude `foreign library` blocks + `Extern` capability atom, §1.14) rather than a generic `expected top-level form` error."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`extern` as a top-level section keyword is retired at v1.0"
         (p/parse "extern C_stdio:\n    function puts(s: String) -> Int\n")))))

(deftest state-section-retired-in-def-atom
  (testing "`state:` section in a def-atom body is retired at v1.0 (spec §1.11). Parser emits a targeted migration diagnostic pointing at `data:` instead of a generic fall-through error."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`state:` section is retired at v1.0"
         (p/parse "def main:\n    state:\n        count: Int = 0\n")))))

(deftest state-section-retired-in-record
  (testing "`state:` section in a record body is also retired (records are immutable; use `data:`)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`state:` section is retired at v1.0"
         (p/parse "record V:\n    state:\n        x: Float\n")))))

(deftest chained-comparison-rejects-effectful-middle
  (testing "Audit F27: `a <= b <= c` desugars to `a <= b and b <= c`, which evaluates the middle expression twice. A call in the middle would double-execute (double-send for atom methods, double-alloc for ctors). Parser rejects with a targeted diagnostic pointing at hoisting to `let:`. Pure-value middles (ident, arithmetic, field) remain accepted."
    ;; Middle is a call → reject
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"chained comparison middle expression has side effects"
         (p/parse "record V:
    data:
        x: Int
    methods:
        function mid(self) -> Int:
            return x

def main:
    data:
        v: V = V(x: 5)

    -> running

    running <: check -> self
        when: 0 <= v.mid() <= 10
        then: v => v
")))
    ;; Middle is an ident → OK (structural regression: this is the
    ;; invariant-chained.cxc pattern `-lim <= val <= lim`).
    (is (some? (p/parse "def main:
    data:
        val: Int = 5

    -> running

    running <: bump -> self
        when: -10 <= val <= 10
        then: val => val + 1
")))))

(deftest transition-clause-order-enforced
  (testing "Audit F22: spec §10 — transition sub-blocks must appear in the order `when, let, then, do`. Out-of-order clauses (e.g. `then:` before `let:`) parse-error with a specific diagnostic."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`let:` clause out of order"
         (p/parse "def main:
    data:
        n: Int = 0

    -> running

    running <: bump -> self
        then: n => n + 1
        let:
            k: Int = 2
")))))

(deftest transition-clause-multiplicity-enforced
  (testing "Audit F22: spec §10 — each of when/let/then/do may appear at most once per transition body. Duplicates parse-error with a specific diagnostic."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"duplicate `then:` clause"
         (p/parse "def main:
    data:
        n: Int = 0

    -> running

    running <: bump -> self
        then: n => n + 1
        then: n => n + 2
")))))

(deftest def-method-form-accepted
  (testing "Audit K8: spec-canonical `def name(params) -> Ret:` method form is accepted alongside `function name(params) -> Ret:`. Both produce a `:function` node so downstream lowering is unaffected."
    (let [src "record V:\n    data:\n        x: Int\n    methods:\n        def bumped(self) -> Int:\n            return x\n"
          n   (first-node src)]
      (is (= :record (node-kind n)))
      (let [ms (second (:sections n))]
        (is (= :methods-section (node-kind ms)))
        (is (= 1 (count (:methods ms))))
        (let [m (first (:methods ms))]
          (is (= :function (node-kind m)))
          (is (= "bumped" (:name m)))))))
  (testing "def-form method inside a def-atom body — same parse shape as function-form."
    (let [src "def M:\n    data:\n        n: Int = 0\n    def bumped(self) -> Int:\n        return n\n"
          nodes (p/parse src)
          body  (:body (first nodes))]
      (is (some #(and (= :function (:node %)) (= "bumped" (:name %))) body)))))
