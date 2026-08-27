package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln

/**
 * The single "could this quad be the reference object?" test, shared by both detectors.
 *
 * One gate rather than one per detector: the edge and segmentation paths each grew their own set of
 * constants, and the two disagreed on every threshold with no reason ever written down. A user cannot
 * tell which detector produced a box, so they must not be judged by different rules.
 *
 * These bounds only reject the OBVIOUSLY implausible. The dangerous failure — a quad of the right
 * size and the right proportion locked onto the wrong object — passes every check here, and nothing
 * threshold-shaped will catch it; that one is caught by the user seeing the box before confirming.
 * So every bound is deliberately generous: tightening costs real rejections of correct detections and
 * buys almost nothing against the failure that actually hurts.
 */
internal fun isPlausibleReferenceQuad(
    quad: List<Vec2>,
    imageWidth: Float = Float.MAX_VALUE,
    imageHeight: Float = Float.MAX_VALUE,
    targetAspectRatio: Float? = null,
    /**
     * Per-detector, because their corners are not equally trustworthy: Hough intersects exact lines,
     * a mask boundary is approximate by nature.
     */
    maxAspectDeviation: Float = DefaultMaxAspectDeviation,
): Boolean {
    if (quad.size != 4) return false
    if (quad.any { !it.x.isFinite() || !it.y.isFinite() }) return false

    val area = polygonAreaOf(quad)
    if (area < 1e-3f) return false
    if (!isConvexQuad(quad)) return false
    if (!hasBelievableOppositeSides(quad)) return false

    if (targetAspectRatio != null && targetAspectRatio > 0f) {
        val ratio = averagedAspectRatio(quad) ?: return false
        // Log-ratio: scale-invariant and symmetric, so twice too wide and twice too narrow are
        // penalised alike.
        if (abs(ln(ratio / targetAspectRatio)) > maxAspectDeviation) return false
    }

    // Unbounded plane (synthetic tests, and callers that genuinely do not know the frame).
    if (imageWidth == Float.MAX_VALUE || imageHeight == Float.MAX_VALUE) return true

    // Corner outside the frame, or hard against its edge.
    //
    // This catches two different failures with one test. A segmentation that decided the whole scene
    // is the foreground yields a quad reaching the image edges. And a reference object CUT OFF by the
    // frame also reaches them — that one matters more than it looks: the visible part can sit well
    // under any area ceiling while the length it implies is simply not the object's real length, so
    // the calibration is wrong with nothing on screen to suggest it.
    val inset = minOf(imageWidth, imageHeight) * BorderInsetFraction
    if (quad.any { it.x < inset || it.y < inset || it.x > imageWidth - inset || it.y > imageHeight - inset }) {
        return false
    }

    val imageArea = imageWidth * imageHeight
    if (area < MinAreaFractionOfImage * imageArea) return false
    if (area > MaxAreaFractionOfImage * imageArea) return false
    return true
}

/**
 * Smallest share of the frame a real reference object may occupy.
 *
 * Deliberately tiny. A payment card — the commonest reference object there is — measures 85.6 x 54 mm,
 * so in a normal photo of a desk spanning roughly half a metre it covers about 1.4% of the frame, and
 * under 4% even in a close shot. A floor anywhere near "a few percent" rejects it in almost every
 * photo. This one is here only to discard mask speckle.
 */
private const val MinAreaFractionOfImage = 0.001f

/**
 * Largest share of the frame a real reference object may occupy.
 *
 * A backstop, not the main defence: a segmentation that treats the whole scene as foreground usually
 * lands at 95-100% and is caught by the border test above regardless. Left generous because someone
 * can legitimately hold an A4 sheet close enough to fill most of the frame.
 */
private const val MaxAreaFractionOfImage = 0.85f

/** How close to the frame edge a corner may sit, as a fraction of the frame's shorter side. */
private const val BorderInsetFraction = 0.005f

/** Default proportion tolerance, as |ln(ratio/target)| — about 1.4x either way. */
private const val DefaultMaxAspectDeviation = 0.35f

/**
 * How unequal a pair of opposite sides may be, as (long-short)/long.
 *
 * Raised from the original 0.30 once the detector began fitting general quadrilaterals. That number
 * predated perspective support and would now reject the very case that support was added for: a
 * rectangle on a table shot from a low angle projects to a trapezoid whose near edge can be 300px
 * against a far edge of 180px — a 40% difference, and a perfectly real one. Past roughly half, though,
 * the shape has stopped being a rectangle seen in perspective and is four unrelated lines, which is
 * what this check was for in the first place.
 */
private const val MaxOppositeSideMismatch = 0.50f

private fun hasBelievableOppositeSides(quad: List<Vec2>): Boolean {
    fun side(i: Int) = hypot(quad[(i + 1) % 4].x - quad[i].x, quad[(i + 1) % 4].y - quad[i].y)
    fun mismatch(a: Float, b: Float): Float {
        val longer = maxOf(a, b)
        if (longer <= 1e-3f) return Float.MAX_VALUE
        return (longer - minOf(a, b)) / longer
    }
    return mismatch(side(0), side(2)) <= MaxOppositeSideMismatch &&
        mismatch(side(1), side(3)) <= MaxOppositeSideMismatch
}

/**
 * long/short, averaging each pair of opposite sides — under perspective the two long sides genuinely
 * differ, so either alone misreports the proportion.
 */
internal fun averagedAspectRatio(quad: List<Vec2>): Float? {
    fun side(i: Int) = hypot(quad[(i + 1) % 4].x - quad[i].x, quad[(i + 1) % 4].y - quad[i].y)
    val pair0 = (side(0) + side(2)) / 2f
    val pair1 = (side(1) + side(3)) / 2f
    if (pair0 <= 1e-3f || pair1 <= 1e-3f) return null
    return maxOf(pair0, pair1) / minOf(pair0, pair1)
}

private fun isConvexQuad(quad: List<Vec2>): Boolean {
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
