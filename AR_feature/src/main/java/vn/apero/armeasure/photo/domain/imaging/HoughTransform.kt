package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A detected straight line in `rho`/`theta` (normal-form) parametrisation: `x·cosθ + y·sinθ = ρ`. */
internal data class HoughLine(val rho: Float, val thetaRadians: Float, val votes: Int)

/**
 * Standard Hough line transform: every edge pixel votes for every (rho, theta) line that could
 * pass through it; peaks in the vote accumulator are the dominant straight lines in the image —
 * exactly the reference object's 4 edges, if Canny found them cleanly.
 *
 * [minVotesFraction] is relative to the strongest line found, not an absolute vote count —
 * same reasoning as the adaptive thresholds in `CannyEdgeDetector.kt`: a fixed number tuned for
 * one photo's edge density is wrong for the next.
 */
internal fun houghLines(
    edges: BooleanArray,
    width: Int,
    height: Int,
    thetaStepDegrees: Float = 1f,
    // Kept only as a ceiling on how permissive the absolute floor below may be — see minVotesFloor.
    minVotesFraction: Float = 0.3f,
    /**
     * Absolute vote floor, as a fraction of the image's shorter side.
     *
     * This replaces the old behaviour of thresholding at `minVotesFraction * maxVotes`, which was
     * *relative* and therefore fatal for elongated objects. Measured on a real photo of a 15x7cm
     * phone: the strongest clutter line scored 245 votes, putting the bar at 0.3*245 = 74, while the
     * phone's own 7cm short edges only reached ~56 votes because they are physically less than half
     * the length of its long edges. Both short edges were discarded, no perpendicular partner
     * survived, and no quad could be formed — the top eight lines were all in a 90-105 degree band
     * with nothing near the 8 degrees a perpendicular would need.
     *
     * A low absolute floor lets short edges through. The junk it also lets through is now filtered
     * by quadFromLines' in-bounds, convexity, area and opposite-side-balance checks, which did not
     * exist when the relative threshold was written.
     *
     * On by default rather than opt-in: making it conditional on a known aspect ratio meant the two
     * callers ran different edge-selection rules, so their results could not be compared against one
     * another. The floor is always capped by the old relative bar (see below), so switching it on can
     * only ever admit MORE lines, never delete one that used to survive.
     */
    minVotesFloorFraction: Float = 0.08f,
    /**
     * Hard lower bound on the floor above, in votes.
     *
     * Canny's own corner-diagonal artifacts — short oblique segments where two perpendicular edges
     * meet, tied to the blur kernel size, so they do NOT grow with image size — reliably reach 20-30
     * votes even on a clean synthetic rectangle. Left unbounded, [minVotesFloorFraction] on a small
     * image drops below that and lets them into `quadFromLines`, where they out-compete the real
     * edges for "nearest line to the tap" and yield a distorted quad instead of the true one.
     */
    minVotesFloorAbsolute: Int = 40,
    // A real photo's background (fabric weave, printed logos, shadows) generates plenty of its
    // own strong lines — on a real test photo the reference object's own right edge ranked
    // outside the top 12 entirely, crowded out by mousepad-texture lines, so quadFromLines never
    // even got to see it. Raised well past what a clean synthetic edge map would ever need.
    maxLines: Int = 40,
    /**
     * Non-maximum suppression radii: a candidate peak is dropped when an already-chosen stronger
     * line lies within [suppressThetaDegrees] AND [suppressRhoFraction] of `maxRho` of it.
     *
     * Exposed as parameters because they are the knobs that decide whether the reference object's
     * own edges reach `quadFromLines` at all. Measured on a real photo: with a 0.03 rho radius
     * (34px at a 900px detection size) the phone's true top edge at rho 372 was suppressed by a
     * stronger clutter line at rho 356, and its true left edge at rho 192 by one at rho 177 — so
     * three of the object's four edges were missing from the line list and NO combination of them
     * could form the right quad. The ceiling on achievable IoU was 0.38 no matter how the
     * candidates were scored.
     */
    suppressThetaDegrees: Float = 10f,
    suppressRhoFraction: Float = 0.03f,
): List<HoughLine> {
    val thetaSteps = (180f / thetaStepDegrees).toInt()
    val maxRho = hypot(width.toFloat(), height.toFloat())
    val rhoSteps = (2 * maxRho).toInt() + 1

    val cosTable = FloatArray(thetaSteps)
    val sinTable = FloatArray(thetaSteps)
    for (t in 0 until thetaSteps) {
        val theta = t * thetaStepDegrees * PI.toFloat() / 180f
        cosTable[t] = cos(theta)
        sinTable[t] = sin(theta)
    }

    val accumulator = IntArray(thetaSteps * rhoSteps)
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (!edges[y * width + x]) continue
            for (t in 0 until thetaSteps) {
                val rho = x * cosTable[t] + y * sinTable[t]
                val rhoIndex = (rho + maxRho).toInt().coerceIn(0, rhoSteps - 1)
                accumulator[t * rhoSteps + rhoIndex]++
            }
        }
    }

    val maxVotes = accumulator.maxOrNull() ?: 0
    if (maxVotes == 0) return emptyList()
    // Absolute floor, capped so it can never exceed the old relative bar (a nearly blank image with
    // one faint line should still not admit noise).
    val relativeBar = (maxVotes * minVotesFraction).toInt()
    val absoluteFloor = maxOf(minVotesFloorAbsolute, (minVotesFloorFraction * minOf(width, height)).toInt())
    val minVotes = minOf(absoluteFloor, relativeBar).coerceAtLeast(1)

    val candidates = mutableListOf<Triple<Int, Int, Int>>() // theta index, rho index, votes
    for (t in 0 until thetaSteps) {
        for (r in 0 until rhoSteps) {
            val votes = accumulator[t * rhoSteps + r]
            if (votes >= minVotes) candidates.add(Triple(t, r, votes))
        }
    }
    candidates.sortByDescending { it.third }

    // Greedy pick strongest-first, suppressing anything too close to a line already chosen —
    // otherwise one thick edge fills the top of the list with near-duplicates. Done in actual
    // (theta, rho) values rather than raw indices, and checked against BOTH (theta, rho) and its
    // wrapped identity (theta+180°, -rho): near-vertical/near-horizontal edges genuinely produce
    // accumulator peaks on both sides of the theta=0/180° boundary (measurement noise pushes
    // some edge pixels' votes just past the wrap), and without this a real edge and its own
    // duplicate can end up on OPPOSITE sides of a comparison downstream, crowding out the
    // *other* genuine edge entirely (this is not a hypothetical — it's what a rectangle's own
    // silhouette actually produces; see QuadFromEdgesTest).
    val thetaSuppressRadius = suppressThetaDegrees * PI.toFloat() / 180f
    val rhoSuppressRadius = maxRho * suppressRhoFraction
    val chosen = mutableListOf<Triple<Int, Int, Int>>()
    for (candidate in candidates) {
        if (chosen.size >= maxLines) break
        val candidateTheta = candidate.first * thetaStepDegrees * PI.toFloat() / 180f
        val candidateRho = candidate.second - maxRho
        val tooClose = chosen.any { existing ->
            val existingTheta = existing.first * thetaStepDegrees * PI.toFloat() / 180f
            val existingRho = existing.second - maxRho
            isSameLine(existingTheta, existingRho, candidateTheta, candidateRho, thetaSuppressRadius, rhoSuppressRadius)
        }
        if (!tooClose) chosen.add(candidate)
    }

    return chosen.map { (thetaIndex, rhoIndex, votes) ->
        HoughLine(
            rho = rhoIndex - maxRho,
            thetaRadians = thetaIndex * thetaStepDegrees * PI.toFloat() / 180f,
            votes = votes,
        )
    }
}

/** Same physical line either directly, or via the (theta, rho) ~ (theta+180°, -rho) identity. */
private fun isSameLine(theta1: Float, rho1: Float, theta2: Float, rho2: Float, thetaTolerance: Float, rhoTolerance: Float): Boolean {
    val directThetaDiff = abs(theta1 - theta2)
    val wrappedThetaDiff = abs(PI.toFloat() - directThetaDiff)
    return when {
        directThetaDiff <= thetaTolerance -> abs(rho1 - rho2) <= rhoTolerance
        wrappedThetaDiff <= thetaTolerance -> abs(rho1 + rho2) <= rhoTolerance
        else -> false
    }
}
