package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Picks the 4 [HoughLine]s most likely to be a rectangle's edges immediately around [point] and
 * returns their pairwise intersections as a quad, ordered top-left/top-right/bottom-right/
 * bottom-left the way `PhotoMeasureState.confirmReference` expects.
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
 *
 * [expectedAspectRatio] is the reference object's known long/short side ratio (e.g. an A4 sheet
 * is 297/210 ≈ 1.41). When supplied it does two things: (1) hard-rejects a candidate quad whose
 * own long/short pixel ratio is wildly different from the known shape — no amount of Hough votes
 * makes a square-shaped candidate the right pick for a 2:1 object; (2) among the remaining
 * plausible candidates, blends vote strength with aspect-ratio closeness so a slightly
 * lower-voted but correctly-shaped quad wins over a higher-voted but wrong-shaped one (e.g. a
 * neighbouring object's edges that happen to also enclose the tap point). Perspective distorts
 * the pixel ratio somewhat, so the rejection threshold stays loose rather than exact-match —
 * see [aspectRatioScore].
 *
 * [imageWidth]/[imageHeight], when supplied, hard-reject a candidate with any corner far outside
 * the image — two Hough lines with a shallow angle between them intersect at a point that can be
 * arbitrarily far away, and with no bounds check that point is still accepted as a "corner" as
 * long as it happens to be on the correct side of the tap. Observed on a real 1542x2048 photo:
 * a returned corner at x=1985, outside the image entirely.
 */
internal fun quadFromLines(
    lines: List<HoughLine>,
    point: Vec2,
    angleToleranceDegrees: Float = 20f,
    expectedAspectRatio: Float? = null,
    imageWidth: Float? = null,
    imageHeight: Float? = null,
): List<Vec2>? {
    if (lines.size < 4) return null
    val angleTolerance = angleToleranceDegrees * PI.toFloat() / 180f
    val maxVotesSeen = lines.maxOf { it.votes }.coerceAtLeast(1)

    fun lineDirection(line: HoughLine) = normalizeAngle(line.thetaRadians + PI.toFloat() / 2f)
    fun signedDistance(line: HoughLine) = point.x * cos(line.thetaRadians) + point.y * sin(line.thetaRadians) - line.rho

    var quad: List<Vec2>? = null
    var bestScore = -1f
    for (candidate in lines) {
        val primaryDirection = lineDirection(candidate)
        val secondaryDirection = normalizeAngle(primaryDirection + PI.toFloat() / 2f)
        val primaryLines = lines.filter { angularDistance(lineDirection(it), primaryDirection) <= angleTolerance }
        val secondaryLines = lines.filter { angularDistance(lineDirection(it), secondaryDirection) <= angleTolerance }

        val sideA = primaryLines.filter { signedDistance(it) < 0 }.maxByOrNull { signedDistance(it) } ?: continue
        val sideB = primaryLines.filter { signedDistance(it) > 0 }.minByOrNull { signedDistance(it) } ?: continue
        val sideC = secondaryLines.filter { signedDistance(it) < 0 }.maxByOrNull { signedDistance(it) } ?: continue
        val sideD = secondaryLines.filter { signedDistance(it) > 0 }.minByOrNull { signedDistance(it) } ?: continue

        val corner1 = intersect(sideA, sideC) ?: continue
        val corner2 = intersect(sideA, sideD) ?: continue
        val corner3 = intersect(sideB, sideD) ?: continue
        val corner4 = intersect(sideB, sideC) ?: continue
        val candidateQuad = listOf(corner1, corner2, corner3, corner4)
        if (!isPlausibleQuad(candidateQuad, point, imageWidth, imageHeight)) continue

        val aspectScore = if (expectedAspectRatio != null) aspectRatioScore(candidateQuad, expectedAspectRatio) else 1f
        if (expectedAspectRatio != null && aspectScore < MinAspectScoreToConsider) continue

        val totalVotes = sideA.votes + sideB.votes + sideC.votes + sideD.votes
        val voteScore = totalVotes.toFloat() / (maxVotesSeen * 4f)
        val score = if (expectedAspectRatio != null) voteScore * 0.5f + aspectScore * 0.5f else voteScore
        if (score > bestScore) {
            bestScore = score
            quad = candidateQuad
        }
    }
    var result = quad ?: return null

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

/** Below this, a candidate is rejected outright regardless of how many Hough votes it has. */
private const val MinAspectScoreToConsider = 0.4f

/**
 * 1.0 when the candidate quad's long/short pixel-side ratio exactly matches [expectedRatio],
 * falling linearly to 0.0 as the relative error approaches 100%. Uses the average of each pair
 * of opposite sides (more stable under perspective than picking a single side), and normalises
 * [expectedRatio] to be >= 1 so callers can pass either long/short or short/long without caring
 * about orientation.
 */
private fun aspectRatioScore(quad: List<Vec2>, expectedRatio: Float): Float {
    val top = distance(quad[0], quad[1])
    val right = distance(quad[1], quad[2])
    val bottom = distance(quad[2], quad[3])
    val left = distance(quad[3], quad[0])
    val axis1 = (top + bottom) / 2f
    val axis2 = (left + right) / 2f
    val longPx = maxOf(axis1, axis2)
    val shortPx = minOf(axis1, axis2)
    if (shortPx <= 1e-3f) return 0f

    val detectedRatio = longPx / shortPx
    val normalizedExpected = maxOf(expectedRatio, 1f / expectedRatio)
    val error = abs(detectedRatio - normalizedExpected) / normalizedExpected
    return (1f - error).coerceIn(0f, 1f)
}

private fun intersect(line1: HoughLine, line2: HoughLine): Vec2? {
    val determinant = sin(line2.thetaRadians - line1.thetaRadians)
    if (abs(determinant) < 1e-4f) return null // parallel — no single intersection
    val x = (line1.rho * sin(line2.thetaRadians) - line2.rho * sin(line1.thetaRadians)) / determinant
    val y = (line2.rho * cos(line1.thetaRadians) - line1.rho * cos(line2.thetaRadians)) / determinant
    return Vec2(x, y)
}

/**
 * Non-degenerate area, convex (a self-intersecting/concave "rectangle" is never right), opposite
 * sides roughly balanced (a real rectangle's own perspective-foreshortened sides don't differ by
 * more than [maxOppositeSideImbalance] — a quad built from two unrelated lines on one axis, e.g.
 * a nearby object's edge plus the real one, produces a lopsided trapezoid instead: one long side
 * stays 56px while the "opposite" one balloons to 89px), the tap point sits inside it, and — when
 * [imageWidth]/[imageHeight] are known — every corner is inside the image (with a small margin
 * for perspective/rounding overshoot at the true edge). Checked here rather than folded into the
 * aspect-ratio score because [aspectRatioScore] averages opposite sides, which HIDES exactly this
 * imbalance instead of catching it.
 */
private fun isPlausibleQuad(
    quad: List<Vec2>,
    point: Vec2,
    imageWidth: Float?,
    imageHeight: Float?,
    maxOppositeSideImbalance: Float = 0.3f,
): Boolean {
    var area = 0f
    for (i in quad.indices) {
        val a = quad[i]
        val b = quad[(i + 1) % quad.size]
        area += a.x * b.y - b.x * a.y
    }
    if (abs(area) < 1e-3f) return false
    if (!isConvexQuad(quad)) return false
    if (relativeError(distance(quad[0], quad[1]), distance(quad[2], quad[3])) > maxOppositeSideImbalance) return false
    if (relativeError(distance(quad[1], quad[2]), distance(quad[3], quad[0])) > maxOppositeSideImbalance) return false
    if (imageWidth != null && imageHeight != null && !isWithinImageBounds(quad, imageWidth, imageHeight)) return false
    return isPointInConvexQuad(quad, point)
}

private fun relativeError(a: Float, b: Float): Float {
    val larger = maxOf(a, b)
    if (larger <= 1e-3f) return 0f
    return abs(a - b) / larger
}

/** Consistent turn direction at every vertex — rejects self-intersecting/concave quadrilaterals. */
private fun isConvexQuad(quad: List<Vec2>): Boolean {
    var sign = 0f
    for (i in quad.indices) {
        val a = quad[i]
        val b = quad[(i + 1) % quad.size]
        val c = quad[(i + 2) % quad.size]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        if (abs(cross) < 1e-4f) continue
        if (sign == 0f) {
            sign = if (cross > 0) 1f else -1f
        } else if ((cross > 0) != (sign > 0)) {
            return false
        }
    }
    return true
}

/** [marginFraction] of the larger image dimension is tolerated past the edge before rejecting. */
private fun isWithinImageBounds(quad: List<Vec2>, imageWidth: Float, imageHeight: Float, marginFraction: Float = 0.05f): Boolean {
    val margin = marginFraction * maxOf(imageWidth, imageHeight)
    return quad.all { it.x >= -margin && it.x <= imageWidth + margin && it.y >= -margin && it.y <= imageHeight + margin }
}

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
