(ns cxc.lower.extensions
  "F9 L3 + L4 — extension-env registration and single-module composition.

   L3 (build-extension-env) collects every `extend class Foo:` block
   keyed by target name and verifies each target resolves against the
   full symbol table (record ∪ atom ∪ protocol). No composition yet.

   L4 (compose-{record,atom}-env-with-extensions) merges the target's
   own method/observable table with extension-declared methods per
   §3.3.2 rules 1/3/5, unions `:conforms`, and — for records — re-
   detects `:variant` since new observables may promote struct → class.

   `replace` shadowing is resolved by `pick-winner-with-warnings!`;
   warnings go to *err* (mirrors warn-sync-send-cycles!) and are
   non-fatal. Records with extension invariants fail-nyi (records
   have no runtime invariant path yet); atoms compose extension
   invariants into their existing invariant list.

   L6 (defaults inheritance) and L5 (explicit conformance validation)
   are separate — this ns is L3/L4 only."
  (:require [cxc.diagnostics :refer [fail-compile! fail-nyi!]]
            [cxc.lower.method-entry :refer [observable->method-entry]]))

(defn- extension-nodes [top-nodes]
  (filter #(= :extend-class (:node %)) top-nodes))

(defn build-extension-env
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
      (= 1 (count decls))
      (let [d (first decls)]
        (when (:replace? d)
          (warn! (str "`replace` on `" method-name "` in extension of `"
                      target-name "` has no target to shadow — "
                      "replace-without-target. Drop the `replace` "
                      "modifier or verify the target name. "
                      "See §3.3.2 rule 3.")))
        (dissoc d :__source))

      (seq replaces)
      (dissoc (last replaces) :__source)

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
                        (array-map)
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

(defn compose-record-env-with-extensions
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
             own-methods (mapv #(observable->method-entry target %) own-obs)
             ext-methods (extension-methods-for target extension-env)
             _           (compose-target-methods target own-methods ext-methods)
             ext-replace-names (set (map :name (filter :replace? ext-obs)))
             own-effective (filterv #(not (contains? ext-replace-names (:name %)))
                                    own-obs)
             own-eff-names (set (map :name own-effective))
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

(defn compose-atom-env-with-extensions
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
