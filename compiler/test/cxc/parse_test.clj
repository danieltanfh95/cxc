(ns cxc.parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.parse :as p]))

(defn- first-node [source]
  (first (p/parse source)))

(defn- node-kind [n] (:node n))

;; ---------------------------------------------------------------------------
;; Annotation grammar retired 2026-08-30 (annotation-retirement wave).
;; L3a `@name(args)` + Q10-D `@plugin(...)` on module both retire.
;; Plugin manifests: interim landed on `let plugin: Plugin = Plugin(...)`
;; module-let (2026-08-30), then reshaped to `def plugin:` record marker
;; (2026-08-31, plugin-abi-shape wave) with module-let retiring universally.
;; Kind info via body-shape inference (§3.3); deprecation retires as
;; language surface (express as comment or convention).
;; ---------------------------------------------------------------------------

(deftest annotation-grammar-retired
  (testing "annotation-grammar-retirement (2026-08-30) — `@name(args)` at top level rejects with a targeted migration pointer"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"annotation grammar .*retired at v1.0"
         (p/parse "@deprecated(\"msg\")\ndef X:\n    value: Int\n")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"annotation grammar .*retired at v1.0"
         (p/parse "@metadata(kind: \"class\")\ndef X:\n    value: Int\n")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"annotation grammar .*retired at v1.0"
         (p/parse "@plugin(name: \"x\")\nmodule com.acme.foo\n")))))

(deftest annotation-grammar-retired-in-def-body
  (testing "annotation-grammar-retirement — `@name(...)` inside def body rejects with the same migration pointer"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"annotation grammar .*retired at v1.0"
         (p/parse "def X:\n    @metadata(kind: \"class\")\n    value: Int\n")))))

(deftest module-decl-retired
  (testing "F73 (2026-08-20) — the `module <name>` header is retired. Module identity is the file path relative to the source root; the resolver walks the tree. Parser rejects with a migration diagnostic instead of producing a `:module` node."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`module <name>` declaration is retired"
         (p/parse "module com.example.foo\n")))))

(deftest f7-alias-declaration-retired
  (testing "L6 — F7 declarations no longer have a top-level parser entry"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"expected top-level form"
         (p/parse "alias UserID: Int\n")))))

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

    plus(other: Vector2) :- Vector2(x: self.x + other.x, y: self.y + other.y)
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
    (let [n   (first-node "def R:\n    f() :- self.x + other.x\n")
          obs (first (filter #(= :observable (:node %)) (:sections n)))
          expr (:expr obs)]
      (is (= :binop (:node expr)))
      (is (= "+" (:op expr)))
      (is (= :dot (get-in expr [:left :node])))
      (is (= :self (get-in expr [:left :target :node])))
      (is (= "x" (get-in expr [:left :field]))))))

(deftest expr-nested-call-with-named-args
  (let [n   (first-node "def R:\n    f() :- V(x: 1, y: 2)\n")
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

(deftest def-atom-with-direct-use-data-invariants
  (testing "multiple direct use lines normalize to one downstream-stable use section"
    (let [n (first-node "def A:
    use cap: Capability
    use other_cap: OtherCapability

    x: Int = 0

    |: x >= 0

    obs :- x

    -> idle then: x => 0
")]
      (is (= :def-atom (:node n)))
      (is (= "A" (:name n)))
      (let [kinds (mapv :node (:body n))
            caps  (:caps (first (:body n)))]
        (is (= [:use-section :data-section :proposition :observable :transition] kinds))
        (is (= ["cap" "other_cap"] (mapv :name caps)))
        (is (= ["Capability" "OtherCapability"]
               (mapv #(get-in % [:type :name]) caps))))))
  (testing "the legacy use section header is hard-retired"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`use:` section header is retired"
         (first-node "def A:\n    use:\n        cap: Capability\n")))))

(deftest transition-receive-with-arm-proposition-and-body
  (let [n (first-node "def A:
    alive <: attack(amount: Int)
    -> alive
        |: hp > amount
        then: hp => hp - amount
")
        t (-> n :body first)]
    (is (= :transition (:node t)))
    (is (= :phase (get-in t [:source :kind])))
    (is (= "alive" (get-in t [:source :name])))
    (is (= "<:" (get-in t [:receive :glyph])))
    (is (= "attack" (get-in t [:receive :msg :source :name])))
    (is (= "alive" (:target t)))
    (is (not (contains? t :when)))
    (is (= 2 (count (:body t))))
    (is (= :proposition (:node (first (:body t)))))
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

(deftest game-toys-parses-under-v1-surface
  (testing "L14/P9 completion pin — game-toys design sample has been migrated to the v1.0 consolidated surface (arm-head `|:`, `use <name>: <T>` keyword lines, no retired section headers). The sample must parse cleanly; any regression that reintroduces retired syntax in the fixture fails here."
    (let [src (slurp "../design/examples/game-toys.cxc")]
      (is (some? (p/parse src))
          "the migrated game-toys sample must parse under v1.0 surface"))))

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

(deftest wildcard-unification-underscore-in-case-pattern-retired
  (testing "2026-08-26 wildcard unification — `_` in `<:` case-pattern position is retired; `*` is now the uniform wildcard symbol. Users get a parse-time migration hint pointing at the rewrite."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"wildcard case-pattern is now `\*`"
         (p/parse "def Signal:\n    case red\n    case green\n\ndef main:\n    -> running\n        then:\n            let s: Signal = Signal.red\n            s:\n                <: red: print(1)\n                <: _: print(9)\n")))))

(deftest wildcard-unification-star-target-retired
  (testing "2026-08-26 wildcard unification — `-> *` transition target is retired; `-> self` replaces it. Target-`*` was self-reference (stay in current phase), not wildcard-match; unifying `*` as wildcard-only removes the overload. Users get a parse-time migration hint."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"transition target `\*` is retired"
         (p/parse "def A:\n    n: Int = 0\n\n    -> running\n\n    * <: tick(msg: Int) -> *\n        then: return n\n")))))

(deftest wildcard-unification-star-in-case-pattern-parses
  (testing "2026-08-26 wildcard unification — `<: *:` parses as a wildcard case pattern (was `<: _:` pre-unification). Same `:kind :wildcard` AST node; only the surface symbol changed."
    (let [ast (p/parse "def Signal:\n    case red\n    case green\n\ndef main:\n    -> running\n        then:\n            let s: Signal = Signal.red\n            s:\n                <: red: print(1)\n                <: green: print(2)\n                <: *: print(9)\n")
          main-atom (first (filter #(and (= :def-atom (:node %))
                                         (= "main" (:name %))) ast))
          transition (first (filter #(= :transition (:node %))
                                    (:body main-atom)))
          then-clause (first (filter #(= :then-clause (:node %))
                                     (:body transition)))
          dispatch (first (filter #(= :dispatch (:node %))
                                  (:stmts then-clause)))
          arms (:arms dispatch)
          wildcard-arm (last arms)]
      (is (= :dispatch (:node dispatch)))
      (is (= :wildcard (get-in wildcard-arm [:pattern :kind]))))))

(deftest f69-l4-dispatch-parses-cases-and-wildcard
  (testing "F69 L4 — `<:` receive-glyph dispatch block at statement position parses to a `:dispatch` node with a scrutinee and an arms vector. Case arms carry `:kind :case` patterns; the wildcard arm carries `:kind :wildcard`."
    (let [ast (p/parse "def Signal:\n    case red\n    case green\n\ndef main:\n    -> running\n        then:\n            let s: Signal = Signal.red\n            s:\n                <: red: print(1)\n                <: green: print(2)\n                <: *: print(9)\n")
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
    (let [ast (p/parse "def Outcome:\n    case ok\n    case err\n\ndef main:\n    -> running\n        then:\n            let o: Outcome = Outcome.ok\n            o:\n                <: ok(let x): print(1)\n                <: *: print(0)\n")]
      (is (some? ast)))))

(deftest state-section-retired-in-def-atom
  (testing "`state:` section in a def-atom body is retired at v1.0 (class-body-collapse F67 L5). Parser emits a targeted migration diagnostic pointing at top-level field declarations instead of a generic fall-through error. Message updated 2026-08-30 to reflect `data:` header also being retired."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`state:` section header is retired at v1.0"
         (p/parse "def main:\n    state:\n        count: Int = 0\n")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"class-body-collapse F67 L5"
         (p/parse "def main:\n    state:\n        count: Int = 0\n")))))

(deftest state-section-retired-in-record
  (testing "`state:` section in a record body is also retired — records are immutable, all fields sit at def-body top level with no wrapping header."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`state:` section header is retired at v1.0"
         (p/parse "def V:\n    state:\n        x: Float\n")))))

(deftest f8-l3c-metadata-section-remains-retired-after-annotation-retirement
  (testing "L3c HARD RETIRE — metadata: has no targeted or generic section shim; @metadata annotation also retired (annotation-grammar-retirement 2026-08-30). Kind info now via body-shape inference (§3.3)."
    (is (thrown?
         clojure.lang.ExceptionInfo
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
        |: -10 <= val <= 10
        then: val => val + 1
")
          prop-expr (some (fn [n]
                            (some (fn [t]
                                    (some (fn [c] (when (= :proposition (:node c))
                                                    (:predicate c)))
                                          (:body t)))
                                  (filter #(= :transition (:node %)) (:body n))))
                          tree)]
      (is (= :chained-cmp (:node prop-expr)))
      (is (= 3 (count (:operands prop-expr))))
      (is (= ["<=" "<="] (:ops prop-expr))))
    ;; Middle is a call — accepted (semantic once-only makes the
    ;; former double-eval concern moot).
    (is (some? (p/parse "def V:
    x: Int

    mid() :- self.x

def main:
    v: V = V(x: 5)

    -> running

    running <: check -> self
        |: 0 <= v.mid() <= 10
        then: v => v
")))
    ;; 4-operand chain: -10 <= a <= b <= 10 → :chained-cmp with
    ;; 4 operands + 3 ops (fold-into on the postwalk pass).
    (let [tree (p/parse "def main:
    a: Int = 0
    b: Int = 5

    -> running

    running <: check -> self
        |: -10 <= a <= b <= 10
        then: a => a
")
          prop-expr (some (fn [n]
                            (some (fn [t]
                                    (some (fn [c] (when (= :proposition (:node c))
                                                    (:predicate c)))
                                          (:body t)))
                                  (filter #(= :transition (:node %)) (:body n))))
                          tree)]
      (is (= :chained-cmp (:node prop-expr)))
      (is (= 4 (count (:operands prop-expr))))
      (is (= ["<=" "<=" "<="] (:ops prop-expr))))))

(deftest transition-when-guards-hard-retired
  (testing "D3/L2c — transition `when:` guards are rejected at both legacy positions"
    (is (thrown?
         clojure.lang.ExceptionInfo
         (p/parse "def main:
    n: Int = 0

    -> running

    running <: bump when: n < 10 -> self
        then: n => n + 1
")))
    (is (thrown?
         clojure.lang.ExceptionInfo
         (p/parse "def main:
    n: Int = 0

    -> running

    running <: bump -> self
        when: n < 10
        then: n => n + 1
")))))

(deftest transition-arm-proposition-placement-and-multiplicity
  (testing "arm-head propositions must precede `then:`"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"arm-head `\|:` proposition must appear before `then:`"
         (p/parse "def main:
    n: Int = 0

    -> running

    running <: bump -> self
        then: n => n + 1
        |: n < 10
"))))
  (testing "propositions and `then:` clauses may each repeat"
    (let [n (first-node "def main:
    n: Int = 0

    -> running

    running <: bump -> self
        |: n < 10
        |: n >= 0
        then: n => n + 1
        then: n => n + 2
")
          body (:body (first (filter #(and (= :transition (:node %))
                                          (= :phase (get-in % [:source :kind])))
                                    (:body n))))]
      (is (= [:proposition :proposition :then-clause :then-clause]
             (mapv :node body))))))

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
    (let [src "def R:\n    xs: List of Int\n\n    head() :- xs[0]\n"
          rec (first-node src)
          obs (first (filter #(= :observable (:node %)) (:sections rec)))]
      (is (= :subscript (:node (:expr obs))))
      (is (= "xs" (get-in obs [:expr :target :name])))
      (is (= :lit-int (get-in obs [:expr :index :node])))
      (is (= 0 (get-in obs [:expr :index :value]))))))

(deftest subscript-chains-with-other-postfix
  (testing "F19 — subscript composes with `.field` and `(args)` in any order. Post F67 L4: observable expression body."
    (let [src "def R:\n    xs: List of Int\n\n    z() :- xs[0].bar(1)\n"
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
  (testing "F67 L3 — the legacy `-> foo(args):` handler-decl sugar is retired at parse time. Users get a diagnostic naming the retired form and pointing at the explicit handler-shape transition `* <: foo(args) -> self then: ...` (spec §1265 semantics preserved)."
    (let [src "def A:\n    n: Int = 0\n\n    -> running\n\n    -> tick(msg: Int):\n        return n\n"]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"F67 L3.*handler-decl sugar is retired"
           (first-node src))))))

(deftest f67-l3-wildcard-handler-transition-parses
  (testing "F67 L3 — the migration target `* <: name(args) -> self then: ...` parses as a :transition with wildcard source, wildcard target, and typed :params on the receive-msg (distinguishing it from the payload-destructure `msg{payload}` form)."
    (let [src "def A:\n    n: Int = 0\n\n    -> running\n\n    * <: tick(msg: Int) -> self\n        then: return n\n"
          atom-node (first-node src)
          t (last (filter #(= :transition (:node %)) (:body atom-node)))]
      (is (= :def-atom (:node atom-node)))
      (is (= :wildcard (get-in t [:source :kind])))
      (is (= "self" (:target t)))
      (is (= "<:" (get-in t [:receive :glyph])))
      (is (= "tick" (get-in t [:receive :msg :source :name])))
      (is (= 1 (count (get-in t [:receive :msg :params]))))
      (is (= "msg" (get-in t [:receive :msg :params 0 :name])))
      (is (= "Int" (get-in t [:receive :msg :params 0 :type :name]))))))

(deftest f67-l3-wildcard-handler-zero-args-parses
  (testing "F67 L3 — zero-arg handler-shape `* <: tick() -> self` parses with empty :params (still distinguished from a payload-shape receive)."
    (let [src "def A:\n    n: Int = 0\n\n    -> running\n\n    * <: tick() -> self\n        then: return n\n"
          atom-node (first-node src)
          t (last (filter #(= :transition (:node %)) (:body atom-node)))]
      (is (some? t))
      (is (= :wildcard (get-in t [:source :kind])))
      (is (= "self" (:target t)))
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

(deftest l5-def-with-sync-signatures-reshapes-to-protocol
  (testing "L5 — a def containing sync observation signatures reshapes into `:class :kind :protocol`, preserving bodyless signature AST nodes for protocol env-build."
    (let [n (first-node (str "def Iterable:\n"
                             "    has_next() :- Bool\n"
                             "    next() :- Element\n"))
          signatures (:body n)]
      (is (= :class (:node n)))
      (is (= :protocol (:kind n)))
      (is (= "Iterable" (:name n)))
      (is (= [:observation-signature :observation-signature]
             (mapv :node signatures)))
      (is (= ["has_next" "next"] (mapv :name signatures)))
      (is (= ["Bool" "Element"]
             (mapv #(get-in % [:return-type :name]) signatures))))))

(deftest l5-def-with-async-signature-reshapes-to-protocol
  (testing "L5 — a leading `<:` declaration with no source phase or arrow is an async receive signature and infers protocol kind."
    (let [n (first-node (str "def Logger:\n"
                             "    <: warn(msg: String)\n"))
          sig (first (:body n))]
      (is (= :class (:node n)))
      (is (= :protocol (:kind n)))
      (is (= :receive-signature (:node sig)))
      (is (= "warn" (:name sig)))
      (is (= "String" (get-in sig [:params 0 :type :name]))))))

(deftest f68-l1-def-protocol-with-ctor-params-rejected
  (testing "L5 — `def Foo(...)` with signature-only members is rejected at parse — protocols declare no constructor state."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"F68 L1.*ctor params.*protocols declare signatures only"
         (first-node (str "def Iterable(x: Int):\n"
                          "    has_next() :- Bool\n"))))))

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
                  "    bar() :- 1\n"))]
      (is (= :extend-class (:node n)))
      (is (= "Foo" (:target n)))
      (let [obs (first (:body n))]
        (is (= :observable (:node obs)))
        (is (= "bar" (:name obs)))))))

(deftest f68-l3-extend-with-header-conforms-no-class-word
  (testing "F68 L3 — `extend Foo(NewProtocol):` (short form + header-parens conformance) parses to `:extend-class` with `:conforms` populated."
    (let [n (first-node
             (str "extend Foo(NewProtocol):\n"
                  "    bar() :- 3\n"))]
      (is (= :extend-class (:node n)))
      (is (= "Foo" (:target n)))
      (is (= [{:node :type-ref :name "NewProtocol"}]
             (:conforms n))))))

;; ---------------------------------------------------------------------------
;; L5 — signature-only defs are the sole protocol declaration surface.
;; ---------------------------------------------------------------------------

(deftest l5-sync-and-async-protocol-signatures-parse
  (testing "L5 — sync `op(args) :- T` and async `<: name(args)` declarations coexist in a signature-only protocol body."
    (let [n (first-node
             (str "def P:\n"
                  "    foo(x: Int) :- Int\n"
                  "    <: bar(s: String)\n"))
          [sync-sig async-sig] (:body n)]
      (is (= :class    (:node n)))
      (is (= :protocol (:kind n)))
      (is (= :observation-signature (:node sync-sig)))
      (is (= "Int" (get-in sync-sig [:return-type :name])))
      (is (= :receive-signature (:node async-sig)))
      (is (nil? (:return-type async-sig)))))

  (testing "L5 — protocol signatures cannot be mixed with concrete state."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"signature-only protocol.*mixes protocol declarations"
         (first-node (str "def Bad:\n"
                          "    op() :- Int\n"
                          "    n: Int = 0\n"))))))

(deftest l5-accepts-surface-hard-retired
  (testing "L5 / D3 — both the `accepts:` header and `accepts name(...)` body dispatch hard-fail with the signature-only migration."
    (doseq [src [(str "def P:\n"
                      "    accepts:\n"
                      "        foo(x: Int) -> Int\n")
                 (str "def P:\n"
                      "    accepts foo(x: Int) -> Int\n")]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"are retired at v1.0"
           (first-node src))))))

;; ---------------------------------------------------------------------------
;; F9 Landing 3 — `extend Foo:` parsing + strict body dispatch;
;; `replace` method modifier; target-resolution deferred to lower.
;; ---------------------------------------------------------------------------

(deftest f9-l3-extend-class-with-observable
  (testing "F9 Landing 3 + F67 L4 — `extend Foo:` accepts observables (Proposal G reader surface). Extensions land in `:extend-class` node carrying target name and body sections."
    (let [n (first-node
             (str "extend Foo:\n"
                  "    bar() :- 1\n"))]
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
                  "    replace bar() :- 2\n"))]
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
                  "    replace * <: reset() -> self then: print(0)\n"))]
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
               "    replace bumped() :- n + 1\n"))))))

(deftest f9-l3-extend-class-with-header-conforms
  (testing "F9 Landing 3 — `extend Foo(NewProtocol):` populates `:conforms` from header parens."
    (let [n (first-node
             (str "extend Foo(NewProtocol):\n"
                  "    bar() :- 3\n"))]
      (is (= [{:node :type-ref :name "NewProtocol"}]
             (:conforms n))))))

(deftest f9-l3-extend-class-with-body-conforms
  (testing "F9 Landing 3 — body-form `conforms: P` inside `extend class` extracted into `:conforms` and dropped from :body."
    (let [n (first-node
             (str "extend Foo:\n"
                  "    conforms: NewProtocol\n"
                  "    bar() :- 4\n"))]
      (is (= [{:node :type-ref :name "NewProtocol"}]
             (:conforms n)))
      (is (= [:observable] (mapv :node (:body n)))))))

(deftest f9-l3-extend-class-with-invariants
  (testing "L2b — own-line `|:` propositions are legal inside `extend class` (extensions may add invariants per §3.3.2)."
    (let [n (first-node
             (str "extend Foo:\n"
                  "    |: x > 0\n"))]
      (is (= :proposition (:node (first (:body n)))))
      (is (= ">" (get-in n [:body 0 :predicate :op]))))))

(deftest l2b-invariants-section-header-is-hard-retired
  (testing "L2b/D3 — the legacy `invariants:` header has no parser fallback AND emits a targeted migration diagnostic at both def-body and extend-body positions (2026-08-30 audit follow-on)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`invariants:` section header is retired at v1.0"
         (p/parse "def A:\n    invariants:\n        ready\n")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"four-position `\|:` unification"
         (p/parse "def A:\n    invariants:\n        ready\n")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`invariants:` section header is retired at v1.0"
         (p/parse "extend A:\n    invariants:\n        ready\n")))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Extension propositions compose additively"
         (p/parse "extend A:\n    invariants:\n        ready\n")))))

;; Q10-D (2026-08-30) `@plugin(...)` on `module <name>` retired same-day as
;; part of the annotation-grammar-retirement wave. Plugin manifest interim
;; landed on `let plugin: Plugin = Plugin(...)` at module-let scope; that
;; form retired 2026-08-31 (plugin-abi-shape wave) in favor of the
;; `def plugin:` record marker. The `module <name>` header is unconditionally
;; retired — F73 stands with no carve-out. Coverage for the retirement lives
;; in `annotation-grammar-retired` above and `module-decl-retired` earlier.

(deftest module-decl-unconditionally-retired-post-q10d-reversal
  (testing "annotation-grammar-retirement (2026-08-30) — Q10-D carve-out reversed; `module <name>` rejects unconditionally (F73 2026-08-20 stands with no exception)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`module <name>` declaration is retired"
         (p/parse "module com.example.plain\n")))))

(deftest plugin-marker-as-def-plugin-record
  (testing "plugin-abi-shape (2026-08-31) — plugin manifest is expressed as `def plugin:` — a fields-only body at module top level with the reserved name `plugin`. Field defaults are compile-time constants read by the F75 introspection pass. Parses cleanly via body-shape inference (§3.3); the reserved-name discipline + required-field schema are enforced by the F75 pass (landing separately). Supersedes the interim 2026-08-30 `let plugin: Plugin = Plugin(...)` module-let form."
    (let [src (str "def plugin:\n"
                   "    name:                  String! = \"test-plugin\"\n"
                   "    version_major:         Int!    = 0\n"
                   "    version_minor:         Int!    = 1\n"
                   "    version_patch:         Int!    = 0\n"
                   "    cxc_compat_min_major:  Int!    = 1\n"
                   "    cxc_compat_min_minor:  Int!    = 0\n"
                   "    cxc_compat_min_patch:  Int!    = 0\n"
                   "    cxc_compat_max_major:  Int!    = 2\n"
                   "    cxc_compat_max_minor:  Int!    = 0\n"
                   "    cxc_compat_max_patch:  Int!    = 0\n")
          nodes (p/parse src)
          plugin-def (first (filter #(= "plugin" (:name %)) nodes))]
      (is (some? plugin-def)
          "plugin marker parsed as top-level def with name \"plugin\"")
      (is (= :def-plugin (:node plugin-def))
          "PR-3 (2026-09-02): reshapes to `:def-plugin` — the reserved-name marker for R7 plugin manifests. F75 introspection consumes this node kind.")
      (is (vector? (:fields plugin-def))
          ":def-plugin carries a :fields vector (folded from the data-section) for F75 to inspect."))))

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

;; L3c — user metadata belongs in `@metadata(...)` above the original def.
;; A legacy `metadata:` header inside an extension has no section backend and
;; therefore reaches the ordinary extension-body parse error.

(deftest f9-l3-extend-class-rejects-use
  (testing "L4/F9 Landing 3 — direct `use <name>: <Type>` inside `extend` fails at parse (extensions cannot add capabilities)."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`use <name>: <Type>` is not permitted inside `extend"
         (first-node
          (str "extend Foo:\n"
               "    use clock: Clock\n"))))))

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
  (testing "F17 L1 — `break <expr>` is rejected. Value-yielding loops (F17 L2) were rejected 2026-08-18 as redundant with actor state transitions; the diagnostic steers users at the self-transition + arm-head `|:` proposition pattern instead."
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
;; L2a/L2b/L2c — `|:` proposition parser. All four valid placements
;; normalize to :proposition; L2b hard-retires the legacy invariant header
;; and L2c hard-retires transition `when:` guards.
;; ---------------------------------------------------------------------------

(deftest l2a-def-body-proposition-parses-as-class-level
  (testing "a def-body `|:` is retained as a class-level proposition in declaration order"
    (let [n    (first-node "def Counter:\n    n: Int = 0\n\n    |: n >= 0\n\n    -> ready\n")
          prop (first (filter #(= :proposition (:node %)) (:body n)))]
      (is (= :def-atom (:node n)))
      (is (= [:data-section :proposition :transition]
             (mapv :node (:body n))))
      (is (= ">=" (get-in prop [:predicate :op]))))))

(deftest l2a-arm-body-proposition-parses-before-then
  (testing "an arm-body `|:` is an ordered selector before its then clause"
    (let [n    (first-node "def Counter:\n    n: Int = 0\n\n    ready <: bump(by: Int) -> ready\n        |: by > 0\n        then: n => n + by\n")
          body (:body (first (filter #(= :transition (:node %)) (:body n))))]
      (is (= [:proposition :then-clause] (mapv :node body)))
      (is (= "by" (get-in body [0 :predicate :left :name]))))))

(deftest l2a-then-body-proposition-parses-as-post-statement-assertion
  (testing "a `|:` inside then stays in the ordered statement bag at its execution position"
    (let [n      (first-node "def Counter:\n    n: Int = 0\n\n    ready <: bump(by: Int) -> ready\n        then:\n            let checked = by\n            |: checked > 0\n            n => n + checked\n")
          clause (first (:body (first (filter #(= :transition (:node %))
                                             (:body n)))))]
      (is (= :then-clause (:node clause)))
      (is (= [:let-stmt :proposition] (mapv :node (:stmts clause))))
      (is (= "checked" (get-in clause [:stmts 1 :predicate :left :name]))))))

(deftest l2a-observation-body-proposition-parses-as-scoped-bound
  (testing "an observation-body `|: T: Bound` attaches to the explicit-return observable"
    (let [n    (first-node "def Pipeline:\n    total: Int = 0\n\n    sum :- Out\n        |: Out: Addable\n    = self.total\n")
          obs  (first (filter #(= :observable (:node %)) (:sections n)))
          prop (first (:propositions obs))]
      (is (= "Out" (get-in obs [:return-type :name])))
      (is (= :proposition (:node prop)))
      (is (= :type-bound (get-in prop [:predicate :node])))
      (is (= "Addable" (get-in prop [:predicate :bound :name]))))))

(deftest l2a-proposition-own-line-rule-rejects-crammed-form
  (testing "`|:` cannot share an observation signature/expression line"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"`\|:` proposition must occupy its own line"
         (p/parse "def A:\n    foo :- bar |: pred = expr\n")))))

(deftest l2a-typed-proposition-attaches-error-type-and-args
  (testing "`as ErrorType(field: value)` normalizes its type and named argument map"
    (let [n    (first-node "def Counter:\n    n: Int = 0\n\n    |: n >= 0 as OutOfRange(value: n)\n\n    -> ready\n")
          prop (first (filter #(= :proposition (:node %)) (:body n)))]
      (is (= {:node :type-ref :name "OutOfRange"} (:as-error-type prop)))
      (is (= #{"value"} (set (keys (:as-error-args prop)))))
      (is (= {:node :ident :name "n"}
             (get-in prop [:as-error-args "value"]))))))

(deftest l2a-bare-proposition-has-nil-typed-error-fields
  (testing "a bare proposition emits the same normalized shape with nil typed-error slots"
    (let [n    (first-node "def Counter:\n    |: ready\n    -> ready\n")
          prop (first (:body n))]
      (is (= :proposition (:node prop)))
      (is (nil? (:as-error-type prop)))
      (is (nil? (:as-error-args prop))))))

;; ---------------------------------------------------------------------------
;; L8 (2026-08-25) — `computed:` section header retired. The direct
;; observable syntax (`name :- expr` at def-body position) is the
;; terminal shape; the section header was parse-time sugar and has
;; been removed.
;; ---------------------------------------------------------------------------

(deftest l8-computed-section-header-retired
  (testing "L8 — `computed:` as a body-section header is retired; the parser rejects it via the generic body-item dispatch."
    (is (thrown? clojure.lang.ExceptionInfo
                 (p/parse "def V:\n    n: Int\n\n    computed:\n        doubled: Int = n * 2\n")))))

(deftest l8-direct-observable-parses-at-def-body
  (testing "L8 — the direct observable spelling (`name :- expr`) replaces `computed:` for zero-arg computed fields at def-body position."
    (let [n   (first-node "def V:\n    n: Int\n\n    doubled :- n * 2\n")
          obs (first (filter #(and (= :observable (:node %))
                                   (= "doubled" (:name %)))
                             (:sections n)))]
      (is (some? obs) "direct observable surfaces at def-body position")
      (is (empty? (:params obs)))
      (is (= :binop (get-in obs [:expr :node])))
      (is (= "*"    (get-in obs [:expr :op]))))))

(deftest l8-multiple-direct-observables-preserve-order
  (testing "L8 — multiple direct observables at def-body position preserve declaration order."
    (let [n   (first-node "def V:\n    n: Int\n\n    d :- n * 2\n    t :- n * 3\n")
          obs (filterv #(= :observable (:node %)) (:sections n))]
      (is (= ["d" "t"] (mapv :name obs))))))

(deftest l8-parametric-observable-parses-at-def-body
  (testing "L8 — parametric observables (`name(params) :- expr`) parse at def-body position unchanged from prior support."
    (let [n   (first-node "def V:\n    n: Int\n\n    scaled(k: Int) :- n * k\n")
          obs (first (filter #(and (= :observable (:node %))
                                   (= "scaled" (:name %)))
                             (:sections n)))]
      (is (some? obs))
      (is (= 1 (count (:params obs))))
      (is (= "k" (get-in obs [:params 0 :name]))))))

;; ---------------------------------------------------------------------------
;; R6 (2026-08-31, decisions-frozen-2026-08-31-glyphs.md) — expression-position
;; `||: <guard>: <body>` multi-arm dispatch. First truthy guard's body is the
;; expression value. `else` reserved as sole catch-all guard marker. Chains
;; either inline (`||: g1 : b1 ||: g2 : b2 ||: else : bN`) or block
;; (subsequent arms follow a NEWLINE + optional INDENT).
;; ---------------------------------------------------------------------------

(defn- observable-expr [source name-str]
  (let [n   (first-node source)
        obs (first (filter #(and (= :observable (:node %))
                                 (= name-str (:name %)))
                           (:sections n)))]
    (:expr obs)))

(deftest r6-multi-arm-dispatch-inline-parses
  (testing "R6 — inline `||: g : b ||: else : bN` chain parses as :multi-arm-dispatch"
    (let [ex (observable-expr "def F:\n    bar :- ||: 1 > 0 : 1 ||: else : 0\n" "bar")]
      (is (= :multi-arm-dispatch (:node ex)))
      (is (= 2 (count (:arms ex))))
      (is (= :binop        (get-in ex [:arms 0 :guard :node])))
      (is (= :else-marker  (get-in ex [:arms 1 :guard :node]))))))

(deftest r6-multi-arm-dispatch-block-parses
  (testing "R6 — block form (arms on subsequent indented lines) parses the same way"
    (let [ex (observable-expr "def F:\n    bar(x: Int!) :- ||: x > 0 : x\n        ||: else     : 0\n" "bar")]
      (is (= :multi-arm-dispatch (:node ex)))
      (is (= 2 (count (:arms ex)))))))

(deftest r6-multi-arm-dispatch-three-arms-preserve-order
  (testing "R6 — three-arm chain preserves declaration order"
    (let [ex (observable-expr "def F:\n    bar(x: Int!) :- ||: x > 0 : 1 ||: x < 0 : 2 ||: else : 0\n" "bar")]
      (is (= 3 (count (:arms ex))))
      (is (= 1 (get-in ex [:arms 0 :body :value])))
      (is (= 2 (get-in ex [:arms 1 :body :value])))
      (is (= 0 (get-in ex [:arms 2 :body :value]))))))

;; ---------------------------------------------------------------------------
;; R7 (2026-08-31, decisions-frozen-2026-08-31-plugin-shape.md) — `def plugin:`
;; at module top-level is the plugin manifest marker. Reshapes to :def-plugin
;; (fields-only body at PR-3; nested `foreign "..."` sections land as PR-4).
;; Reserved-name discipline: `plugin` (lowercase) is claimed at module scope
;; for this marker. Ctor params, queue, transitions, `use`, observables are
;; rejected with targeted R7 diagnostics.
;; ---------------------------------------------------------------------------

(deftest r7-def-plugin-fields-reshapes-to-def-plugin
  (testing "R7 PR-3 — `def plugin:` with fields-only body reshapes to :def-plugin AST node"
    (let [ast (p/parse (str "def plugin:\n"
                            "    name: String = \"std.env\"\n"
                            "    version: SemVer = SemVer(0, 1, 0)\n"))
          plugin-def (first ast)]
      (is (= :def-plugin (:node plugin-def)))
      (is (= "plugin"    (:name plugin-def)))
      (is (= 2 (count (:fields plugin-def))))
      (is (= "name"    (get-in plugin-def [:fields 0 :name])))
      (is (= "version" (get-in plugin-def [:fields 1 :name]))))))

(deftest r7-def-plugin-rejects-ctor-params
  (testing "R7 PR-3 — `def plugin(x: Int):` is a compile error (manifest is data, not a constructor)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"R7: `def plugin:` manifest cannot take ctor params"
         (p/parse "def plugin(x: Int):\n    name: String = \"x\"\n")))))

(deftest r7-def-plugin-rejects-transitions
  (testing "R7 PR-3 — non-field body content in `def plugin:` is a compile error"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"R7: `def plugin:` body admits only fields"
         (p/parse "def plugin:\n    name: String = \"x\"\n    -> ready\n")))))

(deftest r6-multi-arm-dispatch-nested-arm-body
  (testing "R6 — nested `||:` inside an arm body is parsed as a nested :multi-arm-dispatch"
    ;; Inline nested chains are inherently greedy — the inner chain consumes
    ;; every subsequent `||:` on the same line/block, so the outer chain sees
    ;; a single arm whose body is the inner chain. Users disambiguate with
    ;; newlines / explicit parens when both an outer and inner chain are
    ;; intended at the same source depth.
    (let [ex (observable-expr "def F:\n    bar(x: Int!) :- ||: x > 0 : ||: x > 100 : 100 ||: else : x\n" "bar")]
      (is (= :multi-arm-dispatch (:node ex)))
      (is (= 1 (count (:arms ex))))
      (is (= :multi-arm-dispatch (get-in ex [:arms 0 :body :node])))
      (is (= 2 (count (get-in ex [:arms 0 :body :arms])))))))

;; ---------------------------------------------------------------------------
;; Track B/1 (2026-08-26) — `or:` recovery arms grow bind + typed shapes.
;; The parser produces `:arms` on the `:emit` node: an ordered vector of
;; `{:pattern p :body stmts}` where `p` is nil (bare), a bind
;; (`{:kind :bind :name S}`), or a typed case (`{:kind :case :name T
;; :bindings [...]}`). Downstream lowering only accepts the single-bare-arm
;; case today; the multi-arm dispatch machinery lands in Track B/2+B/3.
;; ---------------------------------------------------------------------------

(defn- first-emit-with-arms
  "Locate the first `:emit` node with an `:arms` vector by walking every
   `:then-clause`'s `:stmts` under every transition of every top-level
   def-atom. Enough for these focused parser tests."
  [source]
  (let [top (p/parse source)]
    (some (fn [atom]
            (some (fn [tr]
                    (when (= :transition (:node tr))
                      (some (fn [clause]
                              (when (= :then-clause (:node clause))
                                (some #(when (and (= :emit (:node %))
                                                  (contains? % :arms)) %)
                                      (:stmts clause))))
                            (:body tr))))
                  (:body atom)))
          (filter #(= :def-atom (:node %)) top))))

(deftest track-b1-bare-or-arm-produces-single-nil-pattern-arm
  (testing "Track B/1 — bare `or:` after a `|>` sync send produces `:arms` = [{:pattern nil :body [...]}]. Terminal shape from day one; the legacy `:or-body` field is retired at parse output."
    (let [emit-node (first-emit-with-arms
                     "def S:\n    -> running\n\ndef B:\n    s: S = S()\n    -> running\n        then:\n            |> s.ping()\n            or:\n                |> s.ping()\n")]
      (is (some? emit-node) "found an :emit node carrying :arms")
      (is (= 1 (count (:arms emit-node))))
      (is (nil? (:pattern (first (:arms emit-node))))
          "bare `or:` arm carries nil pattern")
      (is (not (contains? emit-node :or-body))
          "parse output no longer emits legacy `:or-body` field"))))

(deftest track-b1-or-bind-arm-parses-with-bind-pattern
  (testing "Track B/1 — `or e:` after a `|>` sync send parses into a single arm whose pattern is `{:kind :bind :name \"e\"}`. Lower rejects this shape today (`Track B/3` fail-nyi); parser must still accept it and record the bound name."
    (let [emit-node (first-emit-with-arms
                     "def S:\n    -> running\n\ndef B:\n    s: S = S()\n    -> running\n        then:\n            |> s.ping()\n            or e:\n                |> s.ping()\n")]
      (is (some? emit-node))
      (is (= 1 (count (:arms emit-node))))
      (is (= {:kind :bind :name "e"} (:pattern (first (:arms emit-node))))))))

(deftest track-b1-or-typed-arm-with-empty-parens-parses-as-case
  (testing "Track B/1 — `or T():` is the no-payload typed match; distinguished from bind by the trailing `(`. Parses into `{:kind :case :name \"T\" :bindings []}` even with empty parens."
    (let [emit-node (first-emit-with-arms
                     "def S:\n    -> running\n\ndef B:\n    s: S = S()\n    -> running\n        then:\n            |> s.ping()\n            or MyError():\n                |> s.ping()\n")]
      (is (some? emit-node))
      (is (= {:kind :case :name "MyError" :bindings []}
             (:pattern (first (:arms emit-node))))))))

(deftest track-b1-or-typed-arm-with-destructure-bindings-parses
  (testing "Track B/1 — `or T(field: let x):` parses the payload bindings using the same shape as `<:` case patterns: `{:field <str> :as <str>}` per binding entry."
    (let [emit-node (first-emit-with-arms
                     "def S:\n    -> running\n\ndef B:\n    s: S = S()\n    -> running\n        then:\n            |> s.ping()\n            or MyError(msg: let m):\n                |> s.ping()\n")]
      (is (some? emit-node))
      (let [pat (:pattern (first (:arms emit-node)))]
        (is (= :case (:kind pat)))
        (is (= "MyError" (:name pat)))
        (is (= [{:field "msg" :as "m"}] (:bindings pat)))))))

(deftest track-b1-multiple-or-arms-chain-in-source-order
  (testing "Track B/1 — several sibling `or ...:` blocks chain into a single `:arms` vector preserving source order. Bare, bind, and typed arms may co-exist; dispatch order is exactly the parse order."
    (let [emit-node (first-emit-with-arms
                     "def S:\n    -> running\n\ndef B:\n    s: S = S()\n    -> running\n        then:\n            |> s.ping()\n            or MyError(msg: let m):\n                |> s.ping()\n            or e:\n                |> s.ping()\n            or:\n                |> s.ping()\n")]
      (is (some? emit-node))
      (is (= 3 (count (:arms emit-node))))
      (is (= :case (get-in emit-node [:arms 0 :pattern :kind])))
      (is (= "MyError" (get-in emit-node [:arms 0 :pattern :name])))
      (is (= :bind (get-in emit-node [:arms 1 :pattern :kind])))
      (is (= "e"    (get-in emit-node [:arms 1 :pattern :name])))
      (is (nil? (get-in emit-node [:arms 2 :pattern]))))))

;; ---------------------------------------------------------------------------
;; Track C/2 (2026-08-26) — `def <Atom>(queue: <discipline>)` opt-in
;; syntax attaches a mailbox transport declaration to an agent atom.
;; Two disciplines supported: `bounded(N)` (positive int capacity) and
;; `unbounded` (explicit form of the default). Parser stores as
;; `:queue-config` on the def-atom node. Runtime enforcement is a
;; follow-on landing; the parser + kind-inference machinery ships here.
;; ---------------------------------------------------------------------------

(deftest track-c2-queue-bounded-parses-and-attaches-to-def-atom
  (testing "Track C/2 — `def X(queue: bounded(100)):` parses; the def-atom node carries `:queue-config {:kind :bounded :capacity 100}` and is stamped as `:agent` kind even without explicit ctor params (queue: is itself an agent-intent signal)."
    (let [n (first-node "def R(queue: bounded(100)):\n    n: Int! = 0\n    * <: ping -> self\n        then: n => n + 1\n")]
      (is (= :def-atom (:node n)))
      (is (= :agent (:inferred-kind n)))
      (is (= {:kind :bounded :capacity 100} (:queue-config n))))))

(deftest track-c2-queue-unbounded-parses-explicit-default
  (testing "Track C/2 — explicit `queue: unbounded` parses to `{:kind :unbounded}`. Useful for readability (making the default explicit) even though it's a no-op behaviorally."
    (let [n (first-node "def R(queue: unbounded):\n    n: Int! = 0\n    * <: ping -> self\n        then: n => n + 1\n")]
      (is (= {:kind :unbounded} (:queue-config n))))))

(deftest track-c2-queue-followed-by-ordinary-ctor-params
  (testing "Track C/2 — `queue:` must be first in the header when present, but ordinary ctor params may follow after `,`. Verifies both `:queue-config` and `:ctor-params` coexist."
    (let [n (first-node "def R(queue: bounded(8), initial: Int):\n    n: Int! = initial\n    * <: ping -> self\n        then: n => n + 1\n")]
      (is (= {:kind :bounded :capacity 8} (:queue-config n)))
      (is (= 1 (count (:ctor-params n))))
      (is (= "initial" (get-in n [:ctor-params 0 :name]))))))

(deftest track-c2-queue-non-positive-capacity-rejected
  (testing "Track C/2 — `bounded(0)` and `bounded(-1)` are rejected at parse time. A bounded mailbox with zero or negative capacity is meaningless — user probably meant `unbounded` or a real capacity."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Track C/2: `queue: bounded\(0\)` requires a positive capacity"
         (p/parse "def R(queue: bounded(0)):\n    n: Int! = 0\n    * <: ping -> self\n        then: n => n + 1\n")))))

(deftest track-c2-queue-on-non-agent-def-rejected
  (testing "Track C/2 — `queue:` is agent-only. When the body's structural detection picks a non-agent kind (e.g. protocol via signature-only members) the C/2 check fires with a targeted diagnostic. Protocols / records / enums have no mailbox — `queue:` would be a vestigial attachment."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Track C/2: `queue: <discipline>` is meaningful only on agent atoms"
         (p/parse "def R(queue: bounded(10)):\n    has_next() :- Bool\n")))))

(deftest track-c2-queue-unknown-discipline-rejected
  (testing "Track C/2 — only `bounded(N)` and `unbounded` are recognized disciplines today. `queue: sliding(4)` etc. fail with a targeted diagnostic listing the two accepted forms."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Track C/2: `queue:` expects `bounded\(N\)` or `unbounded`"
         (p/parse "def R(queue: sliding(4)):\n    n: Int! = 0\n    * <: ping -> self\n        then: n => n + 1\n")))))

(deftest track-b1-or-arm-without-block-rejected
  (testing "Track B/1 — inline single-expression bodies are not supported for recovery arms; the arm must open an indented block. Kept strict so future multi-line diagnostics have unambiguous line anchors."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Track B/1: `or` arm requires an indented block body"
         (p/parse "def S:\n    -> running\n\ndef B:\n    s: S = S()\n    -> running\n        then:\n            |> s.ping()\n            or: |> s.ping()\n")))))
