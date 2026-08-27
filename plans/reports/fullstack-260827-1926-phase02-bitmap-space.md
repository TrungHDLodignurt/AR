# Phase 02 — quad/segments/homography moved to bitmap space

Branch `refactor/mvi-alignment`. Spec: `plans/260827-1910-mvi-alignment/phase-02-bitmap-space-coordinates.md`.
Ownership respected: only `photo/**` (main + test) touched. Nothing under `ar/**`, `common/**`, README,
gradle, other plan files.

## Shape after the change

One rule, stated in `PhotoMeasureState`'s class KDoc: **every coordinate the state stores is in the
photo's own bitmap pixel grid.** Two edges convert, nowhere else:

- gesture edge — `Offset.toBitmapSpaceIn(photo, canvas)` (new, wraps `ImageFit.toBitmapSpace`)
- draw edge — `Vec2.toDisplayOffsetIn(photo, canvas)` / `DrawScope.displayOf(point, photo)`
  (both wrap `ImageFit.toDisplaySpace`)

Both helpers live next to the pre-existing `Offset.toVec2`/`Vec2.toOffset` in `PhotoMeasureState.kt`.
They are one-liners over `ImageFit` — no new maths was written anywhere in this phase.

## Per file

**`PhotoMeasureState.kt`** (the bulk)
- `quad: List<Vec2>`, `Segment(start: Vec2, end: Vec2, color)`, `LiveLine(start: Vec2, end: Vec2)`,
  `PhotoSnapshot.quad: List<Vec2>`.
- `revealQuadAt(tapPointBitmapSpace: Vec2, photoWidthPx, photoHeightPx)` — caller converts the tap.
  The aspectFit/tap→bitmap block inside it is gone (the caller does it) and so is the
  bitmap→display mapping that used to be applied to the detector's output: `segmentQuad`/`autoFitQuad`
  already return bitmap-space corners, so `quad = detected` verbatim.
- `moveQuadCorner(index, position: Vec2)`, `moveDraftEndpoint(isStart, position: Vec2)`.
- `placeDraftInitial(photoWidthPx, photoHeightPx)` — fractions of the photo, not of a canvas.
- `commitDrawnSegment()` — **no parameters at all**. Nothing is converted at commit time.
- `draftDistanceMm()` — no parameters. Same one-liner as `distanceMmFor`.
- `distanceMmFor(segment)` — no `toVec2()` round-trip.
- `solveHomography()` feeds `quad` straight to `computeHomography`, so the calibration is
  bitmap-space → mm and does not reference a canvas size anywhere.
- **Deleted:** `onCanvasResized`, `displayCanvas`, `remapToCanvas`. Unused imports (`aspectFit`) removed.

**`PhotoQuadCanvas.kt`** — tap converted on entry (`point.toBitmapSpaceIn(photo, canvasSize)`) then
`revealQuadAt(bitmapTap, photo.width, photo.height)`. Passes `photo` + `canvasSize` down to
`SegmentLabelOverlay`. Class KDoc rewritten (it previously asserted "coordinates throughout are
display pixels").

**`QuadEditorCanvas.kt`** — `quad: List<Vec2>`, `onCornerDrag: (Int, Vec2) -> Unit`. Computes
`displayQuad` once per frame for drawing and for handle placement; converts the drag back to bitmap
space on the way out. Guarded with `canvasSize == IntSize.Zero -> emptyList()` so the quad is never
drawn against an unmeasured canvas (which would collapse all four corners onto the top-left for one
frame).

**`DraggableHandlesOverlay.kt`** — **unchanged**. It is a pure display-space widget (handles,
magnifier); making it bitmap-aware would have pushed conversion past the edge. Its two callers convert.

**`LineDrawScreen.kt`** — `targetCanvasSize` parameter deleted. `placeDraftInitial` now keyed on
`photo` instead of waiting for `canvasSize` to be measured. Handles converted out/in around
`DraggableHandlesOverlay`. `draftDistanceMm()` / `commitDrawnSegment()` argument-free. KDoc paragraph
about remapping between the two screens' canvases replaced.

**`SegmentLabelOverlay.kt`** — now takes `photo` + `canvasSize`; midpoint computed in bitmap space,
projected to display only to position the pill (same conversion the stroke draw uses, so pill and
line cannot drift apart). Early-returns on an unmeasured canvas.

**`PhotoAnnotations.kt`** — `drawSegmentStroke`/`drawSegmentLabel` now take two display `Offset`s
instead of a `LiveLine`; the three public draw entry points project via a new private
`DrawScope.displayOf(point, photo)`. `renderAnnotatedBitmap` lost its `onScreenCanvasSize` parameter
and **`toBitmapSpaceSegment` was deleted** — as the brief suspected, the export path's own
display→bitmap conversion is now a double conversion. See "conversion direction" below for why
removing it is exactly right rather than merely harmless.

**`PhotoMeasureScreen.kt`** — `state.onCanvasResized(it)` call removed from `onSizeChanged`;
`targetCanvasSize` argument to `LineDrawScreen` and `canvasSize` argument to
`performSave`/`renderAnnotatedBitmap` removed.

**`ImageFit.kt`** — KDoc only. `toBitmapSpace` is documented as the gesture edge, `toDisplaySpace` as
the draw edge; the paragraph describing the SCR-23↔SCR-24 canvas bridge and the now-deleted
`PhotoMeasureState.remapToCanvas` is gone.

**`PhotoMeasureSegmentsTest.kt`** — ported to `Vec2`; no `IntSize` and no `Offset` anywhere in the
file now. Two tests added as direction guards (below).

**`RealPhotoAutoFitTest.kt`** — KDoc only: it explained the corrupted ground truth by "quad/segments/
homography are display-space" and told a future reader to recapture "on a build with
`PhotoMeasureState.onCanvasResized`", which no longer exists.

## Conversion direction — where it could have gone either way

1. **The detector output.** `segmentQuad`/`autoFitQuad` take a bitmap-space tap and return bitmap-space
   corners. Old code mapped that output *to display* on assignment. Settled by reading the old
   arithmetic and matching it against `ImageFit`: the old inline block was character-for-character
   `toDisplaySpace`, so deleting it (rather than replacing it) is the correct move — storing `detected`
   verbatim. This is the single highest-risk line in the phase: getting it backwards yields a quad
   that renders somewhere plausible on a roughly-square photo and measures wrong.
2. **The export path.** `renderAnnotatedBitmap` draws into a canvas that IS the photo's resolution, so
   `aspectFit(w,h,w,h)` → scale 1, offsets 0, i.e. `toDisplaySpace` is the identity there. That is why
   the same `displayOf` call is correct both on screen and in the PNG, and why `toBitmapSpaceSegment`
   had to go: keeping it would have applied a second, genuinely wrong conversion.
3. **`placeDraftInitial` / the `revealQuadAt` fallback box.** These were fractions of the *canvas*
   (0.2 / 0.22 / 0.14) and are now the same fractions of the *photo*. Not a coordinate conversion, a
   deliberate re-interpretation: the visible result is a slightly larger initial box/line (the photo
   box is ≤ the canvas), and it is now independent of screen size. Called out because it will show up
   in a visual diff and is not a bug.
4. **`revealQuadAt` keeps `photoWidthPx/photoHeightPx` parameters** even though it could read them off
   the stored `Bitmap`, because the no-photo path is what makes the unit test able to calibrate at all
   on the JVM (no `android.graphics.Bitmap`). Same reason `placeDraftInitial` keeps its two Floats.

## Verify — done

**Compile + tests are GREEN for this phase.** Verified in an isolated git worktree, because the main
tree could not compile at all while this ran: the parallel session is mid-move of `ArSessionState` /
`MeasureState` -> `MeasureViewModel`, and every `e:` line in the main tree is under
`ar/presentation/**` (`ArSessionState`, `tracking`, `lastFrameAtMillis`, `depthSupported`, `noteFrame`,
`trackingFailure` unresolved). Zero errors under `photo/**` in any run of the main tree.

How the isolated check was done (repeatable):
1. `git worktree add --detach <tmp> HEAD` — HEAD `3f7e5fb`, i.e. clean `ar/**`. No commit, no
   checkout, no stash in the shared tree; the worktree was removed afterwards
   (`git worktree list` back to normal, only the pre-existing `experiment/hough-pipeline-fixes` one).
2. copied `local.properties` plus the 10 changed `photo/**` files into it.
3. `:AR_feature:compileDebugKotlin` -> **BUILD SUCCESSFUL**. Confirmed it really compiled my code, not
   a stale cache hit: `javap` on the output shows `toBitmapSpaceIn` / `toDisplayOffsetIn` in
   `PhotoMeasureStateKt`.
4. `:AR_feature:testDebugUnitTest --rerun-tasks` -> **BUILD SUCCESSFUL**; XML results:
   **174 tests, 0 failures/errors, 2 skipped** (172 baseline + the 2 new direction-guard tests; the
   2 skips are the pre-existing `RealPhotoAutoFitTest` `@Ignore`s).

Still not verified: anything on a device. The relayout check is not reachable without a human driving
the photo picker, so it is in the outstanding list rather than done.

## Verify — OUTSTANDING (execute cold; nobody has run this yet)

Serials change; run `adb devices` and always pass `-s`. Never `./gradlew installDebug` — build the APK
and `adb -s <serial> install -r <path>`.

**A. Sign/direction check, before any measuring — 60 seconds, catches the failure mode.**
1. Reference object → pick a photo whose subject is clearly **off-centre and near one edge**
   (a landscape photo works best: the letterbox bars are then large, so a display/bitmap mix-up is
   visible instead of subtle). A near-square photo hides this bug — do not use one.
2. Tap the object. The quad must appear **under your finger**, on the object. If it appears shifted
   toward the centre of the screen, or scaled down toward the top-left, the tap conversion
   (`toBitmapSpaceIn` in `PhotoQuadCanvas`) or the detector-output assignment in `revealQuadAt` is
   inverted. Magnitude of the offset ≈ the letterbox bar size — that is the tell.
3. Drag one corner. The handle must stay exactly under the finger with no drift and no acceleration.
   Drift that grows with distance from the photo's centre = a scale applied in the wrong direction;
   constant offset = a missing letterbox offset.

**B. The measurement gate (the number that proves it).**
4. Card (or A4) as reference, a ruler lying in the same plane, in frame.
5. Tap object → adjust corners onto the reference's real edges → confirm.
6. Draw a segment along exactly 100 mm of the ruler. Read the value.
   - **Pass:** within a few mm of 100. Repeat at 50 and 200 mm; error should stay roughly
     *proportional*, not constant.
   - **A constant offset across all three** = a letterbox offset lost in one direction.
   - **A consistent multiplicative error** (e.g. everything reads ~0.6x or ~1.7x) = display/bitmap
     scale applied once too few or once too many times. That is the "quad looks plausible, measures
     wrong" case; the ratio will be close to `fit.width / canvasWidth` or its inverse.
7. Record the three numbers. They must not be worse than the pre-change reading.

**C. Relayout — the actual point of the phase.**
8. With the quad placed but not yet confirmed, force a relayout: rotate the device, or trigger the
   button appear/disappear that caused the original bug (place a quad so the confirm button appears).
   The quad must stay **on the object**, and after rotation must still be on the same part of the
   photo. There is no remap code left, so if this fails the conversion is wrong, not the remap.
9. Confirm after the rotation and re-measure the 100 mm: the number must match step 6. Calibration no
   longer depends on canvas size, so a difference here means the homography is being solved from
   something screen-shaped.

**D. Draft/committed consistency and save.**
10. On the draw screen, note the live length before pressing ✓; after committing, the label on the
    previous screen must show **the same number**. The two screens have different-sized photo boxes
    and no longer remap between them — a mismatch means a conversion crept back into
    `commitDrawnSegment` (the two new unit tests cover this on the JVM; this is the device echo).
11. Save. Open the PNG at full size: every line must sit on the same photo feature it did on screen,
    and each label on its own line. The export now draws stored coordinates unconverted, so a shifted
    or shrunken annotation set means `displayOf` is not the identity at photo resolution.
12. Undo/redo across a corner drag and a segment delete — unchanged by this phase, but the snapshot
    type changed, so worth one pass.

## Things I wanted to touch and did not (outside ownership)

- `AR_feature/README.md` §15, last paragraph, is now false: it states quad/segments/homography are
  display-space and that `onCanvasResized` re-projects them. Phase 05 rewrites §15 anyway; flagging so
  it is not missed. §15 also lists `canvasSize` among the `rememberSaveable` patches — see below.
- `PhotoMeasureScreen.canvasSize` is now **write-only**: `PhotoQuadCanvas` and `LineDrawScreen` each
  measure their own box. Kept, with a comment saying exactly that, because the brief said to keep it.
  It and `IntSizeSaver` are deletable; phase 03 should decide.

## Unresolved

- Nothing has been seen on a device. The unit tests cover commit/undo/redo/distance arithmetic and
  the two direction guards, but they cannot see a wrong conversion at a *draw or gesture edge* —
  those edges have no test coverage and no automated way to get any. Section "Verify — OUTSTANDING"
  is the whole safety net for them.
- The main tree still will not compile until the parallel `ar/**` session lands. Re-run
  `:AR_feature:compileDebugKotlin` + `:AR_feature:testDebugUnitTest` there once it does; expect
  174/0/2.
- `DraggableHandlesOverlay` and `MagnifierLoupe` stayed display-space on purpose. If a later phase
  wants "no display coordinates anywhere in presentation", those two are the remaining holdouts and
  the decision should be explicit rather than drifting.
- The initial-box/initial-line size changed slightly (item 3 above). Nobody has looked at it on a
  device; if it reads too large on a very wide photo, the fractions are the knob.
