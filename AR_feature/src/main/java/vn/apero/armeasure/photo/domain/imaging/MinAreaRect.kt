package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Smallest-area enclosing rectangle of a point set, at any rotation, as 4 corners ordered so that
 * `[0] -> [1]` is the LONGER side — the convention `PhotoMeasureReducers.confirmReference` maps onto the
 * reference object's real long side.
 *
 * This is what turns a segmentation mask into a measurable quad. Unlike the Canny+Hough path it needs
 * no gradient anywhere along the object's boundary: it only needs to know which pixels belong to the
 * object. That matters because a real photo's object boundary is frequently not in the pixels at all
 * — measured on the gate photo, the reference object's bottom edge differed from the surface behind
 * it by 1/255 in luminance and 0/255 in chroma, so every edge-based method was provably capped at
 * 0.43 IoU no matter how it was tuned.
 *
 * Returns null for a degenerate input (fewer than 3 distinct points, or all points collinear).
 */
internal fun minAreaRect(points: List<Vec2>): List<Vec2>? {
    val hull = convexHull(points) ?: return null

    var bestArea = Float.MAX_VALUE
    var best: List<Vec2>? = null
    // Rotating calipers: the minimum-area enclosing rectangle always has one side flush with a hull
    // edge, so trying every hull edge as that side is exhaustive rather than a heuristic.
    for (i in hull.indices) {
        val a = hull[i]
        val b = hull[(i + 1) % hull.size]
        val edgeLength = hypot(b.x - a.x, b.y - a.y)
        if (edgeLength < 1e-4f) continue
        val axisX = (b.x - a.x) / edgeLength
        val axisY = (b.y - a.y) / edgeLength

        var minAlong = Float.MAX_VALUE
        var maxAlong = -Float.MAX_VALUE
        var minAcross = Float.MAX_VALUE
        var maxAcross = -Float.MAX_VALUE
        for (p in hull) {
            val along = p.x * axisX + p.y * axisY
            val across = -p.x * axisY + p.y * axisX
            if (along < minAlong) minAlong = along
            if (along > maxAlong) maxAlong = along
            if (across < minAcross) minAcross = across
            if (across > maxAcross) maxAcross = across
        }
        val area = (maxAlong - minAlong) * (maxAcross - minAcross)
        if (area >= bestArea) continue
        bestArea = area
        // Back from (along, across) to image coordinates.
        fun corner(along: Float, across: Float) =
            Vec2(along * axisX - across * axisY, along * axisY + across * axisX)
        best = listOf(
            corner(minAlong, minAcross),
            corner(maxAlong, minAcross),
            corner(maxAlong, maxAcross),
            corner(minAlong, maxAcross),
        )
    }

    val rect = best ?: return null
    if (bestArea < 1e-3f) return null
    // Auto-orient so [0] -> [1] is the long side, same fix-up quadFromLines applies: which of the two
    // axes came out longer is not known until the extents are measured.
    val firstEdge = hypot(rect[1].x - rect[0].x, rect[1].y - rect[0].y)
    val secondEdge = hypot(rect[2].x - rect[1].x, rect[2].y - rect[1].y)
    return if (secondEdge > firstEdge) listOf(rect[1], rect[2], rect[3], rect[0]) else rect
}

/**
 * Convex hull by Andrew's monotone chain, counter-clockwise, without the duplicated closing point.
 * Null when the input cannot form a polygon.
 */
internal fun convexHull(points: List<Vec2>): List<Vec2>? {
    val sorted = points.distinctBy { it.x to it.y }.sortedWith(compareBy({ it.x }, { it.y }))
    if (sorted.size < 3) return null

    fun buildChain(source: List<Vec2>): MutableList<Vec2> {
        val chain = mutableListOf<Vec2>()
        for (p in source) {
            while (chain.size >= 2 && cross(chain[chain.size - 2], chain[chain.size - 1], p) <= 0f) {
                chain.removeAt(chain.size - 1)
            }
            chain.add(p)
        }
        return chain
    }

    val lower = buildChain(sorted)
    val upper = buildChain(sorted.reversed())
    // Each chain repeats the other's endpoint; drop them so the cycle isn't duplicated.
    val hull = lower.dropLast(1) + upper.dropLast(1)
    return if (hull.size < 3) null else hull
}

/** Z of (b-a) x (c-a): positive when a->b->c turns counter-clockwise. */
private fun cross(a: Vec2, b: Vec2, c: Vec2): Float =
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

/** Absolute area of a simple polygon, shoelace. Used to sanity-check a hull before measuring it. */
internal fun polygonAreaOf(polygon: List<Vec2>): Float {
    if (polygon.size < 3) return 0f
    var signed = 0f
    for (i in polygon.indices) {
        val p = polygon[i]
        val q = polygon[(i + 1) % polygon.size]
        signed += p.x * q.y - q.x * p.y
    }
    return abs(signed) / 2f
}
