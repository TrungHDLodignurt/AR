package vn.quancua.artapemeasure.photomeasure

import kotlin.math.sqrt
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end test of the Canny+Hough auto-fit pipeline (`CannyEdgeDetector.kt` +
 * `HoughTransform.kt` + `QuadFromEdges.kt`) against a synthetic image — no Bitmap, no device,
 * same reasoning as `HomographyTest`: the algorithm either recovers a known rectangle's corners
 * or it doesn't, and that's checkable without a real photo.
 */
class QuadFromEdgesTest {

    @Test
    fun `recovers a synthetic rectangle's corners from its silhouette`() {
        val width = 200
        val height = 160
        val rectLeft = 40
        val rectTop = 30
        val rectRight = 160
        val rectBottom = 120
        val image = syntheticRectangle(width, height, rectLeft, rectTop, rectRight, rectBottom)

        val edges = cannyEdges(image)
        val lines = houghLines(edges, width, height)
        val tapPoint = Vec2((rectLeft + rectRight) / 2f, (rectTop + rectBottom) / 2f)
        val quad = quadFromLines(lines, tapPoint)

        requireNotNull(quad) { "expected a quad; got null. lines=$lines" }
        val trueCorners = listOf(
            Vec2(rectLeft.toFloat(), rectTop.toFloat()),
            Vec2(rectRight.toFloat(), rectTop.toFloat()),
            Vec2(rectRight.toFloat(), rectBottom.toFloat()),
            Vec2(rectLeft.toFloat(), rectBottom.toFloat()),
        )
        quad.forEach { corner ->
            val nearestTrueCorner = trueCorners.minBy { distance(it, corner) }
            val error = distance(nearestTrueCorner, corner)
            assertTrue("corner $corner is $error px from the nearest true corner (expected < 6px)", error < 6f)
        }
    }

    @Test
    fun `a rectangle taller than it is wide still orients long side as the first edge`() {
        // Same convention QuadFromEdges promises: corners[0]-corners[1] must be the LONGER edge.
        val width = 160
        val height = 220
        val rectLeft = 40
        val rectTop = 20
        val rectRight = 120
        val rectBottom = 200
        val image = syntheticRectangle(width, height, rectLeft, rectTop, rectRight, rectBottom)

        val edges = cannyEdges(image)
        val lines = houghLines(edges, width, height)
        val tapPoint = Vec2((rectLeft + rectRight) / 2f, (rectTop + rectBottom) / 2f)
        val quad = requireNotNull(quadFromLines(lines, tapPoint))

        val firstEdge = distance(quad[0], quad[1])
        val secondEdge = distance(quad[1], quad[2])
        assertTrue("first edge ($firstEdge) should be the longer one (second is $secondEdge)", firstEdge >= secondEdge)
    }

    @Test
    fun `a blank image has no lines to build a quad from`() {
        val width = 100
        val height = 100
        val blank = GrayscaleImage(width, height, FloatArray(width * height) { 200f })
        val edges = cannyEdges(blank)
        val lines = houghLines(edges, width, height)
        assertNull(quadFromLines(lines, Vec2(50f, 50f)))
    }

    private fun syntheticRectangle(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): GrayscaleImage {
        val pixels = FloatArray(width * height) { 250f }
        for (y in top until bottom) {
            for (x in left until right) {
                pixels[y * width + x] = 20f
            }
        }
        return GrayscaleImage(width, height, pixels)
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }
}
