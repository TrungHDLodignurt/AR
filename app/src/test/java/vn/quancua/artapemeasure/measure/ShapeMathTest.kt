package vn.quancua.artapemeasure.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
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
    fun `projected edge vector points wherever the user actually dragged, not a fixed world axis`() {
        // A direction that matches neither of planeBasis(up)'s own axes — the whole point is that
        // the box's edges follow this, not a world-derived axis.
        val origin = Vec3(0f, 0f, 0f)
        val drawnDirection = Vec3(1f, 0f, 1f).normalized()
        val second = origin + drawnDirection * 2f + up * 0.05f // plus off-plane noise to ignore
        val edge = projectedEdgeVector(origin, second, up)
        assertEquals(2f, edge.length(), eps)
        assertEquals(drawnDirection.x * 2f, edge.x, eps)
        assertEquals(drawnDirection.z * 2f, edge.z, eps)
        assertEquals(0f, edge.dot(up), eps) // the off-plane noise was discarded
    }

    @Test
    fun `a second edge need not be perpendicular to the first — the base is whatever parallelogram results`() {
        // The whole point of drawing two edges independently: nothing here forces a right angle.
        val origin = Vec3(0f, 0f, 0f)
        val edgeU = Vec3(2f, 0f, 0f)
        val edgeV = Vec3(0.5f, 0f, 1f) // deliberately not perpendicular to edgeU
        assertTrue(abs(edgeU.normalized().dot(edgeV.normalized())) > 0.01f)
        val corners = parallelogramCorners(origin, edgeU, edgeV)
        assertEquals(4, corners.size)
        assertEquals(origin, corners[0])
        assertEquals(origin + edgeU, corners[1])
        assertEquals(origin + edgeU + edgeV, corners[2])
        assertEquals(origin + edgeV, corners[3])
    }

    @Test
    fun `parallelogram corners form a closed loop back to the origin`() {
        val origin = Vec3(1f, 0f, 1f)
        val corners = parallelogramCorners(origin, edgeU = Vec3(2f, 0f, 0f), edgeV = Vec3(0f, 0f, 1.5f))
        assertEquals(origin, corners[0])
        // Adjacent corners differ by exactly one edge length.
        assertEquals(2f, measureDistanceMeters(corners[0], corners[1]), eps)
        assertEquals(1.5f, measureDistanceMeters(corners[1], corners[2]), eps)
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

    // A camera standing back along the same direction as basis.v, at base height — arbitrary but
    // fixed, so which edges land "near" vs "far" from it is deterministic across these tests.
    private val farCamera = Vec3(0f, 0.2f, 5f)

    @Test
    fun `prism edge visibility marks only the face the camera is actually looking at`() {
        val basis = planeBasis(up)
        val base = parallelogramCorners(Vec3(0f, 0f, 0f), basis.u * 2f, basis.v * 1f)
        val top = base.map { it + up * 0.5f }
        // Far off to the +X side, roughly level with the box's mid-height: only the side face
        // it's looking at (index 2) — and that face's own two bounding verticals — should read
        // visible. Top, bottom, and the other 3 sides face away from the camera.
        val camera = Vec3(10f, 0.25f, 1f)
        val visibility = prismEdgeVisibility(base, top, up, camera)
        assertEquals(listOf(false, false, true, false), visibility.baseVisible)
        assertEquals(listOf(false, false, true, false), visibility.topVisible)
        assertEquals(listOf(false, false, true, true), visibility.verticalVisible)
    }

    @Test
    fun `a camera looking straight down sees only the top cap`() {
        val basis = planeBasis(up)
        val base = parallelogramCorners(Vec3(0f, 0f, 0f), basis.u * 2f, basis.v * 1f)
        val top = base.map { it + up * 0.5f }
        val camera = Vec3(0.5f, 20f, 1f) // directly above the box's centroid
        val visibility = prismEdgeVisibility(base, top, up, camera)
        assertTrue(visibility.topVisible.all { it })
        assertTrue(visibility.baseVisible.none { it })
    }

    @Test
    fun `loop edges for a rectangle produce 4 base, 4 top and 4 vertical edges`() {
        val basis = planeBasis(up)
        val base = parallelogramCorners(Vec3(0f, 0f, 0f), basis.u * 2f, basis.v * 1f)
        val top = base.map { it + up * 0.5f }
        val edges = loopEdges(base, top, up, farCamera)
        assertEquals(12, edges.size)
    }

    @Test
    fun `loop edges for a cylinder cap verticals at maxVerticals`() {
        val basis = planeBasis(up)
        val base = circleRing(Vec3(0f, 0f, 0f), basis, radius = 1f, segments = 24)
        val top = base.map { it + up * 1f }
        val edges = loopEdges(base, top, up, farCamera, maxVerticals = 8)
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

    @Test
    fun `height construction plane normal is horizontal and faces the camera`() {
        val origin = Vec3(0f, 0f, 0f)
        // Camera stands 2m back along +Z and 1.5m up — only the horizontal (Z) part should
        // survive once the vertical (up) component is stripped out.
        val camera = Vec3(0f, 1.5f, 2f)
        val normal = heightConstructionPlaneNormal(origin, camera, axis = up, fallback = Vec3(1f, 0f, 0f))
        assertEquals(0f, normal.dot(up), eps) // stays perpendicular to the height axis
        assertEquals(Vec3(0f, 0f, 1f), normal) // points straight at the camera's horizontal position
    }

    @Test
    fun `height construction plane normal falls back when the camera is directly above the origin`() {
        val origin = Vec3(0f, 0f, 0f)
        val camera = Vec3(0f, 2f, 0f) // straight up — no horizontal direction to the camera exists
        val fallback = Vec3(1f, 0f, 0f)
        val normal = heightConstructionPlaneNormal(origin, camera, axis = up, fallback = fallback)
        assertEquals(fallback, normal)
    }

    @Test
    fun `an aim ray through the construction plane resolves a height matching how far the phone rose`() {
        // End-to-end sanity check tying heightConstructionPlaneNormal to intersectRayPlane and
        // heightAlongAxis the same way ShapeFrameLoop.resolveHeightSample chains them: a camera
        // standing back from the origin, aiming level at a point 1.2m above it, should read a
        // construction-plane height of 1.2m — the same number a person raising the phone to that
        // height and looking straight at the target would see, with no real surface involved.
        val origin = Vec3(0f, 0f, 0f)
        val cameraPosition = Vec3(0f, 1.2f, 3f)
        val planeNormal = heightConstructionPlaneNormal(origin, cameraPosition, axis = up, fallback = Vec3(1f, 0f, 0f))
        val aimRay = Ray3(origin = cameraPosition, direction = Vec3(0f, 0f, -1f))
        val hit = intersectRayPlane(aimRay, origin, planeNormal)
        assertNotNull(hit)
        assertEquals(1.2f, heightAlongAxis(origin, hit!!, up), eps)
    }

    @Test
    fun `plane basis switches its reference vector when the normal is nearly parallel to Z`() {
        // A wall facing almost straight along ARCore's Z axis is the one case where the default
        // world-Z reference vector is itself nearly parallel to the normal (dot >= 0.9) — the
        // fallback to world-X must still produce an orthonormal, non-degenerate basis.
        val nearZ = Vec3(0.1f, 0.1f, 0.99f).normalized()
        assertTrue(abs(nearZ.dot(Vec3(0f, 0f, 1f))) >= 0.9f)
        val basis = planeBasis(nearZ)
        assertEquals(0f, basis.u.dot(nearZ), eps)
        assertEquals(0f, basis.v.dot(nearZ), eps)
        assertEquals(0f, basis.u.dot(basis.v), eps)
        assertEquals(1f, sqrt(basis.u.dot(basis.u)), eps)
        assertEquals(1f, sqrt(basis.v.dot(basis.v)), eps)
    }
}
