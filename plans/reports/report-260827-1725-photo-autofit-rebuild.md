# Picture Measure auto-fit — rebuilt on segmentation, scaffolding removed

Date: 2026-08-27 | Branch: feature/photo-reference-measure | Module: AR_feature
Supersedes the detector conclusions in `report-260827-1626-autofit-diagnosis-and-library-options.md`
(read its CORRECTION section first — the IoU figures in its body are retracted).

## Outcome

Tapping the reference object now fits a quad that follows the object's real perspective. User
confirmed on device: accurate, no layout jump. 172 JVM tests, 0 failures, 2 skipped. Debug/release
builds both pass.

## What the pipeline is now

    tap -> ML Kit subject segmentation -> flood fill from the tap -> convex hull
        -> max-area 4 vertices -> per-side line fit -> intersect -> plausibility gate
        -> (fail) Canny+Hough -> (fail) manual four-corner box

Detector order is deliberate. An edge detector can only find a boundary that exists as a gradient;
segmentation decides which pixels are the object regardless. Hough is kept, not deleted: it is more
precise wherever edges DO have contrast, and it is the only detector on a device without Play
Services.

## The three real bugs found

**1. Display-space state survived relayout.** `quad`, `segments` and `homography` are all stored in
display-space pixels. The screen grows a button once a quad exists, the Column reflows, the photo
re-aspect-fits into a shorter box — and all three stayed at their old coordinates, ~180px out on a
2048px-tall photo. This is what "the box doesn't sit on the object" was, for three sessions.
Fixed by `PhotoMeasureState.onCanvasResized`, which remaps all three and re-solves the homography.

**2. It also corrupted the ground truth.** The hand-dragged reference quad was captured against the
post-reflow layout but converted using the pre-reflow canvas. Every IoU measured against it was
meaningless, including the "this photo is unwinnable" conclusion. Caught by measuring per-edge
luminance contrast: the captured quad sat on a real boundary on 1 side of 4 (60.7 / 4.2 / 0.8 / 1.8),
the detector's on all 4 (133.7 / 30.6 / 30.4 / 31.6).

**3. A rectangle cannot represent a photograph.** `minAreaRect` returns a rotated *rectangle*, so
opposite sides are always equal — but a rectangle shot at an angle projects to a trapezoid, and the
homography wants exactly those skewed corners. Replaced with `quadFromHull`.

## New files

| File | Lines | What it does |
|---|---|---|
| `domain/imaging/HullQuadFit.kt` | 205 | General-quad fit: max-area 4 hull vertices, then per-side total-least-squares line fit and intersection |
| `domain/imaging/MinAreaRect.kt` | 110 | Convex hull + rotating calipers. Now the fallback when a hull is too ragged to fit four sides to |
| `domain/imaging/MaskBlob.kt` | 115 | Confidence mask -> 4-connected flood fill from the tap -> boundary -> quad |
| `domain/imaging/ReferenceQuadPlausibility.kt` | 139 | The shared accept/reject gate |
| `domain/imaging/AutoFitDetection.kt` | 73 | Resolution-independent Canny+Hough core, split out of the Bitmap layer so JVM tests drive production code |
| `data/SegmentQuad.kt` | 131 | ML Kit call, downscale, mask extraction |
| `test/.../QuadIoU.kt` | - | Sutherland-Hodgman IoU for grading real-photo runs |

## The plausibility gate

One function for both detectors. Each previously had its own constants, disagreeing on every
threshold with no reason recorded; a user cannot tell which detector produced a box, so they must not
be judged by different rules.

| Rule | Value | Why this value |
|---|---|---|
| Area floor | 0.1% of frame | A payment card at a normal shooting distance covers ~1.4% of frame, under 4% even close up. A floor of "a few percent" silently rejects the commonest reference object there is |
| Area ceiling | 85% | Backstop only; a whole-scene segmentation lands at 95-100% and the border rule catches it anyway. Left generous — an A4 sheet can legitimately fill the frame |
| Corner at/outside frame | reject (0.5% inset) | Catches two failures at once: whole-scene segmentation, and an object CUT OFF by the frame — the latter can pass every area bound while the length it implies is simply not the object's |
| Opposite-side mismatch | <= 50% | Raised from 30%, which predated perspective support and rejected the very case it was added for (near edge 300 vs far edge 180 is a real 40%) |
| Aspect deviation | 0.35 Hough / 0.5 segmentation | Per-path: Hough intersects exact lines, a mask boundary is approximate |
| Convex, non-self-intersecting, finite | required | - |

**What the gate does not do.** It rejects only the obviously implausible. A quad of the right size and
right proportion locked onto the wrong object passes every check, and nothing threshold-shaped will
catch it — only the user seeing the box before confirming. That is the argument for keeping every
bound generous: tightening costs real rejections and buys almost nothing against the failure that
actually hurts.

## Dependency added

`com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1`

- No APK weight — the model arrives through Play Services
- Licence: Google ML Kit terms, free, commercial use fine. This is the point: ARuler uses FastSAM,
  which is built on YOLOv8 and therefore AGPL-3.0 and unusable here
- **Beta.** Google warns of backward-incompatible change
- Absent on non-GMS devices. Both are why Hough stays
- `assembleRelease` passes; see README §13 for the R8 mapping detail

## Scaffolding removed

`PhotoAutoFit` per-detection logs and the 16-line-per-tap Hough dump; the `PhotoAutoFitGT`
ground-truth dumper; the "Detect again" button and `resetDetection()`; the layout-jump verification
log; a stale TEMPORARY comment on a branch that was ordinary logic. No `TEMPORARY` marker remains
under `photo/`.

Two `Log.d` calls kept in `SegmentQuad.kt`, deliberately: both are silent-fallback paths, so without
them an unavailable model is indistinguishable in the field from a model that ran and found nothing.

**Consequence worth stating: capturing a new ground truth now requires re-adding the `PhotoAutoFitGT`
dumper.** It is one revert away in git history. `RealPhotoAutoFitTest` keeps the harness and the
302 KB fixture but has both assertions `@Ignore`d, with the kdoc explaining what to replace and the
contrast check to run on any new capture before trusting it.

## Docs updated

`AR_feature/README.md` — §2 catalog entry, §6 the new manifest meta-data, §13 the R8 finding,
§14 test count 102 -> 172, §16 rewritten Picture Measure limitations plus the file-size list.

## Unresolved questions

1. No valid real-photo ground truth exists. Every gate threshold is reasoned from geometry and
   synthetic tests, not validated against real data.
2. Is a rejection silent, or should the instruction text say the object was not recognised? Currently
   silent — the user cannot tell a fallback from a poor detection.
3. Untested: a non-GMS device, and a release build on a device (the optional-module download path is
   the least exercised part of the new dependency).
4. `PhotoMeasureState.kt` (436) and `PhotoMeasureScreen.kt` (410) are now the worst offenders against
   the repo's ~200-line guideline and are the next split candidates.
5. Part C of `test-scenario-260827-1626-photo-autofit.md` — measured accuracy against a ruler — has
   still never been run. Auto-fit is a convenience; that scenario tests the actual product promise.
