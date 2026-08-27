package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mask-to-quad step, checked on hand-built masks.
 *
 * The case that matters most is [only the tapped region is measured]: a real photo of a desk segments
 * into several subjects, and a version that hulled every above-threshold pixel produced a rectangle
 * spanning the reference object plus the unrelated object next to it — which reads as a plausible
 * quad and silently miscalibrates every measurement taken afterwards.
 */
class MaskBlobTest {

    private val width = 200
    private val height = 120

    private fun maskWith(vararg boxes: IntArray): FloatArray {
        val mask = FloatArray(width * height)
        boxes.forEach { (left, top, right, bottom) ->
            for (y in top until bottom) for (x in left until right) mask[y * width + x] = 0.9f
        }
        return mask
    }

    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]
    private operator fun IntArray.component3() = this[2]
    private operator fun IntArray.component4() = this[3]

    private fun sides(quad: List<Vec2>) =
        (0 until 4).map { hypot(quad[(it + 1) % 4].x - quad[it].x, quad[(it + 1) % 4].y - quad[it].y) }

    @Test
    fun `a single masked rectangle becomes its own quad`() {
        val mask = maskWith(intArrayOf(40, 30, 140, 80))
        val quad = requireNotNull(quadFromMask(mask, width, height, Vec2(90f, 55f)))
        val edges = sides(quad)
        assertEquals(100f, edges[0], 2f)
        assertEquals(50f, edges[1], 2f)
    }

    @Test
    fun `only the tapped region is measured, not every masked pixel`() {
        // Two separate objects. Hulling both would give a ~160x50 rectangle instead of 60x50.
        val mask = maskWith(intArrayOf(10, 30, 70, 80), intArrayOf(130, 30, 190, 80))
        val quad = requireNotNull(quadFromMask(mask, width, height, Vec2(40f, 55f)))
        val edges = sides(quad)
        assertEquals(60f, edges.max(), 2f)
        assertEquals(50f, edges.min(), 2f)
    }

    @Test
    fun `a tap a few pixels off the object still finds it`() {
        val mask = maskWith(intArrayOf(40, 30, 140, 80))
        // Just outside the left edge — a plausible aiming error, not a miss.
        val quad = requireNotNull(quadFromMask(mask, width, height, Vec2(36f, 55f)))
        assertEquals(100f * 50f, polygonAreaOf(quad), 200f)
    }

    @Test
    fun `a tap nowhere near any masked region returns null`() {
        val mask = maskWith(intArrayOf(150, 90, 190, 115))
        assertNull(quadFromMask(mask, width, height, Vec2(20f, 20f)))
    }

    @Test
    fun `an empty mask returns null`() {
        assertNull(quadFromMask(FloatArray(width * height), width, height, Vec2(100f, 60f)))
    }

    @Test
    fun `mask speckle is too small to be an object`() {
        // 6x6 = 36 px, well under the 0.002 x 24000 = 48 px floor.
        val mask = maskWith(intArrayOf(100, 60, 106, 66))
        assertNull(quadFromMask(mask, width, height, Vec2(103f, 63f)))
    }

    @Test
    fun `a diagonal one-pixel bridge does not merge two objects`() {
        // 4-connectivity is deliberate: mask noise leaves diagonal touches between separate things.
        val mask = maskWith(intArrayOf(20, 20, 80, 70), intArrayOf(80, 70, 140, 110))
        val quad = requireNotNull(quadFromMask(mask, width, height, Vec2(50f, 45f)))
        val edges = sides(quad)
        assertTrue("edges $edges suggest the two boxes merged", edges.max() < 70f)
    }

    @Test
    fun `a rotated masked bar is measured at its own angle`() {
        val mask = FloatArray(width * height)
        // A 30-degree bar, drawn by filling a thick line.
        for (t in 0..100) {
            val cx = 50f + t * 0.87f
            val cy = 30f + t * 0.5f
            for (o in -12..12) {
                val x = (cx - o * 0.5f).toInt()
                val y = (cy + o * 0.87f).toInt()
                if (x in 0 until width && y in 0 until height) mask[y * width + x] = 0.9f
            }
        }
        val quad = requireNotNull(quadFromMask(mask, width, height, Vec2(93f, 55f)))
        val edges = sides(quad)
        assertTrue("long side ${edges[0]} should clearly exceed short side ${edges[1]}", edges[0] > edges[1] * 2f)
        // An axis-aligned box around this bar would be ~87x62 = 5400; the true bar is ~100x25 = 2500.
        assertTrue("area ${polygonAreaOf(quad)} looks like an axis-aligned box, not a rotated fit",
            polygonAreaOf(quad) < 4000f)
    }

    @Test
    fun `values below the confidence threshold are not part of the object`() {
        val mask = FloatArray(width * height)
        for (y in 30 until 80) for (x in 40 until 140) mask[y * width + x] = 0.9f
        // A low-confidence halo that must not widen the quad.
        for (y in 20 until 90) for (x in 30 until 150) if (mask[y * width + x] == 0f) mask[y * width + x] = 0.3f
        val quad = requireNotNull(quadFromMask(mask, width, height, Vec2(90f, 55f)))
        assertEquals(100f * 50f, polygonAreaOf(quad), 300f)
    }
}
