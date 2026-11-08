(ns cxc.prelude
  "Compiler-shipped declarations, available without imports. The build
   parses prelude source separately and prepends its AST to the user
   AST. Lowering tree-shakes unused prelude atoms.

   The registry uses user-visible names. C/Objective-C identifier
   mangling happens at the output boundary in cxc.lower, using
   cxc.resolve/mangled-name-for to avoid Foundation name collisions.

   Resources load only when source is requested, so a classpath may
   ship just the selected target's prelude."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cxc.diagnostics :as diag]
            [cxc.target :as target]))

(set! *warn-on-reflection* true)

(def default-target
  "Fallback when a compiler entrypoint receives no explicit target."
  :macos)

(defn- load-module-src
  "Read a module's classpath resource from prelude/shared/<name>.cxc
   or prelude/target/<target>/<name>.cxc. ios-device uses ios-sim's
   prelude because both call the same Foundation/UIKit runtime.

   For :target-kind modules on a known target, falls back to
   prelude/target/shared/<name>.cxc when no per-target file exists,
   so modules identical across every supported target live in one
   place. Per-target overrides win when present. Unknown targets
   skip the fallback and raise the same missing-resource diagnostic
   as before."
  [{:keys [name kind]} tgt]
  (let [effective (if (= tgt :ios-device) :ios-sim tgt)
        primary-path (case kind
                       :shared (str "prelude/shared/" (clojure.core/name name) ".cxc")
                       :target (str "prelude/target/" (clojure.core/name effective)
                                    "/" (clojure.core/name name) ".cxc"))
        shared-path (when (and (= kind :target) (target/spec tgt))
                      (str "prelude/target/shared/" (clojure.core/name name) ".cxc"))
        resource (or (io/resource primary-path)
                     (when shared-path (io/resource shared-path)))]
    (when-not resource
      (diag/fail-compile!
        :prelude-resource-missing
        (str "prelude module resource missing: `" primary-path
             "`. Check compiler/resources/prelude/ and deps.edn `:paths`.")
        nil
        {:module name :module-kind kind :target tgt :path primary-path}))
    (slurp resource)))

(def prelude-modules
  "Module manifest in source concatenation order.

   Each entry declares its names once, grouped by AST declaration kind
   in :declarations. Initialization derives the public registry shape:
   :name is the resource basename, :kind is :shared or :target, :names
   lists all declarations, and :atoms lists only :def-atom declarations.
   Modules without atoms omit :atoms.

   The manifest stays independent of resource loading and parsing.
   user-declaration-collides-with-prelude-name-rejected checks name and
   atom membership against the shipped sources for every target."
  (mapv
    (fn [{:keys [declarations] :as module}]
      (cond-> (assoc (dissoc module :declarations)
                     :names (into [] cat (vals declarations)))
        (:def-atom declarations) (assoc :atoms (:def-atom declarations))))
    [{:name :time :kind :shared
      :declarations {:record ["Duration" "Instant"]}}
     {:name :geom :kind :shared
      :declarations {:record ["Position"]}}
     {:name :input :kind :shared
      :declarations {:enum ["Key"] :def-atom ["TestKeyboardInput"]}}
     {:name :system :kind :shared
      :declarations {:def-atom ["SystemPanic"] :record ["Uncaught"]}}
     {:name :errors :kind :shared
      :declarations {:record ["IOError" "HttpError" "ShellError" "JsonError"
                              "EnvError" "RandomError"]}}
     {:name :async :kind :shared
      :declarations {:def-atom ["CancelToken"]}}
     {:name :seeded_prng :kind :shared
      :declarations {:def-atom ["SeededPrng"]}}
     {:name :subprocess_types :kind :shared
      :declarations {:enum ["EnvSpec"] :record ["ProcResult" "HttpResponse"]}}
     {:name :semver :kind :shared
      :declarations {:record ["SemVer" "VersionRange"]}}
     {:name :plugin :kind :shared
      :declarations {:def-atom ["Plugin"]}}
     {:name :os_primitive :kind :shared
      :declarations {:enum ["OsPrimitive"]}}
     {:name :random :kind :target
      :declarations {:def-atom ["Random"]}}
     {:name :clock :kind :target
      :declarations {:def-atom ["Clock"]}}
     {:name :console :kind :target
      :declarations {:def-atom ["Console"]}}
     {:name :fs :kind :target
      :declarations {:def-atom ["FileSystem"]}}
     {:name :subprocess :kind :target
      :declarations {:def-atom ["Subprocess"]}}
     {:name :env :kind :target
      :declarations {:def-atom ["EnvVars" "EnvMut"]}}
     {:name :args :kind :target
      :declarations {:def-atom ["Args"]}}
     {:name :json :kind :target
      :declarations {:def-atom ["Json"]}}
     {:name :http :kind :target
      :declarations {:def-atom ["Http"]}}
     {:name :hash :kind :target
      :declarations {:def-atom ["Hash"]}}
     ;; Distinct from the shared :time module declaring Duration/Instant.
     {:name :wall_time :kind :target
      :declarations {:def-atom ["Time"]}}
     ;; §4.18.3 slice 1 — runtime metadata queries backed by compile-
     ;; time tables emitted into the binary (`emit.clj` reflection-table
     ;; block). Later slices add methods, invariants, conformances.
     {:name :reflection :kind :target
      :declarations {:def-atom ["Reflection"]}}]))

(defn assemble-source
  "Load module sources for target and join them with newlines, in
   registry order. Shared modules use the cross-target resource tree."
  [target]
  (str/join "\n" (map #(load-module-src % target) prelude-modules)))

(defn source
  "Default-target source for tests that parse prelude and user source
   together. Kept as a function to defer resource I/O until called.
   Production builds use assemble-source with the selected target."
  []
  (assemble-source default-target))

(def atom-names
  "User-visible prelude atom names, derived from the module manifest.
   Used by cxc.lower for tree-shaking."
  (into #{} (mapcat :atoms) prelude-modules))

(def ^:private class-member-desugars
  "Constructor ASTs for the supported [type-name member-name] pairs."
  {["Instant" "epoch"]
   {:node   :call
    :callee {:node :ident :name "Instant"}
    :args   [{:kind :named :name "millis"
              :value {:node :lit-int :value 0}}]}
   ["Position" "origin"]
   {:node   :call
    :callee {:node :ident :name "Position"}
    :args   [{:kind :named :name "x" :value {:node :lit-float :value 0.0}}
             {:kind :named :name "y" :value {:node :lit-float :value 0.0}}]}})

(defn- desugar-class-member
  "Replace a known prelude class-member reference with its constructor
   AST. Accepts any value visited by postwalk and leaves others intact."
  [node]
  (if (and (map? node)
           (= :dot (:node node))
           (= :ident (get-in node [:target :node])))
    (get class-member-desugars
         [(get-in node [:target :name]) (:field node)]
         node)
    node))

(defn desugar-prelude-class-members
  "Rewrite Instant.epoch and Position.origin to constructor calls in
   the parsed AST before lowering."
  [nodes]
  (walk/postwalk desugar-class-member nodes))

(def declaration-kinds
  "Top-level AST kinds whose :name identifies a user declaration.
   Shared by prelude, resolve, env, and build."
  #{:def-atom :record :enum})

(def ^:private protected-prelude-names
  "Manifest declaration names that user declarations cannot shadow.
   Stream is supplied by the runtime and is outside this set."
  (into #{} (mapcat :names) prelude-modules))

(defn validate-no-prelude-collisions!
  "Reject user declarations that shadow prelude names (spec §4.20).
   Takes user-only top-level AST nodes before prelude prepending."
  [user-top-nodes]
  (doseq [node user-top-nodes
          :when (and (contains? declaration-kinds (:node node))
                     (contains? protected-prelude-names (:name node)))]
    (diag/fail-compile!
      :prelude-name-collision
      (str "user declaration `" (:name node)
           "` collides with a protected prelude name (spec §4.20). "
           "Rename the declaration.")
      node
      {:name (:name node)})))
