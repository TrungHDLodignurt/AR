package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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
    fun `recovers a rectangle photographed at an angle, not aligned to the image axes`() {
        // Real photos rarely have the reference object perfectly square to the frame. The old
        // implementation only ever looked for near-0deg/near-90deg lines and returned null on
        // anything rotated past its 20deg tolerance — this is the regression test for that gap.
        val width = 200
        val height = 200
        val center = Vec2(100f, 100f)
        val halfWidth = 50f
        val halfHeight = 30f
        val angleDegrees = 35f
        val image = syntheticRotatedRectangle(width, height, center, halfWidth, halfHeight, angleDegrees)

        val edges = cannyEdges(image)
        val lines = houghLines(edges, width, height)
        val quad = quadFromLines(lines, center)

        requireNotNull(quad) { "expected a quad for a 35deg-rotated rectangle; got null. lines=$lines" }
        val angleRadians = angleDegrees * Math.PI.toFloat() / 180f
        val cosA = cos(angleRadians)
        val sinA = sin(angleRadians)
        fun rotated(dx: Float, dy: Float) = Vec2(center.x + dx * cosA - dy * sinA, center.y + dx * sinA + dy * cosA)
        val trueCorners = listOf(
            rotated(-halfWidth, -halfHeight),
            rotated(halfWidth, -halfHeight),
            rotated(halfWidth, halfHeight),
            rotated(-halfWidth, halfHeight),
        )
        quad.forEach { corner ->
            val nearestTrueCorner = trueCorners.minBy { distance(it, corner) }
            val error = distance(nearestTrueCorner, corner)
            assertTrue("corner $corner is $error px from the nearest true corner (expected < 8px)", error < 8f)
        }
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

    @Test
    fun `rejects a trapezoid whose opposite sides are badly imbalanced`() {
        // A real rectangle under perspective still has roughly-matched opposite sides. A quad
        // built from two lines that AREN'T actually parallel (allowed into the same "primary"
        // group by angleToleranceDegrees's tolerance, e.g. one edge partly occluded/misread as a
        // tilted line) produces a lopsided trapezoid instead — one side ~32% longer than its
        // "opposite". Real case that motivated this: sides 56px vs 89px on an otherwise
        // plausible-looking candidate.
        val tiltRadians = 19f * (Math.PI / 180.0).toFloat()
        val left = HoughLine(rho = 0f, thetaRadians = 0f, votes = 100) // x = 0
        val right = HoughLine(rho = 100f, thetaRadians = tiltRadians, votes = 100) // tilted "right" edge
        val top = HoughLine(rho = 0f, thetaRadians = (Math.PI / 2).toFloat(), votes = 100) // y = 0
        val bottom = HoughLine(rho = 100f, thetaRadians = (Math.PI / 2).toFloat(), votes = 100) // y = 100
        val tap = Vec2(40f, 50f)

        assertNull(quadFromLines(listOf(left, right, top, bottom), tap))
    }

    @Test
    fun `rejects a candidate quad whose corners fall far outside the image bounds`() {
        // Real 1542x2048-photo bug: two lines with very different rho (a nearby object edge and
        // an unrelated distant one) can intersect at a point nowhere near the actual photo, yet
        // still "contain" the tap point under the loose point-in-quad check alone. Handcrafted so
        // the ONLY candidate quad the 4 lines can form has 2 corners at y=9999, far past the
        // 200x200 image these lines claim to describe.
        val tap = Vec2(100f, 100f)
        val lines = listOf(
            HoughLine(rho = 40f, thetaRadians = 0f, votes = 100), // x=40
            HoughLine(rho = 160f, thetaRadians = 0f, votes = 100), // x=160
            HoughLine(rho = 30f, thetaRadians = (Math.PI / 2).toFloat(), votes = 100), // y=30
            HoughLine(rho = 9999f, thetaRadians = (Math.PI / 2).toFloat(), votes = 50), // y=9999 (bogus/distant)
        )

        assertNull(quadFromLines(lines, tap, imageWidth = 200f, imageHeight = 200f))
    }

    @Test
    fun `a known aspect ratio close to the true rectangle still finds the quad`() {
        val width = 200
        val height = 160
        val rectLeft = 40
        val rectTop = 30
        val rectRight = 160 // 120px long side
        val rectBottom = 120 // 90px short side -> true ratio 120/90 = 1.333
        val image = syntheticRectangle(width, height, rectLeft, rectTop, rectRight, rectBottom)

        val edges = cannyEdges(image)
        val lines = houghLines(edges, width, height)
        val tapPoint = Vec2((rectLeft + rectRight) / 2f, (rectTop + rectBottom) / 2f)
        val quad = quadFromLines(lines, tapPoint, expectedAspectRatio = 1.333f)

        requireNotNull(quad) { "a correct aspect-ratio hint should not reject the true rectangle" }
    }

    @Test
    fun `a known aspect ratio far from the true rectangle rejects the candidate`() {
        val width = 200
        val height = 160
        val rectLeft = 40
        val rectTop = 30
        val rectRight = 160 // true ratio 120/90 = 1.333
        val rectBottom = 120
        val image = syntheticRectangle(width, height, rectLeft, rectTop, rectRight, rectBottom)

        val edges = cannyEdges(image)
        val lines = houghLines(edges, width, height)
        val tapPoint = Vec2((rectLeft + rectRight) / 2f, (rectTop + rectBottom) / 2f)
        // Known object is a 5:1 sliver — nothing like the 1.33 square-ish rectangle actually
        // drawn, so the aspect-ratio constraint should reject the only candidate outright.
        val quad = quadFromLines(lines, tapPoint, expectedAspectRatio = 5f)

        assertNull(quad)
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

    /** Same idea as [syntheticRectangle] but the rectangle is rotated [angleDegrees] about [center]. */
    private fun syntheticRotatedRectangle(
        width: Int,
        height: Int,
        center: Vec2,
        halfWidth: Float,
        halfHeight: Float,
        angleDegrees: Float,
    ): GrayscaleImage {
        val angleRadians = angleDegrees * Math.PI.toFloat() / 180f
        val cosA = cos(-angleRadians)
        val sinA = sin(-angleRadians)
        val pixels = FloatArray(width * height) { 250f }
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Rotate the pixel back into the rectangle's own frame, then a plain axis-aligned check.
                val dx = x - center.x
                val dy = y - center.y
                val localX = dx * cosA - dy * sinA
                val localY = dx * sinA + dy * cosA
                if (abs(localX) <= halfWidth && abs(localY) <= halfHeight) {
                    pixels[y * width + x] = 20f
                }
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
