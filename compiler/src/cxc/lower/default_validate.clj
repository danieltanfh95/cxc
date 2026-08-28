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
  (:require [cxc.diagnostics :refer [fail-user!]]))

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
          (fail-user! (str "required param `" (:name p) "` follows a defaulted "
                           "param on " site " — spec §3.8.5 requires "
                           "defaulted params to come last")
                      {:site site :param (:name p)})
          (recur (or seen-default? (some? (:default p)))
                 (next ps)))))))

(defn default-expr-const-shape?
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
