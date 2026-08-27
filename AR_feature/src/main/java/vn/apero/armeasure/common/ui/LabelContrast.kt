package vn.apero.armeasure.common.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** WCAG relative-luminance gamma correction for one sRGB channel already in `[0, 1]`. */
private fun linearize(channel: Float): Float =
    if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

private fun relativeLuminance(color: Color): Float =
    0.2126f * linearize(color.red) + 0.7152f * linearize(color.green) + 0.0722f * linearize(color.blue)

/**
 * WCAG 2.x contrast ratio between [a] and [b] — symmetric, always in `[1, 21]`; `21.0` only for
 * pure black against pure white. Pure math, no Android/Compose-runtime dependency beyond [Color]
 * itself, so this runs in a plain JUnit test with no Robolectric.
 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val lumA = relativeLuminance(a)
    val lumB = relativeLuminance(b)
    val lighter = maxOf(lumA, lumB)
    val darker = minOf(lumA, lumB)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * White or [ArMeasureTokens.TextPrimary] — whichever actually clears more contrast against
 * [background] — for text drawn on a colour the *user* chose (the photo measuring line's label).
 * A fixed text colour cannot survive that: the design's own white-on-red label is 4.1:1 (fails
 * AA), and white-on-yellow would be far worse.
 *
 * Deliberately not a fixed luminance cutoff (e.g. "use white below 0.5"): for a background where
 * both candidates are middling, only comparing the two real ratios picks the correct side —
 * see `LabelContrastTest`'s palette-wide assertion, which is what actually exercises this.
 */
internal fun labelTextColorFor(background: Color): Color {
    val white = Color.White
    val textPrimary = ArMeasureTokens.TextPrimary
    return if (contrastRatio(white, background) >= contrastRatio(textPrimary, background)) white else textPrimary
}
