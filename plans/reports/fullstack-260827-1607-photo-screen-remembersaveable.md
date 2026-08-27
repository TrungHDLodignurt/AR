# PhotoMeasureScreen — remember → rememberSaveable

Date: 2026-08-27 · Branch: `feature/photo-reference-measure` · Module: `:AR_feature`
File touched: **only** `AR_feature/src/main/java/vn/apero/armeasure/photo/presentation/PhotoMeasureScreen.kt`

## What changed

All five screen-level states moved off `remember`. Reason: picking a photo hands the foreground to
the OEM camera/gallery activity; the activity/process gets recreated (or reclaimed while
backgrounded) and every `remember` resets → flow snapped back to "Chọn đối tượng tham chiếu".

| before (`remember`) | after (`rememberSaveable`) | saver |
|---|---|---|
| `referenceChosen: Boolean` | `chosenReferenceId: String?` | auto (String) |
| `showPickPhotoSheet: Boolean` | same, saveable | auto |
| `showReferenceSheet: Boolean` | same, saveable | auto |
| `editingReference: ReferenceObject?` | `editingReferenceId: String?` | auto (String) |
| `canvasSize: IntSize` | same, saveable | `IntSizeSaver` (`listSaver`) |

`referenceChosen` survives as a derived `val referenceChosen = chosenReferenceId != null` — call
sites unchanged apart from the two writes (`selectReference`, `onChangeReference`).

## Saver strategy for the non-trivial state

- **`IntSize`** — inline value class over a packed `Long`, not Parcelable/Serializable. Added a
  file-private `IntSizeSaver: Saver<IntSize, Any> = listSaver({ listOf(width, height) }, { IntSize(it[0], it[1]) })`,
  used via `rememberSaveable(stateSaver = IntSizeSaver)`. Two ints is all `IntSize` is, so no
  `mapSaver`/custom `Saver` was warranted.
- **`ReferenceObject`** (both `editingReference` and the chosen reference) — plain `data class`, not
  Parcelable/Serializable. Chose **stable id + re-resolve** over writing a saver:
  - `customReferences` is already reloaded from `CustomReferenceStore` on composition, so the object
    is available for free and is *fresher* than a snapshot would be (picks up an edit made in between).
  - avoids making a domain type Parcelable just to satisfy a UI concern.
  - `editingReferenceId` resolves against `customReferences` only — `ReferencePickerScreen` wires
    `onEdit` from the customs grid, never from built-ins. A stale id (object deleted meanwhile)
    resolves to `null`, which the sheet already treats as "add new" — safe degradation.
  - chosen reference resolves against `builtInReferenceObjects + customReferences`.

## Extra fix required for correctness (not in the handoff list)

`PhotoMeasureState` is still a plain `remember`, and `state.reference` defaults to built-in A4.
Restoring `chosenReferenceId` alone would put the UI past the picker while the state still held A4 —
**silently calibrating against the wrong object**. Added a `LaunchedEffect(chosenReferenceId)` that
re-resolves `state.reference` from the surviving id when they disagree.

`showPickPhotoSheet` being restored `true` is load-bearing, not incidental: `PickPhotoSheet` owns the
`rememberLauncherForActivityResult` launchers, so the sheet must be composed again for the picked
`Uri` to be delivered after recreation. Comment in code says so.

## Not touched (per ownership rules)

`AutoFitQuad.kt`, `QuadFromEdges.kt`, `HoughTransform.kt` — untouched. "↩ Detect again",
`resetDetection()` and the loosened `if (detected != null)` guard left exactly as found.

## Compile

`./gradlew :AR_feature:compileDebugKotlin` → **BUILD SUCCESSFUL**.
`./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**.

## Device verification — PARTIAL

Done automatically on Joy_4 `BKB00251473` (Pixel never touched, no `installDebug` used):
- `adb -s BKB00251473 install -r app/build/outputs/apk/debug/app-debug.apk` → Success
- `adb -s BKB00251473 shell settings put global always_finish_activities 1` → **device is armed for the repro**
- launched `vn.quancua.artapemeasure/.MainActivity`; `mCurrentFocus` = MainActivity, logcat clean
  (no `AndroidRuntime`/`FATAL`) → the new `rememberSaveable`/`LaunchedEffect` init path does not crash.

**Not done:** the actual state-survival check. It needs a human to navigate the photo-measure flow
(`adb install` kills the app, so no session can be resumed from here), and blind `input tap` driving
was explicitly off-limits.

### Manual repro script (2 min)

`always_finish_activities` is already ON, which is what makes this deterministic — note
`ArPhotoActivity` declares `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`, so
**rotation alone will NOT reproduce it**; backgrounding is the trigger.

1. Open the app → enter the photo-measure tool.
2. Pick a reference object (e.g. A4, or a custom one) → the pick-photo sheet opens.
3. Tap "Chụp ảnh"/"Chọn từ thư viện" so the OEM camera/gallery comes to the foreground.
4. Return (pick an image, or press back).
5. **PASS:** still past the picker, correct reference label shown, sheet/flow intact.
   **FAIL (old behaviour):** back on "Chọn đối tượng tham chiếu".
6. Extra check for the id re-resolve: register a *custom* reference, select it, repeat 3–4, confirm
   the label after return is the custom one and not "A4 paper".
7. **Turn the flag back off:** `adb -s BKB00251473 shell settings put global always_finish_activities 0`

## Unresolved

- `PhotoMeasureState` (photo `Bitmap`, quad, homography, segments, undo stack) is still `remember`.
  On genuine process death everything after photo-load is still lost. Fixing that means saving the
  photo `Uri` (not the bitmap) + quad offsets + segments — a larger change, deliberately out of scope
  here. Do we want it?
- `showReferenceSheet == true` restoring re-opens the edit sheet with fields reset to the stored
  object; in-flight text the user had typed is not preserved (`ReferenceEditSheet` has its own
  internal `remember`s). Left alone — separate file, separate decision.
- Device PASS/FAIL still outstanding; needs the manual run above.
