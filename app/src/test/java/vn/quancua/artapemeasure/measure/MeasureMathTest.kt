package vn.quancua.artapemeasure.measure


import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The arithmetic behind every displayed number.
 *
 * A wrong distance formula renders a perfectly plausible label: the app does not crash, the
 * screenshot looks right, and the number is simply false. No device pass or visual QA catches
 * that — only these tests do. Hence the per-axis cases: dropping any one term still passes a
 * diagonal-only test.
 */
class MeasureMathTest {

    private val eps = 1e-4f

    @Test
    fun `distance along x only`() {
        assertEquals(3f, measureDistanceMeters(Vec3(0f, 0f, 0f), Vec3(3f, 0f, 0f)), eps)
    }

    @Test
    fun `distance along y only`() {
        assertEquals(3f, measureDistanceMeters(Vec3(0f, 0f, 0f), Vec3(0f, 3f, 0f)), eps)
    }

    /** Catches a dropped `z` term, which no diagonal-only test would reveal. */
    @Test
    fun `distance along z only`() {
        assertEquals(3f, measureDistanceMeters(Vec3(0f, 0f, 0f), Vec3(0f, 0f, 3f)), eps)
    }

    @Test
    fun `distance is a 3-4-5 triangle in xz`() {
        assertEquals(5f, measureDistanceMeters(Vec3(0f, 0f, 0f), Vec3(3f, 0f, 4f)), eps)
    }

    @Test
    fun `distance is symmetric`() {
        val a = Vec3(1.5f, -2f, 0.25f)
        val b = Vec3(-3f, 4f, 7f)
        assertEquals(measureDistanceMeters(a, b), measureDistanceMeters(b, a), eps)
    }

    @Test
    fun `distance between coincident points is zero`() {
        val p = Vec3(1f, 2f, 3f)
        assertEquals(0f, measureDistanceMeters(p, p), eps)
    }

    @Test
    fun `midpoint handles negative coordinates`() {
        val m = measureMidpoint(Vec3(-2f, 0f, 4f), Vec3(4f, 6f, -4f))
        assertEquals(1f, m.x, eps)
        assertEquals(3f, m.y, eps)
        assertEquals(0f, m.z, eps)
    }

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

    @Test
    fun `points moved is true when the count changes`() {
        assertTrue(measurePointsMoved(emptyList(), listOf(Vec3(0f, 0f, 0f))))
    }

    @Test
    fun `points moved ignores sub-millimetre jitter`() {
        val before = listOf(Vec3(0f, 0f, 0f))
        val after = listOf(Vec3(0.0005f, 0f, 0f))
        assertFalse(measurePointsMoved(before, after))
    }

    @Test
    fun `points moved follows a real anchor correction`() {
        val before = listOf(Vec3(0f, 0f, 0f))
        val after = listOf(Vec3(0.002f, 0f, 0f))
        assertTrue(measurePointsMoved(before, after))
    }

    @Test
    fun `points moved is false for two empty lists`() {
        assertFalse(measurePointsMoved(emptyList(), emptyList()))
    }

    @Test
    fun `ray plane intersection hits a flat floor straight down`() {
        val ray = Ray3(origin = Vec3(0f, 2f, 0f), direction = Vec3(0f, -1f, 0f))
        val hit = intersectRayPlane(ray, planePoint = Vec3(0f, 0f, 0f), planeNormal = Vec3(0f, 1f, 0f))
        assertEquals(Vec3(0f, 0f, 0f), hit)
    }

    @Test
    fun `ray plane intersection follows an oblique ray to the right plane`() {
        // Aim ray leans in x while dropping in y; must land exactly on the y=0 plane.
        val ray = Ray3(origin = Vec3(0f, 2f, 0f), direction = Vec3(1f, -1f, 0f).normalized())
        val hit = intersectRayPlane(ray, planePoint = Vec3(0f, 0f, 0f), planeNormal = Vec3(0f, 1f, 0f))
        assertEquals(2f, hit!!.x, eps)
        assertEquals(0f, hit.y, eps)
    }

    @Test
    fun `ray plane intersection is null when the ray is parallel to the plane`() {
        val ray = Ray3(origin = Vec3(0f, 2f, 0f), direction = Vec3(1f, 0f, 0f))
        val hit = intersectRayPlane(ray, planePoint = Vec3(0f, 0f, 0f), planeNormal = Vec3(0f, 1f, 0f))
        assertEquals(null, hit)
    }

    @Test
    fun `ray plane intersection is null when the plane is behind the ray`() {
        // Plane sits at y=5, but the ray points down and away from it.
        val ray = Ray3(origin = Vec3(0f, 2f, 0f), direction = Vec3(0f, -1f, 0f))
        val hit = intersectRayPlane(ray, planePoint = Vec3(0f, 5f, 0f), planeNormal = Vec3(0f, 1f, 0f))
        assertEquals(null, hit)
    }

    @Test
    fun `normalized preserves direction and yields unit length`() {
        val n = Vec3(3f, 0f, 4f).normalized()
        assertEquals(0.6f, n.x, eps)
        assertEquals(0.8f, n.z, eps)
    }

    @Test
    fun `normalized leaves a near-zero vector unchanged rather than dividing by zero`() {
        val zero = Vec3(0f, 0f, 0f)
        assertEquals(zero, zero.normalized())
    }
}
