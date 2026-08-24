package vn.quancua.artapemeasure.photomeasure

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Picks the 4 [HoughLine]s most likely to be a rectangle's edges immediately around [point] —
 * the nearest near-horizontal line on each side, the nearest near-vertical line on each side —
 * and returns their pairwise intersections as a quad, ordered top-left/top-right/bottom-right/
 * bottom-left the way `PhotoMeasureState.confirmReference` expects.
 *
 * Returns null when fewer than 4 suitable lines exist around [point], or the intersections don't
 * form a sane quad (parallel lines, or a shape too small/large to plausibly be what the user
 * tapped on) — a caller MUST fall back to a plain default box rather than trust a degenerate
 * result, same principle as `computeHomography` returning null on a degenerate system.
 */
fun quadFromLines(
    lines: List<HoughLine>,
    point: Vec2,
    angleToleranceDegrees: Float = 20f,
): List<Vec2>? {
    val angleTolerance = angleToleranceDegrees * PI.toFloat() / 180f

    fun lineDirection(line: HoughLine) = normalizeAngle(line.thetaRadians + PI.toFloat() / 2f)
    fun signedDistance(line: HoughLine) = point.x * cos(line.thetaRadians) + point.y * sin(line.thetaRadians) - line.rho

    val horizontalLines = lines.filter { angularDistance(lineDirection(it), 0f) <= angleTolerance }
    val verticalLines = lines.filter { angularDistance(lineDirection(it), PI.toFloat() / 2f) <= angleTolerance }

    val sideA = horizontalLines.filter { signedDistance(it) < 0 }.maxByOrNull { signedDistance(it) } ?: return null
    val sideB = horizontalLines.filter { signedDistance(it) > 0 }.minByOrNull { signedDistance(it) } ?: return null
    val sideC = verticalLines.filter { signedDistance(it) < 0 }.maxByOrNull { signedDistance(it) } ?: return null
    val sideD = verticalLines.filter { signedDistance(it) > 0 }.minByOrNull { signedDistance(it) } ?: return null

    val corner1 = intersect(sideA, sideC) ?: return null
    val corner2 = intersect(sideA, sideD) ?: return null
    val corner3 = intersect(sideB, sideD) ?: return null
    val corner4 = intersect(sideB, sideC) ?: return null
    var quad = listOf(corner1, corner2, corner3, corner4)

    if (!isPlausibleQuad(quad, point)) return null

    // Auto-orient: corners[0..1] must be the LONGER edge (the convention's "long side"). Which
    // of the two axes (horizontal/vertical Hough lines) is actually longer isn't known until
    // the intersections exist, so fix it up after the fact by rotating one step if needed.
    val firstEdge = distance(quad[0], quad[1])
    val secondEdge = distance(quad[1], quad[2])
    if (secondEdge > firstEdge) {
        quad = listOf(quad[1], quad[2], quad[3], quad[0])
    }
    return quad
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
