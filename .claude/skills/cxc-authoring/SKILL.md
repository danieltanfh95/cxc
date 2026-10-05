---
name: cxc-authoring
description: Forwarder. The authoring guidance was consolidated into the `cxc` skill bundle. Load `cxc` and route to `references/authoring.md`.
---

# cxc-authoring (forwarder)

The Corvid authoring guidance moved to the single `cxc` skill bundle on 2026-10-05 as part of the trace-annotation review loop landing.

Load the `cxc` skill instead. Its routing section points at `references/authoring.md` for authoring work.

This forwarder stays in place for one release cycle so existing agent invocations of `/cxc-authoring` keep working. The content lives at [.claude/skills/cxc/references/authoring.md](../cxc/references/authoring.md).
