(ns cxc.alias-test
  "F7 L1 — Alias registry + AST-level type-position expansion.

   Tests the substrate landing: parser emits `:alias` nodes, the
   `cxc.alias/expand` pass builds a registry from them and substitutes
   matching `:type-ref` nodes in the rest of the AST. Duplicate
   declarations error-on-tie; cycles + runaway depth fire named
   diagnostics."
  (:require [clojure.test :refer [deftest is testing]]
            [cxc.parse :as parse]
            [cxc.alias :as alias]))

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
