# Phase 04 — AR screens to MVI, frame stream outside State

Date 2026-08-27 · branch `refactor/mvi-alignment` (not committed, as instructed) · module AR_feature
Phase file: `plans/260827-1910-mvi-alignment/phase-04-ar-screens-viewmodel.md` (todos ticked,
decisions recorded there too).

## Result

Code done. Compiles, tests green, release APK builds and signs. The frame measurement is
**outstanding by instruction** (§5) — procedure written out below so it runs cold.

## The split, as built

Three Contract+ViewModel pairs, five ViewModel instances, no DI (phase 01's `viewModel()` +
explicit factory; `key = "ar-distance"` etc. is what makes four instances of two classes).

| In `State` (human speed) | Outside `State` (frame stream) |
|---|---|
| `ArCameraUiState`: tool, unit, showModeSheet, showUnitMenu | `ArSessionFrameStream`: tracking, anyPlaneTracked, depthSupported, cameraReady, lastFrameAtMillis, trackingFailure |
| `MeasureUiState`: pointCount, canRedo, lastSource (+ derived canUndo) | `MeasureFrameStream`: live, liveStable/gate, worldPoints, overlay, draggingIndex, dragTouchPosition, dragSample |
| `ShapeUiState`: phase, shapeCount, canRedo (+ derived canUndo) | `ShapeFrameStream`: live, liveStable/gate, overlay |

Files added: `camera/ArCameraContract.kt`, `camera/ArCameraViewModel.kt`,
`ruler/MeasureContract.kt`, `ruler/MeasureFrameStream.kt`, `ruler/MeasureViewModel.kt`,
`shapes/ShapeContract.kt`, `shapes/ShapeFrameStream.kt`, `shapes/ShapeMeasureViewModel.kt`.
Deleted: `ruler/MeasureState.kt`, `shapes/ShapeMeasureState.kt`. Renamed (git mv):
`camera/ArSessionState.kt` -> `ArSessionFrameStream.kt`, and its test likewise.
Rewritten: `ArCameraScreen.kt`, `ArCameraControls.kt`, `ArCameraHints.kt`, `MeasureFrameLoop.kt`,
`ShapeFrameLoop.kt`.

The 60 Hz reason is in KDoc in three places (`MeasureFrameStream`, `ShapeFrameStream`,
`ArSessionFrameStream`) plus the `ArCameraScreen` header, in the spec's own words: an intent round
trip costs a coroutine dispatch and a whole-state allocation per frame and replaces Compose's
per-field invalidation with whole-state invalidation; transient render state is not UI state.

Anchors and finished shapes stay in **private plain lists** on the ViewModels, not in `State`: the
renderer wants their *current* world poses, which drift, so they are republished through the frame
stream each frame. `State` carries the count/undo answers the chrome actually asks. `persist` is
overridden only on `ArCameraViewModel` (active tool); the tools override nothing, documented — an
`Anchor` is meaningless once its session is gone.

## Moved differently from the spec

1. **`chained` / `kind` are ViewModel config, not `State` fields.** They never change, and — the real
   reason — `MviViewModel` builds its initial state from a field initializer, which runs before a
   subclass's constructor-parameter fields are guaranteed assigned. Any `createInitialState()` that
   reads a ctor arg is a trap. `ArCameraViewModel` needed its ctor args (UnitPreference,
   SavedStateHandle) so it seeds in an `init { updateState { ... } }` block, which is ordering-safe.
   **Phase 03 should use the same pattern** if `PhotoMeasureViewModel` restores from a handle.
2. **The drag gesture is a direct VM API** (`onDragStart/onDragMove/onDragEnd/onDragCancel`), not
   intents, so `draggingIndex` sits in the frame stream rather than `State` as "whether a gesture is
   in progress". Touch-event rate; resolved against a surface inside the frame loop; and
   `onDragStart` must be visible to the very next `onDrag`, which a `SharedFlow` hop cannot promise.
   A state snapshot captured by `pointerInput(Unit)` would also be permanently stale, which the old
   mutable holder hid.
3. **A third pair (`ArCameraViewModel`) beyond the two in the todo** — the spec lists "selected tool,
   unit" as `State`, and those are the screen's, not a tool's. It is also the only place `persist`
   earns its keep here.
4. **`releaseAll()` now runs from `onCleared()` as well as the screen's `DisposableEffect`.** New
   hazard the conversion itself introduces: a ViewModel outlives the composition, so an Activity
   recreation would otherwise retain anchors from a torn-down session. `releaseAll` also resets the
   state, so a re-entered retained VM cannot report points that no longer exist.
5. `onFrame` free functions renamed `onMeasureFrame` (the name `onFrame` is now the VM method the
   ARCore callback calls) and re-parameterised to take the frame stream + the anchor list.

## §12, one shared session — checked by reading, holds

`ArCameraScreen.kt`, read before and after: `rememberEngine()`/`rememberMaterialLoader()` are still
called once at the top of the composable, outside everything; there is exactly one `key(...)` in the
file (line 226, `key(instanceKey)`) and it wraps only `ARSceneView`; `instanceKey` is bumped by the
watchdog alone. `grep -n "key("` returns that one call site plus two KDoc mentions. `tool` appears
only in `when (tool)` for choosing a frame loop, an overlay and the chrome bindings — never in a
`key`, and the `ARSceneView` call sits outside every branch. The watchdog, the `ON_RESUME` clock
reset and `shouldForceRemount` are unchanged (the pure function is byte-identical; its 6 tests still
pass). Nothing about tool switching moved. This is a read-level check, not a device check — a tool
swap producing a black screen would only be caught by the device round.

## Verification actually performed

- `./gradlew :AR_feature:compileDebugKotlin` — BUILD SUCCESSFUL, zero warnings.
- `./gradlew :AR_feature:testDebugUnitTest --rerun-tasks` — **174 tests, 0 failures, 0 errors, 2
  skipped** (parsed from `AR_feature/build/test-results/testDebugUnitTest/TEST-*.xml`). 174, not the
  expected 172: the parallel phase-02 session modified `RealPhotoAutoFitTest` /
  `PhotoMeasureSegmentsTest`, adding two. Nothing under `ar/` changed count;
  `ArSessionFrameStreamTest` = 6/6 green. The 2 skips are the pre-existing documented ones.
- `./gradlew :app:assembleRelease` — BUILD SUCCESSFUL, R8 + resource shrinking on. Worth having: it
  proves the new `viewModel()`/`viewModelFactory`/`createSavedStateHandle` surface needs no keep
  rules (all reflection-free), so §13's R8 story is intact. No new dependency was added; the two
  lifecycle artifacts were already in the catalog from phase 01.
- Signed a release APK end to end with the documented zipalign+apksigner path (verified by
  `apksigner verify`) so the measurement procedure below is known-good, not assumed.
- No `photo/**`, `common/**`, README, catalog or build-file edits. Confirmed by `git status`.
- **Not** checked, i.e. inferred: anything runtime. Chiefly (a) `createSavedStateHandle()` resolving
  from `ArCameraActivity`'s creation extras — standard AndroidX pattern, ComponentActivity supplies
  the keys, but it throws rather than degrades if it ever does not; (b) the actual frame cost.

## Outstanding: the frame measurement — runnable as-is

Not run, and no human was asked, per the coordinator's instruction. It is the phase gate, so it must
happen in the consolidated round.

**Device.** `adb devices` right now shows exactly one: `99261FFAZ0077C`. That is **not** the Joy_4 —
serials have changed twice today and `BKB00251473` (the Joy_4, the low-end device the gate names) was
not attached. Re-read `adb devices` at the time and pass `-s <joy4-serial>`; if the Joy_4 cannot be
attached, say so rather than substituting a faster phone, because the whole point is the device with
no headroom. Never `installDebug`.

**Build + sign** (from repo root; done today, works):

    ./gradlew :app:assembleRelease
    BT=~/Library/Android/sdk/build-tools/36.1.0
    $BT/zipalign -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/ar-rel.apk
    $BT/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
      --key-pass pass:android --ks-key-alias androiddebugkey /tmp/ar-rel.apk
    $BT/apksigner verify /tmp/ar-rel.apk        # silence = pass
    adb -s <serial> install -r /tmp/ar-rel.apk

A signed, verified copy of tonight's build (this branch, post-conversion) is at
`<scratchpad>/app-release-signed.apk` if it is still there.

**Baseline half.** The before number must come from the same procedure on the pre-conversion code:
`git stash` is forbidden here, so use a throwaway worktree at the parent commit —
`git worktree add /tmp/ar-before 3f7e5fb` — build the release APK there, measure, then remove the
worktree. Measuring after-only proves nothing.

**Run.** Release build, Joy_4, AR Distance tool, 30 s of continuous tracking, identical gestures
before and after:
1. `adb -s <serial> logcat -c`, launch the app, open the Measure tab -> AR card, wait for the
   warm-up toast to clear.
2. Aim at a textured surface (a rug, a wood table — not a blank wall) until the reticle goes solid.
3. Place 4 points with `+`, ~3 s apart, panning slowly between them.
4. Drag one placed point across the screen and release (this is the path that changed most).
5. Undo twice, redo once, Clear.
6. Switch tool to Box via the sheet, back to Distance (the §12 check: any black frame or re-warm-up
   here is a regression regardless of the frame numbers).
7. Keep the phone moving until 30 s have elapsed, then close.

**Capture.** Filter tightly, never dump raw logcat:

    adb -s <serial> logcat -d | grep -E "Choreographer: Skipped|OpenGLRenderer: Davey" \
      | grep -oE "Skipped [0-9]+ frames|Davey! duration=[0-9]+ms"

**Verdict.** Sum the skipped-frame counts and count the `Davey!` events over the 30 s window, for
before and after. Regression = total skipped frames worse by more than ~10% or any new
`Davey! duration>700ms` that the baseline did not have. If the after is worse, the split is wrong
somewhere — the first suspects, in order: (a) the chrome recomposes on every live-reading change
because `distanceActions`/`shapeActions` read `frames.addEnabled` and the hint in composition (this
is *unchanged* from the pre-MVI code, which read the same two fields off the old holder, so it should
be a wash — but it is the largest remaining per-frame recomposition and the obvious next fix is to
pass `addEnabled` as a `() -> Boolean` read inside the button); (b) `collectAsStateWithLifecycle` on
all four tool states in one scope (they only change on taps, so this should be free).

## Wanted to edit outside my ownership — did not

- `AR_feature/src/main/java/vn/apero/armeasure/common/domain/UndoRedoStack.kt` KDoc still names
  `MeasureState` / `ShapeMeasureState`, classes that no longer exist (3 mentions, lines ~9-19).
  `common/**` is frozen and phase 03 owns the `PhotoMeasureState` half of the same sentence, so
  someone should sweep it once both phases land. `AR_feature/src/test/.../UndoRedoStackTest.kt` has
  the same stale names (test file under `common`, also not mine).
- `AR_feature/README.md` §15 states "No ViewModel … four plain classes … No Flow, no Intent, no
  reducer … No DI" and §12's last paragraph says "all three tools". All now false for `ar/**`. Phase
  05 owns the rewrite; flagging so it is not missed.

## Unresolved

1. Runtime-only: does `createSavedStateHandle()` resolve in `ArCameraActivity`'s extras? If it ever
   does not, the AR camera throws at first composition. First thing to watch in the device round.
2. Is `ArSessionFrameStream` staying in `remember` (not a ViewModel) the right call? It is correct
   today because the session dies with the composition; it becomes wrong the day the session is
   hoisted to survive one.
3. The four `collectAsStateWithLifecycle` calls collect inactive tools' states too. Harmless
   (tap-rate), but if the measurement is worse than baseline this is one of two suspects.
4. Presentation layer still has no automated tests — unchanged by this phase, and the reason the gate
   is a device check. The only new pure logic worth a test would be `emitClosedSegment`'s
   closing-pair rule, which needs an `Anchor` to reach.
5. Phase 03 has not landed yet (no `photo/**` ViewModel on disk at the time of writing), so the two
   phases' `MviViewModel` usage has not been read against each other for consistency.
