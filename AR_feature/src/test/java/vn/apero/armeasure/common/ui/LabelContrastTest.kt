package vn.apero.armeasure.common.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.apero.armeasure.photo.presentation.PhotoLineColors

/** Drives [labelTextColorFor] and [contrastRatio] — see phase 08's success criteria for the exact 4 assertions. */
class LabelContrastTest {

    @Test
    fun `dark palette entries get white label text`() {
        assertEquals(Color.White, labelTextColorFor(PhotoLineColors[0])) // red
        assertEquals(Color.White, labelTextColorFor(PhotoLineColors[4])) // purple
    }

    @Test
    fun `light palette entries get TextPrimary label text`() {
        assertEquals(ArMeasureTokens.TextPrimary, labelTextColorFor(PhotoLineColors[2])) // yellow
        assertEquals(ArMeasureTokens.TextPrimary, labelTextColorFor(PhotoLineColors[3])) // green
    }

    @Test
    fun `every palette colour clears WCAG AA 4point5 with its chosen label text colour`() {
        PhotoLineColors.forEach { background ->
            val text = labelTextColorFor(background)
            val ratio = contrastRatio(text, background)
            assertTrue("contrast for $background was only $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun `contrastRatio is symmetric and reaches 21 for black on white`() {
        assertEquals(contrastRatio(Color.Black, Color.White), contrastRatio(Color.White, Color.Black), 0.0001f)
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
    }
}
