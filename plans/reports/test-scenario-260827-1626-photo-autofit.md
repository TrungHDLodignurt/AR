# Test scenario — photo auto-fit quad

Purpose: find out where auto-fit works, where it must fall back, and whether it ever lies.
Not a regression suite — this is the matrix that decides whether the feature ships as-is.

Device: pass `-s` always. Pixel 6 = `18311FDF60085N`, Joy_4 = `BKB00251473`.
Log tags: `PhotoAutoFit` (detection), `PhotoAutoFitGT` (confirmed quad in bitmap space).

## Setup, once per device

    adb -s <serial> shell setprop log.tag.PhotoAutoFit DEBUG
    adb -s <serial> shell setprop log.tag.PhotoAutoFitGT DEBUG
    adb -s <serial> logcat -c

Before every case, check the log line reads the reference object you actually picked:
`targetRatio=` must match `longSideMm/shortSideMm`. A wrong reference makes the case worthless.

## Part A — where it should work (contrast present)

Shoot each photo yourself, then run the flow: pick reference -> pick photo -> tap the object ->
read the quad -> drag corners to correct it -> confirm.

| # | Object | Surface | Lighting | Expect |
|---|---|---|---|---|
| A1 | Payment card | White desk | Even, indoors | Snaps tight, no dragging needed |
| A2 | Payment card | Dark wood desk | Even | Snaps tight |
| A3 | A4 sheet | Wood desk | Even | Snaps tight |
| A4 | A4 sheet | Wood desk | Strong side light, visible shadow | Snaps to the PAPER, not paper+shadow |
| A5 | Payment card | White desk | Shot at ~30 deg, not straight down | Snaps tight (perspective is supported) |
| A6 | Payment card | White desk | Another card lying 2cm away, parallel | Snaps to the one tapped, not its neighbour |

Record per case: IoU by eye (tight / loose / wrong object / no quad), and how far you had to drag.

## Part B — where it must fall back, not lie

The point of these is NOT that auto-fit succeeds. It is that a wrong quad never gets confirmed
silently. A wrong quad is worse than no quad: the user trusts it, confirms, and every subsequent
measurement is miscalibrated with no warning.

| # | Object | Surface | Expect |
|---|---|---|---|
| B1 | Black phone | Dark desk, phone's shadow touching it | Manual box (null), or a quad clearly wrong ON SCREEN so the user corrects it |
| B2 | Payment card | Patterned/marble surface | Manual box or correct — never a confident wrong box |
| B3 | Payment card | Half in shadow, half in sun | Manual box or correct |
| B4 | Object partly out of frame | any | Manual box |
| B5 | No rectangular object at all (tap on carpet) | - | Manual box |
| B6 | Curved/non-planar object (a book cover bent open) | - | Manual box |

For each B case answer one question: **would a non-technical user notice the box is wrong before
tapping confirm?** If no, that is a bug regardless of what the algorithm did.

## Part C — accuracy after confirm (the thing that actually matters)

Auto-fit is only a convenience; the product promise is the measured length. Verify the numbers.

1. Lay a ruler in the same photo as the reference object, both flat on the same surface.
2. Reference = payment card (85.60 x 53.98 mm, precise and always to hand).
3. Confirm the quad, then measure a segment of known length on the ruler: 50 mm, 100 mm, 200 mm.
4. Record the app's reading vs truth for each.

| Distance from reference object | 50 mm | 100 mm | 200 mm |
|---|---|---|---|
| Segment right next to the card | | | |
| Segment at the far side of the frame | | | |

Expect error to grow with distance from the reference object and with camera tilt — that is inherent
to a single-homography calibration, not a bug. What matters is knowing the size of it. If a 200 mm
span 30 cm away from the card reads 220 mm, the feature needs a stated accuracy claim, not a fix.

## Part D — state survival (the bug fixed this session)

1. Pick reference, pick photo, tap to place the quad, drag a corner.
2. Background the app (Home), open the camera app, come back.
3. Expect: still on the quad-editing screen, same photo, same reference — NOT back at
   "Chon doi tuong tham chieu".
4. Repeat with Developer Options -> "Don't keep activities" ON, which forces the recreation path:

       adb -s <serial> shell settings put global always_finish_activities 1
       # ... run the steps ...
       adb -s <serial> shell settings put global always_finish_activities 0   # ALWAYS reset

5. Note: `ArPhotoActivity` declares `configChanges=orientation|screenSize`, so rotating the device
   does NOT exercise this. Only backgrounding does.

Known gap, out of scope so far: on true process death the photo/quad/segments themselves are still
lost, only the reference choice survives.

## Part E — performance

From the `PhotoAutoFit` log line, time between the tap and the line appearing. Detection runs at
900px on a background dispatcher.

- Pixel 6: expect well under 1s
- Joy_4 (weaker): **unmeasured** — if it exceeds ~1.5s the downscale needs revisiting
- Check the spinner (`isDetectingQuad`) is visible for the whole wait, and the UI never blocks

## What to hand back

Per case: pass/fail, one line of what happened, and the `PhotoAutoFit` log line. For any case where
auto-fit produced a confidently wrong quad, also capture the confirmed `PhotoAutoFitGT` line — those
become new JVM fixtures via the IoU harness, which is how a case stops regressing.
