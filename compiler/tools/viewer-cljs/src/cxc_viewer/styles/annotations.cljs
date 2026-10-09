(ns cxc-viewer.styles.annotations)

(def ^:private status-headings "
.ann-status-group { margin: 10px 0; }
.ann-status-heading { display: block; margin-bottom: 4px; padding: 3px 6px;
  border: 0.5px solid var(--p-ink2); font-size: 10px; font-weight: 600;
  letter-spacing: .08em; background: var(--p-paper2); color: var(--p-ink); }
.ann-status-heading[data-status=\"draft\"]       { border-color: var(--p-indigo); }
.ann-status-heading[data-status=\"open\"]        { border-color: var(--p-accent); }
.ann-status-heading[data-status=\"in_progress\"] { border-color: var(--p-rust); }
.ann-status-heading[data-status=\"addressed\"]   { border-color: var(--p-success); }
.ann-status-heading[data-status=\"verified\"]    { border-color: var(--p-success); }
.ann-status-heading[data-status=\"wontfix\"]     { border-color: var(--p-ink2); }
.ann-status-heading[data-status=\"stale\"]       { border-color: var(--p-ink2); }
")

(def ^:private panel "
.annotations { font-size: 11px; }
.annotations .ann-header { white-space: nowrap; }
.annotations .ann-header:hover { background: var(--p-fade); }
.annotations .ann-summary-row { display: flex; align-items: center; gap: 6px; flex: 1; min-width: 0; }
.annotations .ann-summary-label { flex: 1; min-width: 0; }
.annotations .ann-count { color: var(--p-ink2); font-weight: 400; }
.annotations .ann-filters { display:flex; flex-wrap:wrap; gap: 4px; padding: 6px 0; }
.annotations .ann-filter { font-size: 11px; padding: 2px 6px; border: 1px dotted var(--p-dim);
  background: transparent; cursor: pointer; font-family: inherit; color: var(--p-ink); }
.annotations .ann-filter[data-active=\"true\"] { background: var(--p-accent); color: var(--p-bg);
  border-color: var(--p-accent); }
.annotations .ann-list { list-style:none; padding: 0; margin: 0; }
")

(def ^:private row "
.annotations .ann-row { padding: 6px; margin: 2px 0; cursor: pointer;
  border-left: 2px solid var(--ann-status-color, var(--p-ink2)); line-height: 1.5; overflow-wrap: anywhere; }
.annotations .ann-row:hover { background: var(--p-fade); }
.annotations .ann-row[aria-current=\"true\"] { background: var(--p-fade);
  outline: 1px solid var(--p-ink2); outline-offset: -1px; }
.annotations .ann-row[data-status=\"draft\"]       { --ann-status-color: var(--p-indigo); }
.annotations .ann-row[data-status=\"open\"]        { --ann-status-color: var(--p-accent); }
.annotations .ann-row[data-status=\"in_progress\"] { --ann-status-color: var(--p-rust); }
.annotations .ann-row[data-status=\"addressed\"]   { --ann-status-color: var(--p-success); }
.annotations .ann-row[data-status=\"verified\"]    { --ann-status-color: var(--p-success); }
.annotations .ann-row[data-status=\"wontfix\"]     { --ann-status-color: var(--p-ink2); }
.annotations .ann-row[data-status=\"stale\"]       { --ann-status-color: var(--p-ink2); }
.annotations .ann-status-swatch { display: inline-block; width: 7px; height: 7px;
  background: var(--ann-status-color, var(--p-ink2)); }
.annotations .ann-row .ann-head { display:flex; flex-wrap: wrap; gap: 4px;
  font-size: 11px; align-items: center; }
.annotations .ann-row .ann-id { color: var(--p-ink); font-weight: 700; }
.annotations .ann-row .ann-sev { color: var(--p-ink2); font-size: 11px; font-weight: 400; }
.annotations .ann-row .ann-kind { color: var(--p-ink2); font-size: 9px; font-weight: 400; }
.annotations .ann-empty { color: var(--p-ink2); padding: 4px 0; }
.annotations .ann-note { margin: 2px 0 0; color: var(--p-ink2); }
.annotations .ann-actions { display:flex; gap: 6px; }
.annotations .ann-actions.terminal { color: var(--p-dim); font-family: 'IBM Plex Mono', monospace;
  font-size: 11px; }
")

(def ^:private controls "
.annotation-controls { display: flex; gap: 6px; margin-top: 8px; }
.annotation-controls button { flex: 1; min-width: 0; }
.annotations .annotation-controls .primary { background: var(--p-ink); color: var(--p-paper); font-weight: 600; }
.annotations .annotation-controls .primary:hover { background: var(--p-ink2); }
.annotations .btn { font-size: 11px; line-height: 1.4; letter-spacing: 0;
  padding: 4px 8px; border: 0.75px solid var(--p-hair); border-radius: 0;
  background: var(--p-paper); cursor: pointer; font-family: inherit; color: var(--p-ink); }
.annotations .btn:hover { background: var(--p-fade); border-color: var(--p-ink2); }
.annotations .btn:disabled { opacity: .5; cursor: default; }
.annotations .ann-table-toggle { flex-shrink: 0; min-height: 24px;
  font-size: 10px; text-transform: none; padding: 2px 5px; }
.annotations .ann-table-toggle[aria-pressed=\"true\"] { background: var(--p-fade); border-color: var(--p-indigo); }
.annotations .btn.verify { border-color: var(--p-accent); color: var(--p-accent); }
.annotations .btn.verify:hover { background: var(--p-accent); color: var(--p-bg); }
")

(def ^:private top-actions-and-comments "
.ann-top-actions { display: flex; gap: 8px; align-items: center; padding: 6px 0;
  border-bottom: 1px dotted var(--p-dim); flex-wrap: wrap; }
.drafts-controls { display: inline-flex; gap: 6px; align-items: center; margin-left: auto;
  font-size: 11px; color: var(--p-dim); }
.ann-comments { margin-top: 6px; }
.ann-comments-list { list-style: none; margin: 0; padding: 0; }
.ann-comment { font-size: 11px; padding: 4px 0; border-top: 0.5px dotted var(--p-dim); }
.ann-comment .c-author { color: var(--p-accent); font-weight: 600; }
.ann-comment .c-at { color: var(--p-dim); font-size: 9px; }
.ann-reply textarea { width: 100%; font: 11px 'IBM Plex Mono', monospace;
  padding: 4px 6px; border: 0.5px solid var(--p-dim); background: var(--p-bg);
  color: var(--p-ink); resize: vertical; min-height: 60px; box-sizing: border-box; }
.ann-reply .reply-actions { display: flex; gap: 6px; justify-content: flex-end; margin-top: 4px; }
.btn.reply-open { font-size: 10px; padding: 2px 6px; margin-top: 4px; }
.btn.primary { background: var(--p-accent); color: var(--p-bg); border-color: var(--p-accent); }
")

(def ^:private table-view "
.ann-table-pane { overflow-x: auto; }
.ann-table td { vertical-align: top; }
.ann-full-note { padding: 10px 0; }
.saved-attachments { display: flex; gap: 8px; flex-wrap: wrap; }
.saved-attachments img { max-width: 160px; max-height: 120px; }
")

(def css
  (str status-headings panel row controls top-actions-and-comments table-view))
