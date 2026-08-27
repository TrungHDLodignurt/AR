# Phase 03 — Picture Measure -> Contract + ViewModel

Context: [plan.md](plan.md) · depends on phases 01, 02. Status: not started.

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

Open question to settle while writing it: the `Bitmap` does not belong in an immutable `State` — a
`data class` compares it by reference, and holding it there retains several MB across config change.
Keep the bitmap in the ViewModel as a plain field with `photoLoaded: Boolean` in `State`, unless a
better idea appears.

## What must go through SavedStateHandle

The six cases that broke today, each of which must be re-tested by hand:

- [ ] `chosenReferenceId` — restore lands past the picker
- [ ] the chosen reference resolving to a **custom** object, not falling back to A4. This regressed
      once already when the custom list moved to an async load; the resolve must not depend on load order
- [ ] `showPickPhotoSheet`
- [ ] `showReferenceSheet`
- [ ] `editingReferenceId`
- [ ] the camera capture's `pendingUri` (lives in `CameraCapture.kt`, may stay there)

Not persisted, and say so in KDoc: the photo bitmap, the quad, the segments. Same gap as today.

## Gate — on device, Joy_4 and Pixel

`always_finish_activities` does **not** reproduce recreation-with-restore: it destroys the Activity
outright. Use:

    HOME  ->  adb -s <serial> shell am kill vn.quancua.artapemeasure  ->  relaunch

That kills the process while preserving the task's saved state, which is the path that actually
restores. Verified working today.

Per case: reach the state, kill, relaunch, confirm the screen comes back where it was with the right
reference label. Plus the whole happy path once: reference -> photo -> tap -> drag -> confirm ->
measure -> save.

## Risk

No tests cover any of this. The checklist above is the safety net, and it is only as good as the
discipline of running it.
