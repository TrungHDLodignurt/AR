# Phase 07 — Reference objects: grid + add / edit / delete

## Context Links

- [Plan overview](plan.md) · depends on [phase 04](phase-04-entry-layer-hub-and-activities.md),
  [phase 02](phase-02-four-unit-length-and-unit-menu.md)
- Design: `SvcdA` SCR-15 grid, `o8UWT` SCR-16 add (`YBV5s` AddSheet), `beXJR` SCR-17 edit
  (sheet `fgWMc`, containing `N2BCY` DeleteAction)
- Decision 13: reference objects get add + edit + delete.

## Overview

- **Priority:** P2
- **Status:** completed
- **Effort:** 2h

The photo branch's first screen. `CustomReferenceStore` gains update + delete, entries gain a stable
id, and the grid is built to SCR-15.

## Key Insights

1. **`CustomReferenceStore` has no id, and the design proves why that breaks.** SCR-15 shows **two
   cards both named "điện thoại"** with different dimensions (7×16 and 7×15 cm). The store keys
   nothing and `ReferenceObject` is `(label, shortSideMm, longSideMm)` — so those two rows cannot be
   addressed distinctly for edit or delete. A stable `id` is a prerequisite, not a nicety, and it
   needs a one-time migration of existing stored JSON.
2. **The delete action is not on the Add sheet.** The brief says `YBV5s` contains `N2BCY`; the live
   document says otherwise — `YBV5s` (Add, 360×325) has no delete, and `N2BCY` DeleteAction lives in
   `fgWMc` (Edit, 368×368), which is a **document-root orphan at x=8462**, never parented to
   `beXJR`, 14px wider than the screen and missing the close `×` that the Add sheet has. So the two
   sheets are divergent copies of the same thing. **Implement one sheet** with an optional editing
   target: title and CTA switch, and the delete row appears only when editing. DRY, and it
   eliminates the divergence rather than reproducing it.
3. **The built-in dimensions in the design are wrong.** "21 x 30 cm" for A4 (really 210×297mm) and
   "5 x 9 cm" for a payment card (really 53.98×85.60mm). The code is correct but
   `ReferencePickerScreen.kt:85` hardcodes a rounded label. Fix by formatting both sides through
   phase 02's `formatLength` with the *currently selected* unit — that kills the rounding bug and
   makes the card respect the unit choice in one move.
4. **The Add sheet sits at y=498 with height 325 and a ~300px keyboard covers the whole form,
   including its own CTA.** Needs `Modifier.imePadding()` / `windowInsetsPadding(WindowInsets.ime)`.
   The mock cannot show this; implement it anyway.
5. **Three "cm" in one row.** The mock's dimension fields each carry a disabled `"cm"` suffix *and*
   there is a separate `cm ▾` unit selector beside them. Drop the in-field suffixes, keep the
   selector — which is the same `UnitMenu` from phase 02.
6. **The pencil is 16×16 nested inside a tappable card** → an ambiguous tap. Give the pencil its own
   48dp target and exclude that area from the card's click, so card tap = select and pencil tap =
   edit. Six such pencils exist across SCR-15/16/17 (`Htnzg`, `kA3G4`, `T0057j`, …).
7. **The grid already nearly overflows.** Six cards at 176 tall + gaps ≈ 696 of the 799-tall frame,
   and the design has no scroll affordance and no bottom nav on this screen. Use a
   `LazyVerticalGrid` from the start — a user with four custom objects overflows immediately.
8. **SCR-15's subtitle duplicates the nav title verbatim** ("Chọn đối tượng tham chiếu" appears in
   both `O8F0b` and `ba5BF/Line1`, ~30px apart) and splits one sentence across two colours, the
   second in `#8A9A5B` at **2.78:1**. Drop the duplicate line and render the remaining sentence in
   one colour, `TextSecondary`.
9. **Delete is destructive with no undo and the mock offers no confirmation.** Add one — a small
   confirm dialog, since the phase-03 undo stack is per-measurement and does not cover the store.
10. **All the copy on these screens is hardcoded Vietnamese today** (`ReferencePickerScreen.kt`,
    `NameReferenceDialog.kt`, `PickPhotoSheet.kt` — ~20 literals, zero `stringResource` calls in the
    whole photo package). Per decision 14 they all become `armeasure_`-prefixed resources with
    **English** as the default locale, as part of building these screens.

## Requirements

**Functional**
- `ReferenceObject` gains `id: String` and `isBuiltIn: Boolean`.
- `CustomReferenceStore`: `loadAll`, `add`, `update`, `delete`. One-time migration assigns ids to
  legacy entries and rewrites the JSON.
- Grid: 2 columns, built-ins first, then customs, then the "add new" card. Scrollable.
- One sheet for add and edit; edit prefills every field; edit shows a delete row; delete confirms.
- Dimensions rendered in the active unit through `formatLength`.
- Selecting a card advances the photo flow (phase 08 provides the destination).

**Non-functional**
- Built-ins are never persisted and never deletable — a `delete` on a built-in id is a no-op that
  returns `false`, not a crash.
- Corrupt/legacy stored JSON must never crash the screen; it degrades to whatever parsed.
- All targets ≥48dp: pencil 16→48, delete row 34→48, sheet close 22→48, dimension fields 43→48,
  unit selector 43→48, name field 46→48, back chevron 26→48, and the info icon (which has no
  destination anywhere in the document) is **dropped**, not given a fake one.
- Contrast: "Thêm"/"Lưu" CTAs are white on `#8A9A5B` = **3.05:1** — use `SignatureText #6E7C42` as
  the button fill instead (white on it ≈4.6:1). Delete row `#B4483C` on white passes.

## Architecture

```kotlin
// photo/domain/imaging/ReferenceObject.kt   (internal)
internal data class ReferenceObject(
    val id: String,
    val label: String,
    val shortSideMm: Float,
    val longSideMm: Float,
    val isBuiltIn: Boolean = false,
)

internal val builtInReferenceObjects = listOf(
    ReferenceObject("builtin:a4",   "A4 paper",     210f,   297f,  isBuiltIn = true),
    ReferenceObject("builtin:card", "Payment card", 53.98f, 85.60f, isBuiltIn = true),
)
```

```kotlin
// photo/domain/imaging/ReferenceObjectJson.kt   (internal, PURE — this is what the tests drive)
internal fun encodeReferences(objects: List<ReferenceObject>): String
internal fun decodeReferences(json: String?): List<ReferenceObject>   // legacy entries get an id
```

```kotlin
// photo/data/CustomReferenceStore.kt   (internal)
internal class CustomReferenceStore(private val context: Context) {
    fun loadAll(): List<ReferenceObject>
    fun add(label: String, shortSideMm: Float, longSideMm: Float): ReferenceObject
    fun update(id: String, label: String, shortSideMm: Float, longSideMm: Float): ReferenceObject?
    fun delete(id: String): Boolean          // false for built-ins and unknown ids
}
```

Ids for new entries: `UUID.randomUUID().toString()`. Migration: `decodeReferences` mints an id for
any entry lacking one; `loadAll` writes back once if any id was minted. Prefs file and key are
**unchanged** (`vn.apero.armeasure.photo.custom_reference_objects` / `objects`) so no data is lost.

Screen spec (design values; corrections applied):

| Element | Spec |
|---|---|
| Screen | `BgPrimary`, vertical |
| TopNav | height 42→56, pad `[10,16,6,16]`; back 48dp target / `chevron-left` 26 `TextPrimary`; title 17/700 centred `TextPrimary`; **info icon dropped** |
| Subtitle | pad `[10,20,18,20]`; **one** line, 16/600, lineHeight 1.35, `TextSecondary` |
| Grid | `LazyVerticalGrid(2)`, pad `[0,20]`, gaps 14 |
| Preset card | 150×176, `BgSurface`, r14, stroke `BorderSoft` 1dp, pad `[16,14]`, `SpaceBetween`; lucide icon 54; name 15/700 `TextPrimary`; dims 12/400 `TextSecondary`, gap 3 |
| Custom card | same shell, pad `[14,14,16,14]`; avatar 66 r33 `SignatureMuted` + 2-char initials 19/600 `SignatureText`; pencil top-right, 48dp target, `pencil` 16 `TextSecondary` |
| Add card | transparent, r14, stroke `BorderStrong` 1.5dp; plus circle 58 r29 `SignatureMuted` + `plus` 28 `Signature`; label 14/700 centred `TextPrimary` |
| Sheet | `fillMaxWidth`, hug height, `BgSurface`, r`[22,22,0,0]`, gap 16, pad `[12,20,22,20]`, `imePadding()` |
| Handle | 40×4 r2 `BorderSoft` |
| Sheet header | title 16/700 `TextPrimary` + close 48dp target / `x` 22 `TextSecondary` |
| Name field | label 13/500 `TextSecondary`; field height 48, `BgSecondary`, r10, stroke `BorderSubtle` 1dp, pad `[13,14]`, **with a placeholder** |
| Dim fields | two fields height 48, no in-field `cm` suffix; unit selector 67×48, `BgSecondary`, r10, `symbol` 14/600 `TextPrimary` + `chevron-down` 16 → opens `UnitMenu` |
| Delete row (edit only) | height 48, `trash-2` 18 `Error` + label 14/500 `Error` |
| CTA | height 52, fill `SignatureText`, r12, label 15/700 `OnSignature` |

## Related Code Files

**Create**
- `AR_feature/.../photo/domain/imaging/ReferenceObjectJson.kt` (pure encode/decode + migration)
- `AR_feature/src/test/java/vn/apero/armeasure/photo/domain/imaging/ReferenceObjectJsonTest.kt`
- `AR_feature/.../photo/presentation/ReferenceEditSheet.kt` (the one add/edit sheet)

**Modify**
- `.../photo/domain/imaging/ReferenceObject.kt` — add `id` + `isBuiltIn`, exact built-in dims
- `.../photo/data/CustomReferenceStore.kt` — `update`, `delete`, delegate JSON to the pure functions
- `.../photo/presentation/ReferencePickerScreen.kt` — rebuild to SCR-15; **delete the hardcoded
  rounded dimension label at line 85** and format through `formatLength`
- `.../photo/presentation/NameReferenceDialog.kt` — superseded by `ReferenceEditSheet.kt`; delete
- `.../photo/presentation/ArPhotoActivity.kt` — grid is the root; wire add/edit/delete
- `AR_feature/src/main/res/values/strings.xml` — ≈16 strings (title, subtitle, add-card label, sheet
  titles ×2, name/length/width labels, name placeholder, CTA ×2, delete label, delete-confirm
  title/body/confirm/cancel, validation messages, content descriptions)

## Implementation Steps

1. Write `ReferenceObjectJson.kt` and its 6 tests first — pure, so drive it green before any UI.
2. Add `id` + `isBuiltIn` to `ReferenceObject`; correct the built-in dimensions to 210/297 and
   53.98/85.60.
3. Extend `CustomReferenceStore` with `update`/`delete`, both no-ops for built-in ids. `loadAll`
   performs the write-back migration once.
4. Build `ReferenceEditSheet` — one composable, `editing: ReferenceObject? = null`. Wire
   `imePadding()`, the unit selector to `UnitMenu`, validation (non-blank name, both dimensions
   > 0, and keep the existing `customReferenceObject()` short/long normalisation), and a delete
   confirm dialog.
5. Rebuild `ReferencePickerScreen` to SCR-15 with a `LazyVerticalGrid`, the single subtitle line,
   the 48dp pencil target excluded from the card click, and dimensions via `formatLength`.
6. Delete `NameReferenceDialog.kt`.
7. Extract all photo strings touched here into `strings.xml`, English.
8. Gate.

## Todo List

- [x] Pure `encodeReferences` / `decodeReferences` + 6 tests
- [x] `ReferenceObject`: `id`, `isBuiltIn`, exact built-in dims
- [x] `CustomReferenceStore`: `update`, `delete`, one-time id migration on `loadAll`
- [x] `ReferenceEditSheet` — one sheet for add + edit, `imePadding()`, validation, delete confirm
- [x] Delete `NameReferenceDialog.kt`
- [x] `ReferencePickerScreen` rebuilt to SCR-15, `LazyVerticalGrid`, single subtitle
- [x] Remove the hardcoded rounded dimension label; format via `formatLength`
- [x] Drop the info icon (no destination exists anywhere in the design)
- [x] Pencil gets its own 48dp target, excluded from the card click
- [x] Drop the in-field `cm` suffixes, keep the unit selector
- [x] CTA fill → `SignatureText` for AA
- [x] ≈18 strings extracted, English default
- [x] Gate: `compileDebugKotlin testDebugUnitTest assembleDebug assembleRelease`
- [x] On-device: add an object, confirm it appears; edit its name and dims, confirm both persist
      across a force-stop; delete it with confirmation
- [x] On-device: two objects with the **same name** — edit one, confirm only that one changes (the
      id migration's real acceptance test) — verified both with freshly-added duplicates and with
      genuine legacy (no-`id`) prefs XML written directly to the device
- [x] On-device: open the add sheet, raise the keyboard, confirm the CTA is still reachable
- [x] On-device: add 6 objects, confirm the grid scrolls
- [x] On-device: try to delete a built-in — the pencil must not be offered on built-in cards

## Success Criteria

**98 tests pass** (92 + 6). The 6 `ReferenceObjectJsonTest` tests:

1. encode → decode round-trips `id`, `label`, `shortSideMm`, `longSideMm` exactly
2. decoding legacy JSON with no `id` field mints a non-blank id for every entry, all distinct
3. that migration preserves `label` and both dimensions bit-exactly
4. malformed JSON returns an empty list and does not throw
5. an entry missing a dimension is **skipped**, not defaulted to `0f` (a 0mm reference would divide
   the homography by zero and print an absurd measurement)
6. encoding the same list twice produces identical output (deterministic ordering)

Plus: existing stored objects survive an install-over — verified on-device by adding an object
*before* this phase's build and confirming it is still listed, with a working edit, after.

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| Migration loses existing user objects | medium | prefs file and key unchanged; test 2/3 cover the shape; the install-over on-device check is the real gate |
| A 0mm or negative dimension reaches `computeHomography` and produces a nonsense measurement | medium | test 5 + sheet validation + the existing `customReferenceObject()` normalisation |
| Delete with no undo loses a user's object by mistap | medium | confirm dialog; 48dp target; delete row placed away from the CTA |
| Built-in cards offer edit/delete | medium | `isBuiltIn` gates the pencil and both store mutators; named on-device check |
| Implementing one sheet where the design has two divergent ones reads as missing a screen | low | documented here; the orphan `fgWMc` is objectively not wired into `beXJR` |
| `LazyVerticalGrid` inside a `Column` with an unbounded height crashes | medium | give the grid `Modifier.weight(1f)`, not `fillMaxHeight` |

## Security Considerations

- Same prefs file, `MODE_PRIVATE`, namespaced — no host collision. Stored data is a user-chosen
  label and two numbers; no PII beyond whatever the user types as a name.
- `decodeReferences` parses untrusted-at-rest JSON: every field read must be defensive
  (`optString`/`optDouble` with validation), never `getString` on a possibly-absent key. A
  `JSONException` must not escape.
- `UUID.randomUUID()` is fine here — ids are local identifiers, not security tokens.
- No new permissions, dependencies or network.

## Next Steps

- Phase 08 consumes the selected reference and builds the measure path.
- Independent of phases 05/06 — safe to reorder.
