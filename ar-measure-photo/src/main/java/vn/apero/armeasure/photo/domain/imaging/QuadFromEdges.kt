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
 */
internal fun quadFromLines(
    lines: List<HoughLine>,
    point: Vec2,
    angleToleranceDegrees: Float = 20f,
): List<Vec2>? {
    if (lines.size < 4) return null
    val angleTolerance = angleToleranceDegrees * PI.toFloat() / 180f

    fun lineDirection(line: HoughLine) = normalizeAngle(line.thetaRadians + PI.toFloat() / 2f)
    fun signedDistance(line: HoughLine) = point.x * cos(line.thetaRadians) + point.y * sin(line.thetaRadians) - line.rho

    var quad: List<Vec2>? = null
    var bestVotes = -1
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
        if (!isPlausibleQuad(candidateQuad, point)) continue

        val totalVotes = sideA.votes + sideB.votes + sideC.votes + sideD.votes
        if (totalVotes > bestVotes) {
            bestVotes = totalVotes
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

private fun intersect(line1: HoughLine, line2: HoughLine): Vec2? {
    val determinant = sin(line2.thetaRadians - line1.thetaRadians)
    if (abs(determinant) < 1e-4f) return null // parallel — no single intersection
    val x = (line1.rho * sin(line2.thetaRadians) - line2.rho * sin(line1.thetaRadians)) / determinant
    val y = (line2.rho * cos(line1.thetaRadians) - line1.rho * cos(line2.thetaRadians)) / determinant
    return Vec2(x, y)
}

/** Non-degenerate area, and the tap point should sit inside the quad it was supposed to outline. */
private fun isPlausibleQuad(quad: List<Vec2>, point: Vec2): Boolean {
    var area = 0f
    for (i in quad.indices) {
        val a = quad[i]
        val b = quad[(i + 1) % quad.size]
        area += a.x * b.y - b.x * a.y
    }
    if (abs(area) < 1e-3f) return false
    return isPointInConvexQuad(quad, point)
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
