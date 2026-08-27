---
name: ui-review-report-has-two-disproven-findings
description: Two findings in ui-ux-designer-260826-1100-ar-measure-wireframe-review.md are disproven and must not be acted on
metadata:
  type: project
---

`plans/reports/ui-ux-designer-260826-1100-ar-measure-wireframe-review.md` is still valid and
actionable **except for two findings, which are disproven and must not be carried into any plan**:

1. "The reference-object grid (`SvcdA`) has a clipped third row" — computed against a stale on-disk
   copy. In the live document nothing is clipped; the grid ends at y 723 of 799.
2. "Box and Cylinder are absent from the MeasureModeSheet" — the sheet (`ebVJf`) has exactly three
   cards: Distance / Box / Cylinder. The labels the reviewer saw were illustrative.

Everything else in that report holds: the 7 contrast failures (worst: the "Lưu" save button at
2.78:1), the ~20 sub-48dp touch targets, the missing photo-pick screen, the absent AR terminal state,
and the Vietnamese/English mix.

**Why:** the review was produced without the pencil MCP tools, from the stale file — see
[[design-file-source-of-truth]].

**How to apply:** cite the report freely, but strike those two items. The report file itself has not
been corrected, so a future reader will hit them again.
