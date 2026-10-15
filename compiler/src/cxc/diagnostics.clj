(ns cxc.diagnostics
  "M1b prep — compile-error surface for the lowering layer, extracted
   from cxc.lower. Three funnels, chosen by what a hypothetical
   downstream tool (LSP, error UI) would want to do with the failure:

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

   Triage rule: if the failing message says \"duplicate\", \"expected …
   got\", \"requires\", or references a spec § the user's source violates
   — it's `fail-user!`. If it says \"not yet lowered\", \"NYI\", or names
   a plan-doc landing pending — it's `fail-nyi!`. `fail-compile!` is
   for the few call sites that get their own kind slug.")

(defn fail-nyi! [what node]
  (throw (ex-info (str "lowering not yet implemented: " what)
                  {:kind :not-yet-implemented :what what :node node})))

(defn fail-user! [what node]
  (throw (ex-info what
                  {:kind :cxc-user-error :what what :node node})))

(defn fail-compile! [kind msg node]
  (throw (ex-info msg {:kind kind :what msg :node node})))
