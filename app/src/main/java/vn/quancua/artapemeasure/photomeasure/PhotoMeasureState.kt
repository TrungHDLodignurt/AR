package vn.quancua.artapemeasure.photomeasure

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.quancua.artapemeasure.measure.LengthUnit

/** The measuring line's two endpoints, in display-space pixels — both user-draggable. */
data class LiveLine(val start: Offset, val end: Offset)

/**
 * Mutable UI state for the photo-reference measure screen.
 *
 * A plain state holder rather than a ViewModel — same reasoning as `MeasureState`: nothing
 * here needs to survive process death, and a half-finished calibration is not worth restoring.
 */
class PhotoMeasureState {

    var photo by mutableStateOf<Bitmap?>(null)
        private set

    var reference by mutableStateOf(builtInReferenceObjects.first())

    /** Quad corners in display-space pixels, ordered top-left, top-right, bottom-right, bottom-left. */
    var quad by mutableStateOf<List<Offset>>(emptyList())
        private set

    /** Non-null only once the user has confirmed the quad matches [reference]. */
    var homography by mutableStateOf<Homography?>(null)
        private set

    /**
     * One measuring line the user drags into place — matching ARuler's own "Chiều dài" tool,
     * which is a single persistent line with two draggable endpoints, not tap-to-place-2-points.
     * The distance is read live off wherever the endpoints currently are, so dragging an
     * endpoint updates the number on every move rather than needing a separate commit step.
     */
    var line by mutableStateOf<LiveLine?>(null)
        private set

    var unit by mutableStateOf(LengthUnit.Metric)

    /** True while [revealQuadAt] is running the Canny+Hough auto-fit — a few hundred ms of real work. */
    var isDetectingQuad by mutableStateOf(false)
        private set

    val isCalibrated: Boolean get() = homography != null

    /** The live line's real-world length, or null before calibration/placement. */
    val currentDistanceMm: Float?
        get() {
            val h = homography ?: return null
            val l = line ?: return null
            return measureRealDistanceMm(h, Vec2(l.start.x, l.start.y), Vec2(l.end.x, l.end.y))
        }

    /** Loads a new photo and resets everything downstream of it — a new picture is a new plane. */
    fun loadPhoto(bitmap: Bitmap) {
        photo = bitmap
        quad = emptyList()
        homography = null
        line = null
    }

    /**
     * Drops a quad near [tapPoint] — nothing is shown until the user taps roughly where the
     * reference object is in the photo, matching ARuler's own flow ("Nhấp vào ... để đánh dấu
     * nó"): a quad that just appears pre-placed on every fresh photo would sit somewhere
     * arbitrary far more often than not, since the reference object could be anywhere in frame.
     * No-op once a quad already exists — this only ever creates the *first* one.
     *
     * First tries [autoFitQuad] (Canny edge detection + Hough line transform) around the tap —
     * the classical-CV, no-ML answer to ARuler's FastSAM-based auto-fit: it can't segment an
     * arbitrary object, but a reference object is always a plain rectangle, and that's exactly
     * what Canny+Hough are good at outlining. Falls back to a plain centred box on anything it
     * can't find 4 confident edges for (glare, low contrast, background clutter) — the user can
     * always drag the corners from there, same as before this existed.
     */
    suspend fun revealQuadAt(tapPoint: Offset, canvasWidthPx: Float, canvasHeightPx: Float) {
        if (quad.isNotEmpty()) return
        val bitmap = photo

        if (bitmap != null) {
            val fit = aspectFit(bitmap.width.toFloat(), bitmap.height.toFloat(), canvasWidthPx, canvasHeightPx)
            val tapInBitmap = Vec2(
                (tapPoint.x - fit.offsetX) / fit.width * bitmap.width,
                (tapPoint.y - fit.offsetY) / fit.height * bitmap.height,
            )
            isDetectingQuad = true
            val detected = try {
                withContext(Dispatchers.Default) { autoFitQuad(bitmap, tapInBitmap) }
            } finally {
                isDetectingQuad = false
            }
            if (detected != null && quad.isEmpty()) {
                quad = detected.map { corner ->
                    Offset(
                        fit.offsetX + corner.x / bitmap.width * fit.width,
                        fit.offsetY + corner.y / bitmap.height * fit.height,
                    )
                }
                return
            }
        }

        if (quad.isNotEmpty()) return // a drag or a second tap could have raced ahead while detecting
        val halfWidth = canvasWidthPx * 0.22f
        val halfHeight = canvasHeightPx * 0.14f
        quad = listOf(
            Offset(tapPoint.x - halfWidth, tapPoint.y - halfHeight),
            Offset(tapPoint.x + halfWidth, tapPoint.y - halfHeight),
            Offset(tapPoint.x + halfWidth, tapPoint.y + halfHeight),
            Offset(tapPoint.x - halfWidth, tapPoint.y + halfHeight),
        )
    }

    /** Dragging a corner invalidates any prior calibration — it must be confirmed again. */
    fun moveQuadCorner(index: Int, position: Offset) {
        if (index !in quad.indices) return
        quad = quad.toMutableList().also { it[index] = position }
        homography = null
    }

    /**
     * Solves the homography from the current quad to [reference]'s real-world rectangle, and —
     * unlike the quad, which needs a tap first — places the measuring line straight away,
     * centred on screen: ARuler's own tool starts with a line already there to drag, not an
     * empty canvas waiting for a first tap.
     */
    fun confirmReference(canvasWidthPx: Float, canvasHeightPx: Float) {
        if (quad.size != 4) return
        val long = reference.longSideMm
        val short = reference.shortSideMm
        val dst = listOf(
            Vec2(0f, 0f),
            Vec2(long, 0f),
            Vec2(long, short),
            Vec2(0f, short),
        )
        homography = computeHomography(quad.map { Vec2(it.x, it.y) }, dst) ?: return
        resetLine(canvasWidthPx, canvasHeightPx)
    }

    /** Re-centres the measuring line — the "start over" action once it's been dragged somewhere unhelpful. */
    fun resetLine(canvasWidthPx: Float, canvasHeightPx: Float) {
        val halfSpan = canvasWidthPx * 0.25f
        val midY = canvasHeightPx / 2f
        val midX = canvasWidthPx / 2f
        line = LiveLine(Offset(midX - halfSpan, midY), Offset(midX + halfSpan, midY))
    }

    /** Drags one endpoint. `isStart` picks which — there are only ever these two handles. */
    fun moveLineEndpoint(isStart: Boolean, position: Offset) {
        val current = line ?: return
        line = if (isStart) current.copy(start = position) else current.copy(end = position)
    }

    fun toggleUnit() {
        unit = if (unit == LengthUnit.Metric) LengthUnit.Imperial else LengthUnit.Metric
    }
}
