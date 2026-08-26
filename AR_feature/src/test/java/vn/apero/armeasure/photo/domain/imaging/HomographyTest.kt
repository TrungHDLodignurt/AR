package vn.apero.armeasure.photo.domain.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The maths behind "measure from a photo with a reference object".
 *
 * A homography that only handles an axis-aligned, undistorted quad would still pass the most
 * obvious test and still be useless — real photos are never taken square-on. So the main case
 * here runs the reference rectangle through a synthetic perspective distortion first, and
 * checks that the recovered plane un-distorts it correctly; a naive "just scale by pixel
 * length" implementation would fail exactly this case while passing an axis-aligned one.
 */
class HomographyTest {

    private val eps = 1e-2f

    @Test
    fun `axis-aligned quad recovers exact real-world distances`() {
        val quad = listOf(Vec2(100f, 100f), Vec2(400f, 100f), Vec2(400f, 300f), Vec2(100f, 300f))
        val dst = listOf(Vec2(0f, 0f), Vec2(297f, 0f), Vec2(297f, 210f), Vec2(0f, 210f))
        val h = computeHomography(quad, dst)
        assertNotNull(h)

        // The long edge of the quad must measure as exactly the reference's long side.
        val longEdge = measureRealDistanceMm(h!!, quad[0], quad[1])
        assertEquals(297f, longEdge, eps)
        val shortEdge = measureRealDistanceMm(h, quad[1], quad[2])
        assertEquals(210f, shortEdge, eps)
    }

    @Test
    fun `perspective-distorted quad still recovers correct real-world distances`() {
        // A hand-built "camera": world (x, y) -> image (x, y) with a mild perspective divide,
        // the same kind of foreshortening a photo taken at an angle produces.
        val camera = Homography(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.0012f, 0.0006f, 1f))

        val worldLong = 297f
        val worldShort = 210f
        val worldCorners = listOf(
            Vec2(0f, 0f),
            Vec2(worldLong, 0f),
            Vec2(worldLong, worldShort),
            Vec2(0f, worldShort),
        )
        val photographedQuad = worldCorners.map { camera.apply(it) }

        // A point that is NOT one of the 4 calibration corners — the real test of whether the
        // plane reconstruction generalises, rather than only reproducing its own inputs.
        val worldMidpoint = Vec2(worldLong / 2f, worldShort / 2f)
        val worldFarCorner = Vec2(worldLong, worldShort)
        val expectedMm = measureRealDistanceMm(
            Homography(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)), // identity: world space IS mm here
            worldMidpoint,
            worldFarCorner,
        )

        val reconstructed = computeHomography(photographedQuad, worldCorners)
        assertNotNull(reconstructed)
        val photographedMidpoint = camera.apply(worldMidpoint)
        val photographedFarCorner = camera.apply(worldFarCorner)
        val recoveredMm = measureRealDistanceMm(reconstructed!!, photographedMidpoint, photographedFarCorner)

        assertEquals(expectedMm, recoveredMm, eps)
    }

    @Test
    fun `three collinear corners is degenerate and returns null`() {
        val collinear = listOf(Vec2(0f, 0f), Vec2(1f, 0f), Vec2(2f, 0f), Vec2(0f, 1f))
        val dst = listOf(Vec2(0f, 0f), Vec2(1f, 0f), Vec2(2f, 0f), Vec2(0f, 1f))
        assertNull(computeHomography(collinear, dst))
    }
}
