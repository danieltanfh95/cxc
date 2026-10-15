# Annotation flow fixture

The three `sample-01-single-atom.*` files are unchanged snapshots of
`compiler/research/sample-01-single-atom.shape.json`, `.trace.jsonl`, and
`.annotations.json`, copied on 2026-10-06. They describe the Counter atom and
five human review notes. These legacy notes omit `status`. Schema v1 treats
them as `open`. The placeholder `trace_sha256` is retained as source data.

`compiler/research/` is permanently gitignored. Keeping snapshots here lets a
clean checkout run the tests without depending on local research files.
Every test copies all three files to a UUID directory under
`System/getProperty "java.io.tmpdir"`. Only that scratch copy is modified,
and it is deleted in teardown, including on failure. To refresh the fixture,
copy the three source files here explicitly. Never write back to research/.

Schema: `.claude/skills/cxc/references/annotation-schema.md` (v1). Draft status
and the `updated_at`/`submitted_at` lifecycle timestamps extend that schema.
