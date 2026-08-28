# Code review, consolidated — AR_feature after the MVI refactor

Branch `refactor/mvi-alignment` @ `d072207`. Three read-only reviews, nothing fixed.

- `code-reviewer-260828-0914-photo-package.md`
- `code-reviewer-260828-0914-ar-package.md`
- `code-reviewer-260828-0914-common-api-build.md`

## Fix order

### 1. `state` getter allocates a new flow per access — `common/presentation/mvi/MviViewModel.kt:37`
`val state: StateFlow<S> get() = _state.asStateFlow()`. `asStateFlow()` returns a new wrapper each
call, and Compose uses the flow instance as a `LaunchedEffect` key inside
`collectAsStateWithLifecycle` — so collection is cancelled and restarted on **every recomposition**.
`ArCameraScreen.kt:142-146` does it five times, on the AR path §15 exists to protect.

Introduced by me yesterday when the stored `val` became a getter to pair with the lazy `_state`. Fix
is `val state by lazy { _state.asStateFlow() }` — not a revert, the lazy `_state` is still needed for
the constructor-argument trap. `PhotoMeasureViewModel.kt:52` already does it correctly one line from
an affected site. Verified.

Add the test that would have caught it: `assertSame(vm.state, vm.state)` fails today. There is no
`MviViewModel` test at all.

### 2. README §2 cannot be followed — `AR_feature/README.md`
The `[libraries]` block lists **none** of the three aliases `build.gradle.kts` calls:
`androidx-lifecycle-viewmodel-compose`, `androidx-lifecycle-viewmodel-savedstate`,
`mlkit-subject-segmentation` (its version is listed, the library is not). A host following §1–§2
literally fails at Gradle configuration. README is the integration contract, so this is a defect.
Verified.

### 3. Stale KDoc that invites the worst bug back — `photo/domain/imaging/Homography.kt:18-21`
Still states the coordinate space does not matter and "screen display coordinates work fine — there
is no need to map back to the bitmap's native pixel grid". All three clauses are now false. This is
the comment most likely to talk a future editor into re-introducing the measures-wrong class.

### 4. Watchdog remount closes the Session under live anchors — `ar/presentation/camera/`
`key(instanceKey)` dispose calls `Session.close()`, but the release `DisposableEffect` is keyed
`Unit` and the ViewModels keep `points`/`session`. After a stall recovery the frame loop reads
anchors of a dead session. **Pre-existing** (same in `3f7e5fb`), but MVI makes the fix one call in
`onSessionChanged`.

### 5. Concurrent detection race — `photo/presentation/PhotoMeasureViewModel.kt:185-209`
The guard is checked before the `launch` and `showTapToPlace` stays true while detecting, so a second
tap starts a second segmenter plus Canny pass in parallel. The quad is never wrong, but memory spikes
and the spinner disappears while work continues. Guard on `isDetectingQuad` inside the launch.

### 6. `commitLivePoint` never re-checks `liveStable` — `ar/presentation/ruler/`
`commitStep` does. The intent hop now sits between tap and commit, so an unsteady depth sample can be
committed. One line. Reported by the reviewer; not independently re-verified here.

### 7. Back arrow discards the whole session with no confirmation — `photo/presentation/PhotoMeasureScreen.kt:201`
Clears quad, homography, every segment and both undo stacks, from an arrow inches from undo/redo.
The reducer is right; the binding is the problem. Product call as much as a bug.

### 8. Two stranding paths — `ar/presentation/host/ArCameraActivity.kt`
Permission is never re-read on resume (deny, grant in Settings, still the denied screen), and
`NeedsInstall` renders an empty black Box.

### 9. Self-inflicted small stuff
- `persist()` runs on every `updateState`, so a drag writes four `SavedStateHandle` entries per
  pointer event, 60-120/s, none of which can change during a drag.
- `CameraCapture.createCameraCaptureUri` does `mkdirs`/`listFiles`/`delete` on Main; the 6 MB temp
  delete also lands on Main — beside the decode that was moved off Main for that reason.
- Duplicate imports in `ArCameraScreen.kt`, `ArCameraControls.kt`, `ShapeOverlay.kt`, left by the
  components-move script. Verified.
- Comments in both AR Contracts and `ArCameraViewModel.init` still describe the
  `createInitialState`-reads-ctor-args trap as live. The `by lazy` fix removed it.

### 10. Documentation that is false rather than merely stale
- §17 claims `camera-capture/` "is never cleaned" — it is swept per capture and deleted after decode.
- §17's over-200-line list names three deleted files, has four wrong counts, misses six files, and
  recommends splitting a file that no longer exists.
- §13's R8 "verified clean" cites a 2026-08-26 audit; the viewmodel artifacts landed on the 27th. The
  conclusion probably still holds, but it is asserted as verified and is not.
- `UndoRedoStack` is now redo-only: `push`/`undo`/`pushUndo`/`popUndo`/`canUndo` have no callers and
  `undoDeque` is permanently empty. Its KDoc still claims all four screens use it.
- `MagnifierLoupe.kt:49` cites `QuadCrop.kt`, which does not exist.

### 11. Audits with blind spots
- `Theme.ArMeasure` is unprefixed despite `resourcePrefix`, and audit 4 only inspects
  `<string name=`, so it passes forever. A host declaring that style silently overrides both
  Activities' theme.
- Audits 8 and 9 state expected output that was never achievable.
- The public-surface grep in §4 misses leading modifiers, top-level `suspend fun` and multi-line
  annotations. The surface is nevertheless still exactly three symbols, re-verified with a stricter
  grep.

## What the reviews confirm is right

- **Coordinates.** Every conversion goes through `ImageFit` at exactly two edges; no site re-derives
  the letterbox or stores a display coordinate; the homography is bitmap-space throughout; the export
  path's identity-projection assumption holds. This was phase 02's biggest risk.
- **§12, one shared ARCore session.** Verified in the code *and* in sceneview 4.31.0 itself, which
  keeps `onSessionUpdated` in an `AtomicReference` re-set per recomposition — a tool swap changes the
  loop without a remount by construction.
- **Public surface** is exactly the three documented symbols.
- **MVI base**, apart from item 1: lazy is thread-safe, the zero-buffer `SharedFlow` drops nothing
  (suspending `emit`, not `tryEmit`) and cannot deadlock, `processIntent` before first collection is
  safe.
- **Threading, bitmaps, temp-file ownership, reducer purity, undo bounds** all check out.

## Unresolved questions

1. Is back-arrow-discards-everything intentional, or should it return to the picker keeping the session?
2. Should the exported image match on-screen annotation thickness? It currently renders at screen
   density into a full-resolution bitmap, so strokes come out ~1.5x thinner relative to the photo.
3. Item 4 predates this refactor and was never reported before. Worth checking whether it has ever
   been seen in the field, before treating it as urgent.
