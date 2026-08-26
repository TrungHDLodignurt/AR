# Phase 03 — Undo **and redo** in all three state holders

## Context Links

- [Plan overview](plan.md) · depends on [phase 01](phase-01-merge-into-single-ar-feature-module.md)
- Design: `jwRjx` SCR-23 TopNav `h63Wd` UndoForwardGroup — `undo-2` 24×24 + `redo-2` 24×24, gap 40
- Anchor-lifetime constraints: [`report-260825-1703-session-handoff-box-cylinder-measure.md`](../reports/report-260825-1703-session-handoff-box-cylinder-measure.md) §4, §6

## Overview

- **Priority:** P1
- **Status:** completed (AR on-device placing/drawing verification blocked — needs a human aiming
  at a real textured surface, see Todo List)
- **Effort:** 2.5h

Redo does not exist anywhere in the codebase today. This phase adds it to all three state holders
behind one shared, pure, unit-tested stack.

## Key Insights

1. **The brief's premise is half right.** `MeasureState.undo()` (line 187) and
   `ShapeMeasureState.undo()` (line 160) exist. **`PhotoMeasureState` has no `undo()` at all** —
   only `resetLine()`. So the photo path needs undo *and* redo, not just redo.
2. **`undo()` currently calls `anchor.detach()` — and a detached ARCore `Anchor` cannot be
   revived.** This is the whole design problem of redo here. Two options:
   - *Re-anchor from a stored `Pose`.* No extra anchors, but the recreated anchor is a **different**
     anchor that ARCore drifts independently, so a redone measurement can read a different number
     than it did before the undo. For a measuring tool that is a correctness defect.
   - *Defer the detach.* Keep the anchor attached while its entry sits on the redo stack; detach
     only when the entry is evicted. Restores the **exact** original anchor, so the number is
     identical. Costs up to `MaxUndoDepth` extra attached anchors.
   **Choose deferred detach.** The existing KDoc warns that undetached anchors cost ARCore tracking
   work per frame — true, but a *bounded* 20 is a known small cost, and standard redo semantics
   clear the redo stack on the next commit, so it is usually empty. Correct numbers beat a
   marginally cheaper frame.
3. **Therefore eviction must be observable.** The stack has to hand evicted entries back to the
   caller so anchors get detached exactly once — on new-commit-clears-redo, on `clear()`, on
   overflow past the cap, and on screen dispose. A leak here is invisible until frame rate drops.
4. **The stack itself is pure and therefore JVM-testable; the state holders are not** (they need a
   live `Session`/`Anchor`). So extract one generic `UndoRedoStack<T>` and put the 11 tests there.
   All three holders share it — DRY, and it is the only part with non-obvious logic.
5. **`ShapePhase` holds live `Anchor` references inside its data classes**, so a shape's undo entry
   is the whole `ShapePhase`/`MeasuredShape` value, not a pose. Deferred detach makes that work
   unchanged — the phase object stays valid because its anchor is still attached.
6. **`ShapeMeasureState.undo()` already restores `edgeU` exactly** when stepping back from
   `SizingHeight` (line 164, with a comment saying undo-then-redo must not reset it). That comment
   was written in anticipation of this phase — honour it.

## Requirements

**Functional**
- `canUndo` / `canRedo`, `undo()` / `redo()` on `MeasureState`, `ShapeMeasureState`,
  `PhotoMeasureState`.
- Redo restores exactly what undo removed — same anchors, same numbers.
- A new commit after an undo clears the redo stack and releases its resources.
- `clear()` empties both stacks and detaches every anchor it was holding.
- Depth cap: 20. Overflow evicts the oldest and detaches its anchor.
- Screen dispose detaches every anchor held by either stack.

**Non-functional**
- `UndoRedoStack` is pure Kotlin — no Android, no ARCore, no Compose imports. It must compile into
  the JVM test source set.
- `canUndo`/`canRedo` are Compose-observable so the toolbar buttons enable/disable without polling.
- No allocation on the per-frame path — the stack is only touched on user gestures.

## Architecture

```kotlin
// common/domain/UndoRedoStack.kt   (internal, pure)
internal class UndoRedoStack<T>(
    private val maxDepth: Int = 20,
    private val onEvict: (T) -> Unit = {},   // release resources; called exactly once per entry
) {
    val canUndo: Boolean
    val canRedo: Boolean
    fun push(entry: T)        // clears the redo stack, evicting its entries
    fun undo(): T?           // moves the newest undo entry onto the redo stack, returns it
    fun redo(): T?           // moves it back, returns it
    fun clear()              // evicts everything on both stacks
}
```

`canUndo`/`canRedo` are backed by `mutableStateOf` so Compose reads them; the two `ArrayDeque`s are
plain fields.

Per-holder entry types and eviction actions:

| Holder | Entry type | `onEvict` |
|---|---|---|
| `MeasureState` | `MeasuredPoint` (anchor + `HitSource`) | `entry.anchor.detach()` |
| `ShapeMeasureState` | `sealed ShapeStep { PhaseStep(ShapePhase); ShapeStep(MeasuredShape) }` | detach the entry's origin anchor **only if no live phase/shape still references it** |
| `PhotoMeasureState` | `PhotoSnapshot(quad, homography, line, lineColor)` | no-op (pure values) |

**`ShapeMeasureState` needs the ownership check.** Stepping `SizingHeight → SizingEdgeV` reuses the
*same* origin anchor in the new phase, so evicting the old entry must not detach it. Rule: detach
only when the anchor is not the `originAnchor` of the current `phase` and not held by any entry in
`shapes` or either stack. Write it as one small `private fun isAnchorOrphaned(Anchor): Boolean` —
this is the single most bug-prone line in the phase.

`PhotoMeasureState` gets a snapshot-based undo: push a `PhotoSnapshot` **before** each mutating
gesture completes (quad corner drag end, line endpoint drag end, `confirmReference`, colour change),
and `undo()` applies the previous snapshot. Simpler and more honest than per-field undo, and it
covers everything the SCR-23 toolbar can change.

## Related Code Files

**Create**
- `AR_feature/src/main/java/vn/apero/armeasure/common/domain/UndoRedoStack.kt`
- `AR_feature/src/test/java/vn/apero/armeasure/common/domain/UndoRedoStackTest.kt`

**Modify**
- `.../ar/presentation/ruler/MeasureState.kt` — `undo()` stops detaching; add `redo()`, `canRedo`;
  `commitLivePoint`/`commitDrag` push and thus clear redo; `clear()` clears the stack; add
  `releaseAll()` for dispose
- `.../ar/presentation/shapes/ShapeMeasureState.kt` — same, plus `ShapeStep` and
  `isAnchorOrphaned`
- `.../photo/presentation/PhotoMeasureState.kt` — add `PhotoSnapshot`, `undo()`, `redo()`,
  `canUndo`, `canRedo`; push in `moveQuadCorner` (on gesture end, not per move),
  `moveLineEndpoint` (on gesture end), `confirmReference`, `loadPhoto`
- `.../ar/presentation/shared/MeasureControls.kt` — `MeasureTopBar` gains `canRedo` + `onRedo`
- `.../ar/presentation/ruler/MeasureScreen.kt`, `.../shapes/ShapeMeasureScreen.kt` — pass
  `canRedo`/`onRedo`; add a `DisposableEffect { onDispose { state.releaseAll() } }`
- `.../photo/presentation/PhotoMeasureScreen.kt` — undo/redo affordances (final placement lands in
  phase 08's SCR-23 toolbar)
- `AR_feature/src/main/res/values/strings.xml` — `armeasure_action_undo`, `armeasure_action_redo`
  (content descriptions)

## Implementation Steps

1. Write `UndoRedoStack<T>` and its 11 tests first — it is pure, so it can be driven to green before
   anything Android compiles.
2. `MeasureState`: hold `UndoRedoStack<MeasuredPoint>(onEvict = { it.anchor.detach() })`. `undo()`
   removes from `points` and pushes onto the stack **without detaching**. `redo()` pops and appends
   back to `points`, then `worldPoints = points.map { … }` and restores `lastSource`.
   `commitLivePoint` and `commitDrag` call `stack.clearRedo()` semantics via `push`-on-nothing — use
   an explicit `stack.dropRedo()` helper rather than pushing a fake entry.
3. `ShapeMeasureState`: add `ShapeStep`, wire the stack, and write `isAnchorOrphaned`. Walk every
   existing `undo()` branch and make sure the anchor it used to detach is now pushed instead.
4. `PhotoMeasureState`: add `PhotoSnapshot` + the stack, push at the four mutation sites, implement
   `undo()`/`redo()` as snapshot application.
5. Extend `MeasureTopBar` with redo. Keep the existing `↩` glyph choice and add `↪` — the KDoc's
   reason for glyphs over Material icons (one glyph is not worth the extended icon dependency)
   applies equally.
6. Add the `DisposableEffect` release hooks in all three screens.
7. Gate.

## Todo List

- [x] `UndoRedoStack<T>` (pure, `maxDepth = 20`, `onEvict`)
- [x] 11 `UndoRedoStackTest` tests green
- [x] `MeasureState`: deferred detach, `redo()`, `canRedo`, `releaseAll()`
- [x] `ShapeMeasureState`: `ShapeStep`, `isAnchorOrphaned`, `redo()`, `releaseAll()`
- [x] `PhotoMeasureState`: `PhotoSnapshot`, `undo()`, `redo()`, 4 push sites
- [x] `MeasureTopBar` gains `canRedo` + `onRedo`
- [x] `DisposableEffect` release hooks in all three screens
- [x] 2 new content-description strings
- [x] Gate: `compileDebugKotlin testDebugUnitTest assembleDebug assembleRelease` — 86 tests green
- [ ] **BLOCKED** On-device (human): place 3 AR points → undo ×3 → redo ×3, confirm the **same
      numbers** come back; place a 4th point mid-way and confirm redo is then disabled — needs a
      human aiming the phone at a real textured surface, not scriptable
- [ ] **BLOCKED** On-device (human): same for a Box (4 taps) and a Cylinder (3 taps) — same reason
- [x] On-device: photo flow — dragged a quad corner and a line endpoint, undo, redo; confirmed via
      `uiautomator` bounds that both restore to the *exact* pre-drag pixel position, not merely a
      similar one

## Success Criteria

**86 tests pass** (75 + 11). The 11 `UndoRedoStackTest` tests:

1. a fresh stack reports `canUndo == false` and `canRedo == false`
2. after one `push`, `canUndo == true`, `canRedo == false`
3. `undo()` returns the pushed entry and flips `canRedo` to true
4. `redo()` returns that entry again and flips `canUndo` back to true
5. `push` after an `undo` clears the redo stack **and** calls `onEvict` for each discarded entry
6. `undo()` on an empty stack returns `null` and does not throw
7. `redo()` with nothing undone returns `null` and does not throw
8. pushing past `maxDepth` evicts the oldest entry and calls `onEvict` for it exactly once
9. `clear()` empties both stacks and calls `onEvict` once for every entry it held
10. three pushes then three undos then three redos restores the original order
11. `onEvict` is never called twice for the same entry across any sequence of the above

Plus, on device: redo restores the **identical** measured value (not merely a similar one) — this is
the acceptance test for the deferred-detach decision. And `logcat` shows no ARCore anchor-budget
warnings after 30 undo/redo cycles.

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| `ShapeMeasureState` detaches an anchor still referenced by the live phase → crash or vanishing shape | **high** | `isAnchorOrphaned` guard + the on-device box/cylinder undo/redo check is a named gate item |
| Anchor leak: entries evicted without `onEvict` firing | medium | test 11 asserts exactly-once; `releaseAll()` on dispose |
| 20 extra attached anchors degrade tracking | low | cap is 20 and the redo stack is empty in normal use; watch for ARCore anchor warnings in the on-device gate |
| Photo snapshot pushed per drag *frame* instead of per gesture → 200-entry stack of noise | medium | push on `onDragEnd` only, never in `onDrag` |
| `canUndo`/`canRedo` not Compose-observable → buttons stay stale | medium | back them with `mutableStateOf`, not computed from a plain `ArrayDeque.size` |
| Redo restores a *pose* rather than the anchor and the number shifts | — | avoided by design; do not "optimise" to pose storage later without re-running the on-device identical-number check |

## Security Considerations

- No new permissions, dependencies, storage or network.
- `PhotoSnapshot` holds `Offset`/`Homography` values, never the `Bitmap` — snapshotting bitmaps
  would multiply memory by the stack depth and could OOM on a large photo. The bitmap is owned once
  by `photo`.
- Unbounded stacks are a memory-exhaustion path; the `maxDepth` cap is the mitigation and must not
  be removed.

## Next Steps

- Phase 06 renders undo/redo in the design's AR TopBar; phase 08 renders them in SCR-23's
  `UndoForwardGroup`.
- Independent of phase 02 — safe to run in either order or in parallel.
