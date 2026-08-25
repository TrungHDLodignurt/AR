package vn.apero.armeasure.common.domain

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formats metres the way the reference app does: at most two decimals, trailing zero
 * trimmed, decimal separator from [locale] — "1,6 m", "2,45 m".
 *
 * Two decimals on metres is centimetre precision, already at the edge of what the
 * underlying pose supports. A third decimal would render millimetres and would be a lie
 * about that precision.
 */
fun formatMeters(meters: Float, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
        isGroupingUsed = false
    }
    return "${format.format(meters)} m"
}

/** Feet and inches, rounded to the nearest inch — "5' 3\"". */
fun formatImperial(meters: Float): String {
    val totalInches = (meters * 39.3700787f).roundToInt()
    return "${totalInches / 12}' ${totalInches % 12}\""
}

/** Display unit. Imperial is a hard requirement for the US market. */
enum class LengthUnit { Metric, Imperial }

fun formatLength(meters: Float, unit: LengthUnit, locale: Locale = Locale.getDefault()): String =
    when (unit) {
        LengthUnit.Metric -> formatMeters(meters, locale)
        LengthUnit.Imperial -> formatImperial(meters)
    }
