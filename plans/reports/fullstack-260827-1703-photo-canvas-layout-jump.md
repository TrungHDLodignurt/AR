# Photo canvas layout jump after auto-fit detection

Branch feature/photo-reference-measure. No commit made.

## Causes — both real

1. **"↩ Detect again" temp button (real, primary).** Was a `Column` child gated on
   `state.quad.isNotEmpty()` (old line ~242). Absent before detection, present after → Column
   reflow → weighted photo `Box` loses ~45dp → `PhotoQuadCanvas` aspect-fits smaller. That is the
   visible jump/shrink.
2. **InstructionBox text swap (real but conditional).** `armeasure_photo_instruction_place`
   ("Tap on the %1$s to mark it, then adjust its size", 45 chars + label) vs
   `armeasure_photo_instruction_adjust` ("Hold and drag the corners to match the %1$s", 38 chars +
   label). Only one `values/` dir — no VI translation exists yet. `defaultMinSize(minHeight=64.dp)`
   absorbs the 1-vs-2-line difference, so for the built-in labels (A4 paper, card) both wrap to 2
   lines and the box does NOT change height today. But `place` is 7 chars longer, so a long custom
   reference name pushes it to 3 lines while `adjust` stays at 2 → second, independent jump. Not
   left alone: cheap to make unconditional.

## Changes

`photo/presentation/PhotoMeasureScreen.kt`
- Moved the TEMPORARY "Detect again" button out of the `Column` into the weighted photo `Box` as an
  overlay, `align(Alignment.TopCenter)`, same gate (`!hasEverCalibrated && quad.isNotEmpty()`),
  same styling. **Chose overlay over reserved height** because: (a) the button is temporary tuning
  chrome — reserving ~45dp permanently would push SCR-21/22's designed layout off spec and would
  need un-reserving at removal time, (b) as an overlay it costs the Column zero height so the photo
  box is byte-identical between SCR-21 and SCR-22, (c) removal later is a pure delete.
- InstructionBox call site now computes both wordings and passes the unused one as `sizingText`.

`photo/presentation/PhotoMeasureChrome.kt`
- `InstructionBox(text, modifier, sizingText: String? = null)`. The ghost `Text` is laid out with
  `Color.Transparent` + `clearAndSetSemantics {}` (else TalkBack would read both wordings from the
  merged node), so the box measures to `max(place, adjust)` and its height is constant across the
  swap regardless of label length.
- Not touched: `PhotoMeasureState.onCanvasResized`, the PhotoAutoFit diagnostics, the button itself,
  the bottom 100dp reserved check-button slot (the existing pattern this fix follows).

Added a TEMPORARY `Log.d("PhotoAutoFit", "canvas onSizeChanged=... quad=...")` inside the canvas
`onSizeChanged`, marked with the file's existing TEMPORARY convention and to be removed with the
rest of the auto-fit tuning instruments. It is the objective check: if it fires only once per photo
(and not again when the quad lands), the reflow is gone.

## Verification

- `:AR_feature:compileDebugKotlin` — PASS
- `:AR_feature:testDebugUnitTest` — PASS, 164 tests / 0 failures / 1 skipped
- `:app:assembleDebug` — PASS; installed on Pixel 6 `18311FDF60085N` (`-s` used), `log.tag.PhotoAutoFit`
  set to DEBUG.
- **NOT verified on device.** The photo-measure flow needs a human (install kills the app, blind
  tapping is forbidden). Nobody has run pick-reference → pick-photo → tap-object since the install.
  The fix is reasoning + a static layout argument, not an observed run.

## Unresolved

- Needs the user to drive one run and say whether the photo still jumps; the `PhotoAutoFit` log line
  confirms objectively (one `canvas onSizeChanged` per photo, unchanged size when quad appears).
- No `values-vi/` exists — every string is English. If VI translations land later, re-check the
  instruction wrappings; the `sizingText` fix already covers them by construction.
- The temporary Log.d and the "Detect again" button both come out together when auto-fit tuning ends.
