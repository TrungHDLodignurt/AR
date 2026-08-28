package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The 3x3 projective map from the photo's own pixel grid to the reference object's real millimetres.
 *
 * **The source space is bitmap pixels, and that is not incidental.** `PhotoMeasureState` used to hold
 * the quad in screen coordinates, which meant every relayout invalidated the calibration along with
 * it — a button appearing once shifted a quad about 180px off its object, and the same reflow
 * corrupted a hand-captured measurement fixture badly enough to waste a session of tuning. Solving
 * from the photo's own grid removes the dependency entirely: nothing about the canvas can change the
 * answer. Do not "simplify" this by feeding it display coordinates.
 */

internal data class Vec2(val x: Float, val y: Float)

/** A 3x3 projective transform, row-major, applied with a perspective divide. */
internal class Homography(private val m: FloatArray) {
    init { require(m.size == 9) { "Homography needs exactly 9 coefficients" } }

    fun apply(p: Vec2): Vec2 {
        val w = m[6] * p.x + m[7] * p.y + m[8]
        val x = (m[0] * p.x + m[1] * p.y + m[2]) / w
        val y = (m[3] * p.x + m[4] * p.y + m[5]) / w
        return Vec2(x, y)
    }
}

/**
 * Solves for the homography mapping each `src[i] -> dst[i]`, i in 0..3.
 *
 * Standard DLT (Direct Linear Transform) with `h33` fixed to 1, which turns the 4 point
 * correspondences into an 8x8 linear system for the remaining 8 coefficients. Returns null when
 * the 4 points are degenerate (three or more collinear, or duplicated) — that system has no
 * unique solution, and a caller must not silently draw a meaningless reference plane.
 */
internal fun computeHomography(src: List<Vec2>, dst: List<Vec2>): Homography? {
    require(src.size == 4 && dst.size == 4) { "Homography needs exactly 4 point correspondences" }

    // Row 2i:   [x, y, 1, 0, 0, 0, -u*x, -u*y] · h = u
    // Row 2i+1: [0, 0, 0, x, y, 1, -v*x, -v*y] · h = v
    val a = Array(8) { FloatArray(8) }
    val b = FloatArray(8)
    for (i in 0 until 4) {
        val (x, y) = src[i]
        val (u, v) = dst[i]
        a[2 * i] = floatArrayOf(x, y, 1f, 0f, 0f, 0f, -u * x, -u * y)
        b[2 * i] = u
        a[2 * i + 1] = floatArrayOf(0f, 0f, 0f, x, y, 1f, -v * x, -v * y)
        b[2 * i + 1] = v
    }

    val h = solveLinearSystem(a, b) ?: return null
    return Homography(floatArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1f))
}

/** Straight-line distance, in millimetres, between two image-space points on [h]'s plane. */
internal fun measureRealDistanceMm(h: Homography, a: Vec2, b: Vec2): Float {
    val pa = h.apply(a)
    val pb = h.apply(b)
    val dx = pb.x - pa.x
    val dy = pb.y - pa.y
    return sqrt(dx * dx + dy * dy)
}

/**
 * Gaussian elimination with partial pivoting. Null on a singular (degenerate) system rather
 * than a NaN/Infinity result a caller could mistake for a real, if extreme, measurement.
 */
private fun solveLinearSystem(a: Array<FloatArray>, b: FloatArray): FloatArray? {
    val n = b.size
    val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }

    for (col in 0 until n) {
        var pivotRow = col
        for (row in col + 1 until n) {
            if (abs(m[row][col]) > abs(m[pivotRow][col])) pivotRow = row
        }
        if (abs(m[pivotRow][col]) < 1e-6f) return null
        val swap = m[col]; m[col] = m[pivotRow]; m[pivotRow] = swap

        for (row in 0 until n) {
            if (row == col) continue
            val factor = m[row][col] / m[col][col]
            for (k in col..n) m[row][k] -= factor * m[col][k]
        }
    }
    return FloatArray(n) { i -> m[i][n] / m[i][i] }
}
