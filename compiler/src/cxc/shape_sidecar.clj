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
            [clojure.string :as str]))

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

(defn- atom-node->entry [atom-node]
  (let [txns (atom-transitions atom-node)
        real-txns (filterv #(and (not= :implicit (get-in % [:source :kind]))
                                 (not= :wildcard (get-in % [:source :kind])))
                           txns)
        self-txns (filterv #(and (= :wildcard (get-in % [:source :kind]))
                                 (or (= "self" (:target %))
                                     (= "self" (get-in [:target :name] %))))
                           txns)]
    (cond-> {:name (:name atom-node)
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

(defn build-shape
  "Compute the shape doc from an enriched top-node vector plus the input
   path. Pure — no filesystem writes. Public for testability."
  [top-nodes source-path]
  (let [atom-nodes (filter #(= :def-atom (:node %)) top-nodes)
        atom-nodes (remove #(= "plugin" (:name %)) atom-nodes)
        record-nodes (filter #(= :record (:node %)) top-nodes)
        enum-nodes (filter #(= :enum (:node %)) top-nodes)
        main-name (some (fn [a] (when (= "main" (:name a)) (:name a))) atom-nodes)]
    {:cxc_shape_version 1
     :source source-path
     :main_atom main-name
     :atoms (mapv atom-node->entry atom-nodes)
     :records (mapv record-node->entry record-nodes)
     :enums (mapv enum-node->entry enum-nodes)}))

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
