package vn.quancua.artapemeasure.measure

/** Frames a depth/feature-point reading must hold still before it can be committed — a sixth of a second. */
private const val MinSteadyFrames = 5

/**
 * Tracks whether a stream of noisy surface samples has held still long enough to trust.
 *
 * Extracted out of [MeasureState] so the box/cylinder tools can reuse the exact same gate for
 * every tap-commit (origin, base, and height alike), not just height, instead of re-deriving it.
 * Height is the tap most likely to need it — a height reading almost always comes from a
 * depth-map or feature-point hit, since there is rarely a plane floating above a box's top face,
 * which is exactly the unstable case this gate exists to catch — but origin and base commits go
 * through the identical gate for the same reason the original point ruler did: see the rationale
 * on plane vs. depth-map trust in [MeasureState.liveStable]'s doc comment.
 */
class SteadinessGate {

    var stable: Boolean = false
        private set

    private var steadyFrames = 0
    private var lastPosition: Vec3? = null

    /** Feeds one frame's reading in. [distanceMeters] scales the allowed jitter with distance. */
    fun note(sample: SurfaceSample?, distanceMeters: Float?) {
        if (sample == null) {
            reset()
            return
        }
        if (sample.source == HitSource.Plane) {
            steadyFrames = MinSteadyFrames
            lastPosition = sample.position
            stable = true
            return
        }
        val previous = lastPosition
        // Scale the allowance with distance — reticle sweep covers more ground further out —
        // but keep a floor so close-up readings are not held to sub-centimetre steadiness.
        val allowed = maxOf(0.05f, 0.2f * (distanceMeters ?: 0f))
        steadyFrames = if (previous != null && measureDistanceMeters(previous, sample.position) <= allowed) {
            steadyFrames + 1
        } else {
            0
        }
        lastPosition = sample.position
        stable = steadyFrames >= MinSteadyFrames
    }

    fun reset() {
        steadyFrames = 0
        lastPosition = null
        stable = false
    }
}
