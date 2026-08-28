# Code review — `photo/**`

Branch `refactor/mvi-alignment` @ `d072207`, clean tree. Read-only review; nothing fixed.
Transcribed from the reviewing session, which did not write the file itself.

## Confirmed by reading the code

### 1. Concurrent detection race — `PhotoMeasureViewModel.kt:185-209` — medium
`if (stateValue.quad.isNotEmpty()) return` is checked *before* the `launch`, and `showTapToPlace`
stays true while detecting. A second tap during a 1-15 s detection starts a second ML Kit segmenter
plus Canny pass in parallel — each allocating a 1024-long-side scaled bitmap, a ~1 M `FloatArray` and
a flood fill. Whichever finishes first runs `finally { isDetectingQuad = false }`, so the "detecting"
label disappears while the other is still running and a quad pops in seconds later. `quadDetected`
guards the write, so the quad is never *wrong* — the cost is a memory spike and a visibly wrong
spinner. Guard on `stateValue.isDetectingQuad` too, inside the launch.

### 2. Back arrow destroys the session with no confirmation — `PhotoMeasureScreen.kt:201` → `PhotoMeasureReducers.kt:125-135` — medium
`Intent.DiscardPhoto` clears the quad, the homography, every segment and both undo stacks. One tap on
an arrow that sits inches from undo/redo loses a whole measuring session irrecoverably. The reducer
is right; binding it to a bare back arrow is the problem.

### 3. Stale drag state after `moveQuadCorner` / `undo` — `PhotoMeasureReducers.kt:163-180` — low
`moveQuadCorner` does not clear `redoStack` (only `commitDrag` does), and `undo`/`redo` do not clear
`dragStartSnapshot`. A second finger on undo mid-drag leaves a dangling snapshot that the next drag's
`dragStartSnapshot ?: snapshotNow()` reuses, so that drag's undo reverts to a much older quad.
Needs two fingers, so unreachable in normal use. No test covers it.

### 4. `photoPicked` pushes an undo entry while `discardPhoto` clears the stacks — `PhotoMeasureReducers.kt:110` vs `:125` — low, latent
`PickPhotoRequested` is currently only reachable from `WaitingForPhoto`, so the pushed snapshot is
empty and harmless. Add any "change photo" affordance on SCR-23 and that `pushUndo()` becomes an
entry that restores photo A's quad and segments onto photo B — exactly what `discardPhoto`'s own KDoc
says must never happen.

### 5. `persist()` runs on every `updateState` — `MviViewModel.kt:62-66` + `PhotoMeasureViewModel.kt:98` — low
A corner drag writes four `SavedStateHandle` entries per pointer event, 60-120 times a second, and
none of the four fields can change during a drag. The drag path bypasses the intent channel precisely
to avoid per-frame cost, then pays a smaller one here. Persist only on change, or skip it from the
drag entry points.

### 6. Main-thread file IO — `CameraCapture.kt:118-127`, `PhotoMeasureScreen.kt:284` — low
`createCameraCaptureUri` does `mkdirs` + `listFiles` + `delete` on the main thread, and
`discardCameraCapture` is called from `rememberCoroutineScope().launch` (Main), so the 6 MB temp
delete also lands there. Small, but the decode right beside it was moved off Main for this reason.
StrictMode flags both.

### 7. Silent decode failure — `PhotoMeasureScreen.kt:279-286` — low
If `loadRotatedBitmap` returns null the temp JPEG is deleted anyway and no `Intent` is sent: the
sheet stays open, nothing happens, the capture is gone. Needs at least a message, and should not
discard the capture when the decode failed.

### 8. Export annotation thickness — `PhotoAnnotations.kt:133-145` — low
The geometry is right: `aspectFit(w,h,w,h)` gives scale 1 and offset 0, so `displayOf` is the
identity and the export path's assumption holds. But `CanvasDrawScope().draw(density, …)` passes the
*screen* density into a full-resolution bitmap, so 2 dp strokes and 13 sp labels come out about 1.5x
thinner relative to the photo than they looked on screen. Scale the `Density` by
`photo.width / displayedWidth` if the saved image should match what the user saw.

### 9. `Homography.apply` has no `w ≈ 0` guard — `Homography.kt:30-35` — low
A corner dragged into extreme perspective can put an endpoint near the plane's horizon, sending
`Inf`/`NaN` millimetres straight into `formatLength`. `computeHomography` guards degeneracy; `apply`
does not.

### 10. Stale KDoc — medium as a trap rather than a bug
- `Homography.kt:18-21` still says the coordinate space is unspecified and that "screen display
  coordinates work fine — there is no need to map back to the bitmap's native pixel grid, because the
  solve happens fresh from whatever space the quad was last dragged in." All three clauses are now
  false. **This is the comment most likely to talk a future editor into re-introducing the
  measures-wrong bug class.**
- `MagnifierLoupe.kt:49` refers to `QuadCrop.kt`, which does not exist.
- `README.md` §17 claims `camera-capture/` "is never cleaned" — `CameraCapture.kt:124` sweeps the
  directory per capture and `:136` deletes after decode. The same section's over-200-line list still
  names the deleted `PhotoMeasureState.kt` (436), gives `PhotoMeasureScreen.kt` as 410 (now 374), and
  omits `PhotoMeasureChrome.kt` (311), `PhotoMeasureReducers.kt` (243) and `PhotoMeasureViewModel.kt` (224).

### 11. Hardcoded Vietnamese UI strings — `QuadEditorCanvas.kt:102,108`
"cạnh dài" / "cạnh ngắn". Known in §17. Still the only two non-`R.string` user-visible strings in
`photo/**`, and a grep that skips `Canvas` draw calls misses them.

### 12. `decodeReferences` accepts a non-positive side — `ReferenceObjectJson.kt:49-52`
Only NaN is rejected. Unreachable from the sheet, which validates `> 0`, but
`CustomReferenceStore.add`/`update` do not normalise, so validation lives only at the UI edge.

## Checked and correct

- **Coordinates.** Every conversion goes through `ImageFit` at exactly two edges: tap
  (`PhotoQuadCanvas.kt:77`), quad handles (`QuadEditorCanvas.kt:66,83`), draft handles
  (`LineDrawScreen.kt:87,90`), labels (`SegmentLabelOverlay.kt:69`), drawing
  (`PhotoAnnotations.kt:41`), magnifier crop (`MagnifierLoupe.kt:52-59`). No site re-derives the
  letterbox, none stores a display coordinate, and every direction is right.
- **Homography input space** is bitmap-space throughout. `confirmReference` assumes `quad[0]→quad[1]`
  is the long side and all three producers enforce it (`MinAreaRect.kt:63-67`, `HullQuadFit.orient()`,
  `QuadFromEdges.kt:50`). Reversed winding is measurement-safe.
- **`persist`** carries four `String?`/`Boolean` values — nothing that can blow a `Bundle`.
  `State.reference` being derived removes the ordering dependency behind the A4 fallback.
- **Threading.** Decode on IO, segmentation and Canny on Default, prefs on IO, export on Default,
  MediaStore write on IO. Nothing heavy back on Main except item 6 and the documented one-shot
  `UnitPreference.unit` read.
- **Bitmaps.** Scaled copies recycled under `!==` guards; the export bitmap recycled in a `finally`
  and never the displayed one; the segmenter closed in exactly one place.
- **Camera temp file.** The authority check plus a `<cache-path>` scoped to `camera-capture/` means a
  gallery Uri can never be deleted.
- **Drag.** Direct-to-ViewModel in both paths, nothing else quietly became a direct call, and one
  gesture is one undo entry (covered by `PhotoMeasureSegmentsTest`).
- **Reducers** are pure, no aliasing (`toMutableList()` before every mutation), both stacks bounded
  by `takeLast(20)`.

## Unresolved questions

1. Is back-arrow-discards-everything intentional on SCR-23, or should it return to the picker keeping
   the session?
2. Undo depth 20 is asserted nowhere, though both stacks now grow from three different reducers.
3. Should the export match on-screen annotation thickness (item 8)? A product call, not a bug.
