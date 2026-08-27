# Phase 03 — Picture Measure -> Contract + ViewModel

Context: [plan.md](plan.md) · depends on phases 01, 02. Status: **done** 2026-08-27.
Report: [../reports/fullstack-260827-1926-phase03-photo-viewmodel.md](../reports/fullstack-260827-1926-phase03-photo-viewmodel.md)

## Goal

`PhotoMeasureState` (436 lines, ~20 `mutableStateOf` properties) becomes
`PhotoMeasureContract` + `PhotoMeasureViewModel`, with `SavedStateHandle` covering everything that
must survive Activity recreation.

## Why this screen first

It is the one with real lifecycle pain: the flow hands the foreground to the OEM camera and the photo
picker, so recreation mid-flow is routine rather than exceptional. Six separate `rememberSaveable`
patches exist today, and every one of them was a shipped bug first.

## Contract sketch

    internal object PhotoMeasureContract {
        data class State(
            val reference: ReferenceObject,
            val customReferences: List<ReferenceObject>,
            val photoLoaded: Boolean,
            val quad: List<Vec2>,            // bitmap space after phase 02
            val segments: List<Segment>,
            val unit: LengthUnit,
            val isDetectingQuad: Boolean,
            val isEditingQuad: Boolean,
            val isCalibrated: Boolean,
            val canUndo: Boolean, val canRedo: Boolean,
        ) : MviState
        sealed interface Intent : MviIntent { /* TapToReveal, MoveCorner, ConfirmReference, ... */ }
        sealed interface Effect : MviEffect { /* ShowSaveResult, ... */ }
    }

### As built — the two open questions, settled

**Bitmap.** `PhotoMeasureViewModel.photo: StateFlow<Bitmap?>`, not a `State` field, and no mirrored
`photoLoaded` boolean either: a second source of truth for "is there a photo" would be a drift risk
with no reader the flow cannot serve. The flow is observable, so the UI recomposes on load exactly as
a `State` field would have made it.

**Undo/redo.** `undoStack`/`redoStack`/`dragStartSnapshot` moved INTO `State` as plain lists of
`PhotoSnapshot`, replacing this screen's `UndoRedoStack`. With `State` immutable a snapshot is just a
value, so every undo transition became a pure function like the rest — and one drag stays one undo
step (`moveQuadCorner` captures the pre-drag snapshot once, `commitDrag` pushes it), now asserted by
a unit test instead of by reading the code. `canUndo`/`canRedo` are derived, not stored.
`PhotoSnapshot` stays narrower than `State` on purpose: undo must not rewind the unit, a sheet, or
which screen the user is on.

Reducers live in `PhotoMeasureReducers.kt` as pure `State -> State` functions. That is what made this
phase testable at all: no `Dispatchers.Main` (the module has no `kotlinx-coroutines-test`), no
`SavedStateHandle`, no `Bitmap`.

## What must go through SavedStateHandle

The six cases that broke today. Four and a half are now unit-tested; the platform half of each
(the Activity really being handed its bundle back) is still device-only.

All four persisted fields are one map/restore pair in `PhotoMeasureSavedState.kt`, written by
`persist(state)` and read by `createInitialState()`.

- [x] `chosenReferenceId` — restore lands past the picker. Unit test.
- [x] the chosen reference resolving to a **custom** object, not falling back to A4. Fixed
      structurally: `State.reference` is now **derived** from `chosenReferenceId` +
      `customReferences`, so it cannot have an ordering dependency, and there is no A4 fallback
      anywhere to fall back to. Unit-tested in both orders (restore-then-load, load-then-restore).
- [x] `showPickPhotoSheet`. Unit test.
- [x] `showReferenceSheet`. Unit test.
- [x] `editingReferenceId` — same derivation as `reference`. Unit test.
- [x] the camera capture's `pendingUri` — **stayed** in `CameraCapture.kt`. It belongs to the
      `ActivityResult` launcher living in that composition and is meaningless without it; moving it
      to the ViewModel would split one mechanism across two owners. Still device-only to verify.

Not persisted, and said so in KDoc: the photo bitmap, the quad, the segments, the homography, the
undo history. Same gap as today.

## Gate — met

Compile clean, `:AR_feature:testDebugUnitTest` **185 tests, 0 failures, 2 pre-existing skips**
(174 after phase 02, +11 here: 3 new segment/drag invariants and 8 restoration tests). The device checks below moved to
[regression-test-scenario.md](regression-test-scenario.md) — see the note in plan.md.

### Deferred to the final round

`always_finish_activities` does **not** reproduce recreation-with-restore: it destroys the Activity
outright. Use:

    HOME  ->  adb -s <serial> shell am kill vn.quancua.artapemeasure  ->  relaunch

That kills the process while preserving the task's saved state, which is the path that actually
restores. Verified working today.

Per case: reach the state, kill, relaunch, confirm the screen comes back where it was with the right
reference label. Plus the whole happy path once: reference -> photo -> tap -> drag -> confirm ->
measure -> save.

## Risk — as it stands after the work

The presentation layer is no longer entirely untested: the reducers, the derivation that fixes the
A4 fallback, and the persist/restore map are all covered by plain JUnit. What is still uncovered and
device-only: that the platform restores the bundle at all, the camera `pendingUri` round trip through
an OEM camera app, and every gesture/draw edge (unchanged from phase 02).
