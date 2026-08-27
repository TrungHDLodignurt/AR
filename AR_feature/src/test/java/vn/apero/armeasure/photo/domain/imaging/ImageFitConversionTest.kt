package vn.apero.armeasure.photo.domain.imaging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pixel-space bridge between SCR-23's and SCR-24's differently-sized photo boxes (see
 * `PhotoMeasureState.remapToCanvas`): converting a point out of one aspect-fit canvas and into
 * another, via the underlying photo's own pixel grid, must round-trip and must not distort the
 * measured position.
 */
class ImageFitConversionTest {

    private val eps = 1e-2f

    @Test
    fun `toBitmapSpace recovers the photo pixel a display point was drawn from`() {
        // A 400x200 photo aspect-fit into a 300x300 square canvas: scale = min(300/400, 300/200)
        // = 0.75, so it fills the full 300 width and is letterboxed top/bottom by 75 (offset 0,75).
        val displayPoint = Vec2(0f, 75f) // the photo's own top-left corner, on screen
        val bitmapPoint = toBitmapSpace(displayPoint, photoWidth = 400f, photoHeight = 200f, canvasWidth = 300f, canvasHeight = 300f)
        assertEquals(0f, bitmapPoint.x, eps)
        assertEquals(0f, bitmapPoint.y, eps)
    }

    @Test
    fun `toDisplaySpace is the exact inverse of toBitmapSpace`() {
        val original = Vec2(123f, 45f)
        val bitmapPoint = toBitmapSpace(original, photoWidth = 800f, photoHeight = 600f, canvasWidth = 354f, canvasHeight = 500f)
        val restored = toDisplaySpace(bitmapPoint, photoWidth = 800f, photoHeight = 600f, canvasWidth = 354f, canvasHeight = 500f)
        assertEquals(original.x, restored.x, eps)
        assertEquals(original.y, restored.y, eps)
    }

    @Test
    fun `a point remapped across two different-sized canvases lands on the same photo pixel`() {
        val photoWidth = 1000f
        val photoHeight = 1500f
        val scr23Canvas = 354f to 690f
        val scr24Canvas = 354f to 480f

        val pointOnScr23 = Vec2(177f, 300f)
        val bitmapPoint = toBitmapSpace(pointOnScr23, photoWidth, photoHeight, scr23Canvas.first, scr23Canvas.second)
        val pointOnScr24 = toDisplaySpace(bitmapPoint, photoWidth, photoHeight, scr24Canvas.first, scr24Canvas.second)

        // Remapping back must recover the exact original SCR-23 point — the round trip a segment
        // commit relies on.
        val roundTrip = toDisplaySpace(
            toBitmapSpace(pointOnScr24, photoWidth, photoHeight, scr24Canvas.first, scr24Canvas.second),
            photoWidth,
            photoHeight,
            scr23Canvas.first,
            scr23Canvas.second,
        )
        assertEquals(pointOnScr23.x, roundTrip.x, eps)
        assertEquals(pointOnScr23.y, roundTrip.y, eps)
    }
}
