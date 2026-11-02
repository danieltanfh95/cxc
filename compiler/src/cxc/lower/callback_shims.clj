(ns cxc.lower.callback-shims
  "CB-3 (2026-11-02) — function-pointer callback FFI shim generator.

   A plugin's `foreign \"lib\":` block may carry
     callback \"ak_subscribe_key\" -> Keyboard._on_key
   entries. The plugin-manifest validator (CB-2) resolves each entry's
   `Atom.msg` against the enclosing module's atom top-nodes and attaches
   the message's `:payload` (parameter list). This module turns that
   into the C emission artefacts the feature needs:

     - a static singleton pointer, one per capability atom
     - an extern declaration for the subscribe-C-symbol itself
     - a static C shim bridging the C fn-pointer arg type to the
       capability atom's ObjC message selector

   The shim does not retain the atom; the atom is held by its creator
   (the `let kb: Keyboard! = Keyboard(...)` site). When the creator
   releases the atom, the singleton pointer dangles — v1.0 requires the
   capability atom to outlive subscriptions. A fixture that needs a
   weak-ref + nil-check will add it as a follow-on.

   This file is pure name + C-source builders plus init-stmt factories.
   `cxc.lower` composes `collect` and `install-stmts-for-atom` into the
   lowered program's `:callback-shims` slot and the atom's `:init-body`."
  (:require [clojure.string :as str]
            [cxc.lower.selectors :refer [objc-method-selector]]
            [cxc.mangle          :refer [->c-safe]]
            [cxc.type-kw         :refer [primitive-c-type]]))

(set! *warn-on-reflection* true)

(defn- payload-c-type
  "Return the C type string for a callback-payload parameter's `:type`
   node, or nil when the type isn't a primitive/CString we can render
   at an FFI boundary. Mirrors `foreign-signature-c-type` in cxc.lower."
  [ty-node]
  (let [inner (if (and (map? ty-node) (= :type-nonnil (:node ty-node)))
                (:inner ty-node)
                ty-node)]
    (when (and (map? inner) (= :type-ref (:node inner)))
      (primitive-c-type (:name inner)))))

(defn- payload-c-types
  "Vec of C type strings for every param in `payload`, or nil if any
   payload position can't be rendered in C at the FFI boundary."
  [payload]
  (let [types (mapv #(payload-c-type (:type %)) payload)]
    (when (every? some? types)
      types)))

(defn shim-fn-name
  "C name for the shim bridging `sym` to its atom.msg delivery pair."
  [library sym]
  (str "_cxc_shim_" (->c-safe library) "_" (->c-safe sym)))

(defn singleton-var-name
  "Static global pointer C name for the capability atom's singleton
   instance under `library`."
  [library atom-name]
  (str "g_cxc_" (->c-safe library) "_" (->c-safe atom-name)))

(defn- fnptr-c-type
  "C function-pointer type string: `void (*)(T1, T2, ...)`."
  [payload-c-types]
  (str "void (*)("
       (if (empty? payload-c-types) "void" (str/join ", " payload-c-types))
       ")"))

(defn- extern-decl
  "`extern void ak_subscribe_key(void (*)(int32_t, const char *));`.
   Return type is fixed at `void` at v1.0 — the grammar on the callback
   entry does not carry a return type; a fixture that needs non-void
   extends it later."
  [sym payload-c-types]
  (str "extern void " sym "(" (fnptr-c-type payload-c-types) ");"))

(defn- singleton-decl
  [library atom-name]
  (str "static id " (singleton-var-name library atom-name) " = nil;"))

(defn- objc-send
  "Format an ObjC method-send string using the same selector shape the
   emit layer builds for arm methods: `[recv selector:a1 part2:a2 ...]`.
   `selector` is the full `objc-method-selector` output; `arg-names`
   fills each position with its own name (shim arg → ObjC send arg)."
  [recv selector arg-names]
  (if (empty? arg-names)
    (str "[" recv " " selector "]")
    (let [parts (str/split selector #":")]
      (str "[" recv " "
           (str/join " "
                     (map (fn [p a] (str p ":" a)) parts arg-names))
           "]"))))

(defn- shim-body-c
  "Full C function definition for one shim. Casts the singleton `id`
   back to its concrete class so the ObjC send resolves to the atom
   class's method, not whatever selector `id` happens to see first."
  [library atom-name msg-name sym payload payload-c-types]
  (let [shim-nm     (shim-fn-name library sym)
        singleton   (singleton-var-name library atom-name)
        atom-c-nm   (->c-safe atom-name)
        param-names (mapv :name payload)
        selector    (objc-method-selector msg-name param-names)
        param-list  (if (empty? payload-c-types)
                      "void"
                      (str/join ", "
                                (map (fn [c-ty nm] (str c-ty " " nm))
                                     payload-c-types
                                     param-names)))
        send-str    (objc-send (str "(" atom-c-nm " *)" singleton)
                               selector
                               param-names)]
    (str "static void " shim-nm "(" param-list ") {\n"
         "    if (" singleton " != nil) {\n"
         "        " send-str ";\n"
         "    }\n"
         "}")))

(defn- one-callback->c
  "C-source fragment for one callback entry (singleton + extern + shim),
   or nil if the entry's payload has a non-primitive type we can't
   render at the FFI boundary."
  [{:keys [library atom-name msg-name entry payload]}]
  (let [sym       (first (:name-strs entry))
        payload-c (payload-c-types payload)]
    (when payload-c
      (str/join "\n"
                [(singleton-decl library atom-name)
                 (extern-decl    sym payload-c)
                 (shim-body-c    library atom-name msg-name
                                 sym payload payload-c)]))))

(defn- callback-entries-from-plugin
  "Walk one `:def-plugin` node and return [entry] for every `:callback`
   foreign entry on it. Resolution against the module's atom defs is
   left to the caller."
  [plugin-node]
  (vec
   (mapcat
    (fn [blk]
      (keep (fn [e]
              (when (= :callback (:kind e))
                {:library   (:library blk)
                 :atom-name (:atom-name e)
                 :msg-name  (:msg-name  e)
                 :entry     e}))
            (:entries blk)))
    (:foreign-blocks plugin-node))))

(defn- resolve-arm
  "Return [arm-node payload-vec] for a callback entry's atom.msg, or nil
   when either the atom or the arm doesn't exist in top-nodes."
  [atom-name msg-name top-nodes]
  (let [atom-node (first (filter #(and (= :def-atom (:node %))
                                       (= atom-name (:name %)))
                                 top-nodes))
        arm       (when atom-node
                    (first (filter #(and (= :transition (:node %))
                                         (= msg-name
                                            (get-in % [:receive :msg :source :name])))
                                   (:body atom-node))))]
    (when arm
      [arm (or (get-in arm [:receive :msg :payload]) [])])))

(defn- validated-callback-entries
  "Return callback entries enriched with `:payload` + `:arm`, aggregated
   across every `:def-plugin` node in the module. Unlike `pm/extract`
   (which hard-fails on multi-plugin modules), this walks each plugin
   independently — callbacks from multiple plugins coexist peacefully.
   Entries whose atom.msg doesn't resolve in top-nodes are dropped
   silently; the build-time `validate-plugin-callbacks!` gate already
   reports those via a user-facing diagnostic."
  [top-nodes]
  (vec
   (for [plugin  (filter #(= :def-plugin (:node %)) top-nodes)
         entry   (callback-entries-from-plugin plugin)
         :let    [resolved (resolve-arm (:atom-name entry)
                                        (:msg-name entry)
                                        top-nodes)]
         :when   resolved
         :let    [[arm payload] resolved]]
     (assoc entry :payload payload :arm arm))))

(defn collect
  "Walk `top-nodes` and return the vector of C-source strings for every
   callback entry whose payload is representable at the FFI boundary.
   Each string is self-contained (singleton + extern + shim)."
  [top-nodes]
  (vec (keep one-callback->c (validated-callback-entries top-nodes))))

(defn install-stmts-for-atom
  "Return the AST stmt nodes that register `atom-name` as the singleton
   target for every callback bound to it, plus the subscribe-C call
   installing the shim. Called from `cxc.lower` where init-shell is
   built; stmts are appended so super init completes before the
   singleton pointer is set.

   Returns [] when the atom has no callbacks. Non-plugin modules skip
   this path entirely (no plugin → no callbacks → no stmts).

   Only emits stmts for entries whose payload is representable in C at
   the FFI boundary — the matching shim C-source is also elided, so
   emission stays consistent across the two sides."
  [atom-name top-nodes]
  (let [hits (filter #(and (= atom-name (:atom-name %))
                           (payload-c-types (:payload %)))
                     (validated-callback-entries top-nodes))]
    (vec
     (mapcat
      (fn [{:keys [library entry]}]
        (let [sym       (first (:name-strs entry))
              singleton (singleton-var-name library atom-name)
              shim-nm   (shim-fn-name       library sym)]
          [{:node :raw-c-stmt
            :source (str singleton " = self;")}
           {:node :raw-c-stmt
            :source (str sym "(&" shim-nm ");")}]))
      hits))))
