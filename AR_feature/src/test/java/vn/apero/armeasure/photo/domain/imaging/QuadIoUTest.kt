package vn.apero.armeasure.photo.domain.imaging

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The IoU helper grades every real-photo tuning decision, so a bug in it silently invalidates all of
 * them — an early version had a sign error in the segment/line intersection and happily reported an
 * IoU of 2.338, which is geometrically impossible. These cases are all hand-computable.
 */
class QuadIoUTest {

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        listOf(Vec2(left, top), Vec2(right, top), Vec2(right, bottom), Vec2(left, bottom))

    @Test
    fun `identical quads score 1`() {
        assertEquals(1f, quadIoU(box(0f, 0f, 10f, 10f), box(0f, 0f, 10f, 10f)), 1e-4f)
    }

    @Test
    fun `disjoint quads score 0`() {
        assertEquals(0f, quadIoU(box(0f, 0f, 10f, 10f), box(20f, 20f, 30f, 30f)), 1e-4f)
    }

    @Test
    fun `half-overlapping squares score one third`() {
        // Intersection 50, union 150.
        assertEquals(1f / 3f, quadIoU(box(0f, 0f, 10f, 10f), box(5f, 0f, 15f, 10f)), 1e-4f)
    }

    @Test
    fun `a contained quad scores its area ratio`() {
        // Inner 25 inside outer 100 -> intersection 25, union 100.
        assertEquals(0.25f, quadIoU(box(0f, 0f, 10f, 10f), box(2f, 2f, 7f, 7f)), 1e-4f)
    }

    @Test
    fun `corner winding does not matter`() {
        val clockwise = box(0f, 0f, 10f, 10f)
        assertEquals(1f / 3f, quadIoU(clockwise.reversed(), box(5f, 0f, 15f, 10f)), 1e-4f)
    }

    @Test
    fun `a diamond inscribed in a square scores one half`() {
        // Rotated 45 degrees, touching the square's edge midpoints: area 50 inside 100.
        val diamond = listOf(Vec2(5f, 0f), Vec2(10f, 5f), Vec2(5f, 10f), Vec2(0f, 5f))
        assertEquals(0.5f, quadIoU(box(0f, 0f, 10f, 10f), diamond), 1e-4f)
    }

    @Test
    fun `never exceeds 1`() {
        val result = quadIoU(box(0f, 0f, 10f, 10f), box(-5f, -5f, 15f, 15f))
        assert(result <= 1f) { "IoU $result exceeds 1" }
    }
}
