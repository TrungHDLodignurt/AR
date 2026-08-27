# Photo auto-fit: what is actually broken, and the library options

Date: 2026-08-27 | Branch: feature/photo-reference-measure | Module: AR_feature

## TL;DR

The tuning was aimed at the wrong half of the pipeline. Canny+Hough+known-ratio is a sound approach
and is NOT the bug. On the gate photo, three of the reference object's four edges are not present in
the pixels at all, so no scoring rule can succeed. Measured ceiling over every candidate: 0.376 IoU.

## What was measured (all reproducible in 7s via the new JVM harness)

Gate photo: `PXL_20260825_123431190.jpg`, app-decoded at 1542x2048, detection at 677x900.
Reference object: black phone, 150.0 x 70.0 mm, ratio 2.1429. Tap (591, 1165).
Ground truth (hand-dragged on device, then confirmed): `(361.8,873.8) (1083.7,922.8) (1109.1,1249.8) (329.2,1248.1)`

### Per-edge visibility

| GT edge | length | Canny coverage | dLuminance | dMaxChannel | dChroma |
|---|---|---|---|---|---|
| 0 top (long) | 318 | 49% | 76 | 81 | 11.6 |
| 1 right (short) | 144 | 17% | 6.5 | 8.2 | 2.2 |
| 2 bottom (long) | 343 | **0%** | **1.0** | **1.0** | **0.0** |
| 3 left (short) | 165 | **3%** | 1.1 | 1.2 | 0.2 |

Edge 2 raw pixels: inside RGB(36,36,41) vs outside RGB(35,35,40).

### Perpendicular luminance profiles

- Edge 2: flat 31-38 across +/-70px. No transition anywhere near it.
- Edge 3: nearest real transition is **49px away** (187 -> 82), not at the GT line.

Reading: the dark phone body merges into its own dark shadow into one blob. The blob's boundary is
NOT the phone's boundary. The user drew the outline they could see on screen; that outline has no
corresponding gradient. Colour does not help either (dChroma 0.0).

### Ceilings

- Best IoU over all 289 candidates with production settings: **0.376**
- Chosen candidate: **0.263**
- 27-point sweep of Hough suppression knobs (maxLines 40/80/160 x suppressTheta 10/5/3 deg x
  suppressRho 0.03/0.012/0.005): ceiling never passed **0.43**

So: enumeration fault, not ranking fault. Re-weighting the score is provably futile here.

## Correction to earlier numbers

Earlier reports of 0.49 / 0.42 IoU came from a sign error in the segment/line intersection inside the
new `quadIoU` helper, which returned an impossible 2.338 on one input. Fixed and now covered by 7
hand-computable tests. The real figure is **0.263**.

## Library options

| Option | Licence | Size cost | Solves this photo? |
|---|---|---|---|
| ML Kit Subject Segmentation | Google ML Kit terms, free, commercial OK | 0 (Play Services download) | **Plausibly** |
| OpenCV Android (`org.opencv:opencv`) | Apache 2.0 | +8-10 MB per ABI (42 MB universal) | **No** |
| FastSAM (what ARuler uses) | AGPL-3.0 | - | Yes, but unusable commercially |

**OpenCV does not help.** Its `Canny`/`HoughLines` are the same algorithms on the same pixels.
`GrabCut` needs a seed and would swallow the shadow, whose pixels are identical to the phone's.

**ML Kit Subject Segmentation is the candidate.** It is the licence-clean equivalent of what ARuler
gets from FastSAM: a learned model with object priors, so it can infer a plausible boundary from
context instead of requiring a gradient. Per-subject masks, on-device, ~200 ms on a Pixel 7 Pro,
static images only, API 24+, free. Take the mask, fit a min-area rectangle, done.

Costs to weigh: it is **beta** (Google warns of backward-incompatible change), and it needs Google
Play Services, so a non-GMS device still needs the Hough path as fallback. Keeping both is not waste
— Hough is better where edges DO exist, being exact rather than approximate.

## Infrastructure landed this session

- `RealPhotoAutoFitTest` — runs the real pipeline on a real photo, graded by IoU. Fixture committed
  at `AR_feature/src/test/resources/autofit-samples/` (302 KB, stored at the size the app decodes to)
- `QuadIoU.kt` + `QuadIoUTest` — Sutherland-Hodgman IoU, 7 self-checks
- `quadCandidates` split from `rankScore` — lets a test tell an enumeration fault from a ranking fault
- `detectQuadInGrayscale` (`AutoFitDetection.kt`) split out of the Bitmap layer so JVM tests drive
  production code
- Hough suppression radii promoted to parameters (`suppressThetaDegrees`, `suppressRhoFraction`)
- Base unified: 900px downscale, absolute vote floor on by default, opposite-side balance check
  unconditional
- Size/tightness term added to ranking (fixes a real separate bug: a >half-image quad winning on a
  coincidental ratio match)
- Suite: 133 tests, 0 failures, 1 skipped (the 0.85 gate, `@Ignore`d with the measurement above)

## Unresolved questions

1. Does ML Kit Subject Segmentation actually segment a black phone on a dark desk? Unverified — needs
   a spike, not an argument.
2. Is the gate photo representative of real usage? If users mostly shoot a card or A4 on a light
   surface, the current pipeline may already be adequate and this photo is a fallback case.
3. Should a low-confidence detection return null (manual box) rather than a wrong quad? A wrong quad
   is worse than none: the user trusts it, confirms, and every measurement is silently miscalibrated.
   Not currently gated on confidence at all.
4. The ground truth on edges 2/3 does not sit on any visible boundary. If it is kept, no detector can
   ever match it; if it is moved to the blob boundary, the calibration it implies is wrong by the
   shadow's width.

---

# CORRECTION, same day 17:00 — the ground truth was wrong, not the photo

Everything above under "Per-edge visibility", "Perpendicular luminance profiles" and "Ceilings" was
measured correctly **against a bad reference**. The conclusion drawn from it — that this photo is
unwinnable because the object's boundary is not in the pixels — is retracted.

## The actual bug: display-space state survives a relayout

`quad`, `segments` and `homography` are all stored in display-space pixels. The quad-editing screen
grows a button as soon as a quad exists (the TEMPORARY "Detect again" tuning button, plus the
instruction text changing length), the Column reflows, the photo re-aspect-fits into a shorter box —
and all three stay at their old coordinates. On a 2048px-tall photo that is a ~180px vertical shift.

Consequences, in the order they bit:

1. The detected quad is drawn off the object, which is what "vẫn chả đúng gì cả" was.
2. The user dragged the corners to fit the photo *as displayed after* the reflow; the diagnostic
   converted them to bitmap space using the canvas captured *before* it. Hence a ground truth
   ~180px out.
3. Every IoU measured against that ground truth understated the detector, and the "no gradient
   anywhere" readings were taken along lines that mostly do not lie on the object at all.

Fixed by `PhotoMeasureState.onCanvasResized`: remaps quad and segments through the photo's own pixel
grid and re-solves the homography whenever the canvas size changes. Note this is not merely a
debug-button problem — any relayout had the same effect.

## ML Kit was right

Mean luminance contrast across each edge, sampled +/-6px, same photo:

| edge | hand-captured "ground truth" | ML Kit segmentation |
|---|---|---|
| 0 | 60.7 | 133.7 |
| 1 | 4.2 | 30.6 |
| 2 | 0.8 | 30.4 |
| 3 | 1.8 | 31.6 |

ML Kit's quad lies on a real boundary on all four sides; the hand-captured one on exactly one. From
the device log it reported ratio 2.055 against target 2.143, and separately segmented the TV remote
at 3.640 — so it distinguishes the two objects the edge pipeline kept confusing.

## What the test session could and could not show

From 16:56:36 the log reads `target=1.000`: a square reference object had been selected while the
15x7 phone was being tapped. Every tap after that was rejected on ratio and fell back to Hough, so
those results say nothing about segmentation. The first tap at 16:55:32 also failed with "Waiting for
the subject segmentation optional module to be downloaded" — expected once, on first use.

## Revised state of the fixtures

- The `finds the phone...` test in `RealPhotoAutoFitTest` is `@Ignore`d citing reasoning now retracted.
- `BaselineIoU = 0.26` is measured against a bad ground truth and means nothing.
- A fresh capture is needed on a build with `onCanvasResized`. Any new capture must be sanity-checked
  by measuring per-edge contrast before it is trusted as a fixture — that check is what caught this.

## Added to the unresolved list

5. Was the edge pipeline ever as bad as measured? Its 0.263 was scored against the bad ground truth
   too, so the comparison that motivated switching to segmentation is itself unverified. Segmentation
   still looks like the right primary detector on the contrast evidence, but "Hough is hopeless here"
   is no longer established.
