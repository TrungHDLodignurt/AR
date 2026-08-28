package vn.apero.armeasure.ar.domain.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The hysteresis is the whole reason this function exists rather than a bare
 * [nearestIndexWithin] call, so most of these tests are about the enter/release asymmetry.
 */
class SnapTargetTest {

    private val enter = 28f
    private val release = 45f
    private val origin = 0f to 0f

    private fun snap(
        positions: List<Pair<Float, Float>?>,
        reticle: Pair<Float, Float> = origin,
        held: Int? = null,
        excluded: Set<Int> = emptySet(),
    ) = snapTarget(positions, reticle, held, enter, release, excluded)

    @Test
    fun `takes nothing when the nearest point is outside the enter radius`() {
        assertNull(snap(listOf(30f to 0f)))
    }

    @Test
    fun `takes the point just inside the enter radius`() {
        assertEquals(0, snap(listOf(27f to 0f)))
    }

    @Test
    fun `takes the nearest, not the first — the reference app's bug`() {
        // Both are inside the enter radius; ARuler's first-match-wins would answer 0.
        assertEquals(1, snap(listOf(26f to 0f, 5f to 0f)))
    }

    @Test
    fun `holds a snap between the enter and release radii`() {
        // 36 px: too far to have taken the snap, close enough to keep it. This gap is the fix
        // for the per-frame flicker a single threshold produces.
        assertNull(snap(listOf(36f to 0f)))
        assertEquals(0, snap(listOf(36f to 0f), held = 0))
    }

    @Test
    fun `drops a held snap once the point leaves the release radius`() {
        assertNull(snap(listOf(46f to 0f), held = 0))
    }

    @Test
    fun `hand tremor at the enter radius does not toggle a held snap`() {
        // Straddling 28 px by ±1.2 dp — the case that makes a single threshold unusable.
        listOf(26.8f, 29.2f, 27.4f, 28.6f).forEach { distance ->
            assertEquals(
                "tremor to $distance px should not release a held snap",
                0,
                snap(listOf(distance to 0f), held = 0),
            )
        }
    }

    @Test
    fun `a rival steals a held snap only by coming within the enter radius`() {
        val held = 0
        // Rival at 40 px is inside release but outside enter: the original keeps the snap.
        assertEquals(0, snap(listOf(36f to 0f, 40f to 0f), held = held))
        // Rival at 10 px earned it.
        assertEquals(1, snap(listOf(36f to 0f, 10f to 0f), held = held))
    }

    @Test
    fun `never returns an excluded index`() {
        assertNull(snap(listOf(2f to 0f), excluded = setOf(0)))
        assertEquals(1, snap(listOf(2f to 0f, 12f to 0f), excluded = setOf(0)))
    }

    @Test
    fun `an excluded index cannot keep being held`() {
        // The open segment's start becomes excluded the moment it is the previous point; a snap
        // held on it from the frame before must not survive that.
        assertNull(snap(listOf(5f to 0f), held = 0, excluded = setOf(0)))
    }

    @Test
    fun `points that did not project are not candidates`() {
        assertNull(snap(listOf(null)))
        assertEquals(1, snap(listOf(null, 8f to 0f)))
    }

    @Test
    fun `a held index that no longer exists is dropped rather than crashing`() {
        // Undo can shorten the point list under a held snap.
        assertNull(snap(emptyList(), held = 3))
        assertEquals(0, snap(listOf(9f to 0f), held = 7))
    }

    @Test
    fun `empty input snaps to nothing`() {
        assertNull(snap(emptyList()))
    }
}
