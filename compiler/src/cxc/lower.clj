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
   - Hello-world: `def main: -> running then: print(\"...\")`
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
            [cxc.named-tuple :as named-tuple]
            [cxc.prelude :as prelude]
            [cxc.resolve :as resolve]))

(defn- fail-nyi! [what node]
  (throw (ex-info (str "lowering not yet implemented: " what)
                  {:kind :not-yet-implemented :what what :node node})))

(defn- fail-compile! [kind msg node]
  (throw (ex-info msg {:kind kind :what msg :node node})))

;; ---------------------------------------------------------------------------
;; F69 L2 — Unified body-shape lookup.
;;
;; parse-def-atom stamps `:inferred-kind` on every reshaped node:
;;   :structural     — record-shaped (fields + observables only)
;;   :tagged-variant — enum-shaped   (case markers only)
;;   :agent          — class-shaped  (transitions / use: / ctor params)
;;   :protocol       — protocol-shaped (signature-only members)
;;
;; `def-kind` gives lowering-time consumers a single query into the
;; three-env world — instead of ad-hoc `(contains? record-env nm)` /
;; `(contains? atom-env nm)` chains, callers ask
;; `(def-kind ctx name)` and pattern-match on the result. Downstream
;; callers migrate opportunistically; L2 stops at the API.
;; ---------------------------------------------------------------------------

(defn def-kind
  "Return the F69 body-shape of `name` as it appears in the compiled
   world: :structural / :tagged-variant / :agent / :protocol / nil.

   Primary read path: F51 unified symbol table (`ctx[:symbol-table]`).
   Falls back to `:protocol-env` because protocols are not (yet) in
   the F51 table — F51 L1 covers records/def-atoms/enums only. When
   the symbol table absorbs protocols the fallback retires.

   `name` is a def's user-visible short name (not the mangled C name)."
  [ctx name]
  (let [entry (resolve/lookup ctx name)]
    (case (:kind entry)
      :record   :structural
      :def-atom :agent
      :enum     :tagged-variant
      (when (contains? (:protocol-env ctx) name) :protocol))))

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

(defn- optional-ret-value?
  "F39 L5b-ii-d — true iff a LOWERED expression node yields an Optional
   (`_Cxc_Optional_<T>`) at C-level. Recognizes the emit-side
   `:c-optional-*` shapes (already committed to Optional) and a `:field`
   read of an ivar backed by an Optional-storage self-field. Used at
   method-return check-insertion time; the raw-AST `infer-type` doesn't
   run on lowered forms."
  [node ctx]
  (cond
    (not (map? node)) false
    (contains? #{:c-optional-binop :c-optional-unary :c-optional-dot
                 :c-optional-chained-cmp :c-optional-nil-check
                 :c-value-nonnil-unwrap :c-print-optional
                 :c-optional-cond}
               (:node node))
    true

    ;; `_field` direct ivar read on the enclosing atom.
    (and (= :name (:node node))
         (string? (:sym node))
         (.startsWith ^String (:sym node) "_"))
    (let [nm (subs (:sym node) 1)
          f  (get (:self-fields ctx) nm)]
      (boolean (:optional? f)))

    :else false))

(defn- optional-present-check-stmt
  "F39 L5b-ii-d — runtime presence check on an Optional operand feeding
   a `T!` value-typed observation point. `expr-c` is the rendered C
   expression whose `.present` bit is inspected. On absent (present=0)
   fires `cxc_nonnil_violation(site, name)` — the same warning-grade
   runtime helper L3 uses for reference `T!` sites (spec §3.6.1 line
   3686)."
  [expr-c site name]
  {:node :raw-c
   :text (str "if (!(" expr-c ").present) cxc_nonnil_violation("
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

   F34 L1 (2026-08-18): when `atom-env` is provided, also accept
   `:call` nodes whose callee is a bare `:ident` naming an atom in
   `atom-env`. Records/atoms hold atom references through the
   spec-canonical `<name>: <TypeName> = <TypeName>()` shape (§1.11),
   so atom-type ctor calls in field defaults must be accepted. The
   exemption is narrow: only ctor-shaped calls on atom types (not
   value-typed records/enums, which have no runtime state to justify
   a ctor call).

   F16 L3 (2026-08-23): when `enum-env` is provided, also accept
   `:dot` nodes whose target is a bare `:ident` naming an enum and
   whose field is one of that enum's bare (no-payload) cases —
   `Direction.up` etc. Payload-carrying case defaults (`.ok(v: 1)`)
   would be a compound-literal constant; not accepted here yet.

   Explicitly rejected otherwise: subscripts, binops, collection
   literals, message-shapes, emit sites, blocks, ifs, switches."
  ([expr] (default-expr-const-shape? expr nil nil))
  ([expr atom-env] (default-expr-const-shape? expr atom-env nil))
  ([expr atom-env enum-env]
   (case (:node expr)
     (:lit-int :lit-float :lit-string :lit-char
      :lit-nil :lit-bool)             true
     :ident                           true
     (:neg :not)                      (default-expr-const-shape? (:expr expr) atom-env enum-env)
     :call                            (let [callee (:callee expr)]
                                        (and (some? atom-env)
                                             (map? callee)
                                             (= :ident (:node callee))
                                             (contains? atom-env (:name callee))))
     :dot                             (let [target (:target expr)
                                            fname  (:field expr)]
                                        (and (some? enum-env)
                                             (map? target)
                                             (= :ident (:node target))
                                             (let [en   (:name target)
                                                   info (get enum-env en)]
                                               (and info
                                                    (contains? (set (:cases info)) fname)
                                                    ;; bare case only — payload-case
                                                    ;; defaults lower as compound literals
                                                    ;; and aren't accepted at v1.0.
                                                    (empty? (get-in info [:payload-index fname]))))))
     false)))

(defn- validate-default-is-const!
  "Reject user-authored defaults that aren't compile-time constants
   per spec §3.8.5 rule 1. Skips params/fields whose :default is nil
   (no default) and those marked `:cxc-f41-synthesized` or
   `:cxc-compiler-synthesized` (compiler-inserted defaults for
   nested slots / use-caps aren't user surface).

   Optional `atom-env` unlocks the F34 L1 exemption for atom-type
   ctor calls; optional `enum-env` unlocks the F16 L3 exemption for
   bare enum-case access (see `default-expr-const-shape?`)."
  ([site params] (validate-default-is-const! site params nil nil))
  ([site params atom-env] (validate-default-is-const! site params atom-env nil))
  ([site params atom-env enum-env]
   (doseq [p params
           :let [d (:default p)]
           :when (and (some? d)
                      (not (:cxc-f41-synthesized p))
                      (not (:cxc-compiler-synthesized p)))]
     (when-not (default-expr-const-shape? d atom-env enum-env)
       (fail-nyi! (str "default for `" (:name p) "` on " site
                       " is not a compile-time constant (spec §3.8.5 "
                       "permits primitive literals, `nil`, unary `-`/"
                       "`not` on those, a value-alias identifier, or a"
                       " bare enum-case access `Enum.case`); "
                       "got `" (:node d) "` node")
                  {:site site :param (:name p) :default-node (:node d)})))))

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
          ctor-params (:ctor-params atom-node)
          find-in    (fn [items key-fn]
                       (some (fn [it]
                               (when (= nm (key-fn it))
                                 (base-type-name (:type it))))
                             items))
          resolved   (or (find-in ctor-params :name)
                         (find-in data-fields :name)
                         (find-in use-caps :name))]
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

(declare base-type-name optional-type-name type-kw->cxc-name-str
         optional-tk? unwrap-tk)

(defn- cxc-type->c
  "Primitives → C type name; records → own struct name (value); atoms →
   `<name> *` (pointer, already nullable in ObjC); enums → own typedef
   name (int-backed).

   Nullability (spec §3.6.1, unified rule 2026-08-20; L5a landed
   same day): bare `T` is nil-allowed at every position; `T!` is the
   non-nil contract on every shape; `T?` is BANNED for all shapes,
   fires `:cxc-f39-nullable-suffix-banned`. `T!` accepts on every
   shape and emits raw `T` (value types opt out of the Optional
   envelope; L3 runtime nonnil-check fires regardless). L5b lands
   the bare-`T` value-typed → Optional-boxed C-level flip."
  [ty-node record-env atom-env enum-env]
  (case (:node ty-node)
    :type-nullable
    ;; F39 Landing 5a (2026-08-20) — unified rule: `T?` is BANNED at
    ;; every position (reference, value, container, tuple). Bare `T`
    ;; is nil-allowed universally; `T!` is the non-nil contract.
    ;; Single diagnostic `:cxc-f39-nullable-suffix-banned` fires for
    ;; every inner shape (subsumes L1's `:cxc-f39-nullable-on-
    ;; reference` and the value-typed `fail-nyi!` that pointed at L5).
    (let [inner    (:inner ty-node)
          inner-nm (case (:node inner)
                     :type-ref (:name inner)
                     :type-of  (str (:outer inner) " of ...")
                     :type-tuple "(...)"
                     :type-named-tuple "(name: T, ...)"
                     (str "<" (name (:node inner)) ">"))]
      (fail-compile!
       :cxc-f39-nullable-suffix-banned
       (str "`" inner-nm "?` — the `?` suffix is banned universally "
            "(spec §3.6.1 unified rule 2026-08-20). Fix: use bare `"
            inner-nm "` for the nullable form (nil-allowed by default) "
            "or `" inner-nm "!` for the non-nil contract.")
       ty-node))
    :type-nonnil
    ;; F39 Landing 5a (2026-08-20) — `T!` is the non-nil contract on
    ;; every shape. Under the unified rule, bare `T` is nil-allowed
    ;; (references: nil pointer; values: Optional-boxed at C level
    ;; post-L5b), so `T!` is meaningful for value types too: it opts
    ;; OUT of the Optional envelope and lowers to raw `T`. Runtime
    ;; enforcement at the three observation points (ctor bind, then:
    ;; write, method return) fires via F39 L3 regardless of shape.
    ;; Pre-L5a diagnostic `:cxc-f39-nonnil-on-value` retired — see
    ;; plans/g3-structural-canonical/f39-nullability.md L5 companion changes.
    (let [inner (:inner ty-node)]
      (case (:node inner)
        :type-ref
        (let [nm (:name inner)]
          (cond
            (contains? atom-env nm)         (str nm " *")
            (= "String" nm)                 "NSString *"
            (contains? primitive-c-type nm) (primitive-c-type nm)
            (contains? record-env nm)       nm
            (contains? enum-env nm)         nm
            :else
            ;; Bootstrap: build-atom-env resolves ctor-param / data-
            ;; field types with record-env={} because records haven't
            ;; been built yet. If T isn't yet resolvable, treat as a
            ;; value-shaped user type — same as bare `T` fallback.
            ;; A genuine typo surfaces later at the bare `T` :type-ref
            ;; site as "unknown type".
            nm))
        (fail-nyi! (str "cxc-type->c: `!` on unknown inner " (:node inner))
                   ty-node)))
    :type-ref
    ;; F39 L5b-ii-b (2026-08-21) — `cxc-type->c` intentionally stays
    ;; raw for `:type-ref` at this landing. The Optional-boxed flip
    ;; applies to FIELD STORAGE only, done in `fields-from-data-
    ;; section` where the field's `:nonnil?` flag is in scope and the
    ;; POSITION (field vs param vs return) is unambiguous. Applying
    ;; the flip here would cascade into ctor params + method params +
    ;; return types, requiring call-site adaptation for every value-
    ;; typed argument — a much larger surface than the field-storage
    ;; landing. Params + returns get Optional-boxed in a follow-on
    ;; sub-landing that pairs with call-site wrapping (L5b-ii-b* /
    ;; L5b-ii-c).
    (let [nm (:name ty-node)]
      (cond
        (contains? primitive-c-type nm) (primitive-c-type nm)
        (contains? record-env nm)       nm
        (contains? atom-env nm)         (str nm " *")
        (contains? enum-env nm)         nm
        ;; L-panic-cap (2026-08-26) — phantom capability atom types
        ;; render as atom pointers (never actually referenced at
        ;; runtime; the capability is compile-time only).
        (contains? #{"SystemPanic" "_Cxc_SystemPanic"} nm)
        (str nm " *")
        :else                            (fail-nyi! (str "type " nm) ty-node)))
    :type-named-tuple
    ;; F15 L2.c.iii.c.α (2026-08-24) — a named-tuple param type lowers
    ;; to a reference to its canonical mangled struct name, consistent
    ;; with L2.c.ii-b's field-side convention. Dead code today because
    ;; `base-type-name` still fails-nyi on `:type-named-tuple` upstream
    ;; (in ctor-params + method-params lowering paths); reaching this
    ;; branch requires L2.c.iii.c.β to teach base-type-name too AND to
    ;; preserve the raw `:type` node on lowered params so collect-shapes
    ;; finds the shape and emit-typedefs synthesizes the typedef.
    (named-tuple/canonical-named-tuple-name ty-node)

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
   lives in.

   F15 L2.c.iii.c.β.1 (2026-08-24) — `:type-named-tuple` returns
   the canonical mangled name (D4-consistent reference), matching
   `cxc-type->c`'s L2.c.iii.c.α branch. Dead code today: the
   downstream ctor-params / method-params lowering paths don't yet
   preserve `:type` on lowered params, so `collect-shapes` doesn't
   discover the shape and the typedef never emits — reaching this
   branch would produce a mangled reference to an undefined C type.
   L2.c.iii.c.β.2 preserves `:type` so the pipeline is end-to-end."
  [ty-node]
  (case (:node ty-node)
    :type-ref      (:name ty-node)
    :type-nullable (base-type-name (:inner ty-node))
    :type-nonnil   (base-type-name (:inner ty-node))
    :type-named-tuple (named-tuple/canonical-named-tuple-name ty-node)
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
      ;; L-panic-cap (2026-08-26) — phantom capability atoms are
      ;; compiler-known types. Recognized here so type resolution
      ;; passes even when no user def exists.
      (contains? #{"SystemPanic" "_Cxc_SystemPanic"} cxc-name)
      {:atom cxc-name}
      ;; F15 L2.c.iii.c.γ.α (2026-08-24) — mangled named-tuple names
      ;; (D4-reserved `_Cxc_NT` prefix, per named-tuple/reserved-type-
      ;; name-prefix?) resolve to a `:named-tuple` type-kw without a
      ;; separate env lookup. Downstream pattern matches don't handle
      ;; `:named-tuple` yet; today's programs never reach this branch
      ;; (upstream lowering paths still fail-nyi on named-tuple ctor
      ;; params before this point in the pipeline). Prefix check
      ;; matches L2.a's reserved-prefix guarantee: the compiler owns
      ;; every `_Cxc_NT`-prefixed name, so this recognition is safe.
      (str/starts-with? cxc-name "_Cxc_NT")
      {:named-tuple cxc-name}

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
    ;; F39 L5b-ii-c — Optional propagation. `[:optional inner]` renders
    ;; as the synthesized `_Cxc_Optional_<inner-name>` typedef.
    (and (vector? tk) (= :optional (first tk)))
    (optional-type-name (type-kw->cxc-name-str (second tk)))
    (and (map? tk) (:record tk)) (:record tk)
    (and (map? tk) (:atom   tk)) (str (:atom tk) " *")
    (and (map? tk) (:enum   tk)) (:enum tk)
    ;; F15 L2.c.iii.c.γ.β (2026-08-24) — `:named-tuple` type-kw
    ;; renders as the bare mangled struct name, matching L2.c.ii-b's
    ;; field-side convention. Value type (no pointer suffix); the
    ;; typedef itself is emitted via `:named-tuple-typedefs` on the
    ;; program AST (L2.c.iii.b wire-in). Dead code today since γ.α's
    ;; recognition branch isn't reached from any real lowering path
    ;; (upstream lowering doesn't produce lowered `:cxc-ty` starting
    ;; with `_Cxc_NT` in any test program).
    (and (map? tk) (:named-tuple tk)) (:named-tuple tk)
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

(defn- validate-reference-modifier-type!
  "F13 L1 — a `weak`/`unowned` field must reference an atom (or
   `String`, the sole reference-typed value). Records and enums are
   value types; primitives were already rejected at parse time.
   env context lets us distinguish record/atom/enum by looking up
   the type name against each env."
  [field record-env atom-env enum-env]
  (when-let [modifier (:modifier field)]
    (let [ty-nm    (base-type-name (:type field))
          modifier-nm (clojure.core/name modifier)
          in-record? (contains? record-env ty-nm)
          in-enum?   (contains? enum-env ty-nm)
          in-atom?   (contains? atom-env ty-nm)
          string?    (contains? #{"String" "_Cxc_String"} ty-nm)]
      (cond
        in-record?
        (fail-nyi!
         (str "F13 L1: `" modifier-nm " " ty-nm "` on field `"
              (:name field) "` — reference modifiers apply to reference "
              "types (atoms, `String`); `" ty-nm "` is a record "
              "(value type, §3.2). Drop the modifier (spec §6.14).")
         field)

        in-enum?
        (fail-nyi!
         (str "F13 L1: `" modifier-nm " " ty-nm "` on field `"
              (:name field) "` — reference modifiers apply to reference "
              "types (atoms, `String`); `" ty-nm "` is an enum "
              "(value type, §3.2). Drop the modifier (spec §6.14).")
         field)

        (not (or in-atom? string?))
        (fail-nyi!
         (str "F13 L1: `" modifier-nm " " ty-nm "` on field `"
              (:name field) "` — `" ty-nm "` is not a known reference type "
              "(atom or `String`). Reference modifiers only apply to "
              "reference types (spec §6.14).")
         field)))))

(defn- fields-from-data-section
  [data-section record-env atom-env enum-env]
  (mapv (fn [f]
          (validate-reference-modifier-type! f record-env atom-env enum-env)
          (let [ty-node (:type f)
                nonnil? (= :type-nonnil (:node ty-node))
                nullable? (= :type-nullable (:node ty-node))
                base    (base-type-name ty-node)
                scalar-c (cxc-type->c ty-node record-env atom-env enum-env)
                ;; F39 L5b-ii — value-typed bare-T fields (no `!`) box
                ;; to `_Cxc_Optional_<T>` storage. Predicate is inlined
                ;; here because `field-storage-value-typed?` operates on
                ;; a fully-built field record, and we're building it.
                value-typed? (and (string? scalar-c)
                                  (not (str/ends-with? scalar-c "*"))
                                  (not= "id" scalar-c)
                                  (not (.contains ^String scalar-c "^"))
                                  (not= "Block" base)
                                  (not= "String" base)
                                  (not= "Bytes" base)
                                  (not= "Any" base)
                                  (or (contains? primitive-c-type base)
                                      (contains? record-env base)
                                      (contains? enum-env base)))
                ;; F39 L5b-ii-c FLIPPED 2026-08-22 (Sub-P). Value-typed
                ;; bare-T fields box to `_Cxc_Optional_<T>` storage; `T!`
                ;; stays scalar. Fan-out (Sub-A through Sub-N) landed
                ;; guarded on this flag; Sub-Q iterates failures from
                ;; here (observable/method return unwrap, F49 invariant
                ;; substitution, atom-ctor param plumbing).
                optional? (and value-typed? (not nonnil?))
                c-type    (if optional? (optional-type-name base) scalar-c)]
            (merge {:name       (:name f)
                    :cxc-ty     base
                    :nullable?  nullable?
                    :nonnil?    nonnil?
                    :optional?  optional?
                    :scalar-c   scalar-c
                    :c-type     c-type
                    :default    (:default f)
                    ;; F15 L2.d.ii.α (2026-08-24) — preserve the raw
                    ;; parsed `:type` node on lowered record fields so
                    ;; L2.d.ii.β's structural-conformance check has a
                    ;; type node it can hand to `structural-conforms?`
                    ;; (which uses Clojure structural equality on the
                    ;; raw type nodes to decide field-type match). The
                    ;; existing `:cxc-ty` is a lossy string projection —
                    ;; loses nullability distinctions and can't
                    ;; distinguish `Int` from `Int!` at the same base
                    ;; name. Parallel to ε.β.β's `:type` preservation
                    ;; on ctor-params; additive field with no reader
                    ;; today, so existing tests pass without update.
                    :type       ty-node}
                   (map-key-val-of ty-node)
                   (when-let [m (:modifier f)] {:modifier m}))))
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

(defn- observable->method-entry
  "F67 L4 — convert an observable node into a method-shape env entry
   compatible with `:methods` on record/atom env slots and `:defaults`
   on protocol env slots. Observables are pure readers; the body
   becomes `[{:node :return :value <expr>}]`. `self` is prepended to
   params to match downstream expectations.

   F67 L4b — observable return type is inferred from the body expression
   during `infer-observables-in-{record,atom}-env`; callers that need it
   at conformance-check time (see `observable-methods-view`) source
   `:cxc-return-ty` from the post-inference `:type-kw` slot on the
   observable-index entry. The entry produced here carries nil
   `:cxc-return-ty` — the pre-inference view.

   L5 — this converter is intentionally for bodyful `:observable` nodes
   only. Bodyless `:observation-signature` declarations feed protocol
   accepts/witness entries through `protocol-signature->accepts-entry`."
  [owner-name obs]
  (let [self-param {:name "self" :type nil :default nil}
        with-self  (into [self-param] (:params obs))
        site       (str "observable `" owner-name "." (:name obs) "`")]
    (validate-defaults-after-required! site (:params obs))
    (validate-default-is-const!        site (:params obs))
    {:name               (:name obs)
     :params             (mapv #(normalize-method-param owner-name %) with-self)
     :cxc-return-ty      nil
     :cxc-return-ty-node nil
     ;; Body shape mirrors parse-block output: `{:node :block :stmts [...]}`
     ;; with `:return` in parser shape (:expr, not :value; :value is the
     ;; lowered form) so lower-method-fn's `(:stmts (:body method))` works
     ;; uniformly for observable-derived and legacy `methods:` entries.
     :body               {:node :block
                          :stmts [{:node :return :expr (:expr obs)}]}
     :replace?           (boolean (:replace? obs))
     :observable-derived? true}))

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

(defn- user-declared-atom?
  "True iff `nm` names a user-declared def-atom in `atom-env` (as
   opposed to a runtime-provided entry or a synthesized container
   class). Used by [[validate-no-agent-fields-in-records!]] to
   distinguish `foo: Tally` (reject) from `foo: List of Int` (allow)."
  [atom-env nm]
  (when-let [info (get atom-env nm)]
    (not (contains? #{:runtime-provided :list-container
                      :map-container :set-container}
                    (:kind info)))))

(defn- validate-no-agent-fields-in-records!
  "F38 — a structural (record) def can't hold a field whose type
   resolves to a user-declared agent. Records are value-shape; a raw
   agent-pointer field would silently alias the same instance across
   supposed value copies (assignment, ctor bit-copy). Reject with a
   promotion fix-it. Nested containers (`List of Tally`) are a
   separate design question and aren't rejected here — this pass only
   catches the direct field-of-agent shape."
  [record-env atom-env]
  (doseq [[rec-nm rec-info] record-env
          f (:fields rec-info)
          :let [ty (:cxc-ty f)]
          :when (user-declared-atom? atom-env ty)]
    (fail-compile!
     :cxc-f38-record-holds-agent-field
     (str "record `" rec-nm "` field `" (:name f) ": " ty
          "` — a structural def can't hold an agent-typed field. "
          "Records are value-shape; bit-copying `" rec-nm "` would "
          "silently alias the same `" ty "` instance across copies, "
          "breaking value semantics. Fix: promote `" rec-nm "` to an "
          "agent (add a `-> phase` initial-phase marker to turn it "
          "into a def-atom), or drop the field and message the `" ty
          "` via `|>` from wherever `" rec-nm "` is used.")
     f)))

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
             legacy-dsec (first (filter #(= :data-section    (:node %)) (:sections r)))
             top-fields  (filterv #(= :field (:node %)) (:sections r))
             ;; F67 L5 — merge legacy `data:` section with top-level
             ;; field decls. Either surface produces the same shape.
             dsec    (cond
                       (and legacy-dsec (seq top-fields))
                       {:node :data-section
                        :fields (into (or (:fields legacy-dsec) []) top-fields)}
                       legacy-dsec        legacy-dsec
                       (seq top-fields)   {:node :data-section :fields top-fields}
                       :else              nil)
             ;; F67 L4 — observables on records (Proposal G collapse).
             ;; The `methods:` section is retired; observables carry
             ;; the callable-reader surface. Extension methods still
             ;; land in `:methods` via compose-record-env-with-extensions
             ;; (extension observables convert to method-shape entries
             ;; in extension-methods-for).
             observables (filterv #(= :observable (:node %)) (:sections r))
             ;; F37 L6b (2026-08-24) — records may carry `class function`
             ;; decls (spec §3.4.6 static-fn shape; canonical use is
             ;; `class function from(source: Bytes) -> DecodeOutcome<Self>`).
             ;; Parses at v1.0 but lower/emit consumption lands with
             ;; F37 L6 — fail-nyi with a targeted pointer so the shape
             ;; isn't silently ignored on a record body.
             _ (when-let [cfs (seq (filterv #(= :class-function (:node %))
                                             (:sections r)))]
                 (fail-nyi!
                  (str "record `" name "` has `class function` decls "
                       "(" (mapv :name cfs) ") — spec §3.4.6 static-fn "
                       "shape parses at v1.0 but lower/emit consumption "
                       "lands with F37 L6 (`class function from(source: "
                       "Bytes) -> DecodeOutcome<Self>` synthesis + "
                       "`Type.name(args)` call-site dispatch). Track: "
                       "F37 L6 in compiler/design/plans/g3-structural-"
                       "canonical/f37-canonical-bytes.md.")
                  {:record name :class-functions cfs}))
             fields  (fields-from-data-section dsec env atom-env enum-env)
             site    (str "record `" name "` fields (auto-ctor)")
             _       (validate-defaults-after-required! site fields)
             _       (validate-default-is-const!       site fields atom-env enum-env)
             ;; F8 L3a follow-on — reject data-field / observable name
             ;; collisions inside one record. Silent collision would
             ;; make the observable dead code (`.field` on a value
             ;; dispatches to the field access path, never the observable
             ;; getter) — confusing for users writing `computed: n: Int
             ;; = ...` alongside `data: n: Int`. Fires at env build so
             ;; the diagnostic wins over any downstream emit.
             _       (let [field-names (set (map :name fields))
                           collisions (filter #(contains? field-names (:name %)) observables)]
                       (when-let [c (first collisions)]
                         (fail-nyi!
                          (str "F8 L3a: observable `" (:name c) "` on record `"
                               name "` collides with a data field of the same "
                               "name. The field would win at `.` dispatch and "
                               "the observable would be dead code. Rename one "
                               "or move the derivation to `computed:` under a "
                               "different name.")
                          c)))
             ;; F38 auto-detect: any observable → :class variant, else
             ;; :struct. Structural trigger, no user syntax. Base
             ;; :methods is empty; ext-composed methods bump variant
             ;; downstream if needed.
             variant (if (seq observables) :class :struct)]
         (assoc env name {:fields       fields
                          :field-index  (into {} (map (juxt :name identity) fields))
                          :methods      []
                          :method-index {}
                          :observables  observables
                          :variant      variant})))
     skeleton
     records)))

;; ---------------------------------------------------------------------------
;; F9 L2 — protocol-env: name -> {:accepts :accepts-index :conforms :defaults}
;;
;;   :accepts        vec of {:name :params :return-type} — method signatures
;;                   derived from sync/async signature-only body nodes
;;   :accepts-index  {name -> entry} for O(1) lookup + dupe detection
;;   :conforms       vec of type-nodes this protocol extends (protocol->protocol
;;                   subtyping; §3.4.5). Validated in L5.
;;   :defaults       raw methods-section entries (default implementations).
;;                   Parsed and stored here; inheritance semantics land in L6.
;;
;; Type-resolution against internal protocol entries is deferred — L2 only
;; establishes the registry. L5+ validates conforming atoms cover it.
;; ---------------------------------------------------------------------------

(defn- protocol-defs [top-nodes]
  "Return defs inferred as protocols from their signature-only body shape.
   The class/kind check preserves the parser's downstream-stable reshape;
   inferred-kind also makes this consumer tolerant of a raw signature def."
  (filter (fn [n]
            (or (= :protocol (:inferred-kind n))
                (and (= :class (:node n)) (= :protocol (:kind n)))))
          top-nodes))

(defn- protocol-signature->accepts-entry [sig]
  (case (:node sig)
    :observation-signature
    {:node :accepts-entry
     :name (:name sig)
     :params (:params sig)
     :return-type (:return-type sig)}

    :receive-signature
    {:node :accepts-entry
     :name (:name sig)
     :params (:params sig)
     :return-type nil}

    nil))

(defn- validate-unique-protocol-signatures! [proto-name entries]
  (let [names (map :name entries)
        dupes (->> names frequencies (filter #(> (val %) 1)) (map key))]
    (when (seq dupes)
      (fail-compile! :cxc-f9-duplicate-accepts
                     (str "protocol `" proto-name "` declares signature `"
                          (first dupes) "` more than once — each selector "
                          "appears at most once in a protocol's surface. "
                          "See spec §3.4.5.")
                     {:proto proto-name :dupes (vec dupes)}))))

(declare infer-type type-kw->cxc-name-str)

(defn- infer-protocol-defaults
  "F67 L4b — post-pass over protocol-env: for each default observable
   whose `:cxc-return-ty` is still nil (helper default with no matching
   protocol signature), infer the return type by walking the observable
   body expression with `infer-type` and stringify via
   `type-kw->cxc-name-str`. Runs AFTER
   `infer-observables-in-{record,atom}-env` so the ctx has real envs
   for cross-record/atom references; helper bodies are typically
   literals or arithmetic that infer cheaply."
  [protocol-env record-env atom-env enum-env]
  (reduce-kv
   (fn [acc pn info]
     (let [defaults' (mapv (fn [d]
                             (if (:cxc-return-ty d)
                               d
                               (let [expr (get-in d [:body :stmts 0 :expr])
                                     ctx  {:record-env record-env
                                           :atom-env   atom-env
                                           :enum-env   enum-env
                                           :locals     {}}
                                     tk   (try (infer-type expr ctx)
                                               (catch Exception _ nil))
                                     nm   (type-kw->cxc-name-str tk)]
                                 (cond-> d
                                   nm (assoc :cxc-return-ty nm)))))
                           (:defaults info))]
       (assoc acc pn (assoc info :defaults defaults'))))
   {}
   protocol-env))

(defn- build-protocol-env
  "Scan signature-only defs and build the protocol-env. Parser reshapes
   them to `:class :kind :protocol`, while their async/sync signature AST
   nodes remain in `:body` as the source of internal accepts/witness entries.

   F67 L4 — protocol defaults are bodyful observables inside the protocol
   body. The `methods:` section retires (Proposal G class-body collapse).
   Return types on default observables fall back to the matching sync
   signature's return type; helper defaults without a signature infer via
   `infer-protocol-defaults` post-inference."
  [top-nodes]
  (reduce
   (fn [env p]
     (let [nm       (:name p)
           signature-nodes (filterv #(contains? #{:observation-signature
                                                  :receive-signature}
                                                (:node %))
                                    (:body p))
           accepts  (mapv protocol-signature->accepts-entry signature-nodes)
           _        (validate-unique-protocol-signatures! nm accepts)
           accepts-by-name (into {} (map (juxt :name identity) accepts))
           obs-defaults (filterv #(= :observable (:node %)) (:body p))
           defaults (mapv
                     (fn [o]
                       (let [entry  (observable->method-entry nm o)
                             accept (get accepts-by-name (:name o))
                             ret    (:return-type accept)
                             ret-ty (when ret
                                      (case (:node ret)
                                        :type-ref      (:name ret)
                                        :type-of       (:outer ret)
                                        :type-nullable (base-type-name ret)
                                        :type-nonnil   (base-type-name ret)
                                        nil            nil
                                        (:name ret)))]
                         (assoc entry
                                :cxc-return-ty      ret-ty
                                :cxc-return-ty-node ret)))
                     obs-defaults)
           conforms (vec (:conforms p))]
       (assoc env nm {:accepts       accepts
                      :accepts-index accepts-by-name
                      :conforms      conforms
                      :defaults      defaults})))
   {}
   (protocol-defs top-nodes)))

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
   for `target-name`, in declaration order across all its extensions.

   F67 L4 — extensions declare methods as observables (readers) or
   wildcard self-transitions (side effect / mutation). The `methods:`
   section retires (Proposal G class-body collapse); observables here
   convert to method-shape entries so composition, shadowing, and the
   `:conforms` protocol-checking pipeline stay uniform. The observable
   also lands in `:observables` via `extension-observables-for` so
   call-site dispatch keeps its existing shape; shadow detection here
   uses the method-shape representation."
  [target-name extension-env]
  (vec
   (mapcat
    (fn [ext]
      (mapv #(observable->method-entry target-name %)
            (filterv #(= :observable (:node %)) (:body ext))))
    (get extension-env target-name []))))

(defn- extension-observables-for
  "F67 L4 — return raw observable nodes contributed by extensions of
   `target-name`, in declaration order. Composed into the target's
   `:observables` alongside its own observables so downstream
   observable emission (`record-observable-functions`) sees all
   observables uniformly."
  [target-name extension-env]
  (vec
   (mapcat
    (fn [ext]
      (filterv #(= :observable (:node %)) (:body ext)))
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
  (some #(= :proposition (:node %)) (:body ext)))

(defn- compose-record-env-with-extensions
  "Post-composition rebuild of record-env: for each record target with
   extensions, merge own + extension observables (shadow detection via
   `compose-target-methods` on the method-shape projection), union
   :conforms, and (F38) re-detect :variant since new observables may
   promote struct → class.

   F67 L4 — records only carry observables (methods retire under
   Proposal G). Shadowing between own observables and extension
   observables of the same name fires the same warnings that
   method-shadowing used to; the composed observable list becomes
   the record's `:observables` for downstream emission."
  [record-env extension-env]
  (reduce-kv
   (fn [env target rec]
     (if-let [exts (seq (get extension-env target))]
       (let [_          (doseq [e exts]
                          (when (extension-has-invariants? e)
                            (fail-nyi!
                             (str "`|:` proposition inside `extend class "
                                  target "` — record targets have no "
                                  "runtime invariant path yet. F9 L4 "
                                  "composes method tables and conforms; "
                                  "record-invariant emission is a "
                                  "follow-on sub-landing.")
                             e)))
             own-obs (:observables rec)
             ext-obs (extension-observables-for target extension-env)
             ;; Shadow detection runs on the method-shape projection.
             ;; `pick-winner-with-warnings!` emits warnings and picks
             ;; per §3.3.2 rules 1/3/5; we discard its return value here
             ;; and re-derive the observable list ourselves so we can
             ;; keep source identity (own vs ext) — the composed
             ;; method-entry projection strips :__source, and observable
             ;; call-site emission needs the source observable node.
             own-methods (mapv #(observable->method-entry target %) own-obs)
             ext-methods (extension-methods-for target extension-env)
             _           (compose-target-methods target own-methods ext-methods)
             ;; Composed observable list, per §3.3.2:
             ;;   Rule 1: own wins over unmarked extension observables of
             ;;           the same name (shadow warning fired above by
             ;;           `pick-winner-with-warnings!`).
             ;;   Rule 4: an extension observable marked `:replace? true`
             ;;           (F67 L4c prefix keyword) intentionally shadows
             ;;           own — extension wins, no warning.
             ;;   Rule 5: between competing extension observables with the
             ;;           same name and no own, later wins.
             ;; `pick-winner-with-warnings!` already fired appropriate
             ;; warnings above; we re-derive the observable list here to
             ;; keep source identity (own vs ext) intact for downstream
             ;; observable emission that needs the raw node.
             own-by-name (into {} (map (juxt :name identity) own-obs))
             ext-replace-names (set (map :name (filter :replace? ext-obs)))
             ;; Own drops out under any ext-replace on the same name.
             own-effective (filterv #(not (contains? ext-replace-names (:name %)))
                                    own-obs)
             own-eff-names (set (map :name own-effective))
             ;; For ext-obs: keep only the last-declared under each name
             ;; (rule 5). Between multiple replace-flagged extensions,
             ;; last replace wins (still deterministic).
             ext-by-name (reduce (fn [m o] (assoc m (:name o) o)) {} ext-obs)
             ext-winners (filterv (fn [o]
                                    (and (or (:replace? o)
                                             (not (contains? own-eff-names
                                                             (:name o))))
                                         (identical? o (get ext-by-name (:name o)))))
                                  ext-obs)
             composed-obs (into (vec own-effective) ext-winners)
             new-conforms (into (vec (:conforms rec))
                                (extension-conforms-for target extension-env))
             variant     (if (seq composed-obs) :class :struct)]
         (assoc env target
                (assoc rec
                       :observables   composed-obs
                       :methods       []
                       :method-index  {}
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
                                        (filter #(= :proposition (:node %))
                                                (:body e)))
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
;;   2. For every protocol signature P.foo the class must have (a) an own or
;;      extension-provided method named `foo`, or (b) a protocol default
;;      for `foo` (default-inheritance is L6 — for L5 we accept the
;;      structural presence of the default as satisfaction).
;;   3. When present on the class, the method's signature (param names,
;;      param types, return type) must match the protocol signature.
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
   `:is-self? true` by normalize-method-param) and protocol signature
   entries (users may write `foo(self, x: Int)` or the shorter
   `foo(x: Int)` since self is implied)."
  [params]
  (remove #(or (:is-self? %) (= "self" (:name %))) params))

(defn- signature-mismatch-detail
  "Return a human-readable string describing the first signature
   mismatch between a composed-table method and a protocol signature
   entry, or nil if they match.

   F67 L4b — observable-derived entries carry `:cxc-return-ty`
   populated from the inferred body type (see `observable-methods-view`);
   return-type mismatch now fires uniformly whether the entry came from
   a legacy `:method` or a post-collapse observable. Method-typed
   entries with truly-nil `:cxc-return-ty` (rare — pre-inference legacy
   paths) still short-circuit as inferred-and-matches."
  [method accepts-entry]
  (let [method-params  (non-self-params (:params method))
        accepts-params (non-self-params (:params accepts-entry))
        expected-ret   (type-node-name (:return-type accepts-entry))
        actual-ret     (:cxc-return-ty method)]
    (cond
      (not= (count method-params) (count accepts-params))
      (str "expected " (count accepts-params) " param(s), got "
           (count method-params))

      (and (some? actual-ret)
           (not= expected-ret actual-ret))
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
                                  "`'s signature declaration — " detail
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

(defn- type-kw->cxc-name-str
  "F67 L4b — reverse mapping from the internal type-kw vocabulary
   (populated by `cxc-type->type-kw` + `infer-type`) back to the
   user-facing cxc type name used by `signature-mismatch-detail`
   for string-comparison against protocol signature return types.
   Symmetric with the primitives table in `cxc-type->type-kw`. Sized
   numeric variants read the C-type metadata (which is deterministic)
   and map back to the user name (Int8/UInt32/Float etc.)."
  [tk]
  (cond
    (nil? tk)            nil
    (= :int tk)          "Int"
    (= :float tk)        "Double"
    (= :string tk)       "String"
    (= :bool tk)         "Bool"
    (= :unit tk)         "Unit"
    (= :never tk)        "Never"
    (= :any tk)          "Any"
    (= :bytes tk)        "Bytes"
    (= :block tk)        "Block"
    (and (vector? tk) (= :int (first tk)))   (str "Int"   (second tk))
    (and (vector? tk) (= :uint (first tk)))  (str "UInt"  (second tk))
    (and (vector? tk) (= :float (first tk))) (if (= 32 (second tk)) "Float" "Double")
    (and (map? tk) (:record tk)) (:record tk)
    (and (map? tk) (:atom   tk)) (:atom   tk)
    (and (map? tk) (:enum   tk)) (:enum   tk)
    :else                nil))

(defn- observable-methods-view
  "F67 L4 — for conformance checks, project a class's observables into
   method-shape entries so `validate-one-conformance!` can match them
   against protocol signatures uniformly with own + extension
   methods.

   F67 L4b — sources `:cxc-return-ty` from the post-inference
   `:observable-index` entry's `:type-kw` (populated by
   `infer-observables-in-{record,atom}-env`) so return-type mismatch
   checks fire against the class's declared observable body — no
   observable-header return-type annotation required."
  [class-name entry]
  (into (vec (:methods entry))
        (mapv (fn [obs]
                (let [me         (observable->method-entry class-name obs)
                      inferred   (get-in entry [:observable-index (:name obs)])
                      inferred-tk (:type-kw inferred)
                      name-str   (type-kw->cxc-name-str inferred-tk)]
                  (assoc me :cxc-return-ty name-str)))
              (:observables entry))))

(defn- validate-explicit-conformances!
  "Walk record-env + atom-env post-L4-composition and validate every
   `:conforms` entry against protocol-env. Throws on the first
   failure with a targeted §3.4.5 diagnostic. F67 L4: observables
   count as conformance-eligible methods."
  [record-env atom-env protocol-env]
  (doseq [[class-name entry] record-env
          proto-ty (:conforms entry)]
    (validate-one-conformance! class-name (type-node-name proto-ty)
                               (observable-methods-view class-name entry)
                               protocol-env))
  (doseq [[class-name entry] atom-env
          proto-ty (:conforms entry)]
    (validate-one-conformance! class-name (type-node-name proto-ty)
                               (observable-methods-view class-name entry)
                               protocol-env)))

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
;; detection at call sites (a class that satisfies P's signatures structurally
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
  "Given a default method-shape entry (produced by observable->method-entry
   at build-protocol-env time) and the concrete class name, re-anchor
   `self`'s :cxc-ty to `class-name` so downstream lowering treats it
   as a normal class method. F67 L4: raw `:function` default nodes are
   retired with the `methods:` section; defaults arrive here already
   in method-shape via the observable-defaults path."
  [class-name default-entry]
  (update default-entry :params
          (fn [ps]
            (mapv (fn [p]
                    (if (:is-self? p)
                      (assoc p :cxc-ty class-name)
                      p))
                  ps))))

(defn- class-satisfies-protocol-structurally?
  "L6b — true if every required signature in `proto` has a matching method
   (name + signature) in `class-methods`. Empty signature set → trivially
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
   conforms to (satisfies all required signatures) that also have at least one
   default AND are not already in the class's explicit conformance
   set. Protocols with no defaults are skipped — structural
   conformance without inheritable defaults isn't observable here
   (dispatch-only structural conformance is a call-site concern).
   O(|protocols| × |signatures| × |methods|) per class — negligible at
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
   structurally (has all required methods with matching signatures).
   Fires the diamond diagnostic on multi-candidate conflicts —
   whether the candidates came from explicit or structural paths
   doesn't change the ambiguity rule (§3.4.4 call-site error;
   detected at class-composition site here). Returns the class entry
   unchanged when no inheritance applies."
  [class-name entry protocol-env]
  ;; F67 L4 — observables count as "own methods" for the shadowing +
  ;; structural-conformance passes; project them into method-shape here.
  (let [own-method-view   (observable-methods-view class-name entry)
        own-names         (set (map :name own-method-view))
        explicit-conforms (:conforms entry)
        explicit-set      (into #{} (map type-node-name explicit-conforms))
        ;; L6b — widen with structural conformances that have defaults.
        structural-set    (structural-conformances-with-defaults
                           own-method-view explicit-set protocol-env)
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
                    "structural (F9 L6b) — check protocol signatures. "
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

(defn- def-atom-top-level-fields
  "F67 L5 — atom-body top-level `:field` nodes (replaces `data:` section
   per Proposal D). Returns them wrapped in a synthetic data-section so
   downstream `fields-from-data-section` consumes both surfaces
   uniformly during the grace window."
  [atom-node]
  (let [top-fields (filterv #(= :field (:node %)) (:body atom-node))]
    (when (seq top-fields)
      {:node :data-section :fields top-fields})))

(defn- def-atom-effective-data-section
  "Merge legacy `data:`-section fields with F67 L5 top-level fields.
   Both surfaces coexist during the migration window; either produces
   the same downstream shape."
  [atom-node]
  (let [legacy (def-atom-data-section atom-node)
        top    (def-atom-top-level-fields atom-node)]
    (cond
      (and legacy top) {:node :data-section
                        :fields (into (or (:fields legacy) [])
                                      (:fields top))}
      legacy           legacy
      top              top
      :else            nil)))

(defn- ctor-params-normalized
  "Def-atom :ctor-params come from the parser as `:field` nodes
   (`{:node :field :name :type :default}`). Normalize to
   `{:name :cxc-ty :nullable? :c-type :default :type}` for the
   atom-env.

   F15 L2.c.iii.c.β.2 (2026-08-24) — the raw parsed `:type` node is
   preserved on the lowered ctor-param. Downstream `collect-shapes`
   postwalk on the lowered program AST (`:classes` → per-class
   ctor-params → `:type`) discovers any `:type-named-tuple` shape at
   a ctor-param position and feeds it into `emit-typedefs` via the
   L2.c.iii.b wire-in. Fields not reading `:type` on lowered
   ctor-params are unaffected; today no test program has a
   named-tuple ctor-param so the extra field is dead metadata."
  [ctor-params record-env atom-env enum-env]
  (mapv (fn [p]
          (merge {:name      (:name p)
                  :cxc-ty    (base-type-name (:type p))
                  :nullable? (= :type-nullable (get-in p [:type :node]))
                  ;; F39 L2 — carry `T!` intent on ctor params too.
                  ;; Runtime binding check wires in L3.
                  :nonnil?   (= :type-nonnil (get-in p [:type :node]))
                  :c-type    (cxc-type->c (:type p) record-env atom-env enum-env)
                  :default   (:default p)
                  ;; F15 L2.c.iii.c.β.2 — preserve raw parsed :type node.
                  :type      (:type p)}
                 (map-key-val-of (:type p))))
        ctor-params))

(defn- def-atom-transitions [atom-node]
  (filterv #(= :transition (:node %)) (:body atom-node)))

(defn- def-atom-observables [atom-node]
  (filterv #(= :observable (:node %)) (:body atom-node)))

(defn- def-atom-invariants
  "Return normalized def-body proposition records (empty if absent)."
  [atom-node]
  (filterv #(= :proposition (:node %)) (:body atom-node)))

(defn- def-atom-use-caps
  "Return the capability list from the atom's use: section (empty if
   absent). Each cap is {:name :type}."
  [atom-node]
  (or (:caps (first (filter #(= :use-section (:node %)) (:body atom-node))))
      []))

(defn- def-atom-on-nil-entries
  "F39 L8 (spec §4.10.9) — return the on_nil entry list from the atom's
   `on_nil:` section (empty if absent). Each entry is `{:method :value}`
   where `:value` is a parser-produced expression subject to const-check
   at env-build time. Section is unique per atom; extras beyond the first
   are ignored (matches invariants/use-section discipline)."
  [atom-node]
  (or (:entries (first (filter #(= :on-nil-section (:node %))
                               (:body atom-node))))
      []))

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

(defn- proposition-nodes-in-transition
  "L11 — every `:proposition` node reachable from a transition body's
   clauses (guard head and then/do bodies). Kept shallow: propositions
   at the arm head or as their own statements. Deeper walks land with
   nested-scope work in L12."
  [t]
  (->> (:body t)
       (filter #(= :proposition (:node %)))))

(defn- collect-companion-shapes
  "L11 — synthesize `<msg>_failed(reason:)` companion declarations for
   every message-triggered public transition on this atom. Payload is
   the single `reason` key; `:reason-types` records the union of typed
   `|: as T` clauses plus a `:invariant-violation` sentinel for any
   bare `|:`. Runtime delivery is L12; L11 lands metadata only so
   senders can declaratively subscribe against the companion.

   Returns `{\"<msg>_failed\" {:payload [{:name \"reason\"}]
                               :reason-types [...]
                               :of-msg \"<msg>\"}}` per unique msg."
  [_atom-name transitions]
  (let [msg-txns (filter #(and (:receive %)
                               (contains? #{"<:" "<|"}
                                          (get-in % [:receive :glyph]))
                               (= :ident (get-in % [:receive :msg :source :node])))
                         transitions)]
    (reduce
     (fn [acc t]
       (let [msg-name (get-in t [:receive :msg :source :name])
             failed   (str msg-name "_failed")
             props    (proposition-nodes-in-transition t)
             typed    (keep (fn [p]
                              (when-let [et (:as-error-type p)]
                                (when (= :type-ref (:node et))
                                  {:kind :typed :type-name (:name et)})))
                            props)
             has-bare? (some #(nil? (:as-error-type %)) props)
             reasons  (cond-> (vec (distinct typed))
                        has-bare?
                        (conj {:kind :invariant-violation}))
             entry    {:payload      [{:name "reason"}]
                       :reason-types reasons
                       :of-msg       msg-name}]
         (if-let [existing (get acc failed)]
           (assoc acc failed
                  (assoc existing :reason-types
                         (vec (distinct (concat (:reason-types existing) reasons)))))
           (assoc acc failed entry))))
     {}
     msg-txns)))

(defn- collect-companion-demand
  "L12b — walk every atom's transitions to find companion subscription
   sites (`<: field.<msg>_failed(reason: T) -> ...`). Returns
   `{target-atom {companion-name #{{:subscriber X :reason-type T}}}}`.
   Feeds `augment-atom-env-with-companions` so target atoms only
   synthesize per-companion emit/subscribe scaffolding on demand,
   bounding output growth to programs that actually subscribe."
  [atom-env]
  (reduce-kv
   (fn [demand subscriber-name info]
     (reduce
      (fn [demand2 t]
        (let [glyph (get-in t [:receive :glyph])
              src   (get-in t [:receive :msg :source])]
          (if (and (contains? #{"<:" "<|"} glyph)
                   (= :dot (:node src))
                   (= :ident (get-in src [:target :node])))
            (let [field-name  (get-in src [:target :name])
                  field-info  (get-in info [:field-index field-name])
                  target-atom (some-> field-info :type base-type-name)
                  msg-name    (:field src)
                  target-info (get atom-env target-atom)]
              (if (and target-atom target-info
                       (contains? (:companion-index target-info) msg-name))
                (let [payload   (get-in t [:receive :msg :payload])
                      reason-p  (first payload)
                      reason-ty (when (and reason-p (= "reason" (:name reason-p)))
                                  (base-type-name (:type reason-p)))]
                  (update-in demand2 [target-atom msg-name] (fnil conj #{})
                             {:subscriber  subscriber-name
                              :reason-type reason-ty}))
                demand2))
            demand2)))
      demand
      (:transitions info)))
   {}
   atom-env))

(defn- augment-atom-env-with-companions
  "L12b — for each companion subscription demand, augment the target
   atom's `:stream-index` + `:stream-order` + `:stream-payload-index`
   so the existing per-stream synthesis (ivar/hashtable/emit method +
   subscribe/unsubscribe methods) picks the companion up automatically.

   Reason type comes from subscribers' declared type. All subscribers
   of a given companion must agree on the reason type; inconsistent
   declarations fail-nyi. Missing/mis-keyed subscriber payload defers
   to the receive-side validation for a cleaner diagnostic — augment
   here still fires so validation sees stream-index populated."
  [atom-env record-env enum-env]
  (let [demand (collect-companion-demand atom-env)]
    (reduce-kv
     (fn [env target msg-map]
       (reduce-kv
        (fn [env2 msg subs]
          (let [reason-tys (set (keep :reason-type subs))
                _ (when (> (count reason-tys) 1)
                    (fail-nyi!
                     (str "companion `" target "." msg
                          "` has subscribers declaring inconsistent "
                          "reason types: " reason-tys
                          "; all subscribers must agree on the reason type "
                          "(union reason types land with L12c)")
                     {:target target :companion msg}))
                reason-ty  (or (first reason-tys) "Any")
                type-kw    (cxc-type->type-kw reason-ty record-env env2
                                              enum-env {})
                c-type     (type-kw->c-type type-kw {})
                current    (get env2 target)
                stream-idx (assoc (or (:stream-index current) {}) msg
                                  {:payload [{:name "reason"}]})
                stream-ord (let [ord (or (:stream-order current) [])]
                             (if (some #(= msg %) ord)
                               ord
                               (conj ord msg)))
                stream-pay (assoc (or (:stream-payload-index current) {}) msg
                                  [{:name    "reason"
                                    :type-kw type-kw
                                    :c-type  c-type}])]
            (assoc env2 target
                   (assoc current
                          :stream-index         stream-idx
                          :stream-order         stream-ord
                          :stream-payload-index stream-pay))))
        env
        msg-map))
     atom-env
     demand)))

(defn- def-atom-handler-decls
  "F14 — `-> name(args):` handler declarations at atom-body top level.

   F67 L3 note: the legacy `-> name(args):` sugar is retired at parse
   time (see parse.clj/parse-handler-decl); this helper still exists
   because F67 L3 also folds handler-shape transitions
   (`* <: name(args) -> *`) into the same downstream method-entry
   pipeline via `transition->handler-decl`."
  [atom-node]
  (filterv #(= :handler-decl (:node %)) (:body atom-node)))

(defn- transition-is-handler-shape?
  "F67 L3 — recognize the handler-collapse form:
     `* <: name(a: T, b: U) -> * then: <body>`
   i.e. wildcard source, wildcard target, typed-params receive.

   These lower to handler-shaped method entries (spec §1265 semantics),
   subsuming the retired `-> name(args):` sugar."
  [txn]
  (and (= :wildcard (get-in txn [:source :kind]))
       (= "self"    (:target txn))
       (= "<:"      (get-in txn [:receive :glyph]))
       (some?       (get-in txn [:receive :msg :params]))))

(defn- transition->handler-decl
  "F67 L3 — convert a handler-shape transition into a synthetic
   :handler-decl node, so downstream lowering (name uniqueness checks
   in lower-def-atom, `handler-decl->method-entry`) can consume it
   with no branch.

   Body shape: handler-decls historically stored `:body` as a
   `parse-block` result `{:node :block :stmts [...]}` (downstream
   lowering pulls `(:stmts (:body method))`). Wrap the stmts the
   same way so the same accessor works uniformly.

   F70 I3 — collect stmts from BOTH `:do-clause` bodies (legacy)
   and `:then-clause` `:stmts` (I3 unified). Field updates
   (`:updates`) on a handler-shape transition remain a design
   oddity but are ignored here — handlers have no phase state to
   commit."
  [txn]
  (let [body-clauses (:body txn)
        stmts        (vec (mapcat (fn [c]
                                    (case (:node c)
                                      :do-clause   (:stmts c)
                                      :then-clause (:stmts c)
                                      nil))
                                  body-clauses))]
    {:node   :handler-decl
     :name   (get-in txn [:receive :msg :source :name])
     :params (get-in txn [:receive :msg :params])
     :body   {:node :block :stmts stmts}
     ;; Preserve source-location hints for diagnostics.
     :cxc-line (:cxc-line txn)
     :cxc-col  (:cxc-col txn)}))

(defn- def-atom-body-extras
  "Everything in the def-atom body that isn't a section lower knows
   how to consume — e.g. contracts: (Layer 6+). Deprecated: is
   handled at the build/compile step (cxc.build/warn-deprecated-uses)
   and doesn't reach lower, so it's filtered here too. Handler-decls
   are consumed separately by F14. F67 L5 top-level `:field` nodes
   are consumed by `def-atom-effective-data-section`. F37 L6b
   `:class-function` is filtered separately by `def-atom-class-
   functions` (fail-nyi'd with an F37 L6 pointer until synthesis
   lands)."
  [atom-node]
  (remove #(#{:data-section :methods-section :transition :observable
              :proposition :use-section
              :on-nil-section
              :handler-decl :deprecated :field
              :class-function} (:node %))
          (:body atom-node)))

(defn- def-atom-class-functions
  "F37 L6b (2026-08-24) — collect `:class-function` nodes from the
   def-atom body. Filtered out of `def-atom-body-extras` so the
   generic extras fail-nyi doesn't mis-diagnose them; a dedicated
   fail-nyi fires at lower-time pointing at F37 L6 (from(Bytes)
   synthesis) as the landing that will consume them."
  [atom-node]
  (filterv #(= :class-function (:node %)) (:body atom-node)))

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
                             (map :predicate invariants))
             {:keys [lo hi]} bounds
             picked  (pick-sized-atom lo hi)]
         (if picked
           ;; F39 L5b-ii-c — preserve Optional-boxing after narrowing.
           ;; A bare-T value-typed field kept `:optional?` from the
           ;; storage flip; picker narrows the INNER cxc-type (Int64 →
           ;; UInt8 etc.), and the c-type re-wraps as
           ;; `_Cxc_Optional_<narrowed>`. `:scalar-c` stays the picker's
           ;; scalar for callers that pull the underlying rep.
           (let [narrowed-cxc (:cxc-ty picked)
                 narrowed-c   (:c-type picked)
                 opt?         (:optional? f)]
             (assoc f
                    :cxc-ty  narrowed-cxc
                    :c-type  (if opt? (optional-type-name narrowed-cxc)
                                 narrowed-c)
                    :scalar-c narrowed-c
                    :picker-narrowed-from "Int"
                    :picker-bounds [lo hi]))
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
  (let [;; F13 L1 — build-record-env runs after this pass, so we can't
        ;; thread the full record-env into fields-from-data-section.
        ;; But the modifier-validation check only needs *names* to
        ;; distinguish records from atoms/enums; a lightweight
        ;; name-set built from top-nodes suffices as the `record-env`
        ;; argument.
        record-name-set (set (map :name (filter #(= :record (:node %)) top-nodes)))
        other-atoms (filter #(and (= :def-atom (:node %))
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
        ;; A use: cap on main brings that atom's lifecycle into main's
        ;; — cascade end + parent-back-ref plumbing require main to be
        ;; a real atom class. Atom-typed data fields on main are
        ;; handled by `main-has-atom-field?` below (spec-canonical
        ;; §1.11 shape); this predicate covers the `use:` surface.
        main-has-atom-slot-or-cap?
        (boolean (some #(= :use-section (:node %))
                       (:body main-node)))
        ;; F67 L5 — fields at atom body live either under `:data-section`
        ;; (legacy `data:`) or as top-level `:field` nodes (post-collapse).
        ;; Collect from both surfaces uniformly for the promote-to-class
        ;; heuristics below.
        main-fields
        (let [top (filterv #(= :field (:node %)) (:body main-node))
              sec (mapcat :fields
                          (filter #(= :data-section (:node %)) (:body main-node)))]
          (into top sec))
        ;; A List of T field on main is atom-typed (compiler-synth
        ;; container class), so its ivar needs a real class to live on.
        ;; Plain-main-body path can't hold instance state — promote to
        ;; atom class whenever main carries container-typed data fields.
        main-has-list-field?
        (boolean (some #(= :type-of (get-in % [:type :node]))
                       main-fields))
        ;; Layer 6 Step 12 — Block-typed data field needs ivar storage
        ;; (ObjC block type `void (^)(void)`), so main must be an atom
        ;; class. Distinct from list-field because Block is a :type-ref
        ;; (a "primitive" in the C mapping), not a :type-of.
        main-has-block-field?
        (boolean (some #(and (= :type-ref (get-in % [:type :node]))
                             (= "Block" (get-in % [:type :name])))
                       main-fields))
        ;; F34 L3 companion — an atom-typed `data:` field on main
        ;; requires main to be a real ObjC class so the field can live
        ;; as an ivar and transition-body identifier refs resolve to
        ;; `self.field` via `:self-fields`. Mirrors what nested-slot
        ;; declarations were previously doing under the retired
        ;; `def slot: Type` form.
        atom-name-set
        (into #{} (concat (map :name other-atoms) prelude/atom-names))
        main-has-atom-field?
        (boolean (some (fn [f]
                         (and (= :type-ref (get-in f [:type :node]))
                              (contains? atom-name-set
                                         (get-in f [:type :name]))))
                       main-fields))
        include-main? (or (seq other-atoms)
                          main-has-message-txn?
                          main-has-atom-slot-or-cap?
                          main-has-list-field?
                          main-has-block-field?
                          main-has-atom-field?)
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
             dsec          (def-atom-effective-data-section a)
             raw-txns      (def-atom-transitions a)
             ;; L10 (2026-08-25) — two wildcard-source arms on the same
             ;; message name is a static error per the ratified precedence
             ;; rule (01 doc §Transitions). Handler-shape dup is caught
             ;; later by the handler-decl name-uniqueness check; this
             ;; catches the remaining case: `* <: msg -> concrete-phase`
             ;; declared twice (or once alongside a `* <: msg -> *`),
             ;; where both would fire ambiguously.
             _             (let [wildcard-txns (filterv #(= :wildcard (get-in % [:source :kind]))
                                                        raw-txns)
                                 by-msg (group-by (fn [t]
                                                    (get-in t [:receive :msg :source :name]))
                                                  wildcard-txns)
                                 dup    (some (fn [[msg ts]] (when (and msg (> (count ts) 1)) msg))
                                              by-msg)]
                             (when dup
                               (fail-compile!
                                :cxc-l10-duplicate-wildcard-source-arm
                                (str "atom `" name "` declares two wildcard-source "
                                     "arms `* <: " dup " ... -> ...` on the same "
                                     "message; the ratified precedence rule "
                                     "(concrete-phase arm wins in its own phase; "
                                     "`*` arm fires everywhere else) rejects two "
                                     "wildcard arms on the same message as ambiguous.")
                                (first raw-txns))))
             ;; L10.b (2026-08-25) — concrete-over-`*` precedence: when a
             ;; handler-shape arm (`* <: msg(args) -> *`) shares its
             ;; message name with a concrete-source arm on the same atom,
             ;; the wildcard arm is reclassified as a phase-wildcard
             ;; transition (routed through the transition dispatch method
             ;; alongside the concrete arms). Concrete arms fire first in
             ;; their phase; the wildcard arm fires in every other phase.
             ;; Isolated handler-shape arms (no concrete overlap) still
             ;; route through the handler-decl path — that shape is a
             ;; distinct concept for side-effect-only side channels.
             concrete-msg-names
             (set (keep #(when (and (:receive %)
                                    (= "<:" (get-in % [:receive :glyph]))
                                    (= :phase (get-in % [:source :kind]))
                                    (= :ident (get-in % [:receive :msg :source :node])))
                           (get-in % [:receive :msg :source :name]))
                        raw-txns))
             overlapping-wildcard?
             (fn [t]
               (and (transition-is-handler-shape? t)
                    (contains? concrete-msg-names
                               (get-in t [:receive :msg :source :name]))))
             ;; F67 L3 — split handler-shape (`* <: msg(args) -> *`) from
             ;; real phase transitions. Handler-shape lower via the
             ;; handler-decl path (see below); real transitions keep
             ;; their existing phase-machine treatment.
             ;; L10.b — but if the handler-shape arm overlaps a concrete
             ;; arm's message name, reclassify it as a wildcard-source
             ;; transition (concrete-wins precedence).
             handler-txns  (filterv #(and (transition-is-handler-shape? %)
                                          (not (overlapping-wildcard? %)))
                                    raw-txns)
             transitions   (filterv (fn [t]
                                      (or (not (transition-is-handler-shape? t))
                                          (overlapping-wildcard? t)))
                                    raw-txns)
             observables   (def-atom-observables a)
             invariants    (def-atom-invariants a)
             use-caps      (def-atom-use-caps a)
             on-nil-raw    (def-atom-on-nil-entries a)
             extras        (def-atom-body-extras a)
             class-fns     (def-atom-class-functions a)
             ;; use: caps are sugar for a required ctor-param plus a
             ;; same-named data-field defaulted to that ctor-param.
             ;; Type must be an atom (capability atoms are the intended
             ;; kind); user-declared collisions raise below.
             ;; L-panic-cap (2026-08-26) — phantom capability atoms
             ;; are compiler-known types with no runtime state; they
             ;; license their `use:` slot but never resolve to a
             ;; user-defined atom. Recognized here so validation
             ;; passes without requiring the user to `def` them.
             phantom-caps  #{"SystemPanic" "_Cxc_SystemPanic"}
             _             (doseq [c use-caps]
                             (let [ty-nm (base-type-name (:type c))]
                               (when-not (or (contains? atom-names ty-nm)
                                             (contains? phantom-caps ty-nm))
                                 (fail-nyi!
                                  (str "use: `" (:name c) "` requires an atom type; got `"
                                       ty-nm "` (Layer 3.1 only "
                                       "supports capability atom types in use: for now)")
                                  c))))
             cap-idents     (mapv (fn [c] {:node :ident :name (:name c)}) use-caps)
             cap-as-params  (mapv (fn [c] {:name (:name c) :type (:type c) :default nil})
                                  use-caps)
             cap-as-fields  (mapv (fn [c id] {:name (:name c) :type (:type c) :default id
                                              :cxc-compiler-synthesized true})
                                  use-caps cap-idents)
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
                                (into cap-as-fields))
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
                                     "` (collision between data:/use:): "
                                     (mapv key dupes))
                                {:atom name :dupes (mapv key dupes)})))
             merged-dsec   (assoc (or dsec {:node :data-section}) :fields merged-fields)
             fields        (-> (fields-from-data-section merged-dsec record-name-set env enum-env)
                               ;; F36 Landing 2a: invariant-driven picker
                               ;; narrows Int fields whose invariants
                               ;; range-bound them to fit a sized atom.
                               ;; Safe because F49 Landing A's pre-store
                               ;; check runs on the wide source before
                               ;; the narrowing store.
                               (narrow-fields-by-invariants invariants))
             _             (let [site (str "def-atom `" name "` ctor-params")]
                             (validate-defaults-after-required! site merged-ctor)
                             (validate-default-is-const!       site merged-ctor env enum-env))
             ;; NOTE: atom `data:` field defaults are field
             ;; INITIALIZERS, not ctor-param defaults — the auto-ctor
             ;; produced by F41 uses ctor-params (which get const-
             ;; checked above), and any default on a field runs in the
             ;; field initializer, not at the ctor call site. Spec
             ;; §3.8.5 governs param defaults, so no const-check here.
             ctor-params   (ctor-params-normalized merged-ctor {} env enum-env)
             ;; F67 L4 — atom bodies no longer carry `methods:` (retired
             ;; under Proposal G class-body collapse); observables +
             ;; wildcard-self-transitions provide the callable surface.
             ;; `base-methods` remains as `[]` to preserve the merge
             ;; shape below; handler-shaped transitions still fold into
             ;; the method table.
             base-methods  []
             ;; F14 / F67 L3 — handler-shaped declarations lower as
             ;; method entries in the shared dispatch table. Two source
             ;; forms feed this pipeline:
             ;;   (a) legacy `-> name(args):` handler-decl nodes — kept
             ;;       during the F14 grace window (retirement lands in
             ;;       parse-handler-decl per F67 L3).
             ;;   (b) `* <: name(args) -> * do: ...` transitions —
             ;;       collapsed to handler-decl-shaped nodes here.
             ;; Downstream shape check (unique name; no collision with
             ;; methods:) then runs uniformly against the merged set
             ;; (spec §1265, §2160).
             handler-decls (into (def-atom-handler-decls a)
                                 (mapv transition->handler-decl handler-txns))
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
             ;; F39 L8 (spec §4.10.9) — collect on_nil entries. Store as a
             ;; method-name → raw-value-expr map so call-site lowering
             ;; picks up the fallback with full ctx (record-env / atom-env
             ;; not fully constructed here). Validation:
             ;;   (a) method name must resolve to an own method or observable
             ;;       (protocol-inherited entries validated post-L4 folding
             ;;       via a separate pass — deferred; the L8 MVP checks
             ;;       against local surface only).
             ;;   (b) value expression must be compile-time const per
             ;;       `default-expr-const-shape?`.
             ;; Duplicate entries collide with a targeted diagnostic.
             obs-names     (set (map :name observables))
             method-names* (set (map :name methods))
             ;; F8 L3a follow-on — reject data-field / observable name
             ;; collisions inside one atom. Silent collision would
             ;; make the observable dead code (`.field` on an atom
             ;; dispatches to the field/property path before the
             ;; observable lookup) — confusing for users writing
             ;; `computed: n: Int = ...` alongside `data: n: Int`.
             ;; Also blocks `then: n => ...` from firing on the
             ;; observable and hitting the L3a setter-error path.
             _             (let [field-names (set (map :name fields))
                                 collisions (filter #(contains? field-names %) obs-names)]
                             (when-let [c (first collisions)]
                               (fail-nyi!
                                (str "F8 L3a: observable `" c "` on atom `"
                                     name "` collides with a data field of the "
                                     "same name. The field would win at `.` "
                                     "dispatch and the observable would be dead "
                                     "code. Rename one or move the derivation to "
                                     "`computed:` under a different name.")
                                {:atom name :name c})))
             _             (let [dups (->> on-nil-raw
                                           (map :method)
                                           frequencies
                                           (filter (fn [[_ n]] (> n 1)))
                                           (map first))]
                             (when (seq dups)
                               (fail-compile!
                                :cxc-f39-on-nil-duplicate-entry
                                (str "atom `" name "` declares `on_nil:` entry "
                                     "`" (first dups) "` more than once — each "
                                     "method name appears at most once per "
                                     "section (spec §4.10.9).")
                                {:atom name :dupes (mapv identity dups)})))
             _             (doseq [{m :method v :value} on-nil-raw]
                             (when-not (or (contains? method-names* m)
                                           (contains? obs-names m))
                               (fail-compile!
                                :cxc-f39-on-nil-unknown-method
                                (str "atom `" name "` declares `on_nil:` entry "
                                     "for `" m "` but the atom has no method "
                                     "or observable by that name. Add the "
                                     "declaration, drop the entry, or fix a "
                                     "typo. (Protocol-inherited entries via "
                                     "`extend " name "(P):` are validated by "
                                     "a follow-on pass; this L8 MVP checks "
                                     "only own-surface names.) See spec §4.10.9.")
                                {:atom name :method m}))
                             (when-not (default-expr-const-shape? v)
                               (fail-nyi!
                                (str "on_nil entry `" m "` on atom `" name
                                     "` — value must be a compile-time "
                                     "constant (spec §3.8.5 permits primitive "
                                     "literals, `nil`, unary `-`/`not` on "
                                     "those); got `" (:node v) "` node.")
                                {:atom name :method m :value v})))
             on-nil-entries (into {} (map (juxt :method :value) on-nil-raw))
             message-index (collect-message-shapes name transitions)
             companion-index (collect-companion-shapes name transitions)
             ;; L-panic-cap (2026-08-26) — thread the raw `use:` cap
             ;; list onto atom-env so `panic(...)` at a call site can
             ;; check that the enclosing atom declared
             ;; `use panic: SystemPanic`. Cap type-names are captured
             ;; post-rename (matches the atom-env keys).
             raw-use-caps  (mapv (fn [c] {:name (:name c)
                                          :type-name (base-type-name (:type c))})
                                 use-caps)
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
             ;; Names of fields that are owned atom slots — Step 11
             ;; installs a parent back-ref on each after construction, and
             ;; -transitionToEnd cascades end into each on this atom's end.
             ;;
             ;; F31/F35 Landing 4 broadened the set to any atom-typed
             ;; `data:` field with a default (the spec-canonical §1.11
             ;; shape). A field qualifies iff its declared type resolves
             ;; to a regular-kind atom (excludes primitives, records,
             ;; enums, and synthesized container atoms). `use:` cap
             ;; fields and F41-synthesized ctor-param fields carry an
             ;; explicit `:cxc-compiler-synthesized` marker and are
             ;; excluded — caps live in the shared env, not as owned
             ;; children.
             non-slot-kinds #{:list-container :map-container :set-container
                              :runtime-provided}
             regular-atom-name?
             (fn [nm]
               (let [info (get env nm)]
                 (and info
                      (not (contains? non-slot-kinds (:kind info))))))
             ;; F13 L2 — `weak` / `unowned` fields are non-owning
             ;; references. The child atom has a different owner (or
             ;; no owner); this atom must NOT install a parent back-
             ;; ref or cascade end into it. Skip such fields when
             ;; collecting owned-slot names.
             data-slot-names
             (vec
              (keep (fn [f]
                      (let [ty-nm (base-type-name (:type f))]
                        (when (and (not (:cxc-compiler-synthesized f))
                                   (not (:cxc-f41-synthesized f))
                                   (not (:modifier f))
                                   (regular-atom-name? ty-nm))
                          (:name f))))
                    (:fields merged-dsec)))
             slot-names    (vec (distinct data-slot-names))]
         (assoc env name {:fields        fields
                          :field-index   (into {} (map (juxt :name identity) fields))
                          :ctor-params   ctor-params
                          :methods       methods
                          :method-index  (into {} (map (juxt :name identity) methods))
                          :observables   observables
                          :invariants    invariants
                          :transitions   transitions
                          :message-index message-index
                          :companion-index companion-index
                          :use-caps      raw-use-caps
                          :stream-index  stream-index
                          :stream-order  stream-order
                          :subscription-map subscription-map
                          :slot-names    slot-names
                          :on-nil-entries on-nil-entries
                          :extras        extras
                          :class-fns     class-fns})))
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
   :companion-index      {}
   :use-caps             []
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
  "Top-level `def Foo:` with case markers. Env keyed by enum name:
     :cases          — vector of case names in decl order (bare and
                       payload cases both listed; identity-only reads
                       still consult this — pre-F16-L1 behavior).
     :case-index     — name → decl-order index (used as the C integer
                       tag value in the value-only rep and as the
                       `<Enum>_Tag_<case>` value in the tagged rep).
     :case-entries   — vector of `{:name :payload}` maps, decl order.
     :payload-index  — name → payload vector (empty for bare cases).
     :tagged?        — true when at least one case has payload;
                       determines whether the enum lowers to a
                       tagged-union struct or a plain C enum (F16 L1).

   Pre-F16 the parser produced only `:cases`. Post-F16 the parser
   always populates `:case-entries`; the fallback keeps a value-only
   shape for any legacy caller that constructs the AST by hand.

   F16 L2 — raw-value enums carry `:raw-values` (parallel to `:cases`)
   and `:raw-type` (smallest fitting unsigned int name, per spec §3.3)
   on the enum node. Env exposes both for downstream emit + synthesis."
  [top-nodes]
  (into {}
        (for [e (filter #(= :enum (:node %)) top-nodes)]
          (let [entries        (or (:case-entries e)
                                    (mapv (fn [c] {:name c :payload []})
                                          (:cases e)))
                payload-index  (into {}
                                     (map (juxt :name :payload) entries))
                tagged?        (some #(seq (:payload %)) entries)
                raw-values     (:raw-values e)
                raw-type       (:raw-type e)]
            [(:name e) (cond-> {:cases         (:cases e)
                                :case-index    (into {}
                                                     (map-indexed (fn [i c] [c i])
                                                                  (:cases e)))
                                :case-entries  entries
                                :payload-index payload-index
                                :tagged?       (boolean tagged?)}
                         raw-values (assoc :raw-values raw-values)
                         raw-type   (assoc :raw-type   raw-type))]))))

;; ---------------------------------------------------------------------------
;; Type inference (ctx-threaded)
;; ---------------------------------------------------------------------------

(declare infer-type)

(defn- optional-tk?
  "True when a type-kw is [:optional inner] shape."
  [tk]
  (and (vector? tk) (= :optional (first tk))))

(defn- unwrap-tk
  "Return the inner type-kw if `tk` is [:optional inner]; else `tk` unchanged."
  [tk]
  (if (optional-tk? tk) (second tk) tk))

(defn- infer-binop-type
  [{:keys [op left right]} ctx]
  (let [lt (infer-type left  ctx)
        rt (infer-type right ctx)
        ;; F39 L5b-ii-c (Sub-B) — Optional propagation on binops.
        ;; Dead until Sub-P flips the storage flag (no expression can
        ;; infer as Optional today because no field is `:optional?`).
        ;; Rules per plan: arith → [:optional inner-result]; ord →
        ;; [:optional :bool]; eq/neq → :bool (nil-aware, two nils are
        ;; equal). Logical and/or/is stay :bool. `??` returns RHS type.
        any-opt? (or (optional-tk? lt) (optional-tk? rt))
        lt*      (unwrap-tk lt)
        rt*      (unwrap-tk rt)]
    (cond
      (#{"==" "!="} op) :bool
      (and (#{"<" "<=" ">" ">="} op) any-opt?) [:optional :bool]
      (#{"<" "<=" ">" ">="} op) :bool
      (#{"and" "or"} op) :bool
      (= "is" op) :bool
      (= "??" op) rt

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
      ;; F39 L5b-ii-c — arithmetic branches operate on the unwrapped
      ;; families (`lt*` / `rt*`); the result rewraps if either side
      ;; was Optional. Dead today (no expression infers Optional).
      (and (float-family? lt*) (float-family? rt*))
      (let [r (if (>= (or (and (vector? lt*) (second lt*)) 64)
                      (or (and (vector? rt*) (second rt*)) 64))
                lt* rt*)]
        (if any-opt? [:optional r] r))

      (and (float-family? lt*) (int-family? rt*))
      (if any-opt? [:optional lt*] lt*)

      (and (int-family? lt*) (float-family? rt*))
      (if any-opt? [:optional rt*] rt*)

      (and (int-family? lt*) (int-family? rt*))
      (let [l-fam (or (and (vector? lt*) (first lt*)) :int)
            r-fam (or (and (vector? rt*) (first rt*)) :int)]
        (cond
          (not= l-fam r-fam)
          (fail-nyi! (str "binop " op " mixes signed " lt* " with unsigned " rt*
                          " — spec §3.11 requires an explicit conversion")
                     {:left lt :right rt})
          :else
          (let [lw (int-bit-width lt*)
                rw (int-bit-width rt*)
                r  (if (>= lw rw) lt* rt*)]
            (if any-opt? [:optional r] r))))

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
      ;; F37 Landing 2 — `.as() -> Bytes` on a record with primitive-only
      ;; fields. Method isn't user-declared (there'd be an entry in
      ;; :method-index otherwise, which is checked further down); the
      ;; call intercept in lower-method-call dispatches to the
      ;; synthesized function. Type is :bytes so downstream annotations
      ;; (`let b: Bytes = p.as()`) unify cleanly.
      (and (map? target-ty) (:record target-ty)
           (= "as" method-name)
           (empty? (:args call-node))
           (nil? (get-in (:record-env ctx)
                         [(:record target-ty) :method-index "as"])))
      :bytes

      ;; F37 Landing 5 — `.as() -> Bytes` on an enum. Discriminator
      ;; width picked by case count per §3.10.10 (≤256 → UInt8,
      ;; ≤65536 → UInt16, otherwise UInt32). Payload cases append
      ;; per-field encoding after the discriminator.
      (and (map? target-ty) (:enum target-ty)
           (= "as" method-name)
           (empty? (:args call-node)))
      :bytes

      (and (map? target-ty) (:record target-ty))
      (let [rn (:record target-ty)
            m  (get-in (:record-env ctx) [rn :method-index method-name])
            o  (get-in (:record-env ctx) [rn :observable-index method-name])]
        (cond
          m (cxc-type->type-kw (:cxc-return-ty m)
                               (:record-env ctx) (:atom-env ctx) (:enum-env ctx) call-node)
          ;; F67 L4a — record observables dispatch the same as record
          ;; methods (both lower to `<Rec>_<name>(self, ...)` C
          ;; functions); type-kw was pre-computed at env build.
          o (:type-kw o)
          :else (fail-nyi! (str "method/observable `" method-name "` on record `" rn "`")
                           call-node)))

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

      ;; F37 L6a — primitive-method dispatch on `:bytes` values.
      ;; `.at(offset) -> Int` returns a widened byte; `.slice(start,
      ;; length) -> Bytes` returns a fresh NSMutableData covering the
      ;; requested range (Range grammar hasn't landed yet, so the
      ;; two-positional-arg shape is the interim MVP surface). `.bytes`
      ;; (raw pointer) has no cxc-type representation and stays
      ;; compiler-internal until F37 L6 wires it into decode synthesis.
      (and (keyword? target-ty) (= :bytes target-ty)
           (= "at" method-name))
      :int

      (and (keyword? target-ty) (= :bytes target-ty)
           (= "slice" method-name))
      :bytes

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
    ;; F67 L4b — parametric return-type node consulted by the receive-glyph
    ;; :method-stream resolver so observables wrapping this native inherit the
    ;; `Stream of T` structure without an explicit `-> T` annotation on the
    ;; observable header. Post-prelude-rename shape (Stream → CxcStream,
    ;; Instant → _Cxc_Instant); the ffi table stays in the same naming
    ;; convention as its C emission.
    :return-type-node {:node :type-of :outer "CxcStream"
                       :inner {:node :type-ref :name "_Cxc_Instant"}}
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

(defn- enum-payload-call?
  "F16 L1 — a `:call` whose callee is `<Enum>.<case>` where `<Enum>`
   is a known enum and `<case>` is one of its payload-carrying cases.
   Returns the enum-name string when matched, nil otherwise."
  [call-node ctx]
  (let [callee (:callee call-node)]
    (when (and (= :dot (:node callee))
               (= :ident (:node (:target callee))))
      (let [en (:name (:target callee))
            case-nm (:field callee)
            info (get-in ctx [:enum-env en])
            payload (get-in info [:payload-index case-nm])]
        (when (and info payload (seq payload))
          en)))))

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
    :dot   (if-let [en (enum-payload-call? node ctx)]
             {:enum en}
             (infer-method-call-type node ctx))
    (fail-nyi! (str "call with non-ident/non-dot callee " (:node callee)) node)))

(defn- assert-not-optional-guard!
  "F39 L5b-ii-c (Sub-I) — reject an Optional-typed expression in a
   boolean guard position (arm-head `|:`, dispatch `if`, `while` cond, etc).
   Nil-punning stops at control-flow: the user must terminate the
   Optional with `?? default` or `!= nil`. Dead until Sub-P flips
   storage — no expression infers Optional today."
  [guard-expr guard-tk site node]
  (when (optional-tk? guard-tk)
    (fail-compile!
     :cxc-f39-optional-in-control-flow
     (str "F39 L5b-ii-c: " site " expects a Bool but got an Optional value. "
          "Nil-punning stops at control-flow — terminate the Optional first: "
          "either `<expr> ?? <default>` to coalesce or `<expr> != nil` to test "
          "presence. See spec §3.6.1.")
     (or node guard-expr))))

(defn- wrap-field-tk
  "F39 L5b-ii-c — when the field's storage is Optional-boxed
   (`:optional? true`), wrap the raw inner type-kw in
   `[:optional inner]` so downstream inference/lowering knows the
   read yields an Optional value. Dead until Sub-P flips
   `fields-from-data-section` to set the flag; currently `:optional?`
   is always false and this returns `tk` unchanged."
  [field tk]
  (if (:optional? field) [:optional tk] tk))

(defn- field-type-for-owner [owner-ty ctx field-name node]
  (cond
    ;; F39 L5b-ii-c — dot on Optional<Record>. Inner field type wraps
    ;; in Optional (nil-punning propagation). Recurse with the inner
    ;; record type; outer wrap always yields Optional regardless of
    ;; whether the inner field was itself Optional (nil-flow).
    (and (vector? owner-ty) (= :optional (first owner-ty))
         (let [inner (second owner-ty)]
           (and (map? inner) (:record inner))))
    (let [inner-ty (second owner-ty)
          inner-tk (field-type-for-owner inner-ty ctx field-name node)]
      [:optional (unwrap-tk inner-tk)])

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

    ;; F15 L2.c.iii.c.ε.β.γ (2026-08-24) — `.field` access on a value
    ;; whose type-kw is `{:named-tuple <mangled>}`. Consult the
    ;; `:named-tuple-env` threaded into ctx (ε.β.β), look up the field's
    ;; declared type via `named-tuple/field-type`, then translate to the
    ;; ambient type-kw vocabulary via `cxc-type->type-kw`. Nullability
    ;; is expressed at the mangling level (`Nn_` prefix on non-nil
    ;; primitives), so wrap-field-tk isn't needed — the type-kw
    ;; already carries the family. Nested named-tuple field types
    ;; recurse: the nested shape's mangled name maps to
    ;; `{:named-tuple <nested-mangled>}` via cxc-type->type-kw's γ.α
    ;; prefix-recognition branch. Missing shape (mangled not in env)
    ;; or missing field (field not on shape) both fail with a targeted
    ;; diagnostic pointing at the mangled name — same discipline as
    ;; the record-side "unknown field" case below.
    (and (map? owner-ty) (:named-tuple owner-ty))
    (let [mangled (:named-tuple owner-ty)
          shape   (get (:named-tuple-env ctx) mangled)
          type-node (when shape
                      (named-tuple/field-type (:named-tuple-env ctx)
                                              mangled field-name))]
      (cond
        (nil? shape)
        (fail-nyi! (str "named-tuple shape `" mangled "` not found in "
                        ":named-tuple-env — should have been registered "
                        "during lowering via collect-shapes")
                   node)

        (nil? type-node)
        (fail-nyi! (str "field `" field-name "` not present on named "
                        "tuple `" mangled "` (available: "
                        (str/join ", " (map :name (:fields shape))) ")")
                   node)

        :else
        (cxc-type->type-kw (base-type-name type-node)
                           (:record-env ctx) (:atom-env ctx)
                           (:enum-env ctx) node)))

    (and (map? owner-ty) (:record owner-ty))
    (let [owner (:record owner-ty)
          f     (get-in (:record-env ctx) [owner :field-index field-name])
          o     (get-in (:record-env ctx) [owner :observable-index field-name])]
      (cond
        f (wrap-field-tk f
                         (cxc-type->type-kw (:cxc-ty f)
                                            (:record-env ctx) (:atom-env ctx) (:enum-env ctx) node))
        o (:type-kw o)
        :else
        (fail-nyi! (str "field/observable `" field-name "` on record `" owner "`") node)))

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
        ;; See plans/g4-actor-integrity/f33-encapsulation.md L1.
        f (wrap-field-tk f
                         (cxc-type->type-kw (:cxc-ty f)
                                            (:record-env ctx) (:atom-env ctx) (:enum-env ctx) node))
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
    :lit-bool   :bool
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
    :ident      (or
                 ;; F49 pre-store invariant substitution — when
                 ;; `:field-substitutions` maps this ident to a
                 ;; pre-lowered scalar (a ctor-param or default expr),
                 ;; the observable type is the SCALAR's inner type, NOT
                 ;; the field's Optional storage. Otherwise binop
                 ;; dispatch (Sub-C) sees Optional and emits `.value`
                 ;; access on a scalar temp. Ctor-param substitutions
                 ;; map to `{:node :name :sym p}` — look up param type
                 ;; in `:locals`.  Default substitutions are pre-lowered
                 ;; expressions — recursive infer produces the same
                 ;; scalar type since the default is scalar-source.
                 (when-let [sub (get (:field-substitutions ctx) (:name node))]
                   (cond
                     (= :name (:node sub))
                     (get (:locals ctx) (:sym sub))
                     (= :int (:node sub))    :int
                     (= :float (:node sub))  :float
                     :else                    nil))
                 (get (:locals ctx) (:name node))
                 (when-let [f (get (:self-fields ctx) (:name node))]
                   ;; F39 L5b-ii-c — self-field ident reads Optional
                   ;; storage as `[:optional inner]`. Observables carry
                   ;; :type-kw directly (already Optional-aware if
                   ;; their body reads an Optional field via Sub-A).
                   (or (:type-kw f)
                       (wrap-field-tk
                        f
                        (cxc-type->type-kw (:cxc-ty f)
                                           (:record-env ctx) (:atom-env ctx)
                                           (:enum-env ctx) node))))
                 (fail-nyi! (str "type-of identifier `" (:name node)
                                 "` (no local-binding env yet)")
                            node))
    :binop      (infer-binop-type node ctx)
    ;; F39 L5b-ii-c — chained-cmp propagates Optional when any operand
    ;; is Optional (Sub-B/F rule mirrored on the type-side).
    :chained-cmp (let [op-tks (mapv #(infer-type % ctx) (:operands node))]
                   (if (some optional-tk? op-tks) [:optional :bool] :bool))
    :neg        (let [t     (infer-type (:expr node) ctx)
                      opt?  (optional-tk? t)
                      inner (unwrap-tk t)]
                  (cond
                    (numeric-family? inner)
                    (if opt? [:optional inner] inner)
                    :else
                    (fail-nyi! (str "unary minus on non-numeric type "
                                    (pr-str t))
                               node)))
    :not        (let [t (infer-type (:expr node) ctx)]
                  (if (optional-tk? t) [:optional :bool] :bool))
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
    ;; K5 L1 — `if` at expression position. Both branches unify to a
    ;; common type; L1 takes the then-branch's type as the block type
    ;; (assume caller-side annotation drives outer typing; a proper
    ;; unify pass is a follow-on). Optional-lift on branch mismatch
    ;; is left to F15 L3 (bidirectional inference).
    :if-expr
    (infer-type (:then node) ctx)

    ;; F18 L2 — `do:` at expression position. Value = last statement's
    ;; expression value; if the final statement is not an :expr-stmt
    ;; (bare let, void call), block has type :void (Unit). Empty body
    ;; is also :void per open-Q3 ratified decision. `let`-bindings
    ;; introduced earlier in the block are visible when inferring the
    ;; final expression's type — thread ctx through the stmts.
    :do-block-expr
    (let [stmts   (:stmts node)
          [final-ctx final-stmt]
          (reduce (fn [[c _last] s]
                    (case (:node s)
                      :let-stmt
                      (let [ann     (:type s)
                            ann-kw  (when ann (cxc-type->type-kw
                                               (base-type-name ann)
                                               (:record-env c)
                                               (:atom-env c)
                                               (:enum-env c) ann))
                            tk      (or ann-kw (infer-type (:expr s) c))]
                        [(update c :locals assoc (:name s) tk) s])
                      [c s]))
                  [ctx nil] stmts)]
      (cond
        (nil? final-stmt)              :void
        (= :expr-stmt (:node final-stmt)) (infer-type (:expr final-stmt) final-ctx)
        :else                           :void))
    (fail-nyi! (str "infer-type " (:node node)) node)))

;; ---------------------------------------------------------------------------
;; Expression lowering (ctx-threaded)
;; ---------------------------------------------------------------------------

(declare class-name-for-atom)

(declare lower-expr lower-stmt lower-body-stmts record-synth-equals-name
         resolve-args-against-params
         ;; F37 Landings 2 + 3 forward refs — synthesis functions live below
         ;; the equals/hash block but the intercept in lower-method-call
         ;; needs them earlier.
         record-synth-as-bytes-name
         record-has-user-as-method?
         f37-primitive-byte-field?
         f37-bytes-encodable-field?
         ;; F37 Landing 5 — enum canonical bytes.
         enum-synth-as-bytes-name
         enum-eligible-for-as-bytes-synthesis?)

(defn- lower-record-ctor
  [record-name args ctx]
  (let [rec        (get-in ctx [:record-env record-name])
        field-recs (:fields rec)
        field-idx  (into {} (map (juxt :name identity)) field-recs)
        params     (mapv (fn [f] {:name (:name f) :default (:default f)})
                         field-recs)
        resolved   (resolve-args-against-params
                    (str "record `" record-name "` constructor")
                    params args)
        ;; F39 L5b-ii-c (Sub-L) — auto-wrap ctor arg when the target
        ;; field's storage is Optional-boxed. Symmetric with Sub-K's
        ;; then-update wrap. Dead until Sub-P flips `:optional?`.
        wrap-value (fn [nm v]
                     (let [f-info (get field-idx nm)
                           lo     (lower-expr v ctx)]
                       (if (:optional? f-info)
                         (let [tk       (infer-type v ctx)
                               opt-c    (:c-type f-info)
                               already? (optional-tk? tk)
                               nil?*    (= :nil tk)]
                           (cond
                             already? lo
                             nil?*    {:node :struct-lit :type opt-c
                                       :fields [{:name "present"
                                                 :value {:node :int :value 0}}]}
                             :else    {:node :struct-lit :type opt-c
                                       :fields [{:name "present"
                                                 :value {:node :int :value 1}}
                                                {:name "value" :value lo}]}))
                         lo)))
        fields   (mapv (fn [{:keys [name value]}]
                         {:name name :value (wrap-value name value)})
                       resolved)]
    {:node :struct-lit :type record-name :fields fields}))

(defn- lower-enum-payload-ctor
  "F16 L1 — lower `E.case(named args)` where `case` carries a payload.
   Emits a `:c-tagged-init` node the emitter renders as a designated-
   initializer compound literal:
     ((E){ .tag = E_Tag_case, .data.case = { .field = val, ... } })
   Named-only args per §3.7.1 (positional destructuring rejected in
   the payload declaration; call-site still accepts positional via
   `resolve-args-against-params`, matching record ctor discipline)."
  [enum-name call-node ctx]
  (let [case-nm  (:field (:callee call-node))
        payload  (get-in ctx [:enum-env enum-name :payload-index case-nm])
        params   (mapv (fn [f] {:name (:name f) :default (:default f)})
                       payload)
        resolved (resolve-args-against-params
                  (str "enum `" enum-name "." case-nm "` payload constructor")
                  params (:args call-node))
        fields   (mapv (fn [{:keys [name value]}]
                         {:name name :value (lower-expr value ctx)})
                       resolved)]
    {:node      :c-tagged-init
     :enum-name enum-name
     :case-name case-nm
     :fields    fields}))

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


(defn- check-named-tuple-ctor-conformance!
  "F15 L2.d.ii.β (2026-08-24) — for each ctor param whose declared
   type is `:type-named-tuple`, check that the caller-supplied arg's
   value structurally conforms to the required shape per spec §3.4.
   Runs after `resolve-args-against-params` so defaults are folded in.

   Two accepted arg shapes today:
   - Record instance (`{:record N}` type-kw): look up the record's
     lowered fields (each carrying a raw `:type` node preserved by
     L2.d.ii.α) and run `structural-conforms?` against the required
     named tuple's `:fields`.
   - Same-shape named tuple (`{:named-tuple <mangled>}` type-kw):
     equivalence is C-level identity per D4 — same mangled name ⇒
     conforms; different ⇒ fails with a targeted diagnostic naming
     both mangled shapes.

   Skips params where the arg's type doesn't yet infer (nil type-kw)
   or is something other than a record / named-tuple — those either
   fail elsewhere (primitives at cxc-type->c) or are legal shapes not
   yet in L2.d.ii.β scope (atoms, enums). Extend as call-site
   patterns emerge."
  [atom-name ctor-params resolved ctx]
  (doseq [p ctor-params
          :when (= :type-named-tuple (get-in p [:type :node]))]
    (let [param-name (:name p)
          required   (:type p)
          arg-value  (some #(when (= param-name (:name %)) (:value %)) resolved)
          arg-tk     (when arg-value (infer-type arg-value ctx))]
      (cond
        (nil? arg-tk) nil

        (and (map? arg-tk) (:record arg-tk))
        (let [record-name  (:record arg-tk)
              value-fields (get-in ctx [:record-env record-name :fields])
              result       (named-tuple/structural-conforms?
                            value-fields (:fields required))]
          (when-not (:conforms result)
            (let [required-mangled (named-tuple/canonical-named-tuple-name required)
                  reason-msg (case (:reason result)
                               :missing-field
                               (str " missing field `" (:field result)
                                    "` (record has: "
                                    (str/join ", " (:available result)) ")")
                               :wrong-type
                               (str " field `" (:field result)
                                    "` type mismatch (expected "
                                    (pr-str (:expected result))
                                    ", got " (pr-str (:got result)) ")")
                               "")]
              (fail-nyi! (str "atom ctor `" atom-name "` param `"
                              param-name "`: record `" record-name
                              "` does not structurally conform to named tuple `"
                              required-mangled "` —" reason-msg
                              " (spec §3.4 structural conformance)")
                         {:atom atom-name :param param-name
                          :record record-name
                          :required required-mangled
                          :reason (:reason result)}))))

        (and (map? arg-tk) (:named-tuple arg-tk))
        (let [arg-mangled      (:named-tuple arg-tk)
              required-mangled (named-tuple/canonical-named-tuple-name required)]
          (when-not (= arg-mangled required-mangled)
            (fail-nyi! (str "atom ctor `" atom-name "` param `"
                            param-name "`: named tuple `"
                            arg-mangled "` does not match required `"
                            required-mangled
                            "` (spec §3.4 structural conformance —"
                            " named-tuple shapes must be C-level identical)")
                       {:atom atom-name :param param-name
                        :got arg-mangled :required required-mangled})))

        :else nil))))

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
        ;; F15 L2.d.ii.β — structural conformance check runs on
        ;; resolved args before lower-expr so a mismatch surfaces with
        ;; the raw source-level type node in the diagnostic rather
        ;; than a lowered form (better user-facing error).
        (check-named-tuple-ctor-conformance! atom-name ctor-params
                                             resolved ctx)
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
          ;; F67 L4a — parametric observables (`name(args) :< expr`)
          ;; dispatch through the same C-function pathway as record
          ;; methods. Return an observable-synthesized entry that
          ;; mimics the method shape (self as first param) so
          ;; `resolve-args-against-params` works uniformly.
          (or (get-in ctx [:record-env (:record target-ty) :method-index method-name])
              (when-let [o (get-in ctx [:record-env (:record target-ty)
                                        :observable-index method-name])]
                (let [tk (:type-kw o)
                      ret-name (cond
                                 (keyword? tk) (name tk)
                                 (and (map? tk) (:record tk)) (:record tk)
                                 (and (map? tk) (:atom tk))   (:atom tk)
                                 :else (str tk))]
                  {:name          method-name
                   :params        (into [{:name "self" :cxc-ty (:record target-ty)
                                          :is-self? true}]
                                        (mapv (fn [p]
                                                {:name  (:name p)
                                                 :cxc-ty (:name (:type p))
                                                 :default (:default p)})
                                              (:params o)))
                   :cxc-return-ty ret-name
                   :cxc-observable? true})))
          (and (map? target-ty) (:atom target-ty))
          ;; F67 L4 — parametric observables on atoms dispatch as
          ;; ObjC methods. Method-index lookup first (own/handler
          ;; methods, protocol defaults inherited); fall back to
          ;; observable-index and synthesize a method-shape entry so
          ;; `resolve-args-against-params` fires uniformly.
          (or (get-in ctx [:atom-env (:atom target-ty) :method-index method-name])
              (when-let [o (get-in ctx [:atom-env (:atom target-ty)
                                        :observable-index method-name])]
                (let [tk (:type-kw o)
                      ret-name (cond
                                 (keyword? tk) (name tk)
                                 (and (map? tk) (:record tk)) (:record tk)
                                 (and (map? tk) (:atom tk))   (:atom tk)
                                 :else (str tk))]
                  {:name          method-name
                   :params        (into [{:name "self" :cxc-ty (:atom target-ty)
                                          :is-self? true}]
                                        (mapv (fn [p]
                                                {:name  (:name p)
                                                 :cxc-ty (:name (:type p))
                                                 :default (:default p)})
                                              (:params o)))
                   :cxc-return-ty ret-name
                   :cxc-observable? true}))))
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
      ;; F37 Landing 2 — `.as()` on a record with primitive-byte-eligible
      ;; fields dispatches to `<Record>_cxc_as_bytes(target)`. Fires
      ;; only when the user hasn't declared their own `as` method
      ;; (§3.3.2 rule 6 override). Non-primitive fields raise a
      ;; targeted fail-nyi naming the offending field and pointing at
      ;; the follow-on landing (Landing 3+) that unblocks it. Ignores
      ;; args (an `.as(order: ...)` overload would resolve through the
      ;; ordinary user-method path via :method-index, distinct
      ;; signature per §3.11.4).
      (if (and (map? target-ty) (:record target-ty)
               (= "as" method-name) (empty? args))
        (let [rec-name (:record target-ty)
              info     (get-in ctx [:record-env rec-name])]
          (cond
            (record-has-user-as-method? info)
            {:node :call
             :fn   (c-method-name rec-name "as")
             :args [target-lo]}

            (every? #(f37-bytes-encodable-field? % (:record-env ctx))
                    (:fields info))
            {:node :call
             :fn   (record-synth-as-bytes-name rec-name)
             :args [target-lo]}

            :else
            (let [bad (first (remove #(f37-bytes-encodable-field? % (:record-env ctx))
                                     (:fields info)))]
              (fail-nyi!
               (str "F37 Landings 2 + 3: cannot synthesize `.as() -> Bytes` for "
                    "record `" rec-name "." (:name bad) "` (type `"
                    (:cxc-ty bad) "`" (when (:optional? bad) " Optional-boxed")
                    "). Primitive fields (Bool / sized-int / Int / Float / "
                    "Double) and `String!` fields ship at Landings 2 + 3; "
                    "nested records land in Landing 4, enums in Landing 5, "
                    "Optional in F39 Landing 9, atoms require user-written "
                    "`.as()` (spec §3.4.6). Write `function as(self) -> "
                    "Bytes:` on the record to override.")
               call-node))))
      (if (and (map? target-ty) (:enum target-ty)
               (= "as" method-name) (empty? args))
        ;; F37 Landing 5 — enum canonical bytes. Value-only enums are
        ;; always eligible (single discriminator byte). Payload cases
        ;; must have all-encodable fields; a targeted fail-nyi names
        ;; the offending case + field type otherwise.
        (let [en-name (:enum target-ty)
              info    (get-in ctx [:enum-env en-name])]
          (if (enum-eligible-for-as-bytes-synthesis? info (:record-env ctx))
            {:node :call
             :fn   (enum-synth-as-bytes-name en-name)
             :args [target-lo]}
            (fail-nyi!
             (str "F37 Landing 5: cannot synthesize `.as() -> Bytes` for "
                  "enum `" en-name "` — at least one payload field is not "
                  "yet bytes-encodable. Primitive (`Bool` / sized-int / "
                  "`Float` / `Double`) and `String!` payload fields encode; "
                  "nested records / atoms / containers land in later "
                  "landings.")
             call-node)))
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

          ;; F37 L6a — `Bytes.at(offset) -> Int`. Reads the byte at
          ;; `offset` and widens to Int64 so downstream numeric code
          ;; doesn't require an explicit cast. Bounds are the caller's
          ;; responsibility — pair with `bytes.length` when
          ;; iterating. Emits `((int64_t)((const uint8_t *)[bytes bytes])[offset])`
          ;; — one Objective-C property access + one pointer index +
          ;; a widen. No allocation, no message send in the hot path.
          (and (keyword? target-ty) (= :bytes target-ty)
               (= "at" method-name))
          (do (when-not (= 1 (count arg-exprs))
                (fail-nyi! (str "F37 L6a: `Bytes.at` expects exactly one "
                                "arg (offset), got " (count arg-exprs))
                           call-node))
              {:node      :ffi-call
               :name      "cxc_bytes_at"
               :args      [target-lo (first arg-exprs)]
               :emit-c-fn (fn [[b o]]
                            (str "((int64_t)((const uint8_t *)["
                                 b " bytes])[" o "])"))})

          ;; F37 L6a — `Bytes.slice(start: Int, length: Int) -> Bytes`.
          ;; Emits `[(NSMutableData *)[bytes subdataWithRange:NSMakeRange(start, length)] mutableCopy]`
          ;; — Foundation's -subdataWithRange returns an immutable
          ;; NSData; -mutableCopy up-converts to NSMutableData so the
          ;; result keeps the same C-level type as Bytes elsewhere.
          ;; One allocation per call. Two-positional-arg shape is the
          ;; interim MVP surface — Range grammar hasn't landed yet.
          (and (keyword? target-ty) (= :bytes target-ty)
               (= "slice" method-name))
          (do (when-not (= 2 (count arg-exprs))
                (fail-nyi! (str "F37 L6a: `Bytes.slice` expects exactly two "
                                "args (start, length), got " (count arg-exprs))
                           call-node))
              {:node      :ffi-call
               :name      "cxc_bytes_slice"
               :args      (into [target-lo] arg-exprs)
               :emit-c-fn (fn [[b s l]]
                            (str "[[" b " subdataWithRange:NSMakeRange("
                                 s ", " l ")] mutableCopy]"))})

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
                              :else [])
                msg-send    {:node     :objc-msg
                             :recv     target-lo
                             :selector (objc-method-selector method-name param-names)
                             :args     arg-exprs}
                ;; F39 L8 (spec §4.10.9) — nil-receiver fallback dispatch.
                ;; If the callee atom declares an `on_nil:` entry for this
                ;; method, wrap the message send in a guarded ternary:
                ;;   (recv != nil) ? [recv msg...] : <fallback>
                ;; Eval-once discipline on `recv` via a GNU stmt-expr in
                ;; the emit shape (:c-on-nil-dispatch), matching L6-ref
                ;; and L5b-ii-d's single-eval patterns for side-effecting
                ;; receivers (`Counter().other.method()` chains).
                on-nil-expr (get-in ctx [:atom-env atom-name :on-nil-entries method-name])]
            (if on-nil-expr
              {:node     :c-on-nil-dispatch
               :recv     target-lo
               :send     msg-send
               :fallback (lower-expr on-nil-expr ctx)}
              msg-send))

          :else
          (fail-nyi! "method call on non-record/non-atom target" call-node))))))))

(def ^:private statement-node-kinds
  #{:expr-stmt :emit :break-stmt :continue-stmt :while-stmt
    :let-stmt :if-stmt :block})

(defn- lambda-body-stmts
  "Extract the sequence of body statements from a lambda AST node.
   `body` may be:
   - a `:block` wrapping multi-statement form → return its stmts
   - a bare statement node → wrap in a single-element vector
   - a bare expression (post-F20 REJECTED: `xs.for_each(e =>
     print(e))` gives lambda body as raw `:call`) → wrap in
     `:expr-stmt` so downstream stmt-lowering works uniformly"
  [lambda-node]
  (let [body (:body lambda-node)]
    (cond
      (nil? body) []
      (and (map? body) (= :block (:node body))) (:stmts body)
      (and (map? body) (contains? statement-node-kinds (:node body))) [body]
      :else [{:node :expr-stmt :expr body}])))

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
        body-stmts       (lower-body-stmts body-nodes body-ctx)]
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
    (if (optional-tk? arg-ty)
      ;; F39 L5b-ii-c (Sub-G) — Optional print. Present → format
      ;; inner; nil → "nil\n". Dead until Sub-P flips storage.
      (let [inner (unwrap-tk arg-ty)]
        {:node          :c-print-optional
         :arg           (lower-expr arg-expr ctx)
         :inner-arg-type inner
         :inner-fmt     (print-format-string inner)})
      {:node :call
       :fn   "printf"
       :printf-arg-type arg-ty
       :args [{:node :str :value (print-format-string arg-ty)}
              (lower-expr arg-expr ctx)]})))

(defn- lower-expr [node ctx]
  (case (:node node)
    ;; F65 §3.10.5 — String is NSString *. String literals emit as
    ;; `@"..."` (NSString literal) instead of `"..."` (const char *).
    ;; :objc-str already exists as an emit-node kind for this shape.
    :lit-string {:node :objc-str :value (:value node)}
    :lit-int    {:node :int   :value (:value node)}
    :lit-float  {:node :float :value (:value node)}
    :lit-nil    {:node :nil}
    ;; F17 L1 companion — `true` / `false` literal exprs (spec §3.1
    ;; Bool primitive). Emits as C `1` / `0` (Bool is a value type
    ;; mapped to an integer per F36 L1). Callers that need actual
    ;; `_Bool` typing get the correct type-kw via infer-type below.
    :lit-bool   {:node :int :value (if (:value node) 1 0)}
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
                    ;; Real data-field. F33 L2.c: inside a receive-glyph
                    ;; branch body (ctx `:snapshot-name` set), reroute
                    ;; to the transition-entry snapshot so cross-field
                    ;; reads see a consistent pre-transition state and
                    ;; a `then:` update to field A can't leak into a
                    ;; subsequent read of field A in the same body.
                    ;; Absent the ctx entry (init bodies, invariant
                    ;; sites, etc.), fall back to raw ivar access —
                    ;; matches how writes go via `_field = value;` in
                    ;; `lower-then-update`.
                    (if-let [snap (:snapshot-name ctx)]
                      ;; Snapshot struct fields carry the same underscore-
                      ;; prefixed names as the ivars they mirror (see
                      ;; `state-struct-field-line` in emit.clj), so the
                      ;; field selector is `_<n>`, not `<n>`.
                      {:node :field
                       :target {:node :name :sym snap}
                       :field  (str "_" n)}
                      {:node :name :sym (str "_" n)})

                    :else
                    {:node :name :sym n}))
    :self       {:node :name  :sym "self"}
    :binop      (cond
                  (= "in" (:op node))
                  (fail-nyi! "`in` membership operator" node)

                  ;; F39 L6 — `??` coalesce (reference-nullable case).
                  (= "??" (:op node))
                  {:node  :c-coalesce
                   :mode  :reference
                   :left  (lower-expr (:left node)  ctx)
                   :right (lower-expr (:right node) ctx)}

                  ;; F39 L5b-ii-c (Sub-H) — presence check on Optional.
                  ;; `x is nil` / `x == nil` / `x != nil` (or symmetric)
                  ;; where x is Optional → :c-optional-nil-check. Returns
                  ;; pure Bool (presence is a terminating query, not a
                  ;; propagating operation). Dead until Sub-P.
                  (let [op (:op node)
                        lt (infer-type (:left node)  ctx)
                        rt (infer-type (:right node) ctx)
                        left-nil?  (= :nil lt)
                        right-nil? (= :nil rt)]
                    (and (contains? #{"is" "==" "!="} op)
                         (or (and (optional-tk? lt) right-nil?)
                             (and (optional-tk? rt) left-nil?))))
                  (let [op (:op node)
                        lt (infer-type (:left node) ctx)
                        rt (infer-type (:right node) ctx)
                        opt-side (if (optional-tk? lt) (:left node) (:right node))
                        negate?  (= "!=" op)]
                    {:node    :c-optional-nil-check
                     :inner   (lower-expr opt-side ctx)
                     :negate? negate?})

                  ;; F39 L5b-ii-c (Sub-C) — Optional-binop dispatch.
                  ;; If either operand's inferred type is [:optional _],
                  ;; route to :c-optional-binop. Kinds:
                  ;;   :arith → propagates Optional value with widened
                  ;;            inner arithmetic
                  ;;   :ord   → propagates Optional<Bool>
                  ;;   :eq/:neq → collapses to Bool (nil-aware eq)
                  ;; Dead until Sub-P flips the storage flag — no
                  ;; expression today infers as Optional.
                  (let [lt (infer-type (:left node) ctx)
                        rt (infer-type (:right node) ctx)
                        op (:op node)
                        any-opt? (or (optional-tk? lt) (optional-tk? rt))]
                    (and any-opt?
                         (contains? #{"+" "-" "*" "/" "%"
                                      "<" "<=" ">" ">="
                                      "==" "!="} op)))
                  (let [lt (infer-type (:left node) ctx)
                        rt (infer-type (:right node) ctx)
                        op (:op node)
                        result-tk    (infer-binop-type node ctx)
                        result-inner (when (optional-tk? result-tk)
                                       (unwrap-tk result-tk))
                        result-opt-c (when result-inner
                                       (optional-type-name
                                        (type-kw->cxc-name-str result-inner)))
                        kind (cond
                               (contains? #{"+" "-" "*" "/" "%"} op) :arith
                               (contains? #{"<" "<=" ">" ">="} op)   :ord
                               (= "==" op)                            :eq
                               (= "!=" op)                            :neq)]
                    {:node               :c-optional-binop
                     :op                 (if (= "%" op) "%" op)
                     :left               (lower-expr (:left node) ctx)
                     :right              (lower-expr (:right node) ctx)
                     :left-opt?          (optional-tk? lt)
                     :right-opt?         (optional-tk? rt)
                     :result-kind        kind
                     :result-opt-c-type  result-opt-c})

                  ;; F37: record-typed `==` / `!=` reroutes through the
                  ;; synthesized `<Record>_cxc_equals` free function.
                  ;; Raw C `struct1 == struct2` is not valid C — this
                  ;; is a correctness fix, not an optimization.
                  :else
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
    :neg        (let [inner-tk (infer-type (:expr node) ctx)]
                  (if (optional-tk? inner-tk)
                    ;; F39 L5b-ii-c (Sub-D) — Optional propagation.
                    ;; Dead until Sub-P flips storage.
                    {:node :c-optional-unary
                     :op   "-"
                     :inner (lower-expr (:expr node) ctx)
                     :result-opt-c-type
                     (optional-type-name (type-kw->cxc-name-str (unwrap-tk inner-tk)))}
                    {:node :unary :op "-"
                     :expr (lower-expr (:expr node) ctx)}))
    :not        (let [inner-tk (infer-type (:expr node) ctx)]
                  (if (optional-tk? inner-tk)
                    ;; F39 L5b-ii-c (Sub-D) — Optional propagation.
                    ;; Dead until Sub-P flips storage.
                    {:node :c-optional-unary
                     :op   "!"
                     :inner (lower-expr (:expr node) ctx)
                     :result-opt-c-type
                     (optional-type-name (type-kw->cxc-name-str (unwrap-tk inner-tk)))}
                    {:node :unary :op "!"
                     :expr (lower-expr (:expr node) ctx)}))
    ;; F27 semantic once-only: bind each interior operand to a
    ;; `__typeof__` local via GNU statement-expression, then chain
    ;; comparisons over the bound locals. Emit renders the
    ;; statement-expression from the operand + op vectors.
    :chained-cmp
    (let [op-tks    (mapv #(infer-type % ctx) (:operands node))
          opts?     (mapv optional-tk? op-tks)
          any-opt?  (some true? opts?)
          lowered   (mapv #(lower-expr % ctx) (:operands node))
          ops       (mapv (fn [op]
                            (case op "<" "<" "<=" "<=" ">" ">" ">=" ">="))
                          (:ops node))]
      (if any-opt?
        ;; F39 L5b-ii-c (Sub-F) — Optional-propagating chained-cmp
        ;; returns Optional<Bool>. Dead until Sub-P flips storage.
        {:node :c-optional-chained-cmp
         :operands          lowered
         :operand-opts?     opts?
         :ops               ops
         :result-opt-c-type (optional-type-name "Bool")}
        {:node :chained-cmp
         :operands lowered
         :ops      ops}))
    :dot        (cond
                  (enum-case-access? node ctx)
                  ;; F16 L1 — tagged enums require a compound literal
                  ;; even for bare cases (the C type is a struct, not
                  ;; an integer). Value-only enums keep the pre-F16
                  ;; `E_case` C constant.
                  (let [en (get-in node [:target :name])
                        cs (:field node)]
                    (if (get-in ctx [:enum-env en :tagged?])
                      {:node :c-tagged-init :enum-name en :case-name cs
                       :fields []}
                      {:node :name :sym (str en "_" cs)}))

                  ;; F67 L4a — zero-arg observable on a record. `V(...).mag`
                  ;; is really `V_mag(V(...))` — a C function call, not
                  ;; struct-field access. Detect by looking up the field
                  ;; name in the record's `:observable-index` (populated
                  ;; by `infer-observables-in-record-env`). Field-index
                  ;; hits still emit struct access as before.
                  (let [target-ty (infer-type (:target node) ctx)]
                    (and (map? target-ty) (:record target-ty)
                         (get-in (:record-env ctx)
                                 [(:record target-ty) :observable-index (:field node)])
                         (not (get-in (:record-env ctx)
                                      [(:record target-ty) :field-index (:field node)]))))
                  {:node :call
                   :fn   (c-method-name (:record (infer-type (:target node) ctx))
                                        (:field node))
                   :args [(lower-expr (:target node) ctx)]}

                  :else
                  (let [target-ty (infer-type (:target node) ctx)]
                    ;; F33 Landing 1 — reject cross-atom `data:` field
                    ;; reads. infer-type triggers field-type-for-owner
                    ;; which owns the diagnostic; call it here so a bare
                    ;; `.field` in a stmt context (where lower-expr
                    ;; doesn't otherwise type-check) still catches it.
                    (field-type-for-owner target-ty ctx (:field node) node)
                    (cond
                      ;; F39 L5b-ii-c (Sub-E) — dot on Optional record.
                      ;; `x.field` where x is [:optional {:record R}]
                      ;; propagates Optional. Two shapes:
                      ;;   inner-field scalar   → wrap `.value.field`
                      ;;                          into `_Cxc_Optional_<T>`
                      ;;   inner-field Optional → the inner `.value.field`
                      ;;                          is ALREADY Optional; the
                      ;;                          outer just gates presence
                      ;;                          → propagate the inner
                      ;;                          Optional as-is when
                      ;;                          present, nil otherwise
                      (and (optional-tk? target-ty)
                           (let [inner (unwrap-tk target-ty)]
                             (and (map? inner) (:record inner))))
                      (let [inner-rec (:record (unwrap-tk target-ty))
                            f-info    (get-in (:record-env ctx)
                                              [inner-rec :field-index (:field node)])
                            inner-opt? (:optional? f-info)
                            field-tk  (when f-info
                                        (cxc-type->type-kw (:cxc-ty f-info)
                                                           (:record-env ctx)
                                                           (:atom-env ctx)
                                                           (:enum-env ctx) node))
                            opt-c     (when field-tk
                                        (optional-type-name
                                         (type-kw->cxc-name-str field-tk)))]
                        {:node               :c-optional-dot
                         :target             (lower-expr (:target node) ctx)
                         :field              (:field node)
                         :inner-already-opt? inner-opt?
                         :result-opt-c-type  opt-c})

                      :else
                      (let [target-lo (lower-expr (:target node) ctx)
                            atom-nm   (when (and (map? target-ty) (:atom target-ty))
                                        (:atom target-ty))
                            on-nil-expr (when atom-nm
                                          (get-in ctx
                                                  [:atom-env atom-nm
                                                   :on-nil-entries (:field node)]))]
                        (if on-nil-expr
                          ;; F39 L8 — nil-receiver fallback dispatch on
                          ;; a zero-arg observable read (bare `.count`).
                          ;; The plain `:field` access lowers to ObjC
                          ;; property syntax which nil-messages naturally,
                          ;; but the runtime return would be raw zero /
                          ;; nil, not the declared fallback. Wrap in
                          ;; :c-on-nil-dispatch so the ternary picks the
                          ;; explicit fallback when receiver is nil.
                          {:node     :c-on-nil-dispatch
                           :recv     target-lo
                           :send     (cond-> {:node   :field
                                              :target target-lo
                                              :field  (:field node)}
                                       ;; F32 L4.d.β.β — see below.
                                       (and atom-nm
                                            (not= :self (:node (:target node))))
                                       (assoc :cross-atom-cls-name
                                              (class-name-for-atom atom-nm)))
                           :fallback (lower-expr on-nil-expr ctx)}
                          ;; F32 L4.d.β.β (2026-08-24) — cross-atom
                          ;; `.field` reads carry a `:cross-atom-cls-name`
                          ;; marker so the emit-side routes them through
                          ;; `_cxc_atom_state_ptr(&target->_state)` under
                          ;; `!CXC_SINGLE_MAIN_QUEUE`. Under the default
                          ;; preprocessor flag the marker is harmless:
                          ;; emit-side falls back to plain property access.
                          ;;
                          ;; F32 L4.e.β.1 (2026-08-24) — self-reads
                          ;; (`self.field` from a source `:self` node)
                          ;; inside a receive-glyph body (ctx
                          ;; `:snapshot-name` set) reroute to the
                          ;; transition-entry snapshot bag, parallel to
                          ;; L2.c's bare-ident reroute (line 4423).
                          ;; Semantics-equivalent under the default flag
                          ;; per F33 L3.b (self writes stay in
                          ;; `__cxc_next` until commit-next-state fires
                          ;; at end of body, so the ivar during-body has
                          ;; the same pre-transition value the snapshot
                          ;; carries). Substrate for L4.e.β proper —
                          ;; under concurrent mode with L4.e.α's snapshot-
                          ;; from-`_state` seed, self reads route through
                          ;; committed state, closing the self-side of
                          ;; the ivar-reader gap so per-field ivar writes
                          ;; can safely retire at commit-next-state.
                          ;; Absent `:snapshot-name` (init bodies,
                          ;; invariant sites, observable bodies) self-
                          ;; reads stay on the ivar path unchanged.
                          (cond
                            (and (= :self (:node (:target node)))
                                 (:snapshot-name ctx))
                            {:node   :field
                             :target {:node :name :sym (:snapshot-name ctx)}
                             :field  (str "_" (:field node))}

                            :else
                            (cond-> {:node   :field
                                     :target target-lo
                                     :field  (:field node)}
                              (and atom-nm
                                   (not= :self (:node (:target node))))
                              (assoc :cross-atom-cls-name
                                     (class-name-for-atom atom-nm)))))))))
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
            ;; See plans/g4-actor-integrity/f31-f35-concurrency.md L2.
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
                                    node))
                  ;; L-panic-cap (2026-08-26) — require the enclosing
                  ;; atom to declare `use panic: SystemPanic`. Panic
                  ;; is the process-terminal escape hatch; the `use:`
                  ;; is the compile-time "you're on your own" contract.
                  self-info (get-in ctx [:locals "self"])
                  self-atom (and (map? self-info) (:atom self-info))
                  atom-info (when self-atom (get-in ctx [:atom-env self-atom]))
                  has-panic-cap?
                  ;; Accept both the mangled prelude name (real
                  ;; user-facing shape) and the un-mangled name (used
                  ;; by unit tests that skip the prelude rewrite pass).
                  (some #(contains? #{"_Cxc_SystemPanic" "SystemPanic"}
                                    (:type-name %))
                        (:use-caps atom-info))
                  _    (when-not has-panic-cap?
                         (fail-compile!
                          :cxc-panic-requires-use-cap
                          (str "panic(...) requires the enclosing atom to "
                               "declare `use panic: SystemPanic`. Panic is "
                               "the process-terminal escape hatch (F31/F35 "
                               "L2 semantics unchanged); the `use:` is the "
                               "compile-time \"you're on your own\" contract. "
                               "Add `use panic: SystemPanic` to atom `"
                               (or self-atom "<unknown>") "`.")
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

        :dot   (if-let [en (enum-payload-call? node ctx)]
                 (lower-enum-payload-ctor en node ctx)
                 (lower-method-call node ctx))

        (fail-nyi! (str "call with " (:node callee) " callee") node)))

    ;; F69 L4b — dispatch at expression position (embedded inside a
    ;; let-init / function arg / etc.) needs block-as-expression (F18)
    ;; to yield a value. Statement-position dispatch lowers via
    ;; lower-stmt :dispatch directly; only value-yielding embedding
    ;; hits this branch. Reject with a targeted diagnostic pointing
    ;; at the missing dependency.
    :dispatch
    (fail-nyi!
     (str "F69 L4b: `<:` dispatch at expression position needs F18 "
          "(block-as-expression) — not yet landed. Use dispatch at "
          "statement position for now (top-level in a `do:` / method "
          "body / branch body).")
     node)

    ;; K5 L1 — `if` at expression position (spec §3.8.1). Lowers to a
    ;; C ternary `(cond ? then : else)` for the single-expression
    ;; branch form. `else if` chains lowered as nested :c-ternary via
    ;; the recursive parse-side desugar. Both branches lowered as
    ;; expressions; wrap in :c-ternary node (already emitted by F39
    ;; L6 for coalesce).
    :if-expr
    {:node :c-ternary
     :cond (lower-expr (:cond node) ctx)
     :then (lower-expr (:then node) ctx)
     :else (lower-expr (:else node) ctx)}

    ;; F18 L2 — `do:` at expression position. Lower statements uniformly
    ;; via lower-body-stmts (already threads `let`-binding ctx). Wrap any
    ;; naked expression node (from lower-expr on an :expr-stmt) as
    ;; :c-expr-stmt so emit-stmt's dispatch is total. GNU stmt-expr
    ;; treats the last :c-expr-stmt as the block's value; empty body or
    ;; last-is-decl yields void per open-Q3 ratified decision.
    :do-block-expr
    (let [lowered (lower-body-stmts (:stmts node) ctx)
          stmt-kinds #{:var-decl :c-while :c-break :c-continue :c-scope
                       :c-if-chain :return :assign :call :objc-msg
                       :ffi-call :c-print-optional :c-on-nil-dispatch
                       :c-expr-stmt}
          wrapped (mapv (fn [n]
                          (if (stmt-kinds (:node n))
                            n
                            {:node :c-expr-stmt :expr n}))
                        lowered)]
      {:node :c-stmt-expr :stmts wrapped})

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
   substitution point. See plans/g4-actor-integrity/f31-f35-concurrency.md L1."
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
        helper    (if (:async? node) "cxc_send_async" "cxc_wait_for_processing")
        send-call {:node :call
                   :fn   helper
                   :args [target-lo
                          {:node :objc-block-literal
                           :stmts [{:node :objc-msg
                                    :recv target-lo
                                    :selector selector
                                    :args arg-exprs}]}]}
        ;; L9 (2026-08-26) — `or:` continuation attaches a failure-
        ;; recovery body. Applies to sync sends only (async is
        ;; fire-and-forget: no completion the caller can observe).
        or-body   (:or-body node)
        _         (when (and or-body (:async? node))
                    (fail-nyi!
                     (str "L9: `or:` continuation on an async `:>` send — "
                          "async is fire-and-forget so recovery has "
                          "no defined caller frame to run in. Use sync "
                          "`|>` for recoverable sends.")
                     node))]
    (if or-body
      {:node       :sync-send-with-recovery
       :send-call  send-call
       :or-body    (mapv (fn [s] (lower-stmt s ctx)) or-body)}
      send-call))))

;; ---------------------------------------------------------------------------
;; F69 L4b — Lower `<:` dispatch to inline tag-check chain.
;;
;; L4b scope: statement-position dispatch on tagged-variant (enum-typed)
;; scrutinees with case + wildcard patterns only. Payload bindings
;; (`<: name(let x):`) are parse-accepted but rejected at lower-time
;; until F16 (enum payloads) lands. Expression-position dispatch is
;; L4c (requires block-as-expression, F18).
;; ---------------------------------------------------------------------------

(defn- lower-dispatch-pattern-cond
  "Lower a single pattern into a boolean condition against `scrut-tmp`.
   Returns nil for the wildcard (its caller collapses the entire arm
   into a bare `else` branch). Rejects case patterns with payload
   bindings until F16 lands. `scrut-ty` is the scrutinee's inferred
   type — enum-only for case patterns; int/bool for literal patterns."
  [scrut-ty scrut-tmp pattern ctx]
  (case (:kind pattern)
    :wildcard nil

    :literal
    (let [lit-ty (:lit-type pattern)]
      (case lit-ty
        :int  (when-not (= :int scrut-ty)
                (fail-nyi!
                 (str "F69 L4c4: integer literal pattern requires an Int "
                      "scrutinee — got " (pr-str scrut-ty) ".")
                 pattern))
        :bool (when-not (= :bool scrut-ty)
                (fail-nyi!
                 (str "F69 L4c4: bool literal pattern requires a Bool "
                      "scrutinee — got " (pr-str scrut-ty) ".")
                 pattern)))
      {:node :binop :op "=="
       :left  {:node :name :sym scrut-tmp}
       :right (case lit-ty
                :int  {:node :int :value (:value pattern)}
                :bool {:node :int :value (if (:value pattern) 1 0)})})

    :case
    (do
      (when-not (and (map? scrut-ty) (:enum scrut-ty))
        (fail-nyi!
         (str "F69 L4b: case pattern `<: " (:name pattern) ":` requires "
              "a tagged-variant (enum) scrutinee — got " (pr-str scrut-ty) ".")
         pattern))
      (let [enum-name (:enum scrut-ty)
            case-nm   (:name pattern)
            info      (get-in ctx [:enum-env enum-name])
            cases     (:case-index info)
            tagged?   (:tagged? info)
            payload   (get-in info [:payload-index case-nm])]
        (when-not (contains? cases case-nm)
          (fail-nyi!
           (str "F69 L4b: unknown case `" case-nm "` in `<:` dispatch on "
                "enum `" enum-name "` — declared cases are "
                (pr-str (vec (keys cases))) ".")
           pattern))
        (when (and (seq (:bindings pattern)) (not tagged?))
          (fail-nyi!
           (str "F69 L4b: `<: " case-nm "(let ...):` payload bindings on "
                "value-only enum `" enum-name "` — the case declares no "
                "payload (add `case " case-nm "(field: Type)` to the enum "
                "declaration, or drop the bindings).")
           pattern))
        (when (and (seq (:bindings pattern)) (empty? payload))
          (fail-nyi!
           (str "F69 L4b: `<: " case-nm "(let ...):` payload bindings — "
                "case `" case-nm "` on enum `" enum-name "` is bare (no "
                "payload). Drop the bindings.")
           pattern))
        (when (seq (:bindings pattern))
          ;; F16 L1 — reject positional bindings and unknown field names;
          ;; enum payloads are named-only per §3.7.1. `parse-dispatch-
          ;; payload-binding` marks positional bindings with `:field nil`.
          (doseq [b (:bindings pattern)]
            (cond
              (nil? (:field b))
              (fail-nyi!
               (str "F16 L1: positional payload binding `let " (:as b)
                    "` in `<: " case-nm "(...)` — enum payloads are named-"
                    "only per §3.7.1. Use `<field>: let " (:as b) "`.")
               pattern)

              (not (some #(= (:field b) (:name %)) payload))
              (fail-nyi!
               (str "F16 L1: unknown payload field `" (:field b) "` on "
                    "case `" case-nm "` of enum `" enum-name "` — declared "
                    "fields are "
                    (pr-str (mapv :name payload)) ".")
               pattern))))
        (if tagged?
          {:node :binop :op "=="
           :left  {:node :field :target {:node :name :sym scrut-tmp} :field "tag"}
           :right {:node :name  :sym    (str enum-name "_Tag_" case-nm)}}
          {:node :binop :op "=="
           :left  {:node :name :sym scrut-tmp}
           :right {:node :name :sym (str enum-name "_" case-nm)}})))))

(defn- lower-dispatch-arm
  "Lower one dispatch arm into a `:c-if-chain` branch.

   Single-pattern arms produce `{:cond <tag-eq> :body ...}`
   (or `{:cond nil ...}` for the wildcard).

   F69 L4c2 — multi-pattern arms (`<: red, orange, yellow: ...`)
   OR the lowered conditions into a chained `||` expression. A
   wildcard in a multi-pattern arm collapses the pattern condition
   to nil (matches everything).

   F69 L4c3 — guards (`<: pattern if <cond>:`) AND-compose with the
   pattern condition. A guarded wildcard becomes `else if (guard)`
   rather than a bare `else`."
  [scrut-ty scrut-tmp arm ctx]
  (let [{:keys [body guard]} arm
        ;; fall back to :pattern for arms that pre-date L4c2
        patterns   (or (:patterns arm) [(:pattern arm)])
        ;; F16 L1 — collect payload bindings on this arm's pattern.
        ;; Multi-pattern arms (L4c2) with bindings would need bindings
        ;; that overlap across patterns; disallowed here — bindings
        ;; only make sense on single-pattern arms. Bindings apply to
        ;; the arm body only, wrapped in :c-scope with generated var-
        ;; decls.
        payload-bindings
        (when (and (= 1 (count patterns))
                   (= :case (:kind (first patterns)))
                   (seq (:bindings (first patterns)))
                   (map? scrut-ty) (:enum scrut-ty)
                   (seq (get-in ctx [:enum-env (:enum scrut-ty)
                                     :payload-index (:name (first patterns))])))
          (let [pat        (first patterns)
                enum-name  (:enum scrut-ty)
                case-nm    (:name pat)
                info       (get-in ctx [:enum-env enum-name])
                payload    (get-in info [:payload-index case-nm])
                field-idx  (into {} (map (juxt :name identity)) payload)]
            (mapv (fn [b]
                    (let [f       (get field-idx (:field b))
                          ty-node (:type f)
                          inner   (if (= :type-nonnil (:node ty-node))
                                    (:inner ty-node) ty-node)
                          nm      (:name inner)
                          tk      (cxc-type->type-kw nm
                                                     (:record-env ctx)
                                                     (:atom-env ctx)
                                                     (:enum-env ctx)
                                                     ty-node)
                          c-ty    (cxc-type->c ty-node
                                               (:record-env ctx)
                                               (:atom-env ctx)
                                               (:enum-env ctx))]
                      {:name (:as b) :type-kw tk :c-type c-ty
                       :init {:node :field
                              :target {:node :field
                                       :target {:node :field
                                                :target {:node :name :sym scrut-tmp}
                                                :field "data"}
                                       :field case-nm}
                              :field (:field b)}}))
                  (:bindings pat))))
        arm-ctx    (if payload-bindings
                     (update ctx :locals into
                             (map (juxt :name :type-kw) payload-bindings))
                     ctx)
        body-stmts
        (cond
          (= :block (:node body))
          (lower-body-stmts (:stmts body) arm-ctx)

          :else
          [(lower-expr body arm-ctx)])
        ;; Prepend var-decls for payload bindings inside the arm body.
        ;; Emit as :var-decl nodes so the scope of the binding matches
        ;; the arm branch (each `if`/`else if` block gets its own
        ;; scope, so bindings from one case can't leak into another).
        body-stmts
        (if payload-bindings
          (into (mapv (fn [b]
                        {:node :var-decl :name (:name b)
                         :c-type (:c-type b) :rhs (:init b)})
                      payload-bindings)
                body-stmts)
          body-stmts)
        conds      (mapv #(lower-dispatch-pattern-cond scrut-ty scrut-tmp % ctx)
                         patterns)
        pattern-cond
        (cond
          ;; any pattern is a wildcard — pattern-cond collapses to nil
          (some nil? conds) nil

          ;; single pattern — direct cond
          (= 1 (count conds)) (first conds)

          ;; multi-pattern — OR-chain via right-associative binops.
          ;; Emit-ready `||` (post-lower); the `or → ||` translation in
          ;; lower-expr:binop only runs on user-source binops.
          :else
          (reduce (fn [acc c] {:node :binop :op "||" :left c :right acc})
                  (last conds)
                  (reverse (butlast conds))))
        _           (when guard
                       (assert-not-optional-guard! guard (infer-type guard ctx)
                                                   "dispatch guard `if`" guard))
        guard-lo    (when guard (lower-expr guard ctx))
        final-cond
        (cond
          (and pattern-cond guard-lo)
          {:node :binop :op "&&" :left pattern-cond :right guard-lo}

          pattern-cond pattern-cond
          guard-lo     guard-lo
          :else        nil)]
    {:cond final-cond :body body-stmts}))

(defn- arm-patterns
  "Return the vector of patterns in `arm` — L4c2 arms carry `:patterns`;
   older-shape arms fall back to `[(:pattern arm)]`."
  [arm]
  (or (:patterns arm) [(:pattern arm)]))

(defn- check-dispatch-exhaustiveness!
  "F69 L4c1 — spec §3.7 exhaustiveness rule for tagged-variant scrutinees:
   every declared case must be covered, or a `<: *:` wildcard included.
   Rejects at lower-time with a message listing the missing cases when
   coverage is incomplete. Multi-pattern arms (L4c2) contribute every
   pattern in their `:patterns` vector to coverage.

   F69 L4c3 — guarded arms (with `if <cond>`) do NOT contribute to
   coverage: the guard may be false at runtime, so an unguarded arm
   for that case or a wildcard is still required."
  [enum-name enum-cases arms node]
  (let [unguarded-arms (remove :guard arms)
        all-patterns   (mapcat arm-patterns unguarded-arms)
        has-wildcard?  (some #(= :wildcard (:kind %)) all-patterns)]
    (when-not has-wildcard?
      (let [covered  (set (keep #(when (= :case (:kind %)) (:name %))
                                all-patterns))
            declared (set enum-cases)
            missing  (clojure.set/difference declared covered)]
        (when (seq missing)
          (fail-nyi!
           (str "F69 L4c1: non-exhaustive `<:` dispatch on `" enum-name
                "` — case(s) " (pr-str (sort (vec missing)))
                " unhandled. Add an arm per missing case, or a `<: _:` "
                "wildcard tail (spec §3.7 exhaustiveness rule).")
           node))))))

(defn- scrut-ty->c-type
  "Return the C type string for a scrutinee's inferred type. Enum
   scrutinees carry the enum name (C typedef). Primitive scrutinees
   (Int, Bool) resolve via type-kw->c-type."
  [scrut-ty scrut-node]
  (cond
    (and (map? scrut-ty) (:enum scrut-ty)) (:enum scrut-ty)
    :else                                  (type-kw->c-type scrut-ty scrut-node)))

(defn- dispatch-scrutinee-supported?
  "L4b/c4 — the scrutinee-type gate. Enum (tagged-variant) is the
   L4b substrate; Int and Bool are the L4c4 primitive-literal
   substrates. Structural / agent scrutinees remain L4d."
  [scrut-ty]
  (or (and (map? scrut-ty) (:enum scrut-ty))
      (contains? #{:int :bool} scrut-ty)))

(defn- lower-dispatch-stmt
  "Statement-position `<:` dispatch. Binds the scrutinee to a local
   temporary and emits an `if / else if / else` chain over the arms.

   L4b substrate: tagged-variant (enum) scrutinees.
   L4c4 substrate: Int / Bool primitives (for literal patterns).
   Structural / agent scrutinees remain L4d.

   L4c1 exhaustiveness applies only to tagged-variant scrutinees;
   primitive scrutinees require a `<: *:` wildcard tail (open type)."
  [node ctx]
  (let [scrut-ty (infer-type (:scrutinee node) ctx)]
    (when-not (dispatch-scrutinee-supported? scrut-ty)
      (fail-nyi!
       (str "F69 L4b: `<:` dispatch scrutinee must be tagged-variant "
            "(enum) or a primitive supporting literal patterns (Int / "
            "Bool) — got " (pr-str scrut-ty) ". Structural + agent "
            "scrutinees land in L4d.")
       node))
    (let [enum?     (and (map? scrut-ty) (:enum scrut-ty))
          _         (when enum?
                      (let [enum-name  (:enum scrut-ty)
                            enum-cases (get-in ctx [:enum-env enum-name :cases])]
                        (check-dispatch-exhaustiveness! enum-name enum-cases
                                                        (:arms node) node)))
          _         (when-not enum?
                      ;; primitive scrutinee — spec §3.7 requires wildcard
                      ;; for open (non-closed-sum) types.
                      (when-not (some #(= :wildcard (:kind %))
                                      (mapcat arm-patterns (:arms node)))
                        (fail-nyi!
                         (str "F69 L4c4: non-exhaustive `<:` dispatch on "
                              "primitive `" (pr-str scrut-ty) "` — primitive "
                              "scrutinees require a `<: *:` wildcard tail "
                              "(spec §3.7 exhaustiveness — primitives are "
                              "open types).")
                         node)))
          scrut-tmp  "_cxc_scrut"
          scrut-lo   (lower-expr (:scrutinee node) ctx)
          scrut-ct   (scrut-ty->c-type scrut-ty (:scrutinee node))
          arms       (mapv #(lower-dispatch-arm scrut-ty scrut-tmp % ctx)
                           (:arms node))
          scrut-decl {:node :var-decl :name scrut-tmp
                      :c-type scrut-ct :rhs scrut-lo}
          if-chain   {:node :c-if-chain :branches arms}]
      {:node :c-scope
       :stmts [scrut-decl if-chain]})))

(defn- reference-tk?
  "F39 L7 — true iff a type-kw denotes a reference-shaped type
   (nil-carrying at the pointer level). Atoms, `String`, `Bytes`,
   `Any`, `Block`. Optional-boxed value types (`[:optional ...]`)
   are handled separately by [[optional-tk?]]."
  [tk]
  (or (contains? #{:string :bytes :any :block} tk)
      (and (map? tk) (:atom tk))))

(declare lower-body-stmts)

(defn- lower-if-let-stmt
  "F39 L7 (spec §3.6.1 lines 3693-3705) — lower `if let x = expr : then
   [else: else]` to a scoped C block. Each binding's RHS lowers to a
   fresh temporary; the temporary's presence gates a nested `if`; when
   present, the user-visible local binds the unwrapped value inside the
   true-branch scope. Multi-binding nests left-to-right; because C's `if`
   already short-circuits, later bindings' RHS aren't evaluated until
   earlier ones pass their guard. The else-block is textually
   duplicated once per binding depth — semantic impact is nil (only ONE
   fail-path fires per invocation), code-size impact scales as N * |else|.

   Two source shapes per binding, dispatched on the RHS's inferred
   type-kw:
     - reference-nullable (`Foo`, `String`, `Bytes`, `Any`, `Block`) →
       guard is `(tmp != nil)`; unwrapped local is a same-typed pointer.
     - Optional-boxed value (`[:optional inner]`) → guard is `tmp.present`;
       unwrapped local is the inner raw `T`.

   Reject at lower-time when the RHS isn't nullable — a straight scalar
   or `T!` reference has no nil case to unwrap, so `if let` is a
   category error. Diagnostic points at `??` or a plain conditional."
  [node ctx]
  (let [bindings (:bindings node)
        _        (when (empty? bindings)
                   (fail-compile! :cxc-f39-if-let-no-bindings
                                  "F39 L7: `if let` requires at least one binding."
                                  node))
        else-blk (:else node)
        ;; Lower each binding: [{name type-annot expr}] →
        ;;   {name tmp-name tk c-inner-type guard-src bind-src}
        prepare
        (fn [i b]
          (let [expr    (:expr b)
                ann     (:type b)
                ann-kw  (when ann
                          (cxc-type->type-kw (base-type-name ann)
                                             (:record-env ctx)
                                             (:atom-env ctx)
                                             (:enum-env ctx) ann))
                inferred (infer-type expr ctx)
                tk      (or ann-kw inferred)
                ref?    (reference-tk? tk)
                opt?    (optional-tk? tk)]
            (when-not (or ref? opt?)
              (fail-compile!
               :cxc-f39-if-let-non-nullable-rhs
               (str "F39 L7: `if let " (:name b) " = <expr>` requires a nullable "
                    "RHS (reference type — atom, String, Bytes, Any, Block — or "
                    "an Optional-boxed value type via bare `T`). Got `"
                    (pr-str tk) "`. Fix: for a scalar `T!` value use `let " (:name b)
                    " = <expr>` (plain binding, no guard); to force a fallback on a "
                    "nullable, use `<expr> ?? <default>`. See spec §3.6.1 lines 3693-3705.")
               b))
            (let [tmp     (str "__cxc_ifl_" i)
                  tmp-c   (type-kw->c-type tk expr)
                  inner-c (if opt?
                            (type-kw->c-type (unwrap-tk tk) expr)
                            tmp-c)
                  local-tk (if opt? (unwrap-tk tk) tk)]
              {:name    (:name b)
               :tmp     tmp
               :tk      tk
               :tmp-c   tmp-c
               :inner-c inner-c
               :opt?    opt?
               :ref?    ref?
               :local-tk local-tk
               :rhs-lo  (lower-expr expr ctx)})))
        prepped  (map-indexed prepare bindings)
        ;; Bind the unwrapped locals into ctx for the then-block lowering.
        then-ctx (reduce (fn [c p]
                           (assoc-in c [:locals (:name p)] (:local-tk p)))
                         ctx prepped)
        then-lo  (lower-body-stmts (:stmts (:then node)) then-ctx)
        else-lo  (when else-blk
                   (lower-body-stmts (:stmts else-blk) ctx))
        ;; Build the nested-if structure bottom-up. Innermost then-body
        ;; is the lowered user-then; each layer wraps with `if (guard) {
        ;; bind; INNER } else { <else-lo> }`. When there is no else, we
        ;; omit the else branch (no else-block at all — falls through
        ;; silently, per plan L7 test "positive no-else").
        make-guard
        (fn [p]
          (if (:opt? p)
            {:node :field
             :target {:node :name :sym (:tmp p)}
             :field  "present"}
            {:node :binop
             :op    "!="
             :left  {:node :name :sym (:tmp p)}
             :right {:node :nil}}))
        make-bind
        (fn [p]
          {:node   :var-decl
           :name   (:name p)
           :c-type (:inner-c p)
           :rhs    (if (:opt? p)
                     {:node :field
                      :target {:node :name :sym (:tmp p)}
                      :field  "value"}
                     {:node :name :sym (:tmp p)})})
        ;; Layer each binding's temp-decl inside the previous guard so
        ;; later RHS aren't evaluated when an earlier binding is nil
        ;; (short-circuit — plan L7 "Positive short-circuit" test).
        wrap-layer
        (fn [inner-body p]
          (let [branches (cond-> [{:cond (make-guard p)
                                   :body (into [(make-bind p)] inner-body)}]
                           else-lo (conj {:cond nil :body else-lo}))
                if-stmt  {:node :c-if-chain :branches branches}
                tmp-decl {:node :var-decl
                          :name (:tmp p) :c-type (:tmp-c p) :rhs (:rhs-lo p)}]
            ;; Wrap tmp-decl + if in a fresh scope so the temp is
            ;; hidden from sibling stmts.
            [{:node :c-scope :stmts [tmp-decl if-stmt]}]))
        nested-stmts (reduce wrap-layer then-lo (reverse prepped))]
    ;; nested-stmts is a one-element vector holding the outermost :c-scope.
    (first nested-stmts)))

(defn- lower-stmt [node ctx]
  (case (:node node)
    :expr-stmt (lower-expr (:expr node) ctx)
    :emit      (lower-send node ctx)
    :return    {:node :return :value (lower-expr (:expr node) ctx)}
    :dispatch  (lower-dispatch-stmt node ctx)

    :if-let    (lower-if-let-stmt node ctx)

    ;; F17 L1 — statement-position `while <cond>: <body>`. Body lowers
    ;; as a stmt sequence via `lower-body-stmts` so nested `let`
    ;; bindings inside the loop scope through the reduce. Loop-depth
    ;; is stamped on ctx as `:in-loop?` for the recursive lowering so
    ;; break/continue can validate they're inside a loop.
    :while-stmt
    (let [body-ctx (assoc ctx :in-loop? true)
          _        (assert-not-optional-guard!
                    (:cond node) (infer-type (:cond node) ctx)
                    "`while` cond" (:cond node))
          body-stmts (lower-body-stmts (:stmts (:body node)) body-ctx)]
      {:node :c-while
       :cond (lower-expr (:cond node) ctx)
       :body body-stmts})

    :break-stmt
    (do (when-not (:in-loop? ctx)
          (fail-nyi!
           "F17 L1: `break` outside a `while` body — legal only inside a loop."
           node))
        {:node :c-break})

    :continue-stmt
    (do (when-not (:in-loop? ctx)
          (fail-nyi!
           "F17 L1: `continue` outside a `while` body — legal only inside a loop."
           node))
        {:node :c-continue})

    (fail-nyi! (str "statement " (:node node)) node)))

(defn- lower-let-stmt
  "F18 L1 — lower a `let <name> [: <type>] = <expr>` statement into a
   C `:var-decl`. If a type annotation is present, use it authoritatively
   (its type-kw / c-type override inference on the RHS); otherwise infer
   from the RHS expression. Returns `[decl ctx']` where `ctx'` has the
   new local bound so subsequent statements resolve the name."
  [node ctx]
  (let [expr    (:expr node)
        ann     (:type node)
        ann-kw  (when ann (cxc-type->type-kw (base-type-name ann)
                                             (:record-env ctx)
                                             (:atom-env ctx)
                                             (:enum-env ctx) ann))
        tk      (or ann-kw (infer-type expr ctx))
        ct      (if ann
                  (cxc-type->c ann (:record-env ctx)
                               (:atom-env ctx)
                               (:enum-env ctx))
                  (type-kw->c-type tk expr))
        rhs-lo  (lower-expr expr ctx)
        decl    {:node :var-decl :name (:name node) :c-type ct :rhs rhs-lo}]
    [decl (update ctx :locals assoc (:name node) tk)]))

(defn- lower-body-stmts
  "Lower a sequence of statements, threading ctx so an F18 L1
   `:let-stmt` mid-sequence becomes visible to subsequent stmts.
   Shared by lower-do-clause and method-body / lambda-body lowering
   so `let` is uniformly available anywhere a statement is."
  [stmts ctx]
  (first
   (reduce (fn [[acc c] node]
             (if (= :let-stmt (:node node))
               (let [[decl c'] (lower-let-stmt node c)]
                 [(conj acc decl) c'])
               [(conj acc (lower-stmt node c)) c]))
           [[] ctx]
           stmts)))

(defn- lower-do-clause [clause ctx]
  (lower-body-stmts (:stmts clause) ctx))

(declare lower-then-update lower-invariant-check)

(defn- lower-transition-body [clauses ctx]
  (let [[stmts _]
        (reduce
          (fn [[out c] clause]
            (case (:node clause)
              :do-clause
              [(into out (lower-do-clause clause c)) c]

              ;; Layer 6 Step 12 — `then:` on general transitions
              ;; (previously restricted to on-enter-body paths).
              ;; F70 I3 — `:then-clause` now carries both `:updates`
              ;; (field commits — assignments) and `:stmts`
              ;; (side-effect stmts — same shape as retired `do:`).
              ;; Emit updates first, then stmts (updates are "state
              ;; changes"; stmts are "side effects").
              :then-clause
              [(-> out
                   (into (mapcat #(lower-then-update % c) (:updates clause)))
                   (into (lower-body-stmts (:stmts clause) c))) c]

              ;; `:let-clause` retired (F18 L1' Landing 2, 2026-08-24).
              ;; The parser can no longer produce it — statement-form
              ;; `let x = expr` inside a `then:` clause's `:stmts` is
              ;; the successor and lowers via `lower-body-stmts`.

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

(defn- self-field-info
  "F39 L5b-ii-c helper — look up a field record on the enclosing atom
   in ctx. Returns nil when self isn't an atom in scope (e.g. record
   method context or free position). Callers use this to check
   `:optional?` / `:c-type` for Optional wrap/unwrap decisions."
  [field-name ctx]
  (let [self-info (get-in ctx [:locals "self"])
        atom-name (and (map? self-info) (:atom self-info))
        info      (get-in ctx [:atom-env atom-name])]
    (get-in info [:field-index field-name])))

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
   a vector of stmts so callers `mapcat` naturally over each update.

   F8 L3a (setter-error diagnostic) — computed fields (observables
   introduced via `computed:` or declared at class body top level)
   are read-only: writes have no storage backing and would emit a
   dead `__cxc_next._<name>` assignment. Reject with a targeted
   diagnostic naming the field, its owning atom, and the read-only
   nature (spec §4.10.4.1)."
  [update ctx]
  (let [;; F8 L3a — reject writes to observable (computed) fields
        ;; before they emit dead assignments. Check happens first
        ;; so the diagnostic wins over any downstream nil-friendly
        ;; behavior.
        self-info (get-in ctx [:locals "self"])
        atom-name (and (map? self-info) (:atom self-info))
        obs-info  (when atom-name
                    (get-in ctx [:atom-env atom-name :observable-index (:field update)]))
        field-in-atom-index (when atom-name
                              (get-in ctx [:atom-env atom-name :field-index (:field update)]))
        _         (when (and obs-info (not field-in-atom-index))
                    (fail-nyi!
                     (str "F8 L3a: `" (:field update) "` is a computed "
                          "field (observable) on atom `" atom-name
                          "` — writes not allowed (spec §4.10.4.1 read-"
                          "only Computed contract). Move the derivation "
                          "to `data:` if you need a storage-backed field, "
                          "or remove the assignment.")
                     update))
        ;; F39 L5b-ii-c (Sub-K) — when the target field's storage is
        ;; Optional-boxed, wrap a scalar RHS in `{present:1, .value:x}`.
        ;; If RHS is already Optional-typed, pass through unchanged
        ;; (Optional-to-Optional assign is a struct copy). If RHS is a
        ;; nil literal, emit `{present:0}` instead. Dead until Sub-P.
        f-info    (self-field-info (:field update) ctx)
        wrap?     (:optional? f-info)
        rhs-expr  (:expr update)
        ;; F39 L5b-ii-d — inspect RHS type unconditionally so we can
        ;; also fire runtime unwrap-check when the field is `T!` value-
        ;; typed (`nonnil?` true, `:optional?` false) but the RHS is
        ;; Optional. The check runs pre-store since the field storage
        ;; is scalar and can't hold the Optional envelope.
        rhs-tk        (infer-type rhs-expr ctx)
        rhs-nil?      (= :nil rhs-tk)
        rhs-opt?      (optional-tk? rhs-tk)
        lowered       (lower-expr rhs-expr ctx)
        value-nonnil? (and (:nonnil? f-info)
                           (not (:optional? f-info))
                           rhs-opt?)
        self-info     (get-in ctx [:locals "self"])
        atom-name     (or (and (map? self-info) (:atom self-info)) "?")
        ;; F39 L5b-ii-d — when RHS is Optional feeding a T! value field,
        ;; unwrap with a runtime presence check via GNU stmt-expr. Same
        ;; warning-grade nonnil-violation as the reference `T!` path;
        ;; runtime helper writes to stderr and returns (does NOT abort),
        ;; matching L3's post-store discipline.
        unwrap-src    (when value-nonnil?
                        {:node :c-value-nonnil-unwrap
                         :inner lowered
                         :site  (str "def-atom `" atom-name
                                     "` field write (value-typed T!)")
                         :name  (:field update)})
        rhs-final (cond
                    value-nonnil? unwrap-src
                    (not wrap?)   lowered
                    rhs-opt?      lowered
                    rhs-nil?      {:node :struct-lit
                                   :type (:c-type f-info)
                                   :fields [{:name "present"
                                             :value {:node :int :value 0}}]}
                    :else         {:node :struct-lit
                                   :type (:c-type f-info)
                                   :fields [{:name "present"
                                             :value {:node :int :value 1}}
                                            {:name "value"
                                             :value lowered}]})
        ;; F33 L3.b — when `:snapshot-name` is in ctx (i.e. we're
        ;; inside a receive-glyph transition body) AND the atom has
        ;; the bag prelude, redirect ivar writes to `__cxc_next
        ;; ._<field>`. A trailing commit sequence (added by
        ;; `lower-transition-branch` for non-slot-setter fields)
        ;; copies the bag back to ivars at the end of the body,
        ;; producing the same observable ivar state atomically at
        ;; commit rather than field-by-field during the body.
        ;; Slot-setter path is unchanged: subscription lifecycle
        ;; needs the ivar written NOW, and it manages its own atomic
        ;; swap on the affected ivar.
        bag-write? (and (:snapshot-name ctx)
                        (not (field-needs-slot-setter? (:field update) ctx)))
        primary (cond
                  (field-needs-slot-setter? (:field update) ctx)
                  {:node :objc-msg
                   :recv {:node :name :sym "self"}
                   :selector (str "set" (capitalize-first (:field update)) ":")
                   :args [rhs-final]}

                  bag-write?
                  {:node :assign
                   :lhs  {:node :field
                          :target {:node :name :sym "__cxc_next"}
                          :field  (str "_" (:field update))}
                   :rhs  rhs-final}

                  :else
                  {:node :assign
                   :lhs  {:node :name :sym (str "_" (:field update))}
                   :rhs  rhs-final})]
    (if (field-nonnil? (:field update) ctx)
      (let [self-info (get-in ctx [:locals "self"])
            atom-name (or (and (map? self-info) (:atom self-info)) "?")
            ;; The nonnil check needs to see the value we just wrote.
            ;; Under L3.b bag-writes, the ivar is stale until commit —
            ;; read the bag field so the check reflects the intended
            ;; new value. Slot-setter and legacy raw-ivar writes read
            ;; the ivar (its value is the just-written one).
            checked-expr (if bag-write?
                           (str "__cxc_next._" (:field update))
                           (str "_" (:field update)))]
        [primary
         (nonnil-check-stmt checked-expr
                            (str "def-atom `" atom-name "` field write")
                            (:field update))])
      [primary])))

(defn- lower-on-enter-body
  "Body of a wildcard on-enter transition when inlined into init.
   Same shape as a general transition body: `:then-clause` carries
   both `:updates` (field commits) and `:stmts` (side effects); the
   `:let-clause` sub-block retired at F18 L1' Landing 2 (2026-08-24),
   `:do-clause` retired at F70 I3."
  [clauses ctx]
  (let [[stmts _]
        (reduce
          (fn [[out c] clause]
            (case (:node clause)
              ;; F70 I3 — see lower-transition-body comment for split.
              :then-clause
              [(-> out
                   (into (mapcat #(lower-then-update % c) (:updates clause)))
                   (into (lower-body-stmts (:stmts clause) c))) c]

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
        middle    (remove #{"init" "end" "self" "*"} (distinct mentioned))]
    (vec (concat ["init"] middle ["end"]))))

(defn- phase-constant-name
  "Canonicalized ObjC constant name for a phase: `k<Atom>_phase_<name>`.
   Every read/write in emitted code references one of these — never a
   raw literal — so pointer-equality comparisons are safe (spec §5)."
  [atom-name phase-name]
  (str "k" atom-name "_phase_" phase-name))

(defn- wildcard-on-enter?
  "A transition with implicit source and no message receipt —
   the `-> phase then/do: ...` form that fires on every entry."
  [t]
  (and (= :implicit (get-in t [:source :kind]))
       (nil? (:receive t))))

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
  "Post-commit side-effect stmts from all wildcards targeting phase
   X, in order. Fires BEFORE the specific transition's post-commit
   stmts — wildcard's side effects establish invariants of the
   phase, then the specific transition layers its own on top.

   F70 I3 — collects side-effect stmts from BOTH `:do-clause` bodies
   (legacy) and `:then-clause` `:stmts` (I3 unified). Walks
   wildcard body-clauses in source order so ordering across the
   two kinds is preserved."
  [wildcards ctx]
  (vec (mapcat (fn [w]
                 (mapcat (fn [c]
                           (case (:node c)
                             :do-clause   (lower-do-clause c ctx)
                             :then-clause (lower-body-stmts (:stmts c) ctx)
                             nil))
                         (:body w)))
               wildcards)))

(defn- lower-transition-branch
  "Lower one transition into a `:transition-branch` — an `if` block on
   the source phase (+ combined arm-head `|:` propositions) that runs
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

   Payload names in `msg{k: T}` bind as locals visible in `|:`/then:/do:
   (design §12). Their type-kws land in the ctx locals so infer-type
   resolves references, and lower-expr emits them as `{:node :name :sym k}`."
  [atom-name t ctx wildcards-by-phase]
  (when-not (contains? #{:phase :wildcard} (get-in t [:source :kind]))
    (fail-nyi! (str "message-triggered transition on def-atom `" atom-name
                    "` must have an explicit source phase or `*` wildcard "
                    "(got " (get-in t [:source :kind]) ")")
               t))
  (let [wildcard-source? (= :wildcard (get-in t [:source :kind]))
        source-name  (get-in t [:source :name])
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
        body-clauses (:body t)
        then-clauses (filter #(= :then-clause (:node %)) body-clauses)
        do-clauses   (filter #(= :do-clause   (:node %)) body-clauses)
        propositions (filter #(= :proposition (:node %)) body-clauses)
        ;; F18 L1' Landing 2 (2026-08-24) — the transition `let:`
        ;; sub-block retired; statement-form `let x = expr` inside a
        ;; `then:` clause's `:stmts` is the successor. No pre-body
        ;; local-decls anymore — let-statements lower via
        ;; `lower-body-stmts` at the position they appear.
        let-decls    []
        ctx'         ctx'
        guard-exprs  (mapv :predicate propositions)
        _            (doseq [g guard-exprs]
                       (assert-not-optional-guard! g (infer-type g ctx')
                                                   "arm-head `|:` proposition" g))
        guard-lo     (when (seq guard-exprs)
                       ;; Every proposition at the arm head must hold.
                       ;; Normalize them to the emitter's single guard.
                       (reduce (fn [acc e]
                                 {:node :binop :op "&&"
                                  :left acc :right (lower-expr e ctx')})
                               (lower-expr (first guard-exprs) ctx')
                               (rest guard-exprs)))
        then-stmts   (vec (mapcat (fn [c] (mapcat #(lower-then-update % ctx')
                                                  (:updates c)))
                                  then-clauses))
        ;; F70 I3 — post-commit side-effect stmts come from BOTH
        ;; `:do-clause` bodies (legacy) and `:then-clause` `:stmts`
        ;; (I3 unified). Walk body-clauses in source order so
        ;; ordering across the two kinds is preserved.
        ;;
        ;; F33 L3.d — `do:` runs after the commit. Its reads should
        ;; observe the just-committed live ivars, not the pre-
        ;; transition snapshot. Drop `:snapshot-name` from the ctx
        ;; passed to `lower-do-clause` / `lower-body-stmts` for
        ;; :then-clause `:stmts` so bare-ident reads fall back to
        ;; raw ivars. `then:` `:updates` above still use ctx' (with
        ;; snapshot) since they compute against pre-transition state.
        ctx-post-commit (dissoc ctx' :snapshot-name)
        do-stmts     (vec (mapcat (fn [c]
                                    (case (:node c)
                                      :do-clause   (lower-do-clause c ctx-post-commit)
                                      :then-clause (lower-body-stmts (:stmts c) ctx-post-commit)
                                      nil))
                                  body-clauses))
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
        ;; F33 L3.d — wildcard do: (inlined here) runs post-commit
        ;; alongside the specific do: stmts; reads should see live
        ;; ivars (drop `:snapshot-name`).
        w-do-stmts   (inline-wildcard-do-stmts   target-wildcards ctx-post-commit)
        ;; target=end routes through -transitionToEnd (design §7.3);
        ;; that method owns phase-set, `-> end` wildcard, cascade,
        ;; parent notify. Audit F26: spec §1.11 line 1281 —
        ;; *"`end` transitions may have arm propositions and `do:` blocks;
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
        ;; on its own trajectory. Under F33 L3.c, this call is
        ;; suppressed when the branch runs the pre-commit inline
        ;; checks below (snapshot active); the atom's `checkInvariants`
        ;; method still exists and is still called from
        ;; `cxc_runtimeBinding` (construction) + F49 method wrappers.
        check-invariants-stmt {:node :objc-msg
                               :recv {:node :name :sym "self"}
                               :selector "checkInvariants"
                               :args []}
        ;; F33 L3.b — commit the next-state bag to ivars atomically
        ;; after all `then:` writes, before phase update and side
        ;; effects. Slot-setter fields are excluded (they wrote
        ;; through the setter directly in `lower-then-update` and
        ;; would be clobbered here). Emitted only when the branch is
        ;; inside a snapshotted transition (ctx `:snapshot-name`
        ;; set — same predicate as the prelude emission) AND the
        ;; atom has data-fields to commit.
        data-fields    (get-in ctx [:atom-env atom-name :fields])
        commit-fields  (when (:snapshot-name ctx)
                         (filterv (fn [f]
                                    (not (field-needs-slot-setter?
                                           (:name f) ctx')))
                                  data-fields))
        commit-stmt    (when (seq commit-fields)
                         {:node         :commit-next-state
                          ;; F32 L4.c uses this for `sizeof(<Cls>_State)`
                          ;; under `!CXC_SINGLE_MAIN_QUEUE`; the state
                          ;; struct is named after the emitted ObjC class,
                          ;; which is `CxcMain` for `def main` (not
                          ;; `main`, which would collide with C's main()).
                          ;; Pass the class name explicitly so main atom's
                          ;; concurrent-mode commit references the correct
                          ;; typedef.
                          :atom-name    atom-name
                          :class-name   (class-name-for-atom atom-name)
                          :data-fields  commit-fields})
        ;; F33 L3.c — pre-commit invariant check on the `__cxc_next`
        ;; bag (decisions-frozen §8). Re-lower the atom's invariants
        ;; with `:snapshot-name` bound to `"__cxc_next"` so bare
        ;; field-name reads route to the bag (`__cxc_next._<field>`)
        ;; rather than the pre-transition snapshot (`__cxc_snap.…`) or
        ;; live ivars. Placed BEFORE the commit sequence so a failing
        ;; invariant aborts before any ivar write is visible —
        ;; matches F49 pre-store abort semantics: atom stays in the
        ;; pre-transition state on invariant failure. The post-commit
        ;; `[self checkInvariants]` call is suppressed for
        ;; snapshotted branches because the inline pre-commit check
        ;; covers the same contract with correct rollback semantics.
        ;; Only fires when the branch is snapshotted (data-fields
        ;; exist) AND the atom has invariants; atoms without
        ;; snapshots keep the old post-commit `checkInvariants` call.
        pre-commit-invariants
        (when (and (:snapshot-name ctx)
                   (seq (get-in ctx [:atom-env atom-name :invariants])))
          (let [invs    (get-in ctx [:atom-env atom-name :invariants])
                bag-ctx (assoc ctx :snapshot-name "__cxc_next")]
            (vec (map-indexed
                  (fn [i p]
                    (lower-invariant-check atom-name i p bag-ctx))
                  invs))))
        raw-body     (cond-> []
                       ;; F23 — let: bindings sit at the top of the
                       ;; body so they're in scope for propositions+then+do.
                       (seq let-decls)    (into let-decls)
                       (seq w-then-stmts) (into w-then-stmts)
                       (seq then-stmts)   (into then-stmts)
                       ;; F33 L3.c: invariant check on bag before commit.
                       (seq pre-commit-invariants) (into pre-commit-invariants)
                       commit-stmt        (conj commit-stmt)
                       phase-update       (conj phase-update)
                       (seq w-do-stmts)   (into w-do-stmts)
                       (seq do-stmts)     (into do-stmts)
                       ;; F33 L3.c: post-commit `[self checkInvariants]`
                       ;; only when no snapshot (atoms without
                       ;; data-fields fall back to the old path; the
                       ;; call is a no-op there when no invariants
                       ;; exist, and a safety net when they do).
                       (not (:snapshot-name ctx))
                       (conj check-invariants-stmt))
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
     :source-phase-const (when-not wildcard-source?
                           (phase-constant-name atom-name source-name))
     :wildcard-source? wildcard-source?
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
        source-field (get-in msg [:source :target :name])
        payload      (or (:payload msg) [])
        source-info  (get-in ctx [:atom-env source-class])
        _            (when-not source-info
                       (fail-nyi! (str "receive-glyph source `" source-class
                                       "` is not a def-atom in the program")
                                  t))
        stream       (get-in source-info [:stream-index stream-name])
        companion    (when-not stream
                       (get-in source-info [:companion-index stream-name]))
        _            (when-not (or stream companion)
                       (fail-nyi! (str "atom `" source-class "` emits no `:> "
                                       stream-name "` stream and has no "
                                       "`" stream-name "` companion message — "
                                       "receive-glyph `" source-field "."
                                       stream-name "` on `" atom-name
                                       "` has no source")
                                  t))
        ;; L12b — companion demand-driven augmentation runs before
        ;; class synthesis, so subscribed companions appear in
        ;; :stream-index (`stream` truthy). The companion-index
        ;; fallthrough above stays for the miss-diagnostic only:
        ;; unsubscribed companions never land in stream-index, so an
        ;; isolated `<: field.msg_failed(...)` with no matching
        ;; declaration on the target still reports "no stream and no
        ;; companion" cleanly.
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
        ;; F39 L5b-ii-c — subscriber-declared bare `T` matches source
        ;; that emits `[:optional T]`: bare T is nullable by design, and
        ;; Optional<T> is bare T's post-flip storage. Compare inner shape
        ;; after normalizing away Optional-wrapping on either side.
        source-typed (get-in source-info [:stream-payload-index stream-name])
        _            (when (seq payload)
                       (doseq [[sub-p src-p] (map vector payload source-typed)]
                         (let [sub-ty (cxc-type->type-kw
                                       (base-type-name (:type sub-p))
                                       (:record-env ctx) (:atom-env ctx)
                                       (:enum-env ctx) sub-p)
                               src-ty (:type-kw src-p)]
                           (when (and (not= sub-ty src-ty)
                                      (not= (unwrap-tk sub-ty)
                                            (unwrap-tk src-ty)))
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
                              ;; F67 L4b — receive-glyph method-stream source
                              ;; may be either a method (legacy) or an
                              ;; observable. Observables that wrap a bare
                              ;; ffi-builtin call (e.g. Clock.every wrapping
                              ;; native_clock_every) inherit the
                              ;; parametric `Stream of T` structure from
                              ;; the ffi-builtin table's `:return-type-node`
                              ;; entry — no annotation on the observable
                              ;; header needed.
                              method-info  (get-in source-info [:method-index method-nm])
                              obs-node     (when-not method-info
                                             (first (filter #(= method-nm (:name %))
                                                            (:observables source-info))))
                              obs-native-ret-node
                              (when obs-node
                                (let [body (:expr obs-node)]
                                  (when (and body
                                             (= :call (:node body))
                                             (= :ident (:node (:callee body)))
                                             (ffi-builtin? (:name (:callee body))))
                                    (:return-type-node
                                     (get ffi-builtins (:name (:callee body)))))))
                              _            (when-not (or method-info
                                                         obs-native-ret-node)
                                             (fail-nyi!
                                              (str "atom `" source-class "` has no `"
                                                   method-nm "()` method or observable "
                                                   "with a resolvable `Stream of T` "
                                                   "return type — receive-glyph `"
                                                   field-nm "." method-nm "()` on `"
                                                   atom-name "` has no source. "
                                                   "Observable-body must be a bare call "
                                                   "to a `Stream`-returning ffi-builtin "
                                                   "for the receive-glyph resolver to "
                                                   "derive the payload type.")
                                              src))
                              return-node  (or (:cxc-return-ty-node method-info)
                                               obs-native-ret-node)
                              return-ty    (or (:cxc-return-ty method-info)
                                               (case (:node return-node)
                                                 :type-ref      (:name return-node)
                                                 :type-of       (:outer return-node)
                                                 nil))
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
                                                    (:enum-env ctx))
                                           ;; F15 L2.c.iii.c.δ — preserve raw :type
                                           ;; so `collect-shapes` on the lowered
                                           ;; :classes discovers any :type-named-tuple
                                           ;; at a receive-payload param position.
                                           :type   (:type p)})
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
                           ;; F33 L2.b/c — snapshot init at method entry
                           ;; + reroute of data-field reads through the
                           ;; snapshot. L2.b emits the init prelude;
                           ;; L2.c flips `_field` reads to `__cxc_snap
                           ;; ._field` inside branch bodies by threading
                           ;; `:snapshot-name` in the ctx passed to
                           ;; `lower-transition-branch`. Writes still go
                           ;; through raw `_field` (unchanged path in
                           ;; `lower-then-update`). No snapshot ctx when
                           ;; the atom has no user data-fields — the
                           ;; struct type doesn't exist to reference.
                           data-fields (get-in ctx [:atom-env atom-name :fields])
                           snap-stmt   (when (seq data-fields)
                                         {:node        :state-snapshot-init
                                          :atom-name   atom-name
                                          :class-name  (class-name-for-atom atom-name)
                                          :data-fields data-fields})
                           ;; F33 L3.a — next-state bag init (dead code
                           ;; at L3.a). Lives alongside the snapshot in
                           ;; the prelude; L3.b flips `then:` writes to
                           ;; target `__cxc_next._<field>` and inserts
                           ;; a commit sequence, and L3.c/d layer on
                           ;; the invariant + do-clause changes.
                           next-stmt   (when (seq data-fields)
                                         {:node       :next-state-init
                                          :atom-name  atom-name
                                          :class-name (class-name-for-atom atom-name)})
                           branch-ctx  (cond-> ctx
                                         snap-stmt (assoc :snapshot-name "__cxc_snap"))
                           raw-branches (mapv #(lower-transition-branch
                                                atom-name (:txn %) branch-ctx
                                                wildcards-by-phase)
                                              group)
                           ;; L10.b (2026-08-25) — sort branches so
                           ;; concrete-source arms precede wildcard-source
                           ;; arms. Preserves declaration order within
                           ;; each partition (stable sort). First-match
                           ;; dispatch then honors concrete-over-*
                           ;; precedence.
                           branches
                           (vec (concat
                                 (filterv (complement :wildcard-source?) raw-branches)
                                 (filterv :wildcard-source? raw-branches)))
                           has-wildcard-source? (some :wildcard-source? branches)
                           ;; L13 (2026-08-25) — coverage tail-abort per
                           ;; source-phase. For every phase that has at
                           ;; least one branch AND every branch in that
                           ;; phase carries a `:guard`, append a synthetic
                           ;; guardless branch that runs after the guarded
                           ;; ones and panics with a coverage diagnostic.
                           ;; Prevents the silent-drop behavior inherited
                           ;; from the retired `when:` guards (L2c). If a
                           ;; phase already has a guardless arm, that arm
                           ;; fires first — no tail-abort needed for it.
                           ;;
                           ;; L10.b interaction: when a wildcard-source
                           ;; arm exists on this dispatch, it fires in
                           ;; any phase not caught by concrete arms — the
                           ;; wildcard itself is the coverage floor, so
                           ;; suppress the per-phase tail-abort. Coverage
                           ;; on the wildcard arm's own `|:` (if any)
                           ;; still applies at the tail via a global
                           ;; abort, added below.
                           coverage-tails
                           (let [by-phase (group-by :source-phase-const branches)
                                 msg-name (get-in first-d [:selector])]
                             (cond
                               has-wildcard-source?
                               ;; Wildcard covers concrete-phase gaps; if
                               ;; the wildcard arm itself has a `|:`, add
                               ;; ONE final abort to catch the miss.
                               (let [wild-branches (filterv :wildcard-source? branches)]
                                 (if (every? :guard wild-branches)
                                   [{:node               :transition-branch
                                     :source-phase-const nil
                                     :wildcard-source?   true
                                     :guard              nil
                                     :body               [{:node        :coverage-abort
                                                           :msg-selector msg-name
                                                           :phase-const  "any"}]}]
                                   []))
                               :else
                               (vec (mapcat
                                     (fn [[phase-const phase-branches]]
                                       (when (and phase-const
                                                  (seq phase-branches)
                                                  (every? :guard phase-branches))
                                         [{:node               :transition-branch
                                           :source-phase-const phase-const
                                           :guard              nil
                                           :body               [{:node        :coverage-abort
                                                                 :msg-selector msg-name
                                                                 :phase-const  phase-const}]}]))
                                     by-phase))))
                           branches (into branches coverage-tails)]
                       (cond-> {:node        :objc-method
                                :selector    (:selector first-d)
                                :return-type "void"
                                :params      method-params
                                :body        branches}
                         snap-stmt (assoc :prelude
                                          (cond-> [snap-stmt]
                                            next-stmt (conj next-stmt))))))
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
   inside receive-glyph bodies (design §9/§12 payload binding).

   Track A/1 (2026-08-26) — bareword payload entries (`reason:` without
   a type) are materialized at parse time as `{:type {:node :type-ref
   :name \"Any\"}}`, which resolves here to id at the wire — no branch
   needed. Companion subscribers use this to unpack via a `<:` case-
   pattern block in the body instead of a declared type."
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

;; ---------------------------------------------------------------------------
;; Track A/2.a (2026-08-26) — reason-box wrapper class per user record.
;;
;; Records lower as C structs (value semantics; unchanged). Companion
;; reason dispatch (`<msg>_failed(reason:) …  <: reason: <: MyError(…): …`)
;; needs a runtime-identity carrier so `isKindOfClass:` can discriminate.
;; This lands the minimum machinery: one `_Cxc_R_<Record>` @interface +
;; @implementation per user-declared record, holding the struct as a
;; public ivar. Later tracks:
;;
;;   A/4 boxes at the `|:`-failure site + throws NSException carrying
;;       the wrapper in `userInfo[@"reason"]`.
;;   A/5 flips the L12c emit to pass the wrapper (not nil) through
;;       `cxc_emit_<msg>_failedWithreason:`.
;;   A/2.b lifts F69 L4b to accept :any scrutinees and emits
;;       `isKindOfClass:` checks against these wrappers.
;;
;; The wrapper is compile-time synthesis only; users never write it
;; and can't reference `_Cxc_R_*` names directly (leading underscore
;; is reserved). Naming uses the post-rename record name so prelude
;; records get the mangled prefix uniformly (`_Cxc_R__Cxc_Position`).
;; Later tree-shaking (A/7-adjacent) can restrict synthesis to records
;; that actually appear in a `|: as T` clause; for A/2.a we emit
;; unconditionally so the machinery is available whenever a receiver
;; wants to dispatch against a record type.
;; ---------------------------------------------------------------------------

(defn- reason-box-name
  "Wrapper class name for a record. Prefix `_Cxc_R_` is compile-time
   reserved — leading underscore is off-limits to user identifiers."
  [record-name]
  (str "_Cxc_R_" record-name))

(defn- synth-reason-box
  "Build a `:reason-box` node describing the wrapper class for one
   record. Emit consumes it as an `@interface X : NSObject { @public
   T value; } @end / @implementation X @end` pair."
  [record-node]
  {:node          :reason-box
   :name          (reason-box-name (:name record-node))
   :struct-c-type (:name record-node)})

;; F39 Landing 5b-ii-a (2026-08-21) — Optional-boxed value-type
;; infrastructure. Under the unified rule (spec §3.6.1, design premise
;; 1 of plans/g3-structural-canonical/f39-nullability.md) bare `T` on a value type is nullable
;; at every position. The C representation carrying nil is a
;; `_Cxc_Optional_<T> { uint8_t present; T value; }` struct.
;;
;; L5b-ii-a lands the collection + synthesis + name-mangling
;; machinery as DEAD CODE — no field yet triggers Optional storage.
;; L5b-ii-b flips `cxc-type->c :type-ref` to consult this machinery.
;; Isolated here so the flip is a one-place change rather than a
;; scattered rewrite.

(defn optional-type-name
  "Mangle a value-type name into its Optional-boxed C typedef name.
   `Int` → `_Cxc_Optional_Int64` (Int aliases Int64 per §3.1). Other
   primitives pass through unchanged (`Int32` → `_Cxc_Optional_Int32`).
   Records / enums pass through (`Position` → `_Cxc_Optional_Position`).

   For parametric types (`List of T`) L5b-ii-a stays scalar-only —
   containers are reference-shaped and already nil-carrying; they
   never Optional-box. This helper only runs on names that reached
   the value-type branch of `cxc-type->c`."
  [cxc-ty-name]
  (let [canonical (if (= "Int" cxc-ty-name) "Int64" cxc-ty-name)]
    (str "_Cxc_Optional_" canonical)))

(defn- collect-optional-types
  "L5b-ii-b (2026-08-22): scan record + atom envs for fields whose
   storage was flipped to `_Cxc_Optional_<T>` (i.e. `:optional? true`)
   and return the set of inner T names that need a typedef.
   `fields-from-data-section` sets the flag when a bare-T value-typed
   field lands storage. Returned names feed `synth-optional-typedefs`.

   Atom envs share the same fields shape when the atom has a
   data-section — those get their fields via the same helper."
  [record-env atom-env _enum-env]
  (let [rec-fields  (mapcat :fields (vals record-env))
        atom-fields (mapcat (fn [info]
                              (or (:fields info) []))
                            (vals atom-env))
        field-set   (into #{}
                          (comp (filter :optional?)
                                (map :cxc-ty))
                          (concat rec-fields atom-fields))]
    ;; F39 L5b-ii-c — ord binops (`x.n > 0`) on Optional operands produce
    ;; Optional<Bool>, which needs the `_Cxc_Optional_Bool` typedef even
    ;; when no field is declared Bool. Cheap to include unconditionally
    ;; whenever any Optional field exists.
    (if (seq field-set)
      (conj field-set "Bool")
      field-set)))

(defn- synth-optional-typedef
  "Produce a `:c-struct` for `_Cxc_Optional_<T>`. Layout is presence-
   byte-first per §3.10.10 wire encoding — the `.present` field IS
   the first canonical byte, avoiding a conversion pass for
   `.as() to Bytes` (L9). C99 designated initializers construct
   instances at wrap sites via the existing `:struct-lit` emit path
   (no new emitter code needed).

   `t` is the source cxc type name (e.g. `\"Int\"`, `\"Position\"`);
   ctx supplies the envs needed to resolve `t`'s raw C type."
  [t ctx]
  (let [c-value-type (cxc-type->c {:node :type-ref :name t}
                                  (:record-env ctx)
                                  (:atom-env   ctx)
                                  (:enum-env   ctx))]
    {:node   :c-struct
     :name   (optional-type-name t)
     :fields [{:name "present" :c-type "uint8_t"}
              {:name "value"   :c-type c-value-type}]}))

(defn- synth-optional-typedefs
  "Emit one `_Cxc_Optional_<T>` typedef per T in `optional-types`.
   Called from `lower` after record/enum/atom envs are built so
   `cxc-type->c` can resolve the inner T. Under L5b-ii-a `optional-
   types` is always empty; under L5b-ii-b it becomes non-empty."
  [optional-types ctx]
  (mapv #(synth-optional-typedef % ctx) (sort optional-types)))

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
        stmts    (lower-body-stmts (:stmts (:body method)) ctx')
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

(defn- rewrite-self-field-refs
  "Walk an expression and turn bare `:ident` nodes whose name matches
   any of `field-names` into `self.<name>` dot-access nodes. Params
   (in `param-names`) shadow field names — they stay bare so record
   observables mirror the atom-observable scoping rule where args
   shadow self-fields (F67 L2 comment on `lower-observable-method`)."
  [expr field-names param-names]
  (walk/postwalk
   (fn [n]
     (if (and (map? n)
              (= :ident (:node n))
              (contains? field-names (:name n))
              (not (contains? param-names (:name n))))
       {:node :dot
        :target {:node :self}
        :field (:name n)}
       n))
   expr))

(defn- lower-record-observable-fn
  "F67 L4a — a record observable becomes a free C function of self.
   Return type inferred from the expression body; args (if parametric)
   become C params. Same C-function shape as `lower-method-fn`, but
   the body is a single `return <expr>` (observables are pure reads,
   no statements).

   Bare field references in the body are rewritten to `self.<field>`
   so they emit as C struct-pointer access — matching the existing
   record-method convention (which requires explicit `self.x`)."
  [record-name obs ctx]
  (let [param-locals (into {}
                           (map (fn [p]
                                  [(:name p)
                                   (cxc-type->type-kw (:name (:type p))
                                                      (:record-env ctx)
                                                      (:atom-env ctx)
                                                      (:enum-env ctx)
                                                      p)])
                                (:params obs)))
        rec           (get-in ctx [:record-env record-name])
        self-fields   (into {} (map (juxt :name identity) (:fields rec)))
        field-names   (set (map :name (:fields rec)))
        param-names   (set (map :name (:params obs)))
        rewritten-expr (rewrite-self-field-refs (:expr obs) field-names param-names)
        infer-ctx     {:record-env (:record-env ctx)
                       :atom-env   (:atom-env ctx)
                       :enum-env   (:enum-env ctx)
                       :locals     (assoc param-locals "self" {:record record-name})
                       :self-fields self-fields}
        ret-kw        (infer-type rewritten-expr infer-ctx)
        ret-c         (type-kw->c-type ret-kw obs)
        body-ctx      (assoc ctx :locals (assoc param-locals "self" {:record record-name}))
        params        (into [{:name "self" :c-type record-name}]
                            (mapv (fn [p]
                                    {:name   (:name p)
                                     :c-type (cxc-type->c (:type p)
                                                          (:record-env ctx)
                                                          (:atom-env ctx)
                                                          (:enum-env ctx))
                                     ;; F15 L2.c.iii.c.δ — preserve raw :type
                                     ;; so `collect-shapes` on the lowered
                                     ;; :functions discovers any :type-named-tuple
                                     ;; at a record-observable param position.
                                     :type   (:type p)})
                                  (:params obs)))]
    {:node        :c-function
     :name        (c-method-name record-name (:name obs))
     :return-type ret-c
     :params      params
     :body        [{:node :return :value (lower-expr rewritten-expr body-ctx)}]}))

(defn- record-observable-functions
  "For every record, lower each of its observables to a :c-function
   of self. Same shape as record methods so they share downstream
   emission and call-site dispatch."
  [ctx]
  (vec (for [[rec-name info] (:record-env ctx)
             o               (:observables info)]
         (lower-record-observable-fn rec-name o ctx))))

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
   equality.

   F39 L5b-ii-c (Sub-N) — when the field's storage is Optional-boxed,
   compare presence + value: two `nil` are equal; `nil` vs `present`
   are not; both `present` compare `.value`s with the underlying
   equality rule. Dead until Sub-P flips `:optional?`."
  [f a-name b-name ctx]
  (let [fname   (:name f)
        cxc-ty  (:cxc-ty f)
        a-fld   (str a-name "." fname)
        b-fld   (str b-name "." fname)]
    (cond
      (:optional? f)
      (let [a-val (str a-fld ".value")
            b-val (str b-fld ".value")
            a-pres (str a-fld ".present")
            b-pres (str b-fld ".present")
            inner-eq (cond
                       (= "String" cxc-ty)
                       (str "((" a-val " == " b-val ") || "
                            "[" a-val " isEqualToString:" b-val "])")
                       (contains? (:record-env ctx) cxc-ty)
                       (str (record-synth-equals-name cxc-ty)
                            "(" a-val ", " b-val ")")
                       :else
                       (str "(" a-val " == " b-val ")"))]
        (str "((" a-pres " == " b-pres ") && (!" a-pres " || " inner-eq "))"))

      (= "String" cxc-ty)
      (str "((" a-fld " == " b-fld ") || "
           "[" a-fld " isEqualToString:" b-fld "])")

      (contains? (:record-env ctx) cxc-ty)
      (str (record-synth-equals-name cxc-ty)
           "(" a-fld ", " b-fld ")")

      (contains? (:atom-env ctx) cxc-ty)
      (str "(" a-fld " == " b-fld ")")

      (primitive-eq-op? cxc-ty ctx)
      (str "(" a-fld " == " b-fld ")")

      :else
      (str "[" a-fld " isEqual:" b-fld "]"))))

(defn- field-hash-c-src
  "Emit a C expression producing a `long` hash contribution for one
   field. Combined with 31-multiply accumulator.

   F39 L5b-ii-c (Sub-N) — Optional field hashes to 0 when nil, else
   the underlying value's hash. Dead until Sub-P."
  [f var-name ctx]
  (let [fname   (:name f)
        cxc-ty  (:cxc-ty f)
        fld     (str var-name "." fname)]
    (cond
      (:optional? f)
      (let [val (str fld ".value")
            inner (cond
                    (= "String" cxc-ty)  (str "(long)[" val " hash]")
                    (contains? (:record-env ctx) cxc-ty)
                    (str (record-synth-hash-name cxc-ty) "(" val ")")
                    :else                 (str "(long)" val))]
        (str "(" fld ".present ? " inner " : 0)"))

      (= "String" cxc-ty)
      (str "(long)[" fld " hash]")

      (contains? (:record-env ctx) cxc-ty)
      (str (record-synth-hash-name cxc-ty) "(" fld ")")

      (contains? (:atom-env ctx) cxc-ty)
      (str "(long)(intptr_t)" fld)

      (primitive-eq-op? cxc-ty ctx)
      (str "(long)" fld)

      :else
      (str "(long)[" fld " hash]"))))

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

;; ---------------------------------------------------------------------------
;; F37 Landing 2 — canonical bytes synthesis for primitive-field records
;; (spec §3.4.6 / §3.10.10). Emits `<Record>_cxc_as_bytes(<Record>) -> NSMutableData *`
;; producing big-endian encoding of all fields concatenated in declaration
;; order. Records with any non-primitive field skip synthesis at this
;; landing; calling `.as()` on them raises a targeted `fail-nyi!` naming
;; the offending field type and pointing at the follow-on landing that
;; unblocks it (Landing 3 String/Char, Landing 4 nested record, Landing 5
;; enum, Landing 6 DecodeOutcome, Landing 8 user-override).
;;
;; User override (§3.3.2 rule 6) — a user-declared `.as(self) -> Bytes`
;; on the record shadows synthesis at both the eligibility check (skip
;; synth) and the intercept (fall through to normal record method
;; dispatch which routes to `<Record>_as`).
;; ---------------------------------------------------------------------------

(defn- record-synth-as-bytes-name [record-name]
  (str record-name "_cxc_as_bytes"))

(def ^:private f37-primitive-byte-widths
  ;; C fixed widths per §3.10.10. Bool is one byte (0/1).
  {"Bool"   1
   "UInt8"  1  "Int8"   1
   "UInt16" 2  "Int16"  2
   "UInt32" 4  "Int32"  4
   "UInt64" 8  "Int64"  8  "Int" 8
   "Float"  4  "Double" 8})

(defn- f37-primitive-byte-field?
  "F37 Landing 2 eligibility: field type is a Bool / sized integer /
   Int / Float / Double, non-Optional. Nested records, String, Char,
   Bytes, enum, atom, and Optional-boxed variants land later."
  [f]
  (and (not (:optional? f))
       (contains? f37-primitive-byte-widths (:cxc-ty f))))

(defn- f37-string-byte-field?
  "F37 Landing 3 — a `String!` field. Encoded as `UInt32` big-endian
   length prefix + UTF-8 bytes per §3.10.10 String row. Reference-
   type, non-Optional (bare `String` is nullable per §3.6.1; L3
   handles the `String!` non-nil case; bare-`String` waits on F39 L9
   Optional plumbing for the presence-byte envelope)."
  [f]
  (and (:nonnil? f) (= "String" (:cxc-ty f))))

(defn- f37-nested-record-field?
  "F37 Landing 4 — a nested record field (value-typed, non-Optional).
   Recursively encoded via the nested record's own synthesized
   `<Nested>_cxc_as_bytes`. Fields concatenated in declaration order
   without a length prefix (record encoding is fixed-shape per
   §3.10.10 record row). The nested record itself must be eligible
   at the enclosing record's synthesis time; the field-type-validity
   check surfaces at fail-nyi otherwise."
  [f record-env]
  (and (not (:optional? f))
       (contains? record-env (:cxc-ty f))))

(defn- f37-optional-primitive-field?
  "F39 Landing 9 — a bare-T value-typed field (Optional-boxed under
   F39 L5b-ii-c Sub-P) whose inner type is primitive-byte-eligible.
   Encoded as `UInt8` presence byte (0/1) + T bytes when present per
   §3.10.10 Optional row. `T!` fields skip the envelope (already
   handled by L2)."
  [f]
  (and (:optional? f)
       (contains? f37-primitive-byte-widths (:cxc-ty f))))

(defn- f37-optional-nested-record-field?
  "F39 Landing 9 — a bare-T value-typed field where T is a nested
   record. Encoded as presence byte + T's canonical bytes when
   present."
  [f record-env]
  (and (:optional? f)
       (contains? record-env (:cxc-ty f))))

(defn- f37-bytes-encodable-field?
  "F37 Landings 2+3+4 + F39 L9 — union of the shipped eligibility
   checks. Any field for which the encoder function has a synthesis
   path. Nested-record fields require record-env to check whether the
   field's cxc-ty is a known record."
  ([f]
   (or (f37-primitive-byte-field? f)
       (f37-string-byte-field? f)
       (f37-optional-primitive-field? f)))
  ([f record-env]
   (or (f37-primitive-byte-field? f)
       (f37-string-byte-field? f)
       (f37-nested-record-field? f record-env)
       (f37-optional-primitive-field? f)
       (f37-optional-nested-record-field? f record-env))))

(defn- f37-primitive-value-encode-src
  "Return the C-statement string encoding a primitive value bound at
   variable name `val` into `__d`. Shared between direct (non-Optional)
   emission and the Optional-envelope inner path."
  [cxc-ty val]
  (case cxc-ty
    "Bool"
    (str "uint8_t __v = " val " ? 0x01 : 0x00;"
         " [__d appendBytes:&__v length:1];")

    ("UInt8" "Int8")
    (str "uint8_t __v = (uint8_t)" val ";"
         " [__d appendBytes:&__v length:1];")

    ("UInt16" "Int16")
    (str "uint16_t __v = OSSwapHostToBigInt16((uint16_t)" val ");"
         " [__d appendBytes:&__v length:2];")

    ("UInt32" "Int32")
    (str "uint32_t __v = OSSwapHostToBigInt32((uint32_t)" val ");"
         " [__d appendBytes:&__v length:4];")

    ("UInt64" "Int64" "Int")
    (str "uint64_t __v = OSSwapHostToBigInt64((uint64_t)" val ");"
         " [__d appendBytes:&__v length:8];")

    "Float"
    (str "uint32_t __v; memcpy(&__v, &" val ", 4);"
         " __v = OSSwapHostToBigInt32(__v);"
         " [__d appendBytes:&__v length:4];")

    "Double"
    (str "uint64_t __v; memcpy(&__v, &" val ", 8);"
         " __v = OSSwapHostToBigInt64(__v);"
         " [__d appendBytes:&__v length:8];")))

(defn- f37-field-encoding-c-src
  "Emit a C compound statement that appends field `f`'s big-endian
   encoding to `__d` (the NSMutableData accumulator). Assumes the
   record var is bound as `a`. Landings 2 (primitive) + 3 (String) +
   4 (nested record) + F39 L9 (Optional envelope) — other field types
   raise the caller's fail-nyi guard first. record-env is consulted
   for the L4/L9 dispatch to nested records' synthesized fns."
  [f record-env]
  (let [fname (:name f)
        cxc-ty (:cxc-ty f)
        fld    (str "a." fname)]
    (cond
      (:optional? f)
      ;; F39 L9 — Optional envelope: presence byte (0/1) + payload
      ;; when present. Storage rep is `_Cxc_Optional_<T>` with
      ;; `.present` (uint8) + `.value` (raw T). Per §3.10.10:
      ;;   0x00 → absent, no payload bytes
      ;;   0x01 → present, followed by T's canonical bytes
      (let [pres-src (str "uint8_t __p = " fld ".present ? 0x01 : 0x00;"
                          " [__d appendBytes:&__p length:1];")
            val-src  (cond
                       (contains? record-env cxc-ty)
                       (str "NSData *__nd = " cxc-ty "_cxc_as_bytes("
                            fld ".value); [__d appendData:__nd];")

                       (contains? f37-primitive-byte-widths cxc-ty)
                       (f37-primitive-value-encode-src cxc-ty
                                                       (str fld ".value")))]
        (str "    {\n"
             "        " pres-src "\n"
             "        if (" fld ".present) { " val-src " }\n"
             "    }"))

      (contains? record-env cxc-ty)
      ;; F37 Landing 4 — nested record dispatches to its own
      ;; synthesized fn. No length prefix per §3.10.10 record row
      ;; (fixed-shape concatenation). Forward declarations at emit
      ;; time make dispatch order irrelevant.
      (str "    { NSData *__nd = " cxc-ty "_cxc_as_bytes(" fld ");"
           " [__d appendData:__nd]; }")

      :else
      (case cxc-ty
      "Bool"
      (str "    { uint8_t __v = " fld " ? 0x01 : 0x00;"
           " [__d appendBytes:&__v length:1]; }")

      ("UInt8" "Int8")
      (str "    { uint8_t __v = (uint8_t)" fld ";"
           " [__d appendBytes:&__v length:1]; }")

      ("UInt16" "Int16")
      (str "    { uint16_t __v = OSSwapHostToBigInt16((uint16_t)" fld ");"
           " [__d appendBytes:&__v length:2]; }")

      ("UInt32" "Int32")
      (str "    { uint32_t __v = OSSwapHostToBigInt32((uint32_t)" fld ");"
           " [__d appendBytes:&__v length:4]; }")

      ("UInt64" "Int64" "Int")
      (str "    { uint64_t __v = OSSwapHostToBigInt64((uint64_t)" fld ");"
           " [__d appendBytes:&__v length:8]; }")

      "Float"
      ;; Type-pun through uint32_t to byteswap IEEE 754 bits without
      ;; touching the float value's representation.
      (str "    { uint32_t __v; memcpy(&__v, &" fld ", 4);"
           " __v = OSSwapHostToBigInt32(__v);"
           " [__d appendBytes:&__v length:4]; }")

      "Double"
      (str "    { uint64_t __v; memcpy(&__v, &" fld ", 8);"
           " __v = OSSwapHostToBigInt64(__v);"
           " [__d appendBytes:&__v length:8]; }")

      "String"
      ;; F37 Landing 3 — String canonical bytes: `UInt32` big-endian
      ;; length prefix + raw UTF-8 bytes per §3.10.10 String row.
      ;; NSString round-trips to valid UTF-8 (Unicode bijection at the
      ;; Unicode-scalar level); no encode-side invalidity to guard
      ;; against. Decode-side invalidity is F37 Landing 6.
      (str "    {\n"
           "        NSData *__sd = [" fld " dataUsingEncoding:NSUTF8StringEncoding];\n"
           "        uint32_t __len = OSSwapHostToBigInt32((uint32_t)[__sd length]);\n"
           "        [__d appendBytes:&__len length:4];\n"
           "        [__d appendData:__sd];\n"
           "    }")))))

(defn- record-has-user-as-method?
  "True when the record explicitly declares an `as` callable — either
   as an extension method landed in `:method-index` or as an own
   observable landed in `:observable-index`. Either surface shadows
   the synthesized `.as() -> Bytes` default per spec §3.3.2 rule 6.
   Post F67 L4 records carry their own callable readers as
   observables, so checking `:method-index` alone (the pre-collapse
   surface) missed same-record overrides."
  [info]
  (or (contains? (get-in info [:method-index])     "as")
      (contains? (get-in info [:observable-index]) "as")))

(defn- record-eligible-for-as-bytes-synthesis?
  ([info]
   (and (not (record-has-user-as-method? info))
        (every? f37-bytes-encodable-field? (:fields info))))
  ([info record-env]
   (and (not (record-has-user-as-method? info))
        (every? #(f37-bytes-encodable-field? % record-env) (:fields info)))))

(defn- synthesize-record-as-bytes-fn
  "F37 Landings 2 + 3 + 4 — produce a `:c-function` node implementing
   `<Record>_cxc_as_bytes(<Record> a) -> NSMutableData *`. Precondition:
   `record-eligible-for-as-bytes-synthesis?` returned true.

   Capacity hint sums the compile-time-known widths of primitive
   fields; String / nested-record fields are variable-length so any
   record containing one drops the hint (passes 0)."
  [record-name info record-env]
  (let [fields    (:fields info)
        all-fixed? (every? f37-primitive-byte-field? fields)
        total     (if all-fixed?
                    (reduce + 0 (map #(get f37-primitive-byte-widths (:cxc-ty %))
                                     fields))
                    0)
        head      (str "    NSMutableData *__d = [NSMutableData dataWithCapacity:"
                       total "];")
        blocks    (map #(f37-field-encoding-c-src % record-env) fields)
        body-src  (str/join "\n" (concat [head] blocks ["    return __d;"]))]
    {:node        :c-function
     :name        (record-synth-as-bytes-name record-name)
     :return-type "NSMutableData *"
     :params      [{:name "a" :c-type record-name}]
     :body        [{:node :raw-c :text body-src}]}))

(defn- enum-synth-as-bytes-name [enum-name]
  (str enum-name "_cxc_as_bytes"))

(defn- f37-enum-discriminator-width
  "F37 L5 — discriminator width in bytes per §3.10.10: ≤256 → 1, ≤65536
   → 2, otherwise 4. Case count is total, not tag-value range — a
   256-case enum uses `UInt8` because tag values are 0..255."
  [case-count]
  (cond
    (<= case-count 256)    1
    (<= case-count 65536)  2
    :else                  4))

(defn- f37-enum-discriminator-c
  "F37 L5 — emit a C statement appending the discriminator to `__d`.
   `disc-expr` is the source expression for the tag (`a` for value-
   only enums, `a.tag` for tagged enums). Big-endian for widths > 1."
  [disc-expr width]
  (case width
    1 (str "uint8_t __disc = (uint8_t)(" disc-expr ");"
           " [__d appendBytes:&__disc length:1];")
    2 (str "uint16_t __disc = OSSwapHostToBigInt16((uint16_t)(" disc-expr "));"
           " [__d appendBytes:&__disc length:2];")
    4 (str "uint32_t __disc = OSSwapHostToBigInt32((uint32_t)(" disc-expr "));"
           " [__d appendBytes:&__disc length:4];")))

(defn- f37-enum-payload-field-for-encoding
  "F37 L5 — normalize an enum payload param into the field shape
   `f37-field-encoding-c-src` expects: `{:name :cxc-ty :nonnil?
   :optional?}`. Payload params carry their `:type` node with a
   `:type-nonnil` wrapper when the user wrote `T!`."
  [param]
  (let [ty-node (:type param)
        nonnil? (= :type-nonnil (:node ty-node))
        inner   (if nonnil? (:inner ty-node) ty-node)
        cxc-ty  (:name inner)]
    {:name      (:name param)
     :cxc-ty    cxc-ty
     :nonnil?   nonnil?
     :optional? false}))

(defn- f37-enum-payload-field-encoding-c-src
  "F37 L5 — encode one payload field inside a case block. Reads from
   `a.data.<case>.<field>` rather than `a.<field>` (record) — uses
   the same primitive/String encoding helpers by rewriting the
   accessor prefix."
  [case-nm payload-field record-env]
  (let [f       (f37-enum-payload-field-for-encoding payload-field)
        cxc-ty  (:cxc-ty f)
        fname   (:name f)
        fld     (str "a.data." case-nm "." fname)]
    (cond
      (contains? record-env cxc-ty)
      (str "        { NSData *__nd = " cxc-ty "_cxc_as_bytes(" fld ");"
           " [__d appendData:__nd]; }")

      (= "String" cxc-ty)
      (str "        {\n"
           "            NSData *__sd = [" fld " dataUsingEncoding:NSUTF8StringEncoding];\n"
           "            uint32_t __len = OSSwapHostToBigInt32((uint32_t)[__sd length]);\n"
           "            [__d appendBytes:&__len length:4];\n"
           "            [__d appendData:__sd];\n"
           "        }")

      (contains? f37-primitive-byte-widths cxc-ty)
      (str "        { " (f37-primitive-value-encode-src cxc-ty fld) " }"))))

(defn- synthesize-enum-as-bytes-fn
  "F37 Landing 5 — produce a `:c-function` node implementing
   `<Enum>_cxc_as_bytes(<Enum> a) -> NSMutableData *`. Discriminator
   width per §3.10.10 case count. Payload cases append per-field
   encoding after the discriminator via a switch on the tag.

   Value-only enums (no case carries payload) skip the switch — the
   discriminator alone is the full encoding. Tagged enums emit a
   `switch (a.tag) { case E_Tag_<case>: {...encode fields...} break; ... }`."
  [enum-name info record-env]
  (let [entries    (:case-entries info)
        tagged?    (:tagged? info)
        disc-width (f37-enum-discriminator-width (count entries))
        disc-expr  (if tagged? "a.tag" "a")
        head       (str "    NSMutableData *__d = "
                        "[NSMutableData dataWithCapacity:" disc-width "];")
        disc-src   (str "    " (f37-enum-discriminator-c disc-expr disc-width))
        switch-src
        (when tagged?
          (let [arms
                (for [ce entries]
                  (let [case-nm (:name ce)
                        fields  (:payload ce)
                        blocks  (if (seq fields)
                                  (str/join "\n"
                                            (map #(f37-enum-payload-field-encoding-c-src
                                                   case-nm % record-env)
                                                 fields))
                                  "")]
                    (str "    case " enum-name "_Tag_" case-nm ": {\n"
                         (when (seq blocks) (str blocks "\n"))
                         "        break;\n"
                         "    }")))]
            (str "    switch (a.tag) {\n"
                 (str/join "\n" arms)
                 "\n    }")))
        body-src   (str/join "\n"
                             (remove nil?
                                     [head disc-src switch-src "    return __d;"]))]
    {:node        :c-function
     :name        (enum-synth-as-bytes-name enum-name)
     :return-type "NSMutableData *"
     :params      [{:name "a" :c-type enum-name}]
     :body        [{:node :raw-c :text body-src}]}))

(defn- enum-eligible-for-as-bytes-synthesis?
  "F37 L5 — every payload field on every case must be bytes-encodable.
   Value-only enums (no payload cases) are always eligible."
  [info record-env]
  (every? (fn [ce]
            (every? #(f37-bytes-encodable-field?
                       (f37-enum-payload-field-for-encoding %)
                       record-env)
                    (:payload ce)))
          (:case-entries info)))

(defn- synthesized-record-functions
  "Emit the synthesized equals + hash function pair for every record,
   plus F37 Landings 2 + 3 + 4 canonical-bytes synthesis when the
   record's fields are all bytes-encodable (primitive / String! /
   nested-record) AND the user hasn't declared their own
   `.as(self) -> Bytes` (§3.3.2 rule 6 override). Forward decls at
   emit time make nested-record dispatch order irrelevant.

   F37 L5 folds enum canonical-bytes into the same pipeline — enums
   whose payload cases are all bytes-encodable synth
   `<Enum>_cxc_as_bytes`."
  [ctx]
  (let [record-env (:record-env ctx)
        enum-env   (:enum-env ctx)
        record-fns
        (vec (for [[rec-name info] record-env
                   f-fn            [synthesize-record-equals-fn
                                    synthesize-record-hash-fn
                                    (fn [rn ri _]
                                      (when (record-eligible-for-as-bytes-synthesis? ri record-env)
                                        (synthesize-record-as-bytes-fn rn ri record-env)))]
                   :let            [emitted (f-fn rec-name info ctx)]
                   :when           emitted]
               emitted))
        enum-fns
        (vec (for [[en-name info] enum-env
                   :when          (enum-eligible-for-as-bytes-synthesis? info record-env)]
               (synthesize-enum-as-bytes-fn en-name info record-env)))]
    (into record-fns enum-fns)))

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

(defn- field-storage-value-typed?
  [field ctx]
  (let [c-ty (:c-type field)
        nm   (:cxc-ty field)]
    (and (string? c-ty)
         (not (str/ends-with? c-ty "*"))
         (not= "id" c-ty)
         (or (contains? primitive-c-type nm)
             (contains? (:record-env ctx) nm)
             (contains? (:enum-env   ctx) nm)))))

(defn- default-init-stmt-for [field ctx]
  (let [d (:default field)]
    ;; F39 L5b-i fence — only fires on value-typed fields whose storage
    ;; is still scalar (NOT `:optional?`). Once Sub-P flips the flag,
    ;; the same shape becomes legal via the Optional wrap below.
    (when (and (map? d)
               (= :lit-nil (:node d))
               (field-storage-value-typed? field ctx)
               (not (:optional? field)))
      (let [ty-nm  (:cxc-ty field)
            suffix (if (:nonnil? field) "!" "")]
        (fail-nyi!
         (str "F39 L5b-i: value-typed field `" (:name field) ": " ty-nm suffix
              "` cannot take `= nil` default until L5b-ii lands the "
              "Optional-boxed representation (spec §3.6.1 unified rule "
              "2026-08-20). See compiler/design/plans/g3-structural-canonical/f39-nullability.md.")
         field)))
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

      ;; F39 L5b-ii-c (Sub-J) — Optional field default-init.
      ;; `= nil` OR no-default → `{present:0}`;
      ;; already-Optional default (e.g. `= Counter(...).bump` where
      ;; `.bump` returns Optional) → pass-through struct-copy;
      ;; scalar default → wrap `{present:1, .value:<lowered>}`.
      (:optional? field)
      (let [opt-c (:c-type field)
            nil-default? (or (nil? d)
                             (and (map? d) (= :lit-nil (:node d))))
            rhs-tk (when d (infer-type d ctx))
            already-opt? (and rhs-tk (optional-tk? rhs-tk))
            rhs (cond
                  nil-default?
                  {:node :struct-lit
                   :type opt-c
                   :fields [{:name "present" :value {:node :int :value 0}}]}
                  already-opt?
                  (lower-expr d ctx)
                  :else
                  {:node :struct-lit
                   :type opt-c
                   :fields [{:name "present" :value {:node :int :value 1}}
                            {:name "value"   :value (lower-expr d ctx)}]})]
        {:node :assign
         :lhs  {:node :name :sym (str "_" (:name field))}
         :rhs  rhs})

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
        stmts       (lower-body-stmts (:stmts (:body method)) ctx')
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
        ;; F39 L3 + L5b-ii-d — method-return check for `-> T!`. Rewrite
        ;; the trailing `return <expr>` into a `_Cxc_ret = <expr>; guard;
        ;; return _Cxc_ret;` triple so the returned value is bound once,
        ;; checked, then returned. Two shapes:
        ;;   reference `T!` → pointer-nil check (existing L3 path)
        ;;   value `T!` fed an Optional expression → `.present` check
        ;;     with unwrap via `:c-value-nonnil-unwrap` (L5b-ii-d).
        ;; For value `T!` fed a scalar expression, no check needed —
        ;; the type is already non-nil by construction.
        nonnil-ret?  (= :type-nonnil (get-in method [:cxc-return-ty-node :node]))
        return-ty-kw (when nonnil-ret?
                       (cxc-type->type-kw (:cxc-return-ty method)
                                          (:record-env ctx) (:atom-env ctx)
                                          (:enum-env ctx) method))
        ret-c-value?  (and (string? ret-c)
                           (not (str/ends-with? ret-c "*"))
                           (not= "id" ret-c)
                           (not (.contains ^String ret-c "^")))
        ret-check-stmts
        (fn [ret-stmt]
          (cond
            (not nonnil-ret?)
            [ret-stmt]

            ;; Value-typed `T!` with an Optional return-expression:
            ;; unwrap + presence check inline. Detected on the lowered
            ;; form (already-emitted `:c-optional-*` node, or a `:field`
            ;; read of an Optional-storage self-field). Raw-AST
            ;; `infer-type` doesn't run here — the lowered pipeline has
            ;; already converted user idents into ivars.
            (and ret-c-value?
                 (optional-ret-value? (:value ret-stmt) ctx))
            [{:node :var-decl :name "_Cxc_ret" :c-type ret-c
              :rhs  {:node :c-value-nonnil-unwrap
                     :inner (:value ret-stmt)
                     :site  (str "def-atom `" atom-name "` method `"
                                 (:name method) "` return (value-typed T!)")
                     :name  "return value"}}
             {:node :return :value {:node :name :sym "_Cxc_ret"}}]

            :else
            [{:node :var-decl :name "_Cxc_ret" :c-type ret-c
              :rhs  (:value ret-stmt)}
             (nonnil-check-stmt "_Cxc_ret"
                                (str "def-atom `" atom-name "` method `"
                                     (:name method) "` return")
                                "return value")
             {:node :return :value {:node :name :sym "_Cxc_ret"}}]))
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
        ;; F32 L4.e.β.2.α (2026-08-24) — thread `:snapshot-name` into
        ;; the observable body ctx so field reads route via the
        ;; snapshot bag (parallel to receive-glyph body ctx). Under
        ;; the default flag the snapshot copies from ivars — same
        ;; values; under concurrent mode L4.e.α's guarded shape
        ;; sources from `_state` so observables read committed state.
        body-ctx     (-> ctx
                         (update :locals #(merge (or % {}) param-locals))
                         (assoc :snapshot-name "__cxc_snap"))
        objc-params  (mapv (fn [p]
                             {:name   (:name p)
                              :c-type (cxc-type->c (:type p)
                                                   (:record-env ctx)
                                                   (:atom-env ctx)
                                                   (:enum-env ctx))
                              ;; F15 L2.c.iii.c.δ — preserve raw :type
                              ;; so `collect-shapes` on the lowered
                              ;; :classes discovers any :type-named-tuple
                              ;; at an atom-observable param position.
                              :type   (:type p)})
                           (:params o))
        ;; F67 L4 — under Proposal G class-body collapse, observables
        ;; are the reader-method surface. Spec §3.4.2 requires
        ;; invariants to hold before/after each method — wrap entry +
        ;; exit checkInvariants around the observable's return the
        ;; same way lower-atom-method does. Only fires when the atom
        ;; declares invariants (empty invariants → wrap is a no-op
        ;; but adds two selfmsg calls; skip when there are none).
        has-invariants? (seq (get-in ctx [:atom-env atom-name :invariants]))
        check-stmt   {:node :objc-msg
                      :recv {:node :name :sym "self"}
                      :selector "checkInvariants"
                      :args []}
        return-stmt  {:node :return :value (lower-expr (:expr o) body-ctx)}
        core-stmts   (if has-invariants?
                       [check-stmt check-stmt return-stmt]
                       [return-stmt])
        ;; F32 L4.e.β.2.α — prepend `<Atom>_State __cxc_snap = { ... };`
        ;; when the atom has data-fields. Skipped for zero-data atoms
        ;; (state struct would be empty, snapshot would be a no-op).
        ;; L4.e.α's guarded emit handles the concurrent-mode source
        ;; flip; observable snapshot mirrors receive-glyph snapshot.
        data-fields  (get-in ctx [:atom-env atom-name :fields])
        snap-stmt    (when (seq data-fields)
                       {:node        :state-snapshot-init
                        :class-name  (class-name-for-atom atom-name)
                        :data-fields data-fields})
        body-stmts   (if snap-stmt
                       (into [snap-stmt] core-stmts)
                       core-stmts)]
    {:node        :objc-method
     :selector    (if (seq objc-params)
                    (objc-method-selector (:name o) (mapv :name objc-params))
                    (:name o))
     :return-type (:c-type o)
     :params      objc-params
     :body        body-stmts}))

(defn- proposition-error-call
  "Convert a normalized proposition's optional typed failure payload back
   into the ordinary constructor-call AST consumed by `lower-expr`. L2a
   keeps the type and named args separate so all proposition placements
   share one record shape; F49 only needs to reconstruct the expression
   when it builds a runtime failure branch."
  [{:keys [as-error-type as-error-args] :as proposition}]
  (when as-error-type
    (when-not (= :type-ref (:node as-error-type))
      (fail-compile!
       :cxc-l2b-invalid-proposition-error-type
       (str "typed proposition failure must construct a named error type; got `"
            (:node as-error-type) "`")
       proposition))
    {:node   :call
     :callee {:node :ident :name (:name as-error-type)}
     :args   (mapv (fn [[name value]]
                     {:kind :named :name name :value value})
                   as-error-args)}))

(defn- lower-invariant-check
  "One normalized proposition → an :invariant-check init statement. Ctx must carry
   self-fields + init-locals so unqualified names resolve as in init.

   Chained comparisons (`a <= b <= c`) are desugared to
   `(a <= b) and (b <= c)` at the parser layer's
   `desugar-chained-comparisons` pass before lower ever sees them —
   no special handling here.

   F39 L5b-ii-c — when the invariant predicate infers as Optional<Bool>
   (because it reads an Optional field via nil-punning arithmetic /
   ordering), reinterpret the check as \"skip if any operand is nil;
   fail only when present and false\". Wrapped in a `:c-optional-cond`
   marker so emit can produce `(cond.present && !cond.value)` instead
   of `!cond` on a struct."
  [atom-name idx proposition ctx]
  (let [pred          (:predicate proposition)
        pred-tk       (infer-type pred ctx)
        cond-lo       (lower-expr pred ctx)
        failure-value (some-> (proposition-error-call proposition)
                              (lower-expr ctx))]
    (cond->
     {:node  :invariant-check
      :cond  (if (optional-tk? pred-tk)
               {:node :c-optional-cond :inner cond-lo}
               cond-lo)
      ;; Fires from init AND post-transition (Step 14); label stays generic.
      :label (str atom-name " invariant #" idx " failed")}
      failure-value (assoc :failure-value failure-value))))

(defn- collect-self-field-refs
  "Walk an AST value bottom-up, returning the set of unqualified :ident names
   that would resolve to a self-field (not to a local, not to a fully-
   qualified path). Used by F49 Landing A to decide whether a proposition
   (predicate plus optional typed failure args) is safe to pre-store-check:
   if every field ref has a source value in the substitution map, the
   pre-store check emits before any field assignment truncates."
  [ast ctx]
  (let [acc (atom #{})]
    (walk/postwalk
     (fn [n]
       (when (and (map? n)
                  (= :ident (:node n))
                  (not (contains? (:locals ctx) (:name n)))
                  (get (:self-fields ctx) (:name n)))
         (swap! acc conj (:name n)))
       n)
     ast)
    @acc))

(defn- pre-store-invariant-check
  "F49 Landing A: emit the pre-store construction check for a normalized
   proposition when
   every self-field it references has a source-value entry in
   `field-subs` (ctor-param ident or default expression). Returns nil
   if any field reference lacks a source value — those predicates fall
   back to the post-store checkInvariants call from cxc_runtimeBinding,
   which is still correct for non-narrowed storage.

   The label distinguishes pre-store from post-store so a diagnostic on
   a narrowed-field violation names the correct site."
  [atom-name idx proposition ctx field-subs]
  (let [pred (:predicate proposition)
        refs (collect-self-field-refs proposition ctx)
        all-substituted? (every? #(contains? field-subs %) refs)]
    (when all-substituted?
      (let [sub-ctx (assoc ctx :field-substitutions field-subs)
            pred-tk (infer-type pred sub-ctx)
            cond-lo (lower-expr pred sub-ctx)
            failure-value (some-> (proposition-error-call proposition)
                                  (lower-expr sub-ctx))]
        (cond->
         {:node  :invariant-check
          :cond  (if (optional-tk? pred-tk)
                   {:node :c-optional-cond :inner cond-lo}
                   cond-lo)
          :label (str atom-name " invariant #" idx
                      " failed at construction (source value violates)")}
          failure-value (assoc :failure-value failure-value))))))

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
    ;; F37 L6b (2026-08-24) — `class function name(...)` bodies parse
    ;; cleanly but F37 L6 (`from(Bytes)` synthesis + call-site
    ;; dispatch) hasn't landed yet, so lower can't consume them. Fire
    ;; a targeted fail-nyi naming the class + function so users know
    ;; the landing that would enable this shape.
    (when-let [cfs (seq (:class-fns rec))]
      (fail-nyi! (str "def-atom `" atom-name "` has `class function` decls "
                      "(" (mapv :name cfs) ") — spec §3.4.6 static-fn shape "
                      "parses at v1.0 but lower/emit consumption lands with "
                      "F37 L6 (`class function from(source: Bytes) -> "
                      "DecodeOutcome<Self>` synthesis + `Type.name(args)` "
                      "call-site dispatch). Track: F37 L6 in "
                      "compiler/design/plans/g3-structural-canonical/"
                      "f37-canonical-bytes.md.")
                 {:atom atom-name :class-functions cfs}))
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
          ;;
          ;; F32 L4.e.β.3 (2026-08-24) — thread `:snapshot-name` into
          ;; the -checkInvariants lowering ctx so invariant predicates
          ;; read fields via `__cxc_snap._field` instead of raw `_field`
          ;; ivars. Under default flag: semantics-equivalent (snapshot
          ;; copies from ivars — same values). Under concurrent mode:
          ;; L4.e.α's guarded emit sources snapshot from `_state`, so
          ;; -checkInvariants observes committed state. Composes with
          ;; the snapshot-init stmt injected into the method body below.
          ;; Without this, L4.e.β proper (retire per-field ivar writes)
          ;; would leave -checkInvariants observing stale initial-ivar
          ;; values on every observable entry/exit + transitionToEnd
          ;; call.
          ctx-post-store  (assoc ctx-with-self :snapshot-name "__cxc_snap")
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
                                  ;;
                                  ;; F13 L2 — reference modifiers override the default
                                  ;; ownership attr on reference-typed fields:
                                  ;;   `weak T`    → `(nonatomic, weak) T *name`
                                  ;;   `unowned T` → `(nonatomic, unsafe_unretained) T *name`
                                  ;; Modifier presence was already validated in L1 to
                                  ;; be on a reference type; here we just pick the attr.
                                  (let [modifier     (:modifier f)
                                        is-atom-ptr? (contains? (:atom-env ctx) (:cxc-ty f))
                                        is-block?    (= "Block" (:cxc-ty f))
                                        is-string?   (= "String" (:cxc-ty f))]  ; F65
                                    {:name (:name f)
                                     :c-type (:c-type f)
                                     ;; F33 L5 — data-field @properties
                                     ;; are readonly. External code can
                                     ;; still READ (spec §1.11: reads
                                     ;; are always OK), but no `-set<F>:`
                                     ;; selector is synthesized — the
                                     ;; ObjC bridge can no longer write
                                     ;; to atom state. Internal writes
                                     ;; go through the ivar directly
                                     ;; (`_field = ...`); ObjC allows
                                     ;; that even when the @property
                                     ;; is marked readonly.
                                     :attrs (cond
                                              (= :weak modifier)    ["nonatomic" "readonly" "weak"]
                                              (= :unowned modifier) ["nonatomic" "readonly" "unsafe_unretained"]
                                              is-block?    ["nonatomic" "readonly" "copy"]
                                              is-string?   ["nonatomic" "readonly" "copy"]  ; F65
                                              is-atom-ptr? ["readonly" "strong"]
                                              :else        ["readonly" "assign"])}))
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
          ;; F32 L4.d.β.α (2026-08-24) — post-init seed of the
          ;; concurrent-mode `_state` slot from the freshly-populated
          ;; ivars. Only appended when the atom carries data-fields
          ;; (otherwise there's no `<Cls>_State` typedef to sizeof).
          ;; Emits a guarded compound-literal + `_cxc_state_commit`;
          ;; under the default preprocessor flag the whole stmt
          ;; guards out (byte-identical .m modulo the extra
          ;; `#if …#endif` block). L4.d.β.β wires cross-atom reads
          ;; to route through the seeded slot — safe because this
          ;; seed guarantees `_state` is populated before any
          ;; observer can reach it.
          initial-state-commit-stmt
          (when (seq data-props)
            {:node        :initial-state-commit
             :class-name  (class-name-for-atom atom-name)
             :data-fields data-props})
          state-method    {:node :objc-method :selector state-selector
                           :return-type "void"
                           :params (mapv (fn [p] {:name (:name p) :c-type (:c-type p)})
                                         ctor-params)
                           ;; F49 Landing A: pre-store invariant checks
                           ;; run first so ctor-param / default values
                           ;; failing the invariant abort before any
                           ;; narrowing store loses information.
                           :body (cond-> (-> []
                                             (into nonnil-ctor-check-stmts)
                                             (into pre-store-invariant-stmts)
                                             (into default-stmts)
                                             (into on-enter-stmts))
                                   initial-state-commit-stmt
                                   (conj initial-state-commit-stmt))}
          ;; Factor invariants into -checkInvariants (design §14) so
          ;; init AND every message-triggered transition can call it —
          ;; duplication-free, single source of truth. cxc_runtimeBinding
          ;; forwards to it so the check runs at end-of-construction.
          ;;
          ;; F32 L4.e.β.3 (2026-08-24) — prepend a `:state-snapshot-init`
          ;; when the atom has data-fields; combined with `:snapshot-
          ;; name "__cxc_snap"` in ctx-post-store (above), invariant
          ;; predicates read via `__cxc_snap._field` rather than the
          ;; raw `_field` ivar. L4.e.α's guarded snapshot emit sources
          ;; from `_state` under concurrent mode — closes the invariant
          ;; side of the ivar-reader gap. Atoms without data-fields
          ;; skip the snapshot (state struct empty; predicates can
          ;; only reference observables/literals in that case).
          check-invariants-snap (when (and (seq (:fields rec))
                                            (seq (:invariants rec)))
                                  {:node        :state-snapshot-init
                                   :class-name  (class-name-for-atom atom-name)
                                   :data-fields (:fields rec)})
          check-invariants-body (if check-invariants-snap
                                  (into [check-invariants-snap] invariant-stmts)
                                  invariant-stmts)
          check-invariants-method {:node :objc-method
                                   :selector "checkInvariants"
                                   :return-type "void" :params []
                                   :body check-invariants-body}
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
          ;; Audit F26: spec §1.11 line 1281 — field updates in an
          ;; `-> end` transition are a compile error (the atom's
          ;; state is about to be discarded, so writes are
          ;; meaningless). F70 I3 migrates this rule from clause-
          ;; level to ENTRY-level: reject only when `:then-clause`
          ;; carries non-empty `:updates`. Non-update entries
          ;; (`:stmts` — prints, sends) inside `then:` on `-> end`
          ;; are allowed and lower as post-commit side effects.
          _               (doseq [w end-wildcards
                                  c (:body w)
                                  :when (and (= :then-clause (:node c))
                                             (seq (:updates c)))]
                            (throw (ex-info
                                    (str "`then:` field updates on `-> end` "
                                         "wildcard (atom `" atom-name "`) is a "
                                         "compile error — the atom's state is "
                                         "about to be discarded, so field "
                                         "updates are meaningless. See spec "
                                         "§1.11.")
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
                              ;; F35 L5 (2026-08-23) — post-order sync
                              ;; cascade. Order: user do: blocks (may
                              ;; still need timers) → invariant check
                              ;; (§3.4.2 pre-destruction) → cascade to
                              ;; children (each fully tears down before
                              ;; the next begins) → clear self's owned
                              ;; resources → notify parent. Pre-L5 the
                              ;; resources-clear ran BEFORE the cascade
                              ;; and the cascade was `cxc_send_async`;
                              ;; under sync cascade the child may still
                              ;; observe self-owned shared resources
                              ;; during its own teardown, so self's
                              ;; resources release AFTER all children
                              ;; are fully torn down.
                              (into cascade-stmts)
                              (conj {:node :owned-resources-clear})
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
       ;; F33 L2.a — snapshot-struct source. Only the user's `data:` fields
       ;; (data-props) — never compiler-injected slots (_phase, _own_queue,
       ;; _parent, stream subscribers, owned-resources). Emitted as
       ;; `typedef struct <Atom>_State { <c-type> _<name>; ... } <Atom>_State;`
       ;; alongside `@interface`; dead code at L2.a (no reads route through
       ;; it yet). L2.b–e progressively wire in the snapshot init + read
       ;; reroute.
       :data-fields     data-props
       :properties      (-> [phase-prop own-queue-prop owned-res-prop
                             parent-prop parent-slot-prop]
                            (into data-props)
                            (into stream-props))
       :init-params     (mapv (fn [p]
                                ;; F15 L2.c.iii.c.δ.2 — preserve raw :type on
                                ;; class :init-params so `collect-shapes` postwalk
                                ;; of the lowered :classes discovers named-tuple
                                ;; shapes at ctor-param positions. Extra field is
                                ;; ignored by downstream emit (which projects to
                                ;; :name + :c-type when rendering param lists).
                                (cond-> {:name (:name p) :c-type (:c-type p)}
                                  (:type p) (assoc :type (:type p))))
                              ctor-params)
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
  "Top-level `def Foo:` with case markers.
   Value-only enums (all cases bare) lower to a C typedef enum with
   `Foo_a`, `Foo_b`, ... as members — pre-F16 behavior preserved.

   F16 L1 — if any case carries a payload, lower to a tagged-union
   struct: `typedef struct { <Foo>_Tag tag; union { struct { … } case1;
   … } data; } Foo;` with an accompanying `<Foo>_Tag` enum for the
   discriminator. Emit resolves per-field C types via record-env /
   atom-env / enum-env.

   F16 L2 — raw-value enums (all cases have `= <int>`) preserve their
   raw values on the `:c-enum` node so emit can render explicit
   `<Enum>_<case> = <value>` initializers with a typed underlying
   integer (`typedef enum : <raw-type> { … }`, per §3.3)."
  [enum-node record-env atom-env enum-env]
  (let [tagged?    (some #(seq (:payload %)) (:case-entries enum-node))
        raw-values (:raw-values enum-node)
        raw-type   (:raw-type   enum-node)]
    (cond
      tagged?
      {:node    :c-tagged-enum
       :name    (:name enum-node)
       :entries (mapv (fn [ce]
                        {:name    (:name ce)
                         :payload (mapv (fn [p]
                                          {:name   (:name p)
                                           :c-type (cxc-type->c (:type p)
                                                                record-env
                                                                atom-env
                                                                enum-env)})
                                        (:payload ce))})
                      (:case-entries enum-node))}

      raw-values
      {:node       :c-enum
       :name       (:name enum-node)
       :cases      (:cases enum-node)
       :raw-values raw-values
       :raw-c-type (get {"UInt8" "uint8_t" "UInt16" "uint16_t"
                          "UInt32" "uint32_t" "UInt64" "uint64_t"}
                         raw-type)}

      :else
      {:node  :c-enum
       :name  (:name enum-node)
       :cases (:cases enum-node)})))

(defn- infer-observables-in-record-env
  "F67 L4a — mirror of `infer-observables-in-atom-env` for records.
   With record-env built (fields resolved), infer each observable's
   return type. Populates `:observables-typed` and `:observable-index`
   on each record-env entry so call-site dispatch
   (`lower-method-call`) can look up an observable's return type by
   name without recomputing.

   Bare field references get rewritten to `self.<field>` before
   inference so the type walker resolves them as record-field access
   (record-method convention; see `lower-record-observable-fn`)."
  [record-env atom-env enum-env]
  (reduce-kv
   (fn [env rec-name info]
     (let [self-fields (into {} (map (juxt :name identity) (:fields info)))
           field-names (set (map :name (:fields info)))
           typed (mapv (fn [o]
                         (let [param-locals (into {}
                                                  (map (fn [p]
                                                         [(:name p)
                                                          (cxc-type->type-kw
                                                           (:name (:type p))
                                                           record-env atom-env
                                                           enum-env p)])
                                                       (:params o)))
                               param-names (set (map :name (:params o)))
                               rewritten (rewrite-self-field-refs
                                          (:expr o) field-names param-names)
                               ctx {:record-env record-env
                                    :atom-env   atom-env
                                    :enum-env   enum-env
                                    :locals     (assoc param-locals
                                                       "self" {:record rec-name})
                                    :self-fields self-fields}
                               tk  (infer-type rewritten ctx)]
                           (assoc o :type-kw tk
                                  :c-type  (type-kw->c-type tk o))))
                       (or (:observables info) []))]
       (assoc env rec-name
              (assoc info
                     :observables-typed typed
                     :observable-index  (into {} (map (juxt :name identity) typed))))))
   record-env record-env))

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
                                   :enum-env enum-env
                                   :locals (assoc param-locals
                                                  "self" {:atom atom-name})
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
        ;; L5 — signature-only defs are registered in the protocol-env;
        ;; conformance validation, dispatch, default inheritance, and
        ;; witness semantics remain unchanged. Other
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
                                     "See plans/g3-structural-canonical/f9-class-protocol-extension.md.")
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
        ;; F38 — reject records that hold a raw agent-typed field.
        ;; Runs after atom-env is finalized (post-container-synthesis)
        ;; so `user-declared-atom?` can exclude the synthesized
        ;; List/Map/Set container classes, and before record-env is
        ;; used for observable inference / struct emission.
        _          (validate-no-agent-fields-in-records! record-env atom-env)
        record-env (infer-observables-in-record-env record-env atom-env enum-env)
        atom-env   (infer-observables-in-atom-env atom-env record-env enum-env)
        atom-env   (infer-streams-in-atom-env atom-env record-env enum-env)
        ;; L12b — augment atom-env with per-companion stream-index
        ;; entries where a subscriber demand exists. This lets the
        ;; existing per-stream synthesis (ivar/hashtable/emit/subscribe
        ;; methods) pick companions up automatically, bounding output
        ;; growth to programs that actually subscribe.
        atom-env   (augment-atom-env-with-companions atom-env record-env
                                                     enum-env)
        ;; F67 L4b — infer return types on protocol helper defaults
        ;; (observables with no matching signature) so downstream
        ;; C emission has a concrete return type when the default gets
        ;; inherited into a conforming class.
        protocol-env (infer-protocol-defaults protocol-env record-env atom-env
                                              enum-env)
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
        ;; F67 L4 — re-run observable inference on the post-compose record
        ;; env so extension-added observables get typed and land in
        ;; `:observable-index` for call-site dispatch.
        record-env (infer-observables-in-record-env record-env atom-env enum-env)
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
        ;; F15 L2.c.iii.c.ε.β.β (2026-08-24) — a shape env computed from
        ;; the RAW source AST is threaded into ctx so class/atom
        ;; lowering can consult it during `.field` access inference
        ;; (`field-type-for-owner` — ε.β.γ adds the consuming branch).
        ;; Kept separate from the tail-end lowered-AST walk (below)
        ;; that populates `:named-tuple-env` on the program AST — that
        ;; walk stays byte-identical to ε.α semantics. Duplication is
        ;; cheap and eliminates any risk of surfacing shapes on the
        ;; program AST that the ε.α tests didn't already expect.
        ctx-nt-shapes (named-tuple/collect-shapes top-nodes)
        ctx        {:record-env record-env :atom-env atom-env :enum-env enum-env
                    :symbol-table symbol-table
                    :named-tuple-env ctx-nt-shapes
                    :locals {}}
        records    (filter #(= :record (:node %)) top-nodes)
        enums      (filter #(= :enum   (:node %)) top-nodes)
        c-enums    (mapv #(lower-enum % record-env atom-env enum-env) enums)
        ;; F39 L5b-ii-a — collect + synthesize Optional-boxed value-type
        ;; typedefs. Under L5b-ii-a `collect-optional-types` returns the
        ;; empty set unconditionally (dead code); the pipeline is in
        ;; place so L5b-ii-b's flip is a single change to the collection
        ;; function. Optional typedefs precede record structs so their
        ;; `.value` field type is always in scope when a record contains
        ;; a bare-value-typed field lowered as `_Cxc_Optional_<T>`.
        optional-types (collect-optional-types record-env atom-env enum-env)
        ;; F39 L5b-ii-c — Optional typedefs whose inner is a record
        ;; must appear AFTER that record's struct definition (C requires
        ;; complete-type for a struct field). Split the set: primitive/
        ;; enum-inner typedefs precede records (they don't reference
        ;; record types); record-inner typedefs are emitted per-record
        ;; immediately after the record's own struct definition. This
        ;; assumes records don't cycle by name (spec §3.4 — value-type
        ;; records are trees).
        record-name-set (into #{} (map :name) records)
        {rec-inner-opts true prim-inner-opts false}
        (group-by #(contains? record-name-set %) optional-types)
        prim-inner-structs (synth-optional-typedefs (or prim-inner-opts []) ctx)
        rec-inner-set      (into #{} rec-inner-opts)
        ;; Emit each record struct followed by its own Optional-typedef
        ;; (when present in the set), so a downstream record whose field
        ;; is `_Cxc_Optional_<PrevRec>` sees the typedef declared.
        record-blocks
        (mapv (fn [r]
                (let [rec-struct (lower-record-struct r ctx)
                      opt-struct (when (contains? rec-inner-set (:name r))
                                   (synth-optional-typedef (:name r) ctx))]
                  (cond-> [rec-struct]
                    opt-struct (conj opt-struct))))
              records)
        structs    (into (vec prim-inner-structs)
                         (mapcat identity record-blocks))
        ;; Track A/2.a (2026-08-26) — reason-box wrapper class per
        ;; user record. Compiled unconditionally today; later tree-
        ;; shaking can restrict to records referenced by `|: as T`
        ;; (companion reason-types) or by receiver-side `<:` arms.
        reason-boxes (mapv synth-reason-box records)
        functions  (-> (record-method-functions ctx)
                       ;; F67 L4a — record observables (Proposal G
                       ;; class-body collapse: pure-data readers land
                       ;; here as C functions of self).
                       (into (record-observable-functions ctx))
                       ;; F37 — synthesized equals + hash per record
                       (into (synthesized-record-functions ctx)))
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
                                       atom-info (get atom-env ty-name)
                                       ;; L-panic-cap (2026-08-26) —
                                       ;; phantom capability atoms have
                                       ;; no atom-env entry (no runtime
                                       ;; state); skip provisioning
                                       ;; validation for them.
                                       phantom?  (contains?
                                                  #{"SystemPanic"
                                                    "_Cxc_SystemPanic"}
                                                  ty-name)]]
                           (when-not (or atom-info phantom?)
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
        ;; F37 Landings 2 + 5 — synthesized `<Record>_cxc_as_bytes` /
        ;; `<Enum>_cxc_as_bytes` use OSSwapHostToBigInt16/32/64.
        ;; Declared in <libkern/OSByteOrder.h>; Foundation transitively
        ;; pulls it in on macOS SDKs today, but add the explicit
        ;; include so a non-macOS backend (F54) or a future SDK change
        ;; doesn't silently break the encode path.
        needs-byteorder?
        (boolean
         (or (some (fn [[_ info]]
                     (and (not (record-has-user-as-method? info))
                          (seq (:fields info))
                          (every? #(f37-bytes-encodable-field? % record-env)
                                  (:fields info))))
                   record-env)
             (some (fn [[_ info]]
                     (enum-eligible-for-as-bytes-synthesis? info record-env))
                   enum-env)))
        includes          (cond-> base-includes
                            needs-abort?     (conj "<stdlib.h>")
                            needs-runtime?   (conj "<stdlib.h>")
                            needs-byteorder? (conj "<libkern/OSByteOrder.h>"
                                                    "<string.h>")
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
                            (main-atom-body main-atom ctx))
        ;; F15 L2.c.iii.c.ε.α — shared collect-shapes computation so
        ;; the typedef emit + the :named-tuple-env consumer read
        ;; from the same source of truth. Walks the LOWERED AST
        ;; (structs/classes/functions); ε.β.β (2026-08-24) added a
        ;; separate raw-AST walk earlier (`ctx-nt-shapes`) for the
        ;; lowering-phase ctx.
        nt-shapes             (named-tuple/collect-shapes
                               {:structs   structs
                                :classes   classes
                                :functions functions})]
    {:node                :objc-program
     :includes            includes
     :needs-runtime?      needs-runtime?
     :needs-dispatch-loop? needs-dispatch-loop?
     :enums               c-enums
     :structs             structs
     ;; Track A/2.a (2026-08-26) — reason-box wrapper classes, one per
     ;; user record. Emit slots them after `:structs` and before any
     ;; class @interface that might reference them (see emit/emit).
     :reason-boxes        reason-boxes
     :functions           functions
     :classes             classes
     ;; F15 L2.c.iii.b — named-tuple typedef block populated by walking
     ;; the LOWERED program AST via `collect-shapes`. Today every
     ;; `:type-named-tuple` node in a source program crashes lower with
     ;; a fail-nyi (no lowering rule handles it), so lowered ASTs never
     ;; contain named-tuple type nodes and this collection is always
     ;; empty. When L2.c.iii.c teaches lower to preserve named-tuple
     ;; type info in the lowered AST, this call surfaces the shapes
     ;; automatically and the emit hook (L2.c.iii.a) renders them into
     ;; the .m file. Zero-cost forward-compat wiring per the storage-
     ;; flip discipline (fan-out sites as guarded dead code before the
     ;; flip).
     :named-tuple-typedefs (named-tuple/emit-typedefs nt-shapes primitive-c-type)
     ;; F15 L2.c.iii.c.ε.α (2026-08-24) — the collected named-tuple
     ;; shape map, keyed by canonical mangled name. Downstream (ε.β+)
     ;; consults this to resolve field types on `.field` access
     ;; against a value with `:named-tuple` type-kw. Empty today for
     ;; every existing program (nothing exercises named-tuple ctor
     ;; params in the suite); populated automatically once a real
     ;; program uses one.
     :named-tuple-env      nt-shapes
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
