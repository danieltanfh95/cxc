(ns cxc.lower.enum-env
  "Build enum-env from top-level `def Foo:` with case markers.")

(set! *warn-on-reflection* true)

(defn build-enum-env
  "Top-level `def Foo:` with case markers. Env keyed by enum name:
     :cases          — vector of case names in decl order (bare and
                       payload cases both listed; identity-only reads
                       still consult this — pre-F16-L1 behavior).
     :case-index     — name → decl-order index (used as the C integer
                       tag value in the value-only rep and as the
                       `<Enum>_Tag_<case>` value in the tagged rep).
     :case-entries   — vector of `{:name :payload}` maps, decl order.
     :payload-index  — name → payload vector (empty for bare cases).
     :tagged?        — true when at least one case has payload;
                       determines whether the enum lowers to a
                       tagged-union struct or a plain C enum (F16 L1).

   F16 L2 — raw-value enums carry `:raw-values` (parallel to `:cases`)
   and `:raw-type` (smallest fitting unsigned int name, per spec §3.3)
   on the enum node. Env exposes both for downstream emit + synthesis."
  [top-nodes]
  (into {}
        (for [e (filter #(= :enum (:node %)) top-nodes)]
          (let [entries        (:case-entries e)
                payload-index  (into {}
                                     (map (juxt :name :payload) entries))
                tagged?        (some #(seq (:payload %)) entries)
                raw-values     (:raw-values e)
                raw-type       (:raw-type e)]
            [(:name e) (cond-> {:cases         (:cases e)
                                :case-index    (into {}
                                                     (map-indexed (fn [i c] [c i])
                                                                  (:cases e)))
                                :case-entries  entries
                                :payload-index payload-index
                                :tagged?       (boolean tagged?)}
                         raw-values (assoc :raw-values raw-values)
                         raw-type   (assoc :raw-type   raw-type))]))))
