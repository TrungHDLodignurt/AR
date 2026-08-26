package vn.apero.armeasure.photo.data

import android.graphics.Bitmap
import vn.apero.armeasure.photo.domain.imaging.GrayscaleImage
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.domain.imaging.cannyEdges
import vn.apero.armeasure.photo.domain.imaging.houghLines
import vn.apero.armeasure.photo.domain.imaging.quadFromLines

/**
 * Runs Canny edge detection + Hough line transform on a window of [photo] around
 * [tapPointBitmapSpace] and returns the 4 corners of the most plausible surrounding rectangle,
 * in the same bitmap-pixel coordinate space as the input point — or null if nothing plausible
 * was found, in which case the caller falls back to a plain default box (see
 * `PhotoMeasureState.revealQuadAt`).
 *
 * This is the "no ML, no license risk" alternative to ARuler's FastSAM-based auto-fit: it can't
 * segment an arbitrary object, but a reference object is always a plain rectangle, and a
 * rectangle's edges are exactly what Canny+Hough are good at.
 *
 * Does real per-pixel work over the window (Canny is O(window size), Hough is
 * O(edge pixels × theta steps)) — callers must run this off the main thread.
 */
internal fun autoFitQuad(photo: Bitmap, tapPointBitmapSpace: Vec2, windowSizePx: Int = 480): List<Vec2>? {
    val halfWindow = windowSizePx / 2
    val left = (tapPointBitmapSpace.x - halfWindow).toInt().coerceIn(0, (photo.width - 1).coerceAtLeast(0))
    val top = (tapPointBitmapSpace.y - halfWindow).toInt().coerceIn(0, (photo.height - 1).coerceAtLeast(0))
    val right = (tapPointBitmapSpace.x + halfWindow).toInt().coerceIn(left + 1, photo.width)
    val bottom = (tapPointBitmapSpace.y + halfWindow).toInt().coerceIn(top + 1, photo.height)
    val windowWidth = right - left
    val windowHeight = bottom - top
    if (windowWidth < 20 || windowHeight < 20) return null

    val grayscale = extractGrayscaleWindow(photo, left, top, windowWidth, windowHeight)
    val edges = cannyEdges(grayscale)
    val lines = houghLines(edges, windowWidth, windowHeight)

    val pointInWindow = Vec2(tapPointBitmapSpace.x - left, tapPointBitmapSpace.y - top)
    val quadInWindow = quadFromLines(lines, pointInWindow) ?: return null

    return quadInWindow.map { Vec2(it.x + left, it.y + top) }
}

private fun extractGrayscaleWindow(bitmap: Bitmap, left: Int, top: Int, width: Int, height: Int): GrayscaleImage {
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, left, top, width, height)

    val luminance = FloatArray(width * height)
    for (i in pixels.indices) {
        val pixel = pixels[i]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        luminance[i] = 0.299f * r + 0.587f * g + 0.114f * b
    }
    return GrayscaleImage(width, height, luminance)
}
