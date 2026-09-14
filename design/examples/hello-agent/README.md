# hello-agent — minimum viable agent harness

**Purpose.** Demonstrate what an LLM agent looks like in cxc. Used
as a forcing function for Layer 7 (effects + plugins)
— every L7 landing (F74–F80) is checked against "does this let
hello-agent.cxc parse and lower?"

**Not compilable today** — see [STATUS.md](STATUS.md) for the
blocking landings.

## Data shapes — what moves through the system

Contract at every seam is JSON (the LLM API's native surface).

| Shape | Producer | Consumer | Contents |
|---|---|---|---|
| **Task** | user (CLI arg) | Agent atom | `String!` — the user's prompt |
| **Message** | Agent / Claude / tool | context list | `{role: Role, content: String \| List(ContentBlock)}` |
| **Role** | enum | Message.role | `system` \| `user` \| `assistant` \| `tool` |
| **ChatResponse** | Claude plugin | Agent atom | `{stop_reason: StopReason, text: String, tool_calls: List(ToolCall)}` |
| **StopReason** | enum | Agent transition guard | `end` \| `tool_use` \| `max_tokens` |
| **ToolCall** | Claude plugin | AgentTools | `{id: String, name: String, args: Json}` |
| **ToolResult** | AgentTools | context list | `{id: String, output: String}` |

## Data flow — layer by layer

```
CLI arg  ──►  Agent.user_task (String!)
                 │
                 └──►  Message.user  ──►  ctx: List(Message)
                                                │
                                                ▼
                                      |> claude.chat(messages: ctx)
                                                │
                                    HTTP POST ──┴──► Anthropic Messages API
                                                │
                                    HTTP resp   ──► ChatResponse
                                                │
                        ┌───────────────────────┴─────────────────────┐
                        │                                             │
        stop_reason == tool_use                        stop_reason == end
                        │                                             │
              ctx.append(response)                            ctx.append(response)
                        │                                             │
      for tc in tool_calls:                             console.write(response.text)
        result = tools.dispatch(tc)                             │
        ctx.append(tool_result_msg)                           DONE
                        │
                        └── loop: |> claude.chat(messages: ctx)
```

## Custom pledge / capability composition

The example demonstrates **AgentTools** — a composite capability
atom that bundles filesystem + subprocess + env access into one
`use` slot. This is what a real agent needs (its tool set), and
it shows off L7's compositional discipline:

- App author writes `use tools: AgentTools` — one line.
- The Agent's transitively-required OS primitives are computed at
  build time: `{os.net.tcp-client, os.net.dns, os.fs.read, os.fs.write,
  os.subprocess.spawn, os.env.read, os.stdio}`.
- Emitted `<binary>.cxc-caps` manifest lists them.
- On Darwin, drives entitlement generation.
- On OpenBSD, drives `pledge()` string.

Contrast: if app source had to declare each OS primitive
individually, that'd be 7 lines of noise. The composite capability
is the abstraction; the manifest is the audit.

## Spec sections exercised

- **§3.3 (atoms + transitions)** — Agent's `planning → planning →
  done` state machine
- **§3.4 (records)** — Message, ChatResponse, ToolCall
- **§3.5 (enums / tagged variants)** — Role, StopReason
- **§3.10.9 (exceptions + `or T():` recovery)** — fallible tool operations
- **§4.16 (aliases, collection literals)** — `[]`, `{}` in
  Message construction
- **§1.11 (`use` capabilities)** — capability composition via
  `AgentTools`
- **Layer 6 §Plugin** — nested `foreign "..."` blocks inside
  `def plugin:` bodies in the plugin-provided Claude / Subprocess
  implementations (§7.2.1 plugin form, R7 2026-08-31; sole FFI form
  post-R11 plugin-only wave 2026-09-01)
- **Layer 7 (proposal)** — OS-primitive vocabulary, capability
  manifest emission, plugin ABI

## Not shown (deferred to future examples)

- Streaming (SSE) — first draft is single-shot
- Session persistence across CLI invocations — single-turn
- Multiple concurrent agents — single Agent instance
- Error recovery (`or T():` recovery arms) beyond max_steps guard
- Rate limiting / retry logic on LLM API failures

Each of these adds real code; the first draft's goal is the
smallest thing that reads as "yes, this is an agent."
