package vn.quancua.artapemeasure.measure

import com.google.ar.core.Pose
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trust gate every tap-commit (point ruler, box, cylinder) runs through before a reading is
 * allowed to become a permanent point.
 *
 * Ported from [MeasureState]'s own steadiness logic, which had no dedicated test either — this
 * now backs three screens instead of one, so a regression here is three times as visible.
 */
class SteadinessGateTest {

    private val origin = Vec3(0f, 0f, 0f)
    private val near = Vec3(0f, 0f, 1f)

    // A neutral pose at the origin with no rotation — SteadinessGate only ever reads [SurfaceSample.position],
    // so the pose itself is a required-but-inert constructor argument here.
    private val identityPose = Pose(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 1f))

    private fun planeSample(position: Vec3 = origin) =
        SurfaceSample(position, HitSource.Plane, hitResult = null, pose = identityPose)

    private fun depthSample(position: Vec3) =
        SurfaceSample(position, HitSource.Depth, hitResult = null, pose = identityPose)

    @Test
    fun `a plane reading is trusted instantly, no frames of history needed`() {
        val gate = SteadinessGate()
        gate.note(planeSample(), distanceMeters = 1f)
        assertTrue(gate.stable)
    }

    @Test
    fun `a depth reading is not trusted on the very first frame`() {
        val gate = SteadinessGate()
        gate.note(depthSample(origin), distanceMeters = 1f)
        assertFalse(gate.stable)
    }

    @Test
    fun `a depth reading becomes stable once it holds within the allowance for 5 frames`() {
        val gate = SteadinessGate()
        // The very first note() always resets the streak to 0 — there is no previous position
        // to compare against yet — so reaching a streak of 5 takes 6 total calls, not 5.
        // Close-up floor allowance is 0.05m regardless of distance (the maxOf floor).
        repeat(6) { gate.note(depthSample(origin), distanceMeters = 1f) }
        assertTrue(gate.stable)
    }

    @Test
    fun `a depth reading that jumps outside the allowance resets the streak`() {
        val gate = SteadinessGate()
        repeat(4) { gate.note(depthSample(origin), distanceMeters = 1f) }
        // 1 metre away is far outside any distance-scaled allowance — the streak must restart.
        gate.note(depthSample(near), distanceMeters = 1f)
        assertFalse(gate.stable)
        // And it takes a fresh 5 steady frames from here, not just 1 more.
        repeat(4) { gate.note(depthSample(near), distanceMeters = 1f) }
        assertFalse(gate.stable)
        gate.note(depthSample(near), distanceMeters = 1f)
        assertTrue(gate.stable)
    }

    @Test
    fun `the allowance scales with distance so far-away readings are not held to sub-centimetre steadiness`() {
        val gate = SteadinessGate()
        // At 10m, the allowance is 0.2 * 10 = 2m — well past the floor of 0.05m used up close.
        val farBase = Vec3(0f, 0f, 10f)
        val farDrift = Vec3(0f, 0f, 11.5f) // 1.5m of drift, inside the 2m allowance.
        gate.note(depthSample(farBase), distanceMeters = 10f)
        // 5 more (6 total) to clear the same first-call reset described above.
        repeat(5) { gate.note(depthSample(farDrift), distanceMeters = 10f) }
        assertTrue(gate.stable)
    }

    @Test
    fun `a null sample resets the gate entirely`() {
        val gate = SteadinessGate()
        gate.note(planeSample(), distanceMeters = 1f)
        assertTrue(gate.stable)
        gate.note(null, distanceMeters = null)
        assertFalse(gate.stable)
    }

    @Test
    fun `reset clears an in-progress streak the same way a null sample does`() {
        val gate = SteadinessGate()
        repeat(3) { gate.note(depthSample(origin), distanceMeters = 1f) }
        gate.reset()
        assertFalse(gate.stable)
        // Confirms the streak actually restarted, not just the stable flag: 3 more frames alone
        // would not be enough to reach the 5-frame threshold if the counter had survived.
        repeat(3) { gate.note(depthSample(origin), distanceMeters = 1f) }
        assertFalse(gate.stable)
    }
}
