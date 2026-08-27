# Phase 04 — AR screens -> ViewModel, frame stream outside State

Context: [plan.md](plan.md) · depends on phase 01. Status: not started.

## Goal

`MeasureState`, `ShapeMeasureState` and `ArSessionState` become Contract + ViewModel pairs like every
other screen, **without** putting the per-frame ARCore values into the MVI `State`.

## The split

`ArCameraScreen.kt:218` calls `onFrame(...)` from ARCore's frame callback, ~30-60 Hz. It writes
tracking flags, the live measurement and drag samples.

**In `State`** — what the user drives, changing at human speed: selected tool, unit, committed
segments/shapes, undo availability, whether a gesture is in progress.

**Outside `State`** — the frame stream: `tracking`, `anyPlaneTracked`, `depthSupported`, `live`, drag
samples. These stay in a plain holder the renderer reads directly, owned by the ViewModel but not part
of the emitted state.

Reason, to be written into the KDoc so it is not re-litigated: routing 60 Hz through
`processIntent -> SharedFlow -> handleIntent -> updateState { copy() } -> StateFlow` costs a coroutine
dispatch and a full state allocation per frame, and replaces Compose's per-field invalidation with
whole-state invalidation. Transient render state is not UI state.

## Gate — measured, not assumed

The performance claim above is inferred from the call site and from today's frame-drop numbers. It has
never been profiled. Phase 04 is not done until it is:

1. **Release** build (debug overhead dominates and would hide the effect — today debug cold start was
   2.7 s against release 648 ms on the same device).
2. Joy_4. Run the AR Distance tool for 30 s of continuous tracking, with the same gestures before and
   after.
3. Record `Choreographer: Skipped N frames` counts and any `OpenGLRenderer: Davey!` durations.
4. If the after is worse, the split above is wrong somewhere — find it rather than accepting it.

Signing a release APK for on-device measurement: `zipalign` then `apksigner` with
`~/.android/debug.keystore` (`pass:android`, alias `androiddebugkey`). Done today, works.

## Todo

- [ ] `MeasureContract` + `MeasureViewModel` (Distance and DistanceChain share it; `chained` is a ctor arg today)
- [ ] `ShapeMeasureContract` + `ShapeMeasureViewModel` (Box and Cylinder, `kind` is a ctor arg)
- [ ] Frame-stream holder, owned by the ViewModel, outside `State`
- [ ] `ArSessionState` — decide whether it becomes part of the frame holder rather than its own screen
- [ ] One shared ARCore session must still serve all four tools; the tool identity must not appear in
      a `key(...)` around `ARSceneView` (README §12)
- [ ] `compileDebugKotlin` + `testDebugUnitTest`
- [ ] The frame measurement above

## Risk

The one shared-session rule is easy to break while moving state around, and breaking it costs seconds
of black screen on every tool switch — the exact thing README §12 exists to prevent.
