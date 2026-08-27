package vn.apero.armeasure.ar.domain.geometry

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.formatLength

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
internal data class PlaneBasis(val u: Vec3, val v: Vec3)

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
internal fun planeBasis(normal: Vec3): PlaneBasis {
    val n = normal.normalized()
    // Any reference vector not parallel to n works; whichever of world Z/X is safely off-axis
    // from n is picked so the projection below never divides by a near-zero direction.
    val reference = if (abs(n.dot(Vec3(0f, 0f, 1f))) < 0.9f) Vec3(0f, 0f, 1f) else Vec3(1f, 0f, 0f)
    val u = (reference - n * reference.dot(n)).normalized()
    val v = n.cross(u).normalized()
    return PlaneBasis(u, v)
}

/**
 * [second]'s position relative to [origin], flattened onto the plane with the given [normal] —
 * the box tool's free-hand edge vector.
 *
 * The box's two base edges are each just this: wherever the user actually drew them, with no
 * fixed axis and no forced right angle between them. A box measured by hand rarely comes out
 * perfectly square either — asking for a second independent edge and building the base as the
 * parallelogram those two vectors describe (see [parallelogramCorners]) matches what was actually
 * measured instead of silently correcting it to 90°, which would visibly disagree with where the
 * user aimed the second edge.
 */
internal fun projectedEdgeVector(origin: Vec3, second: Vec3, normal: Vec3): Vec3 {
    val delta = second - origin
    return delta - normal * delta.dot(normal)
}

/**
 * The 4 corners of the parallelogram [origin] plus two independently drawn edges describe — not
 * forced to a right angle. See [projectedEdgeVector].
 */
internal fun parallelogramCorners(origin: Vec3, edgeU: Vec3, edgeV: Vec3): List<Vec3> =
    listOf(origin, origin + edgeU, origin + edgeU + edgeV, origin + edgeV)

/** How many points make up a drawn circle — enough to read as round, not so many the wireframe clutters. */
private const val CircleSegments = 24

/** A circle's ring points, in winding order, plus its radius. */
internal data class CircleBase(val ring: List<Vec3>, val radius: Float)

/** The ring of points around [center] with the given [radius], lying in the plane spanned by [basis]. */
internal fun circleRing(center: Vec3, basis: PlaneBasis, radius: Float, segments: Int = CircleSegments): List<Vec3> =
    (0 until segments).map { i ->
        val theta = 2f * PI.toFloat() * i / segments
        center + basis.u * (radius * cos(theta)) + basis.v * (radius * sin(theta))
    }

/** The live/base circle from [center] to [edge] — [edge]'s distance from [center] is the radius. */
internal fun circleFromPoints(center: Vec3, edge: Vec3, basis: PlaneBasis, segments: Int = CircleSegments): CircleBase {
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
internal fun heightAlongAxis(base: Vec3, top: Vec3, axis: Vec3): Float = (top - base).dot(axis)

/**
 * A construction-plane normal for the height step's live reading: perpendicular to [axis] (so the
 * plane always contains the axis the height is measured along) and oriented toward
 * [towardPosition] — the camera — so the aim ray reliably crosses it as the phone tilts up.
 *
 * The height tap has no real surface to aim at: the reticle is pointed at open air above a box's
 * top face, or wherever the room happens to be behind it. Resolving that against ARCore's actual
 * depth map or feature points (the way the origin and base taps do, because for them a real
 * surface IS the point) makes the reading depend on whatever texture or geometry is incidentally
 * behind the phone, and go stale or vanish when there is none — the reticle has to hunt for a
 * hit, then hold still while [SteadinessGate] catches up. A box's height is a number the user is
 * choosing by how far they raise the phone, not a surface they are aiming at, so it should be
 * resolved the same way a plane hit already is elsewhere in this codebase: analytically, against
 * a mathematical plane, immune to whatever is or is not actually there.
 *
 * Falls back to [fallback] on the degenerate case where the camera sits (almost) directly above
 * or below [origin] along [axis], where "horizontal direction to the camera" has no answer —
 * any plane containing [axis] still works, so the shape's own in-plane basis is a safe pick.
 */
internal fun heightConstructionPlaneNormal(origin: Vec3, towardPosition: Vec3, axis: Vec3, fallback: Vec3): Vec3 {
    val toward = towardPosition - origin
    val perpendicular = toward - axis * toward.dot(axis)
    return if (perpendicular.dot(perpendicular) > 1e-6f) perpendicular.normalized() else fallback
}

private fun centroid(points: List<Vec3>): Vec3 {
    var x = 0f
    var y = 0f
    var z = 0f
    points.forEach { x += it.x; y += it.y; z += it.z }
    val n = points.size.toFloat()
    return Vec3(x / n, y / n, z / n)
}

/**
 * One line of a wireframe, still in world space — projecting to screen space happens elsewhere.
 *
 * [visible] is true when at least one of this edge's two adjacent faces faces the camera — see
 * [prismEdgeVisibility]. An edge with both adjacent faces turned away is occluded by the shape's
 * own front side and should render dashed, the usual "hidden line" wireframe convention, rather
 * than solid as if it sat in plain view.
 */
internal data class Edge3(val a: Vec3, val b: Vec3, val visible: Boolean = true)

/** Which of a convex prism's base/top/vertical edges face the camera — see [loopEdges]. */
internal data class PrismVisibility(
    val baseVisible: List<Boolean>,
    val topVisible: List<Boolean>,
    val verticalVisible: List<Boolean>,
)

/**
 * Per-edge visibility for a right prism (box or cylinder alike — both are just an N-gon extruded
 * along [axis]) as seen from [cameraPosition].
 *
 * A face's outward direction is taken radially from its ring's own centroid rather than from a
 * cross-product of its edges, so this needs no assumption about winding order: side face `i`'s
 * outward direction is `(midpoint of base edge i) - (base centroid)`, and the top/bottom caps use
 * [axis] directly since a right prism's caps are always perpendicular to it. A face is
 * front-facing when the camera sits on the outward side of it.
 *
 * An edge is visible when EITHER of its adjacent faces is front-facing — true for any convex
 * solid: an edge with both neighbours turned away is entirely behind the shape's own near side.
 * Base/top edges each border one side face and one cap; vertical edges border the two side faces
 * meeting at that corner.
 */
internal fun prismEdgeVisibility(base: List<Vec3>, top: List<Vec3>, axis: Vec3, cameraPosition: Vec3): PrismVisibility {
    val n = base.size
    val baseCentroid = centroid(base)
    val topCentroid = centroid(top)
    val bottomFaces = axis.dot(cameraPosition - baseCentroid) < 0f
    val topFaces = axis.dot(cameraPosition - topCentroid) > 0f
    val sideFaces = (0 until n).map { i ->
        val edgeMid = (base[i] + base[(i + 1) % n]) * 0.5f
        (edgeMid - baseCentroid).dot(cameraPosition - edgeMid) > 0f
    }
    return PrismVisibility(
        baseVisible = (0 until n).map { i -> bottomFaces || sideFaces[i] },
        topVisible = (0 until n).map { i -> topFaces || sideFaces[i] },
        // Vertical edge i sits between side face (i-1) and side face i.
        verticalVisible = (0 until n).map { i -> sideFaces[i] || sideFaces[(i - 1 + n) % n] },
    )
}

/**
 * Base ring/rectangle + matching top ring/rectangle + a handful of verticals connecting them,
 * each tagged [Edge3.visible] against [cameraPosition] via [prismEdgeVisibility].
 */
internal fun loopEdges(base: List<Vec3>, top: List<Vec3>, axis: Vec3, cameraPosition: Vec3, maxVerticals: Int = 8): List<Edge3> {
    val n = base.size
    val visibility = prismEdgeVisibility(base, top, axis, cameraPosition)
    val baseEdges = (0 until n).map { Edge3(base[it], base[(it + 1) % n], visibility.baseVisible[it]) }
    val topEdges = (0 until n).map { Edge3(top[it], top[(it + 1) % n], visibility.topVisible[it]) }
    // A box has only 4 corners, so all 4 verticals are drawn; a circle's ring has many more
    // points than are worth a vertical each, so only every Nth one is — enough to read as a
    // cylinder without the wireframe turning into a solid mesh of lines.
    val step = maxOf(1, n / maxVerticals)
    val verticals = (0 until n step step).map { Edge3(base[it], top[it], visibility.verticalVisible[it]) }
    return baseEdges + topEdges + verticals
}

/** Screen-space anchor point for a shape's combined dimension label — its top face's centre. */
internal fun labelAnchor(top: List<Vec3>): Vec3 = centroid(top)

/** "1.2 m x 0.8 m x 0.5 m" — the box tool's combined dimension label. */
internal fun formatBoxDimensions(lengthU: Float, lengthV: Float, height: Float, unit: LengthUnit, locale: Locale = Locale.getDefault()): String {
    val length = formatLength(abs(lengthU), unit, locale)
    val width = formatLength(abs(lengthV), unit, locale)
    val heightLabel = formatLength(abs(height), unit, locale)
    return "$length x $width x $heightLabel"
}

/** "⌀0.6 m x 0.5 m" — the cylinder tool's combined dimension label. */
internal fun formatCylinderDimensions(radius: Float, height: Float, unit: LengthUnit, locale: Locale = Locale.getDefault()): String {
    val diameter = formatLength(2f * abs(radius), unit, locale)
    val heightLabel = formatLength(abs(height), unit, locale)
    return "⌀$diameter x $heightLabel"
}
