package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Picks the 4 [HoughLine]s most likely to be a rectangle's edges immediately around [point] and
 * returns their pairwise intersections as a quad, ordered top-left/top-right/bottom-right/
 * bottom-left the way `PhotoMeasureReducers.confirmReference` expects.
 *
 * Does NOT assume the rectangle is aligned with the image's horizontal/vertical axes — a
 * reference object photographed at an angle has no lines anywhere near 0°/90°, and an
 * axis-locked version would return null on every such photo even with a perfect edge map. Instead
 * every detected line's own direction is tried as a candidate "primary" axis, paired with its
 * perpendicular as the "secondary" axis; whichever pair actually sandwiches [point] into a
 * plausible quad, with the most combined Hough votes, wins. This also naturally rejects a
 * nearby unrelated object's edges: their lines only win if they happen to enclose the tap point,
 * which a foreign object's edges generally don't.
 *
 * Returns null when no direction pair yields a sane enclosing quad (fewer than 4 suitable lines,
 * only parallel lines, or every candidate quad is degenerate/doesn't contain [point]) — a caller
 * MUST fall back to a plain default box rather than trust a degenerate result, same principle as
 * `computeHomography` returning null on a degenerate system.
 */
internal fun quadFromLines(
    lines: List<HoughLine>,
    point: Vec2,
    angleToleranceDegrees: Float = 20f,
    imageWidth: Float = Float.MAX_VALUE,
    imageHeight: Float = Float.MAX_VALUE,
    targetAspectRatio: Float? = null,
): List<Vec2>? {
    val candidates = quadCandidates(lines, point, angleToleranceDegrees, imageWidth, imageHeight, targetAspectRatio)
    if (candidates.isEmpty()) return null

    // Size is scored only now, against the other candidates, because there is no absolute "right"
    // area to compare against — the object's size in pixels depends on how close the camera was.
    // Relative to the field of candidates, though, "smallest box that still encloses the tap" is a
    // strong signal: a wrong quad built from far-away clutter lines is almost always the bigger one.
    // Real failure this fixes: a quad covering over half the frame won purely because a distant
    // high-vote line (a table edge) was in it, and the score had no size term at all to object.
    val smallestArea = candidates.minOf { it.area }
    var result = candidates.maxBy { rankScore(it, smallestArea) }.quad

    // Auto-orient: corners[0..1] must be the LONGER edge (the convention's "long side"). Which
    // of the two axes (horizontal/vertical Hough lines) is actually longer isn't known until
    // the intersections exist, so fix it up after the fact by rotating one step if needed.
    val firstEdge = distance(result[0], result[1])
    val secondEdge = distance(result[1], result[2])
    if (secondEdge > firstEdge) {
        result = listOf(result[1], result[2], result[3], result[0])
    }
    return result
}

/** How a candidate is ranked once the whole field is known. [smallestArea] is the field's minimum. */
internal fun rankScore(candidate: QuadCandidate, smallestArea: Float): Float {
    val tightnessScore = (smallestArea / candidate.area).coerceIn(0f, 1f)
    return if (candidate.shapeScore != null) {
        // Shape still leads — with dozens of clutter rectangles available, the known proportion
        // is the most discriminating single fact we have — but size now outranks vote strength.
        0.55f * candidate.shapeScore + 0.30f * tightnessScore + 0.15f * candidate.voteScore
    } else {
        0.60f * tightnessScore + 0.40f * candidate.voteScore
    }
}

/**
 * Every quad the line set can form around [point] that survives the geometric plausibility checks,
 * unranked. Exposed separately from [quadFromLines] so a test can ask the two distinct questions
 * that a single return value conflates: is a good-enough quad even in this list (an enumeration
 * fault — a missing or mis-placed Hough line), or is it in the list but losing (a ranking fault)?
 */
internal fun quadCandidates(
    lines: List<HoughLine>,
    point: Vec2,
    angleToleranceDegrees: Float = 20f,
    // Image extent, so intersections that land outside the picture can be rejected. A real photo
    // produced a corner at x=1985 on a 1542-wide image before this existed. Defaults are permissive
    // so the synthetic unit tests, which work in an unbounded plane, are unaffected.
    imageWidth: Float = Float.MAX_VALUE,
    imageHeight: Float = Float.MAX_VALUE,
    // long/short of the real object, when known. THE load-bearing constraint: a real photo yields
    // dozens of plausible rectangles from clutter, and vote strength alone reliably picks a table
    // edge or a shadow over the reference object. Knowing the object is a rectangle of *this*
    // proportion is what tells them apart. Null = fall back to vote strength only.
    targetAspectRatio: Float? = null,
): List<QuadCandidate> {
    if (lines.size < 4) return emptyList()
    val angleTolerance = angleToleranceDegrees * PI.toFloat() / 180f

    fun lineDirection(line: HoughLine) = normalizeAngle(line.thetaRadians + PI.toFloat() / 2f)
    fun signedDistance(line: HoughLine) = point.x * cos(line.thetaRadians) + point.y * sin(line.thetaRadians) - line.rho

    val candidates = mutableListOf<QuadCandidate>()
    val maxVotes = lines.maxOf { it.votes }.toFloat().coerceAtLeast(1f)
    for (candidate in lines) {
        val primaryDirection = lineDirection(candidate)
        val secondaryDirection = normalizeAngle(primaryDirection + PI.toFloat() / 2f)
        val primaryLines = lines.filter { angularDistance(lineDirection(it), primaryDirection) <= angleTolerance }
        val secondaryLines = lines.filter { angularDistance(lineDirection(it), secondaryDirection) <= angleTolerance }

        // The nearest line on each side is NOT enough. Measured on a real photo: the reference
        // object's own perpendicular edges were present in the line list (theta 6 deg, 106 votes) yet
        // no quad could be built, because on a cluttered desk there is almost always some unrelated
        // line lying between the tap point and the object's true edge — and taking only the closest
        // line per side means the true edge is never even considered. So take the nearest few on each
        // side and try the combinations, letting the shape constraints below pick the winner.
        val sidesA = primaryLines.filter { signedDistance(it) < 0 }.sortedByDescending { signedDistance(it) }.take(NearestLinesPerSide)
        val sidesB = primaryLines.filter { signedDistance(it) > 0 }.sortedBy { signedDistance(it) }.take(NearestLinesPerSide)
        val sidesC = secondaryLines.filter { signedDistance(it) < 0 }.sortedByDescending { signedDistance(it) }.take(NearestLinesPerSide)
        val sidesD = secondaryLines.filter { signedDistance(it) > 0 }.sortedBy { signedDistance(it) }.take(NearestLinesPerSide)
        if (sidesA.isEmpty() || sidesB.isEmpty() || sidesC.isEmpty() || sidesD.isEmpty()) continue

        for (sideA in sidesA) for (sideB in sidesB) for (sideC in sidesC) for (sideD in sidesD) {
        val corner1 = intersect(sideA, sideC) ?: continue
        val corner2 = intersect(sideA, sideD) ?: continue
        val corner3 = intersect(sideB, sideD) ?: continue
        val corner4 = intersect(sideB, sideC) ?: continue
        val candidateQuad = listOf(corner1, corner2, corner3, corner4)
        if (!isPointInConvexQuad(candidateQuad, point)) continue
        if (!isPlausibleReferenceQuad(candidateQuad, imageWidth, imageHeight, targetAspectRatio, MaxAspectDeviation)) continue

        // Vote strength normalised to 0..1 so it can be blended with the shape term.
        val voteScore = (sideA.votes + sideB.votes + sideC.votes + sideD.votes) / (4f * maxVotes)

        // Only how CLOSE the proportion is; whether it is close enough at all was already decided by
        // isPlausibleReferenceQuad. Scoring and admitting are separate jobs.
        val shapeScore = targetAspectRatio?.let { target ->
            if (target <= 0f) return@let null
            val ratio = averagedAspectRatio(candidateQuad) ?: return@let null
            (1f - abs(ln(ratio / target)) / MaxAspectDeviation).coerceIn(0f, 1f)
        }

        candidates += QuadCandidate(candidateQuad, areaOf(candidateQuad), voteScore, shapeScore)
        }
    }
    return candidates
}

/** One surviving quad plus the terms needed to rank it once the whole field is known. */
internal class QuadCandidate(
    val quad: List<Vec2>,
    val area: Float,
    val voteScore: Float,
    /** Null when the object's proportions aren't known, in which case shape can't be scored. */
    val shapeScore: Float?,
)

/** Absolute polygon area via the shoelace formula. */
private fun areaOf(quad: List<Vec2>): Float {
    var signedArea = 0f
    for (i in quad.indices) {
        val a = quad[i]
        val b = quad[(i + 1) % quad.size]
        signedArea += a.x * b.y - b.x * a.y
    }
    return abs(signedArea) / 2f
}

private fun intersect(line1: HoughLine, line2: HoughLine): Vec2? {
    val determinant = sin(line2.thetaRadians - line1.thetaRadians)
    if (abs(determinant) < 1e-4f) return null // parallel — no single intersection
    val x = (line1.rho * sin(line2.thetaRadians) - line2.rho * sin(line1.thetaRadians)) / determinant
    val y = (line2.rho * cos(line1.thetaRadians) - line1.rho * cos(line2.thetaRadians)) / determinant
    return Vec2(x, y)
}

/**
 * How many of the nearest lines on each side of the tap point to try.
 *
 * 3 per side gives 81 combinations per primary direction — trivial arithmetic, and it is what lets a
 * true edge be found when clutter sits nearer the tap than the object's own boundary.
 */
private const val NearestLinesPerSide = 3

/** How far a quad's proportions may deviate from the known ratio, as |ln(ratio/target)|. ~1.4x. */
private const val MaxAspectDeviation = 0.35f

private fun isPointInConvexQuad(quad: List<Vec2>, point: Vec2): Boolean {
    var sign = 0f
    for (i in quad.indices) {
        val a = quad[i]
        val b = quad[(i + 1) % quad.size]
        val cross = (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
        if (abs(cross) < 1e-4f) continue
        if (sign == 0f) {
            sign = if (cross > 0) 1f else -1f
        } else if ((cross > 0) != (sign > 0)) {
            return false
        }
    }
    return true
}

private fun distance(a: Vec2, b: Vec2): Float = sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))

private fun normalizeAngle(angle: Float): Float {
    var a = angle % PI.toFloat()
    if (a < 0) a += PI.toFloat()
    return a
}

private fun angularDistance(a: Float, b: Float): Float {
    val diff = abs(normalizeAngle(a) - normalizeAngle(b))
    return min(diff, PI.toFloat() - diff)
}
