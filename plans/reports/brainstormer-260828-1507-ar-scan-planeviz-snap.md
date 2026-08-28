# Brainstorm — 3 AR UI/UX fixes: scan anim, plane viz, snap-to-point

Scope: `AR_feature` module only. **AIP936 untouched.**
Companion doc: `teardown-260828-1507-aruler-scan-coaching-ux.md` (ARuler v3.3.5 teardown).
Reference images: `~/Desktop/UI change/{Aruler,Myapp}` (read, not copied into repo).

---

## 1. Problem statement

Three defects, all confirmed against side-by-side screenshots.

| # | Defect | Evidence |
|---|---|---|
| **A** | While no plane is detected our screen is bare — camera feed + a 8dp dot + a 13sp bottom pill. Reads as broken. | `Myapp/Screenshot_…152610.png` |
| **B** | After detection, SceneView's `planeRenderer = true` trails `assets/textures/plane_renderer.png` (293×513 square grid) over the **whole plane extent** — spills across floor AND wall, fights the camera image. | `Myapp/Screenshot_…152931.png` |
| **C** | No snap. To restart at an existing point the user must aim by hand → easy to miss by a few px, silently producing a wrong measurement. | user report |

Root cause of B is **not** dots-vs-squares. ARuler's plane viz looks good because it is a **localised patch around the reticle with radial falloff**, not because the marks are round. Copying a dot texture while still painting the whole extent would fix ~30 % of the problem.

---

## 2. What ARuler actually does (decoded, evidence in teardown §3)

**Scan state** — `searchIndicator`: Lottie `ar_search.json` at 162dp, dead centre, + "Planes detection…" 16sp below. Confirmed live at `[297,835][783,1321]` (486px = 162dp @3x). Their JSON is a **100-frame PNG image sequence wrapped in Lottie → 827 KB**; a lazy export, not a model to copy.

**Post-detection** — world-space dot field on the plane, radial density/alpha falloff, plus a reticle that is a **world-space circle lying on the plane** (projects to an ellipse) with a centre dot. That flat-ellipse reticle is what sells "I am on the surface".

**Snap** — feature is named **"Sticking"** (`ARulerActivity.I0()`, gated on `jx.c`):
- `f08.z0 = 45 × density` → **45dp screen-space radius**, from `ARBaseActivity` line 458.
- Compares 2D distance between reticle centre (`f08.X/Y` = screen centre) and each **projected, visible vertex**.
- **First-match-wins** (early `return`), iteration order = object creation order → wrong point when two candidates are both inside the radius. A shortcut, not a design.
- Only vertices on the currently-selected plane, from objects passing `!Z()` (inferred: not-yet-finished).
- Runs once per frame. `f08.k0(vertex)` sets a static snap target that the renderer and the commit both read.
- `f08.B0 = z0 × 0.1` = 4.5dp — separate, tighter threshold for cross-object co-vertex linking.
- **User-toggleable, default ON** (`prefs "auto sticking"`), with `sticking_btn` on the AR screen and `switch_sticking` in settings.

---

## 3. Decisions taken

All four confirmed by the user (2026-08-28).

| Q | Chosen | Rejected, and why |
|---|---|---|
| Scan anim asset | **Lottie vector JSON**, user sources it | WebP sprite (still needs a decoder dep, raster, un-tintable); hand-drawn Canvas (lower fidelity than a designer's) |
| Plane viz | **`planeRenderer = false` + own Canvas dot field** | `PlaneRendererV2` (params unreachable — see §4); texture override (asset-merge fragility, still whole-extent) |
| Snap scope | **Endpoints only**, nearest-wins | +midpoints (doubles candidates → mis-snaps); +along-segment (reticle "sticks" when merely crossing a line) |
| Snap escape | **Always-on, 28dp, no toggle** | Toggle (top bar already has 5 controls; 99 % never touch it) |

### Assumption flagged on the snap-escape decision

Always-on at a single 28dp threshold **flickers**: parked at exactly 28dp, snap toggles on/off every frame. This was named as option B's selling point, and it is a real defect of A rather than a separate feature. **Proceeding with minimal hysteresis folded into A** — enter at 28dp, release at 45dp, one extra boolean field. Still zero settings UI, still no toggle, so the substance of the decision (no configuration surface, narrow radius) is intact. Veto this and A ships with the flicker.

---

## 4. Feasibility — verified against source, not assumed

**`PlaneRendererV2` is a dead end for us.** It genuinely exposes the parameters we would want — `gridTint`, `gridAlpha` (default 0.85), `surfaceAlpha` (0.1), and even `scanProgress` / `scanPlaneRadius` with `SCAN_IN_DURATION_MS`, i.e. a built-in radial scan reveal (`PlaneVisualizerV2.refreshScanRadius()`). But `ARSceneScope` (javap, arsceneview 4.31.0) exposes **no accessor for the `PlaneRendererBase` instance**; the composable only takes `planeRendererVersion: PlaneRendererBase.Version`. So V1↔V2 is switchable, its material params are not reachable without reflection. Recorded here so nobody re-derives it.

**Everything option A needs already exists:**
- `SurfaceSample.planeNormal: Vec3?` — already populated for `HitSource.Plane` readings (added for box/cylinder). Build two orthonormal tangents off it (Gram-Schmidt against any non-parallel axis) → the dot lattice.
- `PoseProjector.project(Vec3, w, h): Offset?` — already called per point per frame.
- `frames.live` carries the sample; `frames.worldPoints` is already the projected endpoint list snap needs.

**Naturally correct behaviour, no extra code:** `planeNormal` is null for Depth/FeaturePoint hits → no dot field when there is no plane. That is the honest signal, not a gap.

### Perf note (do not pre-optimise)

`project()` returns `Offset?`. `Offset` is an inline value class over a `Long`, so the **nullable return boxes** — ~225 boxes/frame ≈ 13.5k short-lived allocs/s. Not a new class of problem: `buildOverlay` already allocates an `OverlayFrame`, two lists and N `Segment2D` per frame, and Compose allocates far more. Ship it, measure, and if jank appears the first lever is dot count (not a non-nullable `projectInto` overload — that is the second).

---

## 5. Recommended solution

### P-A · Scan-state indicator

New `ar/presentation/camera/components/ScanningIndicator.kt`. Shown when `cameraReady && !anyPlaneTracked`, and during `!isWarmedUp` in place of today's bare centred toast. Adds `lottie-compose` (~300 KB).

**Asset spec — hand this to the designer / use when sourcing:**

```
format   : Lottie JSON (bodymovin)
canvas   : 512 × 512 px, square
fps      : 30 or 60
length   : ≤ 3 s, seamless loop
colour   : white #FFFFFF only (mono) — tinted at runtime from ArMeasureTokens
bg       : transparent
weight   : ≤ 60 KB
layers   : vector shapes ONLY
           ✗ no image/PNG layers   ✗ no complex masks/mattes   ✗ no expressions
content  : a phone sweeping over a perspective grid plane
render   : 162 dp, screen centre, label below
```

Rendered at 162dp centred; label `armeasure_hint_scanning` "Looking for a surface…" (new string, base EN + 11 locales) 16sp below.
**Do not use** ARuler's 100-frame-PNG approach — 827 KB for what a vector does in 40.

### P-B · Canvas dot field

`planeRenderer = false` in `ArCameraScreen`'s `ARSceneView`. New `ar/presentation/camera/components/PlaneDotField.kt`, drawn under the measurement graphics in the existing overlay Canvas.

Per frame, only when `frames.live?.planeNormal != null`:
1. `hit = frames.live.position`, `n = planeNormal`; derive orthonormal `u`, `v` from `n`.
2. Lattice `w = hit + i·step·u + j·step·v` for `i,j ∈ -8..8`, `step = 2.5 cm` → 40 × 40 cm patch, **constant in world units** so perspective shrinks it with distance (what makes it look glued).
3. Circular mask on `√(i²+j²) ≤ 8` (~200 dots); radial alpha `1 → 0` toward the rim.
4. `project()` each; drop nulls and anything outside the viewport.
5. One `drawPoints(PointMode.Points)` call.

**Reticle change (the half that actually sells it):** replace the screen-space 8dp dot with a **world-space circle on the plane** — 3 cm radius, sampled at ~24 points around `hit` in the `u`/`v` basis, projected, stroked as a closed path. Projects to an ellipse that tilts with the surface. Keep the existing on/off-surface distinction (solid centre dot when `liveStable`, hollow when not) — that signal is load-bearing and must not be traded for looks.

Optional, defer: cull dots outside the plane polygon. `Plane.isPoseInPolygon` is 200 native calls/frame; a plane-local `extentX/extentZ` rect test is the cheap version. ARuler's dots spill slightly too — YAGNI until someone complains.

### P-C · Snap to existing endpoint

In `MeasureFrameLoop.onMeasureFrame`, after `resolveAt` and before `noteLiveSample`:

```
candidates = frames.worldPoints projected (already computed downstream — hoist)
nearest    = argmin ‖p - centre‖ over candidates
enter  if  d < 28.dp        (SnapEnterRadius)
release if d > 45.dp        (SnapReleaseRadius — hysteresis, §3)
on snap: live sample position := that point's world position
```

- New `MeasureFrameStream.snappedIndex: Int?` (Compose state, same as siblings).
- Nearest-wins, **not** ARuler's first-match.
- Never snap to the point currently being dragged, nor — in the unchained tool — to the open segment's own start.
- Feedback: reticle grows to a double ring; **haptic tick fired only on target change**, never per frame; hint line `armeasure_hint_snapped` "Dính vào điểm %1$d".
- Applies to the two distance tools. Shape tools (box/cylinder) out of scope for this pass — their commits are phase-driven, snapping mid-extrusion needs its own thinking.

---

## 6. Risks

| Risk | Mitigation |
|---|---|
| `planeRenderer = false` also removes Filament's plane **occlusion** — dots/lines will no longer be hidden by real geometry | We never had usable occlusion (grid drew over everything anyway). Overlay is 2D Canvas by design; accepted, documented. |
| Dot field distracts once measuring starts | Fade dots to ~30 % alpha once `pointCount > 0`. Cheap; include from the start. |
| Snap makes two deliberately-close points impossible | 28dp ≈ 4.6 mm on a Pixel 6; hysteresis release at 45dp. If it still bites, the toggle (rejected option C) is the fallback — do not add it pre-emptively. |
| `lottie-compose` size on a module meant to be dropped into hosts | ~300 KB. If a host objects, `ScanningIndicator` degrades to the hand-drawn Canvas variant behind the same composable signature. |
| Lottie asset arrives as image-sequence anyway | Spec above says vector-only; reject and re-request rather than shipping 800 KB. |

---

## 7. Success criteria

1. Cold entry with no plane in view: something animated and centred within 500 ms of first camera frame. No bare screen.
2. Plane detected: no marks outside a ~40 cm patch around the reticle; nothing painted on walls the user is not aiming at.
3. Reticle visibly tilts with the surface (ellipse, not circle) on a floor viewed at a shallow angle.
4. Aiming within 28dp of an existing endpoint snaps, fires one haptic tick, and the committed point is bit-identical to the original endpoint's world position.
5. Parked at the snap boundary: no on/off flicker (validates the hysteresis).
6. No new frame drops on a Pixel 6 vs. the current build.

---

## 8. Out of scope this pass

Coaching carousel + `?` button (P2 in the teardown), torch (P3), blocked-action toasts (P4), "Reconnecting…" (P5), anchored first-run bubbles (P6), plane-lock (P8). All still stand; this pass is the three defects the user named.

## 9. Open questions

1. ~~Scan label copy~~ — settled: base `strings.xml` is English-first, so "Looking for a surface…" plus the 11 locale files. No question.
2. Dot colour: white like ARuler, or `ArMeasureTokens.Signature`? White survives more backgrounds; Signature is on-brand.
3. Shape tools (box/cylinder): confirm snap is genuinely deferred, or is snapping a box corner to an existing point wanted in the same pass?
