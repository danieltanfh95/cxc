# CLI reference

The `cxc` CLI entry lives at `compiler/bin/cxc`. All subcommands dispatch through `cxc.core`.

## Compilation

```
cxc compile <input.cxc-or-dir> <out-dir> [--target=<name>] [--trace]
```

Standard compile. `--trace` enables trace emission; the binary writes `<name>.trace.jsonl` on run.

## Trace summary (legacy)

```
cxc trace <file.jsonl> [--top=N] [--silent-clock]
```

Prints a summary of a trace. For deeper inspection use the viewer.

## Trace filtering and diff

```
cxc trace-filter <file.jsonl> [--kinds=...] [--atom=...] [--selector=...] [--seq=lo-hi]
cxc trace-diff   <a.jsonl> <b.jsonl>
```

## Browser viewer and server

```
cxc view                                 # launches the viewer in default browser
cxc trace serve [--port 8767] [--trace-dir <path>]
```

`cxc view` is the legacy wrapper that launches a browser pointing at a running server. `cxc trace serve` is the server itself; the Simple Browser in VS Code reaches it directly.

## Annotation review

Read:

```
cxc trace annotation list [--status X] [--severity Y] [--kind Z]
                          [--trace path] [--format table|json]
cxc trace annotation show <id> [--trace path]
```

Write, lifecycle:

```
cxc trace annotation claim     <id> [--as who]
cxc trace annotation comment   <id> --text "..." [--commit sha] [--as who]
cxc trace annotation addressed <id> --commit sha --summary "..." [--as who]
cxc trace annotation wontfix   <id> --reason "..." [--as who]
cxc trace annotation verified  <id> [--as who]
```

Write, creation:

```
cxc trace annotate --from-focus --note "..." --severity bug|finding|advisory|praise
                   --kind event|range|node|edge|expected_vs_actual
                   [--proposed-fix "..."] [--as who]
```

Focus:

```
cxc trace focus
```

## Flag conventions

- `--trace <path>` points at a trace file. The sidecar is resolved as `<path>.annotations.json`. Defaults to the first `*.trace.jsonl` in cwd.
- `--as <who>` overrides the author field. Defaults to `git config user.name`, then falls back to `agent`.
- `--commit <sha>` is a git short or long sha. The commit-landed semantics are per-user, so pass the sha that would show in `git log`.
- `--format json` on `list` emits the raw annotation JSON for machine consumption.

## Exit codes

- `0`: success.
- `1`: expected failure, e.g. "annotation not found", "already terminal", "claim conflict". Error message on stderr.
- `2`: usage error, e.g. missing required flag.

## Agent patterns

For an agent processing open annotations in batch:

```
cxc trace annotation list --status open --format json
# → iterate ids, claim each, show, fix, comment, addressed
```

For a human handing an annotation set to a different agent via the viewer:

```
# Click "export" in the viewer, paste the markdown block into the agent's chat.
```

For a cross-tool share without the viewer:

```
cxc trace annotation list --status open --format json > /tmp/handoff.json
```
