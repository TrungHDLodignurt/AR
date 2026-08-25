---
name: measure-module-conventions
description: Architectural conventions in app/src/main/java/vn/quancua/artapemeasure/measure/ — pure math files, anchor lifecycle, shared helpers
metadata:
  type: project
---

The `measure/` package (MeasureMath.kt, ShapeMath.kt, etc.) keeps a strict split: files
named `*Math.kt` are pure functions taking/returning only `Vec3`/primitives, zero ARCore or
Compose types, so they're JVM-testable with no session/engine. State holders (`MeasureState.kt`,
`ShapeMeasureState.kt`) own the one ARCore `Anchor` per committed item; multi-corner shapes
(box/cylinder) anchor only the origin and re-derive every other corner each frame from
anchor pose + stored basis/lengths, to avoid growing ARCore's anchor budget per shape.

**Why:** an undetached anchor keeps costing ARCore tracking work every frame (explicit repo
comment in `MeasureState.clear()`), so `undo()`/`clear()` must always call `anchor.detach()`
on every anchor they drop, including anchors inside intermediate (uncommitted) phase state,
not just fully-committed items.

**How to apply:** when reviewing a new measuring tool in this codebase, check (1) its pure-math
file has no ARCore/Compose imports, (2) every code path that discards an anchor (undo, clear,
phase-back-out) calls `.detach()`, (3) helpers reused across tools are widened from `private`
to `internal` rather than duplicated (seen with `resolveAt`, `drawSegment`, `drawReticle`,
`HintBanner` — confirmed pure visibility changes, no logic touched, when box/cylinder tools
were added in commit range c00d9ca..HEAD on `feature/box-cylinder-measure`).

See also [[testing-gap-pattern]].
