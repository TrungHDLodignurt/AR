---
title: AR-Measure feature-set extraction — standalone Gradle source-module architecture
date: 2026-08-25
status: decided
---

# AR-Measure module architecture — decision record

Feeds directly into `planner` for phased implementation. No open debate left except the
"Open questions" section at the bottom.

## 1. Gradle module split: 3 modules, not 1, not more

| Module | Contains | Why separate |
|---|---|---|
| `:ar-measure-common` | `LengthUnit`, `MeasurementResult` (shared result sealed type), `LabelPill` | Genuinely shared by both AR and Photo features today (`PhotoMeasureState.kt` already cross-imports `measure.LengthUnit`, `LabelPill` used by both overlay families). Zero ARCore/heavy deps — pure Kotlin + a couple Compose UI atoms. |
| `:ar-measure-ar` | Ruler, Box, Cylinder, Level, ArWarmup, SteadinessGate, MeasureHit, PoseProjector, AR-availability facade | Needs ARCore + `arsceneview` (the heavy dependency). Level has zero ARCore dependency itself but is kept here (not a 4th module) — no host will want Level alone, and it costs zero extra dependency weight once Ruler/Box/Cylinder are already pulling ARCore in. YAGNI on a 4th module. |
| `:ar-measure-photo` | Canny/Hough/Homography photo-reference feature, `CustomReferenceStore` | Has **no ARCore/SceneView dependency at all** today. A host that only wants photo-based measuring (e.g. AIP936 might want this first, without ever touching ARCore) must not be forced to pull ARCore+Filament. This is the real, load-bearing reason to split — not code size. |

Rejected: single `:ar-measure` module (simplest to wire, but forces ARCore+SceneView onto every
consumer even if they only want the photo feature — defeats the stated goal of letting a host
pick a subset). Rejected: splitting further into `:ar-measure-ruler` / `:ar-measure-shapes` /
`:ar-measure-level` — they share 90% of infra (ArWarmup, SteadinessGate, MeasureHit, PoseProjector)
and always ship together in practice; fragmenting them buys nothing and violates KISS.

Consumer wiring (AIP936 `settings.gradle.kts`): 3 `include(...)` lines, copy 3 folders. A host
that truly only wants photo-measure (skips `:ar-measure-ar` entirely) only needs
`:ar-measure-common` + `:ar-measure-photo` — still works, since neither depends on `:ar-measure-ar`.

## 2. Namespace

Root package: **`vn.apero.armeasure`** (org-based, not tied to this repo's original app or to
AIP936's `com.aihomedesign...` namespace — avoids R-class/resource and package collisions in
either direction). Gradle module `namespace =` in each `build.gradle.kts`:
- `vn.apero.armeasure.common`
- `vn.apero.armeasure.ar`
- `vn.apero.armeasure.photo`

All Compose string/drawable resources (if any get added later) live under these namespaces —
`R` classes won't collide with AIP936's `core` (`com.aihomedesign.aihomedecor...core`).

## 3. Package layout per module (domain / data / presentation)

Layer rule verified against actual current imports (see section 5 for violations found): domain
packages contain **zero** `android.*`, `com.google.ar.*`, `androidx.compose.*` imports.

### `:ar-measure-common`
```
vn.apero.armeasure.common.domain   LengthUnit.kt, MeasurementResult.kt (NEW — see §4)
vn.apero.armeasure.common.ui       LabelPill.kt
```

### `:ar-measure-ar`
```
vn.apero.armeasure.ar.domain.geometry     MeasureMath.kt, ShapeMath.kt        (pure, unchanged)
vn.apero.armeasure.ar.domain.steadiness   SteadinessGate.kt                   (pure, unchanged)
vn.apero.armeasure.ar.data.arcore         MeasureHit.kt, PoseProjector.kt      (ARCore session/frame access — infra)
vn.apero.armeasure.ar.data.warmup         ArWarmup.kt → renamed ArWarmupGate (internal object, see §4)
vn.apero.armeasure.ar.presentation.ruler       MeasureScreen.kt, MeasureState.kt, MeasureFrameLoop.kt, MeasureOverlay.kt
vn.apero.armeasure.ar.presentation.shapes      ShapeMeasureScreen.kt, ShapeMeasureState.kt, ShapeFrameLoop.kt, ShapeOverlay.kt
vn.apero.armeasure.ar.presentation.level       LevelScreen.kt
vn.apero.armeasure.ar.presentation.shared      MeasureControls.kt (MeasureTopBar/BottomBar — internal chrome, AppTabBar dropped, see §6)
vn.apero.armeasure.ar                          ArMeasureKit.kt (NEW — public facade, see §4)
```

### `:ar-measure-photo`
```
vn.apero.armeasure.photo.domain.imaging   CannyEdgeDetector.kt, HoughTransform.kt, GrayscaleImage.kt, QuadFromEdges.kt, Homography.kt, ImageFit.kt, ReferenceObject.kt   (pure, unchanged)
vn.apero.armeasure.photo.data              CustomReferenceStore.kt, ExifBitmapLoader.kt, CameraCapture.kt, AutoFitQuad.kt (moved — see §5)
vn.apero.armeasure.photo.presentation      PhotoMeasureScreen.kt, PhotoMeasureState.kt, ReferencePickerScreen.kt, NameReferenceDialog.kt, PickPhotoSheet.kt, QuadEditorCanvas.kt, DraggableHandlesOverlay.kt, MagnifierLoupe.kt, PhotoQuadCanvas.kt
```

File-to-package mapping is 1:1 rename/move, no logic rewrites except the 3 items in §5.

## 4. Public API surface

Everything not listed below becomes Kotlin `internal` — this is the actual enforcement
mechanism for "no hidden surface", not a naming convention. A host (or the future integration
skill) only ever sees:

```kotlin
// vn.apero.armeasure.common.domain
enum class LengthUnit { Metric, Imperial }

sealed interface MeasurementResult {
    data class Distance(val meters: Float, val unit: LengthUnit) : MeasurementResult
    data class Box(val lengthU: Float, val lengthV: Float, val height: Float, val unit: LengthUnit) : MeasurementResult
    data class Cylinder(val radius: Float, val height: Float, val unit: LengthUnit) : MeasurementResult
    data class Photo(val distanceMeters: Float, val unit: LengthUnit) : MeasurementResult
}

// vn.apero.armeasure.ar — facade, owns AR-availability (see §7)
object ArMeasureKit {
    fun checkAvailability(context: Context): ArAvailability   // Ready | NeedsInstall | Unsupported | Checking
    fun requestInstall(activity: Activity): Boolean            // wraps ArCoreApk.requestInstall, returns whether a redirect happened
}
enum class ArAvailability { Checking, Ready, NeedsInstall, Unsupported }

// vn.apero.armeasure.ar.presentation.* — entry composables
@Composable fun ArMeasureRulerScreen(unit: LengthUnit = LengthUnit.Metric, onResult: (MeasurementResult.Distance) -> Unit = {}, onClose: () -> Unit)
@Composable fun ArMeasureBoxScreen(unit: LengthUnit = LengthUnit.Metric, onResult: (MeasurementResult.Box) -> Unit = {}, onClose: () -> Unit)
@Composable fun ArMeasureCylinderScreen(unit: LengthUnit = LengthUnit.Metric, onResult: (MeasurementResult.Cylinder) -> Unit = {}, onClose: () -> Unit)
@Composable fun LevelScreen(onClose: () -> Unit)

// vn.apero.armeasure.photo.presentation
@Composable fun PhotoMeasureScreen(referenceStore: CustomReferenceStore, unit: LengthUnit = LengthUnit.Metric, onResult: (MeasurementResult.Photo) -> Unit = {}, onClose: () -> Unit)

// vn.apero.armeasure.photo.data
class CustomReferenceStore(context: Context)   // plain constructor injection, host instantiates once (e.g. in its own Application/Koin module) and passes in
```

`onResult`/`onClose` callbacks are the whole "config + results" story — deliberately no big
`ArMeasureConfig` object, current app doesn't need per-tool configurability beyond unit, and a
config blob nobody fills in is exactly the kind of speculative flexibility YAGNI forbids. Add
fields to the composable's parameter list when a real host need shows up.

`AppTab`/`AppTabBar` (bottom nav) is **dropped from the public surface** — that's this repo's own
nav chrome, not something AIP936 (or any host with its own nav/theme) should inherit. Host builds
its own tab/nav and calls whichever `ArMeasure*Screen` composable per destination.

## 5. Domain-purity violations found and fixes

Checked actual imports (not assumed) across all 34 files. Result: **the pure-math files were
already clean** (`ShapeMath.kt`, `MeasureMath.kt`, `SteadinessGate.kt`, `Homography.kt`,
`CannyEdgeDetector.kt`, `HoughTransform.kt`, `GrayscaleImage.kt`, `QuadFromEdges.kt`, `ImageFit.kt`,
`ReferenceObject.kt` — zero Android/ARCore/Compose imports, confirmed by grep). Only one real fix
needed:

- **`AutoFitQuad.kt`**: `autoFitQuad()` takes `android.graphics.Bitmap` directly and inlines pixel
  extraction (`extractGrayscaleWindow`) next to calls into the pure `cannyEdges`/`houghLines`/
  `quadFromLines` functions in the same file. Fix: keep `autoFitQuad()` + `extractGrayscaleWindow()`
  in `photo.data` (it's a thin Bitmap→`GrayscaleImage` adapter, correctly infra-layer), the domain
  functions it calls already live in `photo.domain.imaging` — this is a **file relocation only**,
  zero logic change.

Two files that look like violations but aren't (mislabeling risk, not a bug): `MeasureState.kt`
and `ShapeMeasureState.kt` import both `androidx.compose.runtime.*` and `com.google.ar.core.{Anchor,Session}`
in the same file. This is correct for what they actually are — Compose-observable **presentation-layer
controllers** holding a live ARCore session handle for per-frame hit-testing (performance-critical,
can't be abstracted behind a repository interface without adding an indirection layer that buys
nothing here). The fix is placement, not extraction: they belong in `ar.presentation.*`, not
`ar.domain.*`, and must never be misfiled as domain. Same reasoning for `PhotoMeasureState.kt`
(`android.graphics.Bitmap` + Compose state) → `photo.presentation`.

One required fix for module decoupling (not purity, but blocks the split): `PhotoMeasureState.kt`
currently does `import vn.quancua.artapemeasure.measure.LengthUnit` — a cross-feature import that
would make `:ar-measure-photo` depend on `:ar-measure-ar` (defeating the whole point of splitting
them). Fix: `LengthUnit` moves to `:ar-measure-common`, both modules import it from there.

## 6. Cross-cutting state — where it lives, how it avoids host collisions

- **`ArWarmup.hasAttemptedArWarmup`**: currently a `private` top-level `var` — already file-private,
  so it literally cannot name-collide with anything in a host app at the bytecode level. The real
  gap is that it's an anonymous flag with no identity, not testable/resettable, and undocumented as
  "intentionally one per process". Fix: wrap in `internal object ArWarmupGate` inside
  `ar.data.warmup`, package-scoped under `vn.apero.armeasure.ar` — `internal` visibility means it's
  invisible outside the `:ar-measure-ar` module entirely, so a host cannot see it, reference it, or
  collide with it even by accident. Add a `@VisibleForTesting fun reset()` for the module's own
  tests. No DI container needed for this — that would be over-engineering one boolean flag.
- **`CustomReferenceStore`**: SharedPreferences file name is currently the generic literal
  `"custom_reference_objects"` — low but nonzero collision risk if a host happens to use the same
  file name. Fix: prefix with the module namespace: `"vn.apero.armeasure.photo.custom_reference_objects"`.
  Otherwise unchanged — plain constructor-injected class taking `Context`, host owns the instance
  and its lifetime (typically created once, e.g. `remember { CustomReferenceStore(context) }` at
  the call site, or via whatever DI the host already has — see §8).

## 7. AR-availability check: module owns it

Decision: `ArMeasureKit.checkAvailability()`/`requestInstall()` lives in `:ar-measure-ar` and is
the one-stop call a host makes before showing any `ArMeasure*Screen`. Rejected alternative: make
the host call raw `ArCoreApk.checkAvailability()` itself (what this repo's own `MainActivity.kt`
does today, ~40 lines of state machine: `Checking`/`Ready`/`Unsupported` + install-redirect
bookkeeping). Justification: AIP936 has **zero** existing ARCore exposure — forcing it to
reimplement that boilerplate on day one directly contradicts the stated goal of "wire a couple of
composables". The module already needs this logic internally for its own screens' guard rails;
exposing it costs nothing extra and removes a whole category of integration mistakes (e.g. a host
forgetting the `userRequestedInstall` re-entry guard that this repo's `MainActivity.kt` has to
carry by hand).

## 8. DI seam: none — plain constructor/factory, confirmed against AIP936

Checked `AIP936-AIHomeDesign/core/build.gradle.kts`: AIP936 uses **Koin** (not Hilt), `:core`
exposes Compose/lifecycle/media3 as `api`. Decision unchanged from the user's steer: the module
takes **zero DI framework dependency** and exposes plain classes/composables/factory functions.
This is the correct call regardless of what a host uses (Koin today, could be anything tomorrow) —
Koin, Hilt, or manual DI can all trivially wrap a plain constructor
(`class CustomReferenceStore(context: Context)`) in one line; the reverse (this module depending on
Koin) would force that choice onto every future host. AIP936 can optionally add a one-file Koin
module (`single { CustomReferenceStore(androidContext()) }`) in its own `:app`, but that's the
host's business, not this module's.

## 9. Testability

The 5 existing JVM test files (`MeasureMathTest`, `ShapeMathTest`, `SteadinessGateTest`,
`HomographyTest`, `QuadFromEdgesTest`) map 1:1 onto files that were already pure and stay pure —
they move with their source files into `ar.domain.geometry` / `ar.domain.steadiness` /
`photo.domain.imaging` test source sets, package path changes, zero logic/assertion changes,
zero new Android test dependencies required for them to keep running as plain JVM tests.

## 10. Known gaps carried over (not blocking this design)

- Anchor-orphan-on-tab-switch: unfixed, host-app tab switching will hit the same class of issue —
  the module's `ar.presentation.*` screens should each own their ARCore cleanup on dispose
  regardless of host navigation; flag as a follow-up implementation task, not an architecture
  change.
- Wall-anchored-shape terminology mismatch: cosmetic, no design impact.

## Open questions for the user

1. `ArMeasureKit` name — fine, or prefer something else (host-facing identifier, worth getting
   right once since a skill will reference it)?
2. `MeasurementResult.Photo` currently only carries `distanceMeters` (matches what
   `PhotoMeasureState` computes today) — confirm no other photo-measure output (e.g. detected quad
   corners) needs to reach the host, or should the result carry more?
3. Confirm host apps are expected to always pass a already-`Context`-scoped `CustomReferenceStore`
   instance into `PhotoMeasureScreen` (module never constructs its own) — this was inferred from
   "no hidden global state" but wasn't explicitly stated.
