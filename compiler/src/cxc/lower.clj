(ns cxc.lower
  "Lower cxc AST to ObjC/C AST.

   ObjC/C AST vocabulary (Clojure data — a subset of what real ObjC allows,
   grown feature by feature alongside the parser):

     {:node :objc-program
      :includes  [\"<stdio.h>\"] or [\"<Foundation/Foundation.h>\"]
      :structs   [ ... :c-struct ... ]        ; C typedef structs (records)
      :functions [ ... :c-function ... ]      ; C free functions (record methods)
      :classes   [ ... :objc-class ... ]      ; ObjC classes (def-atoms)
      :main      {:body [ ... :stmt ... ]
                  :autorelease? <bool>}}      ; wrap body in @autoreleasepool

     {:node :c-struct
      :name    \"V\"
      :fields  [ {:name \"x\" :c-type \"int\"} ... ]}

     {:node :c-function
      :name        \"V_plus\"
      :return-type \"V\"
      :params      [ {:name \"self\" :c-type \"V\"} ... ]
      :body        [ ... :stmt ... ]}

     {:node :objc-class
      :name        \"Counter\"
      :superclass  \"NSObject\"
      :properties  [ {:name \"n\" :c-type \"int\" :attrs [\"assign\"]} ... ]
      :init-body   [ ... :stmt ... ]}

   Statement nodes (in :body vectors):
     {:node :call    :fn \"printf\" :args [ ... :expr ... ]}
     {:node :return  :value <expr>}
     {:node :assign  :lhs <expr> :rhs <expr>}

   Expression nodes:
     {:node :str             :value \"hello\"}
     {:node :int             :value 42}
     {:node :float           :value 3.14}
     {:node :name            :sym \"self\"}
     {:node :binop           :op \"+\" :left <expr> :right <expr>}
     {:node :field           :target <expr> :field \"x\"}
     {:node :struct-lit      :type \"V\" :fields [{:name \"x\" :value <expr>} ...]}
     {:node :objc-alloc-init :class \"Counter\"}
     {:node :call            :fn \"printf\" :args [ ... ]}

   Coverage this file (2026-08-13):
   - Hello-world: `def main: -> running do: print(\"...\")`
   - Arithmetic in print; binops with %d/%f dispatch
   - Records → C typedef structs; construction; field access; defaults
   - Record methods → C functions (`V_plus(self, other)`); method-call
     syntax `v.plus(other)` lowers to `V_plus(v, other)`
   - Def-atoms with `data:` only → ObjC classes (NSObject subclass with
     @property ivars + -init that fills defaults). `X()` → alloc/init

   Not yet: transitions on def-atoms, use: injection, contracts, nested
   slots, actor runtime. Those raise :not-yet-implemented.

   Ctx map threaded through infer-type / lower-expr:
     {:record-env <name -> {:fields :field-index :methods :method-index}>
      :atom-env   <name -> {:fields :field-index :extras}>
      :locals     <name -> ty>       ; local scope for method bodies etc."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cxc.prelude :as prelude]
            [cxc.resolve :as resolve]))

(defn- fail-nyi! [what node]
  (throw (ex-info (str "lowering not yet implemented: " what)
                  {:kind :not-yet-implemented :what what :node node})))

(defn- fail-compile! [kind msg node]
  (throw (ex-info msg {:kind kind :what msg :node node})))

(defn- c-string-lit
  "Render a Clojure string as a valid C double-quoted string literal.
   Escapes backslash and double-quote; other bytes pass through (the
   sites that use this feed short compiler-authored labels with
   ASCII-safe content — atom/field/param/method names)."
  [s]
  (str "\""
       (-> s
           (str/replace "\\" "\\\\")
           (str/replace "\"" "\\\""))
       "\""))

(defn- nonnil-check-stmt
  "F39 L3 — inline `if ((expr-c) == nil) cxc_nonnil_violation(site, name);`
   guard as a :raw-c stmt. `expr-c` is the already-rendered C expression
   whose nil-ness is being checked (a bare identifier at every current
   observation point — ctor-param name, field ivar, or return-temp).
   `site` and `name` are compiler-authored labels."
  [expr-c site name]
  {:node :raw-c
   :text (str "if ((" expr-c ") == nil) cxc_nonnil_violation("
              (c-string-lit site) ", " (c-string-lit name) ");")})

(defn- validate-defaults-after-required!
  "Spec §3.8.5 rule 3: in any parameter list, all defaulted params must
   follow all non-defaulted (required) params. `f(x: Int = 0, y: Int)`
   is a compile error — a caller giving `f(1)` couldn't tell whether
   `1` bound `x` or `y`.

   `params` is a seq of maps carrying `:name` and `:default` (nil when
   no default). `site` is a short label for the diagnostic."
  [site params]
  (loop [seen-default? false
         ps            (seq params)]
    (when ps
      (let [p (first ps)]
        (if (and seen-default? (nil? (:default p)))
          (fail-nyi! (str "required param `" (:name p) "` follows a defaulted "
                          "param on " site " — spec §3.8.5 requires "
                          "defaulted params to come last")
                     {:site site :param (:name p)})
          (recur (or seen-default? (some? (:default p)))
                 (next ps)))))))

(defn- default-expr-const-shape?
  "Spec §3.8.5 rule 1: default expressions must be compile-time
   constants. Permitted forms: primitive literals (int/float/string/
   char/bool/nil, with or without unit), unary `-`/`not` wrapping a
   permitted form, and bare identifiers (value-alias references —
   validated as-such once value aliases land; treated permissively
   pre-alias).

   Explicitly rejected: calls, dots, subscripts, binops, collection
   literals, message-shapes, emit sites, blocks, ifs, switches."
  [expr]
  (case (:node expr)
    (:lit-int :lit-float :lit-string :lit-char
     :lit-nil :lit-bool)             true
    :ident                           true
    (:neg :not)                      (default-expr-const-shape? (:expr expr))
    false))

(defn- validate-default-is-const!
  "Reject user-authored defaults that aren't compile-time constants
   per spec §3.8.5 rule 1. Skips params/fields whose :default is nil
   (no default) and those marked `:cxc-f41-synthesized` or
   `:cxc-compiler-synthesized` (compiler-inserted defaults for
   nested slots / use-caps aren't user surface)."
  [site params]
  (doseq [p params
          :let [d (:default p)]
          :when (and (some? d)
                     (not (:cxc-f41-synthesized p))
                     (not (:cxc-compiler-synthesized p)))]
    (when-not (default-expr-const-shape? d)
      (fail-nyi! (str "default for `" (:name p) "` on " site
                      " is not a compile-time constant (spec §3.8.5 "
                      "permits primitive literals, `nil`, unary `-`/"
                      "`not` on those, or a value-alias identifier); "
                      "got `" (:node d) "` node")
                 {:site site :param (:name p) :default-node (:node d)}))))

(defn- program-has-async-sends?
  "Walk the parsed AST for any `:emit` node with `:async? true` — a
   `:>` targeted send OR an untargeted stream emission. Programs
   without async sends don't need dispatch_main; their work completes
   synchronously during construction and main() returns normally."
  [top-nodes]
  (let [found (atom false)]
    (walk/postwalk
     (fn [n]
       (when (and (map? n) (= :emit (:node n)) (:async? n))
         (reset! found true))
       n)
     top-nodes)
    @found))

(declare base-type-name)

(defn- resolve-target-atom-name
  "F31/F35 L3 — best-effort resolution of a `|>` target expression to
   the target's atom class name. Uses only the enclosing atom's
   declared shape (data fields, ctor params, use: caps, nested slots)
   — no full type inference, keeps the cycle-detection pass
   independent of the type-check pipeline. Returns nil when the
   target isn't a simple ident or its type doesn't resolve to a
   known atom."
  [target-node atom-node atom-names]
  (case (:node target-node)
    :name
    (when (= "self" (:sym target-node)) (:name atom-node))
    :ident
    (let [nm (:name target-node)
          data-fields (get (first (filter #(= :data-section (:node %))
                                          (:body atom-node)))
                           :fields [])
          use-caps    (get (first (filter #(= :use-section (:node %))
                                          (:body atom-node)))
                           :caps [])
          nested      (filter #(= :nested-atom (:node %)) (:body atom-node))
          ctor-params (:ctor-params atom-node)
          find-in    (fn [items key-fn]
                       (some (fn [it]
                               (when (= nm (key-fn it))
                                 (base-type-name (:type it))))
                             items))
          resolved   (or (find-in ctor-params :name)
                         (find-in data-fields :name)
                         (find-in use-caps :name)
                         (find-in nested :name))]
      (when (contains? atom-names resolved) resolved))
    nil))

(defn- sync-send-edges
  "F31/F35 L3 — extract `|>` cross-atom edges from every atom in the
   program. Returns a set of `#{[source-atom target-atom target-msg]}`
   triples. Self-sends (source = target) are excluded — they're a
   pattern users deliberately reach for (self-drive in on-enter) and
   can only deadlock via infinite recursion on Layer 4, which is a
   distinct failure mode."
  [top-nodes]
  (let [atom-nodes (filter #(and (= :def-atom (:node %))
                                 (not= "main" (:name %)))
                           top-nodes)
        atom-names (into #{} (map :name atom-nodes))
        edges      (java.util.HashSet.)]
    (doseq [a atom-nodes]
      (walk/postwalk
       (fn [n]
         (when (and (map? n) (= :emit (:node n))
                    (not (:async? n))
                    (some? (:target n)))
           (when-let [tgt (resolve-target-atom-name (:target n) a atom-names)]
             (when-not (= tgt (:name a))
               (.add edges [(:name a) tgt (:msg n)]))))
         n)
       (:body a)))
    (into #{} edges)))

(defn- warn-sync-send-cycles!
  "F31/F35 L3 — DFS over the |> edge set collected by `sync-send-edges`.
   For each strongly-connected group of size ≥ 2, log a warning
   naming the atoms in the cycle. Warnings go to *err* so they surface
   in build output without failing the compile — spec §1.11 line 1523
   makes cycle-avoidance the user's responsibility; a --strict flag can
   promote to error in a future landing."
  [edges]
  (let [adj  (reduce (fn [m [s t _msg]] (update m s (fnil conj #{}) t))
                     {} edges)
        seen (atom #{})]
    (letfn [(dfs [node stack on-stack]
              (cond
                (contains? on-stack node)
                (let [cycle (conj (vec (drop-while #(not= % node) stack)) node)]
                  [cycle])
                (contains? @seen node)
                []
                :else
                (do (swap! seen conj node)
                    (mapcat (fn [next-node]
                              (dfs next-node (conj stack node)
                                   (conj on-stack node)))
                            (get adj node #{})))))]
      (let [cycles (into #{} (mapcat (fn [n] (dfs n [] #{})) (keys adj)))]
        (doseq [c cycles]
          (binding [*out* *err*]
            (println (str "warning: `|>` cycle across atoms "
                          (str/join " → " (map #(str "`" % "`") c))
                          " — spec §1.11 line 1523 makes cycle-avoidance "
                          "the caller's responsibility. Under Layer 4 (single "
                          "main queue) this recurses until stack overflow; "
                          "under future F32 concurrent queues it deadlocks. "
                          "Break the cycle by routing one edge through `:>` "
                          "(async) or by refactoring the callback path."))))
        cycles))))

(defn- program-uses-panic?
  "F31/F35 L2 — walk the parsed AST for any `:call` node whose callee is
   `panic`. Programs that use panic need <execinfo.h> and <stdlib.h> for
   the inlined backtrace + free()."
  [top-nodes]
  (let [found (atom false)]
    (walk/postwalk
     (fn [n]
       (when (and (map? n)
                  (= :call (:node n))
                  (= :ident (get-in n [:callee :node]))
                  (= "panic" (get-in n [:callee :name])))
         (reset! found true))
       n)
     top-nodes)
    @found))

;; ---------------------------------------------------------------------------
;; Type env
;; ---------------------------------------------------------------------------

(def ^:private primitive-c-type
  ;; Phase D F36a — spec §3.1 primitive substrate.
  ;;
  ;; `Int` is a prelude alias for `Int64` — 64-bit signed always. Users
  ;; who want tighter storage write a domain atom (see §3.1.2) or reach
  ;; for a sized atom directly.
  ;;
  ;; `Float` = 32-bit IEEE 754 (fixes v0.11 lowering bug — was `double`).
  ;; `Double` = 64-bit IEEE 754.
  ;;
  ;; Sized atoms (UInt8..UInt64, Int8..Int64) pin exact widths for
  ;; interop, wire formats, and explicit-width contexts.
  ;;
  ;; Precision-preservation across arithmetic + observables (a full
  ;; type-kw split into `:int-N` / `:float-N` variants) is Landing 1b —
  ;; currently all integer widths share `:int`, all float widths share
  ;; `:float`. Storage widths on FIELDS are correct.
  {"Int"     "int64_t"      ; §3.1: Int aliased to Int64
   "Int8"    "int8_t"
   "Int16"   "int16_t"
   "Int32"   "int32_t"
   "Int64"   "int64_t"
   "UInt8"   "uint8_t"
   "UInt16"  "uint16_t"
   "UInt32"  "uint32_t"
   "UInt64"  "uint64_t"
   "Float"   "float"        ; §3.1: 32-bit IEEE 754
   "Double"  "double"       ; §3.1: 64-bit IEEE 754
   ;; F65 — String is NSString * per spec §3.10.5 (`kind: regular`,
   ;; toll-free bridged to NSString). Reference-shaped: ARC handles
   ;; retain/release, nullable at C level (like any ObjC object
   ;; pointer). Full grapheme-native method surface per §3.10.5
   ;; (grapheme_count, first_grapheme, substring, ...) is a separate
   ;; follow-on landing.
   "String"  "NSString *"
   "Bool"    "int"
   ;; Phase D F36a Landing 3a — spec §3.1 non-numeric substrate.
   ;;
   ;; Unit: the type with exactly one value, `()`. Return-position only for
   ;; MVP; usage as field/param yields a clang-visible `void field;` error.
   ;; `()` literal syntax + typed use in expression position is a follow-on.
   ;;
   ;; Never: divergent return. Emitted as `void` for MVP; `_Noreturn`
   ;; attribute wiring depends on flow analysis and lands separately.
   ;;
   ;; Any: dynamic type per §3.1.3. Ships as ObjC `id` — nil-allowable
   ;; at C level, which matches the runtime-typed nature. Method dispatch
   ;; on Any values (runtime lookup, fail-if-no-selector) is a follow-on.
   ;;
   ;; Bytes: mutable byte buffer, reference-shaped per §3.10.4. Ships as
   ;; NSMutableData *. Method surface (append/read/slice/length/...) is a
   ;; follow-on Bytes-methods landing.
   "Unit"    "void"
   "Never"   "void"
   "Any"     "id"
   "Bytes"   "NSMutableData *"
   ;; Layer 6 Step 12 — escaping-block field type. MVP is zero-arg
   ;; void return only; a future landing generalizes to
   ;; `Block[(params) -> R]` grammar with full signature support.
   "Block"   "void (^)(void)"})

(declare base-type-name)

(defn- cxc-type->c
  "Primitives → C type name; records → own struct name (value); atoms →
   `<name> *` (pointer, already nullable in ObjC); enums → own typedef
   name (int-backed). `T?` wraps another type — legal today only for
   atoms (their pointer type IS nullable). Nullable primitives/records/
   enums would need Optional wrappers — fail-nyi."
  [ty-node record-env atom-env enum-env]
  (case (:node ty-node)
    :type-nullable
    ;; F39 Landing 1 — `T?` on reference types is spec-refused as
    ;; redundant (spec §3.6.1: reference types are already nil-allowed
    ;; by default; the `?` adds nothing). `T?` on value types (primitive
    ;; scalar / record / enum) needs an Optional<T> representation
    ;; (F39 Landing 5) — reject with a distinct diagnostic pointing at
    ;; that follow-on landing.
    (let [inner (:inner ty-node)]
      (case (:node inner)
        :type-ref
        (let [nm (:name inner)]
          (cond
            (contains? atom-env nm)
            (fail-compile! :cxc-f39-nullable-on-reference
                           (str "redundant nullable on reference type `"
                                nm "?` — reference types are already "
                                "nil-allowed by default (spec §3.6.1). "
                                "Fix: drop the `?` and write `" nm "`.")
                           ty-node)
            (= "String" nm)
            (fail-compile! :cxc-f39-nullable-on-reference
                           (str "redundant nullable on reference type `String?` "
                                "— String is reference-shaped (NSString *) and "
                                "already nil-allowed (spec §3.6.1). Fix: drop "
                                "the `?` and write `String`.")
                           ty-node)
            :else
            (fail-nyi! (str "nullable value type `" nm "?` — Optional<T> "
                            "value representation waits on F39 Landing 5 "
                            "(spec §3.6.1)")
                       ty-node)))
        (fail-nyi! (str "cxc-type->c: nullable of unknown inner " (:node inner))
                   ty-node)))
    :type-nonnil
    ;; F39 Landing 2 — `T!` non-nil contract. Legal only on reference
    ;; types (spec §3.6.1: `!` on value types is trivially true —
    ;; primitives/records/enums have no nil rep). C-level representation
    ;; identical to bare `T`; the contract fires at observation points
    ;; (param bind, field write, method return) via F39 L3.
    (let [inner (:inner ty-node)]
      (case (:node inner)
        :type-ref
        (let [nm (:name inner)]
          (cond
            (contains? atom-env nm) (str nm " *")
            (= "String" nm)         "NSString *"
            (contains? primitive-c-type nm)
            (fail-compile! :cxc-f39-nonnil-on-value
                           (str "`" nm "!` is trivially true — `" nm
                                "` is a value type with no nil "
                                "representation (spec §3.6.1). Fix: "
                                "drop the `!` and write `" nm "`.")
                           ty-node)
            (contains? record-env nm)
            (fail-compile! :cxc-f39-nonnil-on-value
                           (str "`" nm "!` is trivially true — records "
                                "are value types with no nil representation "
                                "(spec §3.6.1). Fix: drop the `!`.")
                           ty-node)
            (contains? enum-env nm)
            (fail-compile! :cxc-f39-nonnil-on-value
                           (str "`" nm "!` is trivially true — enums are "
                                "value types with no nil representation "
                                "(spec §3.6.1). Fix: drop the `!`.")
                           ty-node)
            :else
            ;; Bootstrap: build-atom-env resolves ctor-param / data-field
            ;; types with record-env={} because records haven't been built
            ;; yet. If T isn't in any known reference-type family
            ;; (atom-env/String), it must be a value-shaped user type
            ;; (record/enum) — `!` is trivially true either way. Diagnose
            ;; as such; a genuine typo will surface as "unknown type" once
            ;; the user drops the `!`.
            (fail-compile! :cxc-f39-nonnil-on-value
                           (str "`" nm "!` is trivially true — `" nm
                                "` is a value type (record/enum) with no "
                                "nil representation (spec §3.6.1). Fix: "
                                "drop the `!` and write `" nm "`.")
                           ty-node)))
        (fail-nyi! (str "cxc-type->c: `!` on unknown inner " (:node inner))
                   ty-node)))
    :type-ref
    (let [nm (:name ty-node)]
      (cond
        (contains? primitive-c-type nm) (primitive-c-type nm)
        (contains? record-env nm)       nm
        (contains? atom-env nm)         (str nm " *")
        (contains? enum-env nm)         nm
        :else                            (fail-nyi! (str "type " nm) ty-node)))
    :type-of
    (cond
      (= "List" (:outer ty-node))
      ;; Compiler-synthesized List class — reference-typed, pointer at
      ;; the C level. Element-type validity is checked at the atom-env
      ;; registration site.
      (str (base-type-name ty-node) " *")

      (= "Map" (:outer ty-node))
      ;; Compiler-synthesized Map class (Layer 6 Step 8). Same shape as
      ;; List — mangled class name, pointer-typed at C. K/V validity
      ;; checked at atom-env registration.
      (str (base-type-name ty-node) " *")

      (= "Set" (:outer ty-node))
      ;; Compiler-synthesized Set class (Layer 6 Step 9). Same shape
      ;; as List/Map — pointer-typed at C.
      (str (base-type-name ty-node) " *")

      :else
      (fail-nyi! (str "cxc-type->c: `" (:outer ty-node)
                      " of ...` unsupported")
                 ty-node))
    (fail-nyi! (str "cxc-type->c: unknown type-node " (:node ty-node)) ty-node)))

(defn- base-type-name
  "Extract the underlying type name from either a :type-ref or a
   :type-nullable wrapper. Used to key the type env regardless of
   nullability, since nullability is orthogonal to which env the type
   lives in."
  [ty-node]
  (case (:node ty-node)
    :type-ref      (:name ty-node)
    :type-nullable (base-type-name (:inner ty-node))
    :type-nonnil   (base-type-name (:inner ty-node))
    :type-of       (cond
                     (= "List" (:outer ty-node))
                     ;; List of T → mangled ObjC class name. Deterministic
                     ;; function of the element type, no env lookup needed.
                     ;; Registered in atom-env by build-atom-env so
                     ;; infer-type / method-call resolution find it.
                     (str "_Cxc_ListOf" (base-type-name (:inner ty-node)))

                     (= "Map" (:outer ty-node))
                     ;; Map of (K, V) → mangled name incorporating both
                     ;; type params. :inner is a :type-tuple with :types
                     ;; [K-node V-node].
                     (let [tuple (:inner ty-node)
                           _ (when-not (and (= :type-tuple (:node tuple))
                                            (= 2 (count (:types tuple))))
                               (fail-nyi! (str "Map of ... expects (K, V) — got "
                                               (pr-str tuple))
                                          ty-node))
                           [k v] (:types tuple)]
                       (str "_Cxc_MapOf"
                            (base-type-name k) (base-type-name v)))

                     (= "Set" (:outer ty-node))
                     ;; Set of T (Layer 6 Step 9) — same shape as List
                     ;; but distinct backing (NSMutableSet). Grammar
                     ;; reuses single-type-arg after `of`.
                     (str "_Cxc_SetOf" (base-type-name (:inner ty-node)))

                     :else
                     (fail-nyi! (str "base-type-name: `" (:outer ty-node)
                                     " of ...` — only `List of T`, "
                                     "`Map of (K, V)`, and `Set of T` supported")
                                ty-node))
    (fail-nyi! (str "base-type-name: unknown type-node " (:node ty-node)) ty-node)))

(defn- list-container-class-name
  "Mangled ObjC class name for `List of <element-name>`. Kept in sync
   with base-type-name's :type-of branch."
  [element-name]
  (str "_Cxc_ListOf" element-name))

;; Note: `map-container-class-name` / `set-container-class-name` were
;; introduced alongside Steps 8/9 for symmetry but never called — the
;; mangling happens inline in `base-type-name`'s :type-of branch and
;; then flows through as :cxc-ty on fields. Deleted rather than kept
;; as dead symmetry.

(defn- cxc-type->type-kw
  "Map a cxc type-ref name to the type-keyword vocab used by infer-type.
   Primitive names → :int/:float/:string/:bool (family markers, possibly
   width-carrying). Aggregate names → {:record N}, {:atom N}, or
   {:enum N} depending on which env they resolve in.

   Phase D F36a — sized numeric atoms carry their C storage width in
   metadata on the type-kw so downstream `type-kw->c-type` can preserve
   the width without a full type-kw split. `:int` (default, Int/Int64)
   and `:float` (default, Double) stay as the bare kw for existing
   pattern matches; width variants attach `:cxc-c-type` metadata.

   Width preservation across observables + arithmetic + typed payloads
   flows through the metadata-carrying `type-kw->c-type` — narrower
   variants preserve their C type in emission, same-width family
   arithmetic still works via bare-kw pattern matches."
  [cxc-name record-env atom-env enum-env node]
  (case cxc-name
    "Int"    :int
    "Int8"   ^{:cxc-c-type "int8_t"}   [:int 8]
    "Int16"  ^{:cxc-c-type "int16_t"}  [:int 16]
    "Int32"  ^{:cxc-c-type "int32_t"}  [:int 32]
    "Int64"  ^{:cxc-c-type "int64_t"}  [:int 64]
    "UInt8"  ^{:cxc-c-type "uint8_t"}  [:uint 8]
    "UInt16" ^{:cxc-c-type "uint16_t"} [:uint 16]
    "UInt32" ^{:cxc-c-type "uint32_t"} [:uint 32]
    "UInt64" ^{:cxc-c-type "uint64_t"} [:uint 64]
    "Float"  ^{:cxc-c-type "float"}    [:float 32]
    "Double" :float
    "String" :string
    "Bool"   :bool
    ;; Phase D F36a Landing 3a — non-numeric substrate. Each gets a
    ;; distinct type-kw so infer-type can reject arithmetic on them
    ;; (Unit/Never/Any/Bytes are not numeric family) and so downstream
    ;; type-kw->c-type resolves them to the correct C type.
    "Unit"   :unit
    "Never"  :never
    "Any"    :any
    "Bytes"  :bytes
    ;; Layer 6 Step 12 — Block is a first-class type at the type-kw
    ;; level so infer-call-type / lower-expr can dispatch on it when
    ;; a self-field-typed callee gets invoked.
    "Block"  :block
    (cond
      (contains? record-env cxc-name) {:record cxc-name}
      (contains? atom-env   cxc-name) {:atom   cxc-name}
      (contains? enum-env   cxc-name) {:enum   cxc-name}
      :else (fail-nyi! (str "unresolved type name `" cxc-name "`") node))))

(defn- int-family?
  "True when tk is any integer type-kw: bare `:int` or a `[:int N]`/`[:uint N]` variant."
  [tk]
  (or (= :int tk)
      (and (vector? tk) (contains? #{:int :uint} (first tk)))))

(defn- float-family?
  "True when tk is any float type-kw: bare `:float` or a `[:float N]` variant."
  [tk]
  (or (= :float tk)
      (and (vector? tk) (= :float (first tk)))))

(defn- numeric-family?
  "True when tk is any numeric type-kw (int or float family)."
  [tk]
  (or (int-family? tk) (float-family? tk)))

(defn- int-bit-width
  "Width in bits of an integer type-kw. Bare `:int` = 64 (Int alias)."
  [tk]
  (cond
    (= :int tk)                              64
    (and (vector? tk) (int-family? tk))      (second tk)
    :else                                    nil))

(defn- type-kw->c-type
  "Reverse of cxc-type->type-kw for the C emit side. Observable return
   types are inferred as type-kws directly; this converts them back to
   emit-ready C type strings.

   Phase D F36a Landing 1b — width variants like `[:int 32]` and
   `[:float 32]` carry their C type in `:cxc-c-type` metadata (set by
   `cxc-type->type-kw`); this fn reads that metadata. Bare `:int` (Int
   alias for Int64) and bare `:float` (Double) fall back to `int64_t`
   and `double`."
  [tk node]
  (cond
    (= :int    tk) "int64_t"
    (= :float  tk) "double"
    (= :string tk) "NSString *"           ; F65 §3.10.5
    (= :bool   tk) "int"
    (= :unit   tk) "void"
    (= :never  tk) "void"
    (= :any    tk) "id"
    (= :bytes  tk) "NSMutableData *"
    (= :block  tk) "void (^)(void)"
    ;; Width variants carry their C type in metadata (Landing 1b).
    (and (vector? tk) (:cxc-c-type (meta tk))) (:cxc-c-type (meta tk))
    (and (map? tk) (:record tk)) (:record tk)
    (and (map? tk) (:atom   tk)) (str (:atom tk) " *")
    (and (map? tk) (:enum   tk)) (:enum tk)
    :else (fail-nyi! (str "unrepresentable observable type " (pr-str tk))
                     node)))

(defn- map-key-val-of
  "If ty-node is `Map of (K, V)`, return {:map-key K-name :map-val
   V-name}; else return {}. Used to decorate fields/ctor-params so
   downstream map-container instantiation collection can recover K/V
   from the mangled type without re-parsing the name."
  [ty-node]
  (if (and (= :type-of (:node ty-node))
           (= "Map" (:outer ty-node))
           (= :type-tuple (get-in ty-node [:inner :node])))
    (let [[k v] (get-in ty-node [:inner :types])]
      {:map-key (base-type-name k)
       :map-val (base-type-name v)})
    {}))

(defn- fields-from-data-section
  [data-section record-env atom-env enum-env]
  (mapv (fn [f]
          (merge {:name      (:name f)
                  :cxc-ty    (base-type-name (:type f))
                  :nullable? (= :type-nullable (get-in f [:type :node]))
                  ;; F39 L2 — record `T!` intent on the field; enforcement
                  ;; wires in L3 (observation-point runtime checks).
                  :nonnil?   (= :type-nonnil (get-in f [:type :node]))
                  :c-type    (cxc-type->c (:type f) record-env atom-env enum-env)
                  :default   (:default f)}
                 (map-key-val-of (:type f))))
        (:fields data-section)))

(defn- normalize-method-param
  "Parser hands us `self` as {:node :param :name \"self\"} and typed params
   as {:node :field :name ... :type ...}. Normalize to {:name :cxc-ty
   :is-self? :default}. `:default` preserves the parser's default-
   expr node (nil when absent) so `resolve-args-against-params` can
   insert it at call sites where the arg is omitted (spec §3.8.5)."
  [enclosing-record-name p]
  (if (= "self" (:name p))
    {:name "self" :cxc-ty enclosing-record-name :is-self? true :default nil}
    {:name (:name p) :cxc-ty (:name (:type p)) :is-self? false
     :default (:default p)}))

(defn- extract-methods
  "Return [{:name :params [{:name :cxc-ty :is-self?}] :cxc-return-ty
   :cxc-return-ty-node :body}] from a record's :methods-section (may
   be nil). `:cxc-return-ty` carries the outer type name (works for
   both `:type-ref` and `:type-of` — for `-> Stream of Instant`, it's
   `\"CxcStream\"` after prelude-rename). `:cxc-return-ty-node`
   retains the full type node so Layer 6's method-stream payload
   inference can read the parametric inner type."
  [record-name methods-section]
  (mapv (fn [m]
          (let [rt          (:return-type m)
                user-params (remove #(= "self" (:name %)) (:params m))
                site        (str "method `" record-name "." (:name m) "`")]
            (validate-defaults-after-required! site user-params)
            (validate-default-is-const!       site user-params)
            {:name               (:name m)
             :params             (mapv #(normalize-method-param record-name %) (:params m))
             :cxc-return-ty      (case (:node rt)
                                   :type-ref      (:name rt)
                                   :type-of       (:outer rt)
                                   :type-nullable (base-type-name rt)
                                   :type-nonnil   (base-type-name rt)
                                   nil            nil
                                   (:name rt))
             :cxc-return-ty-node rt
             :body               (:body m)
             ;; F9 L4 — carry `replace` intent through composition so
             ;; the shadowing rules can consult it. Absent on non-
             ;; extension entries (nil is the "no intent" signal).
             :replace?           (boolean (:replace? m))}))
        (:methods methods-section)))

(defn- handler-decl->method-entry
  "F14 — convert an atom-body `-> name(args):` handler declaration
   into a method-shaped entry compatible with `:methods` in atom-env.
   Handlers are externally-invocable side-effect-only methods (spec
   §1265); `:cxc-return-ty nil` marks void return, `:handler? true`
   is provenance for future wire-attachment work. `self` is implicit
   on handlers (spec §1265; user writes `-> log_it(msg: String):`) —
   we synthesize the self-param at position 0 to match the methods-
   section entry shape downstream `lower-atom-method` expects."
  [atom-name hd-node]
  (let [site         (str "handler `" atom-name ".-> " (:name hd-node) "`")
        user-params  (:params hd-node)
        self-param   {:name "self" :type nil :default nil}
        with-self    (into [self-param] user-params)]
    (validate-defaults-after-required! site user-params)
    (validate-default-is-const!        site user-params)
    {:name               (:name hd-node)
     :params             (mapv #(normalize-method-param atom-name %) with-self)
     :cxc-return-ty      nil
     :cxc-return-ty-node nil
     :body               (:body hd-node)
     :replace?           false
     :handler?           true}))

(defn- build-record-env
  [top-nodes atom-env enum-env]
  (let [records  (filter #(= :record (:node %)) top-nodes)
        skeleton (into {} (map (fn [r]
                                 [(:name r) {:fields [] :field-index {}
                                             :methods [] :method-index {}
                                             :variant :struct}])
                               records))]
    (reduce
     (fn [env r]
       (let [name    (:name r)
             dsec    (first (filter #(= :data-section    (:node %)) (:sections r)))
             msec    (first (filter #(= :methods-section (:node %)) (:sections r)))
             fields  (fields-from-data-section dsec env atom-env enum-env)
             site    (str "record `" name "` fields (auto-ctor)")
             _       (validate-defaults-after-required! site fields)
             _       (validate-default-is-const!       site fields)
             methods (extract-methods name msec)
             ;; F38 auto-detect: any method → :class variant, else :struct.
             ;; Structural trigger, no user syntax.
             variant (if (seq methods) :class :struct)]
         (assoc env name {:fields       fields
                          :field-index  (into {} (map (juxt :name identity) fields))
                          :methods      methods
                          :method-index (into {} (map (juxt :name identity) methods))
                          :variant      variant})))
     skeleton
     records)))

;; ---------------------------------------------------------------------------
;; F9 L2 — protocol-env: name -> {:accepts :accepts-index :conforms :defaults}
;;
;;   :accepts        vec of {:name :params :return-type} — method signatures
;;                   the protocol requires (from `accepts:` / `accepts <sig>`)
;;   :accepts-index  {name -> entry} for O(1) lookup + dupe detection
;;   :conforms       vec of type-nodes this protocol extends (protocol->protocol
;;                   subtyping; §3.4.5). Validated in L5.
;;   :defaults       raw methods-section entries (default implementations).
;;                   Parsed and stored here; inheritance semantics land in L6.
;;
;; Type-resolution against protocol accepts entries is deferred — L2 only
;; establishes the registry. L5+ validates conforming atoms cover it.
;; ---------------------------------------------------------------------------

(defn- protocol-classes [top-nodes]
  (filter (fn [n] (and (= :class (:node n)) (= :protocol (:kind n))))
          top-nodes))

(defn- validate-unique-accepts! [proto-name entries]
  (let [names (map :name entries)
        dupes (->> names frequencies (filter #(> (val %) 1)) (map key))]
    (when (seq dupes)
      (fail-compile! :cxc-f9-duplicate-accepts
                     (str "protocol `" proto-name "` declares `accepts` entry `"
                          (first dupes) "` more than once — each selector "
                          "appears at most once in a protocol's surface. "
                          "See spec §3.4.5.")
                     {:proto proto-name :dupes (vec dupes)}))))

(defn- build-protocol-env
  "Scan top-level `:class` nodes with `:kind :protocol` and build the
   protocol-env. L2 is registration-only: no cross-protocol conformance
   validation (L5), no default-method inheritance (L6), no dispatch
   wiring (L4)."
  [top-nodes]
  (reduce
   (fn [env p]
     (let [nm       (:name p)
           accepts  (vec (:accepts p))
           _        (validate-unique-accepts! nm accepts)
           msec     (first (filter #(= :methods-section (:node %)) (:body p)))
           defaults (vec (:methods msec))
           conforms (vec (:conforms p))]
       (assoc env nm {:accepts       accepts
                      :accepts-index (into {} (map (juxt :name identity)
                                                   accepts))
                      :conforms      conforms
                      :defaults      defaults})))
   {}
   (protocol-classes top-nodes)))

;; ---------------------------------------------------------------------------
;; F9 L3 — extension-env: target-name -> [<:extend-class node>, ...].
;;
;; Multiple extensions per target are legal per §3.3.2 (rules 1-7 handle
;; conflict resolution in L4). L3 is registration-only: we just collect
;; the raw parsed nodes keyed by target name and verify the target
;; resolves against the full symbol table (record-env ∪ atom-env ∪
;; protocol-env). Composition — merging extension methods into the
;; target's method table, applying `replace` shadowing, running
;; conflict resolution — lands in L4.
;;
;; Unknown target-name is a hard error at lower (`extend class BogusFoo:`
;; with no matching class in scope). Parse can't check because it doesn't
;; know the symbol table; lower runs after all envs are built.
;; ---------------------------------------------------------------------------

(defn- extension-nodes [top-nodes]
  (filter #(= :extend-class (:node %)) top-nodes))

(defn- build-extension-env
  "Collect all `extend class Foo:` blocks keyed by target name. Validates
   that each target resolves against the record/atom/protocol envs. Does
   NOT merge extension methods into the target's method table — that's
   L4 composition."
  [top-nodes record-env atom-env protocol-env]
  (let [extensions (extension-nodes top-nodes)
        known?     (fn [nm]
                     (or (contains? record-env   nm)
                         (contains? atom-env     nm)
                         (contains? protocol-env nm)))]
    (doseq [e extensions]
      (when-not (known? (:target e))
        (fail-compile! :cxc-f9-unknown-extension-target
                       (str "`extend class " (:target e) ":` targets unknown "
                            "class `" (:target e) "` — no record, atom, or "
                            "protocol with that name is in scope. Check "
                            "imports and typo. See spec §3.3.2.")
                       e)))
    (reduce (fn [env e] (update env (:target e) (fnil conj []) e))
            {}
            extensions)))

;; ---------------------------------------------------------------------------
;; F9 L4 — extension composition (single-module) + `replace` shadowing.
;;
;; For each extension target that resolves to a record or atom, merge the
;; target's own method table with the extension-declared methods, applying
;; §3.3.2 rules 1-7:
;;
;;   Rule 1 — Own definition wins over extension.
;;   Rule 2 — Same-name methods in one body → hard error (parse-time; already
;;            fires for records/atoms; per-extension bodies re-validated here).
;;   Rule 3 — `replace` on an extension winner suppresses the shadowing
;;            warning IF it actually shadows something; otherwise
;;            `replace-without-target` warning fires.
;;   Rule 5 — Among competing extensions of the same rank, later declaration
;;            wins.
;;   Rule 6 — Protocol default vs. extension is L6's story.
;;   Rule 7 — Cross-file / cross-module priority is L8.
;;
;; Extension `conforms:` union into the target's `:conforms`. Extension
;; invariants on records fail-nyi (records have no runtime invariant path
;; yet). Extension methods on protocol targets don't compose here (their
;; role as defaults is L6); L4 only touches record + atom targets.
;;
;; Warnings go to *err* (mirrors warn-sync-send-cycles!) — non-fatal.
;; ---------------------------------------------------------------------------

(defn- warn! [msg]
  (binding [*out* *err*]
    (println (str "warning: " msg))))

(defn- extension-methods-for
  "Return a flat vector of extension methods (in env shape, with :replace?)
   for `target-name`, in declaration order across all its extensions."
  [target-name extension-env]
  (vec
   (mapcat
    (fn [ext]
      (extract-methods
       target-name
       (first (filter #(= :methods-section (:node %)) (:body ext)))))
    (get extension-env target-name []))))

(defn- extension-conforms-for
  "Return all extension-added conformances for `target-name`, in
   declaration order across extensions."
  [target-name extension-env]
  (vec (mapcat :conforms (get extension-env target-name []))))

(defn- pick-winner-with-warnings!
  "For a group of same-name declarations (each tagged `:__source` :own
   or :ext), pick the winner per §3.3.2 rules 1/3/5 and emit warnings
   for losers. Returns the winning method entry (with :__source
   stripped)."
  [target-name method-name decls]
  (let [replaces (filter :replace? decls)
        own-decl (first (filter #(= :own (:__source %)) decls))
        ext-decls (filter #(= :ext (:__source %)) decls)]
    (cond
      ;; Single declaration — no conflict. Replace-without-target if
      ;; a lone extension declaration carries `replace?`.
      (= 1 (count decls))
      (let [d (first decls)]
        (when (:replace? d)
          (warn! (str "`replace` on `" method-name "` in extension of `"
                      target-name "` has no target to shadow — "
                      "replace-without-target. Drop the `replace` "
                      "modifier or verify the target name. "
                      "See §3.3.2 rule 3.")))
        (dissoc d :__source))

      ;; Any `replace` extension present — last replace wins.
      (seq replaces)
      (do
        ;; If a replace-flagged extension exists among multiple decls
        ;; there IS a target; suppress the shadow warning for the
        ;; winner. Losers among non-replace extensions still lose but
        ;; silently (the user documented replace intent).
        (dissoc (last replaces) :__source))

      ;; Own present, no replace — own wins; warn on shadow extensions.
      own-decl
      (do
        (doseq [_ ext-decls]
          (warn! (str "extension of `" target-name "` declares `"
                      method-name "` which shadows the class's own "
                      "definition and is not marked `replace` — the "
                      "own definition wins. Add `replace` to the "
                      "extension method to shadow deliberately. "
                      "See §3.3.2 rule 1.")))
        (dissoc own-decl :__source))

      ;; Only extensions, none replace — last wins; warn on earlier.
      :else
      (do
        (doseq [_ (butlast ext-decls)]
          (warn! (str "extension of `" target-name "` declares `"
                      method-name "`, shadowed by a later extension. "
                      "Add `replace` to the winning declaration to "
                      "document the shadow. See §3.3.2 rule 5.")))
        (dissoc (last ext-decls) :__source)))))

(defn- compose-target-methods
  "Compose own methods with extension-declared methods for one target.
   Emits shadowing / replace-without-target warnings. Returns the
   composed method vector in a deterministic order: winners appear in
   the order their winning declaration was seen (own methods first,
   then per-extension in declaration order)."
  [target-name own-methods ext-methods]
  (let [tagged  (concat (map #(assoc % :__source :own) own-methods)
                        (map #(assoc % :__source :ext) ext-methods))
        by-name (reduce (fn [m d]
                          (update m (:name d) (fnil conj []) d))
                        (array-map)   ; preserve first-seen order
                        tagged)
        winners (reduce-kv
                 (fn [acc nm decls]
                   (assoc acc nm (pick-winner-with-warnings!
                                  target-name nm decls)))
                 (array-map)
                 by-name)]
    (vec (vals winners))))

(defn- extension-has-invariants? [ext]
  (some #(= :invariants-section (:node %)) (:body ext)))

(defn- compose-record-env-with-extensions
  "Post-composition rebuild of record-env: for each record target with
   extensions, replace its :methods + :method-index with the composed
   table, union :conforms, and (F38) re-detect :variant since new
   methods may promote struct → class."
  [record-env extension-env]
  (reduce-kv
   (fn [env target rec]
     (if-let [exts (seq (get extension-env target))]
       (let [_          (doseq [e exts]
                          (when (extension-has-invariants? e)
                            (fail-nyi!
                             (str "`invariants:` inside `extend class "
                                  target "` — record targets have no "
                                  "runtime invariant path yet. F9 L4 "
                                  "composes method tables and conforms; "
                                  "record-invariant emission is a "
                                  "follow-on sub-landing.")
                             e)))
             ext-methods (extension-methods-for target extension-env)
             composed    (compose-target-methods target (:methods rec) ext-methods)
             new-conforms (into (vec (:conforms rec))
                                (extension-conforms-for target extension-env))
             variant     (if (seq composed) :class :struct)]
         (assoc env target
                (assoc rec
                       :methods       composed
                       :method-index  (into {} (map (juxt :name identity) composed))
                       :conforms      new-conforms
                       :variant       variant)))
       env))
   record-env
   record-env))

(defn- compose-atom-env-with-extensions
  "Same as compose-record-env-with-extensions for atom-env. Atoms already
   support invariants natively, so extension invariants can compose into
   the existing invariant list (append in extension declaration order)."
  [atom-env extension-env]
  (reduce-kv
   (fn [env target entry]
     (if-let [exts (seq (get extension-env target))]
       (let [ext-methods (extension-methods-for target extension-env)
             composed    (compose-target-methods target (:methods entry) ext-methods)
             new-conforms (into (vec (:conforms entry))
                                (extension-conforms-for target extension-env))
             ext-invs    (vec (mapcat (fn [e]
                                        (:predicates
                                         (first
                                          (filter #(= :invariants-section (:node %))
                                                  (:body e)))))
                                      exts))
             new-invs    (into (vec (:invariants entry)) ext-invs)]
         (assoc env target
                (assoc entry
                       :methods       composed
                       :method-index  (into {} (map (juxt :name identity) composed))
                       :conforms      new-conforms
                       :invariants    new-invs)))
       env))
   atom-env
   atom-env))

;; ---------------------------------------------------------------------------
;; F9 L5 — explicit conformance validation.
;;
;; For every class carrying `:conforms [P1, P2, ...]` (from header parens,
;; body-form `conforms:`, or extension-added conformance), validate at
;; the class declaration site that the class satisfies each protocol
;; structurally per §3.4.5.
;;
;; Validation runs *after* L4 composition — so extension-added methods
;; count toward satisfying the protocol surface.
;;
;; Rules:
;;   1. Protocol name must resolve in protocol-env; unknown → fail-compile.
;;   2. For every accepts entry P.foo the class must have (a) an own or
;;      extension-provided method named `foo`, or (b) a protocol default
;;      for `foo` (default-inheritance is L6 — for L5 we accept the
;;      structural presence of the default as satisfaction).
;;   3. When present on the class, the method's signature (param names,
;;      param types, return type) must match the accepts entry.
;;
;; Effect-set matching (`async` / `io`) — deferred until effects are
;; first-class in the parser (L2 rejects contract clauses; effects fall
;; out with F49 protocol-contract-inheritance).
;;
;; Diamond ambiguity is NOT a class-declaration error (§3.4.4 makes it a
;; call-site error). L5 checks each conformance independently; L6 wires
;; call-site resolution + diamond detection.
;; ---------------------------------------------------------------------------

(defn- type-node-name
  "Extract the outer type name from an AST type-node. Nil-safe."
  [ty]
  (when ty
    (case (:node ty)
      :type-ref      (:name ty)
      :type-of       (:outer ty)
      :type-nullable (base-type-name ty)
      :type-nonnil   (base-type-name ty)
      nil)))

(defn- non-self-params
  "Filter out `self` — it's implicit in both class methods (marked
   `:is-self? true` by normalize-method-param) and protocol accepts
   entries (users may write `foo(self, x: Int)` or the shorter
   `foo(x: Int)` since self is implied)."
  [params]
  (remove #(or (:is-self? %) (= "self" (:name %))) params))

(defn- signature-mismatch-detail
  "Return a human-readable string describing the first signature
   mismatch between a composed-table method and a protocol accepts
   entry, or nil if they match."
  [method accepts-entry]
  (let [method-params  (non-self-params (:params method))
        accepts-params (non-self-params (:params accepts-entry))
        expected-ret   (type-node-name (:return-type accepts-entry))
        actual-ret     (:cxc-return-ty method)]
    (cond
      (not= (count method-params) (count accepts-params))
      (str "expected " (count accepts-params) " param(s), got "
           (count method-params))

      (not= expected-ret actual-ret)
      (str "return type mismatch — expected `" expected-ret
           "`, got `" actual-ret "`")

      :else
      (some (fn [[mp ap]]
              (let [expected-ty (type-node-name (:type ap))]
                (cond
                  (not= (:name mp) (:name ap))
                  (str "param name mismatch — expected `" (:name ap)
                       "`, got `" (:name mp) "`")
                  (not= (:cxc-ty mp) expected-ty)
                  (str "param `" (:name ap) "` type mismatch — expected `"
                       expected-ty "`, got `" (:cxc-ty mp) "`"))))
            (map vector method-params accepts-params)))))

(defn- validate-one-conformance!
  "Validate one class-conforms-to-protocol claim. Throws on failure
   with a targeted §3.4.5 diagnostic. Composed-methods is the
   post-L4 method vector for the class."
  [class-name protocol-name composed-methods protocol-env]
  (let [proto (get protocol-env protocol-name)]
    (when-not proto
      (fail-compile! :cxc-f9-unknown-protocol-conformance
                     (str "class `" class-name "` declares conformance to "
                          "unknown protocol `" protocol-name "` — check "
                          "imports and typo. See spec §3.4.5.")
                     {:class class-name :protocol protocol-name}))
    (let [method-by-name (into {} (map (juxt :name identity) composed-methods))
          defaults-by-name (into {} (map (juxt :name identity) (:defaults proto)))]
      (doseq [ae (:accepts proto)]
        (let [ae-name (:name ae)
              m       (get method-by-name ae-name)]
          (cond
            m
            (when-let [detail (signature-mismatch-detail m ae)]
              (fail-compile! :cxc-f9-signature-mismatch
                             (str "class `" class-name "." ae-name
                                  "` does not match protocol `" protocol-name
                                  "`'s accepts declaration — " detail
                                  ". See spec §3.4.5.")
                             {:class class-name :protocol protocol-name
                              :method ae-name}))

            ;; L6 wires the actual inheritance; L5 accepts the default's
            ;; presence as structural satisfaction.
            (contains? defaults-by-name ae-name)
            :ok

            :else
            (fail-compile! :cxc-f9-missing-conformance-method
                           (str "class `" class-name "` claims conformance "
                                "to `" protocol-name "` but neither "
                                "implements nor inherits a default for `"
                                ae-name "`. Either add `def " ae-name
                                "(...)` on `" class-name "` (or an "
                                "extension), or provide a default in `"
                                protocol-name "`. See spec §3.4.5.")
                           {:class class-name :protocol protocol-name
                            :missing ae-name})))))))

(defn- validate-explicit-conformances!
  "Walk record-env + atom-env post-L4-composition and validate every
   `:conforms` entry against protocol-env. Throws on the first
   failure with a targeted §3.4.5 diagnostic."
  [record-env atom-env protocol-env]
  (doseq [[class-name entry] record-env
          proto-ty (:conforms entry)]
    (validate-one-conformance! class-name (type-node-name proto-ty)
                               (:methods entry) protocol-env))
  (doseq [[class-name entry] atom-env
          proto-ty (:conforms entry)]
    (validate-one-conformance! class-name (type-node-name proto-ty)
                               (:methods entry) protocol-env)))

;; ---------------------------------------------------------------------------
;; F9 L6 — default-method inheritance (explicit conformance).
;;
;; For each class with an explicit `:conforms [P1, P2, ...]`, inherit
;; every P.defaults[m] where `m` is not already in the class's composed
;; method table (from L4 own+extension composition). The inherited
;; default's body is re-extracted with the *class* name so `self`
;; type-resolves against the concrete class (rather than the protocol).
;;
;; **Diamond ambiguity** — if two or more explicitly-conformed protocols
;; both provide a default for the same method name AND the class does
;; not override it, fail-compile with a §3.4.4 diamond diagnostic naming
;; the candidates. Resolution paths are documented in the error:
;;   (a) define the method on the class (or via extension), or
;;   (b) drop conformance to one of the ambiguous protocols.
;;
;; L6 MVP does explicit-conformance-only. Structural conformance
;; detection at call sites (a class that satisfies P.accepts structurally
;; but doesn't declare `conforms: P` — inheriting P's defaults implicitly)
;; is deferred to L6b (per-lookup structural check + cache).
;;
;; Emission: inherited defaults get the same treatment as own methods
;; — they land in the class's :methods list and emit via
;; record-method-functions / lower-atom's method emission path.
;; ---------------------------------------------------------------------------

(defn- default-candidates-for-method
  "For a class with explicit conforms `conforms-tys`, return the vector
   of protocol names that (a) the class conforms to AND (b) provide a
   default for `method-name`. Unknown protocols are skipped silently
   (L5 already fired on them if the class reached this pass)."
  [method-name conforms-tys protocol-env]
  (vec (for [ty    conforms-tys
             :let  [pn    (type-node-name ty)
                    proto (get protocol-env pn)]
             :when (and proto
                        (some #(= method-name (:name %))
                              (:defaults proto)))]
         pn)))

(defn- extract-default-as-method
  "Given a raw default method node (parser shape :node :function ...)
   and the concrete class name, run it through extract-methods so
   `self` gets `:cxc-ty <class-name>` and downstream lowering treats
   it as a normal class method."
  [class-name default-node]
  (first (extract-methods class-name
                          {:node :methods-section :methods [default-node]})))

(defn- class-satisfies-protocol-structurally?
  "L6b — true if every accepts entry in `proto` has a matching method
   (name + signature) in `class-methods`. Empty accepts → trivially
   true (a protocol with no requirements is satisfied by every class,
   but such a protocol is pointless and this predicate is only called
   under `structural-conformances-with-defaults` which also gates on
   `(seq (:defaults proto))`, so the pathological case is filtered
   out upstream). Reuses `signature-mismatch-detail` — L5's signature-
   match rule (name, param names, param types, return type)."
  [class-methods proto]
  (let [by-name (into {} (map (juxt :name identity) class-methods))]
    (every? (fn [ae]
              (when-let [m (get by-name (:name ae))]
                (nil? (signature-mismatch-detail m ae))))
            (:accepts proto))))

(defn- structural-conformances-with-defaults
  "L6b — return the set of protocol names the class structurally
   conforms to (satisfies all accepts) that also have at least one
   default AND are not already in the class's explicit conformance
   set. Protocols with no defaults are skipped — structural
   conformance without inheritable defaults isn't observable here
   (dispatch-only structural conformance is a call-site concern).
   O(|protocols| × |accepts| × |methods|) per class — negligible at
   compile-scale."
  [class-methods explicit-name-set protocol-env]
  (reduce-kv
   (fn [acc pn proto]
     (if (and (not (contains? explicit-name-set pn))
              (seq (:defaults proto))
              (class-satisfies-protocol-structurally? class-methods proto))
       (conj acc pn)
       acc))
   #{}
   protocol-env))

(defn- collect-defaults-by-name
  "For a seq of protocol names, collect (default-name → [candidates])
   from each protocol's defaults, filtering out names the class
   already owns. `candidates` carry both the protocol name and the
   raw default node so downstream can extract the body per-class."
  [protocol-names own-names protocol-env]
  (reduce
   (fn [acc pn]
     (let [proto (get protocol-env pn)]
       (reduce
        (fn [a d]
          (let [mn (:name d)]
            (if (contains? own-names mn)
              a
              (update a mn (fnil conj [])
                      {:protocol pn :node d}))))
        acc
        (:defaults proto))))
   (array-map)
   protocol-names))

(defn- inherit-defaults-for-class
  "For one class entry, produce an updated :methods list with any
   applicable protocol defaults inherited. L6 covers explicit
   `:conforms`; L6b widens to include protocols the class satisfies
   structurally (has all accepts methods with matching signatures).
   Fires the diamond diagnostic on multi-candidate conflicts —
   whether the candidates came from explicit or structural paths
   doesn't change the ambiguity rule (§3.4.4 call-site error;
   detected at class-composition site here). Returns the class entry
   unchanged when no inheritance applies."
  [class-name entry protocol-env]
  (let [own-names        (set (map :name (:methods entry)))
        explicit-conforms (:conforms entry)
        explicit-set     (into #{} (map type-node-name explicit-conforms))
        ;; L6b — widen with structural conformances that have defaults.
        structural-set   (structural-conformances-with-defaults
                          (:methods entry) explicit-set protocol-env)
        all-protos       (concat (map type-node-name explicit-conforms)
                                 structural-set)
        defaults-by-name (collect-defaults-by-name all-protos own-names
                                                   protocol-env)
        _ (doseq [[mn cands] defaults-by-name]
            (when (> (count cands) 1)
              (fail-compile!
               :cxc-f9-diamond-ambiguity
               (str "diamond ambiguity: method `" mn "` on class `"
                    class-name "` has default definitions in multiple "
                    "conforming protocols ("
                    (str/join ", " (map #(str "`" (:protocol %) "`") cands))
                    "). At least one of these conformances may be "
                    "structural (F9 L6b) — check `accepts:` signatures. "
                    "Resolve by (a) defining `" mn "` on `" class-name
                    "` directly (own or via extension — this shadows all "
                    "defaults per §3.4.4), (b) dropping conformance "
                    "to an ambiguous protocol, or (c) changing a "
                    "method signature so structural conformance no "
                    "longer matches. See spec §3.4.4.")
               {:class class-name :method mn
                :candidates (mapv :protocol cands)})))
        inherited (mapv (fn [[_ cands]]
                          (extract-default-as-method
                           class-name (:node (first cands))))
                        defaults-by-name)]
    (if (seq inherited)
      (let [new-methods (into (vec (:methods entry)) inherited)]
        (assoc entry
               :methods       new-methods
               :method-index  (into {} (map (juxt :name identity) new-methods))
               ;; If inheriting methods promoted the class to method-
               ;; carrying, keep F38 variant auto-detect consistent.
               :variant       (if (seq new-methods) :class :struct)))
      entry)))

(defn- inherit-defaults-into-env
  "Walk an env (record-env or atom-env) and produce an updated env
   with inherited default methods folded into each class's :methods."
  [env protocol-env]
  (reduce-kv
   (fn [acc class-name entry]
     (assoc acc class-name (inherit-defaults-for-class
                            class-name entry protocol-env)))
   {}
   env))

(defn- def-atom-data-section [atom-node]
  (first (filter #(= :data-section (:node %)) (:body atom-node))))

(defn- ctor-params-normalized
  "Def-atom :ctor-params come from the parser as `:field` nodes
   (`{:node :field :name :type :default}`). Normalize to
   `{:name :cxc-ty :nullable? :c-type :default}` for the atom-env."
  [ctor-params record-env atom-env enum-env]
  (mapv (fn [p]
          (merge {:name      (:name p)
                  :cxc-ty    (base-type-name (:type p))
                  :nullable? (= :type-nullable (get-in p [:type :node]))
                  ;; F39 L2 — carry `T!` intent on ctor params too.
                  ;; Runtime binding check wires in L3.
                  :nonnil?   (= :type-nonnil (get-in p [:type :node]))
                  :c-type    (cxc-type->c (:type p) record-env atom-env enum-env)
                  :default   (:default p)}
                 (map-key-val-of (:type p))))
        ctor-params))

(defn- def-atom-methods-section [atom-node]
  (first (filter #(= :methods-section (:node %)) (:body atom-node))))

(defn- def-atom-transitions [atom-node]
  (filterv #(= :transition (:node %)) (:body atom-node)))

(defn- def-atom-observables [atom-node]
  (filterv #(= :observable (:node %)) (:body atom-node)))

(defn- def-atom-invariants
  "Return the predicate list from the atom's invariants: section (empty
   if absent). Section is unique — parser emits at most one."
  [atom-node]
  (or (:predicates (first (filter #(= :invariants-section (:node %))
                                  (:body atom-node))))
      []))

(defn- def-atom-use-caps
  "Return the capability list from the atom's use: section (empty if
   absent). Each cap is {:name :type}."
  [atom-node]
  (or (:caps (first (filter #(= :use-section (:node %)) (:body atom-node))))
      []))

(defn- def-atom-nested-slots
  "Return the `def <lowercase>: <TypeRef>[(args)]` slots from the atom
   body. Parser tags these as `:nested-atom`."
  [atom-node]
  (filterv #(= :nested-atom (:node %)) (:body atom-node)))

(defn- payload-signature
  "Compact comparable representation of a payload shape — vector of
   `[name cxc-type]` pairs in declaration order. Used to catch
   inconsistent payloads across transitions with the same message name."
  [payload]
  (mapv (fn [p] [(:name p) (base-type-name (:type p))]) payload))

(defn- emit-nodes-in
  "Walk any parsed AST subtree and return every `:emit` node whose
   `:target` is nil (untargeted `:>` / `|>` — stream emissions). Skips
   targeted sends. Used both to discover the streams a source atom
   owns (Step 13) and to gate `dispatch_main()` on presence of any
   async work."
  [subtree]
  (let [found (java.util.ArrayList.)]
    (walk/postwalk
     (fn [n]
       (when (and (map? n) (= :emit (:node n)) (nil? (:target n)))
         (.add found n))
       n)
     subtree)
    (vec found)))

(defn- collect-stream-shapes
  "Walk every transition body on this atom, gather all `:>` untargeted
   emit sites, and index them by stream name with payload signature.
   Two emits of the same stream name with different arg-name shapes are
   a design smell — same source-line reads would produce different
   subscriber-side selectors. Fail-nyi on mismatch, parallel to
   collect-message-shapes. Returns `{stream-name {:payload [{:name
   :type}]}}` where each payload entry captures `:name` and an inferred
   `:type` when the arg carried a `k: T` typed shape. Layer 4 payloads
   are typed via the same `{k: T}` grammar receivers use on the receive
   side; if the sender's args-block doesn't carry type info, the payload
   entry has :type nil and downstream lookup falls back on the
   subscriber-side declaration for the C type."
  [atom-name transitions]
  (let [emits (emit-nodes-in transitions)
        ;; Sync `|>` untargeted emits aren't defined for streams (spec
        ;; only ever pairs bare `|>` with a target). Reject early so
        ;; the meaning stays crisp: `:>` is fan-out, no other option.
        _     (doseq [e emits]
                (when-not (:async? e)
                  (fail-nyi! (str "sync stream emit `|> " (:msg e)
                                  "` — Layer 4 streams are async-only "
                                  "(design §18 chose Option A: async fan-out)")
                             e)))]
    (reduce
     (fn [acc e]
       (let [name    (:msg e)
             ;; Emit-site args have shape [{:kind :named/:positional
             ;; :name :value}]. Only named args carry a stream-payload
             ;; key. Positional args on stream emits are legal (spec
             ;; allows bare `:> value` for single-channel), but Layer
             ;; 4 requires named args so the payload shape is
             ;; unambiguous — same rule as message-triggered
             ;; transitions.
             _       (doseq [a (:args e)]
                       (when (= :positional (:kind a))
                         (fail-nyi!
                          (str "positional arg on stream emit `:> " name
                               "` — Layer 4 requires `:> " name "(key: value)` "
                               "named form so payload shape matches subscriber "
                               "receive pattern")
                          e)))
             payload (mapv (fn [a] {:name (:name a)}) (:args e))
             sig     (mapv :name payload)]
         (if-let [existing (get acc name)]
           (do (when (not= (mapv :name (:payload existing)) sig)
                 (fail-nyi!
                  (str "def-atom `" atom-name "` emits stream `" name
                       "` twice with different payload shapes: "
                       (mapv :name (:payload existing)) " vs " sig
                       "; every emit of a stream must carry the same "
                       "keys so subscribers can bind a single receive shape")
                  {:atom atom-name :stream name}))
               acc)
           (assoc acc name {:payload payload}))))
     {}
     emits)))

(defn- collect-message-shapes
  "Walk an atom's message-triggered transitions, grouping by message
   name. Every transition sharing a message name must declare the SAME
   payload shape — different shapes would produce different ObjC
   selectors from cxc source that reads identically, which is a design
   smell. Returns `{msg-name {:payload [{:name :type}] :selector <str>}}`
   for later selector encoding + arg marshalling."
  [atom-name transitions]
  (let [msg-txns (filter #(and (:receive %)
                               (contains? #{"<:" "<|"}
                                          (get-in % [:receive :glyph]))
                               ;; Ident source only: `<: msg` (internal msg).
                               ;; Dot sources (`<: foo.bar`) are external
                               ;; stream subscriptions, handled by the
                               ;; subscription-map pass, not here.
                               (= :ident (get-in % [:receive :msg :source :node])))
                         transitions)]
    (reduce
     (fn [acc t]
       (let [src (get-in t [:receive :msg :source])
             msg-name (:name src)          ; already gated to :ident nodes by transition-msg-name
             payload  (or (get-in t [:receive :msg :payload]) [])]
         (if-let [existing (get acc msg-name)]
           (do (when (not= (payload-signature (:payload existing))
                           (payload-signature payload))
                 (fail-nyi!
                  (str "def-atom `" atom-name "` has two transitions on message `"
                       msg-name "` with different payload shapes: "
                       (payload-signature (:payload existing)) " vs "
                       (payload-signature payload) "; cxc source that reads "
                       "the same must map to the same ObjC selector")
                  {:atom atom-name :msg msg-name}))
               acc)
           (assoc acc msg-name {:payload payload}))))
     {}
     msg-txns)))

(defn- def-atom-handler-decls
  "F14 — `-> name(args):` handler declarations at atom-body top level."
  [atom-node]
  (filterv #(= :handler-decl (:node %)) (:body atom-node)))

(defn- def-atom-body-extras
  "Everything in the def-atom body that isn't a section lower knows
   how to consume — e.g. contracts: (Layer 6+). Deprecated: is
   handled at the build/compile step (cxc.build/warn-deprecated-uses)
   and doesn't reach lower, so it's filtered here too. Handler-decls
   are consumed separately by F14."
  [atom-node]
  (remove #(#{:data-section :methods-section :transition :observable
              :invariants-section :use-section :nested-atom
              :handler-decl :deprecated} (:node %))
          (:body atom-node)))

;; ---------------------------------------------------------------------------
;; F36 Landing 2a — invariant-driven representation picker (§3.1.2 rule 3).
;;
;; For each primitive-typed `data:` field, walk the atom's invariants for
;; chained-comparison predicates that constrain the field to a range that
;; fits a sized atom (UInt8/UInt16/... or Int8/Int16/...). Narrow the
;; field's storage to that sized atom.
;;
;; Safety: F49 Landing A's pre-store check runs on the wide source value
;; before the narrowing assignment, so a source value that doesn't fit
;; the narrowed storage aborts at construction (not silently truncates).
;; Without F49 A, this narrowing would be a silent-corruption path.
;;
;; MVP form: only recognizes literal bounds (compile-time integer/float
;; constants) and only when BOTH lower AND upper bounds are present.
;; Observable-form invariants (`value :< decode(raw)` with
;; `<= value <=`) are v1.1+ picker work — this pass matches direct
;; field references only.
;; ---------------------------------------------------------------------------

(defn- literal-int?
  [n]
  (and (map? n) (contains? #{:lit-int :int :num} (:node n))))

(defn- literal-int-value
  [n]
  (:value n))

(defn- field-ident?
  [n field-name]
  (and (map? n) (= :ident (:node n)) (= field-name (:name n))))

(defn- neg-of-literal-int?
  "Match `-<literal-int>` — the parser lowers `-128` as `:neg` with
   the operand under `:expr`. Returns the negated value or nil."
  [n]
  (when (and (map? n) (= :neg (:node n)) (literal-int? (:expr n)))
    (- (literal-int-value (:expr n)))))

(defn- int-constant-value
  "Extract a compile-time integer constant from `n` — either a bare
   integer literal or a `-<literal>` unary-neg. Returns nil if `n`
   isn't a recognized integer constant expression."
  [n]
  (cond
    (literal-int? n)         (literal-int-value n)
    (neg-of-literal-int? n)  (neg-of-literal-int? n)
    :else                    nil))

(defn- collect-bounds-for-field
  "Walk `pred` (after chained-comparison desugaring) collecting bounds on
   `field-name`. Returns `{:lo <n or nil> :hi <n or nil>}` — where each
   is the tightest constant discovered across all conjuncts. Predicates
   with a shape the picker doesn't recognize contribute nothing (the
   post-store checkInvariants still evaluates them)."
  [pred field-name]
  (let [acc (atom {:lo nil :hi nil})
        note-lo (fn [v] (swap! acc update :lo (fn [cur] (if cur (max cur v) v))))
        note-hi (fn [v] (swap! acc update :hi (fn [cur] (if cur (min cur v) v))))]
    (walk/postwalk
     (fn [n]
       (when (and (map? n) (= :binop (:node n)))
         (let [op (:op n) L (:left n) R (:right n)
               lv (int-constant-value L) rv (int-constant-value R)]
           (cond
             ;; <lit> op <field>
             (and lv (field-ident? R field-name))
             (case op
               "<=" (note-lo lv)
               "<"  (note-lo (inc lv))
               ">=" (note-hi lv)
               ">"  (note-hi (dec lv))
               nil)
             ;; <field> op <lit>
             (and rv (field-ident? L field-name))
             (case op
               "<=" (note-hi rv)
               "<"  (note-hi (dec rv))
               ">=" (note-lo rv)
               ">"  (note-lo (inc rv))
               nil)
             :else nil)))
       n)
     pred)
    @acc))

(def ^:private sized-int-atoms
  ;; Ordered narrowest → widest so `first` picks the smallest fit.
  ;; Each entry: {:name cxc-atom :lo <inclusive> :hi <inclusive>
  ;;              :c-type <C storage> :cxc-ty <cxc source-level name>}
  [{:name "UInt8"  :lo 0                   :hi 255
    :c-type "uint8_t"  :cxc-ty "UInt8"}
   {:name "Int8"   :lo -128                :hi 127
    :c-type "int8_t"   :cxc-ty "Int8"}
   {:name "UInt16" :lo 0                   :hi 65535
    :c-type "uint16_t" :cxc-ty "UInt16"}
   {:name "Int16"  :lo -32768              :hi 32767
    :c-type "int16_t"  :cxc-ty "Int16"}
   {:name "UInt32" :lo 0                   :hi 4294967295
    :c-type "uint32_t" :cxc-ty "UInt32"}
   {:name "Int32"  :lo -2147483648         :hi 2147483647
    :c-type "int32_t"  :cxc-ty "Int32"}])

(defn- pick-sized-atom
  "Given inclusive `lo`/`hi` bounds, return the entry from
   `sized-int-atoms` whose range fully contains [lo, hi], or nil if
   no narrower-than-Int64 atom fits."
  [lo hi]
  (when (and (integer? lo) (integer? hi) (<= lo hi))
    (first (filter #(and (<= (:lo %) lo) (<= hi (:hi %)))
                   sized-int-atoms))))

(def ^:private picker-eligible-cxc-tys
  ;; Only bare `Int` gets picked — sized atoms already pin their width
  ;; per §3.1.2 rule 1, and non-numeric types don't narrow.
  #{"Int"})

(defn- narrow-fields-by-invariants
  "F36 Landing 2a — for each primitive-int-typed field, walk the atom's
   invariants for chained-comparison bounds on that field's name; if
   the union of bounds fits a sized atom, override the field's
   `:c-type` and `:cxc-ty` to the narrower storage.

   Only bare `Int` (Int64) fields are considered — sized-atom fields
   already pin their width per §3.1.2 rule 1. Fields with only one
   bound (or no bounds) keep Int64 storage — narrowing requires both
   lo AND hi.

   Post-store checkInvariants + F49-A pre-store check both still fire
   on the picked field; a violating source value aborts at
   construction without hitting the narrowing store."
  [fields invariants]
  (mapv
   (fn [f]
     (if-not (contains? picker-eligible-cxc-tys (:cxc-ty f))
       f
       (let [bounds  (reduce (fn [{:keys [lo hi]} p]
                               (let [{plo :lo phi :hi} (collect-bounds-for-field p (:name f))]
                                 {:lo (cond (nil? lo) plo
                                            (nil? plo) lo
                                            :else (max lo plo))
                                  :hi (cond (nil? hi) phi
                                            (nil? phi) hi
                                            :else (min hi phi))}))
                             {:lo nil :hi nil}
                             invariants)
             {:keys [lo hi]} bounds
             picked  (pick-sized-atom lo hi)]
         (if picked
           (assoc f
                  :c-type (:c-type picked)
                  :cxc-ty (:cxc-ty picked)
                  :picker-narrowed-from "Int"
                  :picker-bounds [lo hi])
           f))))
   fields))

(defn- build-atom-env
  "Build the atom-env from every top-level `def X:` — includes `main`
   whenever the program has any other user-declared atoms (so main can
   send to them via the same shape check as any other sender) OR
   whenever main itself declares message-triggered transitions
   (needs a class to receive messages). Prelude atoms are ignored for
   this decision so records-only user programs don't get promoted to
   ObjC classes just because Clock exists in the prelude — user
   programs that DO reference a prelude atom will separately reach
   into it via the shape check and pull main in through referenced
   atoms.

   For pure hello-world (no user atoms, no receive transitions on
   main), main stays out of the env and the old main-atom-body path
   handles it as a plain function body."
  [top-nodes enum-env]
  (let [other-atoms (filter #(and (= :def-atom (:node %))
                                  (not= "main" (:name %))
                                  (not (contains? prelude/atom-names
                                                  (:name %))))
                            top-nodes)
        main-node   (first (filter #(and (= :def-atom (:node %))
                                         (= "main" (:name %)))
                                   top-nodes))
        main-has-message-txn?
        (boolean (some #(and (= :transition (:node %))
                             (contains? #{"<:" "<|"} (get-in % [:receive :glyph])))
                       (:body main-node)))
        ;; A nested slot / use: cap on main brings that atom's lifecycle
        ;; into main's — cascade end + parent-back-ref plumbing require
        ;; main to be a real atom class. This catches the case where
        ;; the user program's ONLY atom references are prelude atoms
        ;; via `def clock: Clock` (post-rename `_Cxc_Clock`).
        main-has-atom-slot-or-cap?
        (boolean (some #(contains? #{:nested-atom :use-section} (:node %))
                       (:body main-node)))
        ;; A List of T field on main is atom-typed (compiler-synth
        ;; container class), so its ivar needs a real class to live on.
        ;; Plain-main-body path can't hold instance state — promote to
        ;; atom class whenever main carries container-typed data fields.
        main-has-list-field?
        (boolean (some (fn [n]
                         (and (= :data-section (:node n))
                              (some #(= :type-of (get-in % [:type :node]))
                                    (:fields n))))
                       (:body main-node)))
        ;; Layer 6 Step 12 — Block-typed data field needs ivar storage
        ;; (ObjC block type `void (^)(void)`), so main must be an atom
        ;; class. Distinct from list-field because Block is a :type-ref
        ;; (a "primitive" in the C mapping), not a :type-of.
        main-has-block-field?
        (boolean (some (fn [n]
                         (and (= :data-section (:node n))
                              (some #(and (= :type-ref (get-in % [:type :node]))
                                          (= "Block" (get-in % [:type :name])))
                                    (:fields n))))
                       (:body main-node)))
        include-main? (or (seq other-atoms)
                          main-has-message-txn?
                          main-has-atom-slot-or-cap?
                          main-has-list-field?
                          main-has-block-field?)
        atoms    (if include-main?
                   (filter #(= :def-atom (:node %)) top-nodes)
                   other-atoms)
        skeleton (into {} (map (fn [a] [(:name a) {:fields [] :field-index {}
                                                   :ctor-params [] :methods []
                                                   :method-index {} :observables []
                                                   :invariants [] :transitions []
                                                   :extras []}])
                               atoms))
        atom-names (set (keys skeleton))]
    (reduce
     (fn [env a]
       (let [name          (:name a)
             dsec          (def-atom-data-section a)
             msec          (def-atom-methods-section a)
             transitions   (def-atom-transitions a)
             observables   (def-atom-observables a)
             invariants    (def-atom-invariants a)
             use-caps      (def-atom-use-caps a)
             nested-slots  (def-atom-nested-slots a)
             extras        (def-atom-body-extras a)
             ;; use: caps are sugar for a required ctor-param plus a
             ;; same-named data-field defaulted to that ctor-param.
             ;; Type must be an atom (capability atoms are the intended
             ;; kind); user-declared collisions raise below.
             _             (doseq [c use-caps]
                             (when-not (contains? atom-names (base-type-name (:type c)))
                               (fail-nyi!
                                (str "use: `" (:name c) "` requires an atom type; got `"
                                     (base-type-name (:type c)) "` (Layer 3.1 only "
                                     "supports capability atom types in use: for now)")
                                c)))
             ;; def <lowercase>: <AtomType>[(args)] synthesizes a data
             ;; field whose default is the ctor call. Type must be an
             ;; atom (nested slots hold sub-atoms, not values).
             _             (doseq [s nested-slots]
                             (when-not (contains? atom-names (base-type-name (:type s)))
                               (fail-nyi!
                                (str "nested slot `def " (:name s) "` requires an atom "
                                     "type; got `" (base-type-name (:type s)) "`")
                                s)))
             cap-idents     (mapv (fn [c] {:node :ident :name (:name c)}) use-caps)
             cap-as-params  (mapv (fn [c] {:name (:name c) :type (:type c) :default nil})
                                  use-caps)
             cap-as-fields  (mapv (fn [c id] {:name (:name c) :type (:type c) :default id
                                              :cxc-compiler-synthesized true})
                                  use-caps cap-idents)
             nested-as-fields (mapv
                               (fn [s]
                                 {:name (:name s)
                                  :type (:type s)
                                  :cxc-compiler-synthesized true
                                  :default
                                  {:node :call
                                   :callee {:node :ident :name (base-type-name (:type s))}
                                   :args   (or (:ctor-args s) [])}})
                               nested-slots)
             ;; F41 — data fields without a `:default` become **required
             ;; ctor params** per spec §10 ("constructor parameters
             ;; become data fields; construction auto-synthesised from
             ;; data fields + parameters"). Prior impl silently
             ;; zero-initialized them. Rewrite each no-default data
             ;; field so its default IS the synthesized ctor param ident
             ;; of the same name — reuses the existing ctor-param
             ;; passthrough machinery (same pattern as `use:` caps).
             ;;
             ;; Preserves declaration order: explicit `def X(a, b):`
             ;; ctor params come first, then F41-synthesized field
             ;; params in data-declaration order, then use:-capability
             ;; params. Named-args at call sites keep order irrelevant
             ;; for callers.
             ;;
             ;; Skipped for:
             ;; - `main` atom — has a dedicated construction path;
             ;;   env-cap wiring for main-with-ctor-params is Layer 5
             ;;   scope tracked separately.
             ;; - Container-typed fields (`List of T`, `Map of (K,V)`,
             ;;   `Set of T`) — they auto-initialize to empty via
             ;;   `default-init-stmt-for`. No literal syntax exists for
             ;;   passing containers as ctor args, and empty-container
             ;;   default is the ergonomic behavior anyway.
             container-outer? #{"List" "Map" "Set"}
             container-field?
             (fn [f] (and (= :type-of (get-in f [:type :node]))
                          (container-outer? (get-in f [:type :outer]))))
             f41-eligible? (fn [f] (and (nil? (:default f))
                                        (not (container-field? f))
                                        (not= "main" name)))
             f41-transform
             (reduce (fn [acc f]
                       (if (f41-eligible? f)
                         (-> acc
                             (update :fields conj
                                     (assoc f
                                            :default {:node :ident :name (:name f)}
                                            :cxc-f41-synthesized true))
                             (update :ctor-params conj
                                     {:name (:name f) :type (:type f)
                                      :default nil
                                      :cxc-f41-synthesized true}))
                         (update acc :fields conj f)))
                     {:fields [] :ctor-params []}
                     (or (:fields dsec) []))
             merged-ctor    (-> (vec (:ctor-params a))
                                (into (:ctor-params f41-transform))
                                (into cap-as-params))
             merged-fields  (-> (:fields f41-transform)
                                (into cap-as-fields)
                                (into nested-as-fields))
             _             (let [n->count (frequencies (map :name merged-ctor))
                                 dupes    (filter #(> (val %) 1) n->count)]
                             (when (seq dupes)
                               (fail-nyi!
                                (str "duplicate ctor-param names on def-atom `" name
                                     "` (likely collision between use: and explicit "
                                     "ctor-params): " (mapv key dupes))
                                {:atom name :dupes (mapv key dupes)})))
             _             (let [n->count (frequencies (map :name merged-fields))
                                 dupes    (filter #(> (val %) 1) n->count)]
                             (when (seq dupes)
                               (fail-nyi!
                                (str "duplicate field names on def-atom `" name
                                     "` (collision between data:/use:/nested-slot): "
                                     (mapv key dupes))
                                {:atom name :dupes (mapv key dupes)})))
             merged-dsec   (assoc (or dsec {:node :data-section}) :fields merged-fields)
             fields        (-> (fields-from-data-section merged-dsec {} env enum-env)
                               ;; F36 Landing 2a: invariant-driven picker
                               ;; narrows Int fields whose invariants
                               ;; range-bound them to fit a sized atom.
                               ;; Safe because F49 Landing A's pre-store
                               ;; check runs on the wide source before
                               ;; the narrowing store.
                               (narrow-fields-by-invariants invariants))
             _             (let [site (str "def-atom `" name "` ctor-params")]
                             (validate-defaults-after-required! site merged-ctor)
                             (validate-default-is-const!       site merged-ctor))
             ;; NOTE: atom `data:` field defaults are field
             ;; INITIALIZERS, not ctor-param defaults — the auto-ctor
             ;; produced by F41 uses ctor-params (which get const-
             ;; checked above), and any default on a field runs in the
             ;; field initializer, not at the ctor call site. Spec
             ;; §3.8.5 governs param defaults, so no const-check here.
             ctor-params   (ctor-params-normalized merged-ctor {} env enum-env)
             base-methods  (extract-methods name msec)
             ;; F14 — atom-body `-> name(args):` handler declarations
             ;; lower as method-shaped entries participating in the
             ;; same dispatch table. Detect duplicate handler names
             ;; and handler-vs-method name collisions; both are
             ;; ambiguous dispatch → hard error (spec §1265, §2160).
             handler-decls (def-atom-handler-decls a)
             _             (let [dups (->> handler-decls
                                           (map :name)
                                           frequencies
                                           (filter (fn [[_ n]] (> n 1)))
                                           (map first))]
                             (when (seq dups)
                               (fail-compile!
                                :cxc-f14-duplicate-handler-decl
                                (str "atom `" name "` declares handler `-> "
                                     (first dups) "(...):` more than once "
                                     "(spec §1265: each handler name unique per atom).")
                                (first handler-decls))))
             method-names  (set (map :name base-methods))
             _             (doseq [hd handler-decls]
                             (when (contains? method-names (:name hd))
                               (fail-compile!
                                :cxc-f14-handler-name-collides-with-method
                                (str "atom `" name "` declares handler `-> "
                                     (:name hd) "(...):` AND a `methods:` entry "
                                     "with the same name — handlers share the "
                                     "atom's method table (spec §2160), so this "
                                     "would create ambiguous dispatch. Rename one, "
                                     "or fold the handler body into the method.")
                                hd)))
             handler-methods (mapv #(handler-decl->method-entry name %) handler-decls)
             methods       (into base-methods handler-methods)
             message-index (collect-message-shapes name transitions)
             ;; Streams this atom EMITS (untargeted `:>` in any transition
             ;; body). Indexed by stream name; the ordered list preserves
             ;; declaration order so the emitted ivars + methods have a
             ;; stable footprint. Layer 4 Step 13.
             stream-index  (collect-stream-shapes name transitions)
             stream-order  (vec (distinct (map :msg (emit-nodes-in transitions))))
             ;; Streams this atom SUBSCRIBES to, indexed by the local
             ;; field name whose value is the source atom. Layer 4b uses
             ;; this to synthesize slot setters that (un)register on
             ;; field mutation. Format: {"action" ["cast_completed"], ...}
             subscription-map
             (reduce
              (fn [acc t]
                (let [src (get-in t [:receive :msg :source])]
                  (if (and (= "<:" (get-in t [:receive :glyph]))
                           (= :dot (:node src))
                           (= :ident (get-in src [:target :node])))
                    (update acc (get-in src [:target :name])
                            (fnil conj []) (:field src))
                    acc)))
              {}
              transitions)
             subscription-map (into {} (map (fn [[k v]] [k (vec (distinct v))])
                                            subscription-map))
             ;; Names of fields that are nested-slot children — Step 11
             ;; installs a parent back-ref on each after construction, and
             ;; -transitionToEnd cascades end into each on this atom's end.
             ;;
             ;; F31/F35 Landing 4 broadens the set to include ordinary
             ;; atom-typed `data:` fields (the spec-canonical form). A
             ;; field qualifies iff its declared type resolves to a
             ;; regular-kind atom (excludes primitives, records, enums,
             ;; and synthesized container atoms). `use:` cap fields and
             ;; F41-synthesized ctor-param fields carry an explicit
             ;; `:cxc-compiler-synthesized` marker and are excluded —
             ;; caps live in the shared env, not as owned children.
             ;; Nested-slot F34 fields are already in `nested-as-fields`
             ;; and re-collected here via the same predicate.
             non-slot-kinds #{:list-container :map-container :set-container
                              :runtime-provided}
             regular-atom-name?
             (fn [nm]
               (let [info (get env nm)]
                 (and info
                      (not (contains? non-slot-kinds (:kind info))))))
             data-slot-names
             (vec
              (keep (fn [f]
                      (let [ty-nm (base-type-name (:type f))]
                        (when (and (not (:cxc-compiler-synthesized f))
                                   (not (:cxc-f41-synthesized f))
                                   (regular-atom-name? ty-nm))
                          (:name f))))
                    (:fields merged-dsec)))
             slot-names    (vec (distinct
                                 (into (mapv :name nested-slots)
                                       data-slot-names)))]
         (assoc env name {:fields        fields
                          :field-index   (into {} (map (juxt :name identity) fields))
                          :ctor-params   ctor-params
                          :methods       methods
                          :method-index  (into {} (map (juxt :name identity) methods))
                          :observables   observables
                          :invariants    invariants
                          :transitions   transitions
                          :message-index message-index
                          :stream-index  stream-index
                          :stream-order  stream-order
                          :subscription-map subscription-map
                          :slot-names    slot-names
                          :extras        extras})))
     skeleton
     atoms)))

;; ---------------------------------------------------------------------------
;; Container-synth scaffolding (Hickey simplification pass)
;;
;; List / Map / Set container synthesis converged on the same
;; three-stage pipeline: scan atom-env for `_Cxc_<Kind>Of...` prefixed
;; field/ctor types, produce an atom-env entry per instantiation, and
;; emit an @objc-class per entry. The scan pattern and the atom-env
;; entry skeleton are the same across all three; only what identifies
;; an instantiation (element name for List/Set, [K V] tuple for Map)
;; and what methods live on the container class differ. Retiring the
;; skeleton duplication so a fourth container in the future is one
;; specialization site, not three.
;; ---------------------------------------------------------------------------

(defn- container-entry-defaults
  "The 15 always-empty keys every synthesized container atom-env entry
   carries. Container-specific synth code merges its distinguishing
   keys (`:kind`, `:element-type` / `:key-type`+`:val-type`, methods,
   observables, fan-in) on top."
  []
  {:fields               []
   :field-index          {}
   :ctor-params          []
   :methods              []
   :method-index         {}
   :observables          []
   :observables-typed    []
   :observable-index     {}
   :invariants           []
   :transitions          []
   :message-index        {}
   :stream-index         {}
   :stream-order         []
   :stream-payload-index {}
   :subscription-map     {}
   :slot-names           []
   :extras               []})

(defn- scan-atom-env-fields
  "Walk every atom-env entry's :fields + :ctor-params, threading each
   field/param through `f`. `f` receives (acc, field-or-param) and
   returns the updated acc. Common scaffolding for the container-
   instantiation-collection pattern (List / Map / Set)."
  [atom-env acc f]
  (reduce (fn [acc [_ info]]
            (as-> acc a
              (reduce f a (:fields info))
              (reduce f a (:ctor-params info))))
          acc
          atom-env))

(defn- list-instantiations-from-atom-env
  "Scan every atom-env entry's fields + ctor-params for `List of T`
   usages; return a map {element-type-name → list-class-name}. Feeds
   Step 3 class synthesis: one compiler-generated ObjC class per
   distinct element type."
  [atom-env]
  (scan-atom-env-fields
   atom-env {}
   (fn [acc fp]
     (let [ty (:cxc-ty fp)]
       ;; Field/ctor-param carries the already-mangled class name in
       ;; :cxc-ty (see base-type-name), so detect via the `_Cxc_ListOf`
       ;; prefix and recover the element.
       (if (and (string? ty) (.startsWith ^String ty "_Cxc_ListOf"))
         (assoc acc (subs ty (count "_Cxc_ListOf")) ty)
         acc)))))

(defn- map-instantiations-from-atom-env
  "Scan every atom-env entry's fields + ctor-params for `Map of (K, V)`
   usages; return a map {[K-name V-name] → map-class-name}. Feeds
   Layer 6 Step 8 class synthesis: one compiler-generated ObjC class
   per distinct (K, V) pair."
  [atom-env]
  (scan-atom-env-fields
   atom-env {}
   (fn [acc fp]
     (let [ty (:cxc-ty fp)]
       (if (and (string? ty) (.startsWith ^String ty "_Cxc_MapOf"))
         ;; K and V live in the mangled suffix. Rather than parse back
         ;; out of the string, we recorded :map-key / :map-val on the
         ;; field/param at normalization time (see map-key-val-of in
         ;; fields-from-data-section / ctor-params-normalized).
         (assoc acc [(:map-key fp) (:map-val fp)] ty)
         acc)))))

(def ^:private count-observable-defaults
  "Every container gains a `count` observable — factored so all three
   container-entry synthesizers share the same shape."
  {:observables-typed [{:name "count" :type-kw :int}]
   :observable-index  {"count" {:name "count" :type-kw :int}}})

(defn- container-method
  "Build a method-index entry for a synthesized container method.
   `return-ty` is a cxc type-name string (or nil for void); params
   receive self implicitly prepended."
  [method-name params return-ty]
  {:name               method-name
   :params             (into [{:name "self"}] params)
   :cxc-return-ty      return-ty
   :cxc-return-ty-node (when return-ty {:node :type-ref :name return-ty})})

(defn- synthesize-map-container-entries
  "Given {[K V] → map-class-name} produce atom-env entries for each
   map-class. Layer 6 Step 8 MVP: no fan-in over map values (deferred);
   entries carry :kind :map-container marker, key/value type names, and
   a `count` observable. Method-index registers add/remove/contains so
   infer-method-call-type resolves them."
  [instantiations]
  (into {}
        (map (fn [[[k v] class-name]]
               (let [methods [(container-method "add"
                                                [{:name "k" :cxc-ty k}
                                                 {:name "v" :cxc-ty v}]
                                                nil)
                              (container-method "remove"
                                                [{:name "k" :cxc-ty k}]
                                                nil)
                              (container-method "contains"
                                                [{:name "k" :cxc-ty k}]
                                                "Bool")]]
                 [class-name
                  (merge (container-entry-defaults)
                         count-observable-defaults
                         {:kind         :map-container
                          :key-type     k
                          :val-type     v
                          :methods      methods
                          :method-index (into {} (map (juxt :name identity) methods))})]))
             instantiations)))

(defn- set-instantiations-from-atom-env
  "Scan atom-env fields + ctor-params for `Set of T` usages; return
   {element-name → set-class-name}."
  [atom-env]
  (scan-atom-env-fields
   atom-env {}
   (fn [acc fp]
     (let [ty (:cxc-ty fp)]
       (if (and (string? ty) (.startsWith ^String ty "_Cxc_SetOf"))
         (assoc acc (subs ty (count "_Cxc_SetOf")) ty)
         acc)))))

(defn- synthesize-set-container-entries
  "Given {element-name → set-class-name}, produce atom-env entries.
   MVP shape: no fan-in (parallel to Map). Methods add/remove/contains
   registered in :method-index; count observable in :observable-index."
  [instantiations]
  (into {}
        (map (fn [[elem class-name]]
               (let [methods [(container-method "add"
                                                [{:name "e" :cxc-ty elem}] nil)
                              (container-method "remove"
                                                [{:name "e" :cxc-ty elem}] nil)
                              (container-method "contains"
                                                [{:name "e" :cxc-ty elem}] "Bool")]]
                 [class-name
                  (merge (container-entry-defaults)
                         count-observable-defaults
                         {:kind         :set-container
                          :element-type elem
                          :methods      methods
                          :method-index (into {} (map (juxt :name identity) methods))})]))
             instantiations)))

(defn- synthesize-list-container-entries
  "Given {element-name → list-class-name} and the current atom-env
   (which knows element atoms' :stream-index / :stream-order /
   :stream-payload-index after inference), produce atom-env entries
   for each list-class. The synthetic entry carries:
     - `:kind :list-container` marker (lower-atom skips these)
     - `:element-type` name (used by class synth + for_each unbox)
     - `count` observable in observable-index
     - fan-in :stream-order / :stream-index / :stream-payload-index
       cloned from element atom, each payload prepended with
       `e: <ElementAtom>` so the container's emit signature carries
       the element identity"
  [instantiations atom-env]
  (into {}
        (map (fn [[elem class-name]]
               (let [elem-info      (get atom-env elem)
                     elem-streams   (:stream-order elem-info [])
                     elem-index     (:stream-index elem-info {})
                     elem-payloads  (:stream-payload-index elem-info {})
                     ;; Fan-in payload: prepend e: <ElementAtom> to the
                     ;; element's own emit-site keys. Zero-payload
                     ;; element streams become one-arg fan-in streams
                     ;; carrying just the element identity.
                     fanin-payloads
                     (into {}
                           (map (fn [nm]
                                  [nm (into [{:name   "e"
                                              :type-kw {:atom elem}
                                              :c-type (str elem " *")}]
                                            (get elem-payloads nm []))])
                                elem-streams))
                     ;; Container-side stream-index also mirrors the
                     ;; fan-in shape — element's index has {:payload
                     ;; [{:name "v"}]}; container's re-emit prepends
                     ;; {:name "e"} because the fan-in forwarding
                     ;; method attaches element identity. Receive-side
                     ;; payload validation (validate-stream-subscribe)
                     ;; keys off :stream-index, so this needs the "e"
                     ;; prefix or `xs.beep{e, v}` subscribers fail
                     ;; shape check.
                     fanin-index
                     (into {}
                           (map (fn [[nm entry]]
                                  [nm (assoc entry :payload
                                             (into [{:name "e"}]
                                                   (:payload entry [])))])
                                elem-index))
                     methods       [(container-method "add"
                                                      [{:name "e" :cxc-ty elem}] nil)
                                    (container-method "remove"
                                                      [{:name "e" :cxc-ty elem}] nil)]]
                 [class-name
                  (merge (container-entry-defaults)
                         count-observable-defaults
                         {:kind                 :list-container
                          :element-type         elem
                          :methods              methods
                          :method-index         (into {} (map (juxt :name identity) methods))
                          :stream-index         fanin-index
                          :stream-order         elem-streams
                          :stream-payload-index fanin-payloads})]))
             instantiations)))

(defn- build-enum-env
  "Top-level `enum Foo: a, b, c` decls. Env is keyed by enum name;
   value has :cases (vector of case names in decl order) and
   :case-index (name → declaration-order index; used for the C
   integer value in the emitted `Foo_<case>` constants)."
  [top-nodes]
  (into {}
        (for [e (filter #(= :enum (:node %)) top-nodes)]
          [(:name e) {:cases      (:cases e)
                      :case-index (into {} (map-indexed (fn [i c] [c i])
                                                        (:cases e)))}])))

;; ---------------------------------------------------------------------------
;; Type inference (ctx-threaded)
;; ---------------------------------------------------------------------------

(declare infer-type)

(defn- infer-binop-type
  [{:keys [op left right]} ctx]
  (let [lt (infer-type left  ctx)
        rt (infer-type right ctx)]
    (cond
      ;; Comparison ops always return bool regardless of operand types
      ;; (parser accepts nonsense combos; we let clang catch those).
      (#{"==" "!=" "<" "<=" ">" ">="} op) :bool

      ;; Boolean connectives: both sides must be bool (or int since we
      ;; don't have a distinct bool type at the C level yet).
      (#{"and" "or"} op) :bool

      ;; `is` is identity/equality: pointer-equal for atoms, value-equal
      ;; for enums/ints/nil. Returns bool.
      (= "is" op) :bool

      ;; Audit F47: `in` is a membership predicate (spec §1.3). Full
      ;; lowering — dispatching to a container's contains method — is
      ;; deferred; but the type IS bool regardless of operand kinds,
      ;; and inferring it as :bool here prevents accidental arithmetic
      ;; classification below.
      (= "in" op) :bool

      ;; Phase D F36a Landing 1b — arithmetic widening rules:
      ;;
      ;; 1. int + float (either side) → float family wins (int→float
      ;;    is always widening; standard C/PL convention). Result's
      ;;    float width is the float operand's width.
      ;; 2. Same int family (both signed or both unsigned) → pick the
      ;;    wider operand's type-kw. Same for same-width case (identity).
      ;; 3. Cross-signedness (signed int + unsigned int) → reject.
      ;;    Semantics vary by width; explicit conversion required
      ;;    (spec §3.11 narrowing/widening methods).
      ;; 4. Two floats → pick wider.
      ;;
      ;; Bare `:int` and `:float` are the defaults (Int64 and Double,
      ;; treated as widest-in-family for widening arithmetic).
      (and (float-family? lt) (float-family? rt))
      (if (>= (or (and (vector? lt) (second lt)) 64)
              (or (and (vector? rt) (second rt)) 64))
        lt rt)

      (and (float-family? lt) (int-family? rt))  lt
      (and (int-family? lt)   (float-family? rt)) rt

      (and (int-family? lt) (int-family? rt))
      (let [l-fam (or (and (vector? lt) (first lt)) :int)
            r-fam (or (and (vector? rt) (first rt)) :int)]
        (cond
          (not= l-fam r-fam)
          (fail-nyi! (str "binop " op " mixes signed " lt " with unsigned " rt
                          " — spec §3.11 requires an explicit conversion")
                     {:left lt :right rt})
          :else
          (let [lw (int-bit-width lt)
                rw (int-bit-width rt)]
            (if (>= lw rw) lt rt))))

      :else (fail-nyi! (str "binop " op " between " lt " and " rt)
                       {:left lt :right rt}))))

(defn- infer-method-call-type
  "For `target.method(args)`: infer target-ty, look up method on the
   record OR atom (falling back to observable-index for atoms), return
   its cxc-return-ty translated to a type-kw. Observables carry
   :type-kw directly."
  [call-node ctx]
  (let [dot         (:callee call-node)
        target-ty   (infer-type (:target dot) ctx)
        method-name (:field dot)]
    (cond
      (and (map? target-ty) (:record target-ty))
      (let [rn (:record target-ty)
            m  (get-in (:record-env ctx) [rn :method-index method-name])]
        (when-not m
          (fail-nyi! (str "method `" method-name "` on record `" rn "`") call-node))
        (cxc-type->type-kw (:cxc-return-ty m)
                           (:record-env ctx) (:atom-env ctx) (:enum-env ctx) call-node))

      (and (map? target-ty) (:atom target-ty))
      (let [an (:atom target-ty)
            m  (get-in (:atom-env ctx) [an :method-index method-name])
            o  (get-in (:atom-env ctx) [an :observable-index method-name])]
        (cond
          m (cxc-type->type-kw (:cxc-return-ty m)
                               (:record-env ctx) (:atom-env ctx) (:enum-env ctx) call-node)
          o (:type-kw o)
          :else (fail-nyi! (str "method/observable `" method-name "` on atom `" an "`")
                           call-node)))

      :else
      (fail-nyi! "method call on non-record/non-atom target" call-node))))

(def ^:private ffi-builtins
  "Compiler-recognized function names that lower to raw C/ObjC. Intended
   for prelude-authored bodies (real Clock/Random capabilities); users
   may name-collide only by writing these names themselves, which
   surfaces as an unknown-call from lower today. Each entry declares
   the return type (as a type-kw for infer-type) and the arg count.
   `:emit-c` is a function taking the vector of already-lowered arg
   nodes and returning the raw C expression string that will be
   spliced into the emit output via a `:raw-c-expr` node.

   Retirement pending — this registry is a compiler-invented mechanism
   (audit F/K7) parallel to the spec's `foreign library` blocks (§1.14)
   consumed via the `Extern` capability atom (§1.12). Real machinery
   requires prelude directory tree + name resolution + capability
   provisioning (Phase D). Until that lands, prelude bodies (Clock,
   Random, Console) use this hatch."
  {"native_millis_now"
   {:return-kw :int
    :arity     0
    :emit-c    (fn [_args]
                 "((int)([[NSDate date] timeIntervalSince1970] * 1000))")}
   "native_random_u32"
   {:return-kw :int
    :arity     0
    :emit-c    (fn [_args]
                 "((int)(arc4random() & 0x7FFFFFFF))")}
   "native_console_write"
   ;; Layer 6 Step 5: Console.write(msg) writes to stdout, returns
   ;; the number of bytes written. Wraps printf's %s to route
   ;; through the same underlying fd as `print` — subsequent output
   ;; from either path interleaves correctly.
   ;;
   ;; F65: msg is NSString *; extract UTF8 bytes at the print site.
   {:return-kw :int
    :arity     1
    :emit-c    (fn [args]
                 (str "printf(\"%s\", [" (first args) " UTF8String])"))}
   "native_random_below"
   {:return-kw :int
    :arity     1
    :emit-c    (fn [args]
                 (str "((int)arc4random_uniform((uint32_t)("
                      (first args) ")))"))}
   "native_clock_every"
   ;; Layer 5c Step 8/10 — real method-returned Stream. Called from
   ;; Clock's `every(rate_ms: Int) -> Stream` method body; creates a
   ;; CxcStream carrying subscribers plus a dispatch_source_t periodic
   ;; timer whose event handler builds a fresh `_Cxc_Instant` and
   ;; fans it to every subscriber via `objc_msgSend` cast. macOS
   ;; libdispatch quirk: `dispatch_source_t` timers targeting the main
   ;; queue don't fire under `dispatch_main()`, so the timer lives on
   ;; a dedicated serial queue.
   ;;
   ;; Payload shape (`t: Instant`) matches the registration in
   ;; `method-stream-payload-shapes` for `[_Cxc_Clock every]`. Selector
   ;; on the subscriber side is
   ;; `cxc_stream_every_from__Cxc_ClockWithsender:t:` — receiver-side
   ;; method synthesis in `lower-receive-glyph-transitions` builds the
   ;; matching signature. The fanout bypasses CxcStream's generic
   ;; `-cxc_emit` because that method assumes a fixed (id, id)
   ;; signature; typed payload needs a per-payload-shape cast, which
   ;; we specialize inline here.
   ;;
   ;; Lifecycle: the CxcStream owns the timer via its teardown block.
   ;; The subscriber owns the CxcStream (see :raw-stream-subscribe in
   ;; emit — cxc_own_resource(self, stream) at subscription site).
   ;; When the subscriber -> end, its resources drop the stream →
   ;; ARC dealloc → -cxc_end fires teardown → timer cancelled → the
   ;; CxcTimerHandle deallocs → queue released. Clock (the source)
   ;; is just a factory here; it holds no reference to the stream
   ;; and its own lifecycle is independent. Retires the earlier
   ;; CFBridgingRetain leaks and unblocks per-subscriber lifetime
   ;; (a Clock with a short internal beat count doesn't kill a
   ;; subscriber's every-stream anymore).
   ;;
   ;; The event handler holds a strong ref to the CxcStream — that
   ;; ref lives inside the timer's captured block. The
   ;; CxcTimerHandle in the teardown-captured `__cxc_h` closure keeps
   ;; the timer + queue alive as long as the stream lives. Cycle:
   ;; stream → teardown block → handle → (no back-ref to stream).
   ;; No retain cycle; when subscriber drops stream, stream deallocs
   ;; → teardown runs → handle released → block destroyed → strong
   ;; stream ref inside block also released (already dealloc'd, so
   ;; no-op).
   {:return-kw {:atom "CxcStream"}
    :arity     2
    :emit-c    (fn [args]
                 (let [src  (first args)
                       rate (second args)]
                   (str "({ "
                        "id __cxc_src = " src "; "
                        "int64_t __cxc_ms = (int64_t)(" rate "); "
                        "dispatch_queue_t __cxc_q = "
                        "dispatch_queue_create(\"cxc.every\", DISPATCH_QUEUE_SERIAL); "
                        "dispatch_source_t __cxc_t = "
                        "dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, "
                        "__cxc_q); "
                        "dispatch_source_set_timer(__cxc_t, "
                        "dispatch_time(DISPATCH_TIME_NOW, __cxc_ms * NSEC_PER_MSEC), "
                        "__cxc_ms * NSEC_PER_MSEC, 0); "
                        "CxcStream *__cxc_s = "
                        "[[CxcStream alloc] initWithSelector:"
                        "@\"cxc_stream_every_from__Cxc_ClockWithsender:value:\" "
                        "source:__cxc_src]; "
                        ;; Weak ref inside the event handler so the
                        ;; timer's block doesn't keep the stream
                        ;; alive after subscribers drop it. Without
                        ;; the weak, stream → handle → block → strong
                        ;; stream is a real cycle.
                        "__weak CxcStream *__cxc_ws = __cxc_s; "
                        "dispatch_source_set_event_handler(__cxc_t, ^{ "
                        "CxcStream *__cxc_ss = __cxc_ws; "
                        "if (__cxc_ss == nil) return; "
                        ;; Build a fresh Instant per fire — matches
                        ;; the receive-side `{t: Instant}` type. Layer
                        ;; 5 keeps Instant.millis as int (see prelude
                        ;; Instant record); a wider integral type
                        ;; requires plumbing all the way through.
                        "_Cxc_Instant __cxc_inst = { .millis = "
                        "(int)([[NSDate date] timeIntervalSince1970] * 1000) }; "
                        "SEL __cxc_sel = sel_registerName("
                        "\"cxc_stream_every_from__Cxc_ClockWithsender:value:\"); "
                        "for (id sub in __cxc_ss.subscribers) { "
                        ;; Hop to subscriber via cxc_send_async so
                        ;; the msg lands on the subscriber's own_queue
                        ;; — same delivery model as generic cxc_emit.
                        "cxc_send_async(sub, ^{ "
                        "((void (*)(id, SEL, id, _Cxc_Instant))objc_msgSend)"
                        "(sub, __cxc_sel, __cxc_src, __cxc_inst); "
                        "}); "
                        "} "
                        "}); "
                        "dispatch_resume(__cxc_t); "
                        "CxcTimerHandle *__cxc_h = [[CxcTimerHandle alloc] init]; "
                        "__cxc_h.source = __cxc_t; "
                        "__cxc_h.queue = __cxc_q; "
                        "__cxc_s.teardown = ^{ (void)__cxc_h; }; "
                        "__cxc_s; })")))}})

(defn- ffi-builtin? [name] (contains? ffi-builtins name))

;; Layer 6 Step 1: retired `method-stream-payload-shapes` registry.
;; Payload type for `<: source.method(...){k: T}` receives now derives
;; from the method's declared `-> Stream of T` return type node — read
;; inline in `lower-receive-glyph-transitions`' `:method-stream` branch
;; via `:cxc-return-ty-node`.

(defn- infer-call-type
  [{:keys [callee] :as node} ctx]
  (case (:node callee)
    :ident
    (let [nm (:name callee)]
      (cond
        (contains? (:record-env ctx) nm) {:record nm}
        (contains? (:atom-env   ctx) nm) {:atom nm}
        (= "print" nm)                   :void
        ;; F31/F35 Landing 2 — panic halts the process. Type-wise it
        ;; never returns; the closest cxc type is :void (return-position
        ;; use is illegal but not currently type-checked).
        (= "panic" nm)                   :void
        (= "clamp" nm)                   ;; polymorphic on first arg
        (let [args (:args node)
              _    (when-not (= 3 (count args))
                     (fail-nyi! (str "clamp expects 3 args (x, lo, hi), got "
                                     (count args))
                                node))
              x-ty (infer-type (:value (first args)) ctx)]
          (when-not (numeric-family? x-ty)
            (fail-nyi! (str "clamp arg must be numeric; got " (pr-str x-ty))
                       node))
          x-ty)
        (ffi-builtin? nm)                (:return-kw (get ffi-builtins nm))
        ;; Layer 6 Step 12 — bare call on a Block-typed self-field.
        ;; `cb()` where cb is `data: cb: Block` in the enclosing atom
        ;; invokes the stored block. MVP is zero-arg void-return only,
        ;; so return :void.
        (and (get-in ctx [:self-fields nm])
             (= "Block" (:cxc-ty (get-in ctx [:self-fields nm]))))
        :void
        :else (fail-nyi! (str "call to `" nm "`") node)))
    :dot   (infer-method-call-type node ctx)
    (fail-nyi! (str "call with non-ident/non-dot callee " (:node callee)) node)))

(defn- field-type-for-owner [owner-ty ctx field-name node]
  (cond
    ;; F37b L1 — `Bytes` inspection surface. `.length` returns Int
    ;; (grapheme/element-count convention, matches List/String;
    ;; §3.10.10 uses UInt32 for the wire-length prefix but user-
    ;; facing `.length` is a count). NSMutableData exposes a
    ;; readonly `length` property → ObjC property syntax on the
    ;; emit side lowers as `[bytes length]` unchanged; only the
    ;; type-inference plumbing needs to learn the shape.
    (= :bytes owner-ty)
    (case field-name
      "length" :int
      (fail-nyi! (str "field `" field-name "` on Bytes — only `.length` "
                      "ships in F37b L1 (spec §3.10.10). Slice/at/builder "
                      "methods follow when a use case forces them.")
                 node))

    (and (map? owner-ty) (:record owner-ty))
    (let [owner (:record owner-ty)
          f     (get-in (:record-env ctx) [owner :field-index field-name])]
      (when-not f
        (fail-nyi! (str "field `" field-name "` on record `" owner "`") node))
      (cxc-type->type-kw (:cxc-ty f)
                         (:record-env ctx) (:atom-env ctx) (:enum-env ctx) node))

    (and (map? owner-ty) (:atom owner-ty))
    (let [owner     (:atom owner-ty)
          f         (get-in (:atom-env ctx) [owner :field-index field-name])
          o         (get-in (:atom-env ctx) [owner :observable-index field-name])
          ;; Fallback: infer-observables-in-atom-env may not yet have
          ;; typed this atom's observables when we're mid-pass on a
          ;; peer atom that references it. Use the raw `:observables`
          ;; list to detect the observable's existence; type inference
          ;; below re-runs on the raw expression.
          raw-obs   (some #(when (= field-name (:name %)) %)
                          (get-in (:atom-env ctx) [owner :observables]))
          self-atom (:atom (get-in ctx [:locals "self"]))]
      (cond
        ;; F33 (Proposal C+C1, rewrite 2026-08-17): external `.field`
        ;; reads on any atom are spec-legal. Reading is observation, not
        ;; mutation; external mutation is impossible by grammar (no
        ;; `:assign` from parse). Atomic transitions (F33 L2-L3
        ;; snapshot/commit) + F32 L4 (atomic-pointer-swap on cross-atom
        ;; reads) provide the correctness story. The previous L1
        ;; diagnostic that rejected cross-atom reads was retracted.
        ;; See plans/f33-encapsulation.md L1.
        f (cxc-type->type-kw (:cxc-ty f)
                             (:record-env ctx) (:atom-env ctx) (:enum-env ctx) node)
        ;; Observables read like fields — dot syntax on an ObjC object
        ;; dispatches to our synthesized zero-arg method.
        o (:type-kw o)
        ;; Peer-atom observable not yet typed in this pass — infer its
        ;; expression against the owner's own self-fields.
        raw-obs (let [owner-fields (into {} (map (juxt :name identity)
                                                 (get-in (:atom-env ctx)
                                                         [owner :fields])))
                      obs-ctx (assoc ctx :self-fields owner-fields
                                     :locals (assoc (or (:locals ctx) {})
                                                    "self" {:atom owner}))]
                  (infer-type (:expr raw-obs) obs-ctx))
        :else
        (fail-nyi! (str "field/observable `" field-name "` on atom `" owner "`") node)))

    :else
    (fail-nyi! "field access on non-aggregate type" node)))

(defn- enum-case-access?
  "A `:dot` whose target is an `:ident` naming an enum, and whose field
   is one of that enum's cases. Not to be confused with instance field
   access on an aggregate."
  [dot-node ctx]
  (let [tgt (:target dot-node)]
    (and (= :ident (:node tgt))
         (contains? (:enum-env ctx) (:name tgt))
         (contains? (get-in ctx [:enum-env (:name tgt) :case-index])
                    (:field dot-node)))))

(defn- infer-type [node ctx]
  (case (:node node)
    :lit-string :string
    :lit-int    :int
    :lit-float  :float
    :lit-nil    :nil
    :lambda     (if (empty? (:params node))
                  ;; Layer 6 Step 12 — zero-arg lambda's type is :block.
                  ;; MVP restricts to void-return, so no signature to
                  ;; encode beyond the marker. lower-expr :lambda
                  ;; handles the actual block-literal emit.
                  :block
                  (fail-nyi! (str "lambda expression with " (count (:params node))
                                  " params — needs a consumer to invoke "
                                  "through (Layer 5 collection methods "
                                  "`.map`/`.for_each`; will lower to ObjC "
                                  "blocks)")
                             node))
    :self       (or (get (:locals ctx) "self")
                    (fail-nyi! "type-of `self` outside a method body" node))
    :ident      (or (get (:locals ctx) (:name node))
                    (when-let [f (get (:self-fields ctx) (:name node))]
                      (or (:type-kw f)
                          (cxc-type->type-kw (:cxc-ty f)
                                             (:record-env ctx) (:atom-env ctx)
                                             (:enum-env ctx) node)))
                    (fail-nyi! (str "type-of identifier `" (:name node)
                                    "` (no local-binding env yet)")
                               node))
    :binop      (infer-binop-type node ctx)
    :chained-cmp :bool
    :neg        (let [t (infer-type (:expr node) ctx)]
                  (if (numeric-family? t)
                    t
                    (fail-nyi! (str "unary minus on non-numeric type "
                                    (pr-str t))
                               node)))
    :not        :bool
    :call       (infer-call-type node ctx)
    :dot        (if (enum-case-access? node ctx)
                  {:enum (get-in node [:target :name])}
                  (field-type-for-owner (infer-type (:target node) ctx)
                                        ctx (:field node) node))
    ;; F19 L2 — subscript index access. String[i] returns Int (Unicode
    ;; codepoint from `-characterAtIndex:`, cast to int64_t at emit).
    ;; List of T / Map / Set indexing tangles with F42/F43 container
    ;; infrastructure (element unboxing, key-type validation, spec-
    ;; undefined bounds behavior) — defer to a follow-on that coordinates
    ;; with those landings. Bounds behavior for String[i] matches
    ;; NSString: NSRangeException on OOB, caught by F48's `@try/@catch`
    ;; transition wrapper.
    :subscript  (let [target-ty (infer-type (:target node) ctx)]
                  (cond
                    (= target-ty :string) :int
                    :else
                    (fail-nyi! (str "subscript `expr[i]` on target of type "
                                    (pr-str target-ty)
                                    " — F19 L2 handles `String[i]` only. "
                                    "List / Map indexing waits on F42 "
                                    "(from_literal) + F43 (container-as-"
                                    "atom); use `.get`/`.contains` for now.")
                               node)))
    (fail-nyi! (str "infer-type " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Expression lowering (ctx-threaded)
;; ---------------------------------------------------------------------------

(declare lower-expr lower-stmt record-synth-equals-name
         resolve-args-against-params)

(defn- lower-record-ctor
  [record-name args ctx]
  (let [rec      (get-in ctx [:record-env record-name])
        params   (mapv (fn [f] {:name (:name f) :default (:default f)})
                       (:fields rec))
        resolved (resolve-args-against-params
                  (str "record `" record-name "` constructor")
                  params args)
        fields   (mapv (fn [{:keys [name value]}]
                         {:name name :value (lower-expr value ctx)})
                       resolved)]
    {:node :struct-lit :type record-name :fields fields}))

(defn- resolve-args-against-params
  "Match named/positional call args against declared params in decl
   order. Fall back to each param's declared default; fail-nyi if
   neither is available. Returns a vector of {:name :value} pairs in
   declaration order.

   F40 Landing 1 — call-site validation (spec §3.8.5):
   - duplicate named args              → fail
   - unknown named args                → fail
   - surplus positional args           → fail
   - same param supplied twice
     (once positionally, once named)   → fail
   - missing arg without default       → fail-nyi (pre-existing)"
  [call-site-label params args]
  (let [param-names   (mapv :name params)
        param-set     (set param-names)
        named-args    (filterv #(= :named (:kind %)) args)
        positional    (filterv #(= :positional (:kind %)) args)
        ;; Duplicate named args
        _ (let [dupes (->> (map :name named-args)
                           frequencies
                           (keep (fn [[n c]] (when (> c 1) n)))
                           sort)]
            (when (seq dupes)
              (fail-nyi! (str "duplicate named arg" (when (> (count dupes) 1) "s")
                              " on " call-site-label ": "
                              (str/join ", " (map #(str "`" % "`") dupes)))
                         {:site call-site-label :duplicates dupes})))
        ;; Unknown named args
        _ (let [unknown (->> named-args
                             (map :name)
                             (remove param-set)
                             distinct
                             sort)]
            (when (seq unknown)
              (fail-nyi! (str "unknown named arg" (when (> (count unknown) 1) "s")
                              " on " call-site-label ": "
                              (str/join ", " (map #(str "`" % "`") unknown))
                              " (declared params: "
                              (str/join ", " (map #(str "`" % "`") param-names))
                              ")")
                         {:site call-site-label :unknown unknown
                          :declared param-names})))
        ;; Surplus positional args
        _ (when (> (count positional) (count params))
            (fail-nyi! (str "too many positional args on " call-site-label
                            ": got " (count positional) ", expected at most "
                            (count params))
                       {:site call-site-label
                        :positional-count (count positional)
                        :param-count (count params)}))
        ;; Same param supplied positionally AND by name
        _ (let [by-name-set (set (map :name named-args))
                collisions  (->> (map-indexed (fn [i _] (get param-names i))
                                              positional)
                                 (filter by-name-set)
                                 distinct
                                 sort)]
            (when (seq collisions)
              (fail-nyi! (str "arg for param" (when (> (count collisions) 1) "s")
                              " " (str/join ", " (map #(str "`" % "`") collisions))
                              " supplied both positionally and by name on "
                              call-site-label)
                         {:site call-site-label :collisions collisions})))
        by-name (into {} (map (juxt :name :value) named-args))]
    (mapv (fn [i p]
            (let [v (or (get by-name (:name p))
                        (:value (get positional i))
                        (:default p))]
              (when-not v
                (fail-nyi! (str "no value provided for param `" (:name p)
                                "` on " call-site-label
                                " (no arg and no default)")
                           {:param (:name p) :site call-site-label}))
              {:name (:name p) :value v}))
          (range)
          params)))


(defn- lower-atom-ctor
  "`X(a: 1, b: 2)` → ObjC init call. With no ctor params, emits plain
   `[[X alloc] init]`. With ctor params, emits an :objc-init-with node
   carrying the resolved (name, value) pairs — one selector part per
   param in declaration order."
  [atom-name args ctx]
  (let [rec         (get-in ctx [:atom-env atom-name])
        ctor-params (:ctor-params rec)]
    (if (empty? ctor-params)
      (do
        (when (seq args)
          (fail-nyi! (str "atom constructor args on `" atom-name
                          "` — atom declares no ctor params")
                     {:atom atom-name :args args}))
        {:node :objc-alloc-init :class atom-name})
      (let [resolved (resolve-args-against-params
                      (str "`" atom-name "` constructor")
                      ctor-params args)]
        {:node :objc-init-with
         :class atom-name
         :args  (mapv (fn [{:keys [name value]}]
                        {:name name :value (lower-expr value ctx)})
                      resolved)}))))

(defn- c-method-name
  "Free-function name for a record's method: `<Record>_<method>`. Simple
   name-mangling; collisions with user-declared functions of that shape
   would be a legitimate compile error, not a silent shadowing."
  [record-name method-name]
  (str record-name "_" method-name))

(defn- objc-method-selector
  "Build the ObjC selector for a method call. Zero-arg → bare name;
   with args → `<name>With<first>:<rest>:...` matching the initWith
   convention on ctors. Not to be confused with cxc's `.name` — cxc
   source uses dot syntax; only the emit layer sees selectors."
  [method-name arg-names]
  (if (empty? arg-names)
    method-name
    (str method-name "With" (first arg-names) ":"
         (str/join "" (map #(str % ":") (rest arg-names))))))

(declare lower-for-each-call lambda-body-stmts unbox-prelude-for-element)

(defn- lower-method-call
  "`target.method(args)` dispatches on target type:
   - record target → C free function call `Record_method(target, args...)`
     with self as first positional arg
   - atom target → ObjC message send `[target methodWithArg:...]` with
     arg-name-derived selector parts, falling back to observable-index
     for zero-arg synthesized getters
   - list-container target + method `for_each` → special-cased block
     literal path (Layer 5 Step 5); the lambda arg gets bound + body
     lowered inside a fresh ctx with the element name in locals

   F40 Landing 2 — args are resolved against the method's declared
   params via `resolve-args-against-params`: named and positional
   both accepted, defaults inserted where callers omit an arg, and
   dup/unknown/surplus/collision all fail at the call site."
  [call-node ctx]
  (let [dot         (:callee call-node)
        target-ty   (infer-type (:target dot) ctx)
        method-name (:field dot)
        args        (:args call-node)
        target-lo   (lower-expr (:target dot) ctx)
        list-cont?  (and (map? target-ty) (:atom target-ty)
                         (= :list-container
                            (get-in ctx [:atom-env (:atom target-ty) :kind])))
        for-each-with-lambda?
        (and list-cont?
             (= "for_each" method-name)
             (= 1 (count args))
             (= :lambda (get-in args [0 :value :node])))
        method-info
        (cond
          (and (map? target-ty) (:record target-ty))
          (get-in ctx [:record-env (:record target-ty) :method-index method-name])
          (and (map? target-ty) (:atom target-ty))
          (get-in ctx [:atom-env (:atom target-ty) :method-index method-name]))
        ;; F40 Landing 2: user-facing params (skip self, which is
        ;; either flagged `:is-self? true` on user-authored methods or
        ;; carries name "self" on prelude-synthesized container
        ;; methods).
        user-params
        (when method-info
          (remove #(or (:is-self? %) (= "self" (:name %)))
                  (:params method-info)))
        ;; When we can resolve, do so: named+positional get merged,
        ;; defaults inserted, decl-order preserved. When we can't
        ;; (prelude container synth, observables, for_each-with-lambda),
        ;; fall back to positional args as-supplied.
        ordered-args
        (cond
          for-each-with-lambda? args
          method-info
          (mapv (fn [{:keys [name value]}] {:kind :positional :name name :value value})
                (resolve-args-against-params
                 (str "method call `" method-name "` on "
                      (pr-str target-ty))
                 (mapv (fn [p] {:name (:name p) :default (:default p)})
                       user-params)
                 args))
          :else args)]
    (if for-each-with-lambda?
      (lower-for-each-call target-lo target-ty (get-in args [0 :value]) ctx)
      (let [arg-exprs (mapv #(lower-expr (:value %) ctx) ordered-args)
            ;; Audit F44 — List container's `add`/`remove` take the
            ;; element as `id`, so primitive callers need boxing at
            ;; the call site. Element type comes from the container's
            ;; `:element-type` field (a cxc type name string). Atoms
            ;; and records pass through as-is.
            list-add-or-remove?
            (and list-cont?
                 (contains? #{"add" "remove"} method-name)
                 (= 1 (count arg-exprs)))
            arg-exprs
            (if list-add-or-remove?
              (let [elem-ty (infer-type (:value (first ordered-args)) ctx)]
                (case elem-ty
                  (:int :float)
                  [{:node :objc-num-lit :kind :box :expr (first arg-exprs)}]
                  arg-exprs))
              arg-exprs)]
        (cond
          (and (map? target-ty) (:record target-ty))
          {:node :call
           :fn   (c-method-name (:record target-ty) method-name)
           :args (into [target-lo] arg-exprs)}

          (and (map? target-ty) (:atom target-ty))
          (let [atom-name   (:atom target-ty)
                m           (get-in ctx [:atom-env atom-name :method-index method-name])
                o           (get-in ctx [:atom-env atom-name :observable-index method-name])
                _           (when (and (nil? m) (nil? o))
                              (fail-nyi! (str "method/observable `" method-name "` on atom `"
                                              atom-name "`") call-node))
                ;; F67 L2: parametric observables carry `:params`; a call
                ;; with args must supply exactly those params.
                _           (when (and o (seq arg-exprs)
                                       (not= (count arg-exprs) (count (:params o))))
                              (fail-nyi! (str "observable `" method-name "` called with "
                                              (count arg-exprs) " args but declares "
                                              (count (:params o)) " params")
                                         call-node))
                param-names (cond
                              m (mapv :name (rest (:params m)))
                              (and o (seq (:params o))) (mapv :name (:params o))
                              :else [])]
            {:node     :objc-msg
             :recv     target-lo
             :selector (objc-method-selector method-name param-names)
             :args     arg-exprs})

          :else
          (fail-nyi! "method call on non-record/non-atom target" call-node))))))

(defn- lambda-body-stmts
  "Extract the sequence of body statements from a lambda AST node.
   `body` may be a single statement (from inline `for e in xs:
   <stmt>`) or a `:block` wrapping multi-statement form. Wrapping
   the single case in a vector keeps the downstream emit consistent."
  [lambda-node]
  (let [body (:body lambda-node)]
    (cond
      (nil? body) []
      (and (map? body) (= :block (:node body))) (:stmts body)
      :else [body])))

(defn- unbox-prelude-for-element
  "Given the block's raw id-typed parameter name (`_cxc_e`) and the
   user-declared lambda param name + element type, produce zero or
   one prelude statements that bind the user name at the correct
   primitive/pointer type inside the block body.

   - :int   → `int64_t <name> = [<raw> longLongValue];`
   - :float → `double <name> = [<raw> doubleValue];`
   - width variants: use the picked C type + NSNumber accessor
   - atom   → `<AtomType> *<name> = (<AtomType> *)<raw>;`
   - id/other → no prelude; user name is bound directly to raw id"
  [raw-name user-name element-type-kw]
  (cond
    (int-family? element-type-kw)
    (let [c-ty     (type-kw->c-type element-type-kw nil)
          nsnum-fn (case (int-bit-width element-type-kw)
                     8  "charValue"
                     16 "shortValue"
                     32 "intValue"
                     "longLongValue")]
      [{:node :raw-c
        :text (str c-ty " " user-name " = [" raw-name " " nsnum-fn "];")}])

    (float-family? element-type-kw)
    (let [c-ty     (type-kw->c-type element-type-kw nil)
          nsnum-fn (if (= [:float 32] element-type-kw)
                     "floatValue"
                     "doubleValue")]
      [{:node :raw-c
        :text (str c-ty " " user-name " = [" raw-name " " nsnum-fn "];")}])

    (and (map? element-type-kw) (:atom element-type-kw))
    [{:node :raw-c
      :text (str (:atom element-type-kw) " *" user-name
                 " = (" (:atom element-type-kw) " *)" raw-name ";")}]

    ;; F65 — String is NSString *; bind with the correct static type
    ;; so downstream reads/methods see NSString, not id.
    (= :string element-type-kw)
    [{:node :raw-c
      :text (str "NSString *" user-name " = (NSString *)" raw-name ";")}]

    :else
    ;; id-typed pass-through: just alias user-name to raw
    [{:node :raw-c
      :text (str "id " user-name " = " raw-name ";")}]))

(defn- lower-for-each-call
  "Special-case lowering for `xs.for_each(e => body)` on a List
   container atom. Produces `[xs cxc_for_each:^(id _cxc_e) { <prelude>
   <body-stmts> }]`. Prelude unboxes the id-typed block param into
   the user's declared name at the element's type; body stmts are
   lowered with `e` bound as a local in ctx (so `infer-type` on
   uses of `e` succeeds).

   Free-variable capture: self and any locals from the enclosing
   scope are captured by ObjC's block copy semantics automatically
   (self as strong reference, primitives by value)."
  [target-lo target-ty lambda-node ctx]
  (let [list-name        (:atom target-ty)
        element-name     (get-in ctx [:atom-env list-name :element-type])
        element-type-kw  (cxc-type->type-kw element-name
                                            (:record-env ctx)
                                            (:atom-env ctx)
                                            (:enum-env ctx)
                                            lambda-node)
        user-param-name  (get-in lambda-node [:params 0 :name])
        raw-param-name   "_cxc_e"
        prelude          (unbox-prelude-for-element raw-param-name
                                                    user-param-name
                                                    element-type-kw)
        body-ctx         (assoc-in ctx [:locals user-param-name] element-type-kw)
        body-nodes       (lambda-body-stmts lambda-node)
        body-stmts       (mapv #(lower-stmt % body-ctx) body-nodes)]
    {:node :objc-msg
     :recv target-lo
     :selector "cxc_for_each:"
     :args [{:node :objc-block-literal
             :params [{:name raw-param-name :c-type "id"}]
             :stmts (into (vec prelude) body-stmts)}]}))

(defn- print-format-string [arg-type]
  (cond
    (= :string arg-type)                    "%s\n"
    ;; Phase D F36a Landing 1b — signed int family → `%lld` + cast to
    ;; long long; unsigned int family → `%llu` + cast to unsigned long
    ;; long; float family → `%f` + cast to double (varargs already
    ;; promote float→double so cast is safe for :float 32).
    (and (int-family? arg-type)
         (or (= :int arg-type)
             (= :int (first arg-type))))    "%lld\n"
    (and (int-family? arg-type)
         (= :uint (first arg-type)))        "%llu\n"
    (= :bool arg-type)                      "%d\n"
    (float-family? arg-type)                "%f\n"
    (and (map? arg-type) (:enum arg-type))  "%d\n"    ; enums are int-backed
    :else (fail-nyi! (str "print of type " (pr-str arg-type)) {:type arg-type})))

(defn- lower-print [call ctx]
  (let [arg-expr (:value (first (:args call)))
        arg-ty   (infer-type arg-expr ctx)]
    {:node :call
     :fn   "printf"
     ;; :printf-arg-type carries the picker's type-kw through to emit
     ;; so the printf emit path can cast varargs to a width that
     ;; matches the format specifier (long long for :int; double for
     ;; :float). Kept out of the AST proper (as metadata on the call
     ;; node, not a wrapper on the arg) so existing tests that inspect
     ;; :args continue to see the raw lowered expression.
     :printf-arg-type arg-ty
     :args [{:node :str :value (print-format-string arg-ty)}
            (lower-expr arg-expr ctx)]}))

(defn- lower-expr [node ctx]
  (case (:node node)
    ;; F65 §3.10.5 — String is NSString *. String literals emit as
    ;; `@"..."` (NSString literal) instead of `"..."` (const char *).
    ;; :objc-str already exists as an emit-node kind for this shape.
    :lit-string {:node :objc-str :value (:value node)}
    :lit-int    {:node :int   :value (:value node)}
    :lit-float  {:node :float :value (:value node)}
    :lit-nil    {:node :nil}
    :lambda     (if (empty? (:params node))
                  ;; Layer 6 Step 12 — zero-arg lambda `() => <expr>`
                  ;; lowers to an ObjC block literal `^{ <stmt>; }`.
                  ;; Body lowers as an expression; emit-stmt on `:call`
                  ;; / `:objc-msg` already produces `<expr>;`, so no
                  ;; extra wrapper needed. Non-zero-arg lambdas remain
                  ;; consumer-dispatched (for_each etc); this branch is
                  ;; escaping-block only at MVP.
                  {:node :objc-block-literal
                   :params []
                   :stmts  [(lower-expr (:body node) ctx)]}
                  (fail-nyi! (str "lambda expression with " (count (:params node))
                                  " params — needs a consumer to invoke "
                                  "through (Layer 5 collection methods "
                                  "`.map`/`.for_each`; will lower to ObjC "
                                  "blocks)")
                             node))
    :ident      (let [n    (:name node)
                      fsub (get (:field-substitutions ctx) n)
                      sf   (get (:self-fields ctx) n)]
                  (cond
                    ;; F49 Landing A: pre-store invariant check substitutes
                    ;; field references with an already-lowered expression
                    ;; that reads the value which WILL BE stored (ctor
                    ;; param via `{:node :name :sym ...}`, or a default
                    ;; expression pre-lowered by the caller). Emit the
                    ;; substitution verbatim — do NOT re-lower through
                    ;; this branch (that recurses on the same name).
                    fsub
                    fsub

                    (contains? (:locals ctx) n)
                    {:node :name :sym n}

                    (:expr sf)
                    ;; Observable — dispatch through the synthesized getter.
                    ;; `self.name` in emitted C is an ObjC message send.
                    {:node :field
                     :target {:node :name :sym "self"}
                     :field  n}

                    sf
                    ;; Real data-field — direct ivar access; matches how we
                    ;; write self-fields (`_field = value;`).
                    {:node :name :sym (str "_" n)}

                    :else
                    {:node :name :sym n}))
    :self       {:node :name  :sym "self"}
    :binop      (do
                  ;; Audit F47: `in` parses as a binop but its semantic
                  ;; lowering — dispatch to a container's contains
                  ;; method — is Phase D work (needs the container
                  ;; contains method design). Reject with a targeted
                  ;; diagnostic instead of silently emitting literal
                  ;; `in` into C, which clang would then reject as
                  ;; an unknown operator far from the source.
                  (when (= "in" (:op node))
                    (fail-nyi! "`in` membership operator" node))
                  ;; F37: record-typed `==` / `!=` reroutes through the
                  ;; synthesized `<Record>_cxc_equals` free function.
                  ;; Raw C `struct1 == struct2` is not valid C — this
                  ;; is a correctness fix, not an optimization.
                  (let [cmp?     (contains? #{"==" "!="} (:op node))
                        lt       (when cmp? (infer-type (:left node)  ctx))
                        rt       (when cmp? (infer-type (:right node) ctx))
                        rec-name (when cmp?
                                   (when (and (map? lt) (:record lt)
                                              (map? rt) (:record rt)
                                              (= (:record lt) (:record rt)))
                                     (:record lt)))
                        ;; F37b L1 — `Bytes` == `Bytes` reroutes to
                        ;; `-isEqualToData:` (NSData's value-eq for
                        ;; byte content). Raw C `==` on two NSData
                        ;; pointers is pointer-eq, which is almost
                        ;; never what the user wrote.
                        bytes-eq? (and cmp? (= :bytes lt) (= :bytes rt))]
                    (cond
                      rec-name
                      (let [call {:node :call
                                  :fn   (record-synth-equals-name rec-name)
                                  :args [(lower-expr (:left node)  ctx)
                                         (lower-expr (:right node) ctx)]}]
                        (if (= "==" (:op node))
                          call
                          {:node :unary :op "!" :expr call}))
                      bytes-eq?
                      (let [msg {:node :objc-msg
                                 :recv (lower-expr (:left node) ctx)
                                 :selector "isEqualToData:"
                                 :args [(lower-expr (:right node) ctx)]}]
                        (if (= "==" (:op node))
                          msg
                          {:node :unary :op "!" :expr msg}))
                      :else
                      {:node  :binop
                       :op    (case (:op node)
                                "and" "&&"
                                "or"  "||"
                                "is"  "=="            ; pointer-eq for atoms, value-eq for enums/ints
                                (:op node))
                       :left  (lower-expr (:left node)  ctx)
                       :right (lower-expr (:right node) ctx)})))
    :neg        {:node :unary :op "-"
                 :expr (lower-expr (:expr node) ctx)}
    :not        {:node :unary :op "!"
                 :expr (lower-expr (:expr node) ctx)}
    ;; F27 semantic once-only: bind each interior operand to a
    ;; `__typeof__` local via GNU statement-expression, then chain
    ;; comparisons over the bound locals. Emit renders the
    ;; statement-expression from the operand + op vectors.
    :chained-cmp {:node :chained-cmp
                  :operands (mapv #(lower-expr % ctx) (:operands node))
                  :ops      (mapv (fn [op]
                                    (case op
                                      "<" "<" "<=" "<=" ">" ">" ">=" ">="))
                                  (:ops node))}
    :dot        (if (enum-case-access? node ctx)
                  {:node :name
                   :sym  (str (get-in node [:target :name]) "_" (:field node))}
                  (do
                    ;; F33 Landing 1 — reject cross-atom `data:` field
                    ;; reads. infer-type triggers field-type-for-owner
                    ;; which owns the diagnostic; call it here so a bare
                    ;; `.field` in a stmt context (where lower-expr
                    ;; doesn't otherwise type-check) still catches it.
                    (field-type-for-owner (infer-type (:target node) ctx)
                                          ctx (:field node) node)
                    {:node   :field
                     :target (lower-expr (:target node) ctx)
                     :field  (:field node)}))
    ;; F19 L2 — String[i] lowers to `(int64_t)[str characterAtIndex:i]`.
    ;; Other container types still fail-nyi (see infer-type site for the
    ;; F42/F43 dependency rationale).
    :subscript  (let [target-ty (infer-type (:target node) ctx)]
                  (cond
                    (= target-ty :string)
                    {:node   :objc-cast
                     :c-type "int64_t"
                     :expr   {:node     :objc-msg
                              :recv     (lower-expr (:target node) ctx)
                              :selector "characterAtIndex"
                              :args     [(lower-expr (:index node) ctx)]}}

                    :else
                    (fail-nyi! (str "subscript `expr[i]` on target of type "
                                    (pr-str target-ty)
                                    " — F19 L2 handles `String[i]` only. "
                                    "List / Map indexing waits on F42 "
                                    "(from_literal) + F43 (container-as-"
                                    "atom); use `.get`/`.contains` for now.")
                               node)))
    :call
    (let [callee (:callee node)]
      (case (:node callee)
        :ident
        (let [callee-name (:name callee)]
          (cond
            (contains? (:record-env ctx) callee-name)
            (lower-record-ctor callee-name (:args node) ctx)

            (contains? (:atom-env ctx) callee-name)
            (lower-atom-ctor callee-name (:args node) ctx)

            (= "print" callee-name)
            (lower-print node ctx)

            ;; F31/F35 Landing 2 — `panic(msg)` writes `msg` + backtrace
            ;; to stderr and traps. Uncatchable: bypasses `@try/@catch`
            ;; wrappers (which stay for BEAM-style transition isolation
            ;; on ObjC-level faults). MVP accepts a single string arg.
            ;; See plans/f31-f35-concurrency.md L2.
            (= "panic" callee-name)
            (let [args (:args node)
                  _    (when-not (= 1 (count args))
                         (fail-nyi! (str "panic() expects exactly one string "
                                         "arg, got " (count args))
                                    node))
                  msg-expr (:value (first args))
                  _    (when-not (= :lit-string (:node msg-expr))
                         (fail-nyi! (str "panic() arg must be a string literal "
                                         "for MVP (spec §3.5.4). Domain-error "
                                         "payload waits on Result infra.")
                                    node))]
              {:node :ffi-call
               :name "panic"
               :args [(lower-expr msg-expr ctx)]
               :emit-c-fn (fn [rendered]
                            (let [[m] rendered]
                              ;; Inline the backtrace loop so panic works
                              ;; in programs that don't otherwise pull in
                              ;; the runtime-helpers block (needs-runtime?
                              ;; is atom-gated).
                              (str "({ fprintf(stderr, \"panic: %s\\n\", ["
                                   m " UTF8String]); "
                                   "void *__cxc_frs[32]; "
                                   "int __cxc_n = backtrace(__cxc_frs, 32); "
                                   "char **__cxc_sy = backtrace_symbols(__cxc_frs, __cxc_n); "
                                   "if (__cxc_sy) { "
                                   "for (int __cxc_i = 0; __cxc_i < __cxc_n; __cxc_i++) "
                                   "fprintf(stderr, \"    %s\\n\", __cxc_sy[__cxc_i]); "
                                   "free(__cxc_sy); } "
                                   "__builtin_trap(); "
                                   "(void)0; })")))})

            (= "clamp" callee-name)
            ;; Polymorphic numeric clamp. F46: bind each arg to a
            ;; local via GNU statement-expression + `__typeof__`, so
            ;; side-effectful expressions fire once each. Prior
            ;; emit rendered x three times, lo twice, hi twice —
            ;; visible to `clamp(counter.next(), 0, 100)` and
            ;; friends. Preserves signedness: `__typeof__` picks
            ;; the exact C type of each argument.
            (let [args (:args node)
                  [x-arg lo-arg hi-arg] (map :value args)]
              {:node :ffi-call
               :name "clamp"
               :args [(lower-expr x-arg ctx)
                      (lower-expr lo-arg ctx)
                      (lower-expr hi-arg ctx)]
               :emit-c-fn (fn [rendered]
                            (let [[x lo hi] rendered]
                              (str "({ "
                                   "__typeof__(" x ") __cxc_cv = (" x "); "
                                   "__typeof__(" lo ") __cxc_clo = (" lo "); "
                                   "__typeof__(" hi ") __cxc_chi = (" hi "); "
                                   "__cxc_cv < __cxc_clo ? __cxc_clo : "
                                   "(__cxc_cv > __cxc_chi ? __cxc_chi : "
                                   "__cxc_cv); })")))})

            (ffi-builtin? callee-name)
            (let [spec (get ffi-builtins callee-name)
                  args (:args node)]
              (when-not (= (:arity spec) (count args))
                (fail-nyi! (str "FFI builtin `" callee-name
                                "` expects " (:arity spec)
                                " arg(s), got " (count args))
                           node))
              ;; The emit-c template runs against arg strings, but at
              ;; lower time we only have lowered arg NODES — emit renders
              ;; nodes. We carry the template through unchanged and hand
              ;; it emit-side placeholders {0}, {1}, ... that emit-expr
              ;; substitutes with each argument's rendered form.
              {:node :ffi-call
               :name callee-name
               :args (mapv #(lower-expr (:value %) ctx) args)
               ;; The final emit template — arg positions replaced with
               ;; already-rendered strings from emit-expr. Precomputed
               ;; here so emit stays registry-free.
               :emit-c-fn (:emit-c spec)})

            ;; Layer 6 Step 12 — bare call on a Block-typed self-field.
            ;; `cb()` lowers to `_cb()` — a nil-safe check would be
            ;; nice, but ObjC blocks are ARC-managed pointers; invoking
            ;; a nil block crashes. Users must nil-check via `if cb` or
            ;; ensure installation before invocation.
            (and (get-in ctx [:self-fields callee-name])
                 (= "Block" (:cxc-ty (get-in ctx [:self-fields callee-name]))))
            (do
              (when (seq (:args node))
                (fail-nyi! (str "block invocation `" callee-name
                                "(...)` with args — Layer 6 Step 12 MVP "
                                "supports zero-arg void-return `Block` "
                                "only")
                           node))
              ;; Uses the generic :call emit path — fn-name is the
              ;; block ivar (`_cb`), args empty for MVP.
              {:node :call :fn (str "_" callee-name) :args []})

            :else
            (fail-nyi! (str "call to `" callee-name "`") node)))

        :dot   (lower-method-call node ctx)

        (fail-nyi! (str "call with " (:node callee) " callee") node)))
    (fail-nyi! (str "expression " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Statement lowering
;; ---------------------------------------------------------------------------

(defn- lower-stream-emit
  "Lower an untargeted `:>` — stream emission from the enclosing atom.
   The stream must have been discovered during build-atom-env (i.e. the
   atom has some `:>` site declaring this stream, which this call is
   one of). Payload args are marshalled by name against the emit-site's
   declared payload shape; the enclosing atom is identified via
   `self` in ctx locals (set by lower-atom / lower-message-triggered-
   transitions before descending into body clauses). Lowers to
   `[self cxc_emit_<stream>[With<k>:...]]` — the synthesized emit
   method (see stream-methods-for-atom) owns fan-out."
  [node ctx]
  (let [self-info (get-in ctx [:locals "self"])
        _         (when-not (and (map? self-info) (:atom self-info))
                    (fail-nyi! (str "untargeted `:> " (:msg node)
                                    "` outside a def-atom transition body "
                                    "(no `self` in scope)")
                               node))
        atom-name (:atom self-info)
        stream    (get-in ctx [:atom-env atom-name :stream-index (:msg node)])
        _         (when-not stream
                    ;; Shouldn't happen — collect-stream-shapes registered
                    ;; every emit site — but guard against ctx drift.
                    (fail-nyi! (str "atom `" atom-name "` has no `:> "
                                    (:msg node) "` emit registered "
                                    "(stream-index missed it)")
                               node))
        declared-params (mapv (fn [p] {:name (:name p) :default nil})
                              (:payload stream))
        resolved  (resolve-args-against-params
                   (str "stream emit `:> " (:msg node) "` on `" atom-name "`")
                   declared-params
                   (:args node))
        arg-exprs (mapv (fn [{:keys [value]}] (lower-expr value ctx)) resolved)
        param-names (mapv :name (:payload stream))
        selector  (objc-method-selector (str "cxc_emit_" (:msg node)) param-names)
        direct-send {:node :objc-msg
                     :recv {:node :name :sym "self"}
                     :selector selector
                     :args arg-exprs}]
    (if (:async? node)
      ;; Truly async — defer the emit itself to self's own_queue.
      ;; Fanout still runs synchronously WITHIN the deferred block
      ;; (one cxc_send_async per subscriber). Critical for the
      ;; "atom's initial-phase do: emits before anyone subscribed"
      ;; problem: the block sits on own_queue until dispatch_main
      ;; starts, by which point subscribers hooked up in other
      ;; atoms' init/runtimeBinding phases are present.
      {:node :call
       :fn "cxc_send_async"
       :args [{:node :name :sym "self"}
              {:node :objc-block-literal
               :params []
               :stmts [direct-send]}]}
      ;; `|> foo` sync emit — direct call, no queue hop.
      direct-send)))

(defn- lower-send
  "Lower a targeted `:emit` node to a `cxc_send_async` /
   `cxc_wait_for_processing` runtime helper call. Untargeted `:>` fans
   out through the enclosing atom's synthesized stream emit method
   (Step 13). Payload args are matched by name against the receiver's
   declared `msg{...}` payload shape (design §9/§12) — arg order in the
   sender may differ from receiver declaration order.

   F31/F35 L1 — sync send lowers to `cxc_wait_for_processing`
   (spec framing: `|>` is a processing barrier, not send-with-reply).
   Under Layer 4 single-main-queue, the helper is a nil-guarded direct
   call with a debug-build queue-invariant assertion. F32 flips it to
   `dispatch_sync` onto the target's queue — the emit site is the sole
   substitution point. See plans/f31-f35-concurrency.md L1."
  [node ctx]
  (if (nil? (:target node))
    (lower-stream-emit node ctx)
  (let [target-lo (lower-expr (:target node) ctx)
        target-ty (infer-type (:target node) ctx)
        _         (when-not (and (map? target-ty) (:atom target-ty))
                    (fail-nyi! (str "send target must resolve to an atom-typed "
                                    "expression (got " (pr-str target-ty) ")")
                               node))
        atom-name (:atom target-ty)
        atom-info (get-in ctx [:atom-env atom-name])
        _         (when-not atom-info
                    (fail-nyi! (str "unknown atom type `" atom-name "` on send target") node))
        msg-name  (:msg node)
        ;; Cross-atom shape check (Step 10 formalizes diagnostic).
        msg-shape (get-in atom-info [:message-index msg-name])
        _         (when-not msg-shape
                    (fail-nyi! (str "atom `" atom-name "` has no `<: " msg-name
                                    "` message-triggered transition — cannot send")
                               node))
        declared-params (mapv (fn [p] {:name    (:name p)
                                       :default nil})
                              (:payload msg-shape))
        ;; Match sender's args (named/positional) against declared payload.
        resolved  (resolve-args-against-params
                   (str "send `" msg-name "` to `" atom-name "`")
                   declared-params
                   (:args node))
        arg-exprs (mapv (fn [{:keys [value]}] (lower-expr value ctx)) resolved)
        param-names (mapv :name (:payload msg-shape))
        selector  (objc-method-selector msg-name param-names)
        helper    (if (:async? node) "cxc_send_async" "cxc_wait_for_processing")]
    {:node :call
     :fn   helper
     :args [target-lo
            {:node :objc-block-literal
             :stmts [{:node :objc-msg
                      :recv target-lo
                      :selector selector
                      :args arg-exprs}]}]})))

(defn- lower-stmt [node ctx]
  (case (:node node)
    :expr-stmt (lower-expr (:expr node) ctx)
    :emit      (lower-send node ctx)
    :return    {:node :return :value (lower-expr (:expr node) ctx)}
    (fail-nyi! (str "statement " (:node node)) node)))

(defn- lower-do-clause [clause ctx]
  (mapv #(lower-stmt % ctx) (:stmts clause)))

(declare lower-then-update)

(defn- lower-transition-body [clauses ctx]
  (let [[stmts _]
        (reduce
          (fn [[out c] clause]
            (case (:node clause)
              :do-clause
              [(into out (lower-do-clause clause c)) c]

              ;; Layer 6 Step 12 — `then:` on general transitions
              ;; (previously restricted to on-enter-body paths).
              ;; Escaping-block install-then-invoke `then: cb => ...
              ;; do: cb()` needs the assignment to happen before
              ;; the do:-clause runs.
              :then-clause
              [(into out (mapcat #(lower-then-update % c) (:updates clause))) c]

              ;; F23 — let: bindings emit local variable decls and enter
              ;; the ctx as they land; subsequent then/do references
              ;; resolve to them.
              :let-clause
              (let [[decls c']
                    (reduce (fn [[ds cc] b]
                              (let [tk     (infer-type (:expr b) cc)
                                    ct     (type-kw->c-type tk (:expr b))
                                    rhs-lo (lower-expr (:expr b) cc)]
                                [(conj ds {:node :var-decl :name (:name b)
                                           :c-type ct :rhs rhs-lo})
                                 (update cc :locals assoc (:name b) tk)]))
                            [[] c]
                            (:bindings clause))]
                [(into out decls) c'])

              (fail-nyi! (str "transition body clause " (:node clause)) clause)))
          [[] ctx]
          clauses)]
    stmts))

(defn- capitalize-first
  [s]
  (if (str/blank? s) s (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn- field-needs-slot-setter?
  "True when writing this field in a `then:` update must route through
   the synthesized slot setter instead of a direct ivar assignment.
   Applies when the field is a subscription root on the enclosing
   atom's :subscription-map. Other fields keep the simple write."
  [field-name ctx]
  (let [self-info (get-in ctx [:locals "self"])
        atom-name (and (map? self-info) (:atom self-info))
        info      (get-in ctx [:atom-env atom-name])
        subs      (get-in info [:subscription-map field-name])]
    (boolean (seq subs))))

(defn- field-nonnil?
  "F39 L3 — is the field with this name declared `T!` on the enclosing
   atom? Reads self-fields from ctx; returns false when not found (so
   containers without the enclosing-atom context degrade gracefully)."
  [field-name ctx]
  (let [self-info (get-in ctx [:locals "self"])
        atom-name (and (map? self-info) (:atom self-info))
        info      (get-in ctx [:atom-env atom-name])
        f         (get-in info [:field-index field-name])]
    (boolean (:nonnil? f))))

(defn- lower-then-update
  "`then: field => expr` → `_field = <expr>;` for the common case.
   For atom-typed subscription-root fields (Layer 4b nullable-slot
   dynamic subscription), routes through the synthesized slot setter
   `-set<Field>:` so it can (un)register subscriptions on old/new
   source values.

   Audit F44 retirement — the former `xs => xs + e` / `xs => xs - e`
   pattern-match on List-typed fields (which silently mutated in
   place from inside `then:`) is retired. Container mutation belongs
   in `do:` as an ordinary method call: `do: xs.add(e)` / `do: xs.remove(e)`.
   Spec §3.9 uses `+`/`-` for arithmetic only; §1.11 keeps `then:` as
   pure state updates and puts side-effecting method sends in `do:`.

   F39 L3 — for a `T!` field, appends a post-store nonnil-check reading
   the just-assigned ivar. Post-store is safe (warning-grade, no abort)
   and sidesteps eval-once discipline on the RHS expression. Returns
   a vector of stmts so callers `mapcat` naturally over each update."
  [update ctx]
  (let [primary (if (field-needs-slot-setter? (:field update) ctx)
                  {:node :objc-msg
                   :recv {:node :name :sym "self"}
                   :selector (str "set" (capitalize-first (:field update)) ":")
                   :args [(lower-expr (:expr update) ctx)]}
                  {:node :assign
                   :lhs  {:node :name :sym (str "_" (:field update))}
                   :rhs  (lower-expr (:expr update) ctx)})]
    (if (field-nonnil? (:field update) ctx)
      (let [self-info (get-in ctx [:locals "self"])
            atom-name (or (and (map? self-info) (:atom self-info)) "?")]
        [primary
         (nonnil-check-stmt (str "_" (:field update))
                            (str "def-atom `" atom-name "` field write")
                            (:field update))])
      [primary])))

(defn- lower-on-enter-body
  "Body of a wildcard on-enter transition when inlined into init. Same
   as a general transition body but with `then:` support — regular
   `do:` stmts pass through unchanged.

   F23 — `let:` bindings emit local variable declarations at the top
   of the block and enter the ctx as they land, so subsequent
   then/do clauses reference them."
  [clauses ctx]
  (let [[stmts _]
        (reduce
          (fn [[out c] clause]
            (case (:node clause)
              :let-clause
              (let [[decls c']
                    (reduce (fn [[ds cc] b]
                              (let [tk     (infer-type (:expr b) cc)
                                    ct     (type-kw->c-type tk (:expr b))
                                    rhs-lo (lower-expr (:expr b) cc)]
                                [(conj ds {:node :var-decl :name (:name b)
                                           :c-type ct :rhs rhs-lo})
                                 (update cc :locals assoc (:name b) tk)]))
                            [[] c]
                            (:bindings clause))]
                [(into out decls) c'])

              :then-clause
              [(into out (mapcat #(lower-then-update % c) (:updates clause))) c]

              :do-clause
              [(into out (lower-do-clause clause c)) c]

              (fail-nyi! (str "on-enter body clause " (:node clause)) clause)))
          [[] ctx]
          clauses)]
    stmts))

(defn- initial-phase-for
  "First phase mentioned as a transition target — i.e. the phase the
   atom enters when init completes. cxc has no explicit initial-phase
   marker; declaration order fixes it. Atoms without any transitions
   default to `init` — every atom has a phase (spec §1.12), and `init`
   is a semantically-neutral choice for `data:`/`methods:`-only atoms."
  [transitions]
  (or (some :target transitions) "init"))

(defn- collect-phase-names
  "All phase names appearing in transition sources or targets, plus the
   implicit `init` and `end` sentinels. Order-preserving dedup — the
   first mention fixes ordering, which matters for readability of the
   emitted `static NSString * const` block. `init` is always first,
   `end` always last. `self` (audit F24 — spec §1.11) is a target
   pseudo-phase meaning \"no phase change\" and is NOT a real phase."
  [transitions]
  (let [mentioned (concat (keep #(when (= :phase (get-in % [:source :kind]))
                                   (get-in % [:source :name])) transitions)
                          (map :target transitions))
        middle    (remove #{"init" "end" "self"} (distinct mentioned))]
    (vec (concat ["init"] middle ["end"]))))

(defn- phase-constant-name
  "Canonicalized ObjC constant name for a phase: `k<Atom>_phase_<name>`.
   Every read/write in emitted code references one of these — never a
   raw literal — so pointer-equality comparisons are safe (spec §5)."
  [atom-name phase-name]
  (str "k" atom-name "_phase_" phase-name))

(defn- wildcard-on-enter?
  "A transition with implicit source, no message receipt, no guard —
   the `-> phase then/do: ...` form that fires on every entry."
  [t]
  (and (= :implicit (get-in t [:source :kind]))
       (nil? (:receive t))
       (nil? (:when t))))

(defn- message-triggered?
  "A transition with a receive glyph (`<:` async, `<|` sync). Contrasts
   with implicit-source wildcards (`-> phase ...`) which fire on every
   entry to their target phase."
  [t]
  (contains? #{"<:" "<|"} (get-in t [:receive :glyph])))

(defn- transition-trigger-kind
  "Classify a receive-glyph transition's source pattern:
   - :message       — bare ident like `bump`. Direct message-triggered
                      transition (Steps 4-7).
   - :stream        — dot expression like `source.beep`. Stream
                      subscription (Layer 4b Step 13); source atom's
                      static stream emit drives the transition.
   - :method-stream — call expression like `source.every(rate)`. The
                      source atom's method returns a CxcStream value
                      (Layer 5c Step 8); subscriber attaches at
                      cxc_runtimeBinding via -cxc_addSubscriber:."
  [t]
  (let [src (get-in t [:receive :msg :source])]
    (case (:node src)
      :ident :message
      :dot   :stream
      :call  (if (and (map? (:callee src))
                      (= :dot (get-in src [:callee :node])))
               :method-stream
               (fail-nyi! (str "receive-glyph source `:call` needs a "
                               "dot callee (`source.method(args)`); got "
                               (get-in src [:callee :node]))
                          src))
      (fail-nyi! (str "receive-glyph source pattern must be a bare ident "
                      "(message), dot expression (static stream), or "
                      "method call (returned stream); got " (:node src))
                 src))))

(defn- transition-msg-name
  "Extract the message-name from a message-triggered receive-glyph
   transition. Only bare-ident sources call this; stream transitions
   go through transition-stream-descriptor."
  [t]
  (let [src (get-in t [:receive :msg :source])]
    (case (:node src)
      :ident (:name src)
      (fail-nyi! (str "expected bare-ident message source (got " (:node src) ")")
                 src))))

(defn- transition-display-name
  "Human-readable identifier for a receive-glyph transition, used in
   diagnostics. Handles both message-triggered (`bump`) and stream-
   subscribed (`source.beep`) shapes without discriminating on kind."
  [t]
  (let [src (get-in t [:receive :msg :source])]
    (case (:node src)
      :ident (:name src)
      :dot   (str (get-in src [:target :name]) "." (:field src))
      (pr-str src))))

(defn- stream-selector
  "Cocoa selector on the SUBSCRIBER side for a stream receive. Encoding:
   `cxc_stream_<streamname>_from_<SourceClass>Withsender:<k1>:<k2>:...`.
   The `_from_<Source>` suffix scopes the selector to the source class,
   so two receive-glyph transitions on the same subscriber pulling
   streams named identically from different sources compile to distinct
   selectors (Choice 1A, see design analysis).

   Layer 5b Step 6b: every subscriber-side selector carries a leading
   `sender:` argument (typed `id`) filled by the source's fanout with
   `self`. Container fan-in forwarding methods use it as the element
   pointer on re-emit (`e:`); ordinary main-side subscribers receive it
   but can ignore it. Source-side selectors (`cxc_emit_<name>[With...]`)
   are unchanged — sender is derived at fanout, not passed by the caller
   of `:> name`."
  [source-class stream-name payload-keys]
  (objc-method-selector (str "cxc_stream_" stream-name "_from_" source-class)
                        (into ["sender"] payload-keys)))

(defn- stream-emit-selector
  "Cocoa selector on the SOURCE side for a stream emit method:
   `cxc_emit_<streamname>[With<k1>:<k2>:...]`. Called by `:>` emit-site
   lowering; body iterates the subscriber hashtable + cxc_send_async's
   each."
  [stream-name payload-keys]
  (objc-method-selector (str "cxc_emit_" stream-name) payload-keys))

(defn- wildcards-by-target-phase
  "Index wildcards (`-> phase then:/do:`) by their target phase. A
   phase may have zero, one, or (rare) multiple wildcards; multiples
   are concat'd in declaration order (design §6 leaves the count
   unspecified but doesn't forbid — declaration order is the natural
   choice)."
  [transitions]
  (reduce (fn [acc t] (update acc (:target t) (fnil conj []) t))
          {}
          (filter wildcard-on-enter? transitions)))

(defn- inline-wildcard-then-stmts
  "Then-clauses from all wildcards targeting phase X, in order. Fires
   BEFORE the specific transition's then: so the specific can override
   (design §6 emit ordering: wildcard defaults, then specific overrides)."
  [wildcards ctx]
  (vec (mapcat (fn [w]
                 (mapcat (fn [c] (mapcat #(lower-then-update % ctx) (:updates c)))
                         (filter #(= :then-clause (:node %)) (:body w))))
               wildcards)))

(defn- inline-wildcard-do-stmts
  "Do-clauses from all wildcards targeting phase X, in order. Fires
   BEFORE the specific transition's do: — wildcard's side effects
   establish invariants of the phase, then the specific transition
   layers its own on top."
  [wildcards ctx]
  (vec (mapcat (fn [w]
                 (mapcat (fn [c] (lower-do-clause c ctx))
                         (filter #(= :do-clause (:node %)) (:body w))))
               wildcards)))

(defn- lower-transition-branch
  "Lower one transition into a `:transition-branch` — an `if` block on
   the source phase (+ combined header/body `when:` guards) that runs
   the transition's body and returns. Multiple transitions on the same
   selector are stacked in first-match order inside one method (design
   §12: first-match dispatch).

   Emit order per §6:
     1. wildcard then: (for target phase)   \\
     2. specific then:                        }-- state changes
     3. phase update (source → target)      /
     4. wildcard do:  (for target phase)    \\
     5. specific do:                          }-- side effects
     6. post-transition invariants (Step 14)

   Payload names in `msg{k: T}` bind as locals visible in when:/then:/do:
   (design §12). Their type-kws land in the ctx locals so infer-type
   resolves references, and lower-expr emits them as `{:node :name :sym k}`."
  [atom-name t ctx wildcards-by-phase]
  (when (not= :phase (get-in t [:source :kind]))
    (fail-nyi! (str "message-triggered transition on def-atom `" atom-name
                    "` must have an explicit source phase (got "
                    (get-in t [:source :kind]) ")")
               t))
  (let [source-name  (get-in t [:source :name])
        target       (:target t)
        msg          (get-in t [:receive :msg])
        payload      (or (:payload msg) [])
        payload-locals (into {} (map (fn [p]
                                       [(:name p)
                                        (cxc-type->type-kw (base-type-name (:type p))
                                                           (:record-env ctx)
                                                           (:atom-env ctx)
                                                           (:enum-env ctx)
                                                           p)])
                                     payload))
        ;; `self` + payload names in the branch body — infer-type needs
        ;; them for guard/then/do expressions that reference payload.
        ctx'         (assoc ctx :locals
                            (merge (:locals ctx)
                                   payload-locals
                                   {"self" {:atom atom-name}}))
        header-when  (:when t)
        body-clauses (:body t)
        then-clauses (filter #(= :then-clause (:node %)) body-clauses)
        do-clauses   (filter #(= :do-clause   (:node %)) body-clauses)
        let-clauses  (filter #(= :let-clause  (:node %)) body-clauses)
        when-clauses (filter #(= :when-clause (:node %)) body-clauses)
        ;; F23 — let: bindings lower to local variable declarations that
        ;; sit at the top of the transition body. Each binding's RHS
        ;; expression is type-inferred (spec §1.11 — bare `name: expr`,
        ;; no user type annotation); the inferred type-kw maps to a C
        ;; type via `type-kw->c-type` for the declaration. Bindings
        ;; enter `ctx' :locals` as they land, so later bindings + the
        ;; when/then/do clauses reference them directly.
        [let-decls ctx']
        (reduce (fn [[decls c] b]
                  (let [tk       (infer-type (:expr b) c)
                        c-type   (type-kw->c-type tk (:expr b))
                        rhs-lo   (lower-expr (:expr b) c)]
                    [(conj decls {:node :var-decl :name (:name b)
                                  :c-type c-type :rhs rhs-lo})
                     (update c :locals assoc (:name b) tk)]))
                [[] ctx']
                (mapcat :bindings let-clauses))
        body-whens   (mapv :expr when-clauses)
        guard-exprs  (cond-> []
                       header-when      (conj header-when)
                       (seq body-whens) (into body-whens))
        guard-lo     (when (seq guard-exprs)
                       ;; AND-fold all guards into one expression.
                       (reduce (fn [acc e]
                                 {:node :binop :op "&&"
                                  :left acc :right (lower-expr e ctx')})
                               (lower-expr (first guard-exprs) ctx')
                               (rest guard-exprs)))
        then-stmts   (vec (mapcat (fn [c] (mapcat #(lower-then-update % ctx')
                                                  (:updates c)))
                                  then-clauses))
        do-stmts     (vec (mapcat (fn [c] (lower-do-clause c ctx')) do-clauses))
        ;; Audit F24: `-> self` means self-transition, no phase change
        ;; (spec §1.11 line 1312 — *"target phase. May be a phase name,
        ;; `self` (self-transition, no phase change), or `end`
        ;; (deconstruct)"*). Prior code treated `self` as a literal
        ;; phase name and generated `k<Atom>_phase_self`, causing a
        ;; bogus phase change. Now `self` is treated the same as
        ;; source-name for both phase-update suppression and wildcard
        ;; inlining.
        self-target? (= target "self")
        phase-update (when (and (not self-target?) (not= target source-name))
                       {:node :assign
                        :lhs {:node :name :sym "_phase"}
                        :rhs {:node :name
                              :sym (phase-constant-name atom-name target)}})
        ;; Wildcards inline only when the transition actually CHANGES
        ;; phase (target != source). Self-loops (both `-> <source>` and
        ;; `-> self`) don't count as "entering" the phase — the atom
        ;; was already there — so a wildcard's `do:` doesn't rerun on
        ;; every message that keeps the phase the same. Without this,
        ;; `-> alive do: self |> bump` combined with `alive <: bump ->
        ;; alive` (or `-> self`) would recurse infinitely.
        target-wildcards (if (or self-target? (= target source-name))
                           []
                           (get wildcards-by-phase target []))
        w-then-stmts (inline-wildcard-then-stmts target-wildcards ctx')
        w-do-stmts   (inline-wildcard-do-stmts   target-wildcards ctx')
        ;; target=end routes through -transitionToEnd (design §7.3);
        ;; that method owns phase-set, `-> end` wildcard, cascade,
        ;; parent notify. Audit F26: spec §1.11 line 1281 —
        ;; *"`end` transitions may have `when:` and `do:` blocks;
        ;; `then:` in an `end` transition is meaningless (the atom's
        ;; state is being discarded) and is a compile error."* Prior
        ;; behaviour rejected BOTH `then:` and `do:` on target=end;
        ;; corrected to reject only `then:` and to run `do:` stmts
        ;; before `-transitionToEnd` (they must fire while the atom
        ;; is still in the origin phase — sibling atoms still see the
        ;; source state during the emission).
        end-target?  (= "end" target)
        _            (when (and end-target? (seq then-stmts))
                       (throw (ex-info
                               (str "`then:` on a target=`end` transition "
                                    "(atom `" atom-name "`, trigger `"
                                    (transition-display-name t)
                                    "`) is a compile error — the atom's state "
                                    "is about to be discarded, so field updates "
                                    "are meaningless. See spec §1.11.")
                               {:kind :compile-error :node t})))
        ;; Post-transition invariant check (design §14). Skipped for
        ;; target=end — the transitionToEnd path owns invariant checks
        ;; on its own trajectory.
        check-invariants-stmt {:node :objc-msg
                               :recv {:node :name :sym "self"}
                               :selector "checkInvariants"
                               :args []}
        raw-body     (cond-> []
                       ;; F23 — let: bindings sit at the top of the
                       ;; body so they're in scope for guards+then+do.
                       (seq let-decls)    (into let-decls)
                       (seq w-then-stmts) (into w-then-stmts)
                       (seq then-stmts)   (into then-stmts)
                       phase-update       (conj phase-update)
                       (seq w-do-stmts)   (into w-do-stmts)
                       (seq do-stmts)     (into do-stmts)
                       :always            (conj check-invariants-stmt))
        ;; BEAM-style @try/@catch (design §20 / Step 15). Wraps the
        ;; body so any runtime exception routes the atom into
        ;; -transitionToEnd, leaving the parent slot to nil and the
        ;; process running. End-target branches don't need wrapping —
        ;; they're just `[self transitionToEnd]`, and transitionToEnd
        ;; owns its own catch (loop-guard variant).
        body         (if end-target?
                       ;; Audit F26: run the transition's `do:` stmts
                       ;; while still in the source phase, then hand
                       ;; off to -transitionToEnd for phase-set +
                       ;; `-> end` wildcard + cascade + parent notify.
                       (into (vec do-stmts)
                             [{:node :objc-msg
                               :recv {:node :name :sym "self"}
                               :selector "transitionToEnd"
                               :args []}])
                       [{:node :end-catch-wrap :body raw-body}])]
    {:node        :transition-branch
     :source-phase-const (phase-constant-name atom-name source-name)
     :guard       guard-lo
     :body        body}))

(defn- resolve-stream-source
  "The receive-glyph's source is `source.event` (a :dot node). Walk
   the target to determine which atom class emits the stream; must
   be an atom-typed data-field / cap on the enclosing atom. Nullable
   slots are supported at Layer 4b via slot setters that re-register
   subscriptions on assignment. Returns `{:source-class C
   :source-field F}` when legal, else fails."
  [atom-name enclosing-fields dot-src]
  (let [target (:target dot-src)
        _      (when-not (= :ident (:node target))
                 (fail-nyi! (str "receive-glyph source `" (:field dot-src)
                                 "` must be `<field>." (:field dot-src)
                                 "` where <field> names a data-field / cap "
                                 "on the enclosing atom (got "
                                 (:node target) ")")
                            dot-src))
        fname  (:name target)
        field  (get enclosing-fields fname)
        _      (when-not field
                 (fail-nyi! (str "receive-glyph source `" fname "." (:field dot-src)
                                 "` — no data-field / cap named `" fname
                                 "` on atom `" atom-name "`")
                            dot-src))
        cxc-ty (:cxc-ty field)]
    {:source-class cxc-ty
     :source-field fname}))

(defn- validate-stream-subscribe
  "Cross-check a stream subscription against the source atom's
   stream-index: source class must exist, stream must be declared
   (i.e. source atom has some `:>` emit for it), payload keys AND
   inferred types must match the subscriber's declared payload.
   Fail-nyi with a diagnostic naming the mismatch — parallels the
   cross-atom message shape check at Step 10."
  [atom-name t {:keys [source-class]} ctx]
  (let [msg          (get-in t [:receive :msg])
        stream-name  (get-in msg [:source :field])
        payload      (or (:payload msg) [])
        source-info  (get-in ctx [:atom-env source-class])
        _            (when-not source-info
                       (fail-nyi! (str "receive-glyph source `" source-class
                                       "` is not a def-atom in the program")
                                  t))
        stream       (get-in source-info [:stream-index stream-name])
        _            (when-not stream
                       (fail-nyi! (str "atom `" source-class "` emits no `:> "
                                       stream-name "` stream — receive-glyph "
                                       "`" (:source-field t) "." stream-name
                                       "` on `" atom-name "` has no source")
                                  t))
        expected-keys (mapv :name (:payload stream))
        got-keys      (mapv :name payload)
        _            (when (not= expected-keys got-keys)
                       (fail-nyi! (str "receive-glyph payload shape on atom `"
                                       atom-name "` subscribing to `"
                                       source-class "." stream-name
                                       "` differs from source-side emit shape: "
                                       "declared " got-keys ", source emits "
                                       expected-keys)
                                  t))
        ;; Type check: subscriber's declared types vs source's inferred.
        source-typed (get-in source-info [:stream-payload-index stream-name])
        _            (when (seq payload)
                       (doseq [[sub-p src-p] (map vector payload source-typed)]
                         (let [sub-ty (cxc-type->type-kw
                                       (base-type-name (:type sub-p))
                                       (:record-env ctx) (:atom-env ctx)
                                       (:enum-env ctx) sub-p)
                               src-ty (:type-kw src-p)]
                           (when (not= sub-ty src-ty)
                             (fail-nyi!
                              (str "receive-glyph payload key `" (:name sub-p)
                                   "` on atom `" atom-name "` subscribing to `"
                                   source-class "." stream-name
                                   "` declared as " (pr-str sub-ty)
                                   ", but source emits it as " (pr-str src-ty))
                              t)))))]
    {:source-class source-class
     :stream-name  stream-name
     :payload      payload}))

(defn- lower-receive-glyph-transitions
  "Lower every receive-glyph transition on this atom, grouping by
   selector so `alive <: bump ...` and `dying <: bump ...` collapse
   into one ObjC method with two first-match branches (design §12).
   Handles both discriminants of receive-glyph source (Step 13):
     - :message — bare-ident (`bump`), receiver selector = `<msg>[With...]`
     - :stream  — dot expression (`source.beep`), receiver selector =
       `cxc_stream_<stream>_from_<Source>[With...]`, plus a
       subscription tuple registered later in cxc_runtimeBinding.
   Returns `{:methods [...], :stream-subscriptions [...]}`. The
   subscription vector carries `{:source-field :source-class
   :stream-name}` per unique subscription (dedup'd, so multiple
   receive sites on the same source-class.stream register once)."
  [atom-name transitions ctx]
  (let [receive-txns (filter #(and (:receive %)
                                   (contains? #{"<:" "<|"}
                                              (get-in % [:receive :glyph])))
                             transitions)
        ;; Under Layer 4's single-thread model (queues target main),
        ;; `<:` and `<|` are functionally equivalent: the atom's serial
        ;; queue already ensures "no other transition runs meanwhile"
        ;; that spec §11 attributes to `<|`. Blocks dispatched via
        ;; cxc_send_async run in the enqueue order; cxc_send_sync
        ;; already runs the block synchronously. Layer 4c multi-thread
        ;; will need actual dispatch_semaphore rendezvous for `<|` to
        ;; distinguish from `<:` semantically.
        wildcards-by-phase (wildcards-by-target-phase transitions)
        enclosing-fields (get-in ctx [:self-fields])
        ;; Descriptor per transition: {:kind :group-key :source-selector-info}.
        descs      (mapv
                    (fn [t]
                      (case (transition-trigger-kind t)
                        :message
                        (let [msg-name (transition-msg-name t)
                              payload  (or (get-in t [:receive :msg :payload]) [])
                              param-names (mapv :name payload)]
                          {:kind        :message
                           :txn         t
                           :group-key   [:message msg-name]
                           :selector    (objc-method-selector msg-name param-names)
                           :payload     payload})
                        :stream
                        (let [src-info (resolve-stream-source
                                        atom-name enclosing-fields
                                        (get-in t [:receive :msg :source]))
                              validated (validate-stream-subscribe
                                         atom-name t src-info ctx)
                              stream-name (:stream-name validated)
                              source-class (:source-class validated)
                              payload    (:payload validated)
                              param-names (mapv :name payload)]
                          {:kind         :stream
                           :txn          t
                           :group-key    [:stream source-class stream-name]
                           :selector     (stream-selector source-class stream-name param-names)
                           :payload      payload
                           :source-class source-class
                           :source-field (:source-field src-info)
                           :stream-name  stream-name})

                        :method-stream
                        ;; Layer 6 Step 1: source is `<field>.<method>(args)`.
                        ;; The method must be declared on the source-class atom
                        ;; and return `Stream of T`. Payload type comes from
                        ;; the method's return-type node's `:inner` (T), NOT
                        ;; a compiler-side registry — this retires the
                        ;; Layer 5c `method-stream-payload-shapes` shim.
                        ;;
                        ;; Wire-side selector key is fixed as `value:` for the
                        ;; single-payload case. The receive site declares its
                        ;; own local name for the payload (`{whatever: T}`) —
                        ;; that name becomes the ObjC method param name; the
                        ;; selector part stays `value:`. Different receivers
                        ;; can bind different local names without producing
                        ;; different selectors.
                        (let [src        (get-in t [:receive :msg :source])
                              dot        (:callee src)
                              target     (:target dot)
                              method-nm  (:field dot)
                              _          (when-not (= :ident (:node target))
                                           (fail-nyi!
                                            (str "receive-glyph method-stream source "
                                                 "must be `<field>." method-nm "(args)`; "
                                                 "got a nested expression as receiver")
                                            src))
                              field-nm   (:name target)
                              field      (get enclosing-fields field-nm)
                              _          (when-not field
                                           (fail-nyi!
                                            (str "receive-glyph method-stream source `"
                                                 field-nm "." method-nm
                                                 "()` — no data-field / cap named `"
                                                 field-nm "` on atom `" atom-name "`")
                                            src))
                              source-class (:cxc-ty field)
                              source-info  (get-in ctx [:atom-env source-class])
                              _            (when-not source-info
                                             (fail-nyi!
                                              (str "method-stream source `" source-class
                                                   "` is not a def-atom in the program")
                                              src))
                              method-info  (get-in source-info [:method-index method-nm])
                              _            (when-not method-info
                                             (fail-nyi!
                                              (str "atom `" source-class "` has no `"
                                                   method-nm "()` method — "
                                                   "receive-glyph `" field-nm "."
                                                   method-nm "()` on `" atom-name
                                                   "` has no source")
                                              src))
                              return-ty    (:cxc-return-ty method-info)
                              return-node  (:cxc-return-ty-node method-info)
                              _            (when-not (= "CxcStream" return-ty)
                                             (fail-nyi!
                                              (str "method `" source-class "."
                                                   method-nm "()` returns `"
                                                   return-ty "`, but receive-glyph "
                                                   "requires a `Stream` return type")
                                              src))
                              ;; Extract T from `Stream of T`. `:type-of` node
                              ;; carries :inner as another type node.
                              inner-node   (:inner return-node)
                              payload-cxc-ty (when (and inner-node
                                                        (contains? #{:type-ref :type-nullable}
                                                                   (:node inner-node)))
                                               (base-type-name inner-node))
                              declared     (or (:payload (get-in t [:receive :msg]))
                                               [])
                              expects-payload? (some? payload-cxc-ty)
                              _            (cond
                                             ;; Bare `Stream` without `of T` and receiver declared payload — mismatch.
                                             (and (not expects-payload?) (seq declared))
                                             (fail-nyi!
                                              (str "method `" source-class "."
                                                   method-nm "()` returns bare `Stream`, "
                                                   "but receive declares payload "
                                                   (mapv :name declared))
                                              src)
                                             ;; `Stream of T` with no receive-declared payload — need one.
                                             (and expects-payload? (empty? declared))
                                             (fail-nyi!
                                              (str "method `" source-class "."
                                                   method-nm "()` returns `Stream of "
                                                   payload-cxc-ty
                                                   "` — receive must declare `{name: "
                                                   payload-cxc-ty "}` to bind the "
                                                   "payload locally")
                                              src)
                                             ;; Multi-key declared payload — Layer 6 single-key only for now.
                                             (and expects-payload? (> (count declared) 1))
                                             (fail-nyi!
                                              (str "method-stream `" field-nm "."
                                                   method-nm "()` payload has "
                                                   (count declared) " keys; Layer 6 "
                                                   "supports single-key payload "
                                                   "(`Stream of T` shape)")
                                              src)
                                             ;; Type check the single declared payload against T.
                                             (and expects-payload?
                                                  (= 1 (count declared))
                                                  (not= payload-cxc-ty
                                                        (base-type-name (:type (first declared)))))
                                             (fail-nyi!
                                              (str "method-stream `" field-nm "."
                                                   method-nm "()` payload key `"
                                                   (:name (first declared))
                                                   "` declared as `"
                                                   (base-type-name (:type (first declared)))
                                                   "`, but source emits `Stream of "
                                                   payload-cxc-ty "`")
                                              src))
                              payload      declared
                              ;; Wire selector uses fixed `value:` key part so
                              ;; all subscribers to the same method-stream get
                              ;; the same selector regardless of their local
                              ;; binding name. Empty payload → no key parts.
                              wire-keys    (if expects-payload? ["value"] [])]
                          {:kind          :method-stream
                           :txn           t
                           :group-key     [:method-stream source-class method-nm]
                           :selector      (stream-selector source-class method-nm wire-keys)
                           :payload       payload
                           :source-class  source-class
                           :source-field  field-nm
                           :method-name   method-nm
                           :method-args   (:args src)})))
                    receive-txns)
        by-key    (reduce (fn [acc d] (update acc (:group-key d) (fnil conj []) d))
                          {}
                          descs)
        key-order (distinct (mapv :group-key descs))
        methods   (mapv
                   (fn [k]
                     (let [group (get by-key k)
                           first-d (first group)
                           payload (:payload first-d)
                           user-params (mapv
                                        (fn [p]
                                          {:name   (:name p)
                                           :c-type (cxc-type->c
                                                    (:type p)
                                                    (:record-env ctx)
                                                    (:atom-env ctx)
                                                    (:enum-env ctx))})
                                        payload)
                           ;; Layer 5b Step 6b: stream receives carry a
                           ;; leading sender:(id) param matching the
                           ;; source's fanout-side arg. Applies to both
                           ;; static streams (Step 13) and method-returned
                           ;; streams (Step 8) — CxcStream's cxc_emit fills
                           ;; sender from its `source` property. Message
                           ;; receives (`:message` kind) skip it — same-atom
                           ;; self-sends don't need sender identity.
                           method-params (if (contains? #{:stream :method-stream}
                                                        (:kind first-d))
                                           (into [{:name "sender" :c-type "id"}]
                                                 user-params)
                                           user-params)
                           branches (mapv #(lower-transition-branch
                                            atom-name (:txn %) ctx
                                            wildcards-by-phase)
                                          group)]
                       {:node        :objc-method
                        :selector    (:selector first-d)
                        :return-type "void"
                        :params      method-params
                        :body        branches}))
                   key-order)
        ;; Dedup stream subscriptions by (source-field, stream-name):
        ;; multiple receive-glyphs on the same source.stream on THIS
        ;; atom register only once (the ObjC method holds all branches).
        subs (->> descs
                  (filter #(= :stream (:kind %)))
                  (map (fn [d] (select-keys d [:source-field :source-class
                                               :stream-name])))
                  distinct
                  vec)
        ;; Method-returned stream subscriptions (Layer 5c Step 8) run a
        ;; different registration path: at cxc_runtimeBinding, call the
        ;; method to get a CxcStream, then -cxc_addSubscriber:self. We
        ;; keep the original AST call args so lowering happens against
        ;; the correct ctx at runtimeBinding-emit time.
        method-subs (->> descs
                         (filter #(= :method-stream (:kind %)))
                         (map (fn [d] (select-keys d [:source-field :source-class
                                                      :method-name :method-args])))
                         distinct
                         vec)]
    {:methods                methods
     :stream-subscriptions   subs
     :method-stream-subs     method-subs}))

(defn- transition-payload-locals
  "Locals a receive-glyph transition body sees, keyed by payload name →
   inferred :type-kw. Used to build ctx for stream emit type inference
   inside receive-glyph bodies (design §9/§12 payload binding)."
  [t record-env atom-env enum-env]
  (let [payload (or (get-in t [:receive :msg :payload]) [])]
    (into {} (map (fn [p]
                    [(:name p)
                     (cxc-type->type-kw (base-type-name (:type p))
                                        record-env atom-env enum-env p)])
                  payload))))

(defn- infer-streams-in-atom-env
  "Second pass over atom-env: for every atom, walk every emit site in
   the ctx that would exist at that site's textual position and infer
   each named arg's :type-kw + :c-type. Group by stream name; every
   emit of the same stream must agree on each key's type
   (subscriber-side selector encodes types into a single signature).

   Ctx per site (mirrors the lowering ctx):
   - wildcard-on-enter targeting the initial phase → self-fields +
     ctor-params (matches ctx-in-init)
   - wildcard-on-enter targeting a non-initial phase → self-fields
     only. Non-initial wildcards inline at multiple transition sites
     with different payload bindings; picking one arbitrarily would
     miscompile — so payload-consuming emits inside such wildcards
     fail during infer-type on the unknown local (users move them
     to the specific transition's `do:`).
   - receive-glyph transition → self-fields + payload locals

   Populates :stream-payload-index on each atom-env entry:
     {stream-name [{:name :type-kw :c-type} ...]}
   Must run AFTER infer-observables-in-atom-env because emit args
   may reference observables (typed on that pass)."
  [atom-env record-env enum-env]
  (reduce-kv
   (fn [env atom-name info]
     (let [self-fields (into {} (map (juxt :name identity) (:fields info)))
           self-plus-obs (into self-fields
                               (map (juxt :name identity)
                                    (:observables-typed info)))
           ctor-locals (into {} (map (fn [p]
                                       [(:name p)
                                        (cxc-type->type-kw (:cxc-ty p)
                                                           record-env atom-env
                                                           enum-env p)])
                                     (:ctor-params info)))
           self-local  {"self" {:atom atom-name}}
           base-ctx    {:record-env record-env :atom-env atom-env
                        :enum-env enum-env
                        :self-fields self-plus-obs}
           initial-phase (initial-phase-for (:transitions info))
           ctx-for
           (fn [t]
             (cond
               (and (wildcard-on-enter? t) (= initial-phase (:target t)))
               (assoc base-ctx :locals (merge ctor-locals self-local))

               (wildcard-on-enter? t)
               (assoc base-ctx :locals self-local)

               :else
               (assoc base-ctx :locals
                      (merge self-local
                             (transition-payload-locals
                              t record-env atom-env enum-env)))))
           sites (mapcat
                  (fn [t]
                    (let [ctx (ctx-for t)]
                      (map (fn [e]
                             {:stream (:msg e)
                              :emit   e
                              :args   (mapv (fn [a]
                                              {:name (:name a)
                                               :type-kw (infer-type (:value a) ctx)})
                                            (:args e))})
                           (emit-nodes-in [t]))))
                  (:transitions info))
           typed (reduce
                  (fn [acc {:keys [stream args emit]}]
                    (if-let [existing (get acc stream)]
                      (do (doseq [[i {:keys [name type-kw]}]
                                  (map-indexed vector args)]
                            (let [prev (get-in existing [i :type-kw])]
                              (when (not= prev type-kw)
                                (fail-nyi!
                                 (str "def-atom `" atom-name
                                      "` emits stream `" stream
                                      "` with inconsistent type on key `"
                                      name "`: previously " (pr-str prev)
                                      ", here " (pr-str type-kw)
                                      "; every emit of the same stream must "
                                      "agree on payload key types")
                                 emit))))
                          acc)
                      (assoc acc stream
                             (mapv (fn [{:keys [name type-kw]}]
                                     {:name    name
                                      :type-kw type-kw
                                      :c-type  (type-kw->c-type type-kw emit)})
                                   args))))
                  {}
                  sites)]
       (assoc env atom-name
              (assoc info :stream-payload-index typed))))
   atom-env atom-env))

;; ---------------------------------------------------------------------------
;; Stream source-side synthesis (Step 13, Choice 3A + generic dispatch).
;;
;; A source atom owns one NSHashTable (weak refs) per stream it emits.
;; The compiler synthesizes four kinds of members per atom:
;;   1. Ivar/property `_stream_subscribers_<name>` per stream
;;   2. Init statement in cxc_structuralSetup: hashtable allocation
;;   3. Emit method `-cxc_emit_<name>[With<k>:...]` iterating + fan-out
;;   4. Subscribe methods:
;;        - Per-stream `-cxc_addSubscriberFor_<name>:` (compiled sites)
;;        - Generic  `-cxc_addSubscriberToStream:sub:` (restore/reflection)
;;
;; All subscribe bodies wrap addObject in dispatch_sync(_own_queue, ^{})
;; to future-proof against Layer 4c multi-thread — free under single-
;; thread (dispatch_sync-to-current-queue is fast). See design analysis
;; §4.5 for the rationale.
;; ---------------------------------------------------------------------------

(defn- stream-subscribers-ivar-name
  [stream-name]
  (str "_stream_subscribers_" stream-name))

(defn- stream-subscribers-prop
  "Per-stream `NSHashTable *_stream_subscribers_<name>` property.
   `strong` under ARC because the hashtable itself is owned by this
   atom; weak semantics are on the HASHTABLE's stored subscribers
   (configured via +weakObjectsHashTable), not on the container."
  [stream-name]
  {:name   (subs (stream-subscribers-ivar-name stream-name) 1)  ; drop leading _
   :c-type "NSHashTable *"
   :attrs  ["nonatomic" "strong"]})

(defn- stream-source-methods
  "Synthesize the per-stream ivars, init statements, and methods for
   an atom that emits `streams`. `typed-payloads` maps stream-name →
   inferred payload [{:name :type-kw :c-type} ...] (from
   infer-stream-payload-types). Callers wire the returned pieces into
   the class's :properties, cxc_structuralSetup body, and :methods
   list."
  [source-class stream-order typed-payloads]
  (if (empty? stream-order)
    {:properties [] :init-stmts [] :methods []}
    (let [props     (mapv stream-subscribers-prop stream-order)
          init-stmts (mapv (fn [nm]
                             {:node :hashtable-init
                              :ivar (stream-subscribers-ivar-name nm)})
                           stream-order)
          emit-methods
          (mapv (fn [nm]
                  (let [payload (get typed-payloads nm [])
                        keys    (mapv :name payload)
                        c-types (mapv :c-type payload)
                        method-params (mapv (fn [p] {:name (:name p)
                                                     :c-type (:c-type p)})
                                            payload)]
                    {:node        :objc-method
                     :selector    (stream-emit-selector nm keys)
                     :return-type "void"
                     :params      method-params
                     ;; Layer 5b Step 6b: fanout dispatches to subscribers
                     ;; with `sender: self` prepended to the user payload.
                     ;; :arg-names / :arg-c-types match `stream-selector`
                     ;; (which now also prepends "sender").
                     :body        [{:node        :stream-fanout
                                    :ivar        (stream-subscribers-ivar-name nm)
                                    :selector    (stream-selector source-class nm keys)
                                    :arg-names   (into ["sender"] keys)
                                    :arg-c-types (into ["id"] c-types)
                                    :prepend-self? true}]}))
                stream-order)
          per-stream-add
          (mapv (fn [nm]
                  {:node        :objc-method
                   :selector    (str "cxc_addSubscriberFor_" nm ":")
                   :return-type "void"
                   :params      [{:name "sub" :c-type "id"}]
                   :body        [{:node :stream-add-sub
                                  :ivar (stream-subscribers-ivar-name nm)}]})
                stream-order)
          per-stream-remove
          ;; Layer 4b: nullable-slot dynamic subscription. When a slot
          ;; is reassigned, the setter unsubs from the old source. Sends
          ;; are nil-message-safe (Cocoa), so callers pass whatever's in
          ;; the slot without pre-checking.
          (mapv (fn [nm]
                  {:node        :objc-method
                   :selector    (str "cxc_removeSubscriberFor_" nm ":")
                   :return-type "void"
                   :params      [{:name "sub" :c-type "id"}]
                   :body        [{:node :stream-remove-sub
                                  :ivar (stream-subscribers-ivar-name nm)}]})
                stream-order)
          generic-add
          {:node        :objc-method
           :selector    "cxc_addSubscriberToStream:sub:"
           :return-type "void"
           :params      [{:name "streamName" :c-type "NSString *"}
                         {:name "sub"        :c-type "id"}]
           :body        [{:node     :stream-add-sub-dispatch
                          :branches (mapv (fn [nm]
                                            {:stream-name nm
                                             :selector    (str "cxc_addSubscriberFor_"
                                                               nm ":")})
                                          stream-order)}]}
          generic-remove
          {:node        :objc-method
           :selector    "cxc_removeSubscriberFromStream:sub:"
           :return-type "void"
           :params      [{:name "streamName" :c-type "NSString *"}
                         {:name "sub"        :c-type "id"}]
           :body        [{:node     :stream-add-sub-dispatch
                          :branches (mapv (fn [nm]
                                            {:stream-name nm
                                             :selector    (str "cxc_removeSubscriberFor_"
                                                               nm ":")})
                                          stream-order)}]}]
      {:properties props
       :init-stmts init-stmts
       :methods    (-> emit-methods
                       (into per-stream-add)
                       (into per-stream-remove)
                       (conj generic-add generic-remove))})))

;; ---------------------------------------------------------------------------
;; Top-level lowering — records, methods, atoms, main
;; ---------------------------------------------------------------------------

(defn- lower-record-struct [record-node ctx]
  (let [rec (get-in ctx [:record-env (:name record-node)])]
    {:node   :c-struct
     :name   (:name record-node)
     :fields (mapv (fn [f] {:name (:name f) :c-type (:c-type f)}) (:fields rec))}))

(defn- lower-method-fn
  "A record method becomes a free C function. `self` and each typed
   param join the locals env before the body is lowered."
  [record-name method ctx]
  (let [locals   (into {} (map (fn [p]
                                 [(:name p)
                                  (cxc-type->type-kw (:cxc-ty p)
                                                     (:record-env ctx)
                                                     (:atom-env ctx)
                                                     (:enum-env ctx)
                                                     method)])
                               (:params method)))
        ctx'     (assoc ctx :locals locals)
        stmts    (mapv #(lower-stmt % ctx') (:stmts (:body method)))
        params   (mapv (fn [p]
                         {:name   (:name p)
                          :c-type (if (= "self" (:name p))
                                    record-name
                                    (cxc-type->c {:node :type-ref :name (:cxc-ty p)}
                                                 (:record-env ctx)
                                                 (:atom-env ctx)
                                                 (:enum-env ctx)))})
                       (:params method))
        ret-c    (cxc-type->c {:node :type-ref :name (:cxc-return-ty method)}
                              (:record-env ctx) (:atom-env ctx) (:enum-env ctx))]
    {:node        :c-function
     :name        (c-method-name record-name (:name method))
     :return-type ret-c
     :params      params
     :body        stmts}))

(defn- record-method-functions
  "For every record, lower every one of its methods to a :c-function."
  [ctx]
  (vec (for [[rec-name info] (:record-env ctx)
             m               (:methods info)]
         (lower-method-fn rec-name m ctx))))

;; ---------------------------------------------------------------------------
;; F37 — synthesized record equality + hash
;;
;; Spec §3.3, §3.4.6: records auto-derive structural equality and hash.
;; Currently `V == W` on two struct-typed operands lowers to raw C
;; `V == W`, which is not valid C (structs don't compare with ==). This
;; landing synthesizes `<Record>_cxc_equals` and `<Record>_cxc_hash`
;; free C functions and reroutes `==`/`!=` between record-typed operands
;; through the equals fn. Prefix `cxc_` avoids collision with user-
;; defined `equals` / `hash` methods; user methods would still lower to
;; `<Record>_equals` via `c-method-name`.
;;
;; Bytes encoding (`as() to Bytes` / `from(Bytes)` per §3.4.6) is a
;; follow-on landing — requires per-field byte layout decisions that
;; are more subtle than equals.
;; ---------------------------------------------------------------------------

(defn- record-synth-equals-name [record-name]
  (str record-name "_cxc_equals"))

(defn- record-synth-hash-name [record-name]
  (str record-name "_cxc_hash"))

(defn- primitive-eq-op?
  "True when the field's cxc type name is a primitive whose C
   representation supports raw `==` (all numerics, char, bool, and
   enums-as-int)."
  [cxc-ty ctx]
  (or (contains? primitive-c-type cxc-ty)
      (contains? (:enum-env ctx) cxc-ty)))

(defn- field-equals-c-src
  "Emit a C expression comparing field `f` between two record variables
   `a` and `b`. Dispatched on field type: primitives + enums use `==`;
   NSString uses `-isEqualToString:`; nested records recurse into their
   synthesized equals; atoms and NSObject containers use pointer / NSObject
   equality."
  [f a-name b-name ctx]
  (let [fname   (:name f)
        cxc-ty  (:cxc-ty f)]
    (cond
      (= "String" cxc-ty)
      (str "((" a-name "." fname " == " b-name "." fname ") || "
           "[" a-name "." fname " isEqualToString:" b-name "." fname "])")

      (contains? (:record-env ctx) cxc-ty)
      (str (record-synth-equals-name cxc-ty)
           "(" a-name "." fname ", " b-name "." fname ")")

      (contains? (:atom-env ctx) cxc-ty)
      (str "(" a-name "." fname " == " b-name "." fname ")")

      (primitive-eq-op? cxc-ty ctx)
      (str "(" a-name "." fname " == " b-name "." fname ")")

      :else
      (str "[" a-name "." fname " isEqual:" b-name "." fname "]"))))

(defn- field-hash-c-src
  "Emit a C expression producing a `long` hash contribution for one
   field. Combined with 31-multiply accumulator."
  [f var-name ctx]
  (let [fname   (:name f)
        cxc-ty  (:cxc-ty f)]
    (cond
      (= "String" cxc-ty)
      (str "(long)[" var-name "." fname " hash]")

      (contains? (:record-env ctx) cxc-ty)
      (str (record-synth-hash-name cxc-ty)
           "(" var-name "." fname ")")

      (contains? (:atom-env ctx) cxc-ty)
      (str "(long)(intptr_t)" var-name "." fname)

      (primitive-eq-op? cxc-ty ctx)
      (str "(long)" var-name "." fname)

      :else
      (str "(long)[" var-name "." fname " hash]"))))

(defn- synthesize-record-equals-fn
  "Produce a `:c-function` node implementing structural equality for
   `record-name`. Empty records return `true` (all zero fields agree)."
  [record-name info ctx]
  (let [fields   (:fields info)
        body-src (if (empty? fields)
                   "true"
                   (str/join "\n           && "
                             (map #(field-equals-c-src % "a" "b" ctx) fields)))]
    {:node        :c-function
     :name        (record-synth-equals-name record-name)
     :return-type "bool"
     :params      [{:name "a" :c-type record-name}
                   {:name "b" :c-type record-name}]
     :body        [{:node :raw-c :text (str "return " body-src ";")}]}))

(defn- synthesize-record-hash-fn
  "Produce a `:c-function` node implementing structural hash for
   `record-name`. Uses 31-multiply accumulator over per-field hashes;
   empty records hash to 0."
  [record-name info ctx]
  (let [fields   (:fields info)
        lines    (map (fn [f]
                        (str "  h = h * 31 + " (field-hash-c-src f "a" ctx) ";"))
                      fields)
        body-src (str "long h = 0;\n" (str/join "\n" lines) "\n  return h;")]
    {:node        :c-function
     :name        (record-synth-hash-name record-name)
     :return-type "long"
     :params      [{:name "a" :c-type record-name}]
     :body        [{:node :raw-c :text body-src}]}))

(defn- synthesized-record-functions
  "Emit the synthesized equals + hash function pair for every record."
  [ctx]
  (vec (for [[rec-name info] (:record-env ctx)
             f-fn            [synthesize-record-equals-fn
                              synthesize-record-hash-fn]]
         (f-fn rec-name info ctx))))

(defn- lower-list-literal-init
  "Lower `xs: List of T = [e1, e2, ...]` in a data-field default.
   Produces `_xs = [[_Cxc_ListOfT alloc] initWithElements:@[<boxed items>]]`.
   Element boxing follows the element type: primitives → NSNumber
   literal (`@n`); records/atoms/enums land in later steps as
   game-toys forces the shape."
  [field element-name items ctx]
  (let [class-name (list-container-class-name element-name)
        box        (fn [item-node]
                     (let [ty (infer-type item-node ctx)]
                       (cond
                         (int-family? ty)
                         {:node :objc-num-lit :kind :int
                          :value (:value item-node)}
                         (float-family? ty)
                         {:node :objc-num-lit :kind :float
                          :value (:value item-node)}
                         ;; Atom-typed elements: the item's lowered form
                         ;; is already an id-compatible pointer (from an
                         ;; `[[T alloc] initWith...:...]` ctor call), so
                         ;; NSArray literal accepts it directly — no
                         ;; NSNumber/NSValue wrapping needed. Container's
                         ;; `-cxc_for_each:` unboxing prelude in
                         ;; `unbox-prelude-for-element` already casts the
                         ;; block's `id` param back to the atom pointer.
                         (and (map? ty) (:atom ty))
                         (lower-expr item-node ctx)
                         :else
                         (fail-nyi! (str "List of `" element-name "` literal — "
                                         "element type " (pr-str ty) " not "
                                         "boxable at Layer 5 Step 3")
                                    item-node))))
        boxed      (mapv box items)
        array-lit  {:node :objc-array-literal :items boxed}]
    {:node :assign
     :lhs  {:node :name :sym (str "_" (:name field))}
     :rhs  {:node :objc-init-with
            :class class-name
            :args  [{:name "elements" :value array-lit}]}}))

(defn- field-is-list-of?
  "Field's :cxc-ty is a `List of T` mangled class name."
  [field]
  (and (string? (:cxc-ty field))
       (.startsWith ^String (:cxc-ty field) "_Cxc_ListOf")))

(defn- field-list-element-name [field]
  (subs (:cxc-ty field) (count "_Cxc_ListOf")))

(defn- field-is-map-of? [field]
  (and (string? (:cxc-ty field))
       (.startsWith ^String (:cxc-ty field) "_Cxc_MapOf")))

(defn- field-is-set-of? [field]
  (and (string? (:cxc-ty field))
       (.startsWith ^String (:cxc-ty field) "_Cxc_SetOf")))

(defn- default-init-stmt-for [field ctx]
  (let [d (:default field)]
    (cond
      ;; List of T with a list literal default: synthesize a
      ;; -initWithElements: call instead of dropping through to
      ;; lower-expr (which doesn't know list-literal nodes).
      (and (field-is-list-of? field)
           (map? d) (= :list (:node d)))
      (lower-list-literal-init field
                               (field-list-element-name field)
                               (:items d)
                               ctx)

      ;; Layer 6 Step 8 — Map of (K, V) auto-initializes to an empty
      ;; container when no explicit default is provided (MVP has no
      ;; map-literal expression grammar). Explicit defaults surface
      ;; a NYI diagnostic — populate via `.add(k, v)` in transitions.
      (field-is-map-of? field)
      (do
        (when d
          (fail-nyi! (str "Map field `" (:name field)
                          "` with explicit default — Layer 6 Step 8 "
                          "MVP has no map-literal grammar; populate "
                          "via `<field>.add(k, v)` in transitions")
                     field))
        {:node :assign
         :lhs  {:node :name :sym (str "_" (:name field))}
         :rhs  {:node :objc-alloc-init :class (:cxc-ty field)}})

      ;; Layer 6 Step 9 — Set of T mirrors Map's auto-init shape.
      (field-is-set-of? field)
      (do
        (when d
          (fail-nyi! (str "Set field `" (:name field)
                          "` with explicit default — Layer 6 Step 9 "
                          "MVP has no set-literal grammar; populate "
                          "via `<field>.add(e)` in transitions")
                     field))
        {:node :assign
         :lhs  {:node :name :sym (str "_" (:name field))}
         :rhs  {:node :objc-alloc-init :class (:cxc-ty field)}})

      :else
      (let [rhs (if d (lower-expr d ctx) {:node :int :value 0})]
        {:node :assign
         :lhs  {:node :name :sym (str "_" (:name field))}
         :rhs  rhs}))))

(defn- lower-atom-method
  "A def-atom method becomes an ObjC instance method. `self` and each
   typed param join the locals env before the body is lowered. `self`
   isn't emitted as a formal param — ObjC provides it implicitly. Ivars
   are visible via property syntax (`self.n`) which lowers to a normal
   `.n` field access — the emitted C sees an object with a property.

   F49 Landing C: wraps the body with entry + exit `checkInvariants`
   calls per spec §3.4.2 (\"invariants hold ... before/after each
   method\"). Exit check fires after any straight-line `return <expr>`
   at the tail — the check runs on already-computed final state,
   before the return statement executes. Early returns (inside
   if/switch/loop branches) don't get exit checks in this MVP; that's
   a follow-on landing (single-exit transform or emit-time return
   rewrite). Methods without a trailing return get exit-check
   appended after the last statement."
  [atom-name method ctx]
  (let [self-ty     {:atom atom-name}
        locals      (into {}
                          (map (fn [p]
                                 [(:name p)
                                  (if (= "self" (:name p))
                                    self-ty
                                    (cxc-type->type-kw (:cxc-ty p)
                                                       (:record-env ctx)
                                                       (:atom-env ctx)
                                                       (:enum-env ctx)
                                                       method))]))
                          (:params method))
        ctx'        (assoc ctx :locals locals)
        stmts       (mapv #(lower-stmt % ctx') (:stmts (:body method)))
        args        (rest (:params method))            ; drop self
        param-names (mapv :name args)
        objc-params (mapv (fn [p]
                            {:name   (:name p)
                             :c-type (cxc-type->c {:node :type-ref :name (:cxc-ty p)}
                                                  (:record-env ctx)
                                                  (:atom-env ctx)
                                                  (:enum-env ctx))})
                          args)
        ret-c       (if (nil? (:cxc-return-ty method))
                      "void"
                      (cxc-type->c {:node :type-ref :name (:cxc-return-ty method)}
                                   (:record-env ctx) (:atom-env ctx) (:enum-env ctx)))
        check-stmt  {:node :objc-msg
                     :recv {:node :name :sym "self"}
                     :selector "checkInvariants"
                     :args []}
        ;; F39 L3 — method-return check for `-> T!`. Rewrite the
        ;; trailing `return <expr>` into a `_Cxc_ret = <expr>; guard;
        ;; return _Cxc_ret;` triple so the returned value is bound
        ;; once, checked, then returned. Only fires when the return
        ;; type node is `:type-nonnil`; other returns pass through.
        nonnil-ret? (= :type-nonnil (get-in method [:cxc-return-ty-node :node]))
        ret-check-stmts
        (fn [ret-stmt]
          (if nonnil-ret?
            [{:node :var-decl :name "_Cxc_ret" :c-type ret-c
              :rhs  (:value ret-stmt)}
             (nonnil-check-stmt "_Cxc_ret"
                                (str "def-atom `" atom-name "` method `"
                                     (:name method) "` return")
                                "return value")
             {:node :return :value {:node :name :sym "_Cxc_ret"}}]
            [ret-stmt]))
        wrapped     (cond
                      ;; Trailing `return <expr>` — insert exit check
                      ;; before it so state is verified before the
                      ;; return statement runs.
                      (and (seq stmts) (= :return (:node (last stmts))))
                      (-> [check-stmt]
                          (into (pop stmts))
                          (conj check-stmt)
                          (into (ret-check-stmts (last stmts))))
                      ;; Void body or falls-through — entry + append exit.
                      :else
                      (-> [check-stmt] (into stmts) (conj check-stmt)))]
    {:node        :objc-method
     :selector    (objc-method-selector (:name method) param-names)
     :return-type ret-c
     :params      objc-params
     :body        wrapped}))

(defn- lower-observable-method
  "An observable becomes a synthesized method:
     zero-arg   `foo :< expr`         → `-(T)foo { return <expr>; }`
     parametric `foo(a: A) :< expr`   → `-(T)fooWithA:(A)a { return <expr>; }`

   Reads self-fields via ivar direct access (matches init-body). Args
   (if any) are ObjC method params visible in the body expression.
   Only readonly — no setter is emitted. F67 L2 (Proposal G):
   parametric observables replace reader methods, so the shape must
   accept declared params in addition to the pure-read expression body."
  [o ctx atom-name]
  (let [param-locals (into {}
                           (map (fn [p]
                                  [(:name p)
                                   (cxc-type->type-kw (:name (:type p))
                                                      (:record-env ctx)
                                                      (:atom-env ctx)
                                                      (:enum-env ctx)
                                                      p)])
                                (:params o)))
        body-ctx     (update ctx :locals #(merge (or % {}) param-locals))
        objc-params  (mapv (fn [p]
                             {:name   (:name p)
                              :c-type (cxc-type->c (:type p)
                                                   (:record-env ctx)
                                                   (:atom-env ctx)
                                                   (:enum-env ctx))})
                           (:params o))]
    {:node        :objc-method
     :selector    (if (seq objc-params)
                    (objc-method-selector (:name o) (mapv :name objc-params))
                    (:name o))
     :return-type (:c-type o)
     :params      objc-params
     :body        [{:node :return :value (lower-expr (:expr o) body-ctx)}]}))

(defn- lower-invariant-check
  "One predicate → an :invariant-check init statement. Ctx must carry
   self-fields + init-locals so unqualified names resolve as in init.

   Chained comparisons (`a <= b <= c`) are desugared to
   `(a <= b) and (b <= c)` at the parser layer's
   `desugar-chained-comparisons` pass before lower ever sees them —
   no special handling here."
  [atom-name idx pred ctx]
  {:node  :invariant-check
   :cond  (lower-expr pred ctx)
   ;; Fires from init AND post-transition (Step 14); label stays generic.
   :label (str atom-name " invariant #" idx " failed")})

(defn- collect-self-field-refs
  "Walk `pred` bottom-up, returning the set of unqualified :ident names
   that would resolve to a self-field (not to a local, not to a fully-
   qualified path). Used by F49 Landing A to decide whether a predicate
   is safe to pre-store-check: if every field ref has a source value in
   the substitution map, the pre-store check emits before any field
   assignment truncates."
  [pred ctx]
  (let [acc (atom #{})]
    (walk/postwalk
     (fn [n]
       (when (and (map? n)
                  (= :ident (:node n))
                  (not (contains? (:locals ctx) (:name n)))
                  (get (:self-fields ctx) (:name n)))
         (swap! acc conj (:name n)))
       n)
     pred)
    @acc))

(defn- pre-store-invariant-check
  "F49 Landing A: emit the pre-store construction check for `pred` when
   every self-field it references has a source-value entry in
   `field-subs` (ctor-param ident or default expression). Returns nil
   if any field reference lacks a source value — those predicates fall
   back to the post-store checkInvariants call from cxc_runtimeBinding,
   which is still correct for non-narrowed storage.

   The label distinguishes pre-store from post-store so a diagnostic on
   a narrowed-field violation names the correct site."
  [atom-name idx pred ctx field-subs]
  (let [refs (collect-self-field-refs pred ctx)
        all-substituted? (every? #(contains? field-subs %) refs)]
    (when all-substituted?
      {:node  :invariant-check
       :cond  (lower-expr pred (assoc ctx :field-substitutions field-subs))
       :label (str atom-name " invariant #" idx
                   " failed at construction (source value violates)")})))

(defn- class-name-for-atom
  "Emitted ObjC class name. Regular atoms use their cxc name verbatim.
   Main gets `CxcMain` — `main` collides with C's main() symbol and
   also isn't idiomatic ObjC (class names are UpperCamel)."
  [atom-name]
  (if (= "main" atom-name) "CxcMain" atom-name))

(defn- lower-atom [atom-name ctx & {:keys [is-main?]}]
  (let [rec           (get-in ctx [:atom-env atom-name])
        extras        (:extras rec)
        ctor-params   (:ctor-params rec)
        methods       (:methods rec)
        observables   (:observables-typed rec)
        transitions   (:transitions rec)
        initial-phase (initial-phase-for transitions)
        initial-on-enter (first (filter #(and (wildcard-on-enter? %)
                                              (= initial-phase (:target %)))
                                        transitions))
        locals        (into {} (map (fn [p]
                                      [(:name p)
                                       (cxc-type->type-kw (:cxc-ty p)
                                                          (:record-env ctx)
                                                          (:atom-env ctx)
                                                          (:enum-env ctx)
                                                          p)])
                                    ctor-params))
        self-fields   (into {} (map (juxt :name identity) (:fields rec)))
        ;; Observables see other observables declared earlier — same
        ;; lookup shape as self-fields but keyed by name → :type-kw entry.
        self-plus-obs (into self-fields
                            (map (juxt :name identity) observables))
        ctx-with-self (assoc ctx :self-fields self-plus-obs)
        ;; `self` visible in on-enter bodies for `self :> foo` / `self.x`.
        ctx-in-init   (assoc ctx-with-self
                             :locals (assoc locals "self" {:atom atom-name}))]
    (when (seq extras)
      (fail-nyi! (str "def-atom `" atom-name "` has body items beyond `data:`/`methods:`"
                      "/observable/wildcard on-enter transition "
                      "(" (mapv :node extras) ") — use:/invariants/receive-glyphs next up")
                 {:atom atom-name :extras extras}))
    ;; Message-triggered transitions become instance methods (Step 4/7).
    ;; Wildcard on-enter (implicit source): initial-phase wildcards inline
    ;; into cxc_stateInit; non-initial-phase wildcards inline at each
    ;; message-triggered branch that targets that phase (Step 8). A
    ;; wildcard whose target phase is never entered is dead code — a
    ;; lint concern (Layer 5+), not a hard error here.
    (let [unhandled (remove #(or (message-triggered? %)
                                 (wildcard-on-enter? %))
                            transitions)]
      (when (seq unhandled)
        (fail-nyi! (str "def-atom `" atom-name "` has transitions Layer 4 "
                        "doesn't handle yet (sync-receive `<|` → Layer 4b): "
                        (mapv #(select-keys % [:source :receive :target]) unhandled))
                   {:atom atom-name :unhandled unhandled})))
    (let [phase-names     (collect-phase-names transitions)
          slot-names      (set (:slot-names rec))
          queue-init-stmt {:node :queue-init :ivar "_own_queue"}
          phase-init-stmt {:node :assign
                           :lhs  {:node :name :sym "_phase"}
                           :rhs  {:node :name
                                  :sym  (phase-constant-name atom-name initial-phase)}}
          ;; For nested-slot fields, install this atom as the child's
          ;; parent right after the child is constructed. The install
          ;; setter stashes weak parent + slot name on the child so
          ;; the child's -transitionToEnd can cascade back (design §7.2).
          install-parent-for-slot
          (fn [field]
            (when (contains? slot-names (:name field))
              [{:node :objc-msg
                :recv {:node :name :sym (str "_" (:name field))}
                :selector "cxc_installParent:inSlot:"
                :args [{:node :name :sym "self"}
                       ;; NSString literal, not C string — slotName is
                       ;; typed NSString * in cxc_installParent:inSlot:.
                       {:node :objc-str :value (:name field)}]}]))
          default-stmts   (vec (mapcat (fn [f]
                                         (cons (default-init-stmt-for f ctx-in-init)
                                               (install-parent-for-slot f)))
                                       (:fields rec)))
          on-enter-stmts  (if initial-on-enter
                            (lower-on-enter-body (:body initial-on-enter) ctx-in-init)
                            [])
          ;; The post-store `checkInvariants` method must lower field
          ;; refs to `_field` ivars — not to ctor-param locals. Prior
          ;; to F41 this was moot (ctor params were rare, and their
          ;; names rarely matched field names), but F41 synthesizes
          ;; ctor params from every no-default field, making the name
          ;; collision the default. Post-store lowering uses ctx WITHOUT
          ;; init-locals so bare `value` resolves to `_value` in the
          ;; emitted checkInvariants body.
          ctx-post-store  ctx-with-self
          invariant-stmts (vec (map-indexed
                                (fn [i p]
                                  (lower-invariant-check atom-name i p ctx-post-store))
                                (:invariants rec)))
          ;; F49 Landing A — pre-store construction check. Maps each
          ;; field name to an ALREADY-LOWERED expression that reads
          ;; the value which will be stored:
          ;;   - Ctor param: `{:node :name :sym <p>}` — bare-name (the
          ;;     ctor param is in scope inside cxc_stateInit).
          ;;   - Default expression: lowered once against ctx-in-init
          ;;     without field substitutions (defaults that reference
          ;;     other fields will resolve to `_field`, which reads the
          ;;     zero-init value at pre-store time — accepted MVP
          ;;     limitation; simple literal defaults dominate real use).
          ;; The already-lowered form goes straight into the emitted C
          ;; without re-entering :ident lowering, avoiding the recursion
          ;; that would happen if `hp` substituted to another
          ;; :ident-node named `hp`.
          ;; Defaults that lower-expr can safely handle at this site.
          ;; Aggregate literals (`:list`, `:map`, `:set`) use dedicated
          ;; init helpers (`default-init-stmt-for`) that don't route
          ;; through `lower-expr`; skip them from the substitution map —
          ;; the picker never narrows aggregate storage anyway, and the
          ;; post-store checkInvariants covers `xs.count`-style
          ;; invariants correctly (aggregates aren't truncatable).
          lowerable-default? (fn [d]
                               (and (map? d)
                                    (not (contains? #{:list :map :set}
                                                    (:node d)))))
          field-substitutions
          (into {}
                (concat
                 (keep (fn [f]
                         (let [d (:default f)]
                           (when (lowerable-default? d)
                             [(:name f) (lower-expr d ctx-in-init)])))
                       (:fields rec))
                 (map (fn [p] [(:name p) {:node :name :sym (:name p)}])
                      ctor-params)))
          pre-store-invariant-stmts
          (vec (keep-indexed
                (fn [i p]
                  (pre-store-invariant-check
                   atom-name i p ctx-in-init field-substitutions))
                (:invariants rec)))
          user-methods    (mapv #(lower-atom-method atom-name % ctx-with-self) methods)
          obs-methods     (mapv #(lower-observable-method % ctx-with-self atom-name) observables)
          data-props      (mapv (fn [f]
                                  ;; ARC discipline: object pointers (atom types) → strong;
                                  ;; blocks → copy (ARC needs Block_copy on retain to
                                  ;; move stack blocks to heap); NSString → copy (Cocoa
                                  ;; idiom — defensive against mutable NSStrings; canonical
                                  ;; @"..." literals no-op the copy); scalars/records → assign.
                                  ;; `strong` gives standard owning-reference semantics;
                                  ;; `assign` is right for primitives and copyable values
                                  ;; that ARC ignores.
                                  (let [is-atom-ptr? (contains? (:atom-env ctx) (:cxc-ty f))
                                        is-block?    (= "Block" (:cxc-ty f))
                                        is-string?   (= "String" (:cxc-ty f))]  ; F65
                                    {:name (:name f)
                                     :c-type (:c-type f)
                                     :attrs (cond
                                              is-block?    ["nonatomic" "copy"]
                                              is-string?   ["nonatomic" "copy"]  ; F65
                                              is-atom-ptr? ["strong"]
                                              :else        ["assign"])}))
                                (:fields rec))
          phase-prop      {:name "phase" :c-type "NSString *"
                           :attrs ["nonatomic" "copy"]}
          own-queue-prop  ;; dispatch_queue_t is a toll-free-bridged ObjC object
                          ;; under modern ARC (OS_OBJECT_USE_OBJC=1); `strong`
                          ;; keeps it alive for the atom's lifetime.
                          {:name "own_queue" :c-type "dispatch_queue_t"
                           :attrs ["nonatomic" "strong"]}
          ;; Layer 5c lifecycle: any resource whose lifetime the atom
          ;; wants to bound (dispatch timers, method-returned
          ;; CxcStreams, etc) is appended here via cxc_own_resource.
          ;; -transitionToEnd calls removeAllObjects, cascading ARC
          ;; release + each element's cleanup. Nil until first own —
          ;; atoms that never spawn anything pay one pointer of
          ;; overhead and no allocation.
          owned-res-prop  {:name "cxc_owned_resources"
                           :c-type "NSMutableArray *"
                           :attrs ["nonatomic" "strong"]}
          ;; Parent back-ref (weak → parent isn't kept alive by children;
          ;; child is removed from parent's slot via nilSlot: on end).
          ;; Slot name is copied — defensive against callers passing
          ;; mutable NSStrings; canonical `@"..."` literals no-op it.
          parent-prop     {:name "parent"           :c-type "id"
                           :attrs ["nonatomic" "weak"]}
          parent-slot-prop {:name "parent_slot_name" :c-type "NSString *"
                            :attrs ["nonatomic" "copy"]}
          ;; Three-part init factoring (design §7.4). Splits responsibility
          ;; so alternate ctors (e.g. -initFromSnapshot: in Layer 6+) can
          ;; substitute :state-init with restore logic while keeping
          ;; structural setup and runtime binding intact. Data defaults
          ;; may reference ctor-param names (`data: stored: Int = v`),
          ;; so cxc_stateInit takes the ctor-params through and threads
          ;; them from -init.
          self-msg-noargs (fn [sel] {:node :objc-msg
                                     :recv {:node :name :sym "self"}
                                     :selector sel :args []})
          param-names     (mapv :name ctor-params)
          state-selector  (objc-method-selector "cxc_stateInit" param-names)
          state-init-msg  {:node :objc-msg
                           :recv {:node :name :sym "self"}
                           :selector state-selector
                           :args (mapv (fn [p] {:node :name :sym (:name p)})
                                       ctor-params)}
          ;; Step 13 stream-source infrastructure. Every atom that
          ;; emits any `:>` gets per-stream ivars, a hashtable init in
          ;; structuralSetup, an emit method, per-stream + generic
          ;; subscribe methods. Payload types on the emit method
          ;; signature are inferred across all emit sites in this
          ;; atom (Step 13 follow-up) — see infer-stream-payload-types.
          stream-order    (:stream-order rec)
          typed-payloads  (:stream-payload-index rec)
          stream-parts    (stream-source-methods
                           (class-name-for-atom atom-name)
                           stream-order typed-payloads)
          stream-props        (:properties stream-parts)
          stream-init-stmts   (:init-stmts stream-parts)
          stream-source-mths  (:methods stream-parts)
          ;; Layer 4b: slot setters for atom-typed subscription-root
          ;; fields. Each maps to a `-set<Field>:(<Type>)newValue`
          ;; method that unsubs self from every stream on the old
          ;; source value, assigns the ivar, and subs self to every
          ;; stream on the new source value. Cocoa nil-messaging keeps
          ;; both branches safe on the nil case (start-nil or transition
          ;; -to-nil), so no explicit guards are needed.
          slot-setter-methods
          (mapv (fn [[field-name streams]]
                  (let [field-info (get-in rec [:field-index field-name])
                        c-type     (:c-type field-info)]
                    {:node        :objc-method
                     :selector    (str "set" (capitalize-first field-name) ":")
                     :return-type "void"
                     :params      [{:name "newValue" :c-type c-type}]
                     :body        [{:node    :slot-setter-body
                                    :ivar    (str "_" field-name)
                                    :streams streams}]}))
                (:subscription-map rec))
          setup-method    {:node :objc-method :selector "cxc_structuralSetup"
                           :return-type "void" :params []
                           :body (-> [queue-init-stmt phase-init-stmt]
                                     (into stream-init-stmts))}
          ;; F39 L3 — `T!` ctor-param bind check. One `if (p == nil)
          ;; cxc_nonnil_violation(...)` guard per param declared with
          ;; the non-nil contract. Runs before invariants so a nil
          ;; passed for a `T!` param logs even if invariants would also
          ;; catch it (they cover different failure modes: `T!` is a
          ;; boundary contract, invariants are a state predicate).
          nonnil-ctor-check-stmts
          (vec (keep (fn [p]
                       (when (:nonnil? p)
                         (nonnil-check-stmt
                          (:name p)
                          (str "def-atom `" atom-name "` ctor param")
                          (:name p))))
                     ctor-params))
          state-method    {:node :objc-method :selector state-selector
                           :return-type "void"
                           :params (mapv (fn [p] {:name (:name p) :c-type (:c-type p)})
                                         ctor-params)
                           ;; F49 Landing A: pre-store invariant checks
                           ;; run first so ctor-param / default values
                           ;; failing the invariant abort before any
                           ;; narrowing store loses information.
                           :body (-> []
                                     (into nonnil-ctor-check-stmts)
                                     (into pre-store-invariant-stmts)
                                     (into default-stmts)
                                     (into on-enter-stmts))}
          ;; Factor invariants into -checkInvariants (design §14) so
          ;; init AND every message-triggered transition can call it —
          ;; duplication-free, single source of truth. cxc_runtimeBinding
          ;; forwards to it so the check runs at end-of-construction.
          check-invariants-method {:node :objc-method
                                   :selector "checkInvariants"
                                   :return-type "void" :params []
                                   :body invariant-stmts}
          txn-result      (lower-receive-glyph-transitions
                           atom-name transitions ctx-with-self)
          txn-methods     (:methods txn-result)
          stream-subs     (:stream-subscriptions txn-result)
          method-subs     (:method-stream-subs txn-result)
          ;; Subscribe registrations run in cxc_runtimeBinding BEFORE
          ;; the invariant check. Timing: this atom's stateInit has
          ;; already run (so nested-slot sources like `_source` exist),
          ;; source atom's own runtimeBinding also ran (see design §7.4).
          ;; Any events source dispatches to its queue during its own
          ;; init will still be picked up because the queue targets main
          ;; and dispatch_main hasn't started yet — by the time it does,
          ;; we're subscribed.
          sub-register-stmts
          (mapv (fn [s]
                  {:node :objc-msg
                   :recv {:node :name :sym (str "_" (:source-field s))}
                   :selector (str "cxc_addSubscriberFor_" (:stream-name s) ":")
                   :args [{:node :name :sym "self"}]})
                stream-subs)
          ;; Layer 5c Step 8: method-returned stream subscription.
          ;; Call the method to get a CxcStream, then -cxc_addSubscriber:.
          ;; Both messages emit as raw C — the method-call args need
          ;; lowering against the atom's init-ctx so unqualified refs
          ;; resolve (e.g. `rate_ms` is a data-field). Wrapping in a
          ;; block scope keeps the CxcStream local; ARC retains it
          ;; through the source atom's own reference (returned stream
          ;; is held by whatever ivar/leak strategy the FFI hatch uses).
          method-sub-stmts
          (mapv (fn [s]
                  (let [method-nm (:method-name s)
                        args-lo   (mapv (fn [a] (lower-expr (:value a) ctx-in-init))
                                        (:method-args s))
                        arg-names (mapv :name (:method-args s))
                        selector  (objc-method-selector method-nm arg-names)]
                    {:node :raw-stream-subscribe
                     :field (:source-field s)
                     :selector selector
                     :args args-lo
                     :arg-names arg-names}))
                method-subs)
          bind-method     {:node :objc-method :selector "cxc_runtimeBinding"
                           :return-type "void" :params []
                           :body (-> []
                                     (into sub-register-stmts)
                                     (into method-sub-stmts)
                                     (conj (self-msg-noargs "checkInvariants")))}
          init-shell      [(self-msg-noargs "cxc_structuralSetup")
                           state-init-msg
                           (self-msg-noargs "cxc_runtimeBinding")]
          ;; -cxc_installParent:inSlot: setter. Called by the PARENT
          ;; right after constructing this atom in its cxc_stateInit,
          ;; so this atom can cascade nil back on -> end.
          install-method  {:node :objc-method
                           :selector "cxc_installParent:inSlot:"
                           :return-type "void"
                           :params [{:name "parent"    :c-type "id"}
                                    {:name "slotName"  :c-type "NSString *"}]
                           :body [{:node :assign
                                   :lhs {:node :name :sym "_parent"}
                                   :rhs {:node :name :sym "parent"}}
                                  {:node :assign
                                   :lhs {:node :name :sym "_parent_slot_name"}
                                   :rhs {:node :objc-msg
                                         :recv {:node :name :sym "slotName"}
                                         :selector "copy"
                                         :args []}}]}
          ;; -transitionToEnd (design §7.3). Idempotent; sets phase to
          ;; end constant; runs the `-> end` wildcard (if declared);
          ;; cascades end into every nested-slot child; notifies parent
          ;; (weak back-ref) to nil the slot holding self. Step 16
          ;; layers main-atom cxc_shutdown() on top.
          end-const       (phase-constant-name atom-name "end")
          end-wildcards   (get (wildcards-by-target-phase transitions) "end" [])
          ;; Audit F26: spec §1.11 line 1281 — `then:` in an `end`
          ;; transition is a compile error, including the wildcard
          ;; `-> end` form (the atom's state is about to be
          ;; discarded, so field updates are meaningless).
          _               (doseq [w end-wildcards
                                  c (:body w)
                                  :when (= :then-clause (:node c))]
                            (throw (ex-info
                                    (str "`then:` on `-> end` wildcard "
                                         "(atom `" atom-name "`) is a compile "
                                         "error — the atom's state is about to "
                                         "be discarded, so field updates are "
                                         "meaningless. See spec §1.11.")
                                    {:kind :compile-error :node w})))
          end-wildcard-then (inline-wildcard-then-stmts end-wildcards ctx-in-init)
          end-wildcard-do   (inline-wildcard-do-stmts   end-wildcards ctx-in-init)
          cascade-stmts   (mapv (fn [slot-name]
                                  {:node :end-cascade-child :slot-name slot-name})
                                (:slot-names rec))
          end-body        (-> []
                              (into end-wildcard-then)
                              (conj {:node :assign
                                     :lhs {:node :name :sym "_phase"}
                                     :rhs {:node :name :sym end-const}})
                              (into end-wildcard-do)
                              ;; F49 Landing D: pre-destruction invariant
                              ;; check per spec §3.4.2 — invariants hold
                              ;; BEFORE destruction. Runs after user
                              ;; on-end `do:` blocks (which may fix up
                              ;; state to satisfy invariants) but BEFORE
                              ;; resource clear + child cascade tear
                              ;; things down. Prior to Landing D this
                              ;; check fired last, after cascade — a
                              ;; failure at that point named a state the
                              ;; atom was no longer in.
                              (conj {:node :objc-msg
                                     :recv {:node :name :sym "self"}
                                     :selector "checkInvariants"
                                     :args []})
                              ;; Release owned dispatch/stream resources
                              ;; before cascading to children so timers
                              ;; that would otherwise fire once more mid-
                              ;; cascade stop first. Order: user do:
                              ;; blocks (may still need timers) → invariant
                              ;; check (§3.4.2 pre-destruction) → clear
                              ;; resources → cascade to children → notify
                              ;; parent.
                              (conj {:node :owned-resources-clear})
                              (into cascade-stmts)
                              (conj {:node :end-cascade-parent})
                              ;; Main atom's end shuts down the process
                              ;; (design §19). exit(0) after cascade so
                              ;; children get a chance to run their -> end
                              ;; handlers — best-effort under async cascade
                              ;; (a full sync shutdown needs `<|` in 4b).
                              (cond-> is-main?
                                (conj {:node :call
                                       :fn "cxc_shutdown" :args []})))
          ;; Idempotency guard OUTSIDE the try/catch — a re-entry from
          ;; within a raised catch (e.g. our own cascade or invariant
          ;; check throws) would loop otherwise. Design §20 loop-guard.
          end-method      {:node :objc-method
                           :selector "transitionToEnd"
                           :return-type "void" :params []
                           :body [{:node :phase-guard-eq
                                   :phase-const end-const :ret "return;"}
                                  {:node :end-catch-log-only :body end-body}]}
          ;; -nilSlot: parent-side setter. Compile-time switch on the
          ;; known slot names (`isEqualToString:` — slotName may be a
          ;; child-copied NSString, not pointer-equal to a canonical
          ;; literal).
          slot-clear-stmts
          (mapv (fn [slot-name]
                  {:node :nil-slot-branch :slot-name slot-name})
                (:slot-names rec))
          nil-slot-method {:node :objc-method
                           :selector "nilSlot:"
                           :return-type "void"
                           :params [{:name "slotName" :c-type "NSString *"}]
                           :body slot-clear-stmts}]
      {:node            :objc-class
       :name            (class-name-for-atom atom-name)
       :superclass      "NSObject"
       :phase-constants (mapv (fn [p] {:name (phase-constant-name atom-name p)
                                       :value p}) phase-names)
       :properties      (-> [phase-prop own-queue-prop owned-res-prop
                             parent-prop parent-slot-prop]
                            (into data-props)
                            (into stream-props))
       :init-params     (mapv (fn [p] {:name (:name p) :c-type (:c-type p)}) ctor-params)
       :init-body       init-shell
       :methods         (-> [setup-method state-method bind-method
                             check-invariants-method
                             install-method end-method nil-slot-method]
                            (into user-methods)
                            (into obs-methods)
                            (into txn-methods)
                            (into stream-source-mths)
                            (into slot-setter-methods))})))

(defn- main-atom-body [main-node ctx]
  ;; Audit F50: spec §1.11 does not privilege the phase name `running`
  ;; — `def main` is the entry point by naming convention, and its
  ;; initial transition may target any phase name. For the plain-main
  ;; path (no other atoms, no message txns) the target name is
  ;; effectively unused: this path emits the transition body inline
  ;; into C `main()` with no phase-state machinery. Accept any
  ;; initial transition (source implicit — the `-> X` form) rather
  ;; than requiring target == "running".
  (let [transitions (filter #(= :transition (:node %)) (:body main-node))
        initial-txn (first (filter #(= :implicit (get-in % [:source :kind]))
                                   transitions))]
    (when-not initial-txn
      (fail-nyi! "`def main` must have an initial transition (e.g. `-> running`)"
                 main-node))
    (lower-transition-body (:body initial-txn) ctx)))

(defn- main-def-atom? [node]
  (and (= :def-atom (:node node)) (= "main" (:name node))))

(defn- lower-enum
  "Top-level `enum Foo: a, b, c` → C typedef enum with `Foo_a`, `Foo_b`,
   ... as members. Case ordering fixes each case's integer value."
  [enum-node]
  {:node  :c-enum
   :name  (:name enum-node)
   :cases (:cases enum-node)})

(defn- infer-observables-in-atom-env
  "Second pass: with the atom-env already built (so cross-atom types
   resolve), infer each observable's return type in a ctx where the
   atom's own data-fields are self-fields. Observables are typed in
   declaration order so a later observable may reference earlier ones.
   Populates :observables-typed and :observable-index on each entry.

   F67 L2: parametric observables carry `:params` — each param's name
   becomes a local (its type-kw resolved from the param TypeRef) during
   inference of the body expression. Args shadow self-field names if
   they collide (transient scope; consistent with method-param
   behavior)."
  [atom-env record-env enum-env]
  (reduce-kv
   (fn [env atom-name info]
     (let [self-fields (into {} (map (juxt :name identity) (:fields info)))
           {:keys [typed]}
           (reduce (fn [{:keys [typed known]} o]
                     (let [param-locals (into {}
                                              (map (fn [p]
                                                     [(:name p)
                                                      (cxc-type->type-kw
                                                       (:name (:type p))
                                                       record-env
                                                       atom-env enum-env p)])
                                                   (:params o)))
                           ctx    {:record-env record-env :atom-env atom-env
                                   :enum-env enum-env :locals param-locals
                                   :self-fields known}
                           tk     (infer-type (:expr o) ctx)
                           typed' (assoc o :type-kw tk
                                         :c-type   (type-kw->c-type tk o))]
                       {:typed (conj typed typed')
                        :known (assoc known (:name o) typed')}))
                   {:typed [] :known self-fields}
                   (:observables info))]
       (assoc env atom-name
              (assoc info
                     :observables-typed typed
                     :observable-index  (into {} (map (juxt :name identity) typed))))))
   atom-env atom-env))

(defn- names-mentioned-in
  "Set of :ident-node names and :type-ref-node names anywhere in a
   subtree that fall in the given name-set. Single-pass walk; cheap
   enough to run over full top-nodes for prelude tree-shaking."
  [subtree name-set]
  (let [found (java.util.HashSet.)]
    (walk/postwalk
     (fn [n]
       (when (map? n)
         (let [nm (or (and (contains? #{:ident :type-ref :name} (:node n))
                           (or (:name n) (:sym n)))
                      (:name n))]
           (when (and nm (contains? name-set nm))
             (.add found nm))))
       n)
     subtree)
    (into #{} found)))

(defn- reachable-prelude-atoms
  "Which prelude atoms are actually referenced by non-prelude
   top-nodes. Non-prelude atoms are always kept (users authored them
   directly). Prelude atoms are added only when the user program
   mentions them by name, so shipping capability stubs like Clock
   doesn't pull their fanout selectors into unrelated programs.
   Names here are post-rename (see cxc.prelude/rename-table)."
  [top-nodes prelude-atom-names]
  (names-mentioned-in top-nodes prelude-atom-names))

(defn- synthesize-list-container-class
  "Emit-ready :objc-class node for one `_Cxc_ListOf<T>` instantiation.
   Contents:
     - `NSMutableArray *_elements` ivar (via a property so the setter
       isn't emitted — we access `_elements` directly)
     - `-initWithElements:(NSArray *)elements` constructor that copies
       the input NSArray into a new NSMutableArray
     - `-count` returning `(int)[_elements count]`
     - `-addWithe:` / `-removeWithe:` — mutation ops (audit F44:
       former `cxc_add:`/`cxc_remove:` selectors renamed to match
       the standard `objc-method-selector` shape so ordinary
       method-call dispatch resolves them). Under Step 6 these
       also (un)subscribe the container to each element-side
       stream so fan-in works.
     - `-cxc_for_each:` — Step 5 iteration.
     - Layer 4b stream-source machinery (per fan-in stream: subscribers
       hashtable ivar + emit method + add/remove-subscriber methods
       + generic dispatch) synthesized from the container's fan-in
       :stream-order (populated by synthesize-list-container-entries
       from the element atom's own stream-order).
     - Per-element-stream forwarding methods
       `-cxc_stream_<name>_from_<Element>...` that receive an event
       and re-emit on the container with `e` prepended to payload."
  [class-name element-name & {:keys [fanin-streams fanin-payloads]}]
  (let [;; Step 6 fan-in via Layer 4b machinery. Container is the
        ;; source of its own fan-in streams; stream-source-methods
        ;; produces the hashtable ivars + emit methods + add/remove-
        ;; subscriber methods + generic dispatch.
        stream-parts (stream-source-methods class-name
                                            (or fanin-streams [])
                                            (or fanin-payloads {}))
        ;; Per-fan-in-stream forwarding on the container side:
        ;; when an element emits `<name>`, its fanout invokes
        ;; `[container cxc_stream_<name>_from_<Element>(...)]`. The
        ;; forwarding method re-emits on the container with `e`
        ;; prepended to the payload.
        forwarding-methods
        (mapv (fn [nm]
                (let [;; Element's own emit-site payload (before fan-in
                      ;; prepends `e:`) drives the subscriber-side
                      ;; selector shape — the "from" class in the
                      ;; selector is the ELEMENT type (the container
                      ;; is subscribing to element's stream). Layer 5b
                      ;; Step 6b: the selector now carries a leading
                      ;; sender:(id) arg filled by the element's fanout
                      ;; with itself — that's exactly the element
                      ;; pointer we want to re-emit as `e:`.
                      elem-keys   (mapv :name (rest (get fanin-payloads nm [])))
                      elem-params (into [{:name "sender" :c-type "id"}]
                                        (mapv (fn [p] {:name (:name p)
                                                       :c-type (:c-type p)})
                                              (rest (get fanin-payloads nm []))))
                      ;; Container's own emit-side keys — prepends "e".
                      cont-keys   (into ["e"] elem-keys)]
                  {:node        :objc-method
                   :selector    (stream-selector element-name nm elem-keys)
                   :return-type "void"
                   :params      elem-params
                   :body        [{:node     :objc-msg
                                  :recv     {:node :name :sym "self"}
                                  :selector (stream-emit-selector nm cont-keys)
                                  :args     (into [{:node :name :sym "sender"}]
                                                  (map #(hash-map :node :name :sym %)
                                                       elem-keys))}]}))
              (or fanin-streams []))
        ;; cxc_add: normal add + register container as subscriber on
        ;; each element-side stream.
        add-body (into
                  [{:node :objc-msg
                    :recv {:node :name :sym "_elements"}
                    :selector "addObject:"
                    :args [{:node :name :sym "e"}]}]
                  (map (fn [nm]
                         {:node :objc-msg
                          :recv {:node :name :sym "e"}
                          :selector (str "cxc_addSubscriberFor_" nm ":")
                          :args [{:node :name :sym "self"}]})
                       (or fanin-streams [])))
        remove-body (into
                     (mapv (fn [nm]
                             {:node :objc-msg
                              :recv {:node :name :sym "e"}
                              :selector (str "cxc_removeSubscriberFor_" nm ":")
                              :args [{:node :name :sym "self"}]})
                           (or fanin-streams []))
                     [{:node :objc-msg
                       :recv {:node :name :sym "_elements"}
                       :selector "removeObject:"
                       :args [{:node :name :sym "e"}]}])]
    {:node        :objc-class
     :name        class-name
     :superclass  "NSObject"
     :phase-constants []
     :properties  (into [{:name  "elements"
                          :c-type "NSMutableArray *"
                          :attrs ["nonatomic" "strong"]}
                         ;; own_queue satisfies id<CXCAtom> — the
                         ;; fanout in Layer 4b calls
                         ;; `dispatch_async([sub own_queue], ...)` on
                         ;; every subscriber, so the container must
                         ;; carry a queue to be a subscriber itself
                         ;; (which it becomes when subscribed to
                         ;; element streams).
                         {:name  "own_queue"
                          :c-type "dispatch_queue_t"
                          :attrs ["nonatomic" "strong"]}]
                        (:properties stream-parts))
     :init-params [{:name "elements" :c-type "NSArray *"}]
     :init-body   (-> [{:node :assign
                        :lhs  {:node :name :sym "_elements"}
                        :rhs  {:node :objc-msg
                               :recv {:node :name :sym "NSMutableArray"}
                               :selector "arrayWithArray:"
                               :args [{:node :name :sym "elements"}]}}
                       {:node :queue-init :ivar "_own_queue"}]
                      ;; Auto-subscribe container to each element's
                      ;; fan-in stream for the initial literal load.
                      ;; Without this, elements provided via
                      ;; `xs: List of T = [a, b]` would participate
                      ;; in fan-in on the runtime `cxc_add:` path
                      ;; only — literal-init elements would be
                      ;; silently unhooked, and their emits (fired
                      ;; during their own -> running) would drop
                      ;; when main queues them post-dispatch_main.
                      (cond-> (seq (or fanin-streams []))
                        (conj {:node :raw-c
                               :text (str "for (id _cxc_ie in _elements) { "
                                          (str/join " "
                                                    (map (fn [nm]
                                                           (str "[_cxc_ie cxc_addSubscriberFor_"
                                                                nm ":self];"))
                                                         fanin-streams))
                                          " }")}))
                      (into (:init-stmts stream-parts)))
     :methods     (into [{:name        "count"
                          :selector    "count"
                          :params      []
                          :return-type "int"
                          :body        [{:node :return
                                         :value {:node :objc-cast
                                                 :c-type "int"
                                                 :expr  {:node :objc-msg
                                                         :recv {:node :name :sym "_elements"}
                                                         :selector "count"
                                                         :args []}}}]}
                         {:name        "addWithe"
                          :selector    "addWithe:"
                          :params      [{:name "e" :c-type "id"}]
                          :return-type "void"
                          :body        add-body}
                         {:name        "removeWithe"
                          :selector    "removeWithe:"
                          :params      [{:name "e" :c-type "id"}]
                          :return-type "void"
                          :body        remove-body}
                         {:name        "cxc_for_each"
                          :selector    "cxc_for_each:"
                          :params      [{:name "block" :c-type "void (^)(id)"}]
                          :return-type "void"
                          :body        [{:node :raw-c
                                         :text "for (id _cxc_e in _elements) { block(_cxc_e); }"}]}]
                        (into (vec forwarding-methods)
                              (:methods stream-parts)))
     :element-name element-name}))

;; Layer 6 Step 8 — Map container class synthesis. MVP restricts K/V
;; to Int/String — the boxing helpers below cover exactly those. K/V
;; outside the supported set surfaces fail-nyi at the atom-env
;; registration site (map-instantiations-from-atom-env → synthesize-
;; map-container-class construction).

(def ^:private map-supported-key-types #{"String" "Int"})
(def ^:private map-supported-val-types #{"String" "Int"})

(defn- map-box-expr
  "Cxc-typed expr → NSDictionary-safe ObjC expr. Int wraps as NSNumber
   `@(v)`; String is ALREADY NSString * per F65 §3.10.5, no wrapping
   needed."
  [ty raw-name]
  (case ty
    "Int"    (str "@(" raw-name ")")
    "String" raw-name))

(defn- map-c-type
  "C-level parameter type for a supported K or V map type."
  [ty]
  (case ty
    "Int"    "int"
    "String" "NSString *"))                 ; F65 §3.10.5

(defn- synthesize-map-container-class
  "Emit-ready :objc-class node for one `_Cxc_MapOf<K><V>` instantiation.
   Contents:
     - `NSMutableDictionary *_elements` ivar (allocated empty at init)
     - `-count` observable
     - `-addWithk:v:` — box K/V, `[_elements setObject:box(v) forKey:box(k)]`
     - `-removeWithk:` — box K, `[_elements removeObjectForKey:box(k)]`
     - `-containsWithk:` — box K, returns 1/0
   No fan-in machinery at Step 8 MVP (deferred until value atoms need
   observable aggregation; parallels List's Step 3 → Step 6 arc)."
  [class-name k-name v-name]
  (when-not (contains? map-supported-key-types k-name)
    (fail-nyi! (str "Map key type `" k-name "` — Layer 6 Step 8 MVP "
                    "supports " (sort map-supported-key-types))
               {:map-key k-name}))
  (when-not (contains? map-supported-val-types v-name)
    (fail-nyi! (str "Map value type `" v-name "` — Layer 6 Step 8 MVP "
                    "supports " (sort map-supported-val-types))
               {:map-val v-name}))
  (let [k-c   (map-c-type k-name)
        v-c   (map-c-type v-name)
        box-k (map-box-expr k-name "k")
        box-v (map-box-expr v-name "v")]
    {:node        :objc-class
     :name        class-name
     :superclass  "NSObject"
     :phase-constants []
     :properties  [{:name  "elements"
                    :c-type "NSMutableDictionary *"
                    :attrs ["nonatomic" "strong"]}]
     :init-params []
     :init-body   [{:node :assign
                    :lhs  {:node :name :sym "_elements"}
                    :rhs  {:node :objc-msg
                           :recv {:node :name :sym "NSMutableDictionary"}
                           :selector "dictionary"
                           :args []}}]
     :methods     [{:name        "count"
                    :selector    "count"
                    :params      []
                    :return-type "int"
                    :body        [{:node :return
                                   :value {:node :objc-cast
                                           :c-type "int"
                                           :expr  {:node :objc-msg
                                                   :recv {:node :name :sym "_elements"}
                                                   :selector "count"
                                                   :args []}}}]}
                   {:name        "addWithk_v"
                    :selector    "addWithk:v:"
                    :params      [{:name "k" :c-type k-c}
                                  {:name "v" :c-type v-c}]
                    :return-type "void"
                    :body        [{:node :raw-c
                                   :text (str "[_elements setObject:" box-v
                                              " forKey:" box-k "];")}]}
                   {:name        "removeWithk"
                    :selector    "removeWithk:"
                    :params      [{:name "k" :c-type k-c}]
                    :return-type "void"
                    :body        [{:node :raw-c
                                   :text (str "[_elements removeObjectForKey:"
                                              box-k "];")}]}
                   {:name        "containsWithk"
                    :selector    "containsWithk:"
                    :params      [{:name "k" :c-type k-c}]
                    :return-type "int"
                    :body        [{:node :raw-c
                                   :text (str "return ([_elements objectForKey:"
                                              box-k "] != nil) ? 1 : 0;")}]}]}))

;; Layer 6 Step 9 — Set container class synthesis. MVP parallels Map:
;; element type ∈ {Int, String} only (uses the same boxing helpers as
;; Map for NSNumber/NSString conformance); no fan-in; auto-init to
;; empty when no explicit default.

(def ^:private set-supported-element-types #{"String" "Int"})

(defn- synthesize-set-container-class
  "Emit-ready :objc-class node for one `_Cxc_SetOf<T>` instantiation.
   Contents:
     - `NSMutableSet *_elements` ivar (allocated empty at init)
     - `-count` observable
     - `-addWithe:` — box element, `[_elements addObject:box(e)]`
     - `-removeWithe:` — box element, `[_elements removeObject:box(e)]`
     - `-containsWithe:` — box element, `[_elements containsObject:box(e)] ? 1 : 0`
   No fan-in machinery at Step 9 MVP (parallels Map's landing —
   deferred until element atoms with observable aggregation need it)."
  [class-name element-name]
  (when-not (contains? set-supported-element-types element-name)
    (fail-nyi! (str "Set element type `" element-name "` — Layer 6 Step 9 "
                    "MVP supports " (sort set-supported-element-types))
               {:set-element element-name}))
  (let [e-c   (map-c-type element-name)
        box-e (map-box-expr element-name "e")]
    {:node        :objc-class
     :name        class-name
     :superclass  "NSObject"
     :phase-constants []
     :properties  [{:name  "elements"
                    :c-type "NSMutableSet *"
                    :attrs ["nonatomic" "strong"]}]
     :init-params []
     :init-body   [{:node :assign
                    :lhs  {:node :name :sym "_elements"}
                    :rhs  {:node :objc-msg
                           :recv {:node :name :sym "NSMutableSet"}
                           :selector "set"
                           :args []}}]
     :methods     [{:name        "count"
                    :selector    "count"
                    :params      []
                    :return-type "int"
                    :body        [{:node :return
                                   :value {:node :objc-cast
                                           :c-type "int"
                                           :expr  {:node :objc-msg
                                                   :recv {:node :name :sym "_elements"}
                                                   :selector "count"
                                                   :args []}}}]}
                   {:name        "addWithe"
                    :selector    "addWithe:"
                    :params      [{:name "e" :c-type e-c}]
                    :return-type "void"
                    :body        [{:node :raw-c
                                   :text (str "[_elements addObject:" box-e "];")}]}
                   {:name        "removeWithe"
                    :selector    "removeWithe:"
                    :params      [{:name "e" :c-type e-c}]
                    :return-type "void"
                    :body        [{:node :raw-c
                                   :text (str "[_elements removeObject:" box-e "];")}]}
                   {:name        "containsWithe"
                    :selector    "containsWithe:"
                    :params      [{:name "e" :c-type e-c}]
                    :return-type "int"
                    :body        [{:node :raw-c
                                   :text (str "return [_elements containsObject:"
                                              box-e "] ? 1 : 0;")}]}]}))

(defn lower
  "Take parsed top-level nodes and produce an ObjC/C program AST."
  [top-nodes]
  (let [main-atom  (first (filter main-def-atom? top-nodes))
        _          (when-not main-atom
                     (throw (ex-info "no `def main` entry point found at top level"
                                     {:top top-nodes})))
        ;; F9 L2 — `kind: protocol` classes are registered in the
        ;; protocol-env (surface only; conformance validation is L5,
        ;; dispatch is L4, default-method inheritance is L6). Other
        ;; kinds (`regular`/`record`/`enum`/`section` via `class`) still
        ;; fail-nyi until L4 opens up extension composition.
        _          (when-let [c (first (filter #(and (= :class (:node %))
                                                     (not= :protocol (:kind %)))
                                                top-nodes))]
                     (fail-nyi! (str "`" (name (:kind c)) " " (:name c) "`: "
                                     "F9 L2 lands `kind: protocol` only; "
                                     "regular/record/enum classes via "
                                     "`class`/`metadata: kind:` land with "
                                     "F9 L4 (single-module composition). "
                                     "See plans/f9-class-protocol-extension.md.")
                                c))
        protocol-env (build-protocol-env top-nodes)
        ;; F31/F35 L3 — walk the |> send graph and warn on cross-atom
        ;; cycles. Best-effort compile-time detection; spec §1.11 line
        ;; 1523 keeps cycle-avoidance on the user. No runtime scaffolding
        ;; today — under Layer 4 single-main-queue a |> cycle recurses
        ;; until stack overflow (distinct failure mode). Under future
        ;; F32 concurrent queues the emitted `dispatch_sync` would
        ;; deadlock. Warning surfaces both cases before they fire.
        _          (-> top-nodes sync-send-edges warn-sync-send-cycles!)
        enum-env   (build-enum-env top-nodes)
        atom-env   (build-atom-env top-nodes enum-env)
        ;; Layer 5c Step 8: seed CxcStream as a runtime-provided atom
        ;; entry so type-refs like `Stream` (mangled to `CxcStream`)
        ;; resolve during cxc-type->c. Class synthesis skips it because
        ;; it's not in top-nodes; the runtime definition ships in
        ;; cxc.emit/cxc-runtime-helpers.
        atom-env   (assoc atom-env "CxcStream"
                          (merge (container-entry-defaults)
                                 {:kind :runtime-provided}))
        ;; Prelude tree-shaking: only keep prelude atoms that the
        ;; user program actually references. Prevents compiler errors
        ;; from fanout bodies that message subscriber-side selectors
        ;; on classes no user atom declares. The `def Clock: ...`
        ;; node itself is filtered out before scanning so it doesn't
        ;; count as a self-reference.
        non-prelude-nodes (remove #(and (= :def-atom (:node %))
                                        (contains? prelude/atom-names (:name %)))
                                  top-nodes)
        atom-env   (let [kept-prelude (reachable-prelude-atoms
                                       non-prelude-nodes prelude/atom-names)]
                     (into {} (filter (fn [[k _]]
                                        (or (not (contains? prelude/atom-names k))
                                            (contains? kept-prelude k)))
                                      atom-env)))
        record-env (build-record-env top-nodes atom-env enum-env)
        atom-env   (infer-observables-in-atom-env atom-env record-env enum-env)
        atom-env   (infer-streams-in-atom-env atom-env record-env enum-env)
        ;; Layer 5 Step 3: scan post-inference atom-env for `List of T`
        ;; field/ctor usages, register a synthesized entry per distinct
        ;; T for method-lookup, and generate a matching :objc-class per
        ;; instantiation. Runs after inference so any observable typed
        ;; as `List of T` also participates. Registration is idempotent
        ;; across the two passes: entries keyed by mangled class name
        ;; (which is a deterministic function of the element type).
        list-instantiations (list-instantiations-from-atom-env atom-env)
        list-atom-entries   (synthesize-list-container-entries
                             list-instantiations atom-env)
        atom-env            (merge atom-env list-atom-entries)
        ;; Layer 6 Step 8 — mirror of the List instantiation pass for
        ;; Map. Runs after inference so an observable typed `Map of
        ;; (K, V)` also participates (though no observable produces a
        ;; Map today; setup is future-proof).
        map-instantiations  (map-instantiations-from-atom-env atom-env)
        map-atom-entries    (synthesize-map-container-entries map-instantiations)
        atom-env            (merge atom-env map-atom-entries)
        ;; Layer 6 Step 9 — Set mirrors Map's instantiation pass.
        set-instantiations  (set-instantiations-from-atom-env atom-env)
        set-atom-entries    (synthesize-set-container-entries set-instantiations)
        atom-env            (merge atom-env set-atom-entries)
        ;; F51 Landing 1 — build the unified symbol table alongside the
        ;; ad-hoc envs. `verify-parity!` catches drift between the two
        ;; views so migration is stepwise; downstream landings will
        ;; replace `(:record-env ctx)` / `(:atom-env ctx)` /
        ;; `(:enum-env ctx)` lookups with `resolve/lookup ctx name`.
        symbol-table (resolve/build-symbol-table top-nodes)
        _            (resolve/verify-parity!
                       symbol-table
                       ;; Trim atom-env of runtime-provided + synthesized
                       ;; container entries — those aren't in top-nodes
                       ;; (they're added by the passes above) and so
                       ;; can't appear in the symbol table by design.
                       {:record-env record-env
                        :atom-env   (into {}
                                          (filter (fn [[_ v]]
                                                    (not (contains?
                                                           #{:runtime-provided
                                                             :list-container
                                                             :map-container
                                                             :set-container}
                                                           (:kind v))))
                                                  atom-env))
                        :enum-env   enum-env})
        ;; F9 L3 — collect extension blocks and validate targets against
        ;; the full symbol table. Composition (merging methods,
        ;; applying `replace`, running §3.3.2 rules 1-7) is L4.
        extension-env (build-extension-env top-nodes record-env atom-env
                                           protocol-env)
        ;; F9 L4 — compose each target's own method table with its
        ;; visible extensions, emit shadowing / replace-without-target
        ;; warnings, and rebuild record-env / atom-env with the
        ;; post-composition tables. Downstream lookups transparently
        ;; see the composed table (no API change).
        record-env (compose-record-env-with-extensions record-env extension-env)
        atom-env   (compose-atom-env-with-extensions   atom-env   extension-env)
        ;; F9 L5 — validate explicit `:conforms` declarations against
        ;; the composed method tables. Missing methods, signature
        ;; mismatches, and unknown protocols all fail-compile with
        ;; §3.4.5-pointer diagnostics. Effect-set matching is deferred
        ;; until effects are first-class.
        _          (validate-explicit-conformances! record-env atom-env
                                                    protocol-env)
        ;; F9 L6 — fold protocol defaults into each explicitly-
        ;; conforming class's composed method table. Diamond ambiguity
        ;; (multi-protocol default conflict without an override) fires
        ;; here. Structural-conformance-based default inheritance is
        ;; L6b (per-lookup detection). Runs after L5 so we know every
        ;; :conforms target resolves.
        record-env (inherit-defaults-into-env record-env protocol-env)
        atom-env   (inherit-defaults-into-env atom-env   protocol-env)
        ctx        {:record-env record-env :atom-env atom-env :enum-env enum-env
                    :symbol-table symbol-table
                    :locals {}}
        records    (filter #(= :record (:node %)) top-nodes)
        enums      (filter #(= :enum   (:node %)) top-nodes)
        c-enums    (mapv lower-enum enums)
        structs    (mapv #(lower-record-struct % ctx) records)
        functions  (into (record-method-functions ctx)
                         ;; F37 — synthesized equals + hash per record
                         (synthesized-record-functions ctx))
        ;; main-as-atom kicks in whenever the atom-env contains main
        ;; (build-atom-env puts it in iff other atoms exist).
        main-as-atom?  (contains? atom-env "main")
        ;; F50 — validate main's ctor-params for auto-provisioning.
        ;; Every ctor-param's type must resolve to an atom in atom-env
        ;; so the compiler can synthesize `[[T alloc] init]` (or
        ;; `initWith...:` with T's own declared defaults) at main
        ;; construction. Non-atom-typed params fail-compile with a
        ;; §1.13 pointer at capability provisioning. Actual construction
        ;; wiring lives in the `main-body` builder below via
        ;; `lower-atom-ctor`.
        _              (when main-as-atom?
                         (doseq [p (get-in atom-env ["main" :ctor-params])
                                 :let [ty-name   (:cxc-ty p)
                                       atom-info (get atom-env ty-name)]]
                           (when-not atom-info
                             (fail-compile!
                              :cxc-f50-non-atom-main-param
                              (str "def main param `" (:name p) ": " ty-name
                                   "` — every ctor-param on `main` must "
                                   "resolve to an atom (capability) so the "
                                   "compiler can auto-provision it at "
                                   "startup. Non-atom types have no "
                                   "provisioning story. See spec §1.13.")
                              p))))
        ;; Split atom-env into real atoms (get lower-atom) and
        ;; container synth-classes (get their own generator). Container
        ;; entries carry `:kind :list-container` and skip the queue /
        ;; phase / transition machinery lower-atom assumes.
        real-atom-names       (filter #(not (contains? #{:list-container
                                                          :map-container
                                                          :set-container
                                                          :runtime-provided}
                                                        (:kind (get atom-env %))))
                                      (keys atom-env))
        container-atom-names  (filter #(= :list-container
                                          (:kind (get atom-env %)))
                                      (keys atom-env))
        map-container-names   (filter #(= :map-container
                                          (:kind (get atom-env %)))
                                      (keys atom-env))
        set-container-names   (filter #(= :set-container
                                          (:kind (get atom-env %)))
                                      (keys atom-env))
        real-atom-classes     (mapv (fn [nm]
                                      (lower-atom nm ctx :is-main? (= "main" nm)))
                                    real-atom-names)
        list-container-classes (mapv (fn [nm]
                                       (let [entry (get atom-env nm)]
                                         (synthesize-list-container-class
                                          nm
                                          (:element-type entry)
                                          :fanin-streams  (:stream-order entry)
                                          :fanin-payloads (:stream-payload-index entry))))
                                     container-atom-names)
        map-container-classes (mapv (fn [nm]
                                      (let [entry (get atom-env nm)]
                                        (synthesize-map-container-class
                                         nm
                                         (:key-type entry)
                                         (:val-type entry))))
                                    map-container-names)
        set-container-classes (mapv (fn [nm]
                                      (let [entry (get atom-env nm)]
                                        (synthesize-set-container-class
                                         nm
                                         (:element-type entry))))
                                    set-container-names)
        classes    (-> (vec list-container-classes)
                       (into map-container-classes)
                       (into set-container-classes)
                       (into real-atom-classes))
        ;; F65 — String is NSString * per §3.10.5, so any program
        ;; emitting a string literal (`@"..."`) or calling `-UTF8String`
        ;; needs Foundation. Rather than scanning the whole AST for
        ;; those, we include Foundation whenever the program has
        ;; classes OR uses strings. Detecting the latter is broader
        ;; than needed — string literals appear in almost every program
        ;; except numeric-only smoke tests — so we simply always
        ;; include Foundation. The extra compile-time cost is a few
        ;; hundred ms on modern clang; correctness beats it here.
        needs-foundation? true
        needs-abort?      (boolean (some #(seq (:invariants (second %))) atom-env))
        ;; Runtime helpers (cxc_send_async / cxc_send_sync / cxc_shutdown
        ;; + CXCAtom protocol) are needed by every atom's compiler-inserted
        ;; -transitionToEnd (parent nil-slot cascade uses cxc_send_async
        ;; and casts to id<CXCAtom>). So the presence of any atom class
        ;; means we emit the runtime block.
        needs-runtime?    (boolean (seq classes))
        needs-panic?      (program-uses-panic? non-prelude-nodes)
        ;; dispatch_main() only when the program actually enqueues async
        ;; work (`:>` send). Programs whose only cross-atom talk is sync
        ;; (`|>` — direct call under Layer 4) complete synchronously in
        ;; the constructor and exit normally — no dispatch loop needed,
        ;; no `-> end` termination path required from the user.
        ;; dispatch_main() gated on ANY reachable atom (including kept
        ;; prelude atoms) doing an async send. non-prelude-nodes alone
        ;; would miss prelude-atom self-sends the user reaches into.
        ;; Reachable prelude atoms live in atom-env already (after
        ;; tree-shaking above); scan all their transitions.
        needs-dispatch-loop? (or (program-has-async-sends? non-prelude-nodes)
                                 (some (fn [[nm info]]
                                         (and (contains? prelude/atom-names nm)
                                              (program-has-async-sends?
                                               (:transitions info))))
                                       atom-env))
        base-includes     (if needs-foundation?
                            ["<Foundation/Foundation.h>"
                             ;; Layer 5c: objc_msgSend / sel_registerName
                             ;; used by FFI builtins that need to invoke
                             ;; arbitrary receiver-side selectors (e.g.
                             ;; Clock.every's typed-payload dispatch).
                             "<objc/message.h>"]
                            ["<stdio.h>"])
        includes          (cond-> base-includes
                            needs-abort?   (conj "<stdlib.h>")
                            needs-runtime? (conj "<stdlib.h>")
                            ;; F31/F35 L2 — panic()'s inline backtrace
                            ;; pulls in execinfo.h + stdlib.h (free()).
                            needs-panic?   (conj "<stdlib.h>" "<execinfo.h>"))
        ;; Dedup includes while preserving order.
        includes          (vec (distinct includes))
        main-body         (if main-as-atom?
                            ;; Bootstrap the Main atom. Stored in file-
                            ;; scope static (emit renders both the decl
                            ;; and the alloc/init) so it stays alive
                            ;; across dispatch_main() — the auto-release
                            ;; pool ends before dispatch_main starts;
                            ;; a local would deallocate at pool drain.
                            ;;
                            ;; F50 — when `def main(cap1: T1, cap2: T2, ...)`
                            ;; declares ctor-params, each capability atom is
                            ;; auto-constructed via `lower-atom-ctor` (with
                            ;; empty args, so T's own declared defaults
                            ;; apply) and passed as `initWith<name>:...`
                            ;; parts. Ctor-param types were validated as
                            ;; atoms upstream.
                            (let [main-ctor (get-in atom-env ["main" :ctor-params])]
                              [{:node :main-bootstrap
                                :class-name (class-name-for-atom "main")
                                :cap-args (when (seq main-ctor)
                                            (mapv (fn [p]
                                                    {:name     (:name p)
                                                     :cap-init (lower-atom-ctor
                                                                (:cxc-ty p) [] ctx)})
                                                  main-ctor))}])
                            (main-atom-body main-atom ctx))]
    {:node                :objc-program
     :includes            includes
     :needs-runtime?      needs-runtime?
     :needs-dispatch-loop? needs-dispatch-loop?
     :enums               c-enums
     :structs             structs
     :functions           functions
     :classes             classes
     ;; F9 L2 — registration-only for now (no consumer yet). Surfaced
     ;; on the lowered program so tests can assert protocol-env shape
     ;; and downstream L4+ landings can wire dispatch off a single
     ;; source of truth.
     :protocols           protocol-env
     ;; F9 L3 — target-validated `extend class` blocks, keyed by target.
     ;; L4 composition consumes this; no code-gen effect yet.
     :extensions          extension-env
     :main                {:body         (if main-as-atom?
                                           main-body   ; return 0 unreachable after dispatch_main
                                           (conj main-body
                                                 {:node :return :value {:node :int :value 0}}))
                           :autorelease? needs-foundation?}}))
