# Phase 02 — Snap to existing endpoint

Priority: 2 · Status: pending · Depends on: nothing in phase 01

## Problem

No snap. To restart at an existing point the user must aim by hand, misses by a few px, and gets a
silently wrong measurement.

## Reference behaviour (ARuler "Sticking", decoded)

`ARulerActivity.I0()`, gated on `jx.c` (`prefs "auto sticking"`, default ON):
`f08.z0 = 45 × density` → 45 dp screen-space radius, compared against each projected visible
vertex, **first-match-wins** (early `return`, iteration order = creation order).

We deliberately diverge on two points:
- **nearest-wins**, not first-match — theirs picks the wrong point when two are in range.
- **28 dp enter / 45 dp release**, not a single 45 dp threshold.

## Why hysteresis is not optional

A single threshold means parking at exactly the boundary toggles snap **every frame** — reticle
flickers, haptics fire continuously. Real hand tremor is ±1.2 dp at 60 fps, so this is the normal
case, not an edge case. Demonstrated live in the published artifact's snap panel (transition
counter). Cost: one extra boolean's worth of state.

## Files

**Modify**
- `ar/presentation/ruler/MeasureFrameStream.kt` — `snappedIndex`
- `ar/presentation/ruler/MeasureFrameLoop.kt` — resolve snap, override the live sample
- `ar/presentation/ruler/components/MeasureOverlay.kt` — snapped reticle ring
- `ar/presentation/camera/components/ArCameraHints.kt` — snapped hint line
- `res/values/strings.xml` + 10 locale files — `armeasure_hint_snapped`

**Create**
- `ar/domain/geometry/SnapTarget.kt` — the pure decision
- `src/test/.../SnapTargetTest.kt`

## Design

Pure function, so the one piece of logic worth testing is testable:

```kotlin
internal fun snapTarget(
    projected: List<Offset?>,   // existing endpoints, already projected
    reticle: Offset,
    currentlySnapped: Int?,
    enterPx: Float,             // 28.dp
    releasePx: Float,           // 45.dp
    excluded: Set<Int>,
): Int?
```

- nearest-wins among candidates
- `currentlySnapped == null` → snap only if `d < enterPx`
- already snapped → release only if `d > releasePx`, else re-target nearest
- `excluded` covers the point being dragged and, in the unchained tool, the open segment's own start

On a snap the live sample's **position is replaced by that endpoint's exact world position**, so the
committed value is bit-identical to the original — that is the whole point of the feature.

Feedback: double-ring reticle; haptic tick **only on target change**, never per frame;
hint `"Snapped to point %1$d"`.

Distance tools only. Box/cylinder deferred — see plan.md.

## Steps

1. `SnapTarget.kt` + unit tests (enter/release/nearest/exclusion/empty).
2. `snappedIndex` on `MeasureFrameStream` + a `noteSnap` that reports whether the target changed.
3. Call from `onMeasureFrame` after `resolveAt`, before `noteLiveSample`; override sample position.
4. Haptic on change only, from the overlay's composable scope.
5. Snapped reticle in `MeasureOverlay`; hint in `distanceHint` above the existing chain.
6. Strings × 11 locales.
7. `:AR_feature:compileDebugKotlin` + `:AR_feature:testDebugUnitTest`.

## Success criteria

- Aiming within 28 dp of an endpoint snaps; committed point is bit-identical to the original.
- Parked at the boundary with tremor: **no on/off flicker** (this validates the hysteresis).
- One haptic tick per target change, not per frame.
- Never snaps to the point being dragged.
