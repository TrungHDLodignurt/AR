package vn.quancua.artapemeasure.measure

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
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
data class Vec3(val x: Float, val y: Float, val z: Float)

/** Straight-line distance between two world-space points, in metres. */
fun measureDistanceMeters(a: Vec3, b: Vec3): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val dz = b.z - a.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** Midpoint of the segment `a`–`b` — where a segment's label sits. */
fun measureMidpoint(a: Vec3, b: Vec3): Vec3 =
    Vec3(x = (a.x + b.x) / 2f, y = (a.y + b.y) / 2f, z = (a.z + b.z) / 2f)

operator fun Vec3.plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
operator fun Vec3.minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
operator fun Vec3.times(scalar: Float): Vec3 = Vec3(x * scalar, y * scalar, z * scalar)
fun Vec3.dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

/** Normalizes to unit length, or returns the input unchanged when it is too close to zero to have a direction. */
fun Vec3.normalized(): Vec3 {
    val length = sqrt(x * x + y * y + z * z)
    return if (length > 1e-6f) Vec3(x / length, y / length, z / length) else this
}

/** A world-space ray: an [origin] plus a unit-length [direction]. */
data class Ray3(val origin: Vec3, val direction: Vec3)

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
fun intersectRayPlane(ray: Ray3, planePoint: Vec3, planeNormal: Vec3): Vec3? {
    val denom = ray.direction.dot(planeNormal)
    if (abs(denom) < 1e-4f) return null
    val t = (planePoint - ray.origin).dot(planeNormal) / denom
    if (t < 0f) return null
    return ray.origin + ray.direction * t
}

/**
 * Formats metres the way the reference app does: at most two decimals, trailing zero
 * trimmed, decimal separator from [locale] — "1,6 m", "2,45 m".
 *
 * Two decimals on metres is centimetre precision, already at the edge of what the
 * underlying pose supports. A third decimal would render millimetres and would be a lie
 * about that precision.
 */
fun formatMeters(meters: Float, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
        isGroupingUsed = false
    }
    return "${format.format(meters)} m"
}

/** Feet and inches, rounded to the nearest inch — "5' 3\"". */
fun formatImperial(meters: Float): String {
    val totalInches = (meters * 39.3700787f).roundToInt()
    return "${totalInches / 12}' ${totalInches % 12}\""
}

/** Display unit. Imperial is a hard requirement for the US market. */
enum class LengthUnit { Metric, Imperial }

fun formatLength(meters: Float, unit: LengthUnit, locale: Locale = Locale.getDefault()): String =
    when (unit) {
        LengthUnit.Metric -> formatMeters(meters, locale)
        LengthUnit.Imperial -> formatImperial(meters)
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
fun nearestIndexWithin(
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
fun measurePointsMoved(
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
