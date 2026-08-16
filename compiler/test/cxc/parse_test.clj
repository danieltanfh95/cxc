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

(deftest f5-unit-suffix-retired
  (testing "F5: numeric literals with unit suffix (`200ms`, `2.5s`) lex-error per spec §2.7.1"
    (let [ex (try
               (first-node "def A:\n    data:\n        d: Duration = 200ms\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected lex-error")
      (is (= :lex-unit-suffix-retired (:kind (ex-data ex))))
      (is (= "ms" (:suffix (ex-data ex))))
      (is (re-find #"unit suffixes are not part of v1" (.getMessage ex)))
      (is (re-find #"Duration\(millis: 200\)" (.getMessage ex))))
    (let [ex (try
               (first-node "def A:\n    data:\n        f: Float = 2.5s\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected lex-error for float form")
      (is (= :lex-unit-suffix-retired (:kind (ex-data ex))))
      (is (= "s" (:suffix (ex-data ex)))))))

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

(deftest chained-comparison-desugars-to-chained-cmp
  (testing "F27 semantic once-only: `a <= b <= c` becomes a `:chained-cmp` node carrying flat operands + ops vectors. Emit renders it as a GNU statement-expression that binds each interior operand once (via `__typeof__`) so an effectful middle like a call fires exactly once at runtime."
    ;; Middle is an ident — desugars to :chained-cmp with 3 operands.
    (let [tree (p/parse "def main:
    data:
        val: Int = 5

    -> running

    running <: bump -> self
        when: -10 <= val <= 10
        then: val => val + 1
")
          when-expr (some (fn [n]
                            (some (fn [t]
                                    (some (fn [c] (when (= :when-clause (:node c))
                                                    (:expr c)))
                                          (:body t)))
                                  (filter #(= :transition (:node %)) (:body n))))
                          tree)]
      (is (= :chained-cmp (:node when-expr)))
      (is (= 3 (count (:operands when-expr))))
      (is (= ["<=" "<="] (:ops when-expr))))
    ;; Middle is a call — accepted (semantic once-only makes the
    ;; former double-eval concern moot).
    (is (some? (p/parse "record V:
    data:
        x: Int
    methods:
        function mid(self) -> Int:
            return self.x

def main:
    data:
        v: V = V(x: 5)

    -> running

    running <: check -> self
        when: 0 <= v.mid() <= 10
        then: v => v
")))
    ;; 4-operand chain: -10 <= a <= b <= 10 → :chained-cmp with
    ;; 4 operands + 3 ops (fold-into on the postwalk pass).
    (let [tree (p/parse "def main:
    data:
        a: Int = 0
        b: Int = 5

    -> running

    running <: check -> self
        when: -10 <= a <= b <= 10
        then: a => a
")
          when-expr (some (fn [n]
                            (some (fn [t]
                                    (some (fn [c] (when (= :when-clause (:node c))
                                                    (:expr c)))
                                          (:body t)))
                                  (filter #(= :transition (:node %)) (:body n))))
                          tree)]
      (is (= :chained-cmp (:node when-expr)))
      (is (= 4 (count (:operands when-expr))))
      (is (= ["<=" "<=" "<="] (:ops when-expr))))))

(deftest transition-clause-order-enforced
  (testing "Audit F22: spec §10 — transition sub-blocks must appear in the order `when, let, then, do`. Out-of-order clauses (e.g. `then:` before `let:`) parse-error with a specific diagnostic. F23-form let: binding used here."
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
            k: 2
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

(deftest k8-function-keyword-emits-deprecation-warning
  (testing "K8 — `function` method opener is retired at v1.0 (spec §1201, §2330). Parser still accepts it during the grace period but emits a per-occurrence deprecation warning to *err* naming file line/col so users can migrate. Non-fatal."
    (let [sw (java.io.StringWriter.)]
      (binding [*err* sw]
        (p/parse "record V:\n    data:\n        x: Int\n    methods:\n        function bumped(self) -> Int:\n            return x\n"))
      (let [stderr (str sw)]
        (is (re-find #"`function` method opener is deprecated" stderr))
        (is (re-find #"spec §1201" stderr))
        (is (re-find #"use `def` instead" stderr))))))

(deftest k8-def-method-emits-no-deprecation-warning
  (testing "K8 — `def` method form (spec-canonical) fires no deprecation warning."
    (let [sw (java.io.StringWriter.)]
      (binding [*err* sw]
        (p/parse "record V:\n    data:\n        x: Int\n    methods:\n        def bumped(self) -> Int:\n            return x\n"))
      (is (= "" (str sw))
          "def form is clean — no K8 warning"))))

(deftest subscript-postfix-parses
  (testing "F19 / §3.9 — `expr[i]` parses as a :subscript node (index is any expression)"
    ;; Wrap in a return statement inside a record method so the expression
    ;; is in an expression position the parser reaches.
    (let [src "record R:\n    data:\n        xs: List of Int\n    methods:\n        def head(self) -> Int:\n            return xs[0]\n"
          rec (first-node src)
          ms  (second (:sections rec))
          m   (first (:methods ms))
          ret (first (:stmts (:body m)))]
      (is (= :return (:node ret)))
      (is (= :subscript (get-in ret [:expr :node])))
      (is (= "xs" (get-in ret [:expr :target :name])))
      (is (= :lit-int (get-in ret [:expr :index :node])))
      (is (= 0 (get-in ret [:expr :index :value]))))))

(deftest subscript-chains-with-other-postfix
  (testing "F19 — subscript composes with `.field` and `(args)` in any order"
    (let [src "record R:\n    data:\n        xs: List of Int\n    methods:\n        def z(self) -> Int:\n            return xs[0].bar(1)\n"
          rec (first-node src)
          m   (first (:methods (second (:sections rec))))
          ret (first (:stmts (:body m)))
          call (:expr ret)]
      (is (= :call (:node call)))
      (is (= :dot (get-in call [:callee :node])))
      (is (= "bar" (get-in call [:callee :field])))
      (is (= :subscript (get-in call [:callee :target :node]))))))

(deftest f23-let-clause-uses-name-colon-expr-spec-form
  (testing "F23 / spec §1.11 — `let: k: expr` (bare, no type annotation, no `=`); binding shape is {:name :expr}"
    (let [src "def main:\n    data:\n        n: Int = 0\n\n    -> running\n\n    running <: bump -> self\n        let:\n            k: n + 1\n        then: n => k\n"
          nodes (p/parse src)
          body  (:body (first nodes))
          txn   (some #(when (and (= :transition (:node %))
                                  (get-in % [:receive :glyph]))
                         %) body)
          let-c (first (filter #(= :let-clause (:node %)) (:body txn)))
          b     (first (:bindings let-c))]
      (is (= "k" (:name b)))
      (is (= :binop (get-in b [:expr :node]))
          "bare expression parses; no :type wrapper on the binding"))))

(deftest f23-let-clause-legacy-type-form-rejected
  (testing "F23 — legacy `let: k: Int = 2` form (with type annotation + `=`) rejects with a targeted migration diagnostic pointing at the spec form."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`let:` binding uses `name: expression`"
         (p/parse "def main:
    data:
        n: Int = 0

    -> running

    running <: bump -> self
        let:
            k: Int = 2
        then: n => k
")))))

(deftest handler-decl-parses-as-distinct-node
  (testing "F14 / §1.11 — `-> handler(args):` inside a def-atom body parses as a :handler-decl node distinct from the wildcard-source transition (`-> target-phase`)."
    (let [src "def A:\n    data:\n        n: Int = 0\n\n    -> running\n\n    -> tick(msg: Int):\n        return n\n"
          atom-node (first-node src)
          hd (first (filter #(= :handler-decl (:node %)) (:body atom-node)))]
      (is (= :def-atom (:node atom-node)))
      (is (some? hd) "handler-decl AST node present")
      (is (= "tick" (:name hd)))
      (is (= 1 (count (:params hd))))
      (is (= "msg" (get-in hd [:params 0 :name])))
      (is (= "Int" (get-in hd [:params 0 :type :name])))
      ;; and the ordinary transition still parses next to it
      (is (some #(= :transition (:node %)) (:body atom-node))
          "wildcard transition `-> running` still parses as :transition"))))

(deftest handler-decl-zero-args-parses
  (testing "F14 — `-> tick():` with empty param list parses (explicit parens disambiguate from `-> target-phase`)"
    (let [src "def A:\n    data:\n        n: Int = 0\n\n    -> running\n\n    -> tick():\n        return n\n"
          atom-node (first-node src)
          hd (first (filter #(= :handler-decl (:node %)) (:body atom-node)))]
      (is (some? hd))
      (is (= "tick" (:name hd)))
      (is (empty? (:params hd))))))

(deftest f15-l1-named-tuple-type-at-param-position
  (testing "F15 Landing 1 — `(x: Float, y: Float)` at param position parses as :type-named-tuple with declaration-order fields."
    (let [src "record R:\n    data:\n        p: (x: Float, y: Float)\n"
          rec (first-node src)
          fld (-> rec :sections first :fields first)
          ty  (:type fld)]
      (is (= :type-named-tuple (:node ty)))
      (is (= [{:name "x" :type {:node :type-ref :name "Float"}}
              {:name "y" :type {:node :type-ref :name "Float"}}]
             (:fields ty))))))

(deftest f15-l1-anonymous-tuple-type
  (testing "F15 Landing 1 — `(Int, String)` at type position parses as :type-tuple."
    (let [src "record R:\n    data:\n        pair: (Int, String)\n"
          rec (first-node src)
          ty  (-> rec :sections first :fields first :type)]
      (is (= :type-tuple (:node ty)))
      (is (= 2 (count (:types ty)))))))

(deftest f15-l1-single-paren-groups-not-tuple
  (testing "F15 Landing 1 — a single-element paren group `(T)` parses as T, not a 1-tuple. Preserves the parens-as-grouping intuition."
    (let [src "record R:\n    data:\n        n: (Int)\n"
          rec (first-node src)
          ty  (-> rec :sections first :fields first :type)]
      (is (= :type-ref (:node ty)))
      (is (= "Int" (:name ty))))))

(deftest f15-l1-nullable-named-tuple
  (testing "F15 Landing 1 — `(x: Int, y: Int)?` — nullable suffix wraps the whole tuple type."
    (let [src "record R:\n    data:\n        config: (host: String, port: UInt16)?\n"
          rec (first-node src)
          ty  (-> rec :sections first :fields first :type)]
      (is (= :type-nullable (:node ty)))
      (is (= :type-named-tuple (get-in ty [:inner :node]))))))

(deftest f9-l1-class-with-explicit-kind
  (testing "F9 Landing 1 — `class Foo:` with `metadata: kind: regular` produces `:class :kind :regular`. Body-form kind matches sugar-less class keyword."
    (let [n (first-node "class Foo:\n    metadata:\n        kind: regular\n")]
      (is (= :class (:node n)))
      (is (= "Foo" (:name n)))
      (is (= :regular (:kind n)))
      (is (empty? (:conforms n)))
      (is (empty? (:body n))
          "metadata: section is folded into :kind, not kept in :body"))))

(deftest f9-l1-protocol-sugar
  (testing "F9 Landing 1 — `protocol P:` sugar desugars to `:class :kind :protocol`. Sugar keyword sets kind; no explicit metadata needed. (Uses `methods:` body — `data:` on protocol is rejected at fold, see f9-l2-data-on-protocol-rejected.)"
    (let [n (first-node "protocol P:\n    methods:\n        function f() -> Int:\n            return 1\n")]
      (is (= :class (:node n)))
      (is (= "P" (:name n)))
      (is (= :protocol (:kind n))))))

(deftest f9-l1-header-parens-conformance
  (testing "F9 Landing 1 — `class Foo(P1, P2):` populates `:conforms` in declaration order."
    (let [n (first-node "class Foo(P1, P2):\n    metadata:\n        kind: regular\n")]
      (is (= :class (:node n)))
      (is (= [{:node :type-ref :name "P1"}
              {:node :type-ref :name "P2"}]
             (:conforms n))))))

(deftest f9-l1-body-form-conforms
  (testing "F9 Landing 1 — body-form `conforms: P` populates `:conforms`; multiple `conforms:` lines legal per §3.4.5 rule 5."
    (let [n (first-node "class Foo:\n    metadata:\n        kind: regular\n    conforms: P1\n    conforms: P2\n")]
      (is (= [{:node :type-ref :name "P1"}
              {:node :type-ref :name "P2"}]
             (:conforms n))))))

(deftest f9-l1-header-and-body-conformances-additive
  (testing "F9 Landing 1 — header parens conformances and body-form entries stack additively (§3.4.5 rule 5)."
    (let [n (first-node "class Foo(P1):\n    metadata:\n        kind: regular\n    conforms: P2\n")]
      (is (= [{:node :type-ref :name "P1"}
              {:node :type-ref :name "P2"}]
             (:conforms n))))))

(deftest f9-l1-sugar-vs-metadata-mismatch-rejected
  (testing "F9 Landing 1 — sugar keyword (`protocol`) contradicting `metadata: kind:` (`enum`) is a hard parse error to prevent lying-about-kind."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"sugar keyword `protocol`.*contradicts.*enum"
         (first-node "protocol Foo:\n    metadata:\n        kind: enum\n")))))

(deftest f9-l1-unknown-metadata-key-fail-nyi
  (testing "F9 Landing 1 — non-`kind:` metadata entries (thread, qos, pool, custom keys) fail-nyi with a pointer at F10."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`metadata:` key `pool`.*F10"
         (first-node "class Foo:\n    metadata:\n        pool: main\n")))))

(deftest f9-l1-unknown-kind-value-rejected
  (testing "F9 Landing 1 — `metadata: kind:` value must be one of the five spec-legal kinds (§3.3)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unknown `metadata: kind:` value `interface`"
         (first-node "class Foo:\n    metadata:\n        kind: interface\n")))))

(deftest f9-l1-sealed-protocol-retired
  (testing "F9 Landing 1 — `sealed protocol Foo:` is a spec-retired form (§3.4.1); diagnostic points at `enum` for closed sum types."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`sealed protocol` is retired at v1.0.*§3\.4\.1"
         (first-node "sealed protocol Foo:\n    data:\n        x: Int\n")))))

(deftest f9-l1-class-body-accepts-data-methods-invariants
  (testing "F9 Landing 1 — class body accepts data:, methods:, invariants:, use:, deprecated: sections (same as records/atoms)."
    (let [n (first-node (str "class Foo:\n"
                             "    metadata:\n"
                             "        kind: regular\n"
                             "    data:\n"
                             "        x: Int\n"
                             "    methods:\n"
                             "        function get(self) -> Int:\n"
                             "            return x\n"))]
      (is (= :class (:node n)))
      (is (= 2 (count (:body n)))
          "data + methods sections in body; metadata folded out"))))

(deftest f9-l1-empty-conformance-parens-rejected
  (testing "F9 Landing 1 — `class Foo():` is a hard error; omit the parens if no protocols conform."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"empty conformance list"
         (first-node "class Foo():\n    metadata:\n        kind: regular\n")))))

(deftest f9-l1-record-sugar-still-produces-record-node
  (testing "F9 Landing 1 — backward compat: existing `record Foo:` sugar continues to produce a `:record` node (not routed through parse-class). Existing lower path unchanged."
    (let [n (first-node "record R:\n    data:\n        x: Int\n")]
      (is (= :record (:node n))
          "record sugar keeps its dedicated parser + AST kind for backward compat"))))

;; ---------------------------------------------------------------------------
;; F9 Landing 2 — parse `accepts:` (section-form and per-entry sugar);
;; validate kind-vs-section constraints in fold-class-body.
;; ---------------------------------------------------------------------------

(deftest f9-l2-accepts-section-parses
  (testing "F9 Landing 2 — `accepts:` block on a protocol collects each signature into `:accepts` on the class node. Params follow the standard `name: Type` convention (§2.5)."
    (let [n (first-node
             (str "protocol P:\n"
                  "    accepts:\n"
                  "        foo(x: Int) -> Int\n"
                  "        bar(s: String) -> Bool\n"))]
      (is (= :class    (:node n)))
      (is (= :protocol (:kind n)))
      (is (= 2 (count (:accepts n))))
      (is (= "foo" (:name (first  (:accepts n)))))
      (is (= "bar" (:name (second (:accepts n))))))))

(deftest f9-l2-accepts-per-entry-sugar
  (testing "F9 Landing 2 — per-entry sugar `accepts foo(x: Int) -> Int` at class body level folds into `:accepts` alongside section-form entries."
    (let [n (first-node
             (str "protocol P:\n"
                  "    accepts foo(x: Int) -> Int\n"
                  "    accepts:\n"
                  "        bar(s: String) -> Bool\n"))]
      (is (= 2 (count (:accepts n))))
      (is (= #{"foo" "bar"} (into #{} (map :name (:accepts n))))))))

(deftest f9-l2-accepts-rejected-on-non-protocol
  (testing "F9 Landing 2 — `accepts:` on `kind: regular` fails at fold with a pointer at §3.4.5 (only protocols declare method surface)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`accepts:` is only valid on `kind: protocol`"
         (first-node
          (str "class Foo:\n"
               "    metadata:\n"
               "        kind: regular\n"
               "    accepts:\n"
               "        foo(x: Int) -> Int\n"))))))

(deftest f9-l2-data-on-protocol-rejected
  (testing "F9 Landing 2 — `data:` on `kind: protocol` fails at fold: protocols have no state per §3.4.5."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`data:` is not permitted on `kind: protocol`"
         (first-node "protocol P:\n    data:\n        x: Int\n")))))

(deftest f9-l2-accepts-contract-clauses-fail-nyi
  (testing "F9 Landing 2 — `accepts:` entries reject continuation-indented `requires`/`ensures`/effect annotations, with a pointer at F49 (contract inheritance)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"contract clauses.*not yet implemented|F49"
         (first-node
          (str "protocol P:\n"
               "    accepts:\n"
               "        foo(x: Int) -> Int\n"
               "            requires: true\n"))))))

;; ---------------------------------------------------------------------------
;; F9 Landing 3 — `extend class Foo:` parsing + strict body dispatch;
;; `replace` method modifier; target-resolution deferred to lower.
;; ---------------------------------------------------------------------------

(deftest f9-l3-extend-class-with-methods
  (testing "F9 Landing 3 — `extend class Foo:` at top level parses into an `:extend-class` node carrying the target name and body sections."
    (let [n (first-node
             (str "extend class Foo:\n"
                  "    methods:\n"
                  "        function bar(self) -> Int:\n"
                  "            return 1\n"))]
      (is (= :extend-class (:node n)))
      (is (= "Foo" (:target n)))
      (is (empty? (:conforms n)))
      (let [msec (first (:body n))]
        (is (= :methods-section (:node msec)))
        (is (= "bar" (:name (first (:methods msec)))))
        (is (nil? (:replace? (first (:methods msec)))))))))

(deftest f9-l3-extend-class-with-replace-modifier
  (testing "F9 Landing 3 — `replace function ...` inside `extend class` body sets `:replace?` on the method entry. `replace` is now reserved; the modifier is consumed by shared parse-methods-section."
    (let [n (first-node
             (str "extend class Foo:\n"
                  "    methods:\n"
                  "        replace function bar(self) -> Int:\n"
                  "            return 2\n"))
          m (first (:methods (first (:body n))))]
      (is (= "bar" (:name m)))
      (is (true? (:replace? m))))))

(deftest f9-l3-extend-class-with-header-conforms
  (testing "F9 Landing 3 — `extend class Foo(NewProtocol):` populates `:conforms` from header parens."
    (let [n (first-node
             (str "extend class Foo(NewProtocol):\n"
                  "    methods:\n"
                  "        function bar(self) -> Int:\n"
                  "            return 3\n"))]
      (is (= [{:node :type-ref :name "NewProtocol"}]
             (:conforms n))))))

(deftest f9-l3-extend-class-with-body-conforms
  (testing "F9 Landing 3 — body-form `conforms: P` inside `extend class` extracted into `:conforms` and dropped from :body."
    (let [n (first-node
             (str "extend class Foo:\n"
                  "    conforms: NewProtocol\n"
                  "    methods:\n"
                  "        function bar(self) -> Int:\n"
                  "            return 4\n"))]
      (is (= [{:node :type-ref :name "NewProtocol"}]
             (:conforms n)))
      (is (= [:methods-section] (mapv :node (:body n)))))))

(deftest f9-l3-extend-class-with-invariants
  (testing "F9 Landing 3 — `invariants:` section legal inside `extend class` (extensions may add invariants per §3.3.2)."
    (let [n (first-node
             (str "extend class Foo:\n"
                  "    invariants:\n"
                  "        x > 0\n"))]
      (is (= :invariants-section (:node (first (:body n))))))))

(deftest f9-l3-extend-class-rejects-data
  (testing "F9 Landing 3 — `data:` inside `extend class` fails at parse per §3.3.2 (extensions cannot add state)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`data:` is not permitted inside `extend class"
         (first-node
          (str "extend class Foo:\n"
               "    data:\n"
               "        x: Int\n"))))))

(deftest f9-l3-extend-class-rejects-state
  (testing "F9 Landing 3 — `state:` inside `extend class` fails at parse. `state:` is retired at v1.0 AND extensions cannot add fields either way."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`state:` section is retired"
         (first-node
          (str "extend class Foo:\n"
               "    state:\n"
               "        x: Int\n"))))))

(deftest f9-l3-extend-class-rejects-metadata
  (testing "F9 Landing 3 — `metadata:` inside `extend class` fails at parse — kind/threading/QoS are set at the original definition."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`metadata:` is not permitted inside `extend class"
         (first-node
          (str "extend class Foo:\n"
               "    metadata:\n"
               "        kind: enum\n"))))))

(deftest f9-l3-extend-class-rejects-use
  (testing "F9 Landing 3 — `use:` inside `extend class` fails at parse (extensions cannot add capabilities)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`use:` is not permitted inside `extend class"
         (first-node
          (str "extend class Foo:\n"
               "    use:\n"
               "        clock: Clock\n"))))))

(deftest f9-l3-extend-rejects-non-class-target
  (testing "F9 Landing 3 — `extend enum Foo:` / `extend protocol Foo:` etc. fail at parse with a §3.3.2 pointer — `extend class` is the only extension form."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"expected `class` after `extend`"
         (first-node
          (str "extend enum Foo:\n"
               "    methods:\n"
               "        function bar(self) -> Int:\n"
               "            return 1\n"))))))

(deftest f9-l3-replace-on-plain-class-rejected
  (testing "F9 Landing 3 — `replace` modifier on a method inside a plain (non-extension) class body is rejected at fold with a §3.3.2 rule-4 pointer. `replace` requires a target to shadow, which only exists in an `extend class` context."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`replace` modifier on method.*only meaningful inside an `extend class`"
         (first-node
          (str "class Foo:\n"
               "    metadata:\n"
               "        kind: regular\n"
               "    methods:\n"
               "        replace function bar(self) -> Int:\n"
               "            return 1\n"))))))
