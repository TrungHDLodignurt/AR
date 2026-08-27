# Brainstorm — Photo auto-fit quad: parallel strategy (Pixel main + Joy_4 agent)

Date: 2026-08-27 16:07 | Branch: feature/photo-reference-measure | Module: AR_feature

## Problem

Photo measure flow: tap on a known-size rectangular reference object must snap a 4-corner quad to
its edges. Pipeline = Canny -> Hough -> `quadFromLines` (pure Kotlin; FastSAM/YOLOv8 rejected,
AGPL-3.0 unusable commercially).

Current failure (main, 640px downscale): valid quad produced but WRONG object. A quad covering
>half the image coincidentally matched ratio 2.18 and won. Root cause is mechanical:

`QuadFromEdges.kt` score = `0.75 * shapeScore + 0.25 * voteScore`. No size term at all.
`MinAreaFractionOfImage = 0.005` exists as a floor; there is **no ceiling** and **no preference for
the smallest quad enclosing the tap**.

## Decisions taken

| Question | Decision |
|---|---|
| Work split | Parallel, but on ONE unified base. Main = algorithm on Pixel; agent = UI bug on Joy_4 |
| Success metric | JVM test over the 3 real photos, IoU vs ground-truth. Device = final verify only, NOT the tuning loop |
| Base downscale | **900px** (unify main onto experiment's value) |
| Ground-truth | User adjusts each photo one at a time; assistant records the corners as test fixtures |
| Agent task | `rememberSaveable` state-restoration bug on `PhotoMeasureScreen.kt` |

### Why 900px over 640px
Short edge of the 15x7 phone at 640px downscale is ~40px -> few edge pixels -> few votes -> the
long remote's edges out-vote it. That is exactly the adversarial case failing. 900px roughly
doubles edge pixels on the short edge; Hough stays sub-second per tap.

### Why device is not the tuning loop
Two branches "both improving" on two different devices with different tap points and different
base params cannot be compared. A deterministic JVM oracle (experiment branch already has 120
green tests) is the only way to know which change earned its keep.

## Rejected approaches

- **Keep 2 independent branches (640 vs 900)** — fastest wall-clock, but base params diverge, so
  neither result is attributable and the merge is painful. Rejected.
- **Sequential (size-constraint first, then decide)** — safest attribution but leaves the second
  device idle. Rejected in favour of parallel-on-one-base.
- **Agent does multi-threshold Canny (spec section 39)** — real value (fixes the low-contrast
  "black bezel on black background" case from the experiment branch) but it touches
  `AutoFitQuad.kt`, the same file main is editing. Deferred, not dropped.
- **Loose assertions instead of IoU** (contains tap + area in [0.5%, 8%] + ratio +/-15% + opposite
  sides <20%) — needs no ground-truth, but cannot distinguish "locked onto the phone" from
  "locked onto the remote" when the remote happens to fit. Rejected as the primary gate; usable as
  a cheap pre-filter.

## File-ownership conflict found

"Clean up TEMPORARY debt" cannot run in parallel:
- diagnostic logs (tag `PhotoAutoFit`) live in `AutoFitQuad.kt` — main is editing that file
- the "Detect again" button + `resetDetection()` + the loosened `if (detected != null)` guard are
  the tuning instruments; removing them now disables the work in progress

=> Debt cleanup is deferred until detection is settled. Agent gets only the `rememberSaveable` fix,
whose file (`PhotoMeasureScreen.kt:105` area) main does not touch.

## Plan of record

### Step 0 — unify base (blocking, main)
1. `DetectionLongSidePx` 640 -> 900 in `AutoFitQuad.kt`
2. Pull the 120 JVM tests from `experiment/hough-pipeline-fixes` @ `9b88231` onto main
3. Re-check the absolute vote floor (`minVotesFloorFraction = 0.06f`) — it was measured at 640px
   and vote counts scale with resolution

### Step 1 — ground-truth fixtures (blocking, needs user)
User supplies the 4 corners of the reference object for each of the 3 photos in
`/tmp/ar-autofit-samples/`, in original 3072x4080 space, one photo at a time. Recorded as test
fixtures + an IoU harness that prints ratio / area / IoU for the top-N candidates.

### Step 2 — size constraint (main, Pixel)
In `QuadFromEdges.kt`:
- add an area **ceiling** (`MaxAreaFractionOfImage`) to `isPlausibleQuad`
- add a **tightness** term to the score: `1 - sqrt(area / imageArea)`. For a 2.4%-of-frame object
  this is ~0.85; for a >half-image quad ~0.29 — wide separation
- reweight, e.g. shape 0.55 / tightness 0.30 / votes 0.15 when the ratio is known
Gate: IoU on all 3 photos must not regress; the target photo must improve.

### Step 3 — multi-threshold Canny (after step 2 lands)
Spec `plans/object_auto_detection_overlay_spec.md` section 39. Try several Canny thresholds per
candidate, keep the best quad. Targets the low-contrast case. Pure Kotlin, no OpenCV.

### Step 4 — parallel, independent (agent, Joy_4)
`PhotoMeasureScreen.kt`: `remember` -> `rememberSaveable` for `referenceChosen`,
`showPickPhotoSheet`, `showReferenceSheet`, `editingReference`, `canvasSize`. Symptom: returning
from the OEM camera resets the flow to "choose reference object".

### Step 5 — debt cleanup (after detection settles)
Remove the `PhotoAutoFit` diagnostic logs, the "Detect again" button, `resetDetection()`, and
restore the strict guard.

## Risks

| Risk | Mitigation |
|---|---|
| Wrong ground-truth invalidates the whole gate | User supplies corners directly, one photo at a time; assistant does not guess |
| 900px shifts vote counts, invalidating tuned thresholds | Treat the vote floor as re-tunable in step 0; the JVM harness makes this cheap |
| Tightness term over-shrinks and locks onto an inner element (e.g. a screen icon rather than the outer bezel) | The area FLOOR plus the opposite-side balance check remain; the low-contrast fix in step 3 is the real answer here |
| Only 3 sample photos — overfitting | Do not tune constants past what 3 photos can justify; treat step 2 weights as coarse |

## Success criteria

- IoU >= 0.7 vs ground-truth on the target photo (light desk, remote + black phone 15x7, tap at bitmap 719,1314)
- No regression on the other 2 photos
- Non-planar / no-object case still returns null (correct behaviour, already holds)
- On-device verify on both Pixel 6 and Joy_4 after the JVM gate is green

## Device notes (carried from handoff)

- `-s` is MANDATORY: `18311FDF60085N` = Pixel 6 (`PhotoAutoFit`), `BKB00251473` = Joy_4 (`PhotoMeasureAutoFit`, needs `setprop log.tag.PhotoMeasureAutoFit DEBUG`)
- Never `gradle installDebug` — picks the wrong device. Use `adb -s <serial> install -r <apk>`
- `expectedAspectRatio` in the log MUST read 2.143 (15/7). Anything else = wrong reference picked, test worthless

## Unresolved questions

1. Exact ground-truth corners for photos 2 and 3 (only the target photo's tap point is known so far)
2. Is 900px actually sub-second on the Joy_4, or only on the Pixel 6? Not measured
3. What IoU threshold counts as "snapped tight enough" for the UI to feel right — 0.7 is a guess, not validated against user perception
