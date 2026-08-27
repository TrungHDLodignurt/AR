package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [minAreaRect] is the whole geometric contribution of the segmentation path — the mask decides which
 * pixels belong to the object, and this decides what rectangle they describe. A quiet bug here shows
 * up as a plausible-looking but mis-scaled measurement, so it is checked against hand-computable
 * cases rather than only through the pipeline.
 */
class MinAreaRectTest {

    private fun sides(quad: List<Vec2>) =
        (0 until 4).map { hypot(quad[(it + 1) % 4].x - quad[it].x, quad[(it + 1) % 4].y - quad[it].y) }

    @Test
    fun `an axis-aligned rectangle's own corners come back unchanged in area`() {
        val points = listOf(Vec2(10f, 20f), Vec2(110f, 20f), Vec2(110f, 70f), Vec2(10f, 70f))
        val rect = requireNotNull(minAreaRect(points))
        assertEquals(100f * 50f, polygonAreaOf(rect), 1f)
    }

    @Test
    fun `the long side comes first, as confirmReference requires`() {
        // Taller than wide: the long side is vertical, so the orientation fix-up has to rotate.
        val points = listOf(Vec2(0f, 0f), Vec2(40f, 0f), Vec2(40f, 200f), Vec2(0f, 200f))
        val rect = requireNotNull(minAreaRect(points))
        val edges = sides(rect)
        assertTrue("first edge ${edges[0]} should be the longer of ${edges[0]}/${edges[1]}", edges[0] > edges[1])
        assertEquals(200f, edges[0], 1f)
        assertEquals(40f, edges[1], 1f)
    }

    @Test
    fun `a rotated rectangle is recovered at its own angle, not axis-aligned`() {
        // 120x60 rotated 30 degrees. An axis-aligned bounding box would be much larger in area, so
        // this is what separates a real rotating-calipers fit from a plain min/max box.
        val angle = Math.toRadians(30.0)
        val cos = Math.cos(angle).toFloat()
        val sin = Math.sin(angle).toFloat()
        val corners = listOf(Vec2(0f, 0f), Vec2(120f, 0f), Vec2(120f, 60f), Vec2(0f, 60f))
        val rotated = corners.map { Vec2(it.x * cos - it.y * sin + 300f, it.x * sin + it.y * cos + 400f) }

        val rect = requireNotNull(minAreaRect(rotated))
        assertEquals(120f * 60f, polygonAreaOf(rect), 20f)
        val edges = sides(rect)
        assertEquals(120f, edges[0], 1f)
        assertEquals(60f, edges[1], 1f)
    }

    @Test
    fun `a noisy blob of interior points still yields the enclosing rectangle`() {
        // What a segmentation mask actually looks like: filled, slightly ragged, not just an outline.
        val points = mutableListOf<Vec2>()
        for (x in 0..80 step 2) {
            for (y in 0..40 step 2) {
                points.add(Vec2(x.toFloat(), y.toFloat()))
            }
        }
        val rect = requireNotNull(minAreaRect(points))
        assertEquals(80f * 40f, polygonAreaOf(rect), 10f)
    }

    @Test
    fun `every input point is inside the returned rectangle`() {
        val points = listOf(
            Vec2(5f, 5f), Vec2(90f, 12f), Vec2(88f, 61f), Vec2(3f, 55f),
            Vec2(45f, 2f), Vec2(46f, 63f), Vec2(20f, 30f),
        )
        val rect = requireNotNull(minAreaRect(points))
        points.forEach { p ->
            assertTrue("$p fell outside $rect", isInside(p, rect))
        }
    }

    @Test
    fun `collinear points are rejected rather than measured`() {
        assertNull(minAreaRect(listOf(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(20f, 0f), Vec2(30f, 0f))))
    }

    @Test
    fun `too few points are rejected`() {
        assertNull(minAreaRect(listOf(Vec2(0f, 0f), Vec2(10f, 10f))))
    }

    @Test
    fun `the hull of a filled square is its four corners`() {
        val points = mutableListOf<Vec2>()
        for (x in 0..10) for (y in 0..10) points.add(Vec2(x.toFloat(), y.toFloat()))
        val hull = requireNotNull(convexHull(points))
        assertEquals(4, hull.size)
        assertEquals(100f, polygonAreaOf(hull), 0.01f)
    }

    private fun isInside(point: Vec2, quad: List<Vec2>): Boolean {
        var sign = 0f
        for (i in quad.indices) {
            val a = quad[i]
            val b = quad[(i + 1) % quad.size]
            val cross = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
            if (abs(cross) < 1e-2f) continue
            if (sign == 0f) sign = if (cross > 0) 1f else -1f
            else if ((cross > 0) != (sign > 0)) return false
        }
        return true
    }
}
