package vn.quancua.artapemeasure.photomeasure

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import vn.quancua.artapemeasure.measure.LengthUnit

/** One committed measurement on the photo, with its distance already resolved. */
data class MeasuredLine(val start: Offset, val end: Offset, val distanceMm: Float)

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

    val lines = mutableStateListOf<MeasuredLine>()

    /** First tap of a line in progress; the next tap completes it. */
    var pendingStart by mutableStateOf<Offset?>(null)
        private set

    var unit by mutableStateOf(LengthUnit.Metric)

    val isCalibrated: Boolean get() = homography != null
    val canUndo: Boolean get() = pendingStart != null || lines.isNotEmpty()

    /** Loads a new photo and resets everything downstream of it — a new picture is a new plane. */
    fun loadPhoto(bitmap: Bitmap) {
        photo = bitmap
        quad = emptyList()
        homography = null
        lines.clear()
        pendingStart = null
    }

    /**
     * Places the default calibration quad once the display size is known — a fresh photo has
     * no quad yet because until `PhotoQuadCanvas` is laid out, "display pixels" (the coordinate
     * space every point in this class lives in) has no meaning. No-op once a quad already
     * exists, so this is safe to call on every recomposition.
     */
    fun ensureQuad(canvasWidthPx: Float, canvasHeightPx: Float) {
        if (quad.isNotEmpty()) return
        val bitmap = photo ?: return
        val fit = aspectFit(bitmap.width.toFloat(), bitmap.height.toFloat(), canvasWidthPx, canvasHeightPx)
        val insetX = fit.width * 0.2f
        val insetY = fit.height * 0.25f
        quad = listOf(
            Offset(fit.offsetX + insetX, fit.offsetY + insetY),
            Offset(fit.offsetX + fit.width - insetX, fit.offsetY + insetY),
            Offset(fit.offsetX + fit.width - insetX, fit.offsetY + fit.height - insetY),
            Offset(fit.offsetX + insetX, fit.offsetY + fit.height - insetY),
        )
    }

    /** Dragging a corner invalidates any prior calibration — it must be confirmed again. */
    fun moveQuadCorner(index: Int, position: Offset) {
        if (index !in quad.indices) return
        quad = quad.toMutableList().also { it[index] = position }
        homography = null
    }

    /**
     * Solves the homography from the current quad to [reference]'s real-world rectangle.
     *
     * The quad's top edge (corners 0-1) and bottom edge (2-3) are always treated as the *long*
     * side, left/right (3-0, 1-2) as the *short* side — fixed by convention, not measured from
     * on-screen pixel lengths, because those lengths are exactly what perspective distorts.
     * The UI is responsible for telling the user which edge is which before they drag.
     */
    fun confirmReference() {
        if (quad.size != 4) return
        val long = reference.longSideMm
        val short = reference.shortSideMm
        val dst = listOf(
            Vec2(0f, 0f),
            Vec2(long, 0f),
            Vec2(long, short),
            Vec2(0f, short),
        )
        homography = computeHomography(quad.map { Vec2(it.x, it.y) }, dst)
    }

    /** Places or completes a measurement point. No-op before [confirmReference] has succeeded. */
    fun onTap(point: Offset) {
        val h = homography ?: return
        val start = pendingStart
        if (start == null) {
            pendingStart = point
            return
        }
        val distanceMm = measureRealDistanceMm(h, Vec2(start.x, start.y), Vec2(point.x, point.y))
        lines.add(MeasuredLine(start, point, distanceMm))
        pendingStart = null
    }

    fun undo() {
        if (pendingStart != null) {
            pendingStart = null
            return
        }
        lines.removeLastOrNull()
    }

    fun clear() {
        lines.clear()
        pendingStart = null
    }

    fun toggleUnit() {
        unit = if (unit == LengthUnit.Metric) LengthUnit.Imperial else LengthUnit.Metric
    }
}
