(ns cxc.diagnostics
  "Compile-error surface for the lowering layer, extracted from cxc.lower.

   Three throw funnels, chosen by what a downstream tool (LSP, error UI,
   cxc check CLI, agent write-loop hook) would want to do with the failure:

     fail-nyi!    — a language surface the compiler hasn't landed yet.
                    Message is \"this feature is planned but not
                    implemented\" + a pointer at the tracking plan.
                    Discovering one means the user hit an edge of the
                    bootstrap; the fix is a compiler landing.

     fail-user!   — a structural user error: the source violates a
                    spec rule (duplicate names, wrong-target reference,
                    reference modifier on a value type). Message names
                    the rule + spec §. The fix is in the user's source.

     fail-compile!— per-site diagnostic with a caller-supplied :kind
                    keyword. Use when the diagnostic is one-of-a-kind
                    (F51 unresolved name, F9 duplicate protocol
                    signature) and the caller wants a distinct :kind
                    for downstream classification (LSP, tests grep).

   Triage rule. If the failing message says \"duplicate\", \"expected …
   got\", \"requires\", or references a spec § the user's source violates
   it is `fail-user!`. If it says \"not yet lowered\", \"NYI\", or names a
   plan-doc landing pending it is `fail-nyi!`. `fail-compile!` is for
   the few call sites that get their own kind slug.

   Diagnostic data shape (ex-info :data):

     {:kind         <keyword>              classifier for downstream filtering
      :what         <string>               human-readable error message
      :node         <ast-node or nil>      source AST node, carries :line/:col
      :location     <loc-map or nil>       extracted {:line :col :file}
      :rule-ref     <string or nil>        catalogue rule ID (e.g. \"NUL-001\")
      :suggested-fix <fix-map or nil>      structured fix hint, two shapes:
                                            {:kind :replace
                                             :range {:start {...} :end {...}}
                                             :new-text <string>}
                                            {:kind :advice :text <string>}
      :file         <string or nil>        file path, bound via *current-file*}

   The three fail-* fns accept an optional opts map carrying :rule-ref,
   :suggested-fix, and any additional keys. Existing (what, node) /
   (kind, msg, node) call sites remain source-compatible, Phase 1a is
   additive. Phase 1b wires rule-refs at the 260 existing throw sites per
   design/plans/diagnostic-pattern-dictionary.md.")

(set! *warn-on-reflection* true)

(def ^:dynamic *current-file*
  "Compile-driver-bound file path for the input currently being lowered.
   nil when unknown. Diagnostics merge this into :location so downstream
   tools (LSP, cxc check CLI, agent hooks) can route the diagnostic back
   to the right file without having to re-derive the path."
  nil)

(defn location-from-node
  "Extract {:line :col :file} from an AST node. The parser attaches :line
   and :col at the top of each node (see cxc.parse). Falls back gracefully
   when fields are missing. Returns nil only when the node has neither
   :line nor :col AND no *current-file* is bound."
  [node]
  (let [line (:line node)
        col  (:col node)
        file *current-file*]
    (when (or line col file)
      (cond-> {}
        file (assoc :file file)
        line (assoc :line line)
        col  (assoc :col col)))))

(defn- build-data
  "Merge the caller-supplied opts over the base diagnostic shape.
   opts can carry :rule-ref, :suggested-fix, and any additional keys."
  [base node opts]
  (let [loc (location-from-node node)]
    (cond-> (assoc base :node node)
      loc           (assoc :location loc)
      *current-file* (assoc :file *current-file*)
      :always       (merge opts))))

(defn fail-nyi!
  "Compiler-coverage gap. The language surface named by `what` is planned
   but not landed. Rule-ref defaults to NYI-001."
  ([what node]
   (fail-nyi! what node {}))
  ([what node opts]
   (throw (ex-info (str "lowering not yet implemented: " what)
                   (build-data {:kind :not-yet-implemented
                                :what what
                                :rule-ref (or (:rule-ref opts) "NYI-001")}
                               node
                               (dissoc opts :rule-ref))))))

(defn fail-user!
  "Structural user error. Message names the rule or spec §. Rule-ref is
   the catalogue ID (see design/plans/diagnostic-rules.md and
   diagnostic-pattern-dictionary.md), nil until Phase 1b backfills."
  ([what node]
   (fail-user! what node {}))
  ([what node opts]
   (throw (ex-info what
                   (build-data {:kind :cxc-user-error
                                :what what}
                               node
                               opts)))))

(defn fail-compile!
  "Per-site diagnostic with a caller-supplied :kind keyword. Downstream
   tools filter / group on :kind. Rule-ref maps the kind to the catalogue
   per diagnostic-pattern-dictionary.md."
  ([kind msg node]
   (fail-compile! kind msg node {}))
  ([kind msg node opts]
   (throw (ex-info msg
                   (build-data {:kind kind
                                :what msg}
                               node
                               opts)))))

(defn- loc->json
  "Serialize a location map for JSON output. Parser tracks start
   only. Downstream LSP defaults end to start when a span lands,
   restoring the end-line / end-col keys at that time."
  [loc]
  (some-> loc (select-keys [:file :line :col])))

(defn- fix->json
  "Serialize a suggested-fix map for JSON output. Two shapes:
   :replace with range + new-text, :advice with text."
  [fix]
  (when fix
    (case (:kind fix)
      :replace {:kind "replace"
                :range (:range fix)
                :new_text (:new-text fix)}
      :advice  {:kind "advice"
                :text (:text fix)}
      nil)))

(defn ->json-diagnostic
  "Convert an ex-info diagnostic to a JSON-friendly map. Suitable for
   emission by cxc check CLI, daemon :check response, and the LSP server's
   textDocument/publishDiagnostics payload.

   Keys use snake_case strings per JSON convention. Nil-valued keys are
   dropped so downstream clients see a clean shape."
  [ex]
  (let [data (ex-data ex)
        loc  (loc->json (:location data))
        fix  (fix->json (:suggested-fix data))]
    (cond-> {:kind (some-> (:kind data) name)
             :message (or (:what data) (ex-message ex))}
      loc              (assoc :location loc)
      (:rule-ref data) (assoc :rule_ref (:rule-ref data))
      fix              (assoc :suggested_fix fix)
      (:file data)     (assoc :file (:file data)))))
