# Phase 02 — `LengthUnit` → cm/m/inch/ft, one shared `UnitMenu`

## Context Links

- [Plan overview](plan.md) · depends on [phase 01](phase-01-merge-into-single-ar-feature-module.md)
- Design source: `XCFlV` UnitMenu (cm/m/inch/ft), `d05XD7`/`WY0bd`/`r1PP73` UnitBtn (text label
  showing the current unit — no longer a gear icon)
- UI review: [`ui-ux-designer-260826-1100-ar-measure-wireframe-review.md`](../reports/ui-ux-designer-260826-1100-ar-measure-wireframe-review.md)
  — "`SCR-24`'s `15 cm` is unrenderable today. **Blocks everything else in the design.**"

## Overview

- **Priority:** P1 — hard prerequisite for phases 06 and 08.
- **Status:** pending
- **Effort:** 2h

Replace the 2-value `Metric`/`Imperial` enum with 4 real units, replace the two `toggleUnit()` chips
with one shared `UnitMenu`, and make the choice stick across screens.

## Key Insights

1. **The current formatter physically cannot render the design.** `formatMeters()` hardcodes `" m"`,
   so 0.15 m renders `"0.15 m"` and never `"15 cm"`. `formatImperial()` always returns the compound
   `5' 3"` form, which decision 8 explicitly kills. Both functions are deleted, not adapted.
2. **`formatLength` must stay a pure JVM function.** It is covered by JVM unit tests with no
   Robolectric and no `Context`. Therefore the **unit symbols `cm`/`m`/`in`/`ft` stay as Kotlin
   string literals** — they are SI/imperial symbols, not prose, and are not localised in practice.
   The *menu labels* ("Centimeters", …) do go to `strings.xml`. This is a deliberate, documented
   exception to decision 14; write it into the KDoc so nobody "fixes" it later.
3. **A unit that resets per screen is not a choice.** Today `state.unit` is per-screen state seeded
   from a parameter, and the m/ft chip's effect vanishes on the next screen entry. Decision 8 calls
   this a *hard user choice*, and decision 9 gives it two entry points on two different screens — so
   it needs one process-wide, persisted value. Add a tiny `UnitPreference` on the same
   namespaced-SharedPreferences pattern `CustomReferenceStore` already uses. Zero new dependencies.
4. **Only 6 call sites format anything**: `MeasureFrameLoop.kt:143,178`, `ShapeFrameLoop.kt:198,
   222,223,253,290`, `ShapeMath.formatBoxDimensions`/`formatCylinderDimensions`,
   `PhotoQuadCanvas.kt:110`. All go through `formatLength(meters, unit, locale)` already, so
   changing the enum does not touch any of them.
5. **`PhotoMeasureState.toggleUnit()` is dead code** — defined at line 171 with no call site anywhere
   in the photo UI. So the photo path has no unit control at all today; this phase gives it one.
6. Decimal places differ per unit and are a precision statement, not cosmetics — see the table below.
   `ft` at one decimal is *coarser* than centimetre precision (0.1 ft ≈ 3 cm); that is what decision
   8's `2.6 ft` example specifies, so it is intentional. Record it.

## Requirements

**Functional**
- `enum class LengthUnit { Cm, M, Inch, Ft }`, each with `metersPerUnit`.
- `formatLength(meters, unit, locale)` renders decimal in that single unit — no compound forms, no
  magnitude-based auto-switching.
- One `UnitMenu` composable, opened from two places (AR `UnitBtn`, photo `ColorPickerBar` `cm` badge
  — the second is wired in phase 08).
- Choosing a unit updates every visible label immediately and persists across screens and process
  restarts.
- The 6 existing formatter tests are **rewritten**, not deleted.

**Non-functional**
- `formatLength` stays allocation-light: it runs from `onSessionUpdated`, a per-frame hot path.
  `NumberFormat` instances must not be created per call — cache one per (unit, locale).
- `UnitMenu` items are ≥48dp tall (design's own `size-touchTarget: 48`).
- Menu text meets WCAG AA over its own surface.

## Architecture

| Unit | `metersPerUnit` | max fraction digits | symbol | 0.1524 m renders |
|---|---|---|---|---|
| `Cm` | 0.01 | 0 | `cm` | `15 cm` |
| `M` | 1.0 | 2 | `m` | `0.15 m` |
| `Inch` | 0.0254 | 1 | `in` | `6 in` |
| `Ft` | 0.3048 | 1 | `ft` | `0.5 ft` |

`minimumFractionDigits = 0` throughout, so trailing zeros trim (`1.60` → `1.6 m`). `isGroupingUsed
= false`. Decimal separator comes from `locale`, preserving today's `"1,6 m"` behaviour on
comma-decimal locales.

```kotlin
// common/domain/LengthUnit.kt
enum class LengthUnit(val metersPerUnit: Float, val symbol: String, val maxFractionDigits: Int) {
    Cm(0.01f, "cm", 0),
    M(1f, "m", 2),
    Inch(0.0254f, "in", 1),
    Ft(0.3048f, "ft", 1),
}

fun formatLength(meters: Float, unit: LengthUnit, locale: Locale = Locale.getDefault()): String
```

```kotlin
// common/data/UnitPreference.kt  (internal)
internal class UnitPreference(context: Context) {
    var unit: LengthUnit          // get/set, backed by SharedPreferences
}
// prefs file: "vn.apero.armeasure.unit", key "length_unit", stored by enum name
// unknown/absent name -> LengthUnit.Cm  (matches the design's default badge)
```

```kotlin
// common/ui/UnitMenu.kt  (internal)
@Composable internal fun UnitBtn(unit: LengthUnit, onClick: () -> Unit, modifier: Modifier)
@Composable internal fun UnitMenu(selected: LengthUnit, onSelect: (LengthUnit) -> Unit, onDismiss: () -> Unit)
```

`UnitMenu` is a `Popup`/`DropdownMenu` — not a bottom sheet. The design floats it near the trigger.

Unit flows into the state holders unchanged in shape: `MeasureState`/`ShapeMeasureState`/
`PhotoMeasureState` keep a `var unit`, but `toggleUnit()` becomes `setUnit(LengthUnit)` and the
screen seeds it from `UnitPreference` and writes back on change.

## Related Code Files

**Modify**
- `AR_feature/src/main/java/vn/apero/armeasure/common/domain/LengthUnit.kt` — rewrite: 4-value enum,
  one `formatLength`, delete `formatMeters` and `formatImperial`
- `AR_feature/src/test/java/vn/apero/armeasure/common/domain/LengthUnitTest.kt` — rewrite all 6
  tests, add 8 more
- `.../ar/presentation/ruler/MeasureState.kt` — `toggleUnit()` → `setUnit(LengthUnit)`; default
  `initialUnit` becomes `LengthUnit.Cm`
- `.../ar/presentation/shapes/ShapeMeasureState.kt` — same
- `.../photo/presentation/PhotoMeasureState.kt` — same (replaces the dead `toggleUnit`)
- `.../ar/presentation/ruler/MeasureScreen.kt:280-291` — the hardcoded `"m"`/`"ft"` `Text` chip
  becomes `UnitBtn` + `UnitMenu`
- `.../ar/presentation/shapes/ShapeMeasureScreen.kt:130-141` — same (this duplication disappears
  entirely in phase 05 when the two screens merge)
- `.../photo/presentation/PhotoMeasureScreen.kt` — seed `PhotoMeasureState` from `UnitPreference`
- `AR_feature/src/main/res/values/strings.xml` — add `armeasure_unit_cm`, `_m`, `_inch`, `_ft`,
  `armeasure_unit_menu_title`
- Every `LengthUnit.Metric` / `LengthUnit.Imperial` default-parameter reference (9 sites per the
  grep in the survey)

**Create**
- `AR_feature/src/main/java/vn/apero/armeasure/common/data/UnitPreference.kt`
- `AR_feature/src/main/java/vn/apero/armeasure/common/ui/UnitMenu.kt`

## Implementation Steps

1. Rewrite `LengthUnit.kt`: the 4-value enum with `metersPerUnit`/`symbol`/`maxFractionDigits`, and
   a single `formatLength`. Cache `NumberFormat` per (unit, locale) in a small private map — this is
   a per-frame path. Delete `formatMeters` and `formatImperial`.
2. Rewrite `LengthUnitTest.kt` to the 14 tests listed in Success Criteria. Keep the existing KDoc's
   argument for *why* these tests exist (a wrong format renders a plausible, false number that no
   visual QA catches) — it is still exactly right.
3. Fix the compile fallout: every `LengthUnit.Metric`/`.Imperial` reference. Default parameters
   become `LengthUnit.Cm`.
4. Add `UnitPreference`. Namespaced prefs file, enum stored by `name`, unknown value falls back to
   `Cm` (never throws — a corrupted pref must not crash a measuring screen).
5. Add `UnitBtn` + `UnitMenu` in `common/ui/`. `UnitBtn` renders the current unit's `symbol` as text
   in the existing chrome-pill style; `UnitMenu` is a 4-row popup, each row ≥48dp, selected row
   marked by **both** a check glyph and a fill (not colour alone — the UI review flags colour-only
   state as a colour-blind failure).
6. Replace `toggleUnit()` with `setUnit(LengthUnit)` in all three state holders.
7. Wire `UnitBtn`/`UnitMenu` into `MeasureScreen` and `ShapeMeasureScreen` in place of the
   hardcoded chip; wire `UnitPreference` read-on-enter / write-on-change in all three screens.
8. Gate.

## Todo List

- [ ] Rewrite `LengthUnit.kt` (4 units, one `formatLength`, cached `NumberFormat`)
- [ ] Delete `formatMeters` + `formatImperial`
- [ ] Rewrite the 6 formatter tests, add 8 → 14 total
- [ ] Fix all `LengthUnit.Metric`/`.Imperial` references
- [ ] Add `UnitPreference` (namespaced prefs, `Cm` fallback)
- [ ] Add `UnitBtn` + `UnitMenu` (48dp rows, check glyph **and** fill for selection)
- [ ] `toggleUnit()` → `setUnit()` in the 3 state holders
- [ ] Wire the menu + preference into the ruler, shape and photo screens
- [ ] Add 5 `armeasure_unit_*` strings
- [ ] Gate: `compileDebugKotlin testDebugUnitTest assembleDebug assembleRelease`
- [ ] On-device: pick each of the 4 units, confirm every visible label re-renders and the choice
      survives leaving and re-entering the screen and a force-stop

## Success Criteria

**75 tests pass** (67 − 6 rewritten + 14). The 14 formatter tests:

1. `Cm` renders whole centimetres — `0.15f` → `"15 cm"`
2. `Cm` never shows a decimal — `0.1547f` → `"15 cm"`
3. `M` trims the trailing zero — `1.6f` → `"1.6 m"`
4. `M` keeps two decimals when both are significant — `2.45f` → `"2.45 m"`
5. `M` never shows millimetre precision — `3.1547f` → `"3.15 m"`
6. `M` uses the locale decimal separator — `1.6f`, `Locale.GERMANY` → `"1,6 m"`
7. `Inch` renders one decimal — `0.2108f` → `"8.3 in"`
8. `Inch` uses the locale decimal separator — `Locale.GERMANY` → `"8,3 in"`
9. `Ft` renders one decimal — `0.7925f` → `"2.6 ft"`
10. `Ft` is never the compound form — output contains no `'` and no `"`
11. Zero renders without a sign in all four units — no `"-0 cm"`
12. Round-trip: for each unit, `meters → displayed value × metersPerUnit` is within its own
    precision of the input
13. `formatLength` dispatches correctly for all four enum entries from one input
14. A negative input (possible from a signed height) renders with a single leading `-`

Plus: `git grep -n 'formatMeters\|formatImperial\|Metric\|Imperial' AR_feature` returns nothing.
`assembleRelease` green. On-device, all four units render and persist.

## Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| `NumberFormat` allocated per call in a per-frame path → GC churn | medium | cache per (unit, locale); the existing KDoc already flags `onSessionUpdated` as hot |
| `ft` at 1 decimal loses real precision (0.1 ft ≈ 3 cm) | certain | it is decision 8's stated format; document the trade-off in the enum KDoc rather than silently "improving" it |
| Hardcoded `cm`/`in` symbols read as a violation of decision 14 | medium | documented exception in KDoc + in phase 10's README; menu labels *are* resources |
| `UnitPreference` is scope beyond the letter of decision 8 | — | flagged in Unresolved Questions; the alternative (per-screen reset) contradicts "hard user choice" |
| Popup positioning clips at the screen edge | medium | `Popup` with `alignment` + offset, tested on the attached Pixel 6 at both trigger positions |
| Corrupted/renamed pref value crashes on `enumValueOf` | low | `enumValues().firstOrNull { it.name == stored } ?: Cm` |

## Security Considerations

- New SharedPreferences file `vn.apero.armeasure.unit` — namespaced exactly like
  `vn.apero.armeasure.photo.custom_reference_objects` so no host prefs file can collide. `MODE_PRIVATE`.
- No PII: the stored value is one enum name.
- Never `enumValueOf` untrusted stored text without a fallback.
- No new permissions, no new dependencies, no network.

## Next Steps

- Unblocks phase 06 (AR chrome shows `UnitBtn` in the design TopBar) and phase 08 (the
  `ColorPickerBar` `cm` badge opens the same `UnitMenu`).
- Independent of phase 03 — different files, safe to swap order.
