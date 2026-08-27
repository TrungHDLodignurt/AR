package vn.apero.armeasure.photo.data

import android.graphics.Bitmap
import vn.apero.armeasure.photo.domain.imaging.DetectionLongSidePx
import vn.apero.armeasure.photo.domain.imaging.GrayscaleImage
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.domain.imaging.detectQuadInGrayscale

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
internal fun autoFitQuad(
    photo: Bitmap,
    tapPointBitmapSpace: Vec2,
    // long/short of the real reference object, when known — the constraint that separates the object
    // from the many clutter rectangles a real photo contains. See quadFromLines.
    targetAspectRatio: Float? = null,
): List<Vec2>? {
    if (photo.width < 20 || photo.height < 20) return null

    val longSide = maxOf(photo.width, photo.height)
    val scale = if (longSide > DetectionLongSidePx) DetectionLongSidePx.toFloat() / longSide else 1f
    val detectWidth = (photo.width * scale).toInt().coerceAtLeast(1)
    val detectHeight = (photo.height * scale).toInt().coerceAtLeast(1)

    val grayscale = extractGrayscaleScaled(photo, detectWidth, detectHeight)
    val pointInDetectSpace = Vec2(tapPointBitmapSpace.x * scale, tapPointBitmapSpace.y * scale)
    val detection = detectQuadInGrayscale(grayscale, pointInDetectSpace, targetAspectRatio)

    val quad = detection.quad ?: return null
    // Back to full bitmap space.
    return quad.map { Vec2(it.x / scale, it.y / scale) }
}

private fun extractGrayscaleScaled(bitmap: Bitmap, width: Int, height: Int): GrayscaleImage {
    val scaled = if (bitmap.width == width && bitmap.height == height) {
        bitmap
    } else {
        Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
    val pixels = IntArray(width * height)
    scaled.getPixels(pixels, 0, width, 0, 0, width, height)
    if (scaled !== bitmap) scaled.recycle()

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
