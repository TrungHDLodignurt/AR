# Phase 04 — AR screens -> ViewModel, frame stream outside State

Context: [plan.md](plan.md) · depends on phase 01. Status: **code done** 2026-08-27; the frame
measurement is outstanding by instruction (one consolidated device round after all phases land).
Report: [`../reports/fullstack-260827-1926-phase04-ar-mvi.md`](../reports/fullstack-260827-1926-phase04-ar-mvi.md)

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

## Gate

Compile plus the 172 JVM tests. The measurement below moved to
[regression-test-scenario.md](regression-test-scenario.md) (item X8) — it is still required before this phase
can be called proven, just not before the next phase starts.

### Deferred to the final round — measured, not assumed

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

- [x] `MeasureContract` + `MeasureViewModel` (Distance and DistanceChain share it; `chained` stays a
      ctor arg — see the decisions below for why it is NOT a `State` field)
- [x] `ShapeContract` + `ShapeMeasureViewModel` (Box and Cylinder, `kind` same treatment)
- [x] Frame-stream holder, owned by the ViewModel, outside `State` — `MeasureFrameStream`,
      `ShapeFrameStream`
- [x] `ArSessionState` — decided: it **is** the session-level frame stream, renamed
      `ArSessionFrameStream`, no Contract/ViewModel of its own, still `remember`ed by the screen
- [x] One shared ARCore session still serves all four tools; `tool` appears in no `key(...)`
- [x] `compileDebugKotlin` + `testDebugUnitTest` (174 tests / 0 failures / 2 skipped — 174 not 172
      because phase 02 added two photo tests in parallel)
- [ ] The frame measurement above — **outstanding by instruction**, procedure written out verbatim in
      the report so it can be run cold

## Decisions taken while building it

1. **`ArSessionState` -> `ArSessionFrameStream`.** Every field is written from an ARCore callback, so
   it is frame stream, not state, and it is not a screen. Kept in the screen's `remember` rather than
   a ViewModel: it describes the session this composition owns, and a ViewModel-scoped copy would
   outlive that session and open with the previous one's tracking flags.
2. **A third pair, `ArCameraContract` + `ArCameraViewModel`,** beyond the two the todo listed. The
   spec's in-`State` list names "selected tool, unit"; those belong to the screen, not to a tool. It
   also earns the only genuine `persist` in this phase: the active tool goes into the
   `SavedStateHandle` (the unit is already process-wide in `UnitPreference`; anchors cannot survive
   at all).
3. **`chained`/`kind` are ViewModel config, not `State` fields.** Two reasons: they never change, and
   the base builds its initial state from a field initializer that runs before a subclass's
   constructor-parameter fields are guaranteed assigned — so any `createInitialState()` reading a
   ctor arg is a trap. `ArCameraViewModel` seeds from its ctor args in an `init` block instead, which
   is ordering-safe. Worth knowing for phase 03.
4. **The drag gesture is a direct ViewModel API, not intents.** Deviates from the spec's "whether a
   gesture is in progress" living in `State`: the position arrives at touch-event rate, is resolved
   against a surface inside the frame loop, and `onDragStart` must be visible to the very next
   `onDrag` — which a `SharedFlow` round trip cannot promise. `draggingIndex` therefore lives in the
   frame stream, single source of truth, and the chrome asks it the yes/no question directly.
5. **`releaseAll()` is called from both the screen's `DisposableEffect` and `onCleared()`.** New
   hazard the conversion introduces: a ViewModel outlives the composition, so without `onCleared`
   an Activity recreation would leave anchors from a dead session attached.

## Risk

The one shared-session rule is easy to break while moving state around, and breaking it costs seconds
of black screen on every tool switch — the exact thing README §12 exists to prevent.
