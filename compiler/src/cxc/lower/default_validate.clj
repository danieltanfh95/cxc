(ns cxc.lower.default-validate
  "Parameter/field default-value validation (spec §3.8.5).

   Two rules covered here:
     1. All defaulted params must follow all non-defaulted params
        (rule 3) — `validate-defaults-after-required!`.
     2. Default expressions must be compile-time constants (rule 1)
        — `validate-default-is-const!`, backed by the shape predicate
        `default-expr-const-shape?`.

   Fired at the sites that own a params/fields collection: observable
   entries, handler-decl entries, record-env builds, and ctor-param
   merging in `lower-def-atom`."
  (:require [cxc.diagnostics :refer [fail-user!]]
            [clojure.string :as str]))

(defn validate-defaults-after-required!
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
          (let [defaulted (filter :default params)
                required  (filter #(nil? (:default %)) params)
                order-hint (str "Reorder: put all required params ("
                                (str/join ", "
                                                     (map #(str "`" (:name %) "`") required))
                                ") FIRST, then defaulted params ("
                                (str/join ", "
                                                     (map #(str "`" (:name %) "`") defaulted))
                                ").")]
            (fail-user! (str "required param `" (:name p) "` follows a defaulted "
                             "param on " site " — spec §3.8.5 requires "
                             "defaulted params to come last. " order-hint)
                        (merge {:site site :param (:name p)}
                               (select-keys p [:line :col]))))
          (recur (or seen-default? (some? (:default p)))
                 (next ps)))))))

(defn default-expr-const-shape?
  "Spec §3.8.5 rule 1: default expressions must be compile-time
   constants. Permitted forms track spec §4.16.10 (Q10-B relaxation
   ratified 2026-08-29 + landed 2026-08-30):

   Baseline (always):
     - Primitive literals: int/float/string/char/bool/nil.
     - `:ident` (value-alias reference — validated by alias table
       downstream; treated permissively at this shape check).
     - Unary `-` / `not` wrapping a permitted form.

   With `atom-env` (F34 L1, 2026-08-18):
     - `:call` whose callee is a bare `:ident` naming an atom in
       `atom-env`. Enables the spec-canonical
       `<name>: <TypeName> = <TypeName>()` field-default shape (§1.11).

   With `enum-env` (F16 L3, 2026-08-23):
     - `:dot` whose target is a bare `:ident` naming an enum in
       `enum-env` and whose field is one of that enum's bare
       (no-payload) cases — `Direction.up`.

   Q10-B relaxation (2026-08-30):
     - `:list` collection literals when every `:items` element is
       itself a compile-time constant (transitive). Empty `:list`
       qualifies. Collection literals in defaults are safe because
       cxc collections are persistent per F33 — the compile-time-
       folded value is shared safely across call sites (no mutable-
       default footgun).
     - Payload'd enum-case invocations `Enum.case(arg, arg, ...)`
       when every argument is itself a compile-time constant. The
       payload check uses `enum-env`'s `:payload-index` (already
       populated per F16 L2). Both dotted-call form
       `:call` over `:dot` and bare-payload form (if the parser
       lifts one) are accepted.

   Explicitly still rejected: non-ctor function/method/observable
   calls, `use:`-capability reads, receive-glyph interactions,
   message sends, subscripts, binops other than the arithmetic/
   comparison combinators listed in spec §4.16.10 (deferred to a
   later relaxation landing — this predicate widens conservatively).

   Non-const arithmetic combinators (`+`, `-` binary, `*`, `/`, `%`,
   `and`, `or`, comparisons) are NOT accepted here yet even though
   spec §4.16.10 lists them as compile-time constants. Their
   folding requires an evaluation pass (produces a normalized
   literal AST node); this predicate is a shape check, not an
   evaluator. Callers that need arithmetic-const support must
   invoke a folder pass before checking shape."
  ([expr] (default-expr-const-shape? expr nil nil))
  ([expr atom-env] (default-expr-const-shape? expr atom-env nil))
  ([expr atom-env enum-env]
   (case (:node expr)
     (:lit-int :lit-float :lit-string :lit-char
      :lit-nil :lit-bool)             true
     :ident                           true
     (:neg :not)                      (default-expr-const-shape? (:expr expr) atom-env enum-env)
     :list                            (every? #(default-expr-const-shape? % atom-env enum-env)
                                              (:items expr))
     :call                            (let [callee (:callee expr)]
                                        (cond
                                          ;; F34 L1 — atom-type ctor call.
                                          (and (some? atom-env)
                                               (map? callee)
                                               (= :ident (:node callee))
                                               (contains? atom-env (:name callee)))
                                          (every? #(default-expr-const-shape?
                                                    (if (map? %) (or (:value %) %) %)
                                                    atom-env enum-env)
                                                  (:args expr))

                                          ;; Q10-B — payload'd enum-case call
                                          ;; `Enum.case(arg, arg, ...)`. Parser
                                          ;; produces a `:call` whose callee is
                                          ;; a `:dot` naming a payload-carrying
                                          ;; case; args must all be const.
                                          (and (some? enum-env)
                                               (map? callee)
                                               (= :dot (:node callee))
                                               (let [target (:target callee)
                                                     fname  (:field callee)]
                                                 (and (map? target)
                                                      (= :ident (:node target))
                                                      (let [en   (:name target)
                                                            info (get enum-env en)]
                                                        (and info
                                                             (contains? (set (:cases info)) fname)
                                                             (seq (get-in info [:payload-index fname])))))))
                                          (every? #(default-expr-const-shape?
                                                    (if (map? %) (or (:value %) %) %)
                                                    atom-env enum-env)
                                                  (:args expr))

                                          :else false))
     :dot                             (let [target (:target expr)
                                            fname  (:field expr)]
                                        (and (some? enum-env)
                                             (map? target)
                                             (= :ident (:node target))
                                             (let [en   (:name target)
                                                   info (get enum-env en)]
                                               (and info
                                                    (contains? (set (:cases info)) fname)
                                                    (empty? (get-in info [:payload-index fname]))))))
     false)))

(defn validate-default-is-const!
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
       (fail-user! (str "default for `" (:name p) "` on " site
                       " is not a compile-time constant (spec §3.8.5 "
                       "permits primitive literals, `nil`, unary `-`/"
                       "`not` on those, a value-alias identifier, or a"
                       " bare enum-case access `Enum.case`); "
                       "got `" (:node d) "` node")
                  {:site site :param (:name p) :default-node (:node d)})))))
