package vn.apero.armeasure.ar.domain.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure measurement maths.
 *
 * Zero third-party types on purpose — not ARCore's `Pose`, not SceneView's `Position`, not
 * even kotlin-math's `Float3`. Everything takes the local [Vec3] so this file runs in a plain
 * JVM unit test with no device, no emulator, no GL context and no JDK-version coupling to a
 * transitive dependency. Conversion happens once, at the AR boundary.
 *
 * The composables own the AR plumbing; this file owns the arithmetic that produces the
 * numbers shown to the user — the part that can be wrong in a way no screenshot reveals.
 *
 * The arithmetic here is exact. The world-space points fed into it are not: on a phone
 * without a depth sensor a single point carries several centimetres of error, and two points
 * compound. Treat the output as a layout estimate, never as a caliper reading.
 */

/** A world-space point in metres, in ARCore's right-handed frame (+Y is gravity-up). */
internal data class Vec3(val x: Float, val y: Float, val z: Float)

/** Straight-line distance between two world-space points, in metres. */
internal fun measureDistanceMeters(a: Vec3, b: Vec3): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val dz = b.z - a.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** Midpoint of the segment `a`–`b` — where a segment's label sits. */
internal fun measureMidpoint(a: Vec3, b: Vec3): Vec3 =
    Vec3(x = (a.x + b.x) / 2f, y = (a.y + b.y) / 2f, z = (a.z + b.z) / 2f)

internal operator fun Vec3.plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
internal operator fun Vec3.minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
internal operator fun Vec3.times(scalar: Float): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)
internal fun Vec3.dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

/** Right-hand-rule cross product — feeds [vn.apero.armeasure.ar.domain.geometry.planeBasis], where the second in-plane axis is `normal cross primaryAxis`. */
internal fun Vec3.cross(other: Vec3): Vec3 = Vec3(
    x = y * other.z - z * other.y,
    y = z * other.x - x * other.z,
    z = x * other.y - y * other.x,
)

/** Normalizes to unit length, or returns the input unchanged when it is too close to zero to have a direction. */
internal fun Vec3.normalized(): Vec3 {
    val length = sqrt(x * x + y * y + z * z)
    return if (length > 1e-6f) Vec3(x / length, y / length, z / length) else this
}

/** This vector's own magnitude, treating it as a displacement rather than a point. */
internal fun Vec3.length(): Float = sqrt(x * x + y * y + z * z)

/** A world-space ray: an [origin] plus a unit-length [direction]. */
internal data class Ray3(val origin: Vec3, val direction: Vec3)

/**
 * Where [ray] punches through the infinite plane defined by [planePoint] and [planeNormal].
 *
 * ARCore's own plane hit test walks the plane's live triangulated mesh, which keeps
 * re-triangulating as tracking refines its estimate of the surface — so a perfectly still
 * reticle over a perfectly still plane can still report a slightly different point from one
 * frame to the next, purely from mesh churn and not from anything real moving. Intersecting
 * the aim ray against the plane's mathematical definition instead — a point plus a normal —
 * is exact and immune to that churn. This is the technique the reference app (ARuler) uses
 * for its live reading, which is why its reticle holds noticeably steadier than a per-frame
 * mesh-based hit test does.
 *
 * Returns null when the ray is (near) parallel to the plane, or when the plane is behind the
 * ray's origin — both mean there is no meaningful intersection to report.
 */
internal fun intersectRayPlane(ray: Ray3, planePoint: Vec3, planeNormal: Vec3): Vec3? {
    val denom = ray.direction.dot(planeNormal)
    if (abs(denom) < 1e-4f) return null
    val t = (planePoint - ray.origin).dot(planeNormal) / denom
    if (t < 0f) return null
    return ray.origin + ray.direction * t
}

/**
 * Index of the closest entry in [positions] to [touch], provided it is within [maxDistancePx].
 *
 * Backs "drag an existing point to move it": screen positions are supplied as plain pixel
 * pairs (not `Offset`) so this hit-test — the part of dragging that can silently grab the
 * wrong point — runs in a plain JVM test with no Compose or ARCore on the classpath. A `null`
 * entry means that point currently projects behind the camera and can never be the closest
 * hit. Ties (equal distance) keep the earlier index, matching iteration order.
 */
internal fun nearestIndexWithin(
    positions: List<Pair<Float, Float>?>,
    touch: Pair<Float, Float>,
    maxDistancePx: Float,
): Int? {
    val maxDistanceSq = maxDistancePx * maxDistancePx
    var bestIndex: Int? = null
    var bestDistanceSq = Float.MAX_VALUE
    positions.forEachIndexed { i, position ->
        val (x, y) = position ?: return@forEachIndexed
        val dx = x - touch.first
        val dy = y - touch.second
        val distanceSq = dx * dx + dy * dy
        // Strict less-than on the running best (the qualifying bound above stays <=) so an
        // exact tie keeps the earlier index instead of the later one overwriting it.
        if (distanceSq <= maxDistanceSq && distanceSq < bestDistanceSq) {
            bestDistanceSq = distanceSq
            bestIndex = i
        }
    }
    return bestIndex
}

/**
 * True when any point moved more than [epsilonMeters], or when the count changed.
 *
 * Anchor poses must be re-read every ARCore frame, because ARCore corrects them as it
 * refines its map of the room — a measurement that ignored those corrections would drift
 * away from the marker the user is looking at. But pushing those poses into Compose state
 * unconditionally would recompose at frame rate for sub-millimetre jitter nobody can see,
 * and would make the on-screen number flicker.
 *
 * 1 mm is well below the accuracy floor (centimetres at best), so nothing observable is
 * discarded by this gate.
 */
internal fun measurePointsMoved(
    previous: List<Vec3>,
    current: List<Vec3>,
    epsilonMeters: Float = 0.001f,
): Boolean {
    if (previous.size != current.size) return true
    for (i in previous.indices) {
        if (measureDistanceMeters(previous[i], current[i]) > epsilonMeters) return true
    }
    return false
}

/**
 * Which point pairs form the drawn segments, for a measurement of [pointCount] points.
 *
 * The single place the two distance tools differ. Chained: every point continues the line, so
 * point *i* is both the end of one segment and the start of the next. Unchained: points are
 * consumed two at a time — tap a start, tap an end, and the next tap begins a fresh, unconnected
 * segment.
 *
 * Pure and pairing is the whole behavioural delta between the tools, so it lives here rather than
 * inline at the three call sites that need it (segment build, rubber-band gate, result emission),
 * which would otherwise drift apart. A trailing unpaired point in the unchained case is not a
 * segment yet and is simply absent from the result.
 */
internal fun segmentIndexPairs(pointCount: Int, chained: Boolean): List<Pair<Int, Int>> {
    if (pointCount < 2) return emptyList()
    val step = if (chained) 1 else 2
    return (0 until pointCount - 1 step step).map { it to it + 1 }
}

/**
 * True when the next committed point closes a segment rather than starting one.
 *
 * Drives the dashed rubber band: it must trail the reticle only while a segment is actually open.
 * In the unchained tool an even, non-zero point count means every segment is closed, so drawing a
 * band from the last point would claim a connection that does not exist — which is the exact
 * confusion the unchained tool exists to avoid.
 */
internal fun hasOpenSegment(pointCount: Int, chained: Boolean): Boolean =
    if (chained) pointCount > 0 else pointCount % 2 == 1
