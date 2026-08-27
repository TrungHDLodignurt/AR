package vn.apero.armeasure.photo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a picked photo, corrected for its EXIF orientation.
 *
 * `BitmapFactory` decodes raw pixels only — it ignores the EXIF orientation tag a phone camera
 * writes, so a photo taken in portrait can decode sideways. That would silently rotate the
 * quad the user drags relative to the reference object's real edges, which breaks the long/short
 * side convention `PhotoMeasureReducers.confirmReference` relies on. `android.media.ExifInterface`
 * has taken an `InputStream` since API 24 — this app's floor — so no extra dependency is needed.
 *
 * Also downscales long photos: a 12+ MP camera photo is far more resolution than a screen can
 * show, and the homography maths only needs a consistent pixel space, not the original one.
 *
 * Suspending, on [Dispatchers.IO]: this reads a content Uri and decodes several megapixels, which
 * has no business on the main thread. It used to be called straight from a UI callback.
 *
 * The decode is sampled down as it reads rather than decoded whole and shrunk afterwards. Decoding a
 * 3072x4080 photo at full size costs 50 MB of ARGB_8888, and rotating it allocated a second copy for
 * a 100 MB peak — a genuine OOM risk on a low-heap device, and all of it thrown away moments later
 * by the downscale to [maxDimensionPx].
 */
internal suspend fun loadRotatedBitmap(
    context: Context,
    uri: Uri,
    maxDimensionPx: Int = 2048,
): Bitmap? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver

    val orientation = resolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } ?: ExifInterface.ORIENTATION_NORMAL

    // Bounds-only pass: no pixels allocated, just the stored dimensions, so inSampleSize can be
    // chosen before anything large exists.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), maxDimensionPx)
    }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return@withContext null

    val rotationDegrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val rotated = if (rotationDegrees == 0f) {
        decoded
    } else {
        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    // inSampleSize only halves, so the sampled result can still be up to 2x the target; this trims
    // the remainder exactly.
    val longestSide = maxOf(rotated.width, rotated.height)
    if (longestSide <= maxDimensionPx) return@withContext rotated

    val scale = maxDimensionPx.toFloat() / longestSide
    val scaled = Bitmap.createScaledBitmap(
        rotated,
        (rotated.width * scale).toInt().coerceAtLeast(1),
        (rotated.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== rotated) rotated.recycle()
    scaled
}

/**
 * Largest power-of-two sample size that keeps the decoded long side at or above [maxDimensionPx].
 *
 * Stops one step early on purpose: sampling past the target would decode smaller than needed and
 * lose detail the corner detection depends on, and the exact trim happens afterwards anyway.
 * `BitmapFactory` rounds a non-power-of-two down to one, so only powers of two are worth returning.
 */
private fun sampleSizeFor(longestSidePx: Int, maxDimensionPx: Int): Int {
    if (longestSidePx <= 0 || maxDimensionPx <= 0) return 1
    var sampleSize = 1
    while (longestSidePx / (sampleSize * 2) >= maxDimensionPx) {
        sampleSize *= 2
    }
    return sampleSize
}
