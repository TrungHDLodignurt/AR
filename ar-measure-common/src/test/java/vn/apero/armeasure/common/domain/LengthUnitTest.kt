package vn.apero.armeasure.common.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The formatting behind every displayed length.
 *
 * A wrong format renders a perfectly plausible label: the app does not crash, the screenshot
 * looks right, and the number is simply false (wrong precision, wrong locale separator, wrong
 * feet/inches rollover). No device pass or visual QA catches that — only these tests do.
 */
class LengthUnitTest {

    @Test
    fun `metric format trims the trailing zero`() {
        // 1.60 m must read "1.6 m", not "1.60 m".
        assertEquals("1.6 m", formatMeters(1.6f, Locale.US))
    }

    @Test
    fun `metric format keeps two decimals when both are significant`() {
        assertEquals("2.45 m", formatMeters(2.45f, Locale.US))
    }

    @Test
    fun `metric format never shows millimetre precision`() {
        // 2 decimals on metres is centimetre precision — a third would be a lie about the pose.
        assertEquals("3.15 m", formatMeters(3.1547f, Locale.US))
    }

    @Test
    fun `metric format uses the locale decimal separator`() {
        // The reference video shows "1,6 m" on a comma-decimal locale.
        assertEquals("1,6 m", formatMeters(1.6f, Locale.GERMANY))
    }

    @Test
    fun `imperial format splits feet and inches`() {
        // 1.6002 m == 63 in == 5 ft 3 in
        assertEquals("5' 3\"", formatImperial(1.6002f))
    }

    @Test
    fun `imperial format rolls twelve inches into a foot`() {
        // 0.3048 m == exactly 12 in, which must read 1' 0" and never 0' 12".
        assertEquals("1' 0\"", formatImperial(0.3048f))
    }
}
