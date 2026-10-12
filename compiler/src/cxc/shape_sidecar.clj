(ns cxc.shape-sidecar
  "Emit `<binary>.shape.json` — the program's static topology as seen at
   parse time. Consumed by the browser trace viewer to render source-truth
   phase graphs (atoms → phases → transitions), independent of any trace.

   Shape lives one abstraction level below emit: it walks the enriched
   user AST after resolve-references, before lower/emit. That's early
   enough that every `:def-atom` retains its parsed body (phases,
   transitions with source/target, observations, fields) but late enough
   that user names and provenance are stable.

   Schema (top-level): {:cxc_shape_version 1
                        :source <input path>
                        :main_atom <name or nil>
                        :atoms [<atom entry>...]
                        :records [<record entry>...]
                        :enums   [<enum entry>...]}

   Atom entry: {:name         — user-chosen atom name (e.g. \"Player\", \"main\")
                :ctor_params  — [{name, type}]
                :fields       — [{name, type, container_kind?}]
                :phases       — [phase names, incl. \"end\"]
                :initial_phase — phase entered from `-> phase` (implicit-source txn)
                :transitions  — [{from, to, msg, params, has_guard, line}]
                :self_transitions — [{msg, has_guard, line}] for `* <: msg -> self`
                :observations — [{name, params}]
                :line}

   Deliberately excludes: guard expression text (line ref is enough for
   the viewer; users click through to source), transition then-bodies
   (impl detail), method entries, extras. This is the *shape* — the
   phase machine you'd draw on a whiteboard, not the arm bodies."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cxc.env :as env]
            [cxc.resolve :as resolve]))

(defn- type-node->str
  "Render a parsed type node back to source-style syntax. Sole consumer
   is the shape sidecar. Handles the node kinds parse emits at type
   position; returns `?` on anything unexpected so a novel type shape
   doesn't blow up the sidecar."
  [t]
  (cond
    (nil? t) "?"
    (string? t) t
    (map? t)
    (case (:node t)
      :type-ref      (or (:name t) "?")
      :type-of       (str (:outer t) " of " (type-node->str (:inner t)))
      :type-nonnil   (str (type-node->str (:inner t)) "!")
      :type-nullable (str (type-node->str (:inner t)) "?")
      ;; Tuple positions carry a `:types` vector — used by Map/Set of
      ;; (K,V) at v1.0. Render parenthesized so `Map of (String, Int)`
      ;; round-trips to source syntax the user wrote.
      :type-tuple    (str "(" (str/join ", " (map type-node->str (:types t))) ")")
      :type-named-tuple (str "("
                             (str/join ", "
                                       (map (fn [f] (str (:name f) ": " (type-node->str (:type f))))
                                            (:fields t)))
                             ")")
      "?")
    :else "?"))

(defn- container-kind
  "Recognize compiler-synthesized container / capability types so the
   viewer can pillize them inside the owning atom block rather than
   rendering as edges to a `_Cxc_*` sibling atom."
  [t]
  (when (and (map? t) (= :type-of (:node t)))
    (case (:outer t)
      "List" "list"
      "Map"  "map"
      "Set"  "set"
      nil)))

(defn- param->entry [p]
  {:name (or (:name p) (:label p) "?")
   :type (type-node->str (:type p))})

(defn- field->entry [f]
  (let [ty (:type f)]
    (cond-> {:name (or (:name f) "?")
             :type (type-node->str ty)}
      (container-kind ty) (assoc :container_kind (container-kind ty)))))

(defn- msg-node->name
  "Pull the message selector out of a `:receive` node's `:msg` field.
   Parse builds `msg-shape` for `foo(a: T)` and bare `:ident` for `foo`;
   handle both. Wildcard `*` shows up as `:wildcard-msg`."
  [msg-node]
  (cond
    (nil? msg-node) nil
    (= :wildcard-msg (get-in msg-node [:source :node])) "*"
    :else (or (get-in msg-node [:source :name])
              (get-in msg-node [:source :field])
              "?")))

(defn- msg-node->params [msg-node]
  (mapv param->entry (or (:params msg-node) [])))

(defn- transition->entry
  "Serialize one `:transition` node. Handles:
   - initial-phase: `-> phase then: …` (source :implicit, no receive)
   - normal: `<phase> <: msg -> <phase-or-end>`
   - self-forwarded: `* <: msg -> self` (fan-in aggregation)
   - handler-shape: `* <: name(args) -> *` (F67 L3 handler decl)"
  [txn]
  (let [src (:source txn)
        rcv (:receive txn)
        tgt (:target txn)
        src-kind (:kind src)
        src-phase (cond
                    (= src-kind :phase)    (:name src)
                    (= src-kind :self)     "self"
                    (= src-kind :wildcard) "*"
                    (= src-kind :implicit) nil
                    :else "?")
        tgt-str (cond
                  (nil? tgt) nil
                  (string? tgt) tgt
                  (map? tgt) (or (:name tgt) "?")
                  :else "?")
        msg-name (msg-node->name (:msg rcv))
        params (msg-node->params (:msg rcv))
        has-guard (boolean
                    (or (:guard txn)
                        (some #(= :guard-clause (:node %)) (:body txn))))
        line (:cxc-line txn)
        base {:from src-phase :to tgt-str :msg msg-name
              :params params :has_guard has-guard}]
    (cond-> base
      line (assoc :line line))))

(defn- atom-transitions [atom-node]
  (filterv #(= :transition (:node %)) (:body atom-node)))

(defn- atom-observations [atom-node]
  (mapv (fn [o] {:name (:name o) :params (mapv param->entry (or (:params o) []))})
        (filterv #(= :observable (:node %)) (:body atom-node))))

(defn- atom-fields
  "Collect fields from both surfaces: legacy `data:` section (fields live
   in `(:fields data-section-node)`) and F67 L5 top-level `:field` nodes
   in the atom body. Order matches source appearance so the viewer can
   list them in declaration order."
  [atom-node]
  (let [top-level (filter #(= :field (:node %)) (:body atom-node))
        data-sec-fields (mapcat :fields
                                (filter #(= :data-section (:node %)) (:body atom-node)))]
    (mapv field->entry (concat data-sec-fields top-level))))

(defn- collect-phases
  "Distinct phase names visible in this atom's transitions, in first-seen
   order. Always includes `end` if it appears as a target — the exit
   phase is part of the lifecycle even when nothing explicit enters it
   from source-phase syntax."
  [txns]
  (let [seen (volatile! [])]
    (letfn [(add [nm]
              (when (and nm (string? nm)
                         (not (contains? (set @seen) nm))
                         (not (contains? #{"*" "self" "?"} nm)))
                (vswap! seen conj nm)))]
      (doseq [t txns]
        (add (get-in t [:source :name]))
        (let [tgt (:target t)]
          (add (cond (string? tgt) tgt
                     (map? tgt) (:name tgt)))))
      @seen)))

(defn- initial-phase
  "Phase entered by the implicit-source transition (`-> phase then: …`).
   Multiple `-> phase` blocks are legal for different phases (each
   provides on-enter code); the FIRST one is the atom's actual initial
   phase because it's the target of construction."
  [txns]
  (some (fn [t]
          (when (= :implicit (get-in t [:source :kind]))
            (let [tgt (:target t)]
              (cond (string? tgt) tgt
                    (map? tgt) (:name tgt)))))
        txns))

(defn- atom-class-name
  "Emitted ObjC class name for an atom. Mirrors
   `cxc.lower.emit-names/class-name-for-atom` so the viewer can match
   trace classes to sidecar entries by exact string instead of guessing
   via underscore-suffix heuristics. `main` is special-cased to
   `CxcMain` to avoid colliding with C's `main()` symbol.

   `name->module` is `env/attribute-modules-to-decls` applied to the
   current `top-nodes` stream — the authoritative source for a decl's
   module. Reading `(:module atom-node)` directly returns nil because
   resolve only enriches reference nodes, not declaration nodes."
  [name->module atom-node]
  (let [nm (:name atom-node)]
    (if (= "main" nm)
      "CxcMain"
      (resolve/mangled-name-for (get name->module nm) nm))))

(defn- atom-node->entry [name->module atom-node]
  (let [txns (atom-transitions atom-node)
        real-txns (filterv #(and (not= :implicit (get-in % [:source :kind]))
                                 (not= :wildcard (get-in % [:source :kind])))
                           txns)
        self-txns (filterv #(and (= :wildcard (get-in % [:source :kind]))
                                 (or (= "self" (:target %))
                                     (= "self" (get-in [:target :name] %))))
                           txns)]
    (cond-> {:name (:name atom-node)
             :class (atom-class-name name->module atom-node)
             :ctor_params (mapv param->entry (:ctor-params atom-node))
             :fields (atom-fields atom-node)
             :phases (collect-phases txns)
             :initial_phase (initial-phase txns)
             :transitions (mapv transition->entry real-txns)
             :self_transitions (mapv transition->entry self-txns)
             :observations (atom-observations atom-node)}
      (:cxc-line atom-node) (assoc :line (:cxc-line atom-node)))))

(defn- record-node->entry [rec-node]
  (cond-> {:name (:name rec-node)
           :fields (mapv field->entry
                         (filterv #(= :field (:node %)) (:body rec-node)))}
    (:cxc-line rec-node) (assoc :line (:cxc-line rec-node))))

(defn- enum-node->entry [enum-node]
  (cond-> {:name (:name enum-node)
           :cases (mapv :name
                        (filterv #(= :enum-case (:node %)) (:body enum-node)))}
    (:cxc-line enum-node) (assoc :line (:cxc-line enum-node))))

;; ---------------------------------------------------------------------------
;; Observation call-site extraction (Wave 1: static).
;;
;; For every atom body, find expression nodes shaped like X.obs(args) or X.obs
;; where X resolves to a ctor-param/field of atom type T and `obs` is in T's
;; observable list. Emit {reader target obs line} tuples so the viewer can
;; render caller→observed edges alongside message arrows.
;;
;; Wave 1 only resolves direct references (ctor-param / field refs on the
;; current atom). Chained patterns like self.lane(idx).side(seat) need the
;; return-type of self.lane, which isn't declared until Wave 1.5.

(defn- atom-type-name
  "If a type-ref directly names an atom (per the atom-name set), return it.
   `:type-nonnil` (`T!`) and `:type-nullable` (`T?`) wrap the SAME type's
   nullability so unwrap through `:inner`. Containers (`List of T`, `Map of
   (K, T)` — the `:type-of` node) do NOT resolve: the receiver of a method
   on `xs: List of T` is the list, not an element. Element-wise resolution
   waits for Wave 1.5 when `.at(i).obs()` can trace the chain."
  [t atom-names]
  (cond
    (nil? t) nil
    (string? t) (when (contains? atom-names t) t)
    (map? t)
    (let [kind (:node t)
          nm   (or (:name t) (and (:resolves-to t) (:name (:resolves-to t))))]
      (cond
        (and nm (contains? atom-names nm)) nm
        (or (= kind :type-nonnil) (= kind :type-nullable))
        (recur (:inner t) atom-names)
        :else nil))))

(defn- local-atom-refs
  "name → atom-type-name for ctor-params and fields of the atom whose type is
   another atom. Self is tagged separately so cross-atom-only call sites can
   skip self-recursion when rendering arrows."
  [atom-node atom-names]
  (let [ctor   (:ctor-params atom-node)
        fields (concat
                (mapcat :fields (filter #(= :data-section (:node %)) (:body atom-node)))
                (filter #(= :field (:node %)) (:body atom-node)))]
    (into {}
          (keep (fn [item]
                  (when-let [an (atom-type-name (:type item) atom-names)]
                    [(:name item) an])))
          (concat ctor fields))))

(defn- container-element-atom
  "If `t` is a `List of A`, `Set of A`, or `Map of (K, A)` where A is an atom
   type, return A. Returns nil for non-container or non-atom-element types.
   Only `:type-of` nodes resolve; `:type-nonnil`/`:type-nullable` wrappers
   unwrap through `:inner` first."
  [t atom-names]
  (cond
    (nil? t) nil
    (not (map? t)) nil
    (= :type-nonnil (:node t))   (recur (:inner t) atom-names)
    (= :type-nullable (:node t)) (recur (:inner t) atom-names)
    (= :type-of (:node t))
    (atom-type-name (:inner t) atom-names)
    :else nil))

(defn- local-container-refs
  "name → element-atom-name for ctor-params and fields whose type is a
   container of an atom (`List of A`, `Set of A`, `Map of (K, A)`). Enables
   `xs.at(i).obs()` chained resolution."
  [atom-node atom-names]
  (let [ctor   (:ctor-params atom-node)
        fields (concat
                (mapcat :fields (filter #(= :data-section (:node %)) (:body atom-node)))
                (filter #(= :field (:node %)) (:body atom-node)))]
    (into {}
          (keep (fn [item]
                  (when-let [an (container-element-atom (:type item) atom-names)]
                    [(:name item) an])))
          (concat ctor fields))))

(def ^:private container-elem-methods
  "Container methods that return a single element of the container's element
   type: `.at(i) ?? default`, `.first()`, `.last()`. The sidecar does not
   check for the nullability wrapper; the caller is expected to use `??` or
   `or` recovery to make the receiver non-nil before invoking an observation."
  #{"at" "first" "last"})

(defn- expr-atom-type
  "Return the atom-name an expression evaluates to, or nil if not an atom.
   `self-atom` is the enclosing atom's own name; `:self` therefore resolves
   to that name so chained lookups like `self.lane(x).side(y)` can thread
   through `obs-returns` whose top-level key is the current atom.

   Phase B adds container-element awareness: a `:call` whose callee is a
   container-returning dot (`xs.at(i)` with `xs: List of A`) resolves to the
   element atom A via `member-containers` or `atom-containers`.

   The caller's `hit!` is responsible for filtering out same-atom reads
   (`(= recv self-atom)`); this helper only concerns itself with naming."
  [node local-env member-env member-containers atom-containers
   obs-returns self-atom]
  (letfn [(container-elem [node]
            ;; Returns the element atom type if `node` is a container-of-atom
            ;; expression (`self.field`, bare ident naming a container field,
            ;; or `other.field` where other resolves to atom and field holds a
            ;; container-of-atom). Nil otherwise.
            (cond
              (not (map? node)) nil

              (= :dot (:node node))
              (if (= :self (get-in node [:target :node]))
                (get member-containers (:field node))
                (let [recv (type-of (:target node))]
                  (when recv
                    (get-in atom-containers [recv (:field node)]))))

              (= :ident (:node node))
              (get member-containers (:name node))

              :else nil))
          (type-of [node]
            (when (map? node)
              (case (:node node)
                :self   self-atom
                :ident  (or (get local-env (:name node))
                            ;; Bare self-obs returning an atom: `tower_eligible`
                            ;; in Player's body resolves to Player.tower_eligible's
                            ;; return type.
                            (get-in obs-returns [self-atom (:name node)]))
                :dot    (if (= :self (get-in node [:target :node]))
                          (get member-env (:field node))
                          (let [recv (type-of (:target node))]
                            (when recv
                              (get-in obs-returns [recv (:field node)]))))
                :call   (let [callee (:callee node)]
                          (when (and (map? callee) (= :dot (:node callee)))
                            (let [field (:field callee)
                                  recv  (type-of (:target callee))]
                              (or (when recv
                                    (get-in obs-returns [recv field]))
                                  (when (contains? container-elem-methods field)
                                    (container-elem (:target callee)))))))
                nil)))]
    (type-of node)))

(defn- obs-call-receiver
  "Alias retained for call-site readability. Resolves the dot-form target that
   precedes the observation name; delegates to `expr-atom-type`."
  [target local-env member-env member-containers atom-containers obs-returns
   self-atom]
  (expr-atom-type target local-env member-env member-containers
                  atom-containers obs-returns self-atom))

(defn- apply-params
  "Extend `env` with parameter bindings. A param whose type names an atom adds
   a binding; a param whose type does not shadows any outer binding by removing
   the name. The second case is what prevents a shadowing param from inheriting
   the atom-type of a same-named ctor-param (codex finding 1 witness B)."
  [env params atom-names]
  (reduce (fn [e {:keys [name type]}]
            (if-let [an (atom-type-name type atom-names)]
              (assoc e name an)
              (dissoc e name)))
          env
          (or params [])))

(defn- pattern-shadow-names
  "Names a pattern map introduces in its arm body. Covers `{:kind :bind :name}`
   whole-reason catches and `{:kind :case :bindings [{:as}]}` payload binders.
   `:wildcard` arms introduce none."
  [pat]
  (when (map? pat)
    (case (:kind pat)
      :bind     (when-let [nm (:name pat)] [nm])
      :case     (keep :as (:bindings pat))
      :wildcard nil
      nil)))

(defn- shadow-names [env names]
  (reduce dissoc env (or names [])))

(defn- collect-atom-obs-calls
  "Walk the atom body, emitting one tuple per observation call site. Two envs:
   `member-env` is the stable ctor-param / field env reached by `self.<field>`.
   `env` starts as member-env and gets rebound as the walk enters observable,
   transition, or lambda scopes (via their params) or passes a `:let-stmt` or
   `:alias-stmt` in sequential position. Bare identifiers resolve through `env`
   so a local name masks any same-spelled stored field; `self.<field>` always
   resolves through `member-env` so a parameter cannot shadow a stored field.
   `obs-returns` maps `{atom → {obs → atom-return-type}}` and enables Wave 1.5
   chained-receiver resolution (`self.lane(x).side(y)` and similar).

   Transition params/payload live under `[:receive :msg]`, matching the parser
   shape produced by `parse-source-and-receive`. Sequential statement bodies
   reduce the env across siblings so a let/alias binding takes effect from the
   statement after it, with its own RHS evaluated in the pre-binding env."
  [atom-node obs-index atom-names obs-returns atom-containers]
  (let [member-env        (local-atom-refs atom-node atom-names)
        member-containers (local-container-refs atom-node atom-names)
        reader            (:name atom-node)
        out        (volatile! (transient []))
        hit!       (fn [recv field line]
                     (when (and recv (string? field)
                                (contains? (get obs-index recv #{}) field))
                       (vswap! out conj!
                               (cond-> {:reader reader :target recv :obs field}
                                 line (assoc :line line)))))]
    (letfn [(bind-one [env {:keys [name type]}]
              (if-let [an (atom-type-name type atom-names)]
                (assoc env name an)
                (dissoc env name)))
            (walk-arm [outer-env arm]
              ;; Dispatch arms with multiple :patterns introduce no payload
              ;; local (lower.clj:5802/:5844 gates payload-bindings on a
              ;; single pattern); masking the first pattern's `:as` names
              ;; would then wrongly drop a stored same-named receiver
              ;; (codex NEW-03). Let-stmt recovery arms carry no :patterns,
              ;; so the single-pattern default masks correctly for them.
              (let [pats     (:patterns arm)
                    single?  (or (nil? pats) (<= (count pats) 1))
                    arm-env  (if single?
                               (shadow-names outer-env
                                             (pattern-shadow-names (:pattern arm)))
                               outer-env)]
                (walk arm-env (:body arm))))
            (walk [env node]
              (cond
                (sequential? node)
                ;; A sequential introduces no block scope of its own, but a
                ;; let/alias inside it extends env across subsequent siblings.
                ;; Returning env (not the reduced value) keeps shadows from
                ;; leaking to the caller's sibling slot.
                (do (reduce walk env node) env)

                (and (map? node) (= :observable (:node node)))
                (do (walk (apply-params env (:params node) atom-names)
                          (:expr node))
                    env)

                (and (map? node) (= :transition (:node node)))
                (let [msg  (get-in node [:receive :msg])
                      rp   (concat (:params msg) (:payload msg))
                      env' (apply-params env rp atom-names)]
                  (walk env' (:body node))
                  env)

                (and (map? node) (= :lambda (:node node)))
                (do (walk (apply-params env (:params node) atom-names)
                          (:body node))
                    env)

                (and (map? node) (= :let-stmt (:node node)))
                ;; RHS runs in the incoming env. Recovery arms see the let
                ;; destination plus any pattern-bound name, matching
                ;; lower-let-stmt's arm-ctx registration (codex NEW-01).
                (let [_        (walk env (:expr node))
                      env-next (bind-one env node)]
                  (doseq [arm (:arms node)]
                    (walk-arm env-next arm))
                  env-next)

                (and (map? node) (= :alias-stmt (:node node)))
                (do (walk env (:expr node))
                    (dissoc env (:name node)))

                (and (map? node) (= :if-let (:node node)))
                ;; Thread each binding's RHS through the growing env, then
                ;; walk :then with the final extended env and :else with
                ;; the outer env.
                (let [then-env (reduce (fn [e b]
                                         (walk e (:expr b))
                                         (bind-one e b))
                                       env
                                       (:bindings node))]
                  (walk then-env (:then node))
                  (when-let [else-blk (:else node)]
                    (walk env else-blk))
                  env)

                (and (map? node) (= :dispatch (:node node)))
                ;; Scrutinee reads under the outer env; each arm body sees
                ;; its own pattern bindings masked. Guard uses the outer env.
                (do (walk env (:scrutinee node))
                    (doseq [arm (:arms node)]
                      (when-let [g (:guard arm)] (walk env g))
                      (walk-arm env arm))
                    env)

                (and (map? node) (= :call (:node node))
                     (map? (:callee node)) (= :dot (:node (:callee node))))
                (let [dot (:callee node)]
                  (hit! (obs-call-receiver (:target dot) env member-env member-containers
                                             atom-containers obs-returns reader)
                        (:field dot)
                        (or (:cxc-line node) (:cxc-line dot)))
                  (walk env (:target dot))
                  (walk env (:args node))
                  env)

                (and (map? node) (= :dot (:node node)))
                (do (hit! (obs-call-receiver (:target node) env member-env
                                             member-containers atom-containers
                                             obs-returns reader)
                          (:field node)
                          (:cxc-line node))
                    (walk env (:target node))
                    env)

                ;; Bare `:ident X` where X is a self-observation. Captures the
                ;; zero-arg self-obs call shape (`tower_eligible` on its own,
                ;; or `tower_eligible.at(0)`) and the parametric form parsed as
                ;; `:call {:callee :ident X :args [...]}` whose callee is
                ;; walked here. A local variable with the same name has already
                ;; shadowed via env; only names NOT in env are considered.
                (and (map? node) (= :ident (:node node))
                     (not (contains? env (:name node)))
                     (contains? (get obs-index reader #{}) (:name node)))
                (do (hit! reader (:name node) (:cxc-line node))
                    env)

                (map? node)
                (do (doseq [[_k v] node] (walk env v))
                    env)

                :else env))]
      (walk member-env (:body atom-node)))
    (persistent! @out)))

(defn- obs-call-aggregate
  "Fold per-site tuples into one entry per (reader, target, obs) with
   call_count and lines[]. Keep insertion order stable for test diffs."
  [sites]
  (->> sites
       (reduce (fn [acc {:keys [reader target obs line]}]
                 (let [k [reader target obs]]
                   (-> acc
                       (update-in [k :lines] (fnil conj []) line)
                       (update-in [k :count] (fnil inc 0)))))
               {})
       (map (fn [[[reader target obs] v]]
              (cond-> {:reader reader :target target :obs obs
                       :call_count (:count v)}
                (seq (keep identity (:lines v)))
                (assoc :lines (vec (sort (distinct (keep identity (:lines v)))))))))
       (sort-by (juxt :reader :target :obs))
       vec))

(defn- obs-return-expr-type
  "Infer the atom-return type of a single observable's RHS expression. Walks
   the common value-returning shapes: a bare `:ident`, a `self.<field>` dot,
   a `:multi-arm-dispatch` whose arms unanimously return the same atom, and a
   chained observation call. Returns nil for non-atom expressions. The
   observable body executes with the atom's member-env in scope for bare
   identifiers (ctor-params / fields), so both local-env and member-env
   positions receive the same map here."
  [expr member-env member-containers atom-containers obs-returns atom-names
   self-atom]
  (letfn [(resolve-expr [node]
            (when (map? node)
              (case (:node node)
                :multi-arm-dispatch
                (let [arm-types (map (comp resolve-expr :body) (:arms node))]
                  (when (and (seq arm-types)
                             (every? some? arm-types)
                             (apply = arm-types))
                    (first arm-types)))
                :else-marker nil
                (expr-atom-type node member-env member-env member-containers
                                atom-containers obs-returns self-atom))))]
    (let [t (resolve-expr expr)]
      (when (contains? atom-names t) t))))

(defn- build-obs-returns
  "Fixed-point: compute `{atom-name → {obs-name → atom-return-type}}` so
   chained reads like `self.lane(x).side(y)` can resolve their outer receiver.
   Starts empty, re-infers until no entry changes. Only atom-returning obs
   land in the map; obs returning primitives, records, or containers are left
   out so the walker treats them as unresolved receivers (correct behavior)."
  [atom-nodes atom-names atom-containers]
  (let [atom-infos (mapv (fn [a]
                           {:name       (:name a)
                            :member     (local-atom-refs a atom-names)
                            :containers (local-container-refs a atom-names)
                            :obs-nodes  (filter #(= :observable (:node %))
                                                (:body a))})
                         atom-nodes)
        obs-entries (fn [self-atom member containers obs-nodes current]
                      (into {}
                            (keep (fn [ob]
                                    (when-let [t (obs-return-expr-type
                                                   (:expr ob) member containers
                                                   atom-containers current
                                                   atom-names self-atom)]
                                      [(:name ob) t])))
                            obs-nodes))
        round (fn [current]
                (into {}
                      (map (fn [info]
                             [(:name info)
                              (obs-entries (:name info)
                                           (:member info)
                                           (:containers info)
                                           (:obs-nodes info)
                                           current)]))
                      atom-infos))]
    (loop [current {}]
      (let [next-map (round current)]
        (if (= next-map current) current (recur next-map))))))

(defn- build-atom-containers [atom-nodes atom-names]
  (into {}
        (map (fn [a] [(:name a) (local-container-refs a atom-names)]))
        atom-nodes))

(defn- build-obs-maps
  "Returns `{:obs_calls [...] :obs_self_calls [...]}` by splitting the walker's
   output into cross-atom reads (reader ≠ target) and self-reads
   (reader = target). The self bucket enables the viewer's internal-vs-unused
   classification without a second walk."
  [atom-nodes]
  (let [atom-names      (into #{} (map :name atom-nodes))
        obs-index       (into {}
                              (map (fn [a]
                                     [(:name a)
                                      (into #{} (map :name
                                                     (filter #(= :observable (:node %))
                                                             (:body a))))]))
                              atom-nodes)
        atom-containers (build-atom-containers atom-nodes atom-names)
        obs-returns     (build-obs-returns atom-nodes atom-names atom-containers)
        sites           (mapcat #(collect-atom-obs-calls % obs-index atom-names
                                                        obs-returns atom-containers)
                                atom-nodes)
        {cross false self true} (group-by #(= (:reader %) (:target %)) sites)]
    {:obs_calls      (obs-call-aggregate (or cross []))
     :obs_self_calls (obs-call-aggregate (or self []))}))

(defn build-shape
  "Compute the shape doc from an enriched top-node vector plus the input
   path. Pure — no filesystem writes. Public for testability."
  [top-nodes source-path]
  (let [atom-nodes (filter #(= :def-atom (:node %)) top-nodes)
        atom-nodes (remove #(= "plugin" (:name %)) atom-nodes)
        record-nodes (filter #(= :record (:node %)) top-nodes)
        enum-nodes (filter #(= :enum (:node %)) top-nodes)
        main-name (some (fn [a] (when (= "main" (:name a)) (:name a))) atom-nodes)
        name->module (env/attribute-modules-to-decls top-nodes)]
    (merge
      {:cxc_shape_version 1
       :source source-path
       :main_atom main-name
       :atoms (mapv #(atom-node->entry name->module %) atom-nodes)
       :records (mapv record-node->entry record-nodes)
       :enums (mapv enum-node->entry enum-nodes)}
      (build-obs-maps atom-nodes))))

(defn write-shape!
  "Serialize the shape doc for `top-nodes` to `<bin-path>.shape.json`
   next to the compiled binary. Called from build.clj after emit
   succeeds. Never throws — a sidecar failure must not fail the build."
  [top-nodes source-path bin-path]
  (try
    (let [shape (build-shape top-nodes source-path)
          out (io/file (str bin-path ".shape.json"))]
      (io/make-parents out)
      (spit out (json/write-str shape :escape-slash false))
      (.getPath out))
    (catch Throwable t
      (binding [*out* *err*]
        (println (str "shape-sidecar warning: " (.getMessage t))))
      nil)))
