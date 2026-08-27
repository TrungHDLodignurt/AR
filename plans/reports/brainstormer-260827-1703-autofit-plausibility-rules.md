# Brainstorm — plausibility rules for photo auto-fit

Date: 2026-08-27 | Branch: feature/photo-reference-measure | Module: AR_feature

## Problem

Auto-fit now works (ML Kit subject segmentation -> mask -> general quad fit, Canny+Hough as
fallback). What is missing is a consistent rule for rejecting an implausible detection so the user
gets the draggable default box instead of a wrong quad.

A wrong quad is worse than no quad: the user trusts it, confirms, and every measurement afterwards is
silently miscalibrated with nothing on screen to suggest it.

## Existing guards (before this change)

| | floor | ceiling | aspect dev | opposite-side mismatch |
|---|---|---|---|---|
| Hough (`quadFromLines`) | 0.5% of frame | 60% of frame | 0.35 (~1.4x) | 30% |
| Segmentation (`quadFromMask` + `segmentQuad`) | 0.2% of frame (blob pixels) | **none** | 0.5 (~1.65x) | none |

The gap the user spotted is real: the segmentation path has no upper bound at all.

## Decisions

| Question | Decision |
|---|---|
| Lower bound | **~0.3% of frame. NOT 5%.** |
| Upper bound | **Corner touching/outside the frame, OR area over ~50%** |
| On reject | **Straight to the default draggable box** (do not chain to the other detector) |

### Why 5% was rejected as a floor

A payment card is 85.6 x 53.98 mm = 4622 mm^2. A normal photo of a desk spans roughly 500 mm across,
so the frame covers about 333,500 mm^2 — the card is **1.4%** of it. Even a close shot spanning
300 mm leaves it at **3.9%**. A 5% floor would reject the single most common reference object in
almost every photo. The measured example that motivated 5% (a 150x70 mm phone at 8.3% of frame) is an
unusually large reference object, not the typical one.

### Why "touches the frame border" beats a bare percentage as the ceiling

It catches two distinct failures with one test:
1. Segmentation treating the whole scene as foreground — the quad reaches the image edges.
2. The reference object being **cut off** by the frame. Its visible part can be well under any
   percentage ceiling while the measured length is simply wrong, which a percentage never catches.

Percentage stays as a second condition for the case where a large but interior region is grabbed.

### On reject, do not chain to the other detector

Chosen deliberately over "segmentation -> Hough -> default". Rationale: a photo where segmentation
returns something implausible is a hard photo, and spending another ~1s of Hough to probably produce
another implausible quad delays the user reaching the box they are going to drag anyway.

**Important distinction that must be preserved:** segmentation being *unavailable* (model not yet
downloaded, no Play Services, exception) is NOT a reject. That path must still fall through to Hough,
otherwise every non-GMS device loses auto-fit entirely.

## Bug found while reviewing the rules

`hasBalancedOppositeSides` rejects a quad whose opposite sides differ by more than **30%**. The
general-quad fit added earlier today exists precisely so a perspective trapezoid can be represented —
and the trapezoid in its own test (near edge 300, far edge 180) differs by **40%**. So a rule written
before perspective was supported now rejects exactly the case perspective support was added for.

Needs raising to about 45%. Above that the shape stops being a plausible rectangle-under-perspective
and starts being four unrelated lines, which is what the check was for originally.

## Proposed shared gate

One function used by both detectors, rather than each keeping its own constants — the two currently
disagree on every threshold for no articulated reason.

    isPlausibleReferenceQuad(quad, imageWidth, imageHeight, targetAspectRatio): Boolean

1. Four finite corners, convex, non-degenerate area
2. Every corner inside the frame, inset by ~0.5% of the shorter side
3. Area within [0.3%, 50%] of the frame
4. Opposite-side mismatch <= 45% (raised from 30%)
5. Aspect deviation within tolerance when the ratio is known — kept per-path: 0.35 for Hough (exact
   lines, so a tight bound is fair), 0.5 for segmentation (approximate mask boundary)

## What these rules do NOT catch

They only reject the **obviously** implausible. The dangerous case — a quad of the right size and the
right proportion locked onto the wrong object — passes every one of them. Nothing threshold-shaped
will catch it. That case has to be handled by the user seeing the box before confirming, which the
flow already requires.

This is the argument for keeping every bound generous: a tight bound costs real rejections of correct
detections, and buys almost nothing against the failure that actually hurts.

## Risks

| Risk | Mitigation |
|---|---|
| Border rule falsely rejects an object legitimately placed near the frame edge | Small inset (0.5%), not a wide margin |
| 50% ceiling rejects a legitimately close-up A4 sheet | A4 filling >50% of frame is possible; if it shows up in testing, raise to 60% (the old Hough value) rather than adding a special case |
| Raising opposite-side mismatch to 45% admits more junk on the Hough path | Aspect-ratio and area checks still apply; the JVM harness measures whether it regresses |
| Thresholds tuned on one photo | Only one ground-truth photo exists, and it is currently invalid. Treat all five numbers as provisional |

## Success criteria

- Card-sized reference object at normal distance is NOT rejected (the 5% trap)
- A whole-scene segmentation result IS rejected
- A perspective trapezoid at 40% opposite-side mismatch is NOT rejected
- Non-GMS path still reaches Hough
- No regression in the existing 164 JVM tests

## Unresolved questions

1. No valid ground-truth photo exists yet, so none of these numbers can be validated against real
   data — only against synthetic tests and reasoning.
2. Should a reject be silent, or should the instruction text say the object was not recognised? The
   user chose "straight to default box" without deciding this. Silent means the user cannot tell a
   fallback from a detection that happened to be poor.
3. Is 50% or 60% right for the ceiling? Untested against a close-up A4.
