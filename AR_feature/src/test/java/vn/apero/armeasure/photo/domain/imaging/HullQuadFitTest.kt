package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [quadFromHull] exists because [minAreaRect] cannot express what a photo shows: a rectangle shot at
 * an angle projects to a trapezoid with unequal opposite sides, and a rectangle forced onto it is both
 * too big and wrong at every corner. These tests are the ones that would have caught the box "staying
 * perfectly square and never shrinking" on a tilted photo.
 */
class HullQuadFitTest {

    private fun sides(quad: List<Vec2>) =
        (0 until 4).map { hypot(quad[(it + 1) % 4].x - quad[it].x, quad[(it + 1) % 4].y - quad[it].y) }

    /** Fills a convex polygon with a dense point grid, the way a segmentation mask arrives. */
    private fun fill(polygon: List<Vec2>, step: Int = 2): List<Vec2> {
        val minX = polygon.minOf { it.x }.toInt()
        val maxX = polygon.maxOf { it.x }.toInt()
        val minY = polygon.minOf { it.y }.toInt()
        val maxY = polygon.maxOf { it.y }.toInt()
        val points = mutableListOf<Vec2>()
        for (x in minX..maxX step step) {
            for (y in minY..maxY step step) {
                if (inside(Vec2(x.toFloat(), y.toFloat()), polygon)) points.add(Vec2(x.toFloat(), y.toFloat()))
            }
        }
        return points
    }

    private fun inside(point: Vec2, polygon: List<Vec2>): Boolean {
        var sign = 0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val cross = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
            if (abs(cross) < 1e-4f) continue
            val current = if (cross > 0) 1 else -1
            if (sign == 0) sign = current else if (sign != current) return false
        }
        return true
    }

    @Test
    fun `a perspective trapezoid keeps its unequal opposite sides`() {
        // Near edge 300 wide, far edge 180 — what a rectangle on a table looks like from a low angle.
        val trapezoid = listOf(Vec2(160f, 100f), Vec2(340f, 100f), Vec2(400f, 300f), Vec2(100f, 300f))
        val quad = requireNotNull(quadFromHull(fill(trapezoid)))
        val edges = sides(quad)
        val topBottom = listOf(edges[0], edges[2]).sorted()
        assertTrue(
            "opposite sides ${edges[0]} and ${edges[2]} came out equal — the fit collapsed to a rectangle",
            topBottom[1] - topBottom[0] > 60f,
        )
    }

    @Test
    fun `a perspective trapezoid is fitted tightly, not boxed`() {
        val trapezoid = listOf(Vec2(160f, 100f), Vec2(340f, 100f), Vec2(400f, 300f), Vec2(100f, 300f))
        val trueArea = polygonAreaOf(trapezoid)
        val quad = requireNotNull(quadFromHull(fill(trapezoid)))
        // A min-area RECTANGLE around this shape is ~300x200 = 60000 against a true area of 48000,
        // i.e. 25% too big. The quad fit has to be far closer than that.
        assertEquals(trueArea, polygonAreaOf(quad), trueArea * 0.06f)
    }

    @Test
    fun `each corner lands close to the true corner`() {
        val trapezoid = listOf(Vec2(160f, 100f), Vec2(340f, 100f), Vec2(400f, 300f), Vec2(100f, 300f))
        val quad = requireNotNull(quadFromHull(fill(trapezoid)))
        trapezoid.forEach { trueCorner ->
            val nearest = quad.minOf { hypot(it.x - trueCorner.x, it.y - trueCorner.y) }
            assertTrue("no fitted corner within 8px of $trueCorner (nearest ${nearest})", nearest < 8f)
        }
    }

    @Test
    fun `rounded corners do not pull the sides inward`() {
        // A phone silhouette: a rectangle with the corners cut off. A fit that used the hull vertices
        // directly would cut the corners too and undersize the object; the edge-fit stage must not.
        val rounded = listOf(
            Vec2(120f, 100f), Vec2(280f, 100f), Vec2(300f, 120f), Vec2(300f, 200f),
            Vec2(280f, 220f), Vec2(120f, 220f), Vec2(100f, 200f), Vec2(100f, 120f),
        )
        val quad = requireNotNull(quadFromHull(fill(rounded)))
        val edges = sides(quad)
        // True extents are 200 x 120 including the cut corners.
        assertEquals(200f, maxOf(edges[0], edges[2]), 10f)
        assertEquals(120f, maxOf(edges[1], edges[3]), 10f)
    }

    @Test
    fun `the long side comes first even when perspective makes the pair unequal`() {
        // Long axis vertical, and the two long sides differ, so the orientation choice has to average.
        val trapezoid = listOf(Vec2(200f, 80f), Vec2(260f, 80f), Vec2(290f, 400f), Vec2(170f, 400f))
        val quad = requireNotNull(quadFromHull(fill(trapezoid)))
        val edges = sides(quad)
        assertTrue(
            "averaged first pair ${(edges[0] + edges[2]) / 2} should exceed ${(edges[1] + edges[3]) / 2}",
            (edges[0] + edges[2]) / 2f > (edges[1] + edges[3]) / 2f,
        )
    }

    @Test
    fun `an axis-aligned rectangle is still recovered exactly`() {
        val rect = listOf(Vec2(50f, 40f), Vec2(250f, 40f), Vec2(250f, 140f), Vec2(50f, 140f))
        val quad = requireNotNull(quadFromHull(fill(rect)))
        assertEquals(200f * 100f, polygonAreaOf(quad), 200f * 100f * 0.04f)
        val edges = sides(quad)
        assertEquals(200f, edges[0], 5f)
        assertEquals(100f, edges[1], 5f)
    }

    @Test
    fun `a rotated rectangle is recovered at its own angle`() {
        val angle = Math.toRadians(25.0)
        val cos = Math.cos(angle).toFloat()
        val sin = Math.sin(angle).toFloat()
        val rect = listOf(Vec2(0f, 0f), Vec2(220f, 0f), Vec2(220f, 90f), Vec2(0f, 90f))
        val rotated = rect.map { Vec2(it.x * cos - it.y * sin + 200f, it.x * sin + it.y * cos + 150f) }
        val quad = requireNotNull(quadFromHull(fill(rotated)))
        assertEquals(220f * 90f, polygonAreaOf(quad), 220f * 90f * 0.06f)
    }

    @Test
    fun `a degenerate point set is rejected rather than fitted`() {
        assertTrue(quadFromHull(listOf(Vec2(0f, 0f), Vec2(10f, 0f), Vec2(20f, 0f))) == null)
    }
}
