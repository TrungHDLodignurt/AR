package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Fits a general quadrilateral — not a rectangle — to a point set's convex hull.
 *
 * [minAreaRect] cannot represent what a photograph actually shows. A rectangle shot from anywhere
 * other than straight on projects to a trapezoid whose opposite sides differ in length, and forcing
 * a rectangle onto it leaves a box visibly larger than the object with its corners off every one of
 * them. The homography in `PhotoMeasureState.confirmReference` is a full projective map and wants
 * exactly those four skewed corners; handing it a rectangle throws the perspective information away
 * before it can be used.
 *
 * Two stages. First pick the four hull vertices enclosing the largest area, which lands near the true
 * corners but cuts across them on any object with rounded corners (every phone, every card). Then fit
 * a line to the hull points along each side and intersect adjacent lines, which puts the corners back
 * where the object's own edges actually meet.
 *
 * Returns null when the hull is degenerate or the fit comes out non-convex or wildly the wrong size,
 * in which case the caller should fall back to [minAreaRect].
 */
internal fun quadFromHull(points: List<Vec2>): List<Vec2>? {
    val hull = convexHull(points)?.let { decimate(it, MaxHullVerticesForFit) } ?: return null
    if (hull.size < 4) return null

    val corners = maxAreaQuadIndices(hull) ?: return null
    val refined = refineByEdgeFit(hull, corners) ?: return orient(corners.map { hull[it] })

    val hullArea = polygonAreaOf(hull)
    val fittedArea = polygonAreaOf(refined)
    // A least-squares fit on a lopsided hull can diverge; the enclosing quad of a convex hull should
    // be a little larger than it, never smaller and never wildly bigger.
    if (fittedArea < hullArea * 0.8f || fittedArea > hullArea * 1.6f) return orient(corners.map { hull[it] })
    if (!isConvex(refined)) return orient(corners.map { hull[it] })
    return orient(refined)
}

/**
 * Indices of the 4 hull vertices spanning the greatest area.
 *
 * Brute force over every vertex quadruple, which is why the hull is decimated first: at 48 vertices
 * that is around 200k triangle areas, microseconds, and it avoids the subtle failure modes of the
 * clever O(n log n) versions on the near-degenerate hulls a noisy mask produces.
 */
private fun maxAreaQuadIndices(hull: List<Vec2>): List<Int>? {
    val n = hull.size
    var bestArea = 0f
    var best: List<Int>? = null
    for (a in 0 until n - 3) {
        for (b in a + 1 until n - 2) {
            for (c in b + 1 until n - 1) {
                val areaABC = triangleArea(hull[a], hull[b], hull[c])
                for (d in c + 1 until n) {
                    val area = areaABC + triangleArea(hull[a], hull[c], hull[d])
                    if (area > bestArea) {
                        bestArea = area
                        best = listOf(a, b, c, d)
                    }
                }
            }
        }
    }
    return if (bestArea <= 1e-3f) null else best
}

/**
 * Replaces each of the 4 sides with a line fitted to the hull points along it, then re-derives the
 * corners as the intersections of adjacent lines.
 *
 * The end fifth of each side is dropped from its fit: those points sit on the object's rounded corner,
 * curving away from the straight edge, and including them tilts the line and pulls the corner in.
 */
private fun refineByEdgeFit(hull: List<Vec2>, corners: List<Int>): List<Vec2>? {
    val lines = ArrayList<Line>(4)
    for (i in 0 until 4) {
        val from = corners[i]
        val to = corners[(i + 1) % 4]
        val along = pointsBetween(hull, from, to)
        // Two points is enough — the line through them is exact. A side of a straight-edged object
        // often has exactly two hull vertices (its own corners), and requiring three silently
        // skipped the whole refinement, leaving the corner-cutting raw quad: a 200px-wide phone
        // silhouette came back 160px wide.
        if (along.size < 2) return null
        // Drop a quarter from each end. Those points sit on the object's rounded or chamfered corner,
        // curving away from the straight edge, and averaging them in drags the fitted line inward:
        // a 200px-wide silhouette with 20px chamfers came back 180px because each side's run included
        // its two corner vertices. A run of two is already exactly the edge, so it is kept whole.
        val trim = along.size / 4
        val core = along.subList(trim, along.size - trim).takeIf { it.size >= 2 } ?: along
        lines.add(fitLine(core) ?: return null)
    }
    return (0 until 4).map { i ->
        intersectLines(lines[(i + 3) % 4], lines[i]) ?: return null
    }
}

/** Hull vertices from [from] to [to] inclusive, walking forward around the cycle. */
private fun pointsBetween(hull: List<Vec2>, from: Int, to: Int): List<Vec2> {
    val result = mutableListOf<Vec2>()
    var i = from
    while (true) {
        result.add(hull[i])
        if (i == to) break
        i = (i + 1) % hull.size
    }
    return result
}

private class Line(val pointX: Float, val pointY: Float, val dirX: Float, val dirY: Float)

/**
 * Total least squares (principal axis of the covariance), not `y = mx + c`: an object edge is often
 * near-vertical in a portrait photo, where the slope form is unbounded and the fit blows up.
 */
private fun fitLine(points: List<Vec2>): Line? {
    val n = points.size
    if (n < 2) return null
    var meanX = 0f
    var meanY = 0f
    for (p in points) {
        meanX += p.x
        meanY += p.y
    }
    meanX /= n
    meanY /= n

    var xx = 0f
    var xy = 0f
    var yy = 0f
    for (p in points) {
        val dx = p.x - meanX
        val dy = p.y - meanY
        xx += dx * dx
        xy += dx * dy
        yy += dy * dy
    }
    // Larger eigenvalue's eigenvector of [[xx, xy], [xy, yy]] is the direction of most spread.
    val trace = xx + yy
    val determinant = xx * yy - xy * xy
    val discriminant = trace * trace / 4f - determinant
    if (discriminant < 0f) return null
    val eigenvalue = trace / 2f + sqrt(discriminant)
    var dirX = eigenvalue - yy
    var dirY = xy
    if (hypot(dirX, dirY) < 1e-6f) {
        dirX = xy
        dirY = eigenvalue - xx
    }
    val length = hypot(dirX, dirY)
    if (length < 1e-6f) return null
    return Line(meanX, meanY, dirX / length, dirY / length)
}

private fun intersectLines(a: Line, b: Line): Vec2? {
    val denominator = a.dirX * b.dirY - a.dirY * b.dirX
    if (abs(denominator) < 1e-6f) return null // parallel: adjacent sides should never be
    val dx = b.pointX - a.pointX
    val dy = b.pointY - a.pointY
    val t = (dx * b.dirY - dy * b.dirX) / denominator
    val x = a.pointX + t * a.dirX
    val y = a.pointY + t * a.dirY
    return if (x.isFinite() && y.isFinite()) Vec2(x, y) else null
}

/** Keeps at most [limit] hull vertices, evenly spaced, so the quadruple search stays cheap. */
private fun decimate(hull: List<Vec2>, limit: Int): List<Vec2> {
    if (hull.size <= limit) return hull
    val step = hull.size.toFloat() / limit
    return (0 until limit).map { hull[(it * step).toInt().coerceIn(0, hull.size - 1) ] }.distinct()
}

private fun triangleArea(a: Vec2, b: Vec2, c: Vec2): Float =
    abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2f

private fun isConvex(quad: List<Vec2>): Boolean {
    var sign = 0
    for (i in quad.indices) {
        val a = quad[i]
        val b = quad[(i + 1) % quad.size]
        val c = quad[(i + 2) % quad.size]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        if (abs(cross) < 1e-4f) continue
        val current = if (cross > 0) 1 else -1
        if (sign == 0) sign = current else if (sign != current) return false
    }
    return sign != 0
}

/**
 * Rotates the cycle so `[0] -> [1]` is the longest side, matching the convention
 * `PhotoMeasureState.confirmReference` maps onto the reference object's real long side. Opposite
 * sides are averaged, since under perspective the two "long" sides differ.
 */
private fun orient(quad: List<Vec2>): List<Vec2> {
    fun side(i: Int) = hypot(quad[(i + 1) % 4].x - quad[i].x, quad[(i + 1) % 4].y - quad[i].y)
    val pair0 = (side(0) + side(2)) / 2f
    val pair1 = (side(1) + side(3)) / 2f
    return if (pair1 > pair0) listOf(quad[1], quad[2], quad[3], quad[0]) else quad
}

/** Upper bound on hull vertices entering the O(n^4) quadruple search. */
private const val MaxHullVerticesForFit = 48
