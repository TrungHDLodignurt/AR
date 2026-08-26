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
    minVotesFraction: Float = 0.3f,
    // A real photo's background (fabric weave, printed logos, shadows) generates plenty of its
    // own strong lines — on a real test photo the reference object's own right edge ranked
    // outside the top 12 entirely, crowded out by mousepad-texture lines, so quadFromLines never
    // even got to see it. Raised well past what a clean synthetic edge map would ever need.
    maxLines: Int = 40,
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
    val minVotes = (maxVotes * minVotesFraction).toInt().coerceAtLeast(1)

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
    val thetaSuppressRadius = 10f * PI.toFloat() / 180f
    val rhoSuppressRadius = maxRho * 0.03f
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
