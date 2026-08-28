# Phase 03 — Scanning indicator

Priority: 3 · Status: pending

## Problem

While no plane is tracked the screen is bare: camera feed, an 8 dp dot, and a 13 sp bottom pill.
First-time users read it as broken. Screenshot: `Myapp/Screenshot_20260828_152610.png`.

ARuler shows a 162 dp Lottie dead centre plus "Planes detection…", and tracks time-to-first-plane
as a product KPI (`plane_detection_end`, `planes_detection_time_sec`).

## Decision

**Compose Canvas port** of the drawing in the published artifact, not a Lottie asset.
Rationale: no `lottie-compose` (~300 KB) in a module meant to be dropped into hosts, no asset to
source, tintable from `ArMeasureTokens`. The user reviewed the drawing and approved it.

Keep the composable signature stable — `ScanningIndicator(modifier)` — so if a designer later
delivers a real Lottie, swapping is one file. Vector-only spec is recorded in the brainstorm report.

## Files

**Create**
- `ar/presentation/camera/components/ScanningIndicator.kt`

**Modify**
- `ar/presentation/camera/ArCameraScreen.kt` — show while `cameraReady && !anyPlaneTracked`,
  and during `!isWarmedUp` in place of today's bare centred `ARToast`
- `res/values/strings.xml` + 10 locales — `armeasure_hint_scanning` = "Looking for a surface…"
  (base `strings.xml` is English-first)

## Design

162 dp box, screen centre, label 16 sp below. 3 s loop via `rememberInfiniteTransition`.

Drawn per frame:
1. Trapezoid grid quad in 2D fake perspective (TL/TR/BL/BR at 16/84/2/98 % width, 34/70 % height),
   1.6 dp white stroke.
2. Dashed interior lines, 6 across × 4 deep, `PathEffect.dashPathEffect(2.5, 3.5)`, 50 % white.
3. A rounded-rect phone, 26 × 48 dp, sweeping `sin(2πt)` across ±20 % of the box and rotating
   ±0.22 rad, with a 16 % white fill.

Respect `prefers-reduced-motion`: hold a static mid-sweep frame rather than animating.

## Steps

1. `ScanningIndicator.kt`.
2. Wire the two conditions in `ArCameraScreen`; the warm-up branch loses its bare toast.
3. Strings × 11 locales.
4. `:AR_feature:compileDebugKotlin`.

## Success criteria

- Something animated and centred within 500 ms of the first camera frame on a cold entry.
- Disappears the moment a plane is tracked, handing over to the dot field.
- Static frame under reduced-motion, no animation.

## Deferred from the teardown (still open, not this pass)

Coaching carousel + `?` button, torch toggle, blocked-action toasts, "Reconnecting…" state,
anchored first-run bubbles, plane-lock, time-to-first-plane telemetry.
