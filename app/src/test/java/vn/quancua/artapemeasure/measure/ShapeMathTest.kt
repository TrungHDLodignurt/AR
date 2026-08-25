package vn.quancua.artapemeasure.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.sqrt

/**
 * The arithmetic behind the box/cylinder tools' rectangle, circle and height extrusion.
 *
 * Same reasoning as [MeasureMathTest]: a wrong basis or a dropped sign renders a perfectly
 * plausible-looking wireframe with a false dimension baked into it — only these tests catch that,
 * a screenshot cannot.
 */
class ShapeMathTest {

    private val eps = 1e-4f
    private val up = Vec3(0f, 1f, 0f)

    @Test
    fun `plane basis for a floor is horizontal and perpendicular to itself`() {
        val basis = planeBasis(up)
        assertEquals(0f, basis.u.dot(up), eps)
        assertEquals(0f, basis.v.dot(up), eps)
        assertEquals(0f, basis.u.dot(basis.v), eps)
        assertEquals(1f, sqrt(basis.u.dot(basis.u)), eps)
        assertEquals(1f, sqrt(basis.v.dot(basis.v)), eps)
    }

    @Test
    fun `plane basis for a wall is also orthonormal`() {
        // A vertical (wall) plane's normal points sideways, not up — the reference-vector
        // fallback in planeBasis must still produce two perpendicular unit axes.
        val wallNormal = Vec3(1f, 0f, 0f)
        val basis = planeBasis(wallNormal)
        assertEquals(0f, basis.u.dot(wallNormal), eps)
        assertEquals(0f, basis.v.dot(wallNormal), eps)
        assertEquals(0f, basis.u.dot(basis.v), eps)
    }

    @Test
    fun `rectangle from points reports the right edge lengths`() {
        val basis = planeBasis(up)
        val origin = Vec3(0f, 0f, 0f)
        // 2 along u, 3 along v, plus noise perpendicular to the plane that must be ignored.
        val second = origin + basis.u * 2f + basis.v * 3f + up * 0.05f
        val rect = rectangleFromPoints(origin, second, basis)
        assertEquals(2f, rect.lengthU, eps)
        assertEquals(3f, rect.lengthV, eps)
        assertEquals(4, rect.corners.size)
    }

    @Test
    fun `rectangle corners form a closed loop back to the origin`() {
        val basis = planeBasis(up)
        val origin = Vec3(1f, 0f, 1f)
        val corners = rectangleCorners(origin, basis, lengthU = 2f, lengthV = 1.5f)
        assertEquals(origin, corners[0])
        // Adjacent corners differ by exactly one edge length along one axis.
        assertEquals(2f, measureDistanceMeters(corners[0], corners[1]), eps)
        assertEquals(1.5f, measureDistanceMeters(corners[1], corners[2]), eps)
    }

    @Test
    fun `rectangle handles a negative edge — dragging toward the opposite quadrant`() {
        val basis = planeBasis(up)
        val origin = Vec3(0f, 0f, 0f)
        val second = origin - basis.u * 2f + basis.v * 1f
        val rect = rectangleFromPoints(origin, second, basis)
        assertEquals(-2f, rect.lengthU, eps)
        assertEquals(1f, rect.lengthV, eps)
    }

    @Test
    fun `circle from points reports distance as the radius`() {
        val basis = planeBasis(up)
        val center = Vec3(0f, 0f, 0f)
        val edge = center + basis.u * 3f
        val circle = circleFromPoints(center, edge, basis)
        assertEquals(3f, circle.radius, eps)
        assertEquals(24, circle.ring.size)
    }

    @Test
    fun `circle ring points all sit exactly one radius from the center`() {
        val basis = planeBasis(up)
        val center = Vec3(1f, 2f, 3f)
        val ring = circleRing(center, basis, radius = 2f, segments = 16)
        ring.forEach { point ->
            assertEquals(2f, measureDistanceMeters(center, point), eps)
        }
    }

    @Test
    fun `circle ring points lie in the plane — no normal-axis drift`() {
        val basis = planeBasis(up)
        val center = Vec3(0f, 5f, 0f)
        val ring = circleRing(center, basis, radius = 1f, segments = 12)
        ring.forEach { point ->
            assertEquals(0f, (point - center).dot(up), eps)
        }
    }

    @Test
    fun `height along axis is the straight distance when climbing straight up`() {
        val base = Vec3(0f, 0f, 0f)
        val top = Vec3(0f, 1.5f, 0f)
        assertEquals(1.5f, heightAlongAxis(base, top, up), eps)
    }

    @Test
    fun `height along axis ignores sideways drift from a wavering hand`() {
        val base = Vec3(0f, 0f, 0f)
        val top = Vec3(0.3f, 1.5f, 0.2f)
        assertEquals(1.5f, heightAlongAxis(base, top, up), eps)
    }

    @Test
    fun `height along axis is negative when the reticle lands below the base`() {
        val base = Vec3(0f, 1f, 0f)
        val top = Vec3(0f, 0.5f, 0f)
        assertTrue(heightAlongAxis(base, top, up) < 0f)
    }

    @Test
    fun `loop edges for a rectangle produce 4 base, 4 top and 4 vertical edges`() {
        val basis = planeBasis(up)
        val base = rectangleCorners(Vec3(0f, 0f, 0f), basis, 2f, 1f)
        val top = base.map { it + up * 0.5f }
        val edges = loopEdges(base, top)
        assertEquals(12, edges.size)
    }

    @Test
    fun `loop edges for a cylinder cap verticals at maxVerticals`() {
        val basis = planeBasis(up)
        val base = circleRing(Vec3(0f, 0f, 0f), basis, radius = 1f, segments = 24)
        val top = base.map { it + up * 1f }
        val edges = loopEdges(base, top, maxVerticals = 8)
        // 24 base + 24 top + (24 / (24/8) = 8) verticals.
        assertEquals(56, edges.size)
    }

    @Test
    fun `label anchor is the centroid of the top face`() {
        val top = listOf(Vec3(0f, 1f, 0f), Vec3(2f, 1f, 0f), Vec3(2f, 1f, 2f), Vec3(0f, 1f, 2f))
        val anchor = labelAnchor(top)
        assertEquals(1f, anchor.x, eps)
        assertEquals(1f, anchor.y, eps)
        assertEquals(1f, anchor.z, eps)
    }

    @Test
    fun `box dimension label formats length, width and height in order`() {
        assertEquals("2 m x 1.5 m x 0.5 m", formatBoxDimensions(2f, 1.5f, 0.5f, LengthUnit.Metric, Locale.US))
    }

    @Test
    fun `box dimension label takes the absolute value of a negative edge`() {
        // A rectangle dragged toward the opposite quadrant has a negative signed lengthU —
        // the label must still read as a positive size.
        assertEquals("2 m x 1.5 m x 0.5 m", formatBoxDimensions(-2f, 1.5f, 0.5f, LengthUnit.Metric, Locale.US))
    }

    @Test
    fun `cylinder dimension label shows diameter, not radius`() {
        assertEquals("⌀1 m x 0.8 m", formatCylinderDimensions(0.5f, 0.8f, LengthUnit.Metric, Locale.US))
    }

    @Test
    fun `cross product of x and y axes is the z axis`() {
        val result = Vec3(1f, 0f, 0f).cross(Vec3(0f, 1f, 0f))
        assertEquals(Vec3(0f, 0f, 1f), result)
    }

    @Test
    fun `plane basis result is not null for a diagonal normal`() {
        // Guards against a divide-by-zero-adjacent reference-vector pick for an oblique plane.
        val diagonal = Vec3(0.5f, 0.5f, 0.7071f).normalized()
        val basis = planeBasis(diagonal)
        assertNotNull(basis)
        assertEquals(0f, basis.u.dot(diagonal), eps)
    }
}
