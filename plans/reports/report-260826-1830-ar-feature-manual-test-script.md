---
title: AR_feature — consolidated manual test script
date: 2026-08-26
status: awaiting-human-run
device: Pixel 6 (18311FDF60085N), Android 16
---

# Manual test script — the parts no script could verify

Everything else is already verified: 102 JVM tests, `assembleDebug` + `assembleRelease` green, R8
audited on-device, 10/10 design screens matched, full photo path driven end-to-end via adb, reference
CRUD + JSON migration proven against a hand-written legacy prefs file.

**What is left needs a human holding the phone at a real textured surface** — a desk, a patterned
floor, a wall with visible grain. A blank white wall will not track; the reticle will stay hollow and
every step below will look like a failure when it isn't.

Install: `adb -s 18311FDF60085N install -r app/build/outputs/apk/debug/app-debug.apk`

---

## 1. Plane survival across a tool swap — RUN THIS FIRST

The single most consequential unverified behaviour. The whole reason the design uses a bottom sheet
instead of separate screens is that switching tools keeps the ARCore session alive. Scripted testing
proved no `Engine`/`Session` is recreated across 30 swaps, but never had a real tracked plane to lose.

1. Force-stop the app, launch, tap **AR Measure**. Wait for "Getting the camera ready…" to clear
   (~2s). It must **not** reappear later.
2. Aim at a textured surface until the hint reads "Tap + to add a point" and the reticle goes solid.
   Tap **+** twice. A solid line with a length label appears.
3. Open the tool sheet, tap **Box**. The Distance points disappear from view (Box has its own
   overlay) — no crash, no black flash.
4. Reopen the sheet, tap **Distance**. **Both points and the line must be back in exactly the same
   place, with the same number.**

**FAIL** = points gone · length changed · black screen flash · you have to re-scan the surface.

If this fails, stop and report it — the bottom-sheet design premise is what breaks, not a small bug.

## 2. Half-drawn shape survives a swap

1. In **Box**, tap **+** to place the origin, then aim to draw the first edge — **do not** tap + again.
2. Sheet → **Cylinder** → **Distance** → back to **Box**.
3. The half-drawn box must be exactly where you left it, still asking for the second edge.

**FAIL** = it reset to "tap + to place the corner".

## 3. Post-swap commit guard

Each tool owns its own steadiness gate; a stale reading from before the swap must not be committable.

1. Right after any tool swap, re-aim at the same steady surface and watch the **+** button.
2. It must stay dim for a brief beat even though the reticle already looks solid, then enable.

**FAIL** = **+** is tappable the instant you swap → a pre-swap reading would commit as a real point.

## 4. Undo / redo on the AR path

1. **Distance**: place 3 points. Tap **↩** — the last point and its segment go. Tap **↪** — it comes
   back, same position, same number.
2. **Box**: complete a full 3-tap shape (origin → edge → edge → height). Undo steps back one phase at
   a time; redo restores each. **Cylinder**: same with centre → radius → height.

**FAIL** = redo does nothing · a restored point lands somewhere else · a number changes.

## 5. Measurement accuracy

Measure something you can check with a real tape measure — a table edge, a door width, a box.

1. **Distance** on a flat surface, ~50–100cm span.
2. **Box** on a real box; **Cylinder** on a mug or bottle.
3. Switch units (cm → m → inch → ft) and confirm the number converts correctly rather than changing.

Note the error you see. ARCore on a good surface is typically within a few percent; wildly wrong
numbers point at a plane being mis-detected, not at the maths.

## 6. Rapid swap stress

Swap Distance → Box → Cylinder about 20 times quickly.

**FAIL** = any black screen, freeze, or the app dropping back to the hub.

## 7. Camera capture on the photo path

This crashed until commit `384e1ce` (a missing `FILE_PROVIDER_PATHS` manifest entry) — worth one
human pass now that it is fixed.

1. **Picture Measure** → pick a reference object → **Take photo**.
2. Photograph the reference object lying next to the thing you want to measure.
3. Adjust the quad onto the reference → confirm → drag a measurement line → read the number.
4. Tap **Save** and confirm the image appears in the gallery with the line drawn on it, no watermark.

## 8. Cold launch straight into Box

Force-stop, launch, and go into **Box** as the very first AR screen. No black screen; chrome renders;
the warm-up banner appears once and clears.

---

## Cannot be tested here at all

**Behaviour on an ARCore-unsupported device** — the AR tools should hide while Photo measure keeps
working. Only a Pixel 6 (ARCore-capable) is available; the other attached device belongs to another
team. This matters more than it sounds: two of the team's own test devices (POCO X7, Galaxy A07) fail
ARCore certification, per `report-260824-1520-arcore-hardware-limitation.md`. Worth running on one of
those before shipping.

## Known cosmetic debt — do not report these as bugs

Deferred deliberately, the user is handling them:

1. Icons are bare Unicode glyphs (`↔ □ ○ ▦ ✎ ⌄ 🗑 ← ×`) instead of real icons; `🗑` renders as a
   colour emoji.
2. Dimension labels repeat the unit: "21 cm × 30 cm" rather than "21 × 30 cm".
3. `QuadEditorCanvas.kt` still shows Vietnamese "cạnh dài"/"cạnh ngắn" while everything else is
   English.

Also deliberate, not defects: all UI text is English (the mock is Vietnamese); touch targets are
larger than the mock and inset further from the screen edges; the AR screen has undo/redo and Clear
controls the mock never drew; `in` on the unit button vs `inch` in the menu.

## Open question for the team

`DragHandle` in the quad editor is **28dp**, below the module's own ≥48dp convention. Was
`MagnifierLoupe` intended to compensate for a deliberately small handle (precise aiming), or should
the handle grow? Flagged by the code review, not decided.
