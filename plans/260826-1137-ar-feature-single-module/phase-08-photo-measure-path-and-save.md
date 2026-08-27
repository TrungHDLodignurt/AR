# Phase 08 — Photo measure path + save the annotated bitmap to the gallery

## Context Links

- [Plan overview](plan.md) · depends on [phase 07](phase-07-reference-object-crud.md),
  [phase 02](phase-02-four-unit-length-and-unit-menu.md), [phase 03](phase-03-undo-redo-stacks.md)
- Design: `TUBkX` SCR-21, `DIbuK` SCR-22 (+ `GAQoZ` CheckmarkBtn), `jwRjx` SCR-23,
  `M2m5jZ` ColorPickerBar, `l6wgWg` MagnifierLoupe, `TOKzn` MeasureLabel
- Decisions 9, 10, 12.

## Overview

- **Priority:** P2 — largest phase.
- **Status:** pending
- **Effort:** 4h

Reference → photo → quad → confirm → measure → save. Ends with a real annotated PNG in the gallery.

## Key Insights

1. **The design has no photo-picking screen at all.** The only picker in the document (`zfXsB`
   SCR-03 "SelectPhoto") belongs to the unrelated AI-restyle flow. The code already has the right
   answer — `PickPhotoSheet` (take a photo / pick from gallery) plus the "photo must contain the
   reference *and* the object" waiting state. Keep both, localise them, and treat the missing screen
   as a design gap, not a feature gap.
2. **SCR-22's instruction text is identical to SCR-21's**, verbatim — so the state advance is
   completely unsignalled. SCR-22 needs its own copy ("drag the corners to match the reference, then
   confirm"). Write two distinct strings.
3. **SCR-21 has no forward action at all** — content ends at y≈559 with 240px of empty space and no
   CTA. SCR-22's `GAQoZ` CheckmarkBtn (100dp, centred, `Signature`, 3dp white stroke, `check` 66) is
   the confirm affordance; it belongs on the quad-adjust state, not only on the "confirmed" one.
4. **"Chỉnh sửa tỉ lệ" is architecturally blocked in today's code, and it is the best idea in the
   design.** Once `confirmReference()` sets a non-null homography, `PhotoQuadCanvas` takes its `else`
   branch, `QuadEditorCanvas` becomes unreachable, and `moveQuadCorner` is dead code — so changing
   the scale currently means throwing the photo away. Fix with an explicit `isEditingQuad` flag that
   routes back to the quad editor while **preserving the line**; the image-space coordinates are
   already retained, so nothing has to be recomputed.
5. **Colour selection must not touch the quad editor.** `QuadEditorCanvas` uses cyan and yellow
   *semantically* for the long and short edge (`QuadEditorCanvas.kt:86,92` label them "cạnh dài" /
   "cạnh ngắn"), so a user-chosen colour would collide with meaning. Decision 10 scopes
   `ColorPickerBar` to the photo flow — scope it further, to **the measuring line and its label
   only**. Strokes are currently hardcoded in `MeasureOverlay.kt:17-19`, `ShapeOverlay.kt:41`,
   `PhotoQuadCanvas.kt:36`, `QuadEditorCanvas.kt:30-32`; only `PhotoQuadCanvas`'s line colour becomes
   a parameter.
6. **A user-chosen label colour cannot have a fixed text colour.** The design's `TOKzn` is white
   13/700 on `#EB3232` = **4.1:1**, which already fails; on the yellow `#FFC700` white text is
   ≈1.6:1 — unreadable. Pick the label's text colour from the chosen colour's relative luminance
   (white for dark fills, `TextPrimary` for light ones) and **assert in a test that all five palette
   colours clear 4.5:1** with their chosen text colour. Also give the line a dark halo so it reads
   over a bright photo.
7. **The selected colour dot is a 3px *white* stroke on a `#F4F4F2` bar** — very nearly invisible.
   Use a `TextPrimary` ring plus a size bump instead.
8. **"Lưu" is a 26×17 bare text node in `#8A9A5B` on `#F4F4F2` — 2.78:1, the worst contrast in the
   document, and it is the save button.** Make it a real 48dp-tall filled button, `SignatureText`
   fill, white 15/700. The document's *other* "Lưu" (`fgWMc/Xa6k0`) is already exactly that shape, so
   this is internally consistent, not an invention.
9. **The save default is more than "~30 lines of platform API" because `minSdk` is 24.** MediaStore's
   `RELATIVE_PATH` + `IS_PENDING` path only exists on API 29+. On API 24–28 a gallery write needs
   `WRITE_EXTERNAL_STORAGE`, which means declaring it with `android:maxSdkVersion="28"` **and**
   requesting it at runtime — a real change to the module's permission surface (currently CAMERA
   only) that a host will care about. Budget ~70 lines and document the permission.
10. **Compose the saved bitmap through the same draw code as the screen.** `CanvasDrawScope().draw(
    density, layoutDirection, Canvas(imageBitmap), size) { … }` gives a real `DrawScope` over an
    offscreen `ImageBitmap`, so the existing `drawPhotoAnnotations` function can be called once for
    the screen and once for the export. Two code paths would drift; one cannot. Then
    `imageBitmap.asAndroidBitmap()`. Zero new dependencies.
11. **`PhotoMeasureState.kt:107` logs raw tap coordinates** via `android.util.Log.d`. Remove it —
    it is debug residue and it logs user content.
12. **SCR-23 and SCR-24 are two copies of "edit the line"** with different confirm affordances
    ("Lưu" text vs a bare 24dp check icon) and no defined relationship. Implement **one** screen
    carrying everything: TopNav (back, undo/redo, Save), the photo, the magnifier, the measure label,
    the bottom toolbar, and the colour bar.
13. **Keep the shipped MagnifierLoupe behaviour.** The design pins a 150dp loupe permanently at
    top-left; the code's version follows the finger and clamps. Adopt the design's size, circular
    crop and centre crosshair; keep the follow-and-clamp behaviour and show it only while dragging.

## Requirements

**Functional**
- Flow: reference grid → pick photo (sheet: camera / gallery) → tap to place quad → drag corners →
  confirm → drag the line → change colour → change unit → save.
- Confirm affordance on the quad state (100dp `CheckmarkBtn`).
- "Line segment" and "Edit scale" bottom-toolbar actions; edit-scale returns to the quad editor
  without discarding the photo or the line.
- `ColorPickerBar`: 5 colours applying to the measuring line + its label only; the `cm` badge opens
  the phase-02 `UnitMenu`.
- Undo/redo (phase 03's photo snapshot stack) in the top nav.
- Save writes the **annotated** bitmap — original photo with the measurement drawn over it, **no
  watermark** — via `MeasurementImageSaver`, defaulting to the module's MediaStore implementation.

**Non-functional**
- All strings from `strings.xml`, English default. The photo package currently has **zero**
  `stringResource` calls and ~20 hardcoded Vietnamese literals; this phase and phase 07 clear them.
- All targets ≥48dp: undo/redo 24→48, "Lưu" 26×17→48-tall button, colour dots 20→48, `cm` badge
  40×36→48, back 24→48, close/check 24→48, bottom-toolbar items are already 145×69.
- Every text/background pair ≥4.5:1, including the user-coloured label (test-asserted).
- Saving runs off the main thread; the export bitmap must be recycled.
- The measurement bitmap is never held on the undo stack (phase 03 already forbids this).

## Architecture

```kotlin
// ArMeasureConfig.kt  (public, declared in phase 04)
fun interface MeasurementImageSaver { suspend fun save(bitmap: Bitmap, fileName: String): Uri? }
object ArMeasureConfig { var imageSaver: MeasurementImageSaver? = null }
```

```kotlin
// photo/data/MediaStoreImageSaver.kt   (internal — the module's working default)
internal class MediaStoreImageSaver(private val context: Context) : MeasurementImageSaver {
    override suspend fun save(bitmap: Bitmap, fileName: String): Uri?
}
```

- **API 29+:** insert into `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` with
  `DISPLAY_NAME = fileName`, `MIME_TYPE = "image/png"`,
  `RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/$appLabel"`, `IS_PENDING = 1`; stream the PNG;
  clear `IS_PENDING`. `appLabel` = `context.applicationInfo.loadLabel(packageManager)`, so the album
  follows the host app — never a hardcoded name.
- **API 24–28:** `WRITE_EXTERNAL_STORAGE` (declared `maxSdkVersion="28"`, requested at runtime),
  write to `Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES)/$appLabel/`, then
  insert the row so the gallery indexes it.
- Returns `null` on any failure; the UI shows an error toast and does not pretend it saved.

Do **not** depend on any host `FileUtils`. Both AIP936 and ADA903 have one, and taking it is exactly
the `:core`-kernel coupling that forces `trung-apply-feature-video` to ship a whole
`core-kernel-contract.md`. This module has stayed dependency-free; 70 lines is the right price.

Export path, one draw function for both targets:

```kotlin
// photo/presentation/PhotoAnnotations.kt   (internal)
internal fun DrawScope.drawPhotoAnnotations(
    photo: ImageBitmap, line: LiveLine?, label: String?, lineColor: Color, textMeasurer: TextMeasurer,
)

internal fun renderAnnotatedBitmap(...): Bitmap   // CanvasDrawScope over an offscreen ImageBitmap
```

```kotlin
// common/ui/LabelContrast.kt   (internal, PURE — what the tests drive)
internal fun labelTextColorFor(background: Color): Color   // luminance threshold
internal fun contrastRatio(a: Color, b: Color): Float
```

Palette (design `M2m5jZ`, raw hex — none of these are in the token set, which is itself worth
recording): `#EB3232`, `#FF7700`, `#FFC700`, `#32D74B`, `#6057FF`. Default `#EB3232`, matching the
design's selected dot.

Screen specs, corrections applied:

| Element | Spec |
|---|---|
| Instruction box | `fillMaxWidth` − 2×12, hug height, fill `#1a3a4a`, r12, stroke `#4a6a7a` 1dp, pad `[12,16]`, text 14/400 lineHeight 1.4 centred `#FFFFFF` (>10:1 — passes). Raw hex, off-token: record it |
| Photo area | aspect-fit the real bitmap. The mock's 795×348 image in a 354 frame is a resize artefact — ignore it |
| CheckmarkBtn | 100dp, `Signature`, r999, 3dp `#FFFFFF` stroke, `check` 44 (not the mock's 66 — a 66 glyph in a 100 circle is oversized vs any Material FAB) `OnSignature` |
| TopNav | height 56, pad 16; back 48dp / `arrow-left` 24; undo/redo group centred, 48dp each, gap 24; Save = 48-tall filled button, `SignatureText`, r12, 15/700 `OnSignature` |
| Bottom toolbar | height 109, pad `[16,16,24,16]`, gap 32, two `weight(1f)` items: circle 48 r24 `BgSecondary` + icon 24 `TextPrimary`, label 12/400 `TextSecondary` |
| MeasureLabel | hug pill, r16, fill = chosen colour, text 13/700 = `labelTextColorFor(chosen)` |
| MagnifierLoupe | 150dp circle, centre `plus` 20 `#FFFFFF`, 2dp `#FFFFFFCC` ring (the mock has none), visible while dragging only |
| ColorPickerBar | `fillMaxWidth`, height 96, `BgPrimary`, pad `[0,16]`, `SpaceBetween`; 5 dots drawn 20dp in 48dp targets, selected = 2dp `TextPrimary` ring + 24dp fill; `cm` badge 48dp target / 40×36 pill `#4749A0` r18, `symbol` 12/400 `#FFFFFF` |

## Related Code Files

**Create**
- `AR_feature/.../photo/data/MediaStoreImageSaver.kt`
- `AR_feature/.../photo/presentation/PhotoAnnotations.kt` (shared draw + `renderAnnotatedBitmap`)
- `AR_feature/.../photo/presentation/ColorPickerBar.kt`
- `AR_feature/.../common/ui/LabelContrast.kt`
- `AR_feature/src/test/java/vn/apero/armeasure/common/ui/LabelContrastTest.kt`

**Modify**
- `.../photo/presentation/PhotoMeasureScreen.kt` — rebuilt to SCR-21/22/23: instruction box,
  `CheckmarkBtn`, top nav, bottom toolbar, colour bar; all Vietnamese literals → resources; the
  `WaitingForPhoto`/`HintBanner`/`BottomPanel` private composables are replaced
- `.../photo/presentation/PhotoMeasureState.kt` — `isEditingQuad`, `lineColor`, `beginEditQuad()`;
  **remove the `Log.d` at line 107**
- `.../photo/presentation/PhotoQuadCanvas.kt` — line colour becomes a parameter; delegate drawing to
  `drawPhotoAnnotations`; route to `QuadEditorCanvas` when `isEditingQuad`
- `.../photo/presentation/MagnifierLoupe.kt` — 150dp, circular crop, centre crosshair, ring
- `.../photo/presentation/PickPhotoSheet.kt` — strings → resources
- `.../photo/presentation/ArPhotoActivity.kt` — request `WRITE_EXTERNAL_STORAGE` on API ≤ 28 before
  a save; default `ArMeasureConfig.imageSaver` to `MediaStoreImageSaver(this)` when unset
- `AR_feature/src/main/AndroidManifest.xml` — add
  `<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />`
- `AR_feature/src/main/res/values/strings.xml` — ≈18 strings (2 distinct instruction lines, waiting
  state ×3, pick-photo sheet ×3, toolbar labels ×2, save label, save success/failure, content
  descriptions, colour names for accessibility)

## Implementation Steps

1. Write `LabelContrast.kt` + its 4 tests. Pure, so green first.
2. `MediaStoreImageSaver` with both API paths; the manifest permission; the runtime request in
   `ArPhotoActivity` for API ≤ 28.
3. `PhotoAnnotations.kt`: extract today's on-screen line/label drawing into
   `DrawScope.drawPhotoAnnotations`, then add `renderAnnotatedBitmap` calling the *same* function
   through `CanvasDrawScope`. Verify by eye that the export matches the screen.
4. `PhotoMeasureState`: `lineColor`, `isEditingQuad`, `beginEditQuad()`; delete the `Log.d`.
5. `PhotoQuadCanvas`: parameterise the line colour, delegate to `drawPhotoAnnotations`, honour
   `isEditingQuad` so `QuadEditorCanvas` is reachable again — leaving `moveQuadCorner` dead would be
   the one thing in this phase that silently does nothing.
6. `ColorPickerBar` with 48dp targets, the ring-selected state and the `cm` badge → `UnitMenu`.
7. Rebuild `PhotoMeasureScreen` to the three states (place quad / adjust+confirm / measure) with the
   instruction box, `CheckmarkBtn`, top nav and bottom toolbar. Two distinct instruction strings.
8. `MagnifierLoupe` to spec, keeping follow-and-clamp.
9. Wire Save: render → `ArMeasureConfig.imageSaver ?: MediaStoreImageSaver` → success/failure toast.
   Recycle the export bitmap.
10. Extract every remaining photo string. Gate.

## Todo List

- [ ] `LabelContrast` + 4 tests
- [ ] `MediaStoreImageSaver` (API 29+ and 24–28 paths), manifest permission, runtime request
- [ ] `drawPhotoAnnotations` + `renderAnnotatedBitmap` sharing one draw path
- [ ] `PhotoMeasureState`: `lineColor`, `isEditingQuad`, `beginEditQuad()`; `Log.d` removed
- [ ] `PhotoQuadCanvas`: colour parameter, delegates drawing, `QuadEditorCanvas` reachable again
- [ ] `ColorPickerBar` (48dp dots, ring selection, `cm` badge → `UnitMenu`)
- [ ] `PhotoMeasureScreen` rebuilt: instruction box, `CheckmarkBtn`, top nav, bottom toolbar
- [ ] Two distinct instruction strings for the place-quad and adjust-quad states
- [ ] `MagnifierLoupe` 150dp circle + ring, follow-and-clamp kept
- [ ] Save wired end to end, off the main thread, bitmap recycled
- [ ] ≈18 strings extracted, English default
- [ ] Gate: `compileDebugKotlin testDebugUnitTest assembleDebug assembleRelease`
- [ ] On-device: full flow with a real photo of an A4 sheet next to a known object; check the number
      against a tape measure
- [ ] On-device: Save → confirm the file appears in the gallery app, in `Pictures/<app label>`, and
      that the **lines are drawn into the image** and there is **no watermark**
- [ ] On-device: each of the 5 colours — line, label fill and label text all change and stay legible
      over a bright photo
- [ ] On-device: change unit via the `cm` badge, confirm the label and the saved image agree
- [ ] On-device: "Edit scale" → drag a corner → confirm → the line survives and the number updates
- [ ] On-device: undo/redo across a corner drag, a line drag and a colour change
- [ ] On-device: save with the gallery permission denied on an API ≤ 28 device or emulator — confirm
      a failure toast, not a crash and not a silent no-op

## Success Criteria

**102 tests pass** (98 + 4). The 4 `LabelContrastTest` tests:

1. `labelTextColorFor` returns white for the dark palette entries (`#EB3232`, `#6057FF`)
2. it returns `TextPrimary` for the light palette entries (`#FFC700`, `#32D74B`)
3. **all five palette colours reach ≥4.5:1** with the colour `labelTextColorFor` picks for them —
   this is the real assertion; if a palette colour cannot clear it, the palette changes, not the test
4. `contrastRatio` is symmetric and returns 21.0 for black/white (sanity anchor for 1–3)

Plus: the saved PNG opens in the gallery with the annotation baked in; `moveQuadCorner` has a live
call path (`git grep` shows a caller reachable from `isEditingQuad`); no `Log.` calls remain in the
photo package.

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| API 24–28 gallery write fails or needs a permission the host did not expect | **high** | both paths implemented; `maxSdkVersion="28"` scopes the permission; failure returns `null` and shows a toast; named on-device check |
| Export render diverges from the on-screen render | high if two paths | one `drawPhotoAnnotations`, called twice; visual comparison in the on-device gate |
| OOM rendering a full-resolution photo into a second bitmap | medium | render at the source bitmap's resolution (already downsampled by `ExifBitmapLoader`), recycle immediately, do it off the main thread |
| A user colour makes the label unreadable | medium | `labelTextColorFor` + test 3 + a dark halo on the line |
| `QuadEditorCanvas`'s semantic cyan/yellow collides with a user colour | medium | scoped out: colours apply to the line and label only |
| "Edit scale" loses the line or the calibration | medium | `isEditingQuad` preserves both; image-space coords are already retained; named on-device check |
| `ArMeasureConfig.imageSaver` set by a host that stores the bitmap elsewhere | — | it is the documented purpose of the port; the trust-boundary note goes in the README |
| Two nearly-identical design screens (SCR-23/24) implemented as one reads as a missing screen | low | documented; they have no defined relationship in the document |

## Security Considerations

- **New permission**: `WRITE_EXTERNAL_STORAGE`, `maxSdkVersion="28"` only. This is the module's
  second permission after CAMERA and must be called out prominently in phase 10's README — a host
  reviewing its manifest will see it. It is requested at the moment of saving, never earlier.
- **`MeasurementImageSaver` is a trust boundary.** A host implementation receives the user's photo
  as a `Bitmap`. Document that explicitly; do not log the bitmap, its size, or the resulting `Uri`.
- The saved image contains the user's photo. Write it only to the public Pictures collection the user
  expects, never to `cacheDir` and never with a world-readable file path on the legacy branch beyond
  what the public directory already implies.
- `IS_PENDING` must be cleared in a `finally`, or a failed save leaves an invisible orphan row.
- Filenames must be sanitised — no path separators, no `..`, no user-supplied text that could
  traverse. Derive from a timestamp, not from the reference object's label.
- Remove the existing `Log.d` of tap coordinates; add no new logging of user content.
- `FileProvider` is untouched — the save path uses MediaStore, not a content URI we grant.

## Next Steps

- Phase 09 audits strings, contrast, targets and cross-package imports.
- Phase 10 documents the port, the new permission, and the AIP936 wiring.
