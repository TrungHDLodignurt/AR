package vn.apero.armeasure.photo.domain.imaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared accept/reject gate both detectors run their result through.
 *
 * Every bound here is deliberately generous, and the tests are written to pin that down in both
 * directions: the false-reject cases matter as much as the false-accept ones. A payment card at a
 * normal shooting distance covers about 1.4% of the frame, so a floor set at "a few percent" — which
 * looks harmless — silently rejects the most common reference object there is in almost every photo.
 */
class ReferenceQuadPlausibilityTest {

    private val frameWidth = 1000f
    private val frameHeight = 1000f

    private fun box(left: Float, top: Float, right: Float, bottom: Float) =
        listOf(Vec2(left, top), Vec2(right, top), Vec2(right, bottom), Vec2(left, bottom))

    private fun gate(quad: List<Vec2>, targetAspectRatio: Float? = null) =
        isPlausibleReferenceQuad(quad, frameWidth, frameHeight, targetAspectRatio)

    @Test
    fun `a card-sized object at normal distance is accepted`() {
        // 118 x 74 px on a 1000x1000 frame = 0.87% — squarely in the range a real payment card
        // occupies, and the exact case a percent-scale floor would have thrown away.
        assertTrue(gate(box(400f, 400f, 518f, 474f)))
    }

    @Test
    fun `mask speckle is rejected`() {
        // 20 x 12 px = 0.024%, under the 0.1% floor.
        assertFalse(gate(box(400f, 400f, 420f, 412f)))
    }

    @Test
    fun `a whole-scene segmentation result is rejected`() {
        assertFalse(gate(box(0f, 0f, 1000f, 1000f)))
    }

    @Test
    fun `an object cut off by the frame edge is rejected`() {
        // Small enough to pass every area bound, but running off the left edge means the length it
        // implies is not the object's real length — the calibration would be wrong and look fine.
        assertTrue("control: the same box away from the edge is fine", gate(box(60f, 300f, 360f, 480f)))
        assertFalse(gate(box(0f, 300f, 300f, 480f)))
    }

    @Test
    fun `a large but interior region is still accepted`() {
        // 80% of the frame, inside the border inset. Someone can hold an A4 sheet this close.
        assertTrue(gate(box(20f, 30f, 980f, 900f)))
    }

    @Test
    fun `a perspective trapezoid with 40 percent unequal sides is accepted`() {
        // Near edge 300, far edge 180 — a rectangle on a table seen from a low angle. This is the
        // case the old 30% mismatch bound wrongly rejected.
        val trapezoid = listOf(Vec2(350f, 300f), Vec2(650f, 300f), Vec2(710f, 600f), Vec2(290f, 600f))
        assertTrue(gate(trapezoid))
    }

    @Test
    fun `four unrelated lines with wildly unequal sides are rejected`() {
        // 100 against 400 is a 75% mismatch: no longer a rectangle in perspective.
        val junk = listOf(Vec2(300f, 300f), Vec2(400f, 300f), Vec2(700f, 600f), Vec2(300f, 600f))
        assertFalse(gate(junk))
    }

    @Test
    fun `a self-intersecting quad is rejected`() {
        val bowtie = listOf(Vec2(300f, 300f), Vec2(600f, 600f), Vec2(600f, 300f), Vec2(300f, 600f))
        assertFalse(gate(bowtie))
    }

    @Test
    fun `proportions far from the known ratio are rejected`() {
        val nearlySquare = box(300f, 300f, 600f, 580f) // ratio ~1.07
        assertTrue("no ratio known: shape alone cannot reject it", gate(nearlySquare))
        assertFalse("a 2.14 reference object is not this", gate(nearlySquare, targetAspectRatio = 150f / 70f))
    }

    @Test
    fun `proportions close to the known ratio are accepted`() {
        val phoneShaped = box(300f, 300f, 728f, 500f) // 428 x 200, ratio 2.14
        assertTrue(gate(phoneShaped, targetAspectRatio = 150f / 70f))
    }

    @Test
    fun `a degenerate or non-finite quad is rejected`() {
        assertFalse(gate(listOf(Vec2(0f, 0f), Vec2(1f, 1f), Vec2(2f, 2f), Vec2(3f, 3f))))
        assertFalse(gate(listOf(Vec2(Float.NaN, 0f), Vec2(100f, 0f), Vec2(100f, 50f), Vec2(0f, 50f))))
        assertFalse(gate(listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(100f, 50f))))
    }

    @Test
    fun `an unknown frame size skips only the frame-relative checks`() {
        // Synthetic callers work in an unbounded plane; shape checks must still apply there.
        assertTrue(isPlausibleReferenceQuad(box(0f, 0f, 400f, 200f)))
        val junk = listOf(Vec2(0f, 0f), Vec2(100f, 0f), Vec2(700f, 300f), Vec2(0f, 300f))
        assertFalse(isPlausibleReferenceQuad(junk))
    }
}
