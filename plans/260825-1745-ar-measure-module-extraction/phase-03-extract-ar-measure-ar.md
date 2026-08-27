# Phase 03 — Extract `:ar-measure-ar`

## Context Links

- Spec: architecture record §3 (`:ar-measure-ar` package layout), §4 (public API + facade), §5
  (state files belong in presentation, never domain), §6 (`ArWarmupGate`), §7 (module owns the
  AR-availability check), §9 (tests)
- Feature background: handoff report §3–§5 (why the code is shaped this way — do not "improve" it),
  §10.2 + §12 (the warm-up guard is load-bearing, keep both screens on the shared one)
- Depends on: [phase 02](phase-02-extract-ar-measure-common.md)
- Blocks: [phase 05](phase-05-slim-down-app-module.md)

## Overview

- **Priority:** P1
- **Status:** done (verified by code-reviewer against commit 61160dc)
- **Effort:** 3h
- Move ruler + Box/Cylinder + Level + all AR infra into `:ar-measure-ar`, add the `ArMeasureKit`
  facade, wrap the warm-up flag in `internal object ArWarmupGate`, and reduce everything else to
  `internal`.

## Key Insights

- The 2-second `ArWarmupDelayMs` guard and its process-global flag are **not** cosmetic — they are
  the fix for a reproduced `TextureNotSetException` cold-start race (handoff §10). Both
  `MeasureScreen.kt` and `ShapeMeasureScreen.kt` must keep calling the *same*
  `rememberArWarmedUp()`; duplicating it was the exact bug fixed in commit `6a5cb50`.
- `MeasureState.kt` / `ShapeMeasureState.kt` import Compose **and** `com.google.ar.core` in the
  same file. That is correct (record §5): they are presentation-layer controllers holding a live
  session handle for per-frame hit-testing. File them under `ar.presentation.*`. Do **not** try to
  hide the `Session` behind a repository interface.
- `MeasureScreen.kt` and `ShapeMeasureScreen.kt` reference `vn.quancua.artapemeasure.R` for 11
  hint/tracking strings. Those string resources must move into this module and be renamed with the
  `armeasure_` prefix (`resourcePrefix` from phase 01) — a library resource with a plain name is
  silently overridable by a host app that happens to define the same name.
- `ui/MeasureControls.kt` **splits**: `MeasureTopBar`/`MeasureBottomBar`/`ChromePill` come here as
  `internal` chrome; `AppTab`/`AppTabBar` stay in `:app` (record §4 drops them from the public
  surface). The two files each keep their own small colour constants — 3 duplicated `Color(...)`
  literals is the correct price for not making chrome colours public API.
- Kotlin `internal` in the main source set is still visible to that module's unit tests (AGP wires
  the test compilation as a friend module), so the 54 moved tests keep working against `internal`
  geometry functions with no `@VisibleForTesting` gymnastics.
- Today's screens take only `modifier`. The public API adds `unit`, `onResult`, `onClose` — the
  only intentional signature change in this whole extraction. `unit` seeds the state's existing
  `unit` field, so the in-screen m/ft toggle keeps working exactly as now.

## Requirements

Functional:
- Public surface is exactly: `ArMeasureKit`, `ArAvailability`, `ArMeasureRulerScreen`,
  `ArMeasureBoxScreen`, `ArMeasureCylinderScreen`, `LevelScreen`. Everything else `internal`.
- `onResult` fires once per committed measurement: a new ruler point that forms a segment, a
  completed box, a completed cylinder.
- `unit` parameter sets the initial display unit; the in-screen toggle still overrides it at runtime.
- `onClose` is `(() -> Unit)? = null`; when non-null the screens show a `✕` pill in the top bar,
  when null the chrome looks and behaves exactly as it does today.
- All 54 moved JVM tests pass.

Non-functional:
- No behavior change to AR runtime: same `Config` (`DepthMode.AUTOMATIC`,
  `PlaneFindingMode.HORIZONTAL_AND_VERTICAL`, `planeRenderer = true`), same watchdog, same warm-up
  delay, same steadiness thresholds.
- `ar.domain.*` keeps zero `android.*` / `com.google.ar.*` / `androidx.compose.*` imports.
- No ARCore or SceneView type appears in any `public` declaration (that is what keeps
  `implementation(libs.sceneview.ar)` valid and keeps ARCore off a consumer's compile classpath).

## Architecture

```
ar-measure-ar/src/main/java/vn/apero/armeasure/ar/
  ArMeasureKit.kt                      NEW  public object ArMeasureKit + enum ArAvailability
  domain/geometry/MeasureMath.kt       moved (minus the 4 declarations that went to :common)
  domain/geometry/ShapeMath.kt         moved
  domain/steadiness/SteadinessGate.kt  moved
  data/arcore/MeasureHit.kt            moved
  data/arcore/PoseProjector.kt         moved
  data/warmup/ArWarmupGate.kt          moved from measure/ArWarmup.kt, flag wrapped in an object
  presentation/ruler/MeasureScreen.kt        moved; public entry renamed ArMeasureRulerScreen
  presentation/ruler/MeasureState.kt         moved
  presentation/ruler/MeasureFrameLoop.kt     moved
  presentation/ruler/MeasureOverlay.kt       moved
  presentation/shapes/ShapeMeasureScreen.kt  moved; adds public ArMeasureBoxScreen/ArMeasureCylinderScreen
  presentation/shapes/ShapeMeasureState.kt   moved
  presentation/shapes/ShapeFrameLoop.kt      moved
  presentation/shapes/ShapeOverlay.kt        moved
  presentation/level/LevelScreen.kt          moved from level/LevelScreen.kt
  presentation/shared/MeasureControls.kt     moved (TopBar/BottomBar/ChromePill only)
ar-measure-ar/src/main/res/values/strings.xml   11 armeasure_-prefixed strings
ar-measure-ar/src/test/java/vn/apero/armeasure/ar/
  domain/geometry/MeasureMathTest.kt   moved (22 tests)
  domain/geometry/ShapeMathTest.kt     moved (25 tests)
  domain/steadiness/SteadinessGateTest.kt  moved (7 tests)
```

`ArMeasureKit.kt`:

```kotlin
enum class ArAvailability { Checking, Ready, NeedsInstall, Unsupported }

object ArMeasureKit {
    fun checkAvailability(context: Context): ArAvailability
    fun requestInstall(activity: Activity): Boolean   // true = user was redirected to Play
}
```

- `checkAvailability` maps `ArCoreApk.checkAvailability`: `SUPPORTED_INSTALLED` → `Ready`;
  `SUPPORTED_NOT_INSTALLED`/`SUPPORTED_APK_TOO_OLD` → `NeedsInstall`;
  `UNKNOWN_CHECKING` → `Checking`; everything else (`UNSUPPORTED_DEVICE_NOT_CAPABLE`,
  `UNKNOWN_ERROR`, `UNKNOWN_TIMED_OUT`) → `Unsupported`. KDoc must state that `Checking` is
  ARCore's own async first-call state and the caller should re-poll.
- `requestInstall` owns the `userRequestedInstall` re-entry guard that `MainActivity.kt:52` carries
  by hand today (private `var` inside the object, process-lifetime, same semantics): returns `true`
  and flips the flag to `false` on `INSTALL_REQUESTED`, `false` on `INSTALLED`, `false` on
  `UnavailableException` (caught, not thrown — a non-capable device is a degraded app, not a crash).

## Related Code Files

Move (`git mv`, then package/import edits only) — all from
`app/src/main/java/vn/quancua/artapemeasure/`:

| From | To (`ar-measure-ar/src/main/java/vn/apero/armeasure/ar/`) |
|---|---|
| `measure/MeasureMath.kt` | `domain/geometry/MeasureMath.kt` |
| `measure/ShapeMath.kt` | `domain/geometry/ShapeMath.kt` |
| `measure/SteadinessGate.kt` | `domain/steadiness/SteadinessGate.kt` |
| `measure/MeasureHit.kt` | `data/arcore/MeasureHit.kt` |
| `measure/PoseProjector.kt` | `data/arcore/PoseProjector.kt` |
| `measure/ArWarmup.kt` | `data/warmup/ArWarmupGate.kt` |
| `measure/MeasureScreen.kt`, `MeasureState.kt`, `MeasureFrameLoop.kt`, `MeasureOverlay.kt` | `presentation/ruler/` |
| `measure/ShapeMeasureScreen.kt`, `ShapeMeasureState.kt`, `ShapeFrameLoop.kt`, `ShapeOverlay.kt` | `presentation/shapes/` |
| `level/LevelScreen.kt` | `presentation/level/LevelScreen.kt` |
| `ui/MeasureControls.kt` | `presentation/shared/MeasureControls.kt` |
| `app/src/test/.../measure/MeasureMathTest.kt` | `ar-measure-ar/src/test/.../ar/domain/geometry/MeasureMathTest.kt` |
| `app/src/test/.../measure/ShapeMathTest.kt` | `ar-measure-ar/src/test/.../ar/domain/geometry/ShapeMathTest.kt` |
| `app/src/test/.../measure/SteadinessGateTest.kt` | `ar-measure-ar/src/test/.../ar/domain/steadiness/SteadinessGateTest.kt` |

Create:
- `ar-measure-ar/src/main/java/vn/apero/armeasure/ar/ArMeasureKit.kt`
- `ar-measure-ar/src/main/res/values/strings.xml`

Modify after the moves:
- `app/src/main/res/values/strings.xml` — remove the 11 moved strings (keep `app_name`,
  `ar_unsupported_title`, `ar_unsupported_body`; delete the confirmed-dead `tab_measure`,
  `tab_level`, `action_clear`, `action_undo`, `action_add_point`, `hint_no_surface`, `level_flat` —
  grep confirms zero references).

Delete: nothing by hand — `:app`'s `measure/`, `level/` dirs empty out via the moves.

## Implementation Steps

1. `git mv` every row of the table above, then fix each file's `package` line and its
   `vn.quancua.artapemeasure.*` imports (`ui.drawLabelPill` →
   `vn.apero.armeasure.common.ui.drawLabelPill`; `LengthUnit`/`formatLength` →
   `vn.apero.armeasure.common.domain.*`; `R` → `vn.apero.armeasure.ar.R`).
2. Split `presentation/shared/MeasureControls.kt`: delete `AppTab` and `AppTabBar` from it (they
   are re-created in `:app` in phase 05 — copy them into the scratchpad first so nothing is lost),
   keeping `MeasureTopBar`, `MeasureBottomBar`, `ChromePill` and the colour constants. Mark
   `MeasureTopBar`/`MeasureBottomBar` `internal`.
3. Add the `onClose` affordance: `MeasureTopBar(..., onClose: (() -> Unit)? = null)` renders a
   leading `ChromePill` with `✕` only when `onClose != null`. No layout change in the null case.
4. `data/warmup/ArWarmupGate.kt`: wrap the file-private `hasAttemptedArWarmup` in
   `internal object ArWarmupGate { … }` holding the flag and the delay constant, keep
   `rememberArWarmedUp()` as an `internal @Composable`, add
   `@VisibleForTesting internal fun reset()`. Keep the whole existing KDoc — it is the only record
   of why the delay exists. Update both screens' call sites.
5. Create `res/values/strings.xml` with the 11 used strings, renamed
   `armeasure_hint_warming_up`, `armeasure_hint_move_to_find_surface`,
   `armeasure_hint_aim_at_surface`, `armeasure_hint_tap_to_start`,
   `armeasure_hint_reading_unsteady`, `armeasure_hint_dragging_point`,
   `armeasure_tracking_bad_state`, `armeasure_tracking_insufficient_light`,
   `armeasure_tracking_excessive_motion`, `armeasure_tracking_insufficient_features`,
   `armeasure_tracking_camera_unavailable` — values byte-identical to `:app`'s. Update the 13
   `R.string.*` references in `MeasureScreen.kt`/`ShapeMeasureScreen.kt`.
6. Create `ArMeasureKit.kt` per the Architecture block, porting the ARCore logic out of
   `MainActivity.kt:87-99` (`onResume`) plus its `userRequestedInstall` comment verbatim — that
   comment explains the infinite-install-dialog trap and must survive the move.
7. Public entry points:
   - `MeasureScreen` → `fun ArMeasureRulerScreen(modifier: Modifier = Modifier, unit: LengthUnit = LengthUnit.Metric, onResult: (MeasurementResult.Distance) -> Unit = {}, onClose: (() -> Unit)? = null)`.
   - `ShapeMeasureScreen(kind, …)` becomes `internal`; add two public one-line wrappers in the same
     file: `ArMeasureBoxScreen(...)` / `ArMeasureCylinderScreen(...)` taking the same
     `modifier`/`unit`/`onResult`/`onClose` params (`onResult` typed
     `(MeasurementResult.Box) -> Unit` and `(MeasurementResult.Cylinder) -> Unit` respectively) and
     delegating with `ShapeKind.Box` / `ShapeKind.Cylinder`. `ShapeKind` itself stays `internal`.
   - `LevelScreen(modifier: Modifier = Modifier, onClose: (() -> Unit)? = null)` stays public.
8. `unit` plumbing: give `MeasureState` and `ShapeMeasureState` an `initialUnit: LengthUnit = LengthUnit.Metric`
   constructor param and initialise `var unit by mutableStateOf(initialUnit)`. Pass the composable's
   `unit` argument in at the `remember { … }` site. `toggleUnit()` is untouched.
9. `onResult` plumbing (fire exactly once per commit, at the existing commit call sites — no new
   `LaunchedEffect` polling):
   - Ruler: where the `+` button calls `state.commitLivePoint(session)`, on `true` and
     `state.worldPoints.size >= 2` emit
     `MeasurementResult.Distance(measureDistanceMeters(last two world points), state.unit)`.
   - Shapes: around `state.commitStep(session)`, capture `state.shapes.size` before/after; if it
     grew, map the newly added `MeasuredShape` — `ShapeBase.Rect` →
     `MeasurementResult.Box(edgeU.length(), edgeV.length(), abs(height), state.unit)`;
     `ShapeBase.Circle` → `MeasurementResult.Cylinder(radius, abs(height), state.unit)` — and hand
     it to the wrapper's `onResult`.
10. Visibility sweep: mark `internal` every top-level declaration in the module except
    `ArMeasureKit`, `ArAvailability`, `ArMeasureRulerScreen`, `ArMeasureBoxScreen`,
    `ArMeasureCylinderScreen`, `LevelScreen`. This includes all of `MeasureMath.kt`/`ShapeMath.kt`
    (`Vec3`, `Segment2D`, `measureDistanceMeters`, `planeBasis`, …), `SteadinessGate`,
    `MeasureHit`/`PoseProjector`, all state/frame-loop/overlay types.
11. **Verification gate (blocking):**
    `./gradlew :ar-measure-ar:compileDebugKotlin :ar-measure-ar:testDebugUnitTest` — compile green,
    **54 tests pass**. `:app` stays red until phase 05; do not run project-wide tasks.
12. Grep gates: `grep -rn "com.google.ar\|io.github.sceneview" ar-measure-ar/src/main/java/vn/apero/armeasure/ar/domain` → empty;
    `grep -rn "^public \|^fun \|^class \|^object \|^enum \|^sealed " ar-measure-ar/src/main/java --include=*.kt | grep -v internal`
    → only the 6 allowed public declarations.
13. Commit: `refactor: move AR measure, shapes and level features into :ar-measure-ar`.

## Todo List

- [x] 13 source/test files `git mv`'d, packages + imports updated
- [x] `MeasureControls.kt` split; `AppTab`/`AppTabBar` stashed for phase 05
- [x] `MeasureTopBar` gained the optional `✕` (`onClose`) pill
- [x] `ArWarmupGate` object + `reset()`; both screens on the shared `rememberArWarmedUp()`
- [x] 11 `armeasure_`-prefixed strings in module res; `:app` strings.xml pruned
- [x] `ArMeasureKit` + `ArAvailability` created, install re-entry guard ported with its comment
- [x] Public entries: ruler/box/cylinder/level with `unit`/`onResult`/`onClose`
- [x] `initialUnit` added to `MeasureState` + `ShapeMeasureState`
- [x] `onResult` emitted at the 3 commit sites, once per measurement
- [x] `internal` sweep done; only 6 public declarations remain
- [x] Verification gate green (module compile + 54 tests)
- [x] Commit

## Success Criteria

- `:ar-measure-ar:testDebugUnitTest` → 54 tests, 0 failures.
- Exactly 6 public top-level declarations in the module (grep gate in step 12).
- No `vn.quancua` reference remains in the module.
- AR session config, watchdog, warm-up delay and steadiness constants are byte-identical to
  pre-move (diff-review them explicitly — an accidental change here costs an on-device debug cycle).

## Risk Assessment

| Risk | Mitigation |
|---|---|
| `internal` sweep breaks the moved tests | AGP wires test compilation as a friend module, so `internal` is visible to `src/test`; the gate in step 11 proves it before moving on |
| Silently changing an AR constant during the move (thermal/black-screen behavior is device-dependent and untestable in JVM) | Move files with `git mv` so `git diff -M` shows a pure rename + import diff; review that diff line by line |
| Duplicating the warm-up flag again (the `6a5cb50` bug class) | Single `ArWarmupGate` object; grep for `hasAttemptedArWarmup` must return exactly one definition |
| Host resource collision on `hint_*`/`tracking_*` names | `resourcePrefix = "armeasure_"` + renamed strings |
| ARCore types leaking into public API (would force ARCore onto every consumer) | Step 12's public-declaration grep + `ArAvailability` being the module's own enum |
| `onResult` firing per frame instead of per commit | Emit at the commit call sites only; no `LaunchedEffect(state.…)` observers |
| `Checking` state stalling a host's UI | `checkAvailability` KDoc states the re-poll contract; `:app`'s bounded re-poll lands in phase 05 |

## Security Considerations

- `CAMERA` permission is declared by this module's manifest (merged into the host) but **requested**
  by the host at runtime — the module never prompts, so it cannot surprise a host's permission UX.
  Documented in phase 07's README.
- `ArWarmupGate` is `internal`: a host cannot see, reference or collide with the process-global flag.
- No measurement data is logged or persisted by this module; nothing leaves the device.
- `requestInstall` sends the user to the Play Store only on an explicit AR entry; the re-entry guard
  prevents an install-dialog loop that would look like a hijack.

## Next Steps

- Phase 04 (`:ar-measure-photo`) — independent, can already be in flight.
- Phase 05 consumes `ArMeasureKit` + the 4 entry composables.
- Backlog (not this plan): explicit anchor-detach on dispose for all three AR screens
  (record §10 / handoff §6) — a real behavior fix, so it must not ride along in a restructure.
</content>
