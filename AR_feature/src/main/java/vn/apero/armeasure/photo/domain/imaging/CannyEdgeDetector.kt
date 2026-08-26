package vn.apero.armeasure.photo.domain.imaging

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Classical Canny edge detection — Sobel gradients, non-maximum suppression, hysteresis
 * threshold — the textbook algorithm, not a trained model. This is the "no ML, no license risk"
 * alternative to ARuler's FastSAM auto-fit: it can't segment an arbitrary object, but a
 * reference object is always a plain rectangle, and a rectangle's edges are exactly what Canny
 * is good at.
 *
 * Thresholds are adaptive (percentiles of the gradient magnitude actually present), not fixed
 * absolute numbers — a fixed threshold tuned on one photo's lighting fails on the next.
 */

private const val NoEdge = 0f
private const val WeakEdge = 1f
private const val StrongEdge = 2f

/** Runs the full pipeline and returns a boolean edge map (`true` = edge pixel). */
internal fun cannyEdges(image: GrayscaleImage): BooleanArray {
    val blurred = gaussianBlur(image)
    val (magnitude, direction) = sobelGradients(blurred)
    val suppressed = nonMaxSuppression(magnitude, direction, blurred.width, blurred.height)
    return hysteresisThreshold(suppressed, blurred.width, blurred.height)
}

private data class GradientField(val magnitude: FloatArray, val direction: FloatArray)

private fun sobelGradients(image: GrayscaleImage): GradientField {
    val magnitude = FloatArray(image.width * image.height)
    val direction = FloatArray(image.width * image.height)

    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val x0 = (x - 1).coerceIn(0, image.width - 1)
            val x2 = (x + 1).coerceIn(0, image.width - 1)
            val y0 = (y - 1).coerceIn(0, image.height - 1)
            val y2 = (y + 1).coerceIn(0, image.height - 1)

            val gx = (image.get(x2, y0) + 2 * image.get(x2, y) + image.get(x2, y2)) -
                (image.get(x0, y0) + 2 * image.get(x0, y) + image.get(x0, y2))
            val gy = (image.get(x0, y2) + 2 * image.get(x, y2) + image.get(x2, y2)) -
                (image.get(x0, y0) + 2 * image.get(x, y0) + image.get(x2, y0))

            val index = y * image.width + x
            magnitude[index] = sqrt(gx * gx + gy * gy)
            direction[index] = atan2(gy, gx)
        }
    }
    return GradientField(magnitude, direction)
}

/**
 * Thins edges to one pixel wide: a pixel survives only if its gradient magnitude is a local
 * maximum along the gradient direction. Direction is quantised to the 4 axes (0/45/90/135°)
 * rather than truly interpolated — an approximation, but the quad this feeds only needs edges
 * accurate to a few pixels, not sub-pixel precision.
 */
private fun nonMaxSuppression(magnitude: FloatArray, direction: FloatArray, width: Int, height: Int): FloatArray {
    val result = FloatArray(width * height)
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val index = y * width + x
            val mag = magnitude[index]
            if (mag <= 0f) continue

            // Quantise the angle to one of 4 axes; each axis has a fixed pair of neighbours.
            val degrees = Math.toDegrees(direction[index].toDouble()).let { if (it < 0) it + 180 else it }
            val (dx1, dy1, dx2, dy2) = when {
                degrees < 22.5 || degrees >= 157.5 -> Quad4(1, 0, -1, 0)
                degrees < 67.5 -> Quad4(1, 1, -1, -1)
                degrees < 112.5 -> Quad4(0, 1, 0, -1)
                else -> Quad4(-1, 1, 1, -1)
            }

            val neighbour1 = magnitude[(y + dy1) * width + (x + dx1)]
            val neighbour2 = magnitude[(y + dy2) * width + (x + dx2)]
            if (mag >= neighbour1 && mag >= neighbour2) result[index] = mag
        }
    }
    return result
}

private data class Quad4(val dx1: Int, val dy1: Int, val dx2: Int, val dy2: Int)

/**
 * Strong pixels (top [highPercentile] of the magnitude actually present) are always kept. Weak
 * pixels (above [lowPercentile]) are kept only if reachable from a strong pixel through other
 * weak pixels — the standard hysteresis rule, which is what avoids both fragmented outlines
 * (threshold too high) and noise speckle (threshold too low).
 */
private fun hysteresisThreshold(
    suppressed: FloatArray,
    width: Int,
    height: Int,
    highPercentile: Float = 0.90f,
    lowPercentile: Float = 0.55f,
): BooleanArray {
    val nonZero = suppressed.filter { it > 0f }.sorted()
    if (nonZero.isEmpty()) return BooleanArray(width * height)
    val highThreshold = nonZero[((nonZero.size - 1) * highPercentile).toInt()]
    val lowThreshold = nonZero[((nonZero.size - 1) * lowPercentile).toInt()]

    val state = FloatArray(width * height)
    for (i in suppressed.indices) {
        state[i] = when {
            suppressed[i] >= highThreshold -> StrongEdge
            suppressed[i] >= lowThreshold -> WeakEdge
            else -> NoEdge
        }
    }

    val edges = BooleanArray(width * height)
    val stack = ArrayDeque<Int>()
    for (i in state.indices) if (state[i] == StrongEdge) stack.addLast(i)

    while (stack.isNotEmpty()) {
        val index = stack.removeLast()
        if (edges[index]) continue
        edges[index] = true
        val x = index % width
        val y = index / width
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val neighbourIndex = ny * width + nx
                if (!edges[neighbourIndex] && state[neighbourIndex] >= WeakEdge) stack.addLast(neighbourIndex)
            }
        }
    }
    return edges
}
