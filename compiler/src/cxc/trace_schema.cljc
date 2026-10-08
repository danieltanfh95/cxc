(ns cxc.trace-schema
  "Portable annotation-sidecar schema. Shared source of truth for the CLJ
   CLI/server (cxc.trace-annotations) and the CLJS viewer (cxc-viewer.*).

   This namespace is .cljc so both runtimes read the same enums and the
   same target-brief / filter-annotations / effective-status / terminal?
   definitions. Anything that touches disk, time, shell, or JVM-only APIs
   stays in cxc.trace-annotations (clj-only).

   When adding a schema value here, prefer broadening to breaking. The
   sidecar is on-disk JSON tracked in git, so legacy records written
   against an older schema need to keep round-tripping."
  (:require [clojure.string :as str]))

(def schema-version 1)

(def statuses
  #{"draft" "open" "in_progress" "addressed" "verified" "wontfix" "stale"})

(def severities
  #{"bug" "finding" "advisory" "praise"})

(def kinds
  #{"event" "range" "node" "edge" "expected_vs_actual"})

(def terminal-statuses
  #{"verified" "wontfix" "stale"})

(defn terminal? [status]
  (contains? terminal-statuses status))

(defn effective-status
  "Missing status defaults to `open`, matching legacy sidecars written
   before `status` was mandatory."
  [ann]
  (or (:status ann) "open"))

(defn empty-sidecar
  "Fresh empty sidecar value. Caller supplies the provenance fields; empty
   strings are legal for new reviews that have no commit or trace hash
   yet. `:rev` is monotonic and bumps on every write."
  [source commit trace-sha]
  {:cxc_annotation_version schema-version
   :source                 source
   :commit                 commit
   :trace_sha256           trace-sha
   :rev                    0
   :annotations            []})

(defn effective-rev
  "Missing rev defaults to 0, matching legacy sidecars written before CAS."
  [sidecar]
  (or (:rev sidecar) 0))

(defn validate-annotation
  "Return nil when `a` validates, else a short string naming the first
   defect. Rules: id must be non-empty string; status/kind/severity (when
   present) must come from the known sets; target must be a map. Shared
   across writers so the viewer, CLI, and HTTP server reject the same
   malformed records."
  [a]
  (cond
    (not (map? a))
    "annotation is not a map"
    (or (not (string? (:id a))) (empty? (:id a)))
    "annotation missing :id"
    (and (:status a) (not (contains? statuses (:status a))))
    (str "unknown status: " (pr-str (:status a)))
    (and (:kind a) (not (contains? kinds (:kind a))))
    (str "unknown kind: " (pr-str (:kind a)))
    (and (:severity a) (not (contains? severities (:severity a))))
    (str "unknown severity: " (pr-str (:severity a)))
    (and (:target a) (not (map? (:target a))))
    "annotation :target must be a map"))

(defn validate-sidecar
  "Return nil when `s` validates as a sidecar, else a short string naming
   the first defect. Checks top-level shape, then every annotation."
  [s]
  (cond
    (not (map? s))
    "sidecar is not a map"
    (and (:annotations s) (not (sequential? (:annotations s))))
    ":annotations must be a sequence"
    :else
    (some (fn [[i a]]
            (when-let [e (validate-annotation a)]
              (str "annotation[" i "]: " e)))
          (map-indexed vector (or (:annotations s) [])))))

(defn filter-annotations
  "Filter by any of status/severity/kind. Each filter is a plain `=`
   against the annotation's field, with status defaulted via
   `effective-status` so an un-fielded legacy record still matches
   `status=\"open\"`."
  [anns {:keys [status severity kind]}]
  (cond->> anns
    status   (filter #(= status   (effective-status %)))
    severity (filter #(= severity (:severity %)))
    kind     (filter #(= kind     (:kind %)))))

(defn target-brief
  "One-line summary of an annotation target, shared across the CLI
   listing, the channel push message, and the viewer badge, so every
   renderer shows the same string for the same target value."
  [target]
  (cond
    (:trace_seq target)
    (str "seq="    (:trace_seq target)
         " "       (or (:event_kind target) "?")
         (when (:atom target)     (str " atom=" (:atom target)))
         (when (:atom_cls target) (str " atom_cls=" (:atom_cls target)))
         (when (:msg  target)     (str " msg=" (:msg target))))

    (:trace_seq_start target)
    (str "seq=" (:trace_seq_start target) ".." (:trace_seq_end target))

    (and (:from_atom target) (:to_atom target))
    (str (:from_atom target) "→" (:to_atom target)
         (when (:msg target) (str " via " (:msg target))))

    (:atom target)
    (str "atom=" (:atom target)
         (when (:phase target) (str " phase=" (:phase target))))

    :else
    (pr-str target)))

(defn trace->annotations-path
  "Resolve the annotations sidecar path for a trace path.
   cards.trace.jsonl → cards.annotations.json. Pure string transform, so
   both CLJ and CLJS can derive the key without touching the file
   system."
  [trace-path]
  (-> trace-path
      (str/replace #"\.trace\.jsonl$" ".annotations.json")
      (str/replace #"\.jsonl$"        ".annotations.json")))
