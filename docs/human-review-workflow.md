# Human review workflow

Engineers use the cxc trace viewer to review a program's lifecycle and hand annotations to an agent. For the agent-side CLI and skill routing, see `.claude/skills/cxc/references/trace-review.md`. For the end-to-end dogfood example on `sample-01-single-atom`, see [trace-review.md](trace-review.md). For the schema and the design rationale behind the lifecycle, see [design/trace-annotation.md](design/trace-annotation.md).

## The four verbs

```text
point   → click a thing in the graph (atom, phase, edge, trace event, range)
click   → focus toolbar appears with [+ annotate] [⎘ copy link]
annotate→ freeform textarea, optional pasted image or URL
send    → Cmd+Enter to ship to the agent, Cmd+Shift+Enter to save as draft
```

Those four gestures cover the entire surface. No kind picker, no severity picker, no destination picker. The agent interprets what you wrote and decides how to respond.

## Setup

Two commands, two terminals:

```bash
cxc trace serve --trace-dir <dir> --port 8767   # viewer host + sidecar POST + push fan-out
claude                                 # your usual Claude Code session, nothing custom
```

Open `http://localhost:8767/trace-viewer.html?trace=<name>.trace.jsonl` for viewer v2, including drafts, replies and bulk submission. Submitted annotations push into any live Claude Code session on this machine via its per-session inbox socket at `/tmp/cc-socks/<pid>.sock`. No MCP config or dev flags.

Use `compiler/bin/cxc` from a source checkout (or put `compiler/bin` on your `PATH`). `cxc trace serve` and `cxc trace view` automatically build `compiler/tools/dist/trace-viewer-v2.js` when it is missing or empty. This is the build-prerequisite distribution path: install Clojure CLI, Java and Node.js, with Maven access for shadow-cljs, Replicant and Garden. Build failures stop the launcher with a dependency message. Development bundles stay ignored by git. After editing viewer sources, rebuild explicitly:

```sh
cd compiler/tools/viewer-cljs
clojure -M -m shadow.cljs.devtools.cli release viewer-v2
```

For inspection without a review server, use `cxc view <trace.jsonl> --port 8765`. `cxc view` copies the trace and shape into the trace viewer host and opens the browser. Annotation persistence needs `cxc trace serve`. Relative trace and directory paths resolve from the invoking directory. Ports and annotation IDs pass through as values.

Dropped files are editable only after their SHA-256 matches the server-mounted artifact of the same name. A different file with the same basename remains inspectable and displays an annotations-disabled banner. It never loads or writes that artifact's review. In-progress reviews, including unsent popup and reply text, survive artifact switches in this browser session. Closing or reloading with unsaved work prompts before leaving.

Three processes end up running:

1. The browser (viewer HTML).
2. `cxc trace serve` (viewer host + sidecar POST handler + inbox-socket poster).
3. One or more `claude` sessions (any of them, running anywhere on the machine).

All three write to or read the SAME sidecar file (`<trace-base>.annotations.json`). The server's filesystem watcher catches changes regardless of who wrote them (viewer POST, CLI write, git pull).

**Receiver-side setting handled for you.** Claude Code's default inbound rule holds peer messages for approval when the receiver runs in `bypassPermissions` mode. On first run, `cxc trace serve` merges `{"crossSessionInbound":"accept"}` into `~/.claude/settings.json` so pushes land immediately. The merge is idempotent and only touches the one key:

- If the file doesn't exist, it's created with just that one key.
- If the key is unset, it's added, every other key preserved.
- If the key is already `"accept"`, nothing is written.
- If the key is explicitly `"hold"` or `"refuse"`, the setting is left untouched, your choice wins, and the banner reports the current value so you know pushes won't land.

Pass `--no-accept-peer-inbound` to skip the merge if you don't want `cxc trace serve` to touch user settings. Pass `--no-push` to disable the fan-out entirely and run the trace viewer in isolation.

## Writing an annotation

Click any primitive, an atom header, a phase chip inside the phase graph, an edge in the shape view, an event in the playthrough, a drag-selected range. The right-side detail pane updates with information about what you selected and a focus toolbar appears at the top of the pane with two buttons:

- **+ annotate** opens the popup with target prefilled
- **⎘ copy link** copies a `[name](url)` markdown link to clipboard, ready to paste into a chat, PR description, or planning doc. The link URL carries a `?focus=…` param so pasting it back into a browser auto-opens the same primitive.

In the popup, type whatever you need to say in freeform English. Claude reads each note and infers whether the note asks a question, reports a bug, requests a change, or explores an idea. You don't need to categorize it.

**Paste an image** (screenshot, diagram) directly into the textarea. The trace viewer uploads it to the server and attaches it as an image reference to the annotation. Thumbnails show up below the textarea with × to remove. In the detail pane later, images render inline so the agent (and other reviewers) see them.

**Paste a URL** into the textarea. If the clipboard contains a bare URL, the trace viewer wraps it as `[url](url)` markdown inline in the note. For prose-adjacent references ("see this ticket..."), this keeps the URL anchored to its context.

## Send now vs save draft

- **Cmd+Enter (or click "send")**, writes the annotation as `open` and stamps `submitted_at`. The channel immediately pushes it to the agent. Use this for one-at-a-time annotations where you're not building a batch.
- **Cmd+Shift+Enter (or click "save draft")**, writes as `draft`. Drafts are invisible to the agent and the CLI (`cxc trace annotation list` hides them by default). They accumulate in a DRAFTS section at the top of the sidebar. When you're ready, click **"submit to agent →"** at the top of the DRAFTS section to bulk-flip them all to `open` in one action.

**Draft rows are editable in place**. Click the row body to reopen the popup with the note prefilled. × next to each draft discards it. Non-draft annotations do not have this affordance, once submitted, the record is append-only (comments can still be added via the reply box below).

## When the agent is handling your annotation

The agent replies within seconds with "I'm looking into these N things, give me a moment." Subagents investigate unclear annotations in parallel and return short summaries. The main-thread agent composes a single response that walks each annotation in turn.

**Discussion continues in your Claude Code chat**, this is where the long exploratory phase happens. The agent asks clarifying questions, you push back, you discuss. The main thread keeps your work context intact.

**You read the agent's reply in the trace viewer, not just chat.** On push, `cxc trace serve` writes a `pending` comment to the sidecar carrying a short reply-token. The agent includes the token in its response, and `cxc trace serve` finds matching assistant turns through `/chat-excerpt?token=X` by grepping your local Claude Code transcripts. The trace viewer polls the sidecar every 2 seconds, sees the pending comment, fetches the excerpt, and renders it inline under the annotation. You never pane-switch to read the answer.

**When a conclusion is reached**, the agent calls `cxc trace annotation addressed <id> --summary "..."`. The trace viewer picks up the status flip:

- Status chip for the annotation flips to IN_PROGRESS (while the agent is working), then ADDRESSED (fix landed) or WONTFIX (agent explained why not).
- The agent's chat reply stays rendered inline, the sidecar carries only the token pointer, chat carries the body.

## Continuing the conversation from the trace viewer

The annotation detail pane has a **reply box** at the bottom for non-draft annotations. Type a response, Cmd+Enter posts it as a new comment. Use this when:

- You want the response anchored to the specific annotation record rather than scrolling through chat history.
- You're coming back later and want to see the thread persist across Claude Code sessions.
- You want the record committed to git alongside the code change (the sidecar is a tracked file).

The reply becomes part of the sidecar transcript. Future sessions see it in `cxc trace annotation show <id>`.

## Linking to a primitive from elsewhere

Click a primitive, click **⎘ copy link** in the focus toolbar, paste the markdown anywhere, a planning doc, a PR description, a Slack message, a note. The URL includes `?focus=<kind>:<args>`:

```text
atom:Hand                 → focus a specific atom
event:142                 → focus a specific trace event
range:142:158             → focus an event range
edge:Hand:Deck:draw       → focus a specific message edge
```

Opening the pasted link in a browser round-trips the focus, the trace viewer boots and auto-focuses on the referenced primitive. For atoms, this includes auto-expanding the phase graph.

The port number is embedded in the URL. If you restart `cxc trace serve` on a different port, old links break. Acceptable for v1.

## The sidecar is a persistent transcript

`<trace-base>.annotations.json` lives next to the trace file. Commit it with your code change and the review travels in git history. The agent's actions and the human's responses all land in the same JSON file, so re-reading a trace later shows the full conversation that produced the current code.

## When not to use annotations

- **For scratch thinking that doesn't need the trace.** Chat with the agent in Claude Code. The trace is the right surface when you need to point at this specific thing on screen.
- **For questions about code you aren't currently tracing.** Point the agent at the file directly, don't invent a trace to annotate.
- **For CI-driven feedback.** Annotations are a human surface. CI reports should land somewhere agents read without a human-in-the-loop.
