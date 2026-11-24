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
  (:require [clojure.data.json :as json]
            [clojure.java.io   :as io]
            [clojure.string    :as str]
            [clojure.walk      :as walk]
            [cxc.diagnostics   :as diag]
            [cxc.util.walk     :refer [tree-nodes]]))

(set! *warn-on-reflection* true)

(defn- fail!
  [msg node]
  (diag/fail-compile! :plugin-manifest-invalid
                      (str "plugin manifest: " msg)
                      node))

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

(defn- eval-runtime-shape
  "Accept a `RuntimeShape.<case>` dot expression, return the case as a
   keyword. Legal cases: `:dispatch-main` (default), `:main-runloop`.

   Prototype note (2026-09-26): this is Prototype A of the
   runtime-shape landing (docs/design/runtime-shape.md). Compile-time
   selection via a plugin manifest field. The compiler reads this
   value and emits `main()` accordingly:
   `.dispatch_main` → today's `dispatch_main()` shape;
   `.main_runloop` → `[NSApp run]` shape for AppKit ownership;
   `.uikit` → `UIApplicationMain` shape for iOS, with a cxc-owned
   `CxcAppDelegate` that bridges UIApplication callbacks into
   lifecycle messages on the user's `def main:` atom.

   Only one non-default value per binary is permitted (F76 linker gate
   enforces uniqueness across imported plugins)."
  [expr field-name]
  (let [known #{"dispatch_main" "main_runloop" "uikit"}]
    (when-not (and (= :dot (:node expr))
                   (= :ident (get-in expr [:target :node]))
                   (= "RuntimeShape" (get-in expr [:target :name])))
      (fail! (str field-name " default must be `RuntimeShape.<case>`; "
                  "got " (pr-str (:node expr)))
             expr))
    (when-not (contains? known (:field expr))
      (fail! (str field-name " RuntimeShape.<case> must be one of "
                  (str/join ", " (sort known))
                  "; got `" (:field expr) "`.")
             expr))
    (keyword (str/replace (:field expr) \_ \-))))

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
      (diag/fail-compile! :plugin-manifest-invalid
                          (str "plugin manifest: module has " (count ps)
                               " `def plugin:` markers; exactly one allowed "
                               "(spec §7.2.1). Merge them or split the module.")
                          (first ps)
                          {:count (count ps)}))))

(defn- build-plugin-get-call
  "A+B (2026-11-20) — synthesize the AST for
     `plugin.get(\"<c-sym>\")(<param-ident>, ...)`
   that becomes the body of a wrapper observable. Mirrors the shape
   `parse-observable` produces for an inline `:- expr` method body
   (bare `:call`, no `:do-block-expr` wrap — see the probe in
   docs/design/streamline-candidates.md A+B landing)."
  [c-sym params]
  {:node :call
   :callee {:node :call
            :callee {:node :dot
                     :target {:node :ident :name "plugin"}
                     :field  "get"}
            :args [{:kind :positional
                    :value {:node :lit-string :value c-sym}}]}
   :args (mapv (fn [p]
                 {:kind :positional
                  :value {:node :ident :name (:name p)}})
               params)})

(defn- auto-method->observable
  "Shape one `:auto-method` entry (produced by parse's
   `symbol \"...\" as <method>(...)` form) into the `:observable` AST
   node the synthesized wrapper atom hosts. The `:expr` is a
   `plugin.get(\"<c-sym>\")(args)` call; `:return-type` carries the
   declared Corvid return type so dispatch knows the ObjC method's
   signature authoritatively."
  [c-sym auto-method]
  {:node        :observable
   :name        (:name auto-method)
   :params      (:params auto-method)
   :expr        (build-plugin-get-call c-sym (:params auto-method))
   :return-type (:return-type auto-method)})

(defn- collect-auto-methods
  "Walk every foreign block in a `:def-plugin` node and return a flat
   vector of `[c-sym auto-method]` pairs for each entry that carries
   `:auto-method` metadata. Entries using the explicit long form
   contribute nothing."
  [def-plugin]
  (into []
        (comp (mapcat :entries)
              (keep (fn [e]
                      (when-let [am (:auto-method e)]
                        [(first (:name-strs e)) am]))))
        (:foreign-blocks def-plugin)))

(defn- node-defines-name?
  "True when `node` is a top-level definition (atom, record, enum,
   protocol) binding the given name. Used by the wrapper-atom
   collision check so a `def plugin Foo:` can't silently shadow a
   `def Foo:` record or enum."
  [node name]
  (and (map? node)
       (contains? #{:def-atom :def-record :def-enum :def-protocol}
                  (:node node))
       (= name (:name node))))

(defn- atom-has-inline-foreign?
  "True when `atom-node` is a :def-atom carrying :foreign-block entries
   in its :body. hickey-cycle-302 pre-pass — grammar allowed since R1
   was relaxed in cycle-301."
  [atom-node]
  (and (= :def-atom (:node atom-node))
       (some #(= :foreign-block (:node %)) (:body atom-node))))

(defn- mk-lit-string [s]
  {:node :lit-string :value s})

(defn- mk-lit-int [n]
  {:node :lit-int :value n})

(defn- mk-semver-call [major minor patch]
  {:node :call
   :callee {:node :ident :name "SemVer"}
   :args [{:kind :positional :value (mk-lit-int major)}
          {:kind :positional :value (mk-lit-int minor)}
          {:kind :positional :value (mk-lit-int patch)}]})

(defn- mk-version-range-call [min-sv max-sv]
  {:node :call
   :callee {:node :ident :name "VersionRange"}
   :args [{:kind :named :name "min" :value min-sv}
          {:kind :named :name "max" :value max-sv}]})

(defn- mk-synthetic-plugin-manifest-fields
  "Build name/version/compat field defaults for a synthetic def-plugin
   lifted from atom-inline foreign blocks (hickey-cycle-302). Values
   are placeholders — in-tree foreign-in-atom doesn't carry
   distribution identity. The manifest resolver reads these so its
   mandatory-field check passes; emit-caps records them as-is."
  []
  [{:node :field :name "name"
    :type {:node :type-nonnil
           :inner {:node :type-ref :name "String"}}
    :default (mk-lit-string "<synthetic-inline-foreign>")}
   {:node :field :name "version"
    :type {:node :type-nonnil
           :inner {:node :type-ref :name "SemVer"}}
    :default (mk-semver-call 0 0 0)}
   {:node :field :name "compat"
    :type {:node :type-nonnil
           :inner {:node :type-ref :name "VersionRange"}}
    :default (mk-version-range-call (mk-semver-call 0 0 0)
                                     (mk-semver-call 99 99 99))}])

(defn absorb-atom-foreign-blocks
  "hickey-cycle-302 (2026-11-24) — pre-pass that enables the unified-FFI
   shape: a regular `def Atom:` body may declare `foreign \"libname\":`
   blocks inline. R1 was relaxed in cycle-301 so the grammar parses this
   shape; this pass makes it compile end-to-end by reusing the existing
   plugin+peer-atom machinery.

   For each :def-atom with :foreign-block entries in its :body:
   1. Extract the foreign blocks from the atom body.
   2. Lift them into a module-level :def-plugin — append to an existing
      one, or synthesize a new one with placeholder manifest fields.
   3. Synthesize a plugin.get-call :observable on the atom body for
      each `symbol \"...\" as <method>` entry carrying :auto-method
      metadata (same shape synthesize-plugin-wrapper-atom builds).
   4. Inject `use plugin: Plugin` into the atom body if not already
      present.

   After this pass, downstream (manifest resolver, plugin.get resolver,
   lower/emit) sees a module with one :def-plugin + one or more peer
   atoms — the shape the pipeline already handles.

   R2 (one :def-plugin per module) stays intact: lifted foreign blocks
   append to any existing plugin, never create a second one."
  [parsed]
  (let [atoms-with-foreign (filterv atom-has-inline-foreign? parsed)]
    (if (empty? atoms-with-foreign)
      parsed
      (let [existing-plugin (find-def-plugin parsed)
            ;; Strip :auto-method from lifted entries — the absorb pass
            ;; has already synthesized the observable on the owning atom,
            ;; so the downstream `synthesize-plugin-wrapper-atom` must
            ;; not see these entries as orphan-`as` candidates. :signature
            ;; stays intact for FFI-dispatch typing.
            strip-auto      (fn [e] (dissoc e :auto-method))
            strip-block     (fn [b] (update b :entries #(mapv strip-auto %)))
            lifted-blocks   (into []
                                  (comp (mapcat (fn [a]
                                                  (filter #(= :foreign-block (:node %))
                                                          (:body a))))
                                        (map strip-block))
                                  atoms-with-foreign)
            new-plugin
            (if existing-plugin
              (update existing-plugin :foreign-blocks
                      (fnil into []) lifted-blocks)
              {:node :def-plugin
               :name "plugin"
               :fields (mk-synthetic-plugin-manifest-fields)
               :foreign-blocks lifted-blocks})
            rewrite-atom
            (fn [a]
              (if-not (atom-has-inline-foreign? a)
                a
                (let [orig-body    (:body a)
                      foreigns     (filterv #(= :foreign-block (:node %)) orig-body)
                      body-no-for  (vec (remove #(= :foreign-block (:node %)) orig-body))
                      auto-pairs   (into []
                                         (comp (mapcat :entries)
                                               (keep (fn [e]
                                                       (when-let [am (:auto-method e)]
                                                         [(first (:name-strs e)) am]))))
                                         foreigns)
                      synth-obs    (mapv (fn [[c-sym am]]
                                           (auto-method->observable c-sym am))
                                         auto-pairs)
                      use-present? (some #(= :use-section (:node %)) body-no-for)
                      plug-cap?    (fn [cap] (= "Plugin" (get-in cap [:type :name])))
                      with-use     (if use-present?
                                     (mapv (fn [n]
                                             (if (= :use-section (:node n))
                                               (update n :caps
                                                       (fn [caps]
                                                         (if (some plug-cap? caps)
                                                           caps
                                                           (conj (vec caps)
                                                                 {:name "plugin"
                                                                  :type {:node :type-ref
                                                                         :name "Plugin"}}))))
                                               n))
                                           body-no-for)
                                     (into [{:node :use-section
                                             :caps [{:name "plugin"
                                                     :type {:node :type-ref
                                                            :name "Plugin"}}]}]
                                           body-no-for))
                      new-body     (reduce (fn [acc obs]
                                             ;; prepend wrapper observables
                                             ;; after the use-section and
                                             ;; before any observables/
                                             ;; transitions from the original
                                             ;; body
                                             (let [[prefix suffix]
                                                   (split-with
                                                     #(or (= :use-section (:node %))
                                                          (= :data-section (:node %)))
                                                     acc)]
                                               (vec (concat prefix [obs] suffix))))
                                           with-use
                                           synth-obs)]
                  (assoc a :body new-body))))
            without-existing-plugin
            (if existing-plugin
              (vec (remove #(= existing-plugin %) parsed))
              parsed)
            rewritten (mapv rewrite-atom without-existing-plugin)]
        ;; Insert the (possibly new) plugin at the front of the module so
        ;; downstream passes that assume module-level plugin discovery
        ;; (F76 linker gate, F75 extract) see it unconditionally.
        (vec (cons new-plugin rewritten))))))

(defn synthesize-plugin-wrapper-atom
  "A+B (2026-11-20) — when `:def-plugin` carries `:plugin-atom-name`,
   synthesize a companion `:def-atom` node carrying `use plugin: Plugin`,
   one wrapper observable per `symbol \"...\" as <method>` foreign
   entry, and the two-phase `-> running` / `-> end` lifecycle. Appends
   the synthesized atom to top-nodes after the plugin node. When no
   atom-name is set, or when the module has no plugin, no-op.

   Collision rule: the synthesized atom name must not clash with any
   existing top-level def (`def Foo:` as atom/record/enum/protocol).
   Surfaces as a `:plugin-manifest-invalid` diagnostic naming both
   sites.

   Orphaned-`as` rule: a plugin that declares `symbol \"...\" as ...`
   entries but no `:plugin-atom-name` would drop the auto-method
   metadata on the floor — the `as` suffix becomes meaningless. That
   is an authoring mistake, not a compose, so fail explicitly.

   Zero-entry rule: `:plugin-atom-name` with no auto-method entries
   stays legal — the author stages the atom shell first and adds
   symbols later. The synthesized atom carries no observables but
   still has `use plugin: Plugin` + the two phases."
  [parsed]
  (let [def-plugin (find-def-plugin parsed)
        atom-name  (:plugin-atom-name def-plugin)
        auto-pairs (when def-plugin (collect-auto-methods def-plugin))]
    (cond
      (nil? def-plugin)
      parsed

      (and (seq auto-pairs) (nil? atom-name))
      (do
        (fail! (str "foreign `symbol \"...\" as <method>(...)` entries "
                    "found, but `def plugin:` has no atom name after "
                    "`plugin` to receive the auto-promoted wrapper "
                    "methods. Either name the companion atom "
                    "(`def plugin <AtomName>:`) so auto-synthesis can "
                    "build it, or drop the `as` clauses and wire the "
                    "symbols through `plugin.get(\"...\")` by hand in "
                    "a separately-authored `def <AtomName>:`.")
               def-plugin)
        parsed)

      (nil? atom-name)
      parsed

      :else
      (let [existing   (filter #(node-defines-name? % atom-name) parsed)
            _          (when (seq existing)
                         (fail! (str "`def plugin " atom-name ":` requests "
                                     "auto-synthesis of a wrapper atom named `"
                                     atom-name "`, but the module already "
                                     "declares `def " atom-name ":` elsewhere. "
                                     "Delete one of the two: drop the atom-"
                                     "name after `plugin` to keep the manual "
                                     "wrapper, or drop the manual `def "
                                     atom-name ":` to let auto-synthesis own "
                                     "the atom surface.")
                                def-plugin))
            observables (mapv (fn [[c-sym am]]
                                (auto-method->observable c-sym am))
                              auto-pairs)
            use-section {:node :use-section
                         :caps [{:name "plugin"
                                 :type {:node :type-ref :name "Plugin"}}]}
            transitions [{:node :transition
                          :source {:kind :implicit}
                          :receive nil
                          :target "running"
                          :src-line 0
                          :body []
                          :phase-swallow? false}
                         {:node :transition
                          :source {:kind :implicit}
                          :receive nil
                          :target "end"
                          :src-line 0
                          :body []
                          :phase-swallow? false}]
            synth      {:node :def-atom
                        :name atom-name
                        :ctor-params []
                        :body (into [use-section]
                                    (concat observables transitions))
                        :inferred-kind :agent}
            idx        (.indexOf ^java.util.List parsed def-plugin)]
        (if (neg? idx)
          (conj (vec parsed) synth)
          (vec (concat (subvec parsed 0 (inc idx))
                       [synth]
                       (subvec parsed (inc idx)))))))))

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
        (diag/fail-compile!
         :plugin-capability-outside-plugin-module
         (str "plugin capability: `use " (:name cap) ": Plugin` in atom `"
              (:name a) "` requires a `def plugin:` marker at module top-level "
              "(spec §7.2.1, R11 plugin-only wave 2026-09-01). "
              "Non-plugin modules import capability atoms from plugins "
              "instead (`use console: Console`).")
         cap
         {:atom-name (:name a) :cap-name (:name cap)})))))

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
              (diag/fail-compile!
               :plugin-foreign-symbol-collision
               (str "plugin foreign symbol `" sym "` declared in "
                    "both `" (get-in idx [sym :library]) "` and `"
                    library "` blocks. Symbols must be unique across "
                    "a plugin's foreign blocks (R7 per-library "
                    "disjoint namespaces).")
               entry
               {:symbol sym
                :first-library (get-in idx [sym :library])
                :second-library library}))
            (assoc idx sym {:library   library
                            :signature (:signature entry)
                            :entry     entry}))))
      idx
      entries))
   {}
   foreign-blocks))

(defn- build-selector-index-of-kind
  "Shared helper for FL-13 / FL-14. Walk foreign blocks; keep entries
   whose `:kind` matches `kind`; index by SEL string (second element of
   `:name-strs`). Collision on SEL throws `:plugin-foreign-<label>-collision`
   with the two competing (library, class) pairs."
  [foreign-blocks kind label collision-kind]
  (reduce
   (fn [idx {:keys [library entries]}]
     (reduce
      (fn [idx entry]
        (if-not (= kind (:kind entry))
          idx
          (let [[cls sel] (:name-strs entry)]
            (when (contains? idx sel)
              (diag/fail-compile!
               collision-kind
               (str "plugin foreign " label " `" sel "` declared "
                    "in both `" (get-in idx [sel :library]) "` (`"
                    (get-in idx [sel :cls]) "`) and `" library
                    "` (`" cls "`) blocks. " (str/capitalize label)
                    "s must be unique across a plugin's foreign "
                    "blocks so a single call site resolves to one "
                    "signature.")
               entry
               {:selector sel
                :first-library (get-in idx [sel :library])
                :first-class (get-in idx [sel :cls])
                :second-library library
                :second-class cls}))
            (assoc idx sel {:library   library
                            :cls       cls
                            :signature (:signature entry)
                            :entry     entry}))))
      idx
      entries))
   {}
   foreign-blocks))

(defn build-selector-index
  "FL-13 (2026-09-02). Instance-selector index keyed by SEL string.
   Only `:selector` entries participate. Collision throws
   `:plugin-foreign-selector-collision`."
  [foreign-blocks]
  (build-selector-index-of-kind
    foreign-blocks :selector "selector"
    :plugin-foreign-selector-collision))

(defn build-class-selector-index
  "FL-14 (2026-09-02). Class-selector index keyed by SEL string.
   Only `:class_selector` entries participate. Collision throws
   `:plugin-foreign-class-selector-collision`. The class name lives on
   the entry (`:cls`), so at the emit site `plugin.class_send(\"SEL\",
   ARGS...)` renders as `[ClassName SEL:args]` — receiver is derived,
   not passed."
  [foreign-blocks]
  (build-selector-index-of-kind
    foreign-blocks :class_selector "class selector"
    :plugin-foreign-class-selector-collision))

(defn build-callback-index
  "CB-2 (2026-11-02). Callback index keyed by subscribe-symbol string.
   Only `:callback` entries participate. Collision on symbol throws
   `:plugin-foreign-callback-collision`.

   Entry shape:
     {:library <STR>
      :atom-name <STR>   ; e.g. \"Keyboard\"
      :msg-name  <STR>   ; e.g. \"_on_key\"
      :entry     <full-entry-map>}

   The callback-index is consumed by `validate-plugin-callbacks!` for
   atom.msg resolution and by the lower-side shim emitter for shim
   generation + singleton registration + init-phase auto-install."
  [foreign-blocks]
  (reduce
   (fn [idx {:keys [library entries]}]
     (reduce
      (fn [idx entry]
        (if-not (= :callback (:kind entry))
          idx
          (let [sym (first (:name-strs entry))]
            (when (contains? idx sym)
              (diag/fail-compile!
               :plugin-foreign-callback-collision
               (str "plugin foreign callback `" sym "` declared in "
                    "both `" (get-in idx [sym :library]) "` and `"
                    library "` blocks. Subscribe symbols must be unique "
                    "across a plugin's foreign blocks so each call site "
                    "resolves to one atom.msg delivery pair.")
               entry
               {:symbol sym
                :first-library (get-in idx [sym :library])
                :second-library library}))
            (assoc idx sym {:library   library
                            :atom-name (:atom-name entry)
                            :msg-name  (:msg-name entry)
                            :entry     entry}))))
      idx
      entries))
   {}
   foreign-blocks))

(defn- transition-msg-name
  "Return the msg-source ident name on a `:transition` node, or nil
   when the transition isn't a named-msg arm (e.g. `-> running` entry
   trigger, wildcard-swallow, dispatch-literal)."
  [tr]
  (get-in tr [:receive :msg :source :name]))

(defn- transition-msg-payload
  "Return the message payload (vector of `{:name :type}` maps) from a
   `:transition` node's receive clause, or nil when the arm has no
   payload."
  [tr]
  (get-in tr [:receive :msg :payload]))

(defn- find-atom-by-name
  "Return the `:def-atom` node named `nm` in `top-nodes`, or nil."
  [top-nodes nm]
  (first (filter #(and (= :def-atom (:node %)) (= nm (:name %)))
                 top-nodes)))

(defn- find-msg-arm
  "Return the `:transition` node in atom's body whose receive-msg name
   matches `msg-nm`, or nil."
  [atom-node msg-nm]
  (first (filter #(and (= :transition (:node %))
                       (= msg-nm (transition-msg-name %)))
                 (:body atom-node))))

(defn validate-plugin-callbacks!
  "CB-2 (2026-11-02). For each `:callback` foreign entry in `manifest`,
   verify its referenced `atom-name.msg-name` resolves to a message arm
   on an atom defined in `top-nodes`. Raises
   `:plugin-foreign-callback-unresolved` on misses.

   On success, returns an enriched callback-index where each value also
   carries `:payload` (the message's parameter list) extracted from the
   resolved arm — the signature the lower-side shim emitter needs to
   produce the C fn-ptr argument list.

   Scope: v1.0 requires the capability atom to live in the SAME module
   as the plugin manifest. Cross-module callback resolution is a
   follow-on when a fixture requires it.

   No-op on non-plugin modules (`manifest` nil)."
  [top-nodes manifest]
  (when manifest
    (let [idx (build-callback-index (:foreign-blocks manifest))]
      (reduce-kv
       (fn [out sym {:keys [atom-name msg-name entry] :as info}]
         (let [atom-node (find-atom-by-name top-nodes atom-name)]
           (when-not atom-node
             (diag/fail-compile!
              :plugin-foreign-callback-unresolved
              (str "plugin foreign callback `" sym "` names atom `"
                   atom-name "`, but no `def " atom-name ":` is defined "
                   "in this module. v1.0 requires the capability atom "
                   "to live in the same module as its plugin manifest.")
              entry
              {:symbol sym :atom-name atom-name}))
           (let [arm (find-msg-arm atom-node msg-name)]
             (when-not arm
               (diag/fail-compile!
                :plugin-foreign-callback-unresolved
                (str "plugin foreign callback `" sym "` names "
                     atom-name "." msg-name ", but atom `" atom-name
                     "` has no message arm named `" msg-name "`. "
                     "Add `<: " msg-name "(args) -> <phase>` to the atom.")
                entry
                {:symbol sym :atom-name atom-name :msg-name msg-name}))
             (assoc out sym
                    (assoc info
                           :payload (or (transition-msg-payload arm) [])
                           :arm     arm)))))
       {}
       idx))))

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
      (diag/fail-compile!
       :plugin-get-non-literal-arg
       (str "plugin.get expects a literal string argument at compile "
            "time (compile-time symbol binding); got "
            (pr-str (get-in call-node [:args 0 :value :node])))
       call-node
       {:call call-node}))
    (or (get index sym)
        (diag/fail-compile!
         :plugin-symbol-unresolved
         (str "plugin.get(\"" sym "\"): symbol not declared in any "
              "`foreign \"...\"` block of this plugin's manifest. "
              "Add `symbol \"" sym "\" -> <signature>` to a foreign "
              "block, or check for a typo.")
         call-node
         {:symbol sym :call call-node}))))

(defn resolve-plugin-send
  "FL-13 (2026-09-02). Resolve a `plugin.send(LIT-STR, RECV, ARGS...)`
   call against a selector-index. Validates:
   - first positional arg is a literal string SEL
   - at least one additional positional arg is present (the receiver)
   - SEL resolves in the index
   Returns the index entry `{:library :cls :signature :entry}`.
   Throws `:plugin-send-non-literal-selector`, `:plugin-send-missing-receiver`,
   or `:plugin-selector-unresolved`."
  [call-node index]
  (let [sel (first-string-arg call-node)]
    (when-not sel
      (diag/fail-compile!
       :plugin-send-non-literal-selector
       (str "plugin.send expects a literal string selector as its "
            "first argument (compile-time selector binding); got "
            (pr-str (get-in call-node [:args 0 :value :node])))
       call-node
       {:call call-node}))
    (when (< (count (:args call-node)) 2)
      (diag/fail-compile!
       :plugin-send-missing-receiver
       (str "plugin.send(\"" sel "\", …) requires a receiver as its "
            "second argument. Usage: `plugin.send(\"selector:\", "
            "receiver, arg1, arg2, ...)`.")
       call-node
       {:selector sel :call call-node}))
    (or (get index sel)
        (diag/fail-compile!
         :plugin-selector-unresolved
         (str "plugin.send(\"" sel "\", ...): selector not declared "
              "in any `foreign \"...\"` block of this plugin's "
              "manifest. Add `selector \"ClassName\" \"" sel "\" -> "
              "<signature>` to a foreign block, or check for a typo.")
         call-node
         {:selector sel :call call-node}))))

(defn resolve-plugin-class-send
  "FL-14 (2026-09-02). Resolve a `plugin.class_send(LIT-STR, ARGS...)`
   call against a class-selector-index. Validates:
   - first positional arg is a literal string SEL
   - SEL resolves in the index
   Returns the index entry `{:library :cls :signature :entry}`.
   Throws `:plugin-class-send-non-literal-selector` or
   `:plugin-class-selector-unresolved`.

   Class selectors have no receiver — the class name comes from the
   manifest entry — so no missing-receiver check exists."
  [call-node index]
  (let [sel (first-string-arg call-node)]
    (when-not sel
      (diag/fail-compile!
       :plugin-class-send-non-literal-selector
       (str "plugin.class_send expects a literal string selector "
            "as its first argument (compile-time selector binding); "
            "got " (pr-str (get-in call-node [:args 0 :value :node])))
       call-node
       {:call call-node}))
    (or (get index sel)
        (diag/fail-compile!
         :plugin-class-selector-unresolved
         (str "plugin.class_send(\"" sel "\", ...): class selector "
              "not declared in any `foreign \"...\"` block of this "
              "plugin's manifest. Add `class_selector \"ClassName\" "
              "\"" sel "\" -> <signature>` to a foreign block, or "
              "check for a typo.")
         call-node
         {:selector sel :call call-node}))))

(defn- walk-plugin-calls
  "Depth-first walk over `x` invoking `f` on every node matching
   `plugin-method-call?`. Pure traversal — `f` returns nothing; used
   for validation side-effects. Pre-order so a plugin call's parent
   fires `f` before its arg sub-trees surface nested plugin calls."
  [x f]
  (doseq [n (tree-nodes x) :when (plugin-method-call? n)]
    (f n)))

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
    (let [foreign-idx        (build-foreign-index        (:foreign-blocks manifest))
          selector-idx       (build-selector-index       (:foreign-blocks manifest))
          class-selector-idx (build-class-selector-index (:foreign-blocks manifest))]
      (doseq [a (atom-nodes top-nodes)]
        (walk-plugin-calls
         (:body a)
         (fn [call]
           (case (plugin-method call)
             "get"        (resolve-plugin-get        call foreign-idx)
             ;; FL-13 — instance-selector resolution against the
             ;; per-plugin `:selector` entries.
             "send"       (resolve-plugin-send       call selector-idx)
             ;; FL-14 — class-selector resolution against
             ;; `:class_selector` entries. Class name lives on the
             ;; manifest entry; the call site does not name it.
             "class_send" (resolve-plugin-class-send call class-selector-idx))))))))

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
;; FL-13 (2026-09-02) — extended to also rewrite `plugin.send(LIT, RECV,
;; ARGS...)` into `:foreign-selector-call` nodes carrying `{:library :cls
;; :selector :signature :receiver :args :orig-node}`. `plugin.class_send`
;; is still NYI (FL-14) and B5 raises before this pass reaches it.
;; ============================================================================

(defn- rewrite-plugin-node
  "Shared gate for the three plugin-rewrite steps: match
   `plugin.<method>(LIT, ...)`, look up the literal in `index`, and
   invoke `build` with `(x literal entry)` to produce the replacement.
   Returns `x` unchanged on method mismatch or unresolved literal."
  [x method index build]
  (if-not (and (plugin-method-call? x)
               (= method (plugin-method x)))
    x
    (let [sym (first-string-arg x)]
      (if-let [entry (get index sym)]
        (build x sym entry)
        x))))

(defn- rewrite-plugin-get-node
  "If `x` is a `plugin.get(LIT-STR)` call and the literal resolves in
   `index`, return the `:foreign-ref` replacement. Otherwise return `x`
   unchanged."
  [x index]
  (rewrite-plugin-node
   x "get" index
   (fn [x sym entry]
     {:node      :foreign-ref
      :library   (:library entry)
      :symbol    sym
      :signature (:signature entry)
      :sig-kind  (get-in entry [:entry :kind])
      :orig-node x})))

(defn- rewrite-plugin-send-node
  "FL-13 (2026-09-02). If `x` is a `plugin.send(LIT-STR, RECV, ARGS...)`
   call and the selector resolves in `sel-index`, return a
   `:foreign-selector-call` node. Otherwise return `x` unchanged.
   Runs after B5 validation, so an unresolved selector at this point
   would be a programming error — leave the node alone rather than
   swallowing it (the earlier throw is authoritative)."
  [x sel-index]
  (rewrite-plugin-node
   x "send" sel-index
   (fn [x sel entry]
     (let [all-args (:args x)]
       {:node      :foreign-selector-call
        :library   (:library entry)
        :cls       (:cls entry)
        :selector  sel
        :signature (:signature entry)
        :receiver  (:value (nth all-args 1))
        :args      (vec (drop 2 all-args))
        :orig-node x}))))

(defn- rewrite-plugin-class-send-node
  "FL-14 (2026-09-02). If `x` is a `plugin.class_send(LIT-STR, ARGS...)`
   call and the selector resolves in `cls-sel-index`, return a
   `:foreign-class-selector-call` node carrying `:library :cls :selector
   :signature :args :orig-node`. No receiver slot — the class name from
   the manifest is the receiver at emit time."
  [x cls-sel-index]
  (rewrite-plugin-node
   x "class_send" cls-sel-index
   (fn [x sel entry]
     {:node      :foreign-class-selector-call
      :library   (:library entry)
      :cls       (:cls entry)
      :selector  sel
      :signature (:signature entry)
      :args      (vec (drop 1 (:args x)))
      :orig-node x})))

(def ^:private rewritten-kinds
  #{:foreign-ref :foreign-selector-call :foreign-class-selector-call})

(defn rewrite-plugin-refs
  "FL-1 + FL-13 + FL-14 entry point. Given parsed top-nodes and the
   module's extracted plugin manifest, return a new top-nodes vector
   with every plugin-method call replaced by its resolved node:
   `plugin.get(LIT)` → `:foreign-ref`,
   `plugin.send(LIT, RECV, ARGS...)` → `:foreign-selector-call`,
   `plugin.class_send(LIT, ARGS...)` → `:foreign-class-selector-call`.
   Returns `top-nodes` unchanged when `manifest` is nil (non-plugin
   module).

   Idempotent: running twice produces the same output as running once.
   Already-rewritten subtrees are skipped so the `:orig-node` slot
   (holding the original `:call`) is never re-walked.

   Runs AFTER `validate-plugin-symbol-refs!` — every literal is
   guaranteed to resolve."
  [top-nodes manifest]
  (if-not manifest
    top-nodes
    (let [foreign-idx (build-foreign-index        (:foreign-blocks manifest))
          sel-idx     (build-selector-index       (:foreign-blocks manifest))
          cls-sel-idx (build-class-selector-index (:foreign-blocks manifest))
          walk        (fn walk [x]
                        (cond
                          (and (map? x) (contains? rewritten-kinds (:node x)))
                          x

                          (map? x)
                          (-> (into {} (map (fn [[k v]] [k (walk v)])) x)
                              (rewrite-plugin-get-node        foreign-idx)
                              (rewrite-plugin-send-node       sel-idx)
                              (rewrite-plugin-class-send-node cls-sel-idx))

                          (vector? x) (mapv walk x)
                          (sequential? x) (map walk x)
                          :else x))]
      (mapv walk top-nodes))))

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
   whose callee is the aliased `:foreign-ref` — same shape FL-3 expects.
   Postwalk means args are already rewritten by the time a parent self-
   alias call fires; idempotent because the output's callee is a
   `:foreign-ref` (no longer matches `self-alias-call?`)."
  [x aliases]
  (walk/postwalk
    (fn [n]
      (if (self-alias-call? n aliases)
        (assoc n :callee (get aliases (get-in n [:callee :field])))
        n))
    x))

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

;; ============================================================================
;; FL-12 (2026-09-02) — ObjC type alias registration.
;;
;; `foreign "Foundation": type "NSURLSession"` registers NSURLSession as
;; a usable cxc type name at foreign-signature positions. All ObjC types
;; collapse to `id` at the C level (uniform opaque pointer), so the
;; rewrite is: scan every foreign-block signature for `:type-ref` nodes
;; whose name is a registered alias, replace with `:type-ref "Any"`
;; (which lowers to `id`).
;;
;; Scope-limited to FFI signature positions since app-side code can't
;; reach an ObjC value directly at v1.0 (would need `plugin.send` /
;; `plugin.class_send` — FL-13/14). Extending user-side ObjC-type
;; recognition is additive when those land.
;; ============================================================================

(defn- collect-objc-type-aliases
  "Extract `{alias-name → objc-name}` from `:type` and `:opaque-handle`
   entries across all foreign blocks of the (at most one) `:def-plugin`
   in top-nodes. Bare `type \"NSFoo\"` → alias = \"NSFoo\"; `type
   \"NSFoo\" as Bar` → alias = \"Bar\". Both map to the same C-level
   `id`. Runs before manifest extraction so B5/FL-1 see the rewritten
   signatures.

   `:opaque-handle` entries share the same table — the only difference
   from `:type` is scope (collect-opaque-handle-aliases below)."
  [top-nodes]
  (if-let [dp (find-def-plugin top-nodes)]
    (into {}
          (comp (mapcat :entries)
                (filter #(or (= :type (:kind %))
                             (= :opaque-handle (:kind %))))
                (map (fn [e] [(:alias e) (first (:name-strs e))])))
          (:foreign-blocks dp))
    {}))

(defn- collect-opaque-handle-aliases
  "Opaque-handle alias names only (subset of collect-objc-type-aliases).
   Used to rewrite user-side `:type-ref`s outside foreign-block positions
   in the same module. `:type` aliases stay plugin-signature-scoped."
  [top-nodes]
  (if-let [dp (find-def-plugin top-nodes)]
    (into #{}
          (comp (mapcat :entries)
                (filter #(= :opaque-handle (:kind %)))
                (map :alias))
          (:foreign-blocks dp))
    #{}))

(defn- rewrite-objc-type-refs*
  "Depth-first walk within a foreign signature. Replace `:type-ref`
   whose `:name` is in `aliases` with `{:type-ref \"Any\"}`. Non-
   signature subtrees pass through unchanged. Idempotent: output
   `:name` is `\"Any\"` which no caller registers as an alias."
  [x aliases]
  (walk/postwalk
    (fn [n]
      (if (and (map? n) (= :type-ref (:node n))
               (contains? aliases (:name n)))
        {:node :type-ref :name "Any" :objc-original (:name n)}
        n))
    x))

(defn- rewrite-user-type-refs*
  "Walk any AST subtree and rewrite `:type-ref` references matching an
   opaque-handle alias to `{:type-ref \"Any\" :objc-original <alias>}`.
   Same shape as rewrite-objc-type-refs*; split so :type-only aliases
   stay plugin-signature-scoped while opaque_handle aliases propagate
   to atom/record/let positions in the host module."
  [x aliases]
  (if (empty? aliases)
    x
    (walk/postwalk
      (fn [n]
        (if (and (map? n) (= :type-ref (:node n))
                 (contains? aliases (:name n)))
          {:node :type-ref :name "Any" :objc-original (:name n)}
          n))
      x)))

(defn rewrite-objc-type-aliases
  "FL-12 entry point. Walk the top-nodes' `:def-plugin` (if present)
   collecting alias→ObjC-name from `type` and `opaque_handle` entries,
   then rewrite every `:type-ref` inside foreign-block entries'
   signatures whose name is a registered alias to `{:type-ref \"Any\"}`
   (id at C level).

   Opaque-handle aliases additionally rewrite user atom/record/let
   positions in the SAME file's top-nodes, so an `opaque_handle
   \"NSImage\" as Image` in the plugin's foreign block makes `Image`
   usable as a field type in def-atoms living in that file.

   Runs BEFORE manifest extraction so B5's foreign-index and FL-1's
   rewritten `:foreign-ref` nodes carry the Any-substituted signatures.
   No-op when the module has no `:def-plugin` or no `type` /
   `opaque_handle` entries."
  [top-nodes]
  (let [aliases        (collect-objc-type-aliases top-nodes)
        opaque-aliases (collect-opaque-handle-aliases top-nodes)]
    (if (empty? aliases)
      top-nodes
      (mapv (fn [n]
              (cond
                (= :def-plugin (:node n))
                (update n :foreign-blocks
                        (fn [blocks]
                          (mapv (fn [b]
                                  (update b :entries
                                          (fn [es]
                                            (mapv #(rewrite-objc-type-refs* % aliases) es))))
                                blocks)))
                (seq opaque-aliases)
                (rewrite-user-type-refs* n opaque-aliases)
                :else n))
            top-nodes))))

(defn extract
  "F75 manifest resolver entry point. Given a parsed top-nodes vector,
   returns the plugin manifest map (see ns docstring) or `nil` when
   no `:def-plugin` marker is present. Throws
   `ex-info :kind :plugin-manifest-invalid` for missing required
   fields or wrong-shape defaults."
  [top-nodes]
  (when-let [dp (find-def-plugin top-nodes)]
    (let [by-name (fields->map (:fields dp))
          allowed #{"name" "version" "compat" "requires" "runtime_shape"
                    "bundle_id" "display_name" "min_os_version"}
          unknown (remove allowed (keys by-name))]
      (when (seq unknown)
        (fail! (str "unknown manifest field(s): "
                    (str/join ", " (map #(str "`" % ":`") (sort unknown)))
                    ". R7 v1.0 admits name/version/compat/requires/"
                    "runtime_shape plus the iOS bundle overrides "
                    "bundle_id/display_name/min_os_version.")
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
       :runtime-shape  (extract-optional by-name "runtime_shape"
                                         eval-runtime-shape
                                         :dispatch-main)
       :bundle-id      (extract-optional by-name "bundle_id"
                                         eval-string
                                         nil)
       :display-name   (extract-optional by-name "display_name"
                                         eval-string
                                         nil)
       :min-os-version (extract-optional by-name "min_os_version"
                                         eval-string
                                         nil)
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
    (spit f (json/write-str caps-map :indent true :escape-slash false))
    f))

(defn extract-all-manifests
  "Given a seq of parsed per-file top-nodes vectors, extract each file's
   plugin manifest (returning nil for non-plugin files). Deterministic
   pass-through of order."
  [per-file-nodes]
  (mapv extract per-file-nodes))
