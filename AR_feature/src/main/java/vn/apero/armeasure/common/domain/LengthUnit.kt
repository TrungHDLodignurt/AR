package vn.apero.armeasure.common.domain

import java.text.NumberFormat
import java.util.Locale

/**
 * Display unit for every measured length.
 *
 * [MeasurementResult] always stores metres — unit is a pure display concern, chosen once by the
 * user (see `UnitPreference`) and rendered by [formatLength] as a plain decimal in exactly that
 * one unit: no compound feet-and-inches form (`2'7"`), no magnitude-based auto-switching between
 * units. A hard user choice, not a suggestion.
 *
 * [symbol] is a hardcoded SI/imperial abbreviation, not prose — a deliberate, documented
 * exception to the "no string literals in Kotlin" rule: [formatLength] must stay a pure JVM
 * function (it runs from `onSessionUpdated`, a per-frame hot path, and is covered by plain JUnit
 * tests with no Robolectric and no `Context` to resolve a string resource from), and `cm`/`m`/
 * `in`/`ft` are not localised in practice. The menu labels shown in `UnitMenu` ("Centimeters",
 * …) are real, localisable string resources — only these bare symbols are not.
 *
 * [maxFractionDigits] is a precision statement, not cosmetics: `Ft` at one decimal is coarser
 * than centimetre precision (0.1 ft ≈ 3 cm). That is intentional — matches the design's own
 * `2.6 ft` example — not a rounding bug to "improve".
 */
enum class LengthUnit(val metersPerUnit: Float, val symbol: String, val maxFractionDigits: Int) {
    Cm(0.01f, "cm", 0),
    M(1f, "m", 2),
    Inch(0.0254f, "in", 1),
    Ft(0.3048f, "ft", 1),
}

// One NumberFormat per (unit, locale), never allocated per call: formatLength runs from
// onSessionUpdated, a per-frame hot path, and NumberFormat construction is not free.
private val formatterCache = HashMap<Pair<LengthUnit, Locale>, NumberFormat>()

private fun formatterFor(unit: LengthUnit, locale: Locale): NumberFormat =
    formatterCache.getOrPut(unit to locale) {
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = unit.maxFractionDigits
            minimumFractionDigits = 0
            isGroupingUsed = false
        }
    }

/**
 * Renders [meters] as a decimal in [unit] — no compound form, no unit switching. The decimal
 * separator comes from [locale] (e.g. `"1,6 m"` on a comma-decimal locale). A negative input
 * (possible from a signed height) renders with a single leading `-`; an exact zero never renders
 * with one.
 */
fun formatLength(meters: Float, unit: LengthUnit, locale: Locale = Locale.getDefault()): String {
    val value = meters / unit.metersPerUnit
    // value == 0f is true for both +0.0f and -0.0f in Kotlin, so this also normalises -0.0f to
    // +0.0f — otherwise some NumberFormat/locale combinations would render "-0".
    val safeValue = if (value == 0f) 0f else value
    val formatted = formatterFor(unit, locale).format(safeValue.toDouble())
    return "$formatted ${unit.symbol}"
}
