# cxc / rust / ts structural comparison

Three panes of the same program. The point of the comparison is the effort asymmetry, not the three graphs being apples-to-apples.

- **cxc pane** iframes `trace-viewer-legacy.html` loaded with `cards.shape.json` and a 2850-event sample of `cards.trace.jsonl`, both emitted by `cxc compile cards.cxc --trace`. The viewer renders what the compiler already knows — atoms with phase/arm counts, labeled transition edges, directional sends. Zero custom code to produce the lifecycle flow graph.
- **rust pane** reads `cards.rs.graph.json`, emitted by `extract-graphs.js` walking the tree-sitter-rust AST. There is no equivalent built-in lifecycle view in rust tooling, so this is an approximation: a static declaration graph of `struct` / `enum` / `trait` / free `fn`, with `impl` methods merged into the host container.
- **ts pane** reads `cards.ts.graph.json`, same extractor over tree-sitter-typescript. Same approximation story — static declaration graph of `class` / `interface` / `enum` / `type` / free `function` / module consts.

The declaration graph the rust and ts panes show is nowhere near what the cxc trace shape carries. There are no phases, no transition arms, no directional message edges — because those concepts don't exist at that level in the source language. Reconstructing them would require type-flow + control-flow analysis + a lifecycle-inference pass, which is where the token spend lives.

## Files

- `compare.html` — three panels. Rust and TS panes use the same ELK + hairline style as the trace viewer. The cxc pane embeds the trace viewer itself via iframe.
- `cards.cxc` — pocket MOBA port, bench run `opus-corvid-translate-1-v27d1` (1256 loc, bp 19/19).
- `cards.rs` — same program, bench run `opus-rust-translate-1-v22` (1340 loc, bp 19/19).
- `cards.ts` — same program, bench run `opus-ts-translate-1-v22` (1255 loc, bp 19/19).
- `cards.shape.json` — compiler-emitted. Produced by `cxc compile cards.cxc <outdir> --trace`.
- `cards.trace.jsonl` — first 2850 events of a run of the compiled binary, trimmed to keep the iframe fast.
- `cards.rs.graph.json`, `cards.ts.graph.json` — tree-sitter walker output. Produced by `node extract-graphs.js`.
- `extract-graphs.js` — tree-sitter walker, Rust and TypeScript grammars pinned via `package.json`.
- `vendor/elk.bundled.js` — the ELK bundle the trace viewer uses, kept locally so this directory is self-contained.
- `node_modules/` — tree-sitter runtime and language grammars, gitignored.

Source bench runs live at `~/Projects/g-daniel/bench/cxc-agent-baseline/runs/<run>/cards.<ext>`. The local copies here keep the comparison self-contained so re-running `opus-rust-translate-1-v23` later doesn't retro-change the artifact.

## Regenerating

```
cd compiler/tools/compare

# cxc shape + trace — needs the local cxc binary
CXC_NO_DAEMON=1 cxc compile cards.cxc /tmp/compare-cxc-out --trace
cp /tmp/compare-cxc-out/cards.shape.json .
CXC_TRACE_PATH=/tmp/compare-cxc-out/cards.trace.jsonl /tmp/compare-cxc-out/cards > /dev/null
head -c 500000 /tmp/compare-cxc-out/cards.trace.jsonl \
  | awk '{if(prev!="") print prev; prev=$0}' > cards.trace.jsonl

# rust / ts graphs — tree-sitter walker, run after any source change
node extract-graphs.js
```

Then hard-refresh the page.

## Serving

The compare page iframes `../trace-viewer-legacy.html`, so the server must be rooted at `compiler/tools/`, not `compiler/tools/compare/`:

```
cd compiler/tools
python3 -m http.server 8780
open http://127.0.0.1:8780/compare/compare.html
```

## When to regenerate

- A new bench run beats the current translation on byte-parity + loc.
- Design-language change in `trace-viewer-legacy.html` worth mirroring here.
- Compiler schema change that adds or drops shape fields.

## Related

- `compiler/tools/trace-viewer-legacy.html` — the shape graph viewer whose palette this borrows.
- `bench/cxc-agent-baseline/report/corvid-wall-gap-measurement.md` — the wall-time comparison across the same three languages.
