---
name: cxc-cleanup-audit
description: Forwarder. The cleanup-audit catalog was consolidated into the `cxc` skill bundle. Load `cxc` and route to `references/cleanup-audit.md`.
---

# cxc-cleanup-audit (forwarder)

The Corvid anti-pattern catalog moved to the single `cxc` skill bundle on 2026-10-05 as part of the trace-annotation review loop landing.

Load the `cxc` skill instead. Its routing section points at `references/cleanup-audit.md` for audit work.

This forwarder stays in place for one release cycle so existing agent invocations of `/cxc-cleanup-audit` keep working. The content lives at [.claude/skills/cxc/references/cleanup-audit.md](../cxc/references/cleanup-audit.md).
