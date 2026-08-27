# Phase 02 — quad/segments/homography in bitmap space

Context: [plan.md](plan.md) · README §15
Priority: before any conversion. Status: not started.

## Why first

`quad`, `segments` and `homography` are stored in **display-space pixels**, so any relayout
invalidates all three at once. `PhotoMeasureState.onCanvasResized` re-projects them and re-solves the
calibration — a patch for a problem that would not exist if coordinates were held in the photo's own
pixel grid and projected at draw time.

It comes before the MVI work because it changes the shape of the state. Freezing display-space
coordinates into an immutable `data class State` and fixing it afterwards costs twice.

Proof it is real, from today: the "Detect again" button appearing shifted the canvas, and the quad
stayed behind — about 180 px on a 2048 px-tall photo. It also corrupted a hand-captured ground truth,
which then wasted a session's tuning against a bad reference.

## Design

- `quad: List<Vec2>` in bitmap pixels. Same for `Segment.start/end` and the homography's source points.
- `ImageFit.toDisplaySpace` / `toBitmapSpace` already exist — use them at the draw and gesture edges
  only.
- Drag handlers convert the incoming display-space `Offset` once, on entry.
- `confirmReference` solves the homography from bitmap-space corners, so calibration stops depending
  on canvas size at all.
- Delete `onCanvasResized` and the `displayCanvas` field once nothing reads them.

## Files

Modify: `photo/presentation/PhotoMeasureState.kt`, `PhotoQuadCanvas.kt`, `QuadEditorCanvas.kt`,
`DraggableHandlesOverlay.kt`, `LineDrawScreen.kt`, `SegmentLabelOverlay.kt`, `PhotoAnnotations.kt`,
`PhotoMeasureScreen.kt` (save path).

## Todo

- [ ] Convert `quad` to bitmap space; fix every reader
- [ ] Convert `Segment` and the draft line
- [ ] Solve the homography from bitmap-space corners
- [ ] Delete `onCanvasResized`, `displayCanvas`, and the remap helper if unused
- [ ] Check the save path (`renderAnnotatedBitmap`) still lands annotations correctly
- [ ] `compileDebugKotlin` + `testDebugUnitTest`

## Gate — on device, both handsets

1. Measure a known length: card as reference, ruler in frame, read 50/100/200 mm. Record the numbers;
   they must not get worse than the pre-change reading.
2. Rotate/relayout mid-flow — the quad must stay on the object with no remap code present.
3. Save an annotated photo and confirm labels sit on the right lines.

## Risk

Every coordinate reader is a place to get the direction of the conversion backwards, and there are no
tests over any of them. Convert one consumer at a time and compile between each, rather than a single
sweep.
