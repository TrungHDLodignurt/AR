package vn.apero.armeasure.photo.domain.imaging

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the 3 diagnosed real-photo faults in `houghLines`/`quadFromLines`
 * (see `AutoFitQuad.kt` doc + the instrumented tap data that motivated this):
 * 1. `minVotesFraction` was relative to the strongest line in the window, so one dominant
 *    clutter line (a table edge, a shadow) raised the bar high enough to delete the object's own
 *    weaker edges entirely.
 * 2. Lines clustering in one orientation could consume every surviving slot, starving the
 *    perpendicular partner `quadFromLines` needed to form a pair.
 *
 * Builds the boolean edge map directly (bypassing `cannyEdges`) so vote counts per line are
 * exact and deterministic, unlike a rasterised synthetic photo.
 */
class HoughTransformFixesTest {

    @Test
    fun `a weak real object edge survives despite one dominant unrelated line`() {
        val width = 300
        val height = 200
        val edges = BooleanArray(width * height)

        // Dominant clutter line: a full-width row near the bottom (300 votes) — much stronger
        // than the small rectangle's own edges below, same shape as a real photo's table edge.
        val clutterRow = 190
        for (x in 0 until width) edges[clutterRow * width + x] = true

        // The object: a small rectangle whose sides are far weaker than the clutter line. Old
        // relative threshold (0.3 * 300 = 90) would have discarded all 4 of these; new absolute
        // floor (0.12 * min(300,200) = 24) keeps them.
        val left = 100
        val top = 50
        val right = 150
        val bottom = 90
        for (x in left..right) {
            edges[top * width + x] = true
            edges[bottom * width + x] = true
        }
        for (y in top..bottom) {
            edges[y * width + left] = true
            edges[y * width + right] = true
        }

        val lines = houghLines(edges, width, height)
        val tapPoint = Vec2(((left + right) / 2).toFloat(), ((top + bottom) / 2).toFloat())
        val quad = quadFromLines(lines, tapPoint)

        requireNotNull(quad) {
            "expected the small rectangle's quad despite a dominant unrelated line; lines=$lines"
        }
        val trueCorners = listOf(
            Vec2(left.toFloat(), top.toFloat()),
            Vec2(right.toFloat(), top.toFloat()),
            Vec2(right.toFloat(), bottom.toFloat()),
            Vec2(left.toFloat(), bottom.toFloat()),
        )
        quad.forEach { corner ->
            val nearestTrueCorner = trueCorners.minBy { distance(it, corner) }
            val error = distance(nearestTrueCorner, corner)
            assertTrue("corner $corner is $error px from the nearest true corner (expected < 4px)", error < 4f)
        }
    }

    private fun distance(a: Vec2, b: Vec2): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
