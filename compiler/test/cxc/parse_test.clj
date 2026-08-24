(ns cxc.parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.parse :as p]))

(defn- first-node [source]
  (first (p/parse source)))

(defn- node-kind [n] (:node n))

(deftest module-decl-retired
  (testing "F73 (2026-08-20) — the `module <name>` header is retired. Module identity is the file path relative to the source root; the resolver walks the tree. Parser rejects with a migration diagnostic instead of producing a `:module` node."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`module <name>` declaration is retired"
         (p/parse "module com.example.foo\n")))))

(deftest from-import
  (let [n (first-node "from com.cxc.stdlib import Clock, Random\n")]
    (is (= :from-import (node-kind n)))
    (is (= "com.cxc.stdlib" (:from n)))
    ;; F52 L2 — `:names` is a vector of `{:name :as}` maps post-parse.
    ;; Unaliased entries carry `:as nil`; aliased ones carry the alias.
    (is (= [{:name "Clock" :as nil} {:name "Random" :as nil}] (:names n)))))

(deftest f52-l2-from-import-with-alias
  (let [n (first-node
           "from com.cxc.stdlib import Clock as C, Random\n")]
    (is (= :from-import (node-kind n)))
    (is (= [{:name "Clock" :as "C"} {:name "Random" :as nil}]
           (:names n)))))

(deftest f52-l5-import-objc-parses
  (testing "F52 L5 — `import objc \"Foundation/NSString.h\"` parses as `{:node :foreign-import :lang :objc :header \"...\"}`. Grammar + provenance only; header-path existence is F54's job. Disambiguating from regular `import X`: the token after `objc` is a string literal (regular imports have `.` or newline)."
    (let [n (first-node "import objc \"Foundation/NSString.h\"\n")]
      (is (= :foreign-import (node-kind n)))
      (is (= :objc (:lang n)))
      (is (= "Foundation/NSString.h" (:header n))))))

(deftest f52-l5-import-c-parses
  (testing "F52 L5 — `import c \"stdio.h\"` produces `{:node :foreign-import :lang :c :header \"stdio.h\"}`. Same disambiguation as objc — `c` followed by a string literal is the foreign-import head."
    (let [n (first-node "import c \"stdio.h\"\n")]
      (is (= :foreign-import (node-kind n)))
      (is (= :c (:lang n)))
      (is (= "stdio.h" (:header n))))))

(deftest f52-l5-from-foreign-import-parses
  (testing "F52 L5 — `from objc \"Foundation/NSString.h\" import NSString, NSData` produces `{:node :from-foreign-import :lang :objc :header \"...\" :names [{:name \"NSString\" :as nil} ...]}`. Same name-list shape as regular from-import (supports `as` aliasing per L2 semantics); disambiguating token vs regular from-import is the string literal after the lang ident."
    (let [n (first-node "from objc \"Foundation/NSString.h\" import NSString, NSData as Data\n")]
      (is (= :from-foreign-import (node-kind n)))
      (is (= :objc (:lang n)))
      (is (= "Foundation/NSString.h" (:header n)))
      (is (= [{:name "NSString" :as nil} {:name "NSData" :as "Data"}]
             (:names n))))))

(deftest f52-l5-regular-import-still-parses-as-import
  (testing "F52 L5 regression guard — the foreign-import branch must not fire for regular `import foo.bar` (bare ident followed by `.` or newline, not a string literal). Preserves F52 L1's `:import` node shape."
    (let [n1 (first-node "import foo.bar\n")
          n2 (first-node "import foo.bar as fb\n")
          n3 (first-node "from foo import a, b as c\n")]
      (is (= :import (node-kind n1)))
      (is (= "foo.bar" (:from n1)))
      (is (= :import (node-kind n2)))
      (is (= "fb" (:as n2)))
      (is (= :from-import (node-kind n3)))
      (is (= "foo" (:from n3)))
      (is (= [{:name "a" :as nil} {:name "b" :as "c"}] (:names n3))))))

(deftest empty-record
  (testing "record with just data: section, one field"
    (let [n (first-node "def V:\n    x: Float\n")]
      (is (= :record (node-kind n)))
      (is (= "V" (:name n)))
      (let [ds (first (:sections n))]
        (is (= :data-section (node-kind ds)))
        (is (= 1 (count (:fields ds))))
        (is (= "x" (-> ds :fields first :name)))))))

(deftest record-with-observable
  (testing "F67 L4: parametric observable replaces reader methods. Records collapse to fields + observables (Proposal G class-body collapse)."
    (let [src "def Vector2:
    x: Float
    y: Float

    plus(other: Vector2) :< Vector2(x: self.x + other.x, y: self.y + other.y)
"
          n (first-node src)]
      (is (= :record (node-kind n)))
      (let [obs (first (filter #(= :observable (:node %)) (:sections n)))]
        (is (some? obs))
        (is (= "plus" (:name obs)))
        (is (= 1 (count (:params obs)))
            "observable :params exclude implicit self (self is prepended at lower)")
        (is (= :call (node-kind (:expr obs))))))))

(deftest expr-binops-and-dots
  (testing "self.x + other.x — dot access + binary op inside observable body"
    (let [n   (first-node "def R:\n    f() :< self.x + other.x\n")
          obs (first (filter #(= :observable (:node %)) (:sections n)))
          expr (:expr obs)]
      (is (= :binop (:node expr)))
      (is (= "+" (:op expr)))
      (is (= :dot (get-in expr [:left :node])))
      (is (= :self (get-in expr [:left :target :node])))
      (is (= "x" (get-in expr [:left :field]))))))

(deftest expr-nested-call-with-named-args
  (let [n   (first-node "def R:\n    f() :< V(x: 1, y: 2)\n")
        obs (first (filter #(= :observable (:node %)) (:sections n)))
        call (:expr obs)]
    (is (= :call (:node call)))
    (is (= 2 (count (:args call))))
    (is (= :named (-> call :args first :kind)))
    (is (= "x" (-> call :args first :name)))))

(deftest field-with-default
  (let [n  (first-node "def R:\n    n: Int = 42\n")
        f  (-> n :sections first :fields first)]
    (is (= "n" (:name f)))
    (is (= "Int" (get-in f [:type :name])))
    (is (= 42 (get-in f [:default :value])))))

(deftest def-atom-with-use-data-invariants
  (let [n (first-node "def A:
    use:
        cap: Capability

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
    alive <: attack(amount: Int)
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
        then:
            :> died
            other :> tick(dt: rate)
")
        clause (-> n :body first :body first)
        stmts  (:stmts clause)]
    ;; F70 I3 — the migrated form uses `then:`; entries land in `:stmts`.
    (is (= :then-clause (:node clause)))
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
               (first-node "def A:\n    d: Duration = 200ms\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected lex-error")
      (is (= :lex-unit-suffix-retired (:kind (ex-data ex))))
      (is (= "ms" (:suffix (ex-data ex))))
      (is (re-find #"unit suffixes are not part of v1" (.getMessage ex)))
      (is (re-find #"Duration\(millis: 200\)" (.getMessage ex))))
    (let [ex (try
               (first-node "def A:\n    f: Float = 2.5s\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected lex-error for float form")
      (is (= :lex-unit-suffix-retired (:kind (ex-data ex))))
      (is (= "s" (:suffix (ex-data ex)))))))

(deftest f34-l3-nested-atom-slot-form-rejected
  (testing "F34 L3 — `def <name>: <TypeName>` inside a def body is retired at parse time. Diagnostic names the field, the type, and points at the spec-canonical `data:` migration."
    (let [ex (try
               (first-node "def Game:\n    def paddle: Paddle\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected fail! on the retired form")
      (is (re-find #"F34 L3" (.getMessage ex))
          "diagnostic tagged with the landing")
      (is (re-find #"paddle: Paddle = Paddle\(\)" (.getMessage ex))
          "diagnostic shows the exact rewrite"))))

(deftest f34-l3-nested-atom-slot-with-ctor-args-rejected
  (testing "F34 L3 — the ctor-args form (`def slot: Type(args)`) is also retired; the fix-it preserves args on the RHS ctor call."
    (let [ex (try
               (first-node "def Game:\n    def player: Player(max_hp: 100)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected fail! on the ctor-args form")
      (is (re-find #"F34 L3" (.getMessage ex)))
      (is (re-find #"player: Player = Player\(args\.\.\.\)"
                   (.getMessage ex))
          "diagnostic mentions preserving args on the RHS"))))

(deftest game-toys-parses
  (testing "the full game-toys.cxc example parses end-to-end — entry point is `def main`. The `module` declaration was retired 2026-08-19 (file-path-authoritative); nodes start with the `from-import`."
    (let [src (slurp "../design/examples/game-toys.cxc")
          nodes (p/parse src)]
      (is (= 9 (count nodes)))
      (is (= [:from-import :record
              :def-atom :def-atom :def-atom :def-atom :def-atom :def-atom :def-atom]
             (mapv :node nodes)))
      (is (= ["Vector2"] (mapv :name (filter #(= :record (:node %)) nodes))))
      (is (= ["Paddle" "ActionMachine" "Player" "Enemy" "EnemySpawner" "Game" "main"]
             (mapv :name (filter #(= :def-atom (:node %)) nodes)))))))

(deftest f20-for-sugar-rejected
  (testing "F20 REJECTED 2026-08-18 — `for e in xs: body` sugar retires; iteration is method dispatch per spec §3.7.1 rationale 3. Parser emits a targeted diagnostic pointing at `.for_each` rather than a generic parse error."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F20 REJECTED: `for e in xs: body` is not supported"
         (p/parse "def main:\n    -> running\n        then:\n            for e in xs: e :> tick\n")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"xs\.for_each\(e => body\)"
         (p/parse "def main:\n    -> running\n        then:\n            for e in xs:\n                e :> tick\n                e :> ping\n")))))

(deftest extern-section-retired
  (testing "`extern <Name>:` as a top-level section is retired at v1.0 (spec §1.12, §2335). Parser emits a targeted diagnostic pointing at the migration path (prelude `foreign library` blocks + `Extern` capability atom, §1.14) rather than a generic `expected top-level form` error."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`extern` as a top-level section keyword is retired at v1.0"
         (p/parse "extern C_stdio:\n    function puts(s: String) -> Int\n")))))

(deftest f69-l3-record-sugar-retired
  (testing "F69 L3 — `record Foo:` as a top-level declaration keyword is retired at v1.0 (spec §3.3, F69 atom unification). Parser emits a targeted rewrite hint pointing at `def Foo:` rather than a generic `unexpected` error."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F69 L3: `record Point:` sugar retired"
         (p/parse "record Point:\n    x: Int\n    y: Int\n")))))

(deftest f69-l3-enum-sugar-retired
  (testing "F69 L3 — `enum Foo:` as a top-level declaration keyword is retired at v1.0."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F69 L3: `enum Color:` sugar retired"
         (p/parse "enum Color:\n    case red\n    case green\n")))))

(deftest f69-l3-class-sugar-retired
  (testing "F69 L3 — `class Foo:` as a top-level declaration keyword is retired at v1.0."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F69 L3: `class Counter:` sugar retired"
         (p/parse "class Counter:\n    n: Int = 0\n")))))

(deftest f69-l5-switch-statement-retired
  (testing "F69 L5 — statement-position `switch <expr>: case ...` is retired at v1.0 (spec §3.7 rewrite: expression-position `<:` receive-glyph replaces). Parser emits a targeted rewrite hint pointing at the `<:` block form."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F69 L5: `switch <expr>: <case \.\.\.>` is retired at v1\.0"
         (p/parse "def main:\n    -> running\n        then:\n            switch x:\n                case a: print(1)\n")))))

(deftest f69-l4-dispatch-parses-cases-and-wildcard
  (testing "F69 L4 — `<:` receive-glyph dispatch block at statement position parses to a `:dispatch` node with a scrutinee and an arms vector. Case arms carry `:kind :case` patterns; the wildcard arm carries `:kind :wildcard`."
    (let [ast (p/parse "def Signal:\n    case red\n    case green\n\ndef main:\n    -> running\n        then:\n            let s: Signal = Signal.red\n            s:\n                <: red: print(1)\n                <: green: print(2)\n                <: _: print(9)\n")
          main-atom  (first (filter #(and (= :def-atom (:node %))
                                          (= "main" (:name %))) ast))
          transition (first (filter #(= :transition (:node %))
                                    (:body main-atom)))
          then-clause (first (filter #(= :then-clause (:node %))
                                     (:body transition)))
          dispatch   (first (filter #(= :dispatch (:node %))
                                    (:stmts then-clause)))]
      (is (some? dispatch) "dispatch node appears in the `then:` clause stmts")
      (is (= "s" (get-in dispatch [:scrutinee :name])) "scrutinee is `s`")
      (let [arms (:arms dispatch)]
        (is (= 3 (count arms)) "three arms parsed")
        (is (= :case (get-in arms [0 :pattern :kind])))
        (is (= "red" (get-in arms [0 :pattern :name])))
        (is (= :case (get-in arms [1 :pattern :kind])))
        (is (= "green" (get-in arms [1 :pattern :name])))
        (is (= :wildcard (get-in arms [2 :pattern :kind])))))))

(deftest f69-l4-dispatch-payload-binding-rejected-at-parse-only-when-illegal-shape
  (testing "F69 L4a — payload-binding syntax parses (`<: name(let x):`) even though L4b rejects at lower-time. This keeps parse-side surface stable; the migration diagnostic surfaces during compile."
    ;; Just verifies parse doesn't throw; deeper coverage lives in the
    ;; lower-time rejection test.
    (let [ast (p/parse "def Outcome:\n    case ok\n    case err\n\ndef main:\n    -> running\n        then:\n            let o: Outcome = Outcome.ok\n            o:\n                <: ok(let x): print(1)\n                <: _: print(0)\n")]
      (is (some? ast)))))

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
         (p/parse "def V:\n    state:\n        x: Float\n")))))

(deftest f8-l3c-stub-metadata-section-in-def-body-rejected-with-f10-pointer
  (testing "F8 L3c stub — `metadata:` inside a def-body has no compiler backend at v1.0 (F53 metadata emission goes through `.cxc-manifest.json`, not the section). Without a targeted rejection the body items silently misparse as data fields via the generic custom-section dispatch (`kind: class` → field `kind` of type `class`). Reject at parse time with an F10 pointer."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F8 L3c.*metadata:.*F10"
         (p/parse (str "def Counter:\n"
                       "    n: Int = 0\n\n"
                       "    metadata:\n"
                       "        kind: class\n\n"
                       "    -> running\n"))))))

(deftest chained-comparison-desugars-to-chained-cmp
  (testing "F27 semantic once-only: `a <= b <= c` becomes a `:chained-cmp` node carrying flat operands + ops vectors. Emit renders it as a GNU statement-expression that binds each interior operand once (via `__typeof__`) so an effectful middle like a call fires exactly once at runtime."
    ;; Middle is an ident — desugars to :chained-cmp with 3 operands.
    (let [tree (p/parse "def main:
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
    (is (some? (p/parse "def V:
    x: Int

    mid() :< self.x

def main:
    v: V = V(x: 5)

    -> running

    running <: check -> self
        when: 0 <= v.mid() <= 10
        then: v => v
")))
    ;; 4-operand chain: -10 <= a <= b <= 10 → :chained-cmp with
    ;; 4 operands + 3 ops (fold-into on the postwalk pass).
    (let [tree (p/parse "def main:
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
  (testing "Audit F22: spec §10 — transition sub-blocks must appear in the order `when, then`. Out-of-order clauses (e.g. `then:` before `when:`) parse-error with a specific diagnostic. Post F18 L1' Landing 2 (2026-08-24) the `let:` sub-block retired, so clause-order only involves when/then."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`when:` clause out of order"
         (p/parse "def main:
    n: Int = 0

    -> running

    running <: bump -> self
        then: n => n + 1
        when: n < 10
")))))

(deftest transition-clause-multiplicity-enforced
  (testing "Audit F22 + F70 I3: spec §10 — `when:` may appear at most once per transition body. `then:` is exempt (I3 relaxation) so the `do:` → `then:` migration produces bodies with multiple `then:` blocks that merge at lowering. Post F18 L1' Landing 2 (2026-08-24) the `let:` sub-block retired — `let` bindings are now statement-form inside a `then:` clause, so no clause-level duplicate detection applies."
    ;; F70 I3: multiple then: no longer parse-errors.
    (is (some? (p/parse "def main:
    n: Int = 0

    -> running

    running <: bump -> self
        then: n => n + 1
        then: n => n + 2
")))
    ;; Duplicate when: still rejects.
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"duplicate `when:` clause"
         (p/parse "def main:
    n: Int = 0

    -> running

    running <: bump -> self
        when: n < 10
        when: n < 20
        then: n => n + 1
")))))

;; f67-l4-methods-section-retired-with-migration deleted at F68 L6b — `methods`
;; is no longer a reserved word (Proposal H clears the sugar-body vocabulary),
;; so `methods:` in a body lexes as `methods` (ident) + `:` and hits a generic
;; parse error instead of a targeted diagnostic. F67 L4's migration guidance
;; lives in the plan doc + git log.

(deftest f67-l4-function-keyword-in-def-atom-body-retired
  (testing "F67 L4 — `function` keyword inside a def-atom body retires with `methods:`; free functions still live at module scope."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F67 L4.*`function` keyword"
         (p/parse "def M:\n    n: Int = 0\n    function bumped(self) -> Int:\n        return n\n")))))

(deftest f67-l4-def-method-form-in-def-atom-body-retired
  (testing "F67 L4 — spec-canonical `def name(args)` method form inside a class body retires."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F67 L4.*method form"
         (p/parse "def M:\n    n: Int = 0\n    def bumped(self) -> Int:\n        return n\n")))))

(deftest f37-l6b-class-function-parses-to-class-function-node-inside-def-body
  (testing "F37 L6b — `class function name(params) -> RetType: body` inside a def body parses to `:class-function` (spec §3.4.6 static-fn shape). Params, return type, body preserved. `class` is an :ident (not reserved) followed by `:kw \"function\"` — the parser detects the pair BEFORE the F67 L4 `function` rejection arm so class-functions don't trip that diagnostic."
    (let [ast     (p/parse (str "def Duration:\n"
                                "    ms: Int = 0\n\n"
                                "    class function seconds(n: Int) -> Duration:\n"
                                "        return Duration(ms: n * 1000)\n"))
          rec     (first ast)
          cfn     (first (filter #(= :class-function (:node %))
                                 (or (:sections rec) (:body rec))))]
      (is (some? cfn)
          "class-function node present in body/sections")
      (is (= "seconds" (:name cfn)))
      (is (= 1 (count (:params cfn))))
      (is (= "n" (:name (first (:params cfn)))))
      (is (= "Duration" (get-in cfn [:return-type :name]))
          "return type preserved as :type-ref node"))))

(deftest f37-l6b-class-function-without-return-type-still-parses
  (testing "F37 L6b — return type is optional at parse time (matches parse-function-decl convention). Enforcement of the spec §3.4.6 required-return-type contract lands at lower-time with F37 L6."
    (let [ast (p/parse (str "def Duration:\n"
                            "    ms: Int = 0\n\n"
                            "    class function noop():\n"
                            "        return 0\n"))
          rec (first ast)
          cfn (first (filter #(= :class-function (:node %))
                             (or (:sections rec) (:body rec))))]
      (is (some? cfn))
      (is (nil? (:return-type cfn))
          "absent return type surfaces as nil"))))

(deftest f37-l6b-class-function-does-not-trip-f67-l4-function-rejection
  (testing "F37 L6b — the atom-body dispatch arm for `class function` fires BEFORE the F67 L4 `function` rejection arm, so a class-function decl doesn't get a misleading migration diagnostic. Position matters — regression test locks in the order."
    ;; Should NOT throw the F67 L4 diagnostic.
    (is (some? (p/parse (str "def Foo:\n"
                             "    x: Int = 0\n\n"
                             "    class function make() -> Foo:\n"
                             "        return Foo(x: 0)\n"))))))

(deftest subscript-postfix-parses
  (testing "F19 / §3.9 — `expr[i]` parses as a :subscript node (index is any expression). Post F67 L4: observable body is a bare expression, no `return`."
    (let [src "def R:\n    xs: List of Int\n\n    head() :< xs[0]\n"
          rec (first-node src)
          obs (first (filter #(= :observable (:node %)) (:sections rec)))]
      (is (= :subscript (:node (:expr obs))))
      (is (= "xs" (get-in obs [:expr :target :name])))
      (is (= :lit-int (get-in obs [:expr :index :node])))
      (is (= 0 (get-in obs [:expr :index :value]))))))

(deftest subscript-chains-with-other-postfix
  (testing "F19 — subscript composes with `.field` and `(args)` in any order. Post F67 L4: observable expression body."
    (let [src "def R:\n    xs: List of Int\n\n    z() :< xs[0].bar(1)\n"
          rec (first-node src)
          obs (first (filter #(= :observable (:node %)) (:sections rec)))
          call (:expr obs)]
      (is (= :call (:node call)))
      (is (= :dot (get-in call [:callee :node])))
      (is (= "bar" (get-in call [:callee :field])))
      (is (= :subscript (get-in call [:callee :target :node]))))))

;; F23 tests for the transition `let:` sub-block retired 2026-08-24
;; when F18 L1' Landing 2 hard-rejected the sub-block form. Coverage
;; moved to `f18-l1prime-landing-2-transition-let-clause-hard-rejected`
;; (rejection diagnostic) and the F18 L1 statement-form suite (which
;; already exercises `let x = expr` inside `then:`).

;; f67-l5-data-header-retired-with-migration deleted at F68 L6c — `data` is
;; no longer a reserved word (the retirement is the reservation removal itself).
;; A `data:` header now lexes as an :ident field decl and fails downstream on
;; type parsing; the targeted hand-hold is gone (Option C philosophy).

(deftest f67-l5-top-level-fields-parse-as-synthetic-data-section
  (testing "F67 L5 — fields at atom-body top level fold into a synthetic :data-section so downstream shape stays stable. Two fields in declaration order. F68 note: an atom-shaped body requires an explicit atom marker (transition/use/ctor) so the def-atom AST kind is preserved; the field folding is the assertion here."
    (let [n (first-node "def A:\n    n: Int = 10\n    m: Float = 1.5\n\n    -> running\n\n    -> end\n")
          dsec (first (filter #(= :data-section (:node %)) (:body n)))]
      (is (some? dsec) "synthesized :data-section present")
      (is (= 2 (count (:fields dsec))))
      (is (= "n" (get-in dsec [:fields 0 :name])))
      (is (= "m" (get-in dsec [:fields 1 :name])))
      (is (= "Int" (get-in dsec [:fields 0 :type :name]))))))

(deftest f67-l5-top-level-fields-on-record
  (testing "F67 L5 — records also collapse `data:` to top-level fields (Proposal D applies uniformly; records ARE pure data)."
    (let [n (first-node "def V:\n    x: Float\n    y: Float\n")
          dsec (first (filter #(= :data-section (:node %)) (:sections n)))]
      (is (some? dsec))
      (is (= [{:node :field :name "x" :type {:node :type-ref :name "Float"} :default nil}
              {:node :field :name "y" :type {:node :type-ref :name "Float"} :default nil}]
             (:fields dsec))))))

(deftest f67-l3-handler-decl-sugar-retired-with-migration
  (testing "F67 L3 — the legacy `-> foo(args):` handler-decl sugar is retired at parse time. Users get a diagnostic naming the retired form and pointing at the explicit handler-shape transition `* <: foo(args) -> * then: ...` (spec §1265 semantics preserved)."
    (let [src "def A:\n    n: Int = 0\n\n    -> running\n\n    -> tick(msg: Int):\n        return n\n"]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"F67 L3.*handler-decl sugar is retired"
           (first-node src))))))

(deftest f67-l3-wildcard-handler-transition-parses
  (testing "F67 L3 — the migration target `* <: name(args) -> * then: ...` parses as a :transition with wildcard source, wildcard target, and typed :params on the receive-msg (distinguishing it from the payload-destructure `msg{payload}` form)."
    (let [src "def A:\n    n: Int = 0\n\n    -> running\n\n    * <: tick(msg: Int) -> *\n        then: return n\n"
          atom-node (first-node src)
          t (last (filter #(= :transition (:node %)) (:body atom-node)))]
      (is (= :def-atom (:node atom-node)))
      (is (= :wildcard (get-in t [:source :kind])))
      (is (= "*" (:target t)))
      (is (= "<:" (get-in t [:receive :glyph])))
      (is (= "tick" (get-in t [:receive :msg :source :name])))
      (is (= 1 (count (get-in t [:receive :msg :params]))))
      (is (= "msg" (get-in t [:receive :msg :params 0 :name])))
      (is (= "Int" (get-in t [:receive :msg :params 0 :type :name]))))))

(deftest f67-l3-wildcard-handler-zero-args-parses
  (testing "F67 L3 — zero-arg handler-shape `* <: tick() -> *` parses with empty :params (still distinguished from a payload-shape receive)."
    (let [src "def A:\n    n: Int = 0\n\n    -> running\n\n    * <: tick() -> *\n        then: return n\n"
          atom-node (first-node src)
          t (last (filter #(= :transition (:node %)) (:body atom-node)))]
      (is (some? t))
      (is (= :wildcard (get-in t [:source :kind])))
      (is (= "*" (:target t)))
      (is (= "tick" (get-in t [:receive :msg :source :name])))
      (is (empty? (get-in t [:receive :msg :params]))))))

(deftest f15-l1-named-tuple-type-at-param-position
  (testing "F15 Landing 1 — `(x: Float, y: Float)` at param position parses as :type-named-tuple with declaration-order fields."
    (let [src "def R:\n    p: (x: Float, y: Float)\n"
          rec (first-node src)
          fld (-> rec :sections first :fields first)
          ty  (:type fld)]
      (is (= :type-named-tuple (:node ty)))
      (is (= [{:name "x" :type {:node :type-ref :name "Float"}}
              {:name "y" :type {:node :type-ref :name "Float"}}]
             (:fields ty))))))

(deftest f15-l1-anonymous-tuple-type
  (testing "F15 Landing 1 — `(Int, String)` at type position parses as :type-tuple."
    (let [src "def R:\n    pair: (Int, String)\n"
          rec (first-node src)
          ty  (-> rec :sections first :fields first :type)]
      (is (= :type-tuple (:node ty)))
      (is (= 2 (count (:types ty)))))))

(deftest f15-l1-single-paren-groups-not-tuple
  (testing "F15 Landing 1 — a single-element paren group `(T)` parses as T, not a 1-tuple. Preserves the parens-as-grouping intuition."
    (let [src "def R:\n    n: (Int)\n"
          rec (first-node src)
          ty  (-> rec :sections first :fields first :type)]
      (is (= :type-ref (:node ty)))
      (is (= "Int" (:name ty))))))

(deftest f15-l1-nullable-named-tuple-parses
  (testing "F15 Landing 1 + F39 L5a (2026-08-20) — `(x: Int, y: Int)?` parses; the parser produces `:type-nullable` wrapping `:type-named-tuple` faithfully. Under the unified rule (§3.6.1), tuple positions are covered by the universal `T?` ban — `:cxc-f39-nullable-suffix-banned` fires at lower-time. This test locks in the parse AST shape so the lower-time diagnostic has a stable target."
    (let [src "def R:\n    config: (host: String, port: UInt16)?\n"
          rec (first-node src)
          ty  (-> rec :sections first :fields first :type)]
      (is (= :type-nullable (:node ty)))
      (is (= :type-named-tuple (get-in ty [:inner :node]))))))

;; F9 L1 sugar-path tests deleted at F68 L6b — the `class`/`protocol`/`record`
;; sugar keywords and their `metadata: kind:` + header-conformance-parens
;; surface are retired. `def Foo:` with structural detection is the sole
;; opener. `sealed protocol` was never wired past L1 diagnostic — deleted
;; alongside the rest of the L1 sugar surface. Header conformances now use
;; body-form `conforms: P` (still supported inside `def` and `extend` bodies).

;; ---------------------------------------------------------------------------
;; F68 L1 (Proposal H) — full unification under `def`.
;; ---------------------------------------------------------------------------

(deftest f68-l1-def-with-accepts-reshapes-to-protocol
  (testing "F68 L1 — `def Foo:` body containing `accepts:` entries reshapes into `:class :kind :protocol` at parse. Downstream `build-protocol-env` sees the same AST kind that `protocol Foo:` produces."
    (let [n (first-node (str "def Iterable:\n"
                             "    accepts:\n"
                             "        has_next() -> Bool\n"
                             "        next() -> Element\n"))]
      (is (= :class (:node n)))
      (is (= :protocol (:kind n)))
      (is (= "Iterable" (:name n)))
      (is (= 2 (count (:accepts n)))))))

(deftest f68-l1-def-with-per-entry-accepts-sugar-reshapes-to-protocol
  (testing "F68 L1 — `def Foo:` body containing `accepts <sig>` per-entry sugar (not a `:` section) also reshapes to protocol."
    (let [n (first-node (str "def Comparable:\n"
                             "    accepts compare(self, other: Self) -> Int\n"))]
      (is (= :class (:node n)))
      (is (= :protocol (:kind n)))
      (is (= 1 (count (:accepts n)))))))

(deftest f68-l1-def-protocol-with-ctor-params-rejected
  (testing "F68 L1 — `def Foo(...)` body with `accepts:` is rejected at parse — protocols declare signatures only and carry no state."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F68 L1.*ctor params.*protocols declare signatures only"
         (first-node (str "def Iterable(x: Int):\n"
                          "    accepts:\n"
                          "        has_next() -> Bool\n"))))))

(deftest f68-l1-def-with-transition-stays-def-atom
  (testing "F68 L1 — `def Foo:` with a transition stays `:def-atom` (structural detection classifies as class/atom; not reshaped)."
    (let [n (first-node (str "def Counter:\n"
                             "    n: Int = 0\n"
                             "    self <: bump -> self then: n => n + 1\n"))]
      (is (= :def-atom (:node n))))))

(deftest f68-l2-def-with-case-markers-reshapes-to-enum
  (testing "F68 L2 — `def Foo:` body with `case <ident>` prefix markers reshapes to `:enum` AST. Matches the shape `parse-enum` produces so downstream `build-enum-env` sees the same node kind."
    (let [n (first-node (str "def Color:\n"
                             "    case red\n"
                             "    case green\n"
                             "    case blue\n"))]
      (is (= :enum (:node n)))
      (is (= "Color" (:name n)))
      (is (= ["red" "green" "blue"] (:cases n))))))

(deftest f16-l1-case-with-payload-param-list-parses
  (testing "F16 L1 — a `case <ident>(<param-list>)` marker parses to `:enum-case` with a `:payload` vector of `:field` maps. `:case-entries` on the reshaped enum preserves the payload per case; `:cases` (names) stays available for legacy readers."
    (let [n (first-node (str "def Msg:\n"
                             "    case ping\n"
                             "    case pong(reply_to: Int, seq: Int)\n"))]
      (is (= :enum (:node n)))
      (is (= ["ping" "pong"] (:cases n)))
      (is (= "ping" (get-in n [:case-entries 0 :name])))
      (is (= []     (get-in n [:case-entries 0 :payload])))
      (is (= "pong" (get-in n [:case-entries 1 :name])))
      (let [pl (get-in n [:case-entries 1 :payload])]
        (is (= 2 (count pl)))
        (is (= "reply_to" (:name (first pl))))
        (is (= "Int" (:name (:type (first pl)))))
        (is (= "seq" (:name (second pl))))))))

(deftest f16-l1-case-payload-default-rejected
  (testing "F16 L1 — a default `= <expr>` on an enum payload field rejects at parse time. Payload fields don't carry defaults: the case *value* is the payload; a default would confuse construction semantics."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F16 L1: default on enum payload"
         (first-node (str "def Msg:\n"
                          "    case ok(n: Int = 0)\n"))))))

(deftest f68-l2-def-enum-with-ctor-params-rejected
  (testing "F68 L2 — `def Foo(x: Int)` body with `case` markers is rejected — enums are a closed case set with no ctor state."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F68 L2.*ctor params.*enums declare a closed set of cases"
         (first-node (str "def Color(x: Int):\n"
                          "    case red\n"))))))

(deftest f68-l2-def-mixed-case-and-fields-rejected
  (testing "F68 L2 — `def Foo:` mixing `case` markers with fields/observables/transitions is rejected — enums are a closed case set."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F68 L2.*mixes `case` markers with other body items"
         (first-node (str "def Color:\n"
                          "    case red\n"
                          "    n: Int = 0\n"))))))

(deftest f68-l3-extend-without-class-word-parses
  (testing "F68 L3 — `extend Foo:` (no `class` word) parses to the same `:extend-class` AST node as `extend Foo:`."
    (let [n (first-node
             (str "extend Foo:\n"
                  "    bar() :< 1\n"))]
      (is (= :extend-class (:node n)))
      (is (= "Foo" (:target n)))
      (let [obs (first (:body n))]
        (is (= :observable (:node obs)))
        (is (= "bar" (:name obs)))))))

(deftest f68-l3-extend-with-header-conforms-no-class-word
  (testing "F68 L3 — `extend Foo(NewProtocol):` (short form + header-parens conformance) parses to `:extend-class` with `:conforms` populated."
    (let [n (first-node
             (str "extend Foo(NewProtocol):\n"
                  "    bar() :< 3\n"))]
      (is (= :extend-class (:node n)))
      (is (= "Foo" (:target n)))
      (is (= [{:node :type-ref :name "NewProtocol"}]
             (:conforms n))))))

;; ---------------------------------------------------------------------------
;; F9 Landing 2 — parse `accepts:` (section-form and per-entry sugar);
;; validate kind-vs-section constraints in fold-class-body.
;; ---------------------------------------------------------------------------

(deftest f9-l2-accepts-section-parses
  (testing "F9 Landing 2 — `accepts:` block on a protocol collects each signature into `:accepts` on the class node. Params follow the standard `name: Type` convention (§2.5)."
    (let [n (first-node
             (str "def P:\n"
                  "    accepts:\n"
                  "        foo(x: Int) -> Int\n"
                  "        bar(s: String) -> Bool\n"))]
      (is (= :class    (:node n)))
      (is (= :protocol (:kind n)))
      (is (= 2 (count (:accepts n))))
      (is (= "foo" (:name (first  (:accepts n)))))
      (is (= "bar" (:name (second (:accepts n))))))))

;; f9-l2-accepts-per-entry-sugar / accepts-rejected-on-non-protocol /
;; data-on-protocol-rejected deleted at F68 L6b — the per-entry `accepts <sig>`
;; sugar lived in parse-class-section (retired); accepts-on-non-protocol and
;; data-on-protocol were fold-class-body invariants (also retired). Structural
;; detection in parse-def-atom handles the accepts→protocol / fields→record
;; classification; there is no non-protocol accepts landing site anymore.

(deftest f9-l2-accepts-contract-clauses-fail-nyi
  (testing "F9 Landing 2 — `accepts:` entries reject continuation-indented `requires`/`ensures`/effect annotations, with a pointer at F49 (contract inheritance)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"contract clauses.*not yet implemented|F49"
         (first-node
          (str "def P:\n"
               "    accepts:\n"
               "        foo(x: Int) -> Int\n"
               "            requires: true\n"))))))

;; ---------------------------------------------------------------------------
;; F9 Landing 3 — `extend Foo:` parsing + strict body dispatch;
;; `replace` method modifier; target-resolution deferred to lower.
;; ---------------------------------------------------------------------------

(deftest f9-l3-extend-class-with-observable
  (testing "F9 Landing 3 + F67 L4 — `extend Foo:` accepts observables (Proposal G reader surface). Extensions land in `:extend-class` node carrying target name and body sections."
    (let [n (first-node
             (str "extend Foo:\n"
                  "    bar() :< 1\n"))]
      (is (= :extend-class (:node n)))
      (is (= "Foo" (:target n)))
      (is (empty? (:conforms n)))
      (let [obs (first (:body n))]
        (is (= :observable (:node obs)))
        (is (= "bar" (:name obs)))))))

(deftest f67-l4c-replace-prefix-marks-extension-observable
  (testing "F67 L4c — `replace` keyword prefixed to an extension observable marks the parsed node with `:replace? true` so composition (§3.3.2 rule 4) can honor the intentional shadow without a warning."
    (let [n (first-node
             (str "extend Foo:\n"
                  "    replace bar() :< 2\n"))]
      (is (= :extend-class (:node n)))
      (is (= "Foo" (:target n)))
      (let [obs (first (:body n))]
        (is (= :observable (:node obs)))
        (is (= "bar" (:name obs)))
        (is (true? (:replace? obs)))))))

(deftest f67-l4c-replace-prefix-on-transition
  (testing "F67 L4c — `replace` prefix also legal on a wildcard self-transition inside an extension body (parses; composition wiring for transitions is a separate landing)."
    (let [n (first-node
             (str "extend Foo:\n"
                  "    replace * <: reset() -> * then: print(0)\n"))]
      (is (= :extend-class (:node n)))
      (let [t (first (:body n))]
        (is (= :transition (:node t)))
        (is (true? (:replace? t)))))))

(deftest f67-l4c-replace-on-plain-class-rejected
  (testing "F67 L4c — `replace` inside a non-extension class body has no shadow target; parser rejects with §3.3.2 rule-4 pointer."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`replace` modifier.*only meaningful.*`extend"
         (first-node
          (str "def Widget:\n"
               "    n: Int = 0\n"
               "    replace bumped() :< n + 1\n"))))))

(deftest f9-l3-extend-class-with-header-conforms
  (testing "F9 Landing 3 — `extend Foo(NewProtocol):` populates `:conforms` from header parens."
    (let [n (first-node
             (str "extend Foo(NewProtocol):\n"
                  "    bar() :< 3\n"))]
      (is (= [{:node :type-ref :name "NewProtocol"}]
             (:conforms n))))))

(deftest f9-l3-extend-class-with-body-conforms
  (testing "F9 Landing 3 — body-form `conforms: P` inside `extend class` extracted into `:conforms` and dropped from :body."
    (let [n (first-node
             (str "extend Foo:\n"
                  "    conforms: NewProtocol\n"
                  "    bar() :< 4\n"))]
      (is (= [{:node :type-ref :name "NewProtocol"}]
             (:conforms n)))
      (is (= [:observable] (mapv :node (:body n)))))))

(deftest f9-l3-extend-class-with-invariants
  (testing "F9 Landing 3 — `invariants:` section legal inside `extend class` (extensions may add invariants per §3.3.2)."
    (let [n (first-node
             (str "extend Foo:\n"
                  "    invariants:\n"
                  "        x > 0\n"))]
      (is (= :invariants-section (:node (first (:body n))))))))

(deftest f9-l3-extend-class-rejects-top-level-fields
  (testing "F9 Landing 3 + F67 L5 — a field declaration inside `extend class` fails at parse per §3.3.2 (extensions cannot add state). Post-L5 the surface is top-level fields (no `data:` header)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"field declaration `x: …` is not permitted inside `extend"
         (first-node
          (str "extend Foo:\n"
               "    x: Int\n"))))))

(deftest f9-l3-extend-class-rejects-state
  (testing "F9 Landing 3 — `state:` inside `extend class` fails at parse. `state:` is retired at v1.0 AND extensions cannot add fields either way."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`state:` section is retired"
         (first-node
          (str "extend Foo:\n"
               "    state:\n"
               "        x: Int\n"))))))

;; f9-l3-extend-class-rejects-metadata deleted at F68 L6b — `metadata` is no
;; longer a reserved word; the retirement is the reservation removal itself.
;; `metadata:` inside an extension body hits a generic parse error now.

(deftest f9-l3-extend-class-rejects-use
  (testing "F9 Landing 3 — `use:` inside `extend class` fails at parse (extensions cannot add capabilities)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`use:` is not permitted inside `extend"
         (first-node
          (str "extend Foo:\n"
               "    use:\n"
               "        clock: Clock\n"))))))

;; f9-l3-extend-rejects-non-class-target deleted at F68 L6b — `enum` /
;; `protocol` are no longer reserved words (both retired under Proposal H),
;; so `extend enum Foo:` no longer needs a targeted diagnostic; it becomes
;; a generic "expected `:` after ident" or "unexpected ident `Foo`" parse
;; error since `enum` lexes as a bare identifier. Extensions always target
;; a `def`-declared thing under unification, so the non-class-target check
;; is now vacuous.

;; ---------------------------------------------------------------------------
;; F13 L1 — reference modifiers on `data:` fields (spec §6.14)
;; ---------------------------------------------------------------------------

(deftest f13-l1-weak-modifier-recognized
  (testing "F13 L1 — `weak` between `:` and the type is captured on the field node."
    (let [n (first-node "def X:\n    parent: weak X\n\n    -> alive\n")
          fs (:fields (first (filter #(= :data-section (:node %))
                                     (:body n))))]
      (is (= "parent" (:name (first fs))))
      (is (= :weak (:modifier (first fs))))
      (is (= "X" (get-in (first fs) [:type :name]))))))

(deftest f13-l1-unowned-modifier-recognized
  (testing "F13 L1 — `unowned` between `:` and the type is captured on the field node."
    (let [n (first-node "def X:\n    delegate: unowned X\n\n    -> alive\n")
          fs (:fields (first (filter #(= :data-section (:node %))
                                     (:body n))))]
      (is (= :unowned (:modifier (first fs)))))))

(deftest f13-l1-weak-on-nullable-parses
  (testing "F13 L1 + F39 L5a (2026-08-20) — `weak T?` parses successfully (the pre-L5a parse intercept retired). The `?` reject fires at lower-time via `:cxc-f39-nullable-suffix-banned`; parse just captures the shape faithfully. The `weak` modifier stays attached; dropping the `?` per the unified diagnostic yields `weak X` which is legal."
    (let [n  (first-node "def X:\n    parent: weak X?\n\n    -> alive\n")
          fs (:fields (first (filter #(= :data-section (:node %))
                                     (:body n))))]
      (is (= :weak (:modifier (first fs))))
      (is (= :type-nullable (get-in (first fs) [:type :node]))))))

(deftest f13-l1-weak-on-nonnil-rejected
  (testing "F13 L1 — `weak T!` is rejected — the `!` contract is incompatible with `weak`'s nils-on-dealloc semantics."
    (let [ex (try
               (first-node "def X:\n    parent: weak X!\n\n    -> alive\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected fail!")
      (is (re-find #"F13 L1" (.getMessage ex)))
      (is (re-find #"Drop the `!`" (.getMessage ex))))))

(deftest f13-l1-modifier-on-primitive-rejected
  (testing "F13 L1 — `weak Int` etc. are rejected — reference modifiers apply to reference types (atoms, `String`) only."
    (doseq [prim ["Int" "Float" "Bool" "Char" "Byte" "UInt32"]]
      (let [ex (try
                 (first-node (str "def X:\n    n: weak " prim "\n\n    -> alive\n"))
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
        (is ex (str "expected fail! on `weak " prim "`"))
        (is (re-find #"F13 L1" (.getMessage ex)))
        (is (re-find #"value/primitive" (.getMessage ex)))))))

(deftest f13-l1-modifier-on-container-rejected
  (testing "F13 L1 — `weak List of T` is rejected — collections don't propagate reference modifiers to their elements."
    (let [ex (try
               (first-node "def X:\n    items: weak List of X\n\n    -> alive\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected fail!")
      (is (re-find #"F13 L1" (.getMessage ex)))
      (is (re-find #"collections don't propagate" (.getMessage ex))))))

(deftest f13-l1-no-modifier-omits-key
  (testing "F13 L1 — a plain field without a modifier has no `:modifier` key on the node — reserves the key for opt-in shape."
    (let [n (first-node "def X:\n    n: Int = 0\n\n    -> alive\n")
          fs (:fields (first (filter #(= :data-section (:node %))
                                     (:body n))))]
      (is (not (contains? (first fs) :modifier))
          "no :modifier key present when no modifier declared"))))

;; ---------------------------------------------------------------------------
;; F18 L1 — local `let` binding at statement position (spec §2.5)
;; ---------------------------------------------------------------------------

(defn- do-stmts-from
  "Extract the side-effect statement sequence from `def main`'s
   `-> running then:` transition body for F18 L1 tests. Post-F70 I3
   (2026-08-19) the effect clause is `then:` with `:stmts` holding
   the side-effect entries; earlier `do:` clauses folded into it."
  [source]
  (let [main    (first (filter #(= :def-atom (:node %)) (p/parse source)))
        txn     (first (filter #(= :transition (:node %)) (:body main)))
        then-c  (first (filter #(= :then-clause (:node %)) (:body txn)))]
    (:stmts then-c)))

(deftest f18-l1-let-binding-no-annotation
  (testing "F18 L1 — `let x = 5` in a do-block parses as `:let-stmt` with no `:type`."
    (let [[s] (do-stmts-from "def main:\n    -> running\n        then:\n            let x = 5\n            print(x)\n")]
      (is (= :let-stmt (:node s)))
      (is (= "x" (:name s)))
      (is (nil? (:type s)))
      (is (= 5 (:value (:expr s)))))))

(deftest f18-l1-let-binding-with-annotation
  (testing "F18 L1 — `let x: Int = 5` parses with the type annotation on `:type`."
    (let [[s] (do-stmts-from "def main:\n    -> running\n        then:\n            let x: Int = 5\n            print(x)\n")]
      (is (= :let-stmt (:node s)))
      (is (= "x" (:name s)))
      (is (= "Int" (get-in s [:type :name]))))))

(deftest f18-l1-multiple-lets-in-sequence
  (testing "F18 L1 — multiple let-bindings in a do-block parse as separate `:let-stmt`s, in source order."
    (let [stmts (do-stmts-from "def main:\n    -> running\n        then:\n            let a = 1\n            let b = 2\n            print(a)\n")]
      (is (= :let-stmt (:node (nth stmts 0))))
      (is (= "a"       (:name  (nth stmts 0))))
      (is (= :let-stmt (:node (nth stmts 1))))
      (is (= "b"       (:name  (nth stmts 1)))))))

;; ---------------------------------------------------------------------------
;; F17 L1 — statement-position while / break / continue (spec §3.8.3 / §4.20.3)
;; ---------------------------------------------------------------------------

(deftest f17-l1-while-inline-body
  (testing "F17 L1 — `while <cond>: <single-stmt>` parses to `:while-stmt` whose body is a single-stmt block."
    (let [[s] (do-stmts-from "def main:\n    -> running\n        then:\n            while true: print(1)\n")]
      (is (= :while-stmt (:node s)))
      (is (= true (:value (:cond s))))
      (is (= :block (:node (:body s))))
      (is (= 1 (count (:stmts (:body s))))))))

(deftest f17-l1-while-block-body
  (testing "F17 L1 — `while <cond>:\\n    <stmts...>` parses to a `:while-stmt` whose body is an indented block of multiple stmts."
    (let [[s] (do-stmts-from "def main:\n    -> running\n        then:\n            while true:\n                print(1)\n                print(2)\n")]
      (is (= :while-stmt (:node s)))
      (is (= 2 (count (:stmts (:body s))))))))

(deftest f17-l1-break-and-continue-inside-while
  (testing "F17 L1 — `break` and `continue` as bare statements inside a while body parse to `:break-stmt` and `:continue-stmt`."
    (let [[s] (do-stmts-from "def main:\n    -> running\n        then:\n            while true:\n                break\n                continue\n")]
      (is (= :while-stmt (:node s)))
      (let [inner (:stmts (:body s))]
        (is (= :break-stmt    (:node (nth inner 0))))
        (is (= :continue-stmt (:node (nth inner 1))))))))

(deftest f17-l1-break-with-value-rejected
  (testing "F17 L1 — `break <expr>` is rejected. Value-yielding loops (F17 L2) were rejected 2026-08-18 as redundant with actor state transitions; the diagnostic steers users at the self-transition + `when:` guard pattern instead."
    (let [ex (try
               (do-stmts-from "def main:\n    -> running\n        then:\n            while true:\n                break 42\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected fail!")
      (is (re-find #"F17 L1" (.getMessage ex)))
      (is (re-find #"state transitions" (.getMessage ex))
          "diagnostic points at the actor-substrate replacement"))))

;; ---------------------------------------------------------------------------
;; F18 L2 — `do:` at expression position (spec §3.8.2)
;; ---------------------------------------------------------------------------

(deftest f18-l2-inline-do-parses-as-do-block-expr
  (testing "F18 L2 — `let x = do: 5` — the RHS parses as `:do-block-expr` carrying a single `:expr-stmt` (the lit-int)."
    (let [[s] (do-stmts-from "def main:\n    -> running\n        then:\n            let x = do: 5\n            print(x)\n")]
      (is (= :let-stmt (:node s)))
      (is (= :do-block-expr (:node (:expr s))))
      (is (= 1 (count (:stmts (:expr s)))))
      (is (= :expr-stmt (:node (first (:stmts (:expr s))))))
      (is (= 5 (:value (:expr (first (:stmts (:expr s))))))))))

(deftest f18-l2-indented-do-parses-multiple-stmts
  (testing "F18 L2 — indented `do:` block parses all statements as the body of `:do-block-expr`. Bindings within compose left-to-right; the final `:expr-stmt` is the block's value."
    (let [[s] (do-stmts-from "def main:\n    -> running\n        then:\n            let r = do:\n                let a = 3\n                let b = 4\n                a + b\n            print(r)\n")
          body (:stmts (:expr s))]
      (is (= :do-block-expr (:node (:expr s))))
      (is (= 3 (count body)))
      (is (= :let-stmt (:node (nth body 0))))
      (is (= :let-stmt (:node (nth body 1))))
      (is (= :expr-stmt (:node (nth body 2))))
      (is (= :binop (:node (:expr (nth body 2)))))
      (is (= "+" (:op (:expr (nth body 2))))))))

;; ---------------------------------------------------------------------------
;; K5 L1 — `if` at expression position (spec §3.8.1)
;; ---------------------------------------------------------------------------

(deftest k5-l1-inline-if-else-parses-as-if-expr
  (testing "K5 L1 — `let x = if 1 > 0: 10 else: 20` parses the RHS as `:if-expr` with cond/then/else branches."
    (let [[s] (do-stmts-from "def main:\n    -> running\n        then:\n            let x = if 1 > 0: 10 else: 20\n            print(x)\n")]
      (is (= :let-stmt (:node s)))
      (is (= :if-expr (:node (:expr s))))
      (is (= :binop (:node (:cond (:expr s)))))
      (is (= 10 (:value (:then (:expr s)))))
      (is (= 20 (:value (:else (:expr s))))))))

(deftest k5-l1-else-if-chain-parses-right-associative
  (testing "K5 L1 — `if a: 1 else if b: 2 else: 3` desugars into a nested :if-expr in the else branch (right-associative). Constructed with actual conds since the parser needs boolean-producing test exprs."
    (let [[s] (do-stmts-from "def main:\n    -> running\n        then:\n            let x = if 1 > 2: 1 else if 3 > 4: 2 else: 3\n            print(x)\n")
          ife (:expr s)]
      (is (= :if-expr (:node ife)))
      (is (= 1 (:value (:then ife))))
      (is (= :if-expr (:node (:else ife)))
          "nested :if-expr in outer's else branch")
      (is (= 2 (:value (:then (:else ife)))))
      (is (= 3 (:value (:else (:else ife))))))))

(deftest k5-l1-missing-else-is-compile-error
  (testing "K5 L1 open-Q1 ratified — missing `else` at expression position is a compile error. Parser diagnostic names K5 L1 so users find the ratified decision."
    (let [ex (try
               (do-stmts-from "def main:\n    -> running\n        then:\n            let x = if 1 > 0: 10\n            print(x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex "expected fail!")
      (is (re-find #"K5 L1" (.getMessage ex)))
      (is (re-find #"`else`" (.getMessage ex))))))

(deftest k5-l1-if-let-at-expression-position-rejected
  (testing "K5 L1 — `if let` at expression position is not yet supported. Reject with a targeted diagnostic pointing at F39 L7 (statement-form `if let`)."
    (let [ex (try
               (do-stmts-from "def main:\n    -> running\n        then:\n            let x = if let y = 1: 10 else: 20\n            print(x)\n")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is ex)
      (is (re-find #"K5 L1" (.getMessage ex)))
      (is (re-find #"F39 L7" (.getMessage ex))))))

(deftest f18-l1prime-landing-2-transition-let-clause-hard-rejected
  (testing "F18 L1' Landing 2 (2026-08-24) — the transition `let:` sub-block is hard-retired. Attempting to parse it fires a targeted rejection naming the successor (statement-form `let <name> = <expr>` inside `then:`) and the spec anchor (§2.5). Mirrors F70 I3's `reject-do-clause!` shape."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`let:` transition sub-block is retired"
         (p/parse "def X:\n    n: Int = 0\n\n    -> alive\n\n    alive <: bump -> alive\n        let: k: 5\n        then: n => n + k\n")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"let <name> = <expr>"
         (p/parse "def X:\n    n: Int = 0\n\n    -> alive\n\n    alive <: bump -> alive\n        let: k: 5\n        then: n => n + k\n")))))

(deftest f18-l1prime-landing-2-statement-form-let-in-then-clause-lands
  (testing "F18 L1' Landing 2 — the successor shape (statement-form `let x = expr` inside a `then:` clause) parses cleanly. Same visibility scope (bindings visible to subsequent `then:` stmts and field updates); no separate clause-order concern (let-stmt is a statement inside `then:` `:stmts`, not a distinct clause)."
    (let [n (first-node "def X:\n    n: Int = 0\n\n    -> alive\n\n    alive <: bump -> alive\n        then:\n            let k = 5\n            n => n + k\n")
          txns (filter #(= :transition (:node %)) (:body n))
          bump (first (filter #(get-in % [:receive :glyph]) txns))
          then-c (first (filter #(= :then-clause (:node %)) (:body bump)))
          let-stmt (first (filter #(= :let-stmt (:node %)) (:stmts then-c)))]
      (is (some? let-stmt) "let statement lands inside then: clause :stmts")
      (is (= "k" (:name let-stmt)))
      (is (= 5 (get-in let-stmt [:expr :value]))))))

(deftest f18-l1prime-landing-2-statement-form-let-with-type-annotation
  (testing "F18 L1' Landing 2 — statement-form `let name: Type = expr` (with explicit type) is legal alongside the type-inferred `let name = expr`. Both surfaces route through `parse-let-statement` (F18 L1). Verifies the migration path preserves both flavors; the retired sub-block only supported the bare form (`let: k: expr`)."
    (let [n (first-node "def X:\n    n: Int = 0\n\n    -> alive\n\n    alive <: bump -> alive\n        then:\n            let k: Int = 5\n            n => n + k\n")
          txns (filter #(= :transition (:node %)) (:body n))
          bump (first (filter #(get-in % [:receive :glyph]) txns))
          then-c (first (filter #(= :then-clause (:node %)) (:body bump)))
          let-stmt (first (filter #(= :let-stmt (:node %)) (:stmts then-c)))]
      (is (some? let-stmt))
      (is (= "k" (:name let-stmt)))
      (is (= "Int" (get-in let-stmt [:type :name])))
      (is (= 5 (get-in let-stmt [:expr :value]))))))

(deftest f18-l1prime-landing-2-inline-transition-let-clause-hard-rejected
  (testing "F18 L1' Landing 2 — the inline (single-line) shape `let: k: 5` also hard-rejects. Coverage for the second dispatch site in `parse-transition-body` (inline branch); pairs with the indented-body branch coverage above."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`let:` transition sub-block is retired"
         (p/parse "def X:\n    n: Int = 0\n\n    -> alive\n\n    alive <: bump -> alive\n        let: k: 5\n")))))

;; ============================================================================
;; F70 Surface Facelift — I3 / I5 / I6 regression tests
;; ============================================================================

(deftest f70-i5-parens-payload-parses-as-msg-shape
  (testing "F70 I5 — `(name: Type)` in postfix position (following a dotted source) parses as :msg-shape (payload destructure), not :call. Distinguished from a call by content: first arg value is a bare uppercase ident (a Type) not followed by `(`."
    (let [n (first-node "def A:\n    -> alive\n\n    alive <: input.pressed(key: Int) -> alive\n")
          t (first (filter #(and (= :transition (:node %))
                                 (some? (:receive %))) (:body n)))
          msg (get-in t [:receive :msg])]
      (is (= [{:name "key" :type {:node :type-ref :name "Int"}}]
             (:payload msg))
          "payload extracted from parens form"))))

(deftest f70-i5-parens-call-still-parses-as-call
  (testing "F70 I5 — `(name: value)` in postfix position still parses as :call with kwargs (value is not an uppercase-starting ident, so heuristic doesn't fire)."
    (let [n (first-node "def A:\n    -> alive\n        then: |> self.bump(by: 1)\n")
          t (first (filter #(= :transition (:node %)) (:body n)))
          then-c (first (:body t))
          emit-stmt (first (:stmts then-c))]
      (is (= :emit (:node emit-stmt)))
      (is (= "bump" (:msg emit-stmt))))))

(deftest f16-l3-parens-with-dotted-value-parses-as-call
  (testing "F16 L3 — `Box(d: Direction.down)` in expression position parses as :call with a keyword arg, NOT as a :msg-shape payload. The `looks-like-msg-payload-parens?` heuristic must exclude `.` after the type ident (parallel to its existing `(` exclusion for constructor calls like `Box(item: OldBox(x: 1))`) — otherwise the parser sees `d: Direction` and greedily commits to a :msg-shape payload, then fails on the trailing `.down`. Regression coverage for the enum-value first-class flow."
    (let [ns (p/parse "def Direction:\n    case up\n    case down\n\ndef Box:\n    d: Direction\n\ndef main:\n    -> running\n        then: print(Box(d: Direction.down).d)\n")
          main-atom (last ns)
          txn (first (:body main-atom))
          then-c (first (:body txn))
          print-call (get-in (first (:stmts then-c)) [:expr])
          ;; print( ((Box(d: Direction.down)).d) )
          box-dot (get-in print-call [:args 0 :value])
          box-call (:target box-dot)]
      (is (= :call (:node print-call)))
      (is (= "print" (get-in print-call [:callee :name])))
      (is (= :dot (:node box-dot)) "outer is dot access on the Box(...) result")
      (is (= "d" (:field box-dot)))
      (is (= :call (:node box-call)) "Box(d: Direction.down) is :call, not :msg-shape")
      (is (= "Box" (get-in box-call [:callee :name])))
      (let [box-arg (first (:args box-call))]
        (is (= :named (:kind box-arg)) "keyword arg kind")
        (is (= "d" (:name box-arg)))
        (is (= :dot (get-in box-arg [:value :node])) "arg value is a :dot on Direction")
        (is (= "down" (get-in box-arg [:value :field])))))))

(deftest f70-i5-parens-payload-parses-typed-payload
  (testing "F70 I5 (Stage 3, 2026-08-20) — canonical parens payload `(name: Type)` on a receive-side transition parses to `{:name :type}` maps under `:payload`. (Renamed from `f70-i5-brace-payload-legacy-still-parses` 2026-08-20: Stage 3 rejects the brace form; the test's input was already migrated to parens; the old name lied about what it tested. Rejection of the brace form is covered by a distinct Stage-3 diagnostic test.)"
    (let [n (first-node "def A:\n    -> alive\n\n    alive <: input.pressed(key: Int) -> alive\n")
          t (first (filter #(and (= :transition (:node %))
                                 (some? (:receive %))) (:body n)))
          msg (get-in t [:receive :msg])]
      (is (= [{:name "key" :type {:node :type-ref :name "Int"}}]
             (:payload msg))))))

(deftest f70-i6-prefix-send-parses
  (testing "F70 I6 — prefix `|> receiver.method(args)` parses as :emit with target=receiver, msg=method, args=args."
    (let [n (first-node "def main:\n    n: Int = 0\n    -> running\n        then: |> self.bump(by: 1)\n")
          t (first (filter #(= :transition (:node %)) (:body n)))
          then-c (first (:body t))
          emit-stmt (first (:stmts then-c))]
      (is (= :emit (:node emit-stmt)))
      (is (false? (:async? emit-stmt)))
      (is (= :self (get-in emit-stmt [:target :node])))
      (is (= "bump" (:msg emit-stmt)))
      (is (= [{:kind :named :name "by" :value {:node :lit-int :value 1}}]
             (:args emit-stmt))))))

(deftest f70-i6-prefix-send-no-args-empty-parens
  (testing "F70 I6 — prefix `|> receiver.method()` with empty parens parses correctly."
    (let [n (first-node "def main:\n    -> running\n        then: |> self.finish()\n")
          t (first (filter #(= :transition (:node %)) (:body n)))
          then-c (first (:body t))
          emit-stmt (first (:stmts then-c))]
      (is (= :emit (:node emit-stmt)))
      (is (= :self (get-in emit-stmt [:target :node])))
      (is (= "finish" (:msg emit-stmt)))
      (is (empty? (:args emit-stmt))))))

(deftest f70-i6-prefix-send-under-then-parses
  (testing "F70 I6 (Stage 3, 2026-08-20) — canonical prefix `|> receiver.msg(args)` inside a `then:` clause parses to `:emit`. Duplicate of `f70-i6-prefix-send-parses` above; kept because it exercises the specific `then:` embedding path (versus the standalone-in-block coverage). (Renamed from `f70-i6-binary-send-legacy-still-parses` 2026-08-20: Stage 3 rejects the legacy binary form; the test's input was already migrated to prefix; the old name lied about what it tested. Rejection of the legacy binary form is covered by a distinct Stage-3 diagnostic test.)"
    (let [n (first-node "def main:\n    -> running\n        then: |> self.bump(by: 1)\n")
          t (first (filter #(= :transition (:node %)) (:body n)))
          then-c (first (:body t))
          emit-stmt (first (:stmts then-c))]
      (is (= :emit (:node emit-stmt)))
      (is (= :self (get-in emit-stmt [:target :node])))
      (is (= "bump" (:msg emit-stmt))))))

(deftest f70-i3-then-accepts-sends-and-updates-mixed
  (testing "F70 I3 — `then:` accepts both `<field> => <expr>` updates and arbitrary statements (sends, prints). AST splits into `:updates` and `:stmts` bags."
    (let [n (first-node "def A:\n    n: Int = 0\n\n    -> alive\n\n    alive <: bump -> alive\n        then:\n            n => n + 1\n            :> notified\n")
          t (first (filter #(and (= :transition (:node %))
                                 (= "alive" (get-in % [:source :name])))
                            (:body n)))
          then-c (first (:body t))]
      (is (= :then-clause (:node then-c)))
      (is (= [{:field "n" :expr {:node :binop :op "+"
                                 :left {:node :ident :name "n"}
                                 :right {:node :lit-int :value 1}}}]
             (:updates then-c)))
      (is (= 1 (count (:stmts then-c))))
      (is (= :emit (:node (first (:stmts then-c))))))))

(deftest f70-i3-multi-then-clauses-allowed
  (testing "F70 I3 — multiple `then:` clauses per transition body are allowed (the then: → then: migration produces bodies with multiple then-blocks that merge at lowering)."
    (let [n (first-node "def A:\n    n: Int = 0\n\n    -> alive\n\n    alive <: bump -> alive\n        then: n => n + 1\n        then: :> notified\n")
          t (first (filter #(and (= :transition (:node %))
                                 (= "alive" (get-in % [:source :name])))
                            (:body n)))
          then-cs (filter #(= :then-clause (:node %)) (:body t))]
      (is (= 2 (count then-cs))))))

(deftest f70-i3-stage3-do-clause-rejected
  (testing "F70 I3 Stage 3 (2026-08-19) — the `do:` transition sub-block is retired. Parser emits a targeted diagnostic pointing at the `then:` migration; the diagnostic explains that `then:` accepts side-effect statements alongside field updates."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`do:` transition sub-block is retired"
         (p/parse "def A:\n    n: Int = 0\n\n    -> alive\n\n    alive <: bump -> alive\n        do: :> notified\n")))))

(deftest f70-i5-stage3-brace-payload-rejected
  (testing "F70 I5 Stage 3 (2026-08-19) — message-payload braces `{name: Type}` are retired. Parser emits a targeted diagnostic pointing at the parens form; content-dispatch keeps payload-parens distinguishable from call-parens by first-token shape."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"message-payload braces .* are retired"
         (p/parse "def A:\n    -> alive\n\n    alive <: bump{by: Int} -> alive\n        then: n => n + by\n")))))

(deftest f70-i6-stage3-postfix-pipe-rejected
  (testing "F70 I6 Stage 3 (2026-08-19) — the binary postfix `receiver |> msg(args)` is retired. Parser emits a targeted diagnostic pointing at the prefix form `|> receiver.msg(args)`."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"binary postfix `receiver \|> msg\(args\)` is retired"
         (p/parse "def A:\n    -> running\n        then: self |> bump(by: 1)\n")))))

;; ---------------------------------------------------------------------------
;; F8 L3a — `computed:` behavioral section. Field-shape decls inside
;; `computed:` rewrite to zero-arg observables at parse time — the
;; field_getter generator per F8 plan §Landing 3a. Users get field-style
;; declarative ergonomics; the compiler treats reads as observable
;; dispatches (cross-atom reads recompute every access, per spec
;; §4.10.4.1 Computed contract).
;; ---------------------------------------------------------------------------

(deftest f8-l3a-computed-field-shape-rewrites-to-observable
  (testing "F8 L3a — `computed: doubled: Int = n * 2` inside a class body rewrites to `doubled() :< n * 2` at parse time. The transient :computed-section wrapper is spliced away by fold-top-level-fields."
    (let [n   (first-node "def V:\n    n: Int\n\n    computed:\n        doubled: Int = n * 2\n")
          obs (first (filter #(= :observable (:node %)) (:sections n)))]
      (is (some? obs) "the field-shape decl inside `computed:` surfaces as an observable")
      (is (= "doubled" (:name obs)))
      (is (empty? (:params obs))
          "computed fields are zero-arg — parametric readers use the top-level `name(args) :< expr` form")
      (is (= :binop (get-in obs [:expr :node])))
      (is (= "*"    (get-in obs [:expr :op])))
      (is (not (some #(= :computed-section (:node %)) (:sections n)))
          "no stray :computed-section survives after fold-top-level-fields"))))

(deftest f8-l3a-computed-observable-shape-passes-through
  (testing "F8 L3a — `computed:` also accepts observable-shape decls (`name :< expr`) for parity with top-level observables. Distinguishes MVP scope (both shapes accepted, both flatten to observables) from a full section-class generator run."
    (let [n   (first-node "def V:\n    n: Int\n\n    computed:\n        tripled :< n * 3\n")
          obs (first (filter #(and (= :observable (:node %))
                                   (= "tripled" (:name %)))
                             (:sections n)))]
      (is (some? obs) "observable-shape decl inside `computed:` surfaces unchanged")
      (is (empty? (:params obs))))))

(deftest f8-l3a-computed-field-missing-default-rejected
  (testing "F8 L3a — a computed field with no `= <expression>` is a hard error at parse time; the expression IS the field's derivation. Prevents a silently-broken observable body."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F8 L3a: computed field `broken` must have a `= <expression>`"
         (p/parse "def V:\n    n: Int\n\n    computed:\n        broken: Int\n")))))

(deftest f8-l3a-computed-splices-in-declaration-order
  (testing "F8 L3a — multiple computed fields survive as multiple observables in declaration order. The fold splices at the section's position, preserving the visual grouping intent while the AST stays flat for downstream lowering."
    (let [n   (first-node "def V:\n    n: Int\n\n    computed:\n        d: Int = n * 2\n        t: Int = n * 3\n")
          obs (filterv #(= :observable (:node %)) (:sections n))]
      (is (= ["d" "t"] (mapv :name obs))))))

(deftest f8-l3a-computed-parametric-observable-shape-passes-through
  (testing "F8 L3a — parametric computed fields via the observable-shape (`name(params) :< expr`) pass through unchanged. The plan's MVP-scope note on 'parametric computed fields excluded' applies only to the field-shape sugar (`name(args): T = expr`) — not to the observable-shape which parse-observable already handles."
    (let [n   (first-node "def V:\n    n: Int\n\n    computed:\n        scaled(k: Int) :< n * k\n")
          obs (first (filter #(and (= :observable (:node %))
                                   (= "scaled" (:name %)))
                             (:sections n)))]
      (is (some? obs) "parametric observable-shape survives inside `computed:`")
      (is (= 1 (count (:params obs)))
          "parametric params flow through — one user param `k`")
      (is (= "k" (get-in obs [:params 0 :name]))))))
