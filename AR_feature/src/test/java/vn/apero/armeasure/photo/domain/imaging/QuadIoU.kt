package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.abs

/**
 * Intersection-over-union of two convex quads — the score the real-photo auto-fit tests are graded
 * on.
 *
 * Why IoU and not "are the corners close": a quad can have every corner within a plausible distance
 * of a true corner and still be the wrong rectangle (a diamond rotated 45 degrees inside the true
 * box scores well on per-corner distance), and conversely a correct quad whose corner *order* starts
 * one step around scores terribly. IoU is indifferent to both and answers the only question that
 * matters — how much of the real object did we actually cover, and how much of what we covered
 * wasn't the object.
 */
internal fun quadIoU(a: List<Vec2>, b: List<Vec2>): Float {
    val areaA = polygonArea(a)
    val areaB = polygonArea(b)
    if (areaA <= 0f || areaB <= 0f) return 0f
    val intersection = polygonArea(clipPolygon(a, b))
    val union = areaA + areaB - intersection
    return if (union <= 0f) 0f else (intersection / union).coerceIn(0f, 1f)
}

/** Shoelace area, sign-independent so corner winding doesn't matter. */
internal fun polygonArea(polygon: List<Vec2>): Float {
    if (polygon.size < 3) return 0f
    var signed = 0f
    for (i in polygon.indices) {
        val p = polygon[i]
        val q = polygon[(i + 1) % polygon.size]
        signed += p.x * q.y - q.x * p.y
    }
    return abs(signed) / 2f
}

/**
 * Sutherland-Hodgman: clips [subject] against every edge of the convex polygon [clip]. Both are
 * normalised to counter-clockwise winding first, since the algorithm's inside/outside test depends
 * on it and Hough-derived quads come in either direction.
 */
private fun clipPolygon(subject: List<Vec2>, clip: List<Vec2>): List<Vec2> {
    val clipper = counterClockwise(clip)
    var output = counterClockwise(subject)
    for (i in clipper.indices) {
        if (output.isEmpty()) return emptyList()
        val edgeStart = clipper[i]
        val edgeEnd = clipper[(i + 1) % clipper.size]
        val input = output
        output = mutableListOf()
        for (j in input.indices) {
            val current = input[j]
            val next = input[(j + 1) % input.size]
            val currentInside = side(current, edgeStart, edgeEnd) >= 0f
            val nextInside = side(next, edgeStart, edgeEnd) >= 0f
            if (currentInside) output.add(current)
            if (currentInside != nextInside) {
                intersectSegmentWithLine(current, next, edgeStart, edgeEnd)?.let(output::add)
            }
        }
    }
    return output
}

/** Positive when [point] is left of the directed line [lineStart] -> [lineEnd]. */
private fun side(point: Vec2, lineStart: Vec2, lineEnd: Vec2): Float =
    (lineEnd.x - lineStart.x) * (point.y - lineStart.y) - (lineEnd.y - lineStart.y) * (point.x - lineStart.x)

private fun intersectSegmentWithLine(from: Vec2, to: Vec2, lineStart: Vec2, lineEnd: Vec2): Vec2? {
    val dx = lineEnd.x - lineStart.x
    val dy = lineEnd.y - lineStart.y
    val denominator = dy * (to.x - from.x) - dx * (to.y - from.y)
    if (abs(denominator) < 1e-9f) return null
    val t = (dx * (from.y - lineStart.y) - dy * (from.x - lineStart.x)) / denominator
    return Vec2(from.x + t * (to.x - from.x), from.y + t * (to.y - from.y))
}

private fun counterClockwise(polygon: List<Vec2>): List<Vec2> {
    var signed = 0f
    for (i in polygon.indices) {
        val p = polygon[i]
        val q = polygon[(i + 1) % polygon.size]
        signed += p.x * q.y - q.x * p.y
    }
    return if (signed < 0f) polygon.reversed() else polygon
}
