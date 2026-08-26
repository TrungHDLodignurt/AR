---
name: design-file-source-of-truth
description: The AIP936 wireframe .pen on disk is stale; only the pencil MCP tools read the authoritative live document
metadata:
  type: reference
---

The AR-measure wireframes live at `/Users/admin/Downloads/AIP936-wireframes/aip936-home-design.pen`.

**Read it only through the pencil MCP tools** (`mcp__pencil__execute` with `filePath`; learn the API
via `mcp__pencil__read_skill()` → `execute.md` / `pen-schema.md`). Never `Read`/`Grep` the `.pen`, and
never the `exports/*.html`.

**Why:** the on-disk copy and the HTML export lag the designer's live document by several screens —
as of 2026-08-26 the disk copy had frames at 360×823 and no SCR-21…24, while the live document had
all frames at 354×799 plus four more screens. Anyone reviewing the file directly reviews a design that
no longer exists, and at least one checked-in review report drew wrong conclusions that way (see
[[ui-review-report-has-two-disproven-findings]]).

**How to apply:** when a task references this design, read via MCP or delegate to a subagent that has
the MCP tools. If neither is available, say so and stop rather than falling back to `Read`.
