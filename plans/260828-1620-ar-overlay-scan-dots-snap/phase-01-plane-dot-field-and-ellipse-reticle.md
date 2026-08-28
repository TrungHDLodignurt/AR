# Phase 01 — Plane dot field + ellipse reticle

Priority: 1 (biggest visual win) · Status: pending

## Problem

`planeRenderer = true` trails SceneView's `assets/textures/plane_renderer.png` (293 × 513 square
grid) across the **whole plane extent** — spills onto floor and wall, fights the camera image.
Screenshot: `~/Desktop/UI change/Myapp/Screenshot_20260828_152931.png`.

The reticle is an 8 dp screen-space dot in both states, so nothing says "you are on a surface"
except the dot's fill.

## Key insight

The defect is **not** dots-vs-squares. ARuler looks good because its marks are a **localised patch
with radial falloff**, and its reticle is a **world-space circle lying on the plane**. A dot texture
still painted over the whole extent fixes ~30 % of it.

`PlaneRendererV2` is a dead end: it has `gridAlpha` / `scanPlaneRadius` etc., but `ARSceneScope`
exposes no `PlaneRendererBase` instance, so its params are unreachable without reflection.

## Files

**Create**
- `ar/presentation/camera/components/PlaneDotField.kt`

**Modify**
- `ar/presentation/camera/ArCameraScreen.kt` — `planeRenderer = false`
- `ar/presentation/ruler/MeasureFrameStream.kt` — add `planeDots`, `reticleRing` to `OverlayFrame`
- `ar/presentation/ruler/MeasureFrameLoop.kt` — populate them in `buildOverlay`
- `ar/presentation/ruler/components/MeasureOverlay.kt` — draw dots; ellipse variant in `drawReticle`
- `ar/presentation/shapes/components/ShapeOverlay.kt` — same two fields on `ShapeOverlayFrame`
- `ar/presentation/shapes/ShapeFrameLoop.kt` — populate them

## Design

Dot lattice, only when `live?.planeNormal != null`:

```
basis = planeBasis(normal)                  // already exists
step  = 0.025 m, i,j in -8..8               // 40 x 40 cm patch, constant in WORLD units
mask  = hypot(i,j) <= 8                     // circular, ~200 dots
alpha = min(1, t*t*1.5) * fade, t = 1 - r/8 // radial falloff
fade  = 0.55 once pointCount > 0 else 1.0   // stop competing with the measurement
radius= max(0.45, 1.5 * (1.9 / zCam)) dp    // nearer dots read slightly larger
```

Packed into one `FloatArray` of `[x, y, alpha, radiusPx]` quadruples — one array per frame instead
of ~200 `Offset`/`Pair` objects. Cull nulls and anything more than 8 px outside the viewport.

Constant world-unit patch size is what makes it look glued: perspective shrinks it with distance.

Reticle ring: `circleRing(hit, basis, 0.03f, 32)` (already exists) → project each → stroke closed
path, dark 2.6 dp under white 1.5 dp. Keep the solid/hollow centre dot driven by `liveStable` —
**that signal is load-bearing and must not be traded for looks.**

## Steps

1. `PlaneDotField.kt`: `buildPlaneDots(...)`, `buildReticleRing(...)`, `DrawScope.drawPlaneDots(...)`.
2. Add `planeDots: FloatArray` (default empty) + `reticleRing: List<Offset>` to both overlay frames.
3. Populate in both frame loops, guarded on `planeNormal != null`.
4. `drawReticle(center, onSurface, ring)` — ellipse when `ring` is non-empty, else today's dot.
5. Draw dots first in both overlays, under every measurement graphic.
6. `planeRenderer = false` in `ArCameraScreen`.
7. `:AR_feature:compileDebugKotlin`.

## Success criteria

- No marks outside a ~40 cm patch around the reticle; nothing on walls the user is not aiming at.
- Reticle visibly tilts (ellipse) on a floor seen at a shallow angle; round when seen head-on.
- Dots fade once the first point is placed.
- No dot field at all when the reticle resolves via Depth/FeaturePoint (`planeNormal == null`) —
  correct by construction, not a gap.

## Risks

| Risk | Mitigation |
|---|---|
| `planeRenderer = false` removes Filament plane occlusion | We never had usable occlusion — the grid drew over everything. Overlay is 2D Canvas by design. Documented, accepted. |
| `project()` returns `Offset?`; nullable inline class **boxes** → ~200 boxes/frame | Consistent with existing `buildOverlay` churn. Ship, measure. First lever if jank appears is dot count, second is a non-nullable `projectInto`. |
| Dots distract while measuring | `fade = 0.55` past the first point, in from the start. |
