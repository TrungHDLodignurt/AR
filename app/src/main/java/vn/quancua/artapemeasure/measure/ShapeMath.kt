package vn.quancua.artapemeasure.measure

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure geometry for the box and cylinder tools' rectangular/circular bases and their vertical
 * extrusion.
 *
 * Same rules as [MeasureMath]: zero third-party types, plain [Vec3] in and out, so a rectangle
 * or a circle can be checked on the JVM with no ARCore session and no Compose runtime. The
 * composables and the per-frame loop own projecting these world points to the screen; this file
 * owns only the arithmetic that decides where those points sit in the world.
 */

/** Two unit vectors spanning a plane, both perpendicular to that plane's normal and to each other. */
data class PlaneBasis(val u: Vec3, val v: Vec3)

/**
 * A stable pair of in-plane axes for [normal], derived once from the plane itself rather than
 * from wherever the reticle happens to be.
 *
 * A rectangle/circle needs an axis to measure lengths along, but re-deriving that axis from the
 * live reticle position every frame (e.g. "primary axis = direction from origin to the reticle")
 * would make the shape's orientation chase the reticle instead of holding still — and would make
 * the reticle-to-origin direction always fall exactly ON the primary axis, leaving the
 * perpendicular length permanently zero. Fixing the basis to the plane instead lets the user
 * sweep the reticle freely in two dimensions and see two independent edge lengths grow, the way
 * dragging out a rectangle in a paint program does.
 */
fun planeBasis(normal: Vec3): PlaneBasis {
    val n = normal.normalized()
    // Any reference vector not parallel to n works; whichever of world Z/X is safely off-axis
    // from n is picked so the projection below never divides by a near-zero direction.
    val reference = if (abs(n.dot(Vec3(0f, 0f, 1f))) < 0.9f) Vec3(0f, 0f, 1f) else Vec3(1f, 0f, 0f)
    val u = (reference - n * reference.dot(n)).normalized()
    val v = n.cross(u).normalized()
    return PlaneBasis(u, v)
}

/** A rectangle's 4 corners, in winding order, plus its signed edge lengths along [PlaneBasis.u]/[PlaneBasis.v]. */
data class RectBase(val corners: List<Vec3>, val lengthU: Float, val lengthV: Float)

/** The 4 corners of a [origin]-anchored rectangle with signed edge lengths [lengthU]/[lengthV] along [basis]. */
fun rectangleCorners(origin: Vec3, basis: PlaneBasis, lengthU: Float, lengthV: Float): List<Vec3> {
    val cornerU = origin + basis.u * lengthU
    val cornerUV = cornerU + basis.v * lengthV
    val cornerV = origin + basis.v * lengthV
    return listOf(origin, cornerU, cornerUV, cornerV)
}

/**
 * The live/base rectangle from [origin] to [second], decomposed onto [basis].
 *
 * [second] need not lie exactly on the plane — only its projection onto [basis] is used — so a
 * reticle reading that is a few millimetres off the analytic plane (ordinary sensor noise) still
 * produces a clean rectangle rather than a warped one.
 */
fun rectangleFromPoints(origin: Vec3, second: Vec3, basis: PlaneBasis): RectBase {
    val delta = second - origin
    val lengthU = delta.dot(basis.u)
    val lengthV = delta.dot(basis.v)
    return RectBase(rectangleCorners(origin, basis, lengthU, lengthV), lengthU, lengthV)
}

/** How many points make up a drawn circle — enough to read as round, not so many the wireframe clutters. */
private const val CircleSegments = 24

/** A circle's ring points, in winding order, plus its radius. */
data class CircleBase(val ring: List<Vec3>, val radius: Float)

/** The ring of points around [center] with the given [radius], lying in the plane spanned by [basis]. */
fun circleRing(center: Vec3, basis: PlaneBasis, radius: Float, segments: Int = CircleSegments): List<Vec3> =
    (0 until segments).map { i ->
        val theta = 2f * PI.toFloat() * i / segments
        center + basis.u * (radius * cos(theta)) + basis.v * (radius * sin(theta))
    }

/** The live/base circle from [center] to [edge] — [edge]'s distance from [center] is the radius. */
fun circleFromPoints(center: Vec3, edge: Vec3, basis: PlaneBasis, segments: Int = CircleSegments): CircleBase {
    val radius = measureDistanceMeters(center, edge)
    return CircleBase(circleRing(center, basis, radius, segments), radius)
}

/**
 * Signed length of `top - base` projected onto [axis] (must already be unit length) — the box
 * and cylinder tools' height reading.
 *
 * Straight-line distance (as the point-to-point ruler uses) is wrong here: tilting the phone up
 * from a base corner moves the reticle both away from the surface AND sideways as the hand
 * wavers, and only the component along the surface's own normal is the height a person means.
 * Signed rather than absolute so a reticle that drifts slightly to the wrong side of the base
 * (rounding, or the user undershooting) reports a small negative height instead of a
 * nonsensical positive one — callers take `abs(...)` of this when formatting the label.
 */
fun heightAlongAxis(base: Vec3, top: Vec3, axis: Vec3): Float = (top - base).dot(axis)

private fun centroid(points: List<Vec3>): Vec3 {
    var x = 0f
    var y = 0f
    var z = 0f
    points.forEach { x += it.x; y += it.y; z += it.z }
    val n = points.size.toFloat()
    return Vec3(x / n, y / n, z / n)
}

/** One line of a wireframe, still in world space — projecting to screen space happens elsewhere. */
data class Edge3(val a: Vec3, val b: Vec3)

/** Base ring/rectangle + matching top ring/rectangle + a handful of verticals connecting them. */
fun loopEdges(base: List<Vec3>, top: List<Vec3>, maxVerticals: Int = 8): List<Edge3> {
    val n = base.size
    val baseEdges = (0 until n).map { Edge3(base[it], base[(it + 1) % n]) }
    val topEdges = (0 until n).map { Edge3(top[it], top[(it + 1) % n]) }
    // A box has only 4 corners, so all 4 verticals are drawn; a circle's ring has many more
    // points than are worth a vertical each, so only every Nth one is — enough to read as a
    // cylinder without the wireframe turning into a solid mesh of lines.
    val step = maxOf(1, n / maxVerticals)
    val verticals = (0 until n step step).map { Edge3(base[it], top[it]) }
    return baseEdges + topEdges + verticals
}

/** Screen-space anchor point for a shape's combined dimension label — its top face's centre. */
fun labelAnchor(top: List<Vec3>): Vec3 = centroid(top)

/** "1.2 m x 0.8 m x 0.5 m" — the box tool's combined dimension label. */
fun formatBoxDimensions(lengthU: Float, lengthV: Float, height: Float, unit: LengthUnit, locale: Locale = Locale.getDefault()): String {
    val length = formatLength(abs(lengthU), unit, locale)
    val width = formatLength(abs(lengthV), unit, locale)
    val heightLabel = formatLength(abs(height), unit, locale)
    return "$length x $width x $heightLabel"
}

/** "⌀0.6 m x 0.5 m" — the cylinder tool's combined dimension label. */
fun formatCylinderDimensions(radius: Float, height: Float, unit: LengthUnit, locale: Locale = Locale.getDefault()): String {
    val diameter = formatLength(2f * abs(radius), unit, locale)
    val heightLabel = formatLength(abs(height), unit, locale)
    return "⌀$diameter x $heightLabel"
}
