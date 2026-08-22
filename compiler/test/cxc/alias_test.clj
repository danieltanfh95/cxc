(ns cxc.alias-test
  "F7 L1 — Alias registry + AST-level type-position expansion.

   Tests the substrate landing: parser emits `:alias` nodes, the
   `cxc.alias/expand` pass builds a registry from them and substitutes
   matching `:type-ref` nodes in the rest of the AST. Duplicate
   declarations error-on-tie; cycles + runaway depth fire named
   diagnostics."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cxc.parse :as parse]
            [cxc.alias :as alias]
            [cxc.section :as section]))

(use-fixtures :each (fn [f] (section/clear-registry!) (f)))

(defn- parse-nodes [src] (parse/parse src))

(defn- expand [src] (alias/expand (parse-nodes src)))

;; ---------------------------------------------------------------------------
;; Parser — `alias <name>: <type-expr>` at top-level
;; ---------------------------------------------------------------------------

(deftest alias-decl-parses
  (testing "F7 L1 — bare identifier alias in type position"
    (let [nodes  (parse-nodes "alias UserID: Int\n")
          n      (first nodes)]
      (is (= :alias (:node n)))
      (is (= "UserID" (:name n)))
      (is (= :type   (:position n)))
      (is (= :type-ref (get-in n [:body :node])))
      (is (= "Int"     (get-in n [:body :name]))))))

(deftest alias-decl-parametric-type-body
  (testing "F7 L1 — alias body may be a parametric type expression (`T of X` surface)"
    (let [nodes (parse-nodes "alias Items: Array of String\n")
          n     (first nodes)]
      (is (= :alias (:node n)))
      (is (= "Items" (:name n)))
      (is (= :type-of (get-in n [:body :node])))
      (is (= "Array"  (get-in n [:body :outer]))))))

;; ---------------------------------------------------------------------------
;; Registry construction
;; ---------------------------------------------------------------------------

(deftest empty-source-empty-registry
  (testing "no aliases → registry is empty, expand is a no-op"
    (let [nodes    (parse-nodes "def X:\n    x: Int\n")
          expanded (alias/expand nodes)]
      (is (= nodes expanded)))))

(deftest registry-keyed-by-position-and-name
  (testing "single alias registers under (:type, name) with params + body"
    (let [nodes (parse-nodes "alias UserID: Int\n")
          reg   (alias/build-registry nodes)
          entry (get-in reg [:type "UserID"])]
      (is (contains? reg :type))
      (is (contains? (:type reg) "UserID"))
      (is (= [] (:params entry)))
      (is (= "Int" (get-in entry [:body :name]))))))

;; ---------------------------------------------------------------------------
;; Expansion — the core L1 behavior
;; ---------------------------------------------------------------------------

(deftest type-position-substitution
  (testing "`:type-ref` naming an alias is replaced with alias body"
    (let [src      (str "alias UserID: Int\n"
                        "def U:\n"
                        "    id: UserID\n")
          expanded (expand src)
          record   (first (filter #(= :record (:node %)) expanded))
          field    (first (:fields (first (:sections record))))]
      ;; `UserID` in the field type should have been replaced by `Int`.
      (is (= :type-ref (get-in field [:type :node])))
      (is (= "Int"     (get-in field [:type :name]))))))

(deftest chained-alias-expansion
  (testing "alias A → B → concrete resolves to concrete"
    (let [src (str "alias A: B\n"
                   "alias B: Int\n"
                   "def U:\n"
                   "    id: A\n")
          expanded (expand src)
          record   (first (filter #(= :record (:node %)) expanded))
          field    (first (:fields (first (:sections record))))]
      (is (= "Int" (get-in field [:type :name])))))

  (testing "chain that never terminates in a concrete type still resolves to the final ref"
    (let [src (str "alias A: B\n"
                   "def U:\n"
                   "    id: A\n")
          expanded (expand src)
          record   (first (filter #(= :record (:node %)) expanded))
          field    (first (:fields (first (:sections record))))]
      (is (= "B" (get-in field [:type :name]))))))

(deftest non-aliased-type-refs-untouched
  (testing "type refs that don't name an alias survive unchanged"
    (let [src (str "alias UserID: Int\n"
                   "def U:\n"
                   "    name: String\n")
          expanded (expand src)
          record   (first (filter #(= :record (:node %)) expanded))
          field    (first (:fields (first (:sections record))))]
      (is (= "String" (get-in field [:type :name]))))))

;; ---------------------------------------------------------------------------
;; Error paths — Q2 (duplicate) and Q4 (depth/cycle)
;; ---------------------------------------------------------------------------

(deftest duplicate-alias-rejected
  (testing "F7 L1 Q2 — error-on-tie precedence"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"duplicate alias declaration"
         (alias/expand
          (parse-nodes (str "alias UserID: Int\n"
                            "alias UserID: String\n")))))))

(deftest cycle-detected-and-named
  (testing "F7 L1 Q4 — self-cycle diagnostic names the chain"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"alias expansion cycle"
         (alias/expand
          (parse-nodes (str "alias A: A\n"
                            "def U:\n"
                            "    id: A\n"))))))

  (testing "F7 L1 Q4 — multi-step cycle"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"alias expansion cycle"
         (alias/expand
          (parse-nodes (str "alias A: B\n"
                            "alias B: C\n"
                            "alias C: A\n"
                            "def U:\n"
                            "    id: A\n")))))))

;; ---------------------------------------------------------------------------
;; L1-c — Parametric aliases via `of` qualifier
;; ---------------------------------------------------------------------------

(deftest parametric-alias-decl-parses-single-param
  (testing "F7 L1-c — `alias Name of P: <body>` produces :params [\"P\"]"
    (let [n (first (parse-nodes "alias Box of T: T\n"))]
      (is (= :alias (:node n)))
      (is (= "Box"  (:name n)))
      (is (= ["T"]  (:params n))))))

(deftest parametric-alias-decl-parses-tuple-params
  (testing "F7 L1-c — `alias Name of (P1, P2): <body>` produces :params in order"
    (let [n (first (parse-nodes "alias Pair of (A, B): (A, B)\n"))]
      (is (= :alias (:node n)))
      (is (= ["A" "B"] (:params n))))))

(deftest parametric-substitution-single-param
  (testing "F7 L1-c — `Box of Int` expands `T` inside body to `Int`"
    (let [src      (str "alias Box of T: T\n"
                        "def U:\n"
                        "    b: Box of Int\n")
          expanded (expand src)
          record   (first (filter #(= :record (:node %)) expanded))
          field    (first (:fields (first (:sections record))))]
      ;; After expansion, the field type is `T` substituted with `Int` — a
      ;; bare :type-ref for Int.
      (is (= :type-ref (get-in field [:type :node])))
      (is (= "Int"     (get-in field [:type :name]))))))

(deftest parametric-substitution-into-parametric-body
  (testing "F7 L1-c — `StringDict of V: Map of (String, V)` — V→Int inside :type-of"
    (let [src      (str "alias StringDict of V: Map of (String, V)\n"
                        "def U:\n"
                        "    d: StringDict of Int\n")
          expanded (expand src)
          record   (first (filter #(= :record (:node %)) expanded))
          field    (first (:fields (first (:sections record))))
          ty       (:type field)]
      (is (= :type-of (:node ty)))
      (is (= "Map"    (:outer ty)))
      (is (= :type-tuple (get-in ty [:inner :node])))
      (let [[k v] (get-in ty [:inner :types])]
        (is (= "String" (:name k)))
        (is (= "Int"    (:name v)))))))

(deftest parametric-alias-args-are-expanded
  (testing "F7 L1-c — nested alias inside arg gets expanded before substitution"
    (let [src (str "alias UserID: Int\n"
                   "alias Box of T: T\n"
                   "def U:\n"
                   "    b: Box of UserID\n")
          expanded (expand src)
          record   (first (filter #(= :record (:node %)) expanded))
          field    (first (:fields (first (:sections record))))]
      ;; UserID expands to Int inside the arg; then Box of Int → Int.
      (is (= "Int" (get-in field [:type :name]))))))

(deftest parametric-arity-mismatch-rejected
  (testing "F7 L1-c — arg count vs param count differ → error"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"arity mismatch"
         (alias/expand
          (parse-nodes (str "alias Pair of (A, B): (A, B)\n"
                            "def U:\n"
                            "    p: Pair of Int\n")))))))

(deftest bare-reference-to-parametric-alias-rejected
  (testing "F7 L1-c — parametric alias referenced without `of` → error"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"parametric.*without.*args|referenced as bare"
         (alias/expand
          (parse-nodes (str "alias Box of T: T\n"
                            "def U:\n"
                            "    b: Box\n")))))))

(deftest of-application-to-non-parametric-alias-rejected
  (testing "F7 L1-c — non-parametric alias referenced with `of` → error"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"is not parametric"
         (alias/expand
          (parse-nodes (str "alias UserID: Int\n"
                            "def U:\n"
                            "    id: UserID of Int\n")))))))

;; ---------------------------------------------------------------------------
;; L1-b — Section-header aliases (spec §4.10.2)
;; ---------------------------------------------------------------------------

(deftest section-header-alias-decl-parses
  (testing "F7 L1-b — `alias data: runtime immutable identity` recognized"
    (let [n (first (parse-nodes "alias data: runtime immutable identity\n"))]
      (is (= :alias (:node n)))
      (is (= "data" (:name n)))
      (is (= :section-header (:position n)))
      (is (= [:runtime :immutable :identity] (:primitives n))))))

(deftest section-header-alias-registers-composed-spec
  (testing "F7 L1-b — build-registry populates cxc.section/registry"
    (alias/expand
     (parse-nodes (str "alias data: runtime immutable identity\n")))
    (let [entry (section/lookup :data)]
      (is (some? entry))
      (is (= :alias-expanded (:kind entry)))
      (is (= [:runtime :immutable :identity]
             (get-in entry [:spec :primitives])))
      (is (= :runtime
             (get-in entry [:spec :contributes :default-existence])))
      (is (= true
             (get-in entry [:spec :contributes
                            :contributes-to-equality]))))))

(deftest section-header-alias-canonical-spec-4-10-2
  (testing "F7 L1-b — the three canonical §4.10.2 aliases register"
    (alias/expand
     (parse-nodes (str "alias data: runtime immutable identity\n"
                       "alias state: runtime mutable coordination\n"
                       "alias static: compile_time immutable\n")))
    (is (some? (section/lookup :data)))
    (is (some? (section/lookup :state)))
    (is (some? (section/lookup :static)))
    (is (= :mutable
           (get-in (section/lookup :state)
                   [:spec :contributes :default-mutability])))
    (is (= :compile-time
           (get-in (section/lookup :static)
                   [:spec :contributes :default-existence])))))

(deftest section-header-alias-unknown-primitive-rejected
  (testing "F7 L1-b — the parser rejects unknown primitives up front"
    ;; `sealed` isn't a primitive, so the alias body doesn't classify as
    ;; a section-header body — the parser falls through to type-expr
    ;; parsing and fails there (`sealed identity` isn't a valid type).
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"parse error"
         (parse-nodes (str "alias broken: sealed identity\n"))))))

(deftest section-header-alias-contradiction-surfaces-name
  (testing "F7 L1-b — compose-time contradictions carry the alias name"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"alias `broken`.*conflict on axis"
         (alias/expand
          (parse-nodes (str "alias broken: immutable mutable\n")))))))

(deftest section-header-alias-does-not-substitute-in-ast
  (testing "F7 L1-b — section-header aliases don't rewrite the tree"
    (let [src      (str "alias data: runtime immutable identity\n"
                        "def U:\n"
                        "    id: Int\n")
          expanded (alias/expand (parse-nodes src))
          record   (first (filter #(= :record (:node %)) expanded))
          field    (first (:fields (first (:sections record))))]
      ;; The `data` name should not have leaked into the record body.
      (is (= "Int" (get-in field [:type :name]))))))

;; ---------------------------------------------------------------------------
;; F8 L1-b — Parser hook consulting cxc.section/registry
;;
;; Registration happens at PARSE time (parse-alias-decl → register!)
;; so a `<name>:` section header later in the same source can dispatch
;; through the registry.
;; ---------------------------------------------------------------------------

(deftest custom-section-header-parses-after-alias-decl
  (testing "F8 L1-b — user-declared section header dispatches through registry"
    (let [src   (str "alias my_data: runtime immutable identity\n"
                     "def U:\n"
                     "    my_data:\n"
                     "        x: Int\n"
                     "        y: Int\n")
          nodes (parse-nodes src)
          record (first (filter #(= :record (:node %)) nodes))
          section (first (filter #(= :custom-section (:node %))
                                 (:sections record)))]
      (is (some? section))
      (is (= "my_data" (:name section)))
      (is (= 2 (count (:fields section))))
      (is (= "x" (:name (first (:fields section)))))
      (is (= "y" (:name (second (:fields section)))))
      ;; The composed spec travels with the AST node.
      (is (= :alias-expanded (get-in section [:spec :kind])))
      (is (= [:runtime :immutable :identity]
             (get-in section [:spec :spec :primitives]))))))

(deftest unknown-section-header-still-fails
  (testing "F8 L1-b — section header not in registry falls through"
    ;; With no alias declared, `unknown_section:` isn't a custom
    ;; section — it's handed to `looks-like-transition?` which
    ;; consumes the ident and expects `->` after; parse fails there.
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"parse error"
         (parse-nodes
          (str "def U:\n"
               "    unknown_section:\n"
               "        x: Int\n"))))))

(deftest custom-section-registry-cleared-between-tests
  (testing "F8 L1-b — registry state is per-test via clear-registry! fixture"
    ;; Without declaring `my_data` in THIS test, using `my_data:` fails
    ;; — proving the registry was cleared after the earlier test that
    ;; declared it.
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"parse error"
         (parse-nodes
          (str "def U:\n"
               "    my_data:\n"
               "        x: Int\n"))))))
