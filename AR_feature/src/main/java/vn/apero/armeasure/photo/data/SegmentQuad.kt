package vn.apero.armeasure.photo.data

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.domain.imaging.isPlausibleReferenceQuad
import vn.apero.armeasure.photo.domain.imaging.quadFromMask

/**
 * Auto-fits the reference object's 4 corners using ML Kit's on-device subject segmentation, in the
 * same bitmap-pixel space as [tapPointBitmapSpace] — or null, in which case the caller falls back to
 * the Canny+Hough path in [autoFitQuad].
 *
 * Why segmentation and not more edge tuning: an edge detector can only find a boundary that exists as
 * a luminance or colour gradient, and on real photos the reference object's boundary frequently does
 * not. Measured on a black phone lying on a white desk, its own shadow merged with its body: along the
 * object's true bottom edge the luminance difference was 1/255 and the colour difference 0/255, and the
 * best quad obtainable from ANY pairing of detected lines was capped at 0.43 IoU across a 27-point
 * sweep of the Hough parameters. Segmentation does not need the gradient — it decides which pixels
 * belong to the object from learned appearance, then [quadFromMask] measures the region.
 *
 * This is the licence-clean equivalent of what ARuler gets from FastSAM, which is built on YOLOv8 and
 * therefore AGPL-3.0 and unusable in a commercial app.
 *
 * Segmentation is unbundled — the model ships through Play Services — so it is simply unavailable on
 * some devices. Every failure path here returns null rather than throwing, because "no auto-fit" is a
 * supported outcome (the user drags the box) while a crash is not.
 */
internal suspend fun segmentQuad(
    photo: Bitmap,
    tapPointBitmapSpace: Vec2,
    // long/short of the real object, when known. Used only to reject a mask that clearly isn't it —
    // the commonest way segmentation goes wrong is swallowing an attached shadow or a second object,
    // and both show up as proportions nothing like the reference's.
    targetAspectRatio: Float? = null,
): List<Vec2>? = withContext(Dispatchers.Default) {
    // Every step below is real per-pixel work — a bilinear rescale of a few megapixels, a 3 MB
    // FloatArray copy out of the mask buffer, a flood fill over ~790k pixels and a convex-hull fit.
    // It ran on the main thread until this wrapper existed, which is also why the "detecting"
    // spinner never appeared: the thread that would have animated it was the one doing the work.
    if (photo.width < 20 || photo.height < 20) return@withContext null

    // Segmentation runs on a downscaled copy: the model has its own fixed internal resolution, so a
    // full-size input buys no accuracy while the returned per-pixel FloatBuffer costs 4 bytes per
    // source pixel (12 MB on a 1542x2048 photo) and the flood fill has to walk all of it.
    val longSide = maxOf(photo.width, photo.height)
    val scale = if (longSide > SegmentationLongSidePx) SegmentationLongSidePx.toFloat() / longSide else 1f
    val width = (photo.width * scale).toInt().coerceAtLeast(1)
    val height = (photo.height * scale).toInt().coerceAtLeast(1)
    val scaled = if (scale == 1f) photo else Bitmap.createScaledBitmap(photo, width, height, true)

    val mask = try {
        foregroundConfidenceMask(scaled, width, height)
    } catch (error: Throwable) {
        // Includes the model not being downloaded yet and Play Services being absent entirely.
        Log.d(DiagTag, "segmentation unavailable: ${error.javaClass.simpleName}: ${error.message}")
        null
    } finally {
        if (scaled !== photo) scaled.recycle()
    } ?: return@withContext null

    val quad = quadFromMask(
        mask = mask,
        width = width,
        height = height,
        seed = Vec2(tapPointBitmapSpace.x * scale, tapPointBitmapSpace.y * scale),
    )
    val plausible = quad != null && isPlausibleReferenceQuad(
        quad = quad,
        imageWidth = width.toFloat(),
        imageHeight = height.toFloat(),
        targetAspectRatio = targetAspectRatio,
        maxAspectDeviation = MaxAspectDeviation,
    )
    if (quad == null || !plausible) return@withContext null
    // Back to full bitmap space.
    quad.map { Vec2(it.x / scale, it.y / scale) }
}

/**
 * How far a segmented region's proportions may deviate from the reference object's, as
 * |ln(ratio/target)|. ~1.65x either way — deliberately looser than the edge path's 0.35, because a
 * mask boundary is approximate by nature while a Hough corner is the intersection of two exact lines.
 */
private const val MaxAspectDeviation = 0.5f

/**
 * The foreground confidence mask as a plain row-major [FloatArray], one value per pixel of [bitmap].
 *
 * Only the foreground mask is requested, not per-subject masks: [quadFromMask] flood-fills from the
 * tap, which already isolates the one object the user pointed at, so paying the model to separate
 * every subject in the frame would buy nothing.
 */
private suspend fun foregroundConfidenceMask(bitmap: Bitmap, width: Int, height: Int): FloatArray? {
    val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
    )
    // Bounded wait. A Task that never settles used to hang the coroutine for good, leaving the
    // screen showing "detecting" with no way out and no error — a tap could only start another
    // segmenter alongside the stuck one. Timing out falls through to the edge detector instead.
    return try {
        withTimeoutOrNull(SegmentationTimeoutMs) {
            suspendCancellableCoroutine { continuation ->
                segmenter.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        val buffer = result.foregroundConfidenceMask
                        val values = if (buffer == null || buffer.remaining() < width * height) {
                            null
                        } else {
                            FloatArray(width * height).also { buffer.get(it) }
                        }
                        if (continuation.isActive) continuation.resume(values)
                    }
                    .addOnFailureListener { error ->
                        Log.d(DiagTag, "segmentation failed: ${error.javaClass.simpleName}: ${error.message}")
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        }
    } finally {
        // Closed here and nowhere else. Closing inside the listeners as well as on cancellation
        // gave the client two paths to a double close.
        segmenter.close()
    }
}

/**
 * How long to wait for segmentation before falling back to edge detection.
 *
 * Generous: the model is fetched through Play Services on first use, so a genuine first run can be
 * slow without being stuck. Short enough that a wedged Task does not strand the screen.
 */
private const val SegmentationTimeoutMs = 15_000L

/**
 * Long side the photo is downscaled to before segmentation. Larger than the edge pipeline's 900
 * because a mask boundary is only as precise as the pixels it was computed on, and the corner
 * positions come straight from that boundary.
 */
private const val SegmentationLongSidePx = 1024

/**
 * Logcat tag for the two failure paths below.
 *
 * Kept after the tuning diagnostics were removed: both are silent fallbacks, so without a line in the
 * log an unavailable model is indistinguishable from a model that ran and found nothing.
 */
private const val DiagTag = "PhotoAutoFit"