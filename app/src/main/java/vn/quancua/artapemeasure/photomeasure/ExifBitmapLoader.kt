package vn.quancua.artapemeasure.photomeasure

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri

/**
 * Decodes a picked photo, corrected for its EXIF orientation.
 *
 * `BitmapFactory` decodes raw pixels only — it ignores the EXIF orientation tag a phone camera
 * writes, so a photo taken in portrait can decode sideways. That would silently rotate the
 * quad the user drags relative to the reference object's real edges, which breaks the long/short
 * side convention `PhotoMeasureState.confirmReference` relies on. `android.media.ExifInterface`
 * has taken an `InputStream` since API 24 — this app's floor — so no extra dependency is needed.
 *
 * Also downscales long photos: a 12+ MP camera photo is far more resolution than a screen can
 * show, and the homography maths only needs a consistent pixel space, not the original one.
 */
fun loadRotatedBitmap(context: Context, uri: Uri, maxDimensionPx: Int = 2048): Bitmap? {
    val resolver = context.contentResolver

    val orientation = resolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } ?: ExifInterface.ORIENTATION_NORMAL

    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null

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

    val longestSide = maxOf(rotated.width, rotated.height)
    if (longestSide <= maxDimensionPx) return rotated

    val scale = maxDimensionPx.toFloat() / longestSide
    val scaled = Bitmap.createScaledBitmap(
        rotated,
        (rotated.width * scale).toInt().coerceAtLeast(1),
        (rotated.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== rotated) rotated.recycle()
    return scaled
}
