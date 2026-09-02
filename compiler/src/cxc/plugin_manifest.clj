(ns cxc.plugin-manifest
  "F75 / B3–B7 (2026-09-02) — plugin manifest resolver + linker gate +
   symbol binder + .cxc-caps emission.

   Walks a parsed top-nodes vector, locates the single `:def-plugin`
   node (R7 unified marker; spec §7.2.1), evaluates its compile-time-
   const manifest fields into a plain map, and returns a
   `:plugin-manifest` map for downstream consumers (F76 linker gate,
   F77 caps emitter). Returns `nil` when the module isn't a plugin.

   Shape of the returned manifest — the stable contract downstream
   passes rely on:

   ```
   {:name            \"std.console\"          ; String (from name: field)
    :version         {:major N :minor N :patch N}
    :compat          {:min {:major :minor :patch}
                      :max {:major :minor :patch}}
    :requires        #{:stdio :fs_read ...}   ; OsPrimitive names as kws
    :foreign-blocks  [{:library STR :entries [...]}]
    :source-node     <the original :def-plugin node — for diagnostics>}
   ```

   Field policy — matches R7 (`decisions-frozen-2026-08-31-plugin-shape.md`
   §R7 line 86): `name`, `version`, `compat` mandatory; missing any
   raises `ex-info :kind :plugin-manifest-invalid`. `requires` is
   optional at v1.0 (defaults to `#{}`) — current plugin drafts don't
   set it yet, and B7 (caps emit) will retrofit as it comes online.
   `exports:` and `targets:` are R7-defined but deferred to L6 module
   resolution / target-selection landings; not extracted here.

   Only compile-time constants admitted (spec §4.16.10 / Q10-B):
   literal strings, and prelude ctor-calls with literal-int / nested
   prelude-ctor args. Anything else in a manifest default position is
   a targeted diagnostic — the manifest is data the compiler reads
   before any runtime evaluator exists."
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [cxc.manifest    :as manifest]))

(defn- fail!
  [msg node]
  (throw (ex-info (str "plugin manifest: " msg)
                  {:kind :plugin-manifest-invalid
                   :node node})))

(defn- unwrap-nonnil
  "Manifest fields are declared `T!` per spec; strip the `:type-nonnil`
   wrapper so callers see the inner ref."
  [ty]
  (if (= :type-nonnil (:node ty)) (:inner ty) ty))

(defn- type-name
  "Return the bare type-ref name of a field's type annotation, or nil
   if the type isn't a simple ref."
  [ty]
  (let [inner (unwrap-nonnil ty)]
    (when (= :type-ref (:node inner))
      (:name inner))))

(defn- callee-name
  "Return the ident-name of a `:call`'s callee, or nil if not a bare
   ident callee."
  [call]
  (when (and (= :call (:node call))
             (= :ident (get-in call [:callee :node])))
    (get-in call [:callee :name])))

(defn- eval-semver
  "Accept a `SemVer(major, minor, patch)` call expression, return
   `{:major N :minor N :patch N}`. All three positional int args
   mandatory — named args or non-int args are rejected."
  [expr field-name]
  (when-not (= "SemVer" (callee-name expr))
    (fail! (str field-name " default must be a `SemVer(...)` call; got "
                (pr-str (:node expr)))
           expr))
  (let [args (:args expr)]
    (when-not (= 3 (count args))
      (fail! (str field-name " SemVer(...) needs exactly 3 args "
                  "(major, minor, patch); got " (count args))
             expr))
    (doseq [a args]
      (when-not (= :positional (:kind a))
        (fail! (str field-name " SemVer(...) args must be positional; "
                    "got named `" (:name a) "`")
               expr))
      (when-not (= :lit-int (get-in a [:value :node]))
        (fail! (str field-name " SemVer(...) args must be integer literals; "
                    "got " (pr-str (get-in a [:value :node])))
               expr)))
    (zipmap [:major :minor :patch]
            (mapv #(get-in % [:value :value]) args))))

(defn- eval-version-range
  "Accept a `VersionRange(min: SemVer(...), max: SemVer(...))` call,
   return `{:min {...} :max {...}}`. Both named args mandatory."
  [expr field-name]
  (when-not (= "VersionRange" (callee-name expr))
    (fail! (str field-name " default must be a `VersionRange(...)` call; got "
                (pr-str (:node expr)))
           expr))
  (let [named (into {} (map (juxt :name :value))
                    (filter #(= :named (:kind %)) (:args expr)))]
    (doseq [k ["min" "max"]]
      (when-not (contains? named k)
        (fail! (str field-name " VersionRange(...) missing required named arg `"
                    k ": SemVer(...)`")
               expr)))
    {:min (eval-semver (get named "min") (str field-name ".min"))
     :max (eval-semver (get named "max") (str field-name ".max"))}))

(defn- eval-string
  [expr field-name]
  (when-not (= :lit-string (:node expr))
    (fail! (str field-name " default must be a literal string; got "
                (pr-str (:node expr)))
           expr))
  (:value expr))

(defn- eval-os-primitive-set
  "Accept a set-literal `{OsPrimitive.stdio, OsPrimitive.fs_read, ...}`
   and return `#{:stdio :fs_read ...}` — bare snake_case case-names.
   Empty set literal `{}` returns `#{}`. Anything other than a set
   literal of `OsPrimitive.<case>` dots is a targeted diagnostic."
  [expr field-name]
  (when-not (= :set (:node expr))
    (fail! (str field-name " default must be a set literal "
                "`{OsPrimitive.stdio, ...}`; got " (pr-str (:node expr)))
           expr))
  (into #{}
        (map (fn [e]
               (when-not (and (= :dot (:node e))
                              (= :ident (get-in e [:target :node]))
                              (= "OsPrimitive" (get-in e [:target :name])))
                 (fail! (str field-name " set entries must be `OsPrimitive.<case>`; "
                             "got " (pr-str e))
                        e))
               (keyword (:field e))))
        (:elements expr)))

(defn- fields->map
  "Index a `:fields` vector by field name for O(1) lookup."
  [fields]
  (into {} (map (juxt :name identity)) fields))

(defn- extract-required
  [by-name field-name eval-fn]
  (let [f (get by-name field-name)]
    (when-not f
      (fail! (str "missing required manifest field `" field-name ":`. "
                  "R7: name/version/compat are mandatory (spec §7.2.1).")
             nil))
    (when-not (:default f)
      (fail! (str "field `" field-name ":` requires an inline default "
                  "(compile-time-const per §4.16.10).")
             f))
    (eval-fn (:default f) field-name)))

(defn- extract-optional
  [by-name field-name eval-fn default-value]
  (if-let [f (get by-name field-name)]
    (if (:default f)
      (eval-fn (:default f) field-name)
      default-value)
    default-value))

(defn find-def-plugin
  "Return the single `:def-plugin` top-node in `top-nodes`, or nil if
   the module isn't a plugin. Raises on duplicate markers — the parser
   already gates reserved-name discipline, but a defensive check keeps
   this pass total."
  [top-nodes]
  (let [ps (filterv #(= :def-plugin (:node %)) top-nodes)]
    (case (count ps)
      0 nil
      1 (first ps)
      (throw (ex-info (str "plugin manifest: module has " (count ps)
                           " `def plugin:` markers; exactly one allowed "
                           "(spec §7.2.1). Merge them or split the module.")
                      {:kind :plugin-manifest-invalid
                       :count (count ps)})))))

(defn- use-section-caps
  "Return the `:caps` vector from an atom body's use-section (if
   present). Empty when the atom has no `use:` block."
  [atom-node]
  (or (some (fn [n] (when (= :use-section (:node n)) (:caps n)))
            (:body atom-node))
      []))

(defn- plugin-capability-use?
  "True when a cap declaration binds the `Plugin` prelude capability
   (`use <name>: Plugin`). The bind-name is irrelevant — spec §7.2.1
   / R11 gate on the type, not the local ident."
  [cap]
  (= "Plugin" (get-in cap [:type :name])))

(defn- atom-nodes
  "Return top-level nodes that carry a `:body` and could conceivably
   declare a `use:` (currently only `:def-atom` per parse, but the
   scan is defensive)."
  [top-nodes]
  (filter #(and (map? %) (:body %) (= :def-atom (:node %))) top-nodes))

(defn validate-plugin-capability-uses!
  "F76 / B4 (2026-09-02) — linker gate. `use <name>: Plugin` inside an
   atom body is only legal when the enclosing module carries a
   `def plugin:` marker (spec §7.2.1, R11 plugin-only wave). Enforces
   the grammatical scope R11 established: only plugin modules can
   reach the FFI capability.

   Called after parse + prelude-collision check, before lowering.
   No-op on modules with no plugin uses; throws `ex-info :kind
   :plugin-capability-outside-plugin-module` on the first violation.

   Non-plugin modules that legitimately want a capability atom import
   its exports (`use console: Console`) — Console itself lives in a
   plugin file and holds the `use plugin: Plugin` binding there."
  [top-nodes]
  (let [has-plugin? (some? (find-def-plugin top-nodes))]
    (when-not has-plugin?
      (doseq [a    (atom-nodes top-nodes)
              cap  (use-section-caps a)
              :when (plugin-capability-use? cap)]
        (throw (ex-info
                (str "plugin capability: `use " (:name cap) ": Plugin` in atom `"
                     (:name a) "` requires a `def plugin:` marker at module top-level "
                     "(spec §7.2.1, R11 plugin-only wave 2026-09-01). "
                     "Non-plugin modules import capability atoms from plugins "
                     "instead (`use console: Console`).")
                {:kind        :plugin-capability-outside-plugin-module
                 :atom-name   (:name a)
                 :cap-name    (:name cap)}))))))

;; ============================================================================
;; B5 (2026-09-02) — plugin symbol resolver
;;
;; Compile-time binding of `plugin.get("sym")` against the enclosing plugin
;; module's `foreign "..."` blocks. Cross-plugin symbol reach is grammatically
;; impossible under R11 — each module carries its own manifest, so the index
;; is naturally per-module.
;;
;; Index shape (built from a manifest's :foreign-blocks):
;;   {"sym-name" {:library STR :signature <sig-node> :entry <full-entry-map>}}
;;
;; Collision policy: two `foreign "..."` blocks in the same module declaring
;; the same symbol name → throw at index-build. Author must rename or drop
;; one. R7 said blocks' namespaces are per-library-disjoint by convention;
;; this makes it a hard rule.
;; ============================================================================

(defn build-foreign-index
  "Given a plugin manifest's `:foreign-blocks` vector, return a per-symbol
   lookup index. Throws `ex-info :kind :plugin-foreign-symbol-collision`
   when the same symbol appears in more than one block."
  [foreign-blocks]
  (reduce
   (fn [idx {:keys [library entries]}]
     (reduce
      (fn [idx entry]
        (if-not (= :symbol (:kind entry))
          idx
          (let [sym (first (:name-strs entry))]
            (when (contains? idx sym)
              (throw (ex-info
                      (str "plugin foreign symbol `" sym "` declared in "
                           "both `" (get-in idx [sym :library]) "` and `"
                           library "` blocks. Symbols must be unique across "
                           "a plugin's foreign blocks (R7 per-library "
                           "disjoint namespaces).")
                      {:kind          :plugin-foreign-symbol-collision
                       :symbol        sym
                       :first-library (get-in idx [sym :library])
                       :second-library library})))
            (assoc idx sym {:library   library
                            :signature (:signature entry)
                            :entry     entry}))))
      idx
      entries))
   {}
   foreign-blocks))

(defn- plugin-method-call?
  "True when `expr` is `plugin.<method>(args...)` for one of the recognized
   plugin methods (`get`, `send`, `class_send`)."
  [expr]
  (and (map? expr)
       (= :call (:node expr))
       (let [c (:callee expr)]
         (and (map? c)
              (= :dot (:node c))
              (= :ident (get-in c [:target :node]))
              (= "plugin" (get-in c [:target :name]))
              (contains? #{"get" "send" "class_send"} (:field c))))))

(defn- plugin-method
  "Return the method name string of a `plugin.<method>(...)` call."
  [expr]
  (get-in expr [:callee :field]))

(defn- first-string-arg
  "Return the string value of `expr`'s first positional arg if it's a
   literal string; nil otherwise."
  [expr]
  (let [a (first (:args expr))]
    (when (and a
               (= :positional (:kind a))
               (= :lit-string (get-in a [:value :node])))
      (get-in a [:value :value]))))

(defn resolve-plugin-get
  "Resolve a `plugin.get(LIT-STR)` call against a foreign-index. Returns
   the index entry `{:library :signature :entry}`. Throws
   `ex-info :kind :plugin-symbol-unresolved` when the symbol is not in
   any foreign block, or `:plugin-get-non-literal-arg` when the first
   arg is not a literal string."
  [call-node index]
  (let [sym (first-string-arg call-node)]
    (when-not sym
      (throw (ex-info
              (str "plugin.get expects a literal string argument at compile "
                   "time (compile-time symbol binding); got "
                   (pr-str (get-in call-node [:args 0 :value :node])))
              {:kind :plugin-get-non-literal-arg
               :call call-node})))
    (or (get index sym)
        (throw (ex-info
                (str "plugin.get(\"" sym "\"): symbol not declared in any "
                     "`foreign \"...\"` block of this plugin's manifest. "
                     "Add `symbol \"" sym "\" -> <signature>` to a foreign "
                     "block, or check for a typo.")
                {:kind   :plugin-symbol-unresolved
                 :symbol sym
                 :call   call-node})))))

(defn- walk-plugin-calls
  "Depth-first walk over `x` invoking `f` on every node matching
   `plugin-method-call?`. Pure traversal — `f` returns nothing; used
   for validation side-effects."
  [x f]
  (cond
    (plugin-method-call? x)
    (do (f x)
        (doseq [a (:args x)]
          (walk-plugin-calls (:value a) f)))

    (map? x)
    (doseq [v (vals x)]
      (walk-plugin-calls v f))

    (sequential? x)
    (doseq [v x]
      (walk-plugin-calls v f))))

(defn validate-plugin-symbol-refs!
  "B5 (2026-09-02). Given parsed top-nodes and their extracted plugin
   manifest (from `extract`), walk every atom body for `plugin.get`,
   `plugin.send`, and `plugin.class_send` calls. Resolve each `plugin.get`
   against the manifest's foreign-block symbols; throw with a targeted
   diagnostic on unresolved symbols. `.send` / `.class_send` currently
   raise `ex-info :kind :plugin-method-nyi` — B5 MVP covers `.get` only;
   selector-based calls land when a plugin fixture needs them.

   No-op when `manifest` is nil (module is not a plugin — B4 already
   rejected any `use <name>: Plugin` in that case)."
  [top-nodes manifest]
  (when manifest
    (let [index (build-foreign-index (:foreign-blocks manifest))]
      (doseq [a (atom-nodes top-nodes)]
        (walk-plugin-calls
         (:body a)
         (fn [call]
           (case (plugin-method call)
             "get"
             (resolve-plugin-get call index)

             ("send" "class_send")
             (throw (ex-info
                     (str "plugin." (plugin-method call) " is not yet "
                          "implemented at v1.0. Selector-call resolution "
                          "against `foreign \"...\": selector \"...\"` "
                          "entries lands when a plugin fixture needs it. "
                          "Meanwhile, use `plugin.get(\"c-symbol\")` for "
                          "libc / cxc_runtime bindings.")
                     {:kind      :plugin-method-nyi
                      :method    (plugin-method call)
                      :atom-name (:name a)})))))))))

;; ============================================================================
;; FL-1 (2026-09-02) — parse-post rewrite of `plugin.get(LIT)` into
;; `:foreign-ref` AST nodes so lower/emit don't need to understand the
;; `plugin.<method>` grammar. Runs after B5 validation (so every LIT is
;; guaranteed to resolve). The rewrite is a whole-AST walk with
;; substitution in expression position.
;;
;; Output shape:
;;   {:node       :foreign-ref
;;    :library    "libc"                       ; from manifest's foreign-index
;;    :symbol     "puts"
;;    :signature  <parsed signature type-node> ; from the foreign entry
;;    :sig-kind   :symbol                      ; matches foreign-entry :kind
;;    :orig-node  <the original :call node>}   ; for diagnostics
;;
;; Not rewritten: `plugin.send` / `plugin.class_send` — B5 raises NYI
;; before this pass runs, so they never reach here.
;; ============================================================================

(defn- rewrite-plugin-get-node
  "If `x` is a `plugin.get(LIT-STR)` call and the literal resolves in
   `index`, return the `:foreign-ref` replacement. Otherwise return `x`
   unchanged."
  [x index]
  (if-not (and (plugin-method-call? x)
               (= "get" (plugin-method x)))
    x
    (let [sym (first-string-arg x)]
      (if-let [entry (get index sym)]
        {:node      :foreign-ref
         :library   (:library entry)
         :symbol    sym
         :signature (:signature entry)
         :sig-kind  (get-in entry [:entry :kind])
         :orig-node x}
        x))))

(defn- rewrite-plugin-refs*
  "Depth-first postwalk replacing `plugin.get(LIT)` calls with
   `:foreign-ref` nodes. Structural — preserves record types, order of
   maps/vectors, and only touches nodes that match. Skips already-
   rewritten `:foreign-ref` subtrees to preserve idempotence: the
   `:orig-node` slot holds the original `:call` and must NOT be
   re-walked (that would re-wrap into `:foreign-ref{:orig :foreign-ref{...}}`)."
  [x index]
  (cond
    (and (map? x) (= :foreign-ref (:node x)))
    x

    (map? x)
    (let [walked (into {}
                       (map (fn [[k v]] [k (rewrite-plugin-refs* v index)]))
                       x)]
      (rewrite-plugin-get-node walked index))

    (vector? x)
    (mapv #(rewrite-plugin-refs* % index) x)

    (sequential? x)
    (map #(rewrite-plugin-refs* % index) x)

    :else x))

(defn rewrite-plugin-refs
  "FL-1 entry point. Given parsed top-nodes and the module's extracted
   plugin manifest, return a new top-nodes vector with every
   `plugin.get(LIT)` call replaced by a `:foreign-ref` node carrying
   `{:library :symbol :signature :sig-kind :orig-node}`. Returns
   `top-nodes` unchanged when `manifest` is nil (non-plugin module).

   Idempotent: running twice produces the same output as running once.

   Runs AFTER `validate-plugin-symbol-refs!` — every literal is
   guaranteed to resolve, so the rewrite is total on plugin.get calls."
  [top-nodes manifest]
  (if-not manifest
    top-nodes
    (let [index (build-foreign-index (:foreign-blocks manifest))]
      (mapv #(rewrite-plugin-refs* % index) top-nodes))))

;; ============================================================================
;; FL-11 (2026-09-02) — foreign-alias field elision.
;;
;; A field with fn-type annotation and a `:foreign-ref` default is a
;; compile-time symbolic alias — not runtime storage. We rewrite each
;; `self.<name>(args...)` in the atom body to `<foreign-ref>(args...)`
;; so lower/FL-3 emits the direct C call, and we drop the field from
;; the atom's data-section so lower never sees an ivar for it.
;;
;;   def Console:
;;       use plugin: Plugin
;;       _write: (Int32, Ptr of Byte, SizeT) -> SsizeT = plugin.get("write")
;;       hi() :- Int32 = self._write(1, "hi\n", 3)
;;   →
;;   def Console:
;;       use plugin: Plugin
;;       hi() :- Int32 = plugin.get("write")(1, "hi\n", 3)  ; conceptually
;;
;; No new fields, no runtime ivars, no init logic — the alias is
;; purely a source-level ergonomic wrapper around the FFI call.
;; Trade-off: `self._write` isn't a first-class value (can't be
;; passed as a fn arg). Real function-pointer storage is a follow-on
;; landing when a fixture asks.
;; ============================================================================

(defn- foreign-alias-field?
  "True when a field-decl is a foreign-symbol alias — has a
   `:foreign-fn-type` type annotation AND a `:foreign-ref` default
   (already resolved by FL-1)."
  [field]
  (and (= :field (:node field))
       (= :foreign-fn-type (get-in field [:type :node]))
       (= :foreign-ref    (get-in field [:default :node]))))

(defn- collect-alias-map
  "Walk an atom's body; return `{field-name → :foreign-ref-node}` for
   each foreign-alias field discovered inside its `:data-section`s."
  [atom-node]
  (into {}
        (comp
         (filter #(= :data-section (:node %)))
         (mapcat :fields)
         (filter foreign-alias-field?)
         (map (juxt :name :default)))
        (:body atom-node)))

(defn- drop-alias-fields
  "Return atom body with alias fields removed from every data-section."
  [body aliases]
  (mapv (fn [n]
          (if (= :data-section (:node n))
            (assoc n :fields
                   (vec (remove (fn [f]
                                  (and (foreign-alias-field? f)
                                       (contains? aliases (:name f))))
                                (:fields n))))
            n))
        body))

(defn- self-alias-call?
  "True when `x` is `:call {:callee {:dot :target :self :field NAME}}`
   and NAME is in the alias map."
  [x aliases]
  (and (map? x)
       (= :call (:node x))
       (let [c (:callee x)]
         (and (map? c)
              (= :dot (:node c))
              (= :self (get-in c [:target :node]))
              (contains? aliases (:field c))))))

(defn- rewrite-self-alias-calls*
  "Depth-first walk replacing `self.<alias-name>(args)` with a `:call`
   whose callee is the aliased `:foreign-ref` — same shape FL-3 expects."
  [x aliases]
  (cond
    (self-alias-call? x aliases)
    (let [foreign-ref (get aliases (get-in x [:callee :field]))
          new-args    (mapv (fn [a]
                              (update a :value #(rewrite-self-alias-calls* % aliases)))
                            (:args x))]
      (assoc x :callee foreign-ref :args new-args))

    (map? x)
    (into {}
          (map (fn [[k v]] [k (rewrite-self-alias-calls* v aliases)]))
          x)

    (vector? x) (mapv #(rewrite-self-alias-calls* % aliases) x)
    (sequential? x) (map #(rewrite-self-alias-calls* % aliases) x)
    :else x))

(defn- rewrite-atom-foreign-aliases
  "Per-atom pass: collect the alias map, drop the alias field decls,
   rewrite `self.<alias>(args)` call sites in the body to direct
   foreign-ref calls. Non-atom nodes pass through unchanged."
  [node]
  (if-not (= :def-atom (:node node))
    node
    (let [aliases (collect-alias-map node)]
      (if (empty? aliases)
        node
        (-> node
            (update :body drop-alias-fields aliases)
            (update :body rewrite-self-alias-calls* aliases))))))

(defn rewrite-foreign-alias-fields
  "FL-11 entry point. Run per-atom `rewrite-atom-foreign-aliases` across
   every top-node. Called AFTER `rewrite-plugin-refs` so foreign-alias
   fields already carry `:foreign-ref` defaults. Idempotent — a second
   pass finds no alias fields left in the AST."
  [top-nodes]
  (mapv rewrite-atom-foreign-aliases top-nodes))

(defn extract
  "F75 manifest resolver entry point. Given a parsed top-nodes vector,
   returns the plugin manifest map (see ns docstring) or `nil` when
   no `:def-plugin` marker is present. Throws
   `ex-info :kind :plugin-manifest-invalid` for missing required
   fields or wrong-shape defaults."
  [top-nodes]
  (when-let [dp (find-def-plugin top-nodes)]
    (let [by-name (fields->map (:fields dp))
          allowed #{"name" "version" "compat" "requires"}
          unknown (remove allowed (keys by-name))]
      (when (seq unknown)
        (fail! (str "unknown manifest field(s): "
                    (str/join ", " (map #(str "`" % ":`") (sort unknown)))
                    ". R7 v1.0 admits name/version/compat/requires only "
                    "(exports/targets deferred to L6).")
               dp))
      (doseq [fname ["name" "version" "compat"]
              :let  [f (get by-name fname)]
              :when f
              :let  [tname (type-name (:type f))]]
        (let [expected (case fname
                         "name"    "String"
                         "version" "SemVer"
                         "compat"  "VersionRange")]
          (when-not (= tname expected)
            (fail! (str "field `" fname ":` must be typed `" expected "!`; "
                        "got `" (pr-str tname) "`.")
                   f))))
      {:name           (extract-required by-name "name"    eval-string)
       :version        (extract-required by-name "version" eval-semver)
       :compat         (extract-required by-name "compat"  eval-version-range)
       :requires       (extract-optional by-name "requires"
                                         eval-os-primitive-set
                                         #{})
       :foreign-blocks (mapv (fn [b]
                               {:library (:library b)
                                :entries (:entries b)})
                             (:foreign-blocks dp))
       :source-node    dp})))

;; ============================================================================
;; B7 / F77 (2026-09-02) — .cxc-caps manifest emission.
;;
;; Per-binary JSON manifest listing plugins present + unioned OS primitives.
;; Consumers: CI audit tools, package managers, per-target enforcement emit
;; (Darwin entitlements, Linux seccomp-bpf, OpenBSD pledge). Documentation-
;; only on non-enforcing platforms.
;;
;; Shape (schema-tag `cxc-caps/v1`):
;;   {"schema":        "cxc-caps/v1"
;;    "cxc_version":   "0.11.0"
;;    "target":        "macos"
;;    "plugins":       [{"name": STR, "version": "X.Y.Z"} ...]      ; sorted by name
;;    "os_primitives": ["clock_monotonic", "fs_read", ...]}         ; sorted set
;;
;; Deferred (aspirational per §7 spec, not v1.0):
;; - binary_sha256   — needs post-clang hash
;; - capability_atoms_used  — needs cross-module import + use analysis
;; - enforcement     — per-target primitive → syscall/entitlement mapping
;; ============================================================================

(def caps-schema-tag "cxc-caps/v1")

(defn- version->string
  "Render a `{:major :minor :patch}` map as canonical `X.Y.Z`."
  [{:keys [major minor patch]}]
  (str major "." minor "." patch))

(defn build-caps-map
  "Given a seq of extracted plugin manifests (nils filtered), a target
   keyword, and a cxc-version string, produce the JSON-serializable
   caps map. Deterministic ordering: plugins sorted by name,
   primitives sorted alphabetically."
  [manifests target cxc-version]
  (let [ms      (->> manifests
                     (filter some?)
                     (sort-by :name))
        prims   (->> ms
                     (mapcat :requires)
                     (map name)
                     (into (sorted-set)))]
    {:schema        caps-schema-tag
     :cxc_version   cxc-version
     :target        (name target)
     :plugins       (mapv (fn [m]
                            {:name    (:name m)
                             :version (version->string (:version m))})
                          ms)
     :os_primitives (vec prims)}))

(defn emit-caps!
  "Serialize `caps-map` as JSON and write to `<bin-path>.cxc-caps`.
   Returns the written File. `bin-path` is the compiled binary path
   (per compile-parsed-to-file's :bin-path return)."
  [bin-path caps-map]
  (let [f (io/file (str bin-path ".cxc-caps"))]
    (io/make-parents f)
    (spit f (manifest/write-json caps-map))
    f))

(defn extract-all-manifests
  "Given a seq of parsed per-file top-nodes vectors, extract each file's
   plugin manifest (returning nil for non-plugin files). Deterministic
   pass-through of order."
  [per-file-nodes]
  (mapv extract per-file-nodes))
