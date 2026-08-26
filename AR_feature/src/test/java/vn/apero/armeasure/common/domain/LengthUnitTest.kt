package vn.apero.armeasure.common.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import java.util.Locale

/**
 * The formatting behind every displayed length.
 *
 * A wrong format renders a perfectly plausible label: the app does not crash, the screenshot
 * looks right, and the number is simply false (wrong precision, wrong locale separator, wrong
 * rounding). No device pass or visual QA catches that — only these tests do.
 */
class LengthUnitTest {

    @Test
    fun `cm renders whole centimetres`() {
        assertEquals("15 cm", formatLength(0.15f, LengthUnit.Cm, Locale.US))
    }

    @Test
    fun `cm never shows a decimal`() {
        assertEquals("15 cm", formatLength(0.1547f, LengthUnit.Cm, Locale.US))
    }

    @Test
    fun `m trims the trailing zero`() {
        assertEquals("1.6 m", formatLength(1.6f, LengthUnit.M, Locale.US))
    }

    @Test
    fun `m keeps two decimals when both are significant`() {
        assertEquals("2.45 m", formatLength(2.45f, LengthUnit.M, Locale.US))
    }

    @Test
    fun `m never shows millimetre precision`() {
        // 2 decimals on metres is centimetre precision — a third would be a lie about the pose.
        assertEquals("3.15 m", formatLength(3.1547f, LengthUnit.M, Locale.US))
    }

    @Test
    fun `m uses the locale decimal separator`() {
        assertEquals("1,6 m", formatLength(1.6f, LengthUnit.M, Locale.GERMANY))
    }

    @Test
    fun `inch renders one decimal`() {
        assertEquals("8.3 in", formatLength(0.2108f, LengthUnit.Inch, Locale.US))
    }

    @Test
    fun `inch uses the locale decimal separator`() {
        assertEquals("8,3 in", formatLength(0.2108f, LengthUnit.Inch, Locale.GERMANY))
    }

    @Test
    fun `ft renders one decimal`() {
        assertEquals("2.6 ft", formatLength(0.7925f, LengthUnit.Ft, Locale.US))
    }

    @Test
    fun `ft is never the compound form`() {
        // Decision 8 kills the feet-and-inches form outright — no "'" and no '"' anywhere.
        val rendered = formatLength(1.9f, LengthUnit.Ft, Locale.US)
        assertFalse(rendered.contains("'"))
        assertFalse(rendered.contains("\""))
    }

    @Test
    fun `zero renders without a sign in all four units`() {
        for (unit in LengthUnit.entries) {
            val rendered = formatLength(0f, unit, Locale.US)
            assertFalse("$unit rendered \"$rendered\"", rendered.contains("-"))
        }
    }

    @Test
    fun `round trip stays within each unit's own precision`() {
        val meters = 1.8734f
        for (unit in LengthUnit.entries) {
            val rendered = formatLength(meters, unit, Locale.US)
            val displayedValue = rendered.removeSuffix(" ${unit.symbol}").toFloat()
            val roundTripMeters = displayedValue * unit.metersPerUnit
            // Half of the smallest step this unit can display, in metres.
            val tolerance = unit.metersPerUnit * 0.5f * Math.pow(10.0, -unit.maxFractionDigits.toDouble()).toFloat()
            assertTrue(
                "unit=$unit rendered=$rendered diff=${abs(roundTripMeters - meters)} tolerance=$tolerance",
                abs(roundTripMeters - meters) <= tolerance + 1e-4f,
            )
        }
    }

    @Test
    fun `formatLength dispatches correctly for all four enum entries from one input`() {
        // 0.1524 m: the table's own worked example.
        assertEquals("15 cm", formatLength(0.1524f, LengthUnit.Cm, Locale.US))
        assertEquals("0.15 m", formatLength(0.1524f, LengthUnit.M, Locale.US))
        assertEquals("6 in", formatLength(0.1524f, LengthUnit.Inch, Locale.US))
        assertEquals("0.5 ft", formatLength(0.1524f, LengthUnit.Ft, Locale.US))
    }

    @Test
    fun `a negative input renders with a single leading dash`() {
        val rendered = formatLength(-0.5f, LengthUnit.Cm, Locale.US)
        assertEquals("-50 cm", rendered)
        assertEquals(1, rendered.count { it == '-' })
    }
}
