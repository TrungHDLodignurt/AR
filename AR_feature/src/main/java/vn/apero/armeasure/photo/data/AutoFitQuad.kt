package vn.apero.armeasure.photo.data

import android.graphics.Bitmap
import android.util.Log
import vn.apero.armeasure.photo.domain.imaging.GrayscaleImage
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.domain.imaging.cannyEdges
import vn.apero.armeasure.photo.domain.imaging.houghLines
import vn.apero.armeasure.photo.domain.imaging.quadFromLines

private const val LogTag = "PhotoMeasureAutoFit"

/**
 * Runs Canny edge detection + Hough line transform on the WHOLE of [photo] (downscaled — see
 * [maxDetectionDimensionPx]) and returns the 4 corners of the most plausible rectangle enclosing
 * [tapPointBitmapSpace], in the same bitmap-pixel coordinate space as the input point — or null
 * if nothing plausible was found, in which case the caller falls back to a plain default box
 * (see `PhotoMeasureState.revealQuadAt`).
 *
 * This is the "no ML, no license risk" alternative to ARuler's FastSAM-based auto-fit: it can't
 * segment an arbitrary object, but a reference object is always a plain rectangle, and a
 * rectangle's edges are exactly what Canny+Hough are good at.
 *
 * A tap-centred crop was tried first and measurably failed: a real 1542x2048 photo's reference
 * object routinely has edges farther apart than any crop window small enough to stay fast, so
 * `quadFromLines` never even saw all 4 sides and always returned null. The whole image sees every
 * edge, but Hough at full camera resolution took seconds per tap on-device — too slow to run on a
 * tap. Downscaling first keeps the "see everything" property while staying cheap, the same
 * trade-off `resizeForDetection` makes in the original OpenCV-based spec this pipeline replaces.
 *
 * Does real per-pixel work over the downscaled image (Canny is O(image size), Hough is
 * O(edge pixels × theta steps)) — callers must run this off the main thread.
 *
 * [expectedAspectRatio] is the known reference object's long/short side ratio (e.g. A4 =
 * 297/210). Passed through to [quadFromLines] to reject/score candidates by shape — see that
 * function's doc. Null skips the constraint entirely (pure vote-based selection).
 */
internal fun autoFitQuad(
    photo: Bitmap,
    tapPointBitmapSpace: Vec2,
    maxDetectionDimensionPx: Int = 900,
    expectedAspectRatio: Float? = null,
): List<Vec2>? {
    val scale = minOf(
        maxDetectionDimensionPx.toFloat() / photo.width,
        maxDetectionDimensionPx.toFloat() / photo.height,
        1f, // never upscale — a small source photo needs no extra detail invented
    )
    val detectionWidth = (photo.width * scale).toInt().coerceAtLeast(1)
    val detectionHeight = (photo.height * scale).toInt().coerceAtLeast(1)
    val detectionBitmap = if (scale < 1f) {
        Bitmap.createScaledBitmap(photo, detectionWidth, detectionHeight, true)
    } else {
        photo
    }

    val grayscale = extractGrayscaleWindow(detectionBitmap, 0, 0, detectionWidth, detectionHeight)
    val edges = cannyEdges(grayscale)
    val lines = houghLines(edges, detectionWidth, detectionHeight)

    val tapInDetection = Vec2(tapPointBitmapSpace.x * scale, tapPointBitmapSpace.y * scale)
    val quadInDetection = quadFromLines(
        lines,
        tapInDetection,
        expectedAspectRatio = expectedAspectRatio,
        imageWidth = detectionWidth.toFloat(),
        imageHeight = detectionHeight.toFloat(),
    )

    if (Log.isLoggable(LogTag, Log.DEBUG)) {
        val edgeCount = edges.count { it }
        val edgePercent = "%.2f".format(100f * edgeCount / edges.size)
        val thetas = lines.joinToString { "%.0f".format(Math.toDegrees(it.thetaRadians.toDouble())) }
        val votes = lines.joinToString { it.votes.toString() }
        val quadStatus = if (quadInDetection != null) "FOUND" else "NULL"
        Log.d(
            LogTag,
            "tap=$tapPointBitmapSpace detection=${detectionWidth}x$detectionHeight scale=$scale " +
                "edges=$edgeCount ($edgePercent%) lines=${lines.size} quad=$quadStatus " +
                "thetas=[$thetas] votes=[$votes] expectedAspectRatio=$expectedAspectRatio",
        )
    }

    if (detectionBitmap !== photo) detectionBitmap.recycle()

    return quadInDetection?.map { Vec2(it.x / scale, it.y / scale) }
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
