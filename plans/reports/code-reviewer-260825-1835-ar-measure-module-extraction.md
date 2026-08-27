# Code review — AR-Measure module extraction (27eaa90..HEAD, b1dd385)

## Scope
- Commits: afab38d, a8c8777, 9377e3d (docs), a94af63, 61160dc, b1dd385 (6 phase commits, phases 01-05)
- 69 files changed, +1436/-467, mostly renames (`git diff -M`)
- Verified: `./gradlew compileDebugKotlin testDebugUnitTest` green, 67/67 tests pass (6 common + 54 ar + 7 photo)
- Verified: `:ar-measure-photo:dependencies --configuration debugCompileClasspath` has zero ARCore/SceneView
- Verified: merged `:app` manifest has exactly one CAMERA permission, one of each uses-feature, one AR meta-data, one FileProvider, `exported=false` on the provider, `exported=true` only on MainActivity
- Verified: public surface grep — exactly 6 public top-level declarations in `:ar-measure-ar` (`ArAvailability`, `ArMeasureKit`, `ArMeasureRulerScreen`, `ArMeasureBoxScreen`, `ArMeasureCylinderScreen`, `LevelScreen`), exactly 2 in `:ar-measure-photo` (`PhotoMeasureScreen`, `CustomReferenceStore`), `CustomReferenceStore`'s `loadAll`/`add` are `internal` as documented in deviation 4
- Verified: `app/src/main/java` contains exactly 2 files (`MainActivity.kt`, `ui/AppTabBar.kt`), no `vn.quancua` references left in any `ar-measure-*` module, no build/ dirs committed
- Diffed line-by-line against pre-move versions: `MeasureState.kt`, `MeasureScreen.kt`, `ShapeMeasureState.kt`, `ShapeMeasureScreen.kt`, `ShapeFrameLoop.kt`, `MeasureFrameLoop.kt`, `MeasureHit.kt`, `PoseProjector.kt`, `SteadinessGate.kt`, `ArWarmup.kt`→`ArWarmupGate.kt`, `MeasureMath.kt`, `ShapeMath.kt`, `MeasureControls.kt`, `LevelScreen.kt`, all `photo/domain/imaging/*`, `PhotoMeasureScreen.kt`, `PhotoMeasureState.kt`, `PhotoQuadCanvas.kt`, `DraggableHandlesOverlay.kt`, `AutoFitQuad.kt`, `CameraCapture.kt`, all 5 test files

The 5 documented deviations in plan.md all show up in the diff exactly as described, and are not
re-flagged: formatters moved to `:ar-measure-common` (dev 1), `onClose: (() -> Unit)? = null` (dev
2), `armeasure_` resource prefix + module-owned FileProvider (dev 3), `CustomReferenceStore.loadAll/add`
internal (dev 4), PascalCase Kotlin filenames kept (dev 5). The bounded `Checking`-state re-poll
added to `MainActivity`'s `AppRoot` is not in that list but is fully justified and documented in
phase-05's Key Insights/Risk Assessment as a mechanical consequence of moving the ARCore-availability
state machine into `ArMeasureKit` — not flagged as a defect.

## Overall Assessment
This is a genuinely clean restructure. AR session config, watchdog constants, warm-up delay,
steadiness thresholds and all pure-math bodies are byte-identical to pre-move (confirmed via
`git diff -M`, not just trusted from commit messages). Visibility sweep matches the architecture
record's §4 surface exactly, module boundaries hold (photo has zero ARCore on its classpath,
domain packages have zero Android/AR/Compose imports), and the manifest merge is duplicate-free.
One real bug found in the `onResult` wiring (below) — everything else is nit-level or already
explicitly out of scope.

## Critical Issues
None.

## High Priority
**1. `ArMeasureBoxScreen`/`ArMeasureCylinderScreen` `onResult` reports the wrong unit after an in-screen toggle**
`ar-measure-ar/src/main/java/vn/apero/armeasure/ar/presentation/shapes/ShapeMeasureScreen.kt:178` and `:205`

```kotlin
fun ArMeasureBoxScreen(..., unit: LengthUnit = LengthUnit.Metric, onResult: ... , ...) {
    ShapeMeasureScreen(..., onShapeCommitted = { shape ->
        ...
        onResult(MeasurementResult.Box(rect.edgeU.length(), rect.edgeV.length(), abs(shape.height), unit))
        //                                                                                          ^^^^ captured composable param, not state.unit
    }, ...)
}
```

Both wrappers close over the composable's own `unit` parameter (the *initial* value) instead of
`state.unit` (the live value the in-screen m/ft toggle mutates — see
`ShapeMeasureScreen.kt:139` `.clickable { state.toggleUnit() }`, and `ShapeFrameLoop.kt:159`
which correctly uses `state.unit` for the on-screen label). If a host's user opens Box/Cylinder,
toggles to Imperial, and finishes a shape, the on-screen label reads feet/inches but the
`onResult` callback reports `LengthUnit.Metric` alongside metre-valued fields — a silent
unit/value mismatch a host cannot detect without cross-checking the screen.

`ArMeasureRulerScreen` (`MeasureScreen.kt:290` `onResult(MeasurementResult.Distance(meters, state.unit))`)
and `PhotoMeasureScreen` (`state.unit` in `emitResult()`) both do this correctly — only the two
shape wrappers regressed. This also contradicts phase-03's own step 9 spec, which explicitly
says `state.unit` for both Box and Cylinder.

Fix: replace `unit` with `shapeState`'s live unit in both call sites — easiest is exposing
`state.unit` up into the wrapper (e.g. have `ShapeMeasureScreen` pass the committed `state.unit`
into `onShapeCommitted` alongside the shape, since `unit` isn't otherwise reachable from the
wrapper without holding a reference to `state`).

No JVM test catches this — the whole `onResult` plumbing is Compose-only, untested by the 54 AR
JVM tests (consistent with this repo's known pattern of new callback/gating wiring shipping
without dedicated tests).

## Medium Priority
None beyond what's listed under Low/observations.

## Low Priority / Nits
1. **Dangling comment reference** — `app/src/main/java/vn/quancua/artapemeasure/ui/AppTabBar.kt:22`
   references "`phase-03-app-tab-stash.kt`" as if it were a real file; no such file exists anywhere
   in the repo (it's a paraphrase of phase-03's "stash it in the scratchpad" instruction). Harmless,
   but a misleading breadcrumb for a future reader. Fix: reword to point at the phase-03 plan file
   or drop the filename reference.
2. **One extra native call per `onResume`** — `MainActivity.onResume()` now calls
   `ArMeasureKit.requestInstall(this)` then, if it returns `false`, an additional
   `ArMeasureKit.checkAvailability(this)` — two `ArCoreApk` calls where the pre-refactor code made
   one (call `requestInstall`, and only on caught `UnavailableException` set `Unsupported` directly).
   Functionally equivalent end state, just a redundant native round-trip on every resume. Not worth
   blocking on.
3. **Domain layer importing from data layer** — `ar-measure-ar/.../domain/steadiness/SteadinessGate.kt`
   imports `HitSource`/`SurfaceSample` from `ar.data.arcore`. This is baked into the architecture
   record's own package layout (§3), not something the implementer introduced, but it means
   `ar.domain.steadiness` is not actually independent of `ar.data.arcore` despite the "domain"
   name — worth a note in phase 07's README if a host ever wants to depend on domain packages alone.

## Edge Cases Found by Scout
- Shape `onResult` unit mismatch above — only surfaces when a user toggles units *during* an
  in-progress Box/Cylinder measurement, which is exactly the kind of interaction a manual on-device
  smoke test (phase 05's checklist) would not exercise unless someone specifically toggles mid-shape.
- `PhotoMeasureScreen.emitResult()` reads `state.currentDistanceMm` synchronously right after
  `state.confirmReference(...)` and after `onLineDragEnd`/`onPointDragEnd` fire — both are plain
  (non-suspending) state mutations before the read, so no stale-read race; confirmed by reading
  `PhotoMeasureState.confirmReference` (not a coroutine).
- `LaunchedEffect(arAvailability)` bounded re-poll in `MainActivity.AppRoot`: keyed on
  `arAvailability`, so it only restarts when the value actually changes; re-entrant `onResume` calls
  that re-set the same value (e.g. `Checking` → `Checking`) do not spawn a duplicate polling loop.
  No bug found here, but flagging it as the one place with genuinely new runtime behavior (not just
  moved code) beyond the 5 documented deviations — see Overall Assessment.

## Positive Observations
- `git diff -M` renames are clean across every AR-critical file (session config, watchdog,
  warm-up gate, steadiness gate) — zero body diff beyond package/import fixups and `internal`
  visibility, confirmed by direct diff, not by trusting the commit message.
- `ArWarmupGate` singleton correctly avoids re-introducing the duplicated-flag bug class from
  6a5cb50; both AR screens call the same `ArWarmupGate.rememberArWarmedUp()`.
- Public API surface is exactly the 6+2 declarations the architecture record specifies — verified
  by grep, not just by reading the intent.
- `:ar-measure-photo` has zero ARCore/SceneView on its compile classpath — verified with the actual
  `dependencies` Gradle task, not assumed from `implementation` vs `api` wiring.
- Merged manifest has no duplicate CAMERA/uses-feature/meta-data/provider entries; FileProvider and
  MainActivity `exported` flags are correct.
- Test count is exactly preserved (67) with identical assertions moved verbatim (`formatMeters`/
  `formatImperial` tests are byte-identical between the old `MeasureMathTest.kt` and the new
  `LengthUnitTest.kt`).
- The one pre-existing `Log.d("PhotoMeasure", "autoFitQuad tap=...")` (from 4fc3bea) correctly
  moved without being flagged as new, per the task's own exclusion note — confirmed via `git log -S`.

## Recommended Actions
1. **(High)** Fix the Box/Cylinder `onResult` unit capture bug before phase 07's README documents
   this as a reliable contract for host integrators.
2. **(Low)** Reword the dangling `phase-03-app-tab-stash.kt` comment reference in `AppTabBar.kt`.
3. **(Low, optional)** Collapse `MainActivity.onResume()`'s two-call pattern back to one branch if
   ever touched again; not worth a standalone commit today.
4. Plan-file bookkeeping done as part of this review: phases 01-05 status/todo checkboxes in
   `plans/260825-1745-ar-measure-module-extraction/{plan.md,phase-03,phase-04,phase-05}.md` were
   stale (still "pending"/unchecked) despite being fully implemented and verified — updated to
   reflect actual state. Phase 06/07 remain genuinely pending (no per-module READMEs yet, on-device
   smoke test not run this session either).

## Metrics
- Public API surface: 6/6 (`:ar-measure-ar`) + 2/2 (`:ar-measure-photo`) — exact match to spec
- Test Coverage: 67/67 JVM tests pass (6 + 54 + 7), 0 failures; no dedicated test for the
  `onResult` wiring bug above (Compose-only code path)
- Domain purity: 0 Android/ARCore/Compose imports in `ar.domain.*` and `photo.domain.imaging`
- `:ar-measure-photo` compile+runtime classpath: 0 ARCore/SceneView entries
- Linting: not run separately (no ktlint/detekt config found in repo); compile is clean with `-q`
  (no warnings surfaced)

## Unresolved Questions
- Is the Box/Cylinder `onResult` unit bug worth a standalone fix commit now, or bundled into
  whichever phase 07 README work touches these files next? (Recommend fixing now — it's a one-line
  change per wrapper and phase 06 is explicitly the "prove it's leak-free" gate before phase 07
  writes host-facing docs describing this contract.)
