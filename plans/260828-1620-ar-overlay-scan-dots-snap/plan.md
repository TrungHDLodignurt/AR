---
status: implemented-unverified-on-device
created: 2026-08-28
branch: refactor/mvi-alignment
---

# AR overlay: scan indicator, dot field, snap-to-point

Fixes the three defects the user raised on 2026-08-28 after comparing ARuler side by side.
Scope: **`AR_feature` only. AIP936 untouched.**

## Context

- Teardown: `plans/reports/teardown-260828-1507-aruler-scan-coaching-ux.md`
- Brainstorm + decisions: `plans/reports/brainstormer-260828-1507-ar-scan-planeviz-snap.md`
- Interactive decision preview (published artifact): `9629156d-90ef-4000-ab50-ef4250259cbf`

## Locked decisions

| Topic | Decision |
|---|---|
| Scan anim | **Compose Canvas port** of the artifact's drawing. No Lottie dep, no sourced asset. Composable signature kept stable so a future Lottie is a one-file swap. |
| Plane viz | `planeRenderer = false` + own world-space dot patch, 40 × 40 cm around the reticle hit |
| Dot colour | **White**, with a dark halo offset 0.5 px — the existing `EndpointHaloColor` pattern |
| Reticle | World-space 3 cm ring on the plane → projects as a tilted ellipse. Dot kept as fallback when there is no plane normal. |
| Snap | Endpoints only, **nearest-wins**, enter 28 dp / release 45 dp (hysteresis), always on, no toggle |
| Snap on box/cylinder | **Deferred** — 4 of 5 `ShapePhase`s use the reticle as a resize handle |

## Phases

| # | Phase | Status |
|---|---|---|
| 01 | [Plane dot field + ellipse reticle](phase-01-plane-dot-field-and-ellipse-reticle.md) | pending |
| 02 | [Snap to existing endpoint](phase-02-snap-to-existing-endpoint.md) | pending |
| 03 | [Scanning indicator](phase-03-scanning-indicator.md) | pending |

## Key dependencies

- Geometry already exists — **do not rewrite**: `planeBasis(normal)`, `circleRing(center, basis, r, segments)`,
  `PlaneBasis`, and every `Vec3` operator in `ar/domain/geometry/{MeasureMath,ShapeMath}.kt`.
- `SurfaceSample.planeNormal` is already populated for `HitSource.Plane` readings.
- `PoseProjector.project(Vec3, w, h): Offset?` already runs per frame.
- `drawReticle` is shared by `MeasureOverlay` and `ShapeOverlay` — changing it changes all four tools.

## Verification

Per `ui-changes-skip-build-verification` memory: `:AR_feature:compileDebugKotlin` is the gate.
Unit tests for the two pure additions (`snapTarget`, dot-lattice culling). Device run only if asked.

## Status 2026-08-28

`:AR_feature:compileDebugKotlin` clean. `:AR_feature:testDebugUnitTest` — 200 tests, 0 failures,
2 pre-existing skips. 12 of those tests are new (`SnapTargetTest`).

**Not yet run on a device.** Everything below is verified by the simulation, the compiler and the
unit tests only — the ARCore-dependent behaviour is not:

- how often `SurfaceSample.planeNormal` is actually non-null in the field (it gates the whole dot
  field and the ellipse reticle; a depth-heavy environment shows neither)
- whether the 40 cm patch and 2.5 cm spacing read well at real measuring distances
- whether ~200 `drawCircle` pairs per frame hold 60 fps on the low-end target
- whether 28 dp / 45 dp feel right with a real hand on real glass

### Deviation from the original brief, recorded

Phase 03 was specified as a sourced Lottie asset. The user asked to reuse the drawing from the
decision-preview artifact instead, which removed both the `lottie-compose` dependency and the asset
hunt. `ScanningIndicator(label, modifier)` keeps that reversible.

### Two additions not in the phase files

- `MeasureFrameStream.commitReady` (= `liveStable || snapped`): a snap bypasses the steadiness gate,
  because a snapped position comes from an already-placed anchor, not from the live depth estimate
  the gate exists to distrust. Without it a visible lock would refuse the user's tap for ~5 frames.
- `SurfaceSample.snappedTo(target)`: moves `position` **and** `pose` together and drops `hitResult`,
  so the anchor created on tap lands on the snapped point rather than the raw ray hit. Drawing the
  snap without this would have been a lie.
