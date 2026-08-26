package vn.apero.armeasure.photo.presentation

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.UndoRedoStack
import vn.apero.armeasure.photo.data.autoFitQuad
import vn.apero.armeasure.photo.domain.imaging.Homography
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.domain.imaging.aspectFit
import vn.apero.armeasure.photo.domain.imaging.builtInReferenceObjects
import vn.apero.armeasure.photo.domain.imaging.computeHomography
import vn.apero.armeasure.photo.domain.imaging.measureRealDistanceMm

/** The measuring line's two endpoints, in display-space pixels — both user-draggable. */
internal data class LiveLine(val start: Offset, val end: Offset)

/**
 * A whole-state snapshot for [PhotoMeasureState] undo/redo — simpler and more honest than
 * per-field undo, and it covers everything a mutating gesture can change. Deliberately excludes
 * the photo [Bitmap] itself: snapshotting bitmaps would multiply memory by the undo depth and
 * could OOM on a large photo, and `loadPhoto` is the only gesture that changes it anyway.
 */
internal data class PhotoSnapshot(
    val quad: List<Offset>,
    val homography: Homography?,
    val line: LiveLine?,
    val lineColor: Color,
)

/**
 * Mutable UI state for the photo-reference measure screen.
 *
 * A plain state holder rather than a ViewModel — same reasoning as `MeasureState`: nothing
 * here needs to survive process death, and a half-finished calibration is not worth restoring.
 */
internal class PhotoMeasureState(initialUnit: LengthUnit = LengthUnit.Cm) {

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

    var unit by mutableStateOf(initialUnit)

    /** True while [revealQuadAt] is running the Canny+Hough auto-fit — a few hundred ms of real work. */
    var isDetectingQuad by mutableStateOf(false)
        private set

    /**
     * The measuring line + its label's fill colour (design `ColorPickerBar`, decision: colour
     * choice is scoped to the photo line only, never [QuadEditorCanvas]'s semantic cyan/yellow).
     * Seeded from the palette's own default entry.
     */
    var lineColor by mutableStateOf(PhotoLineColors.first())
        private set

    /**
     * True once the user has asked to re-open the quad editor after already calibrating once —
     * "Chỉnh sửa tỉ lệ" in SCR-23's bottom toolbar (see [beginEditQuad]). Set back to `false` by
     * [confirmReference]. The quad and line survive the round trip untouched: dragging a corner
     * only clears [homography] (via [moveQuadCorner]), never [line] — see the class doc's own
     * note on why this was architecturally dead code before this flag existed.
     */
    var isEditingQuad by mutableStateOf(false)
        private set

    val isCalibrated: Boolean get() = homography != null

    /**
     * Pure-value history, unlike the ARCore-anchor-holding stacks in `MeasureState`/
     * `ShapeMeasureState` — there is nothing to release, so [onEvict] stays the default no-op.
     */
    private val undoRedo = UndoRedoStack<PhotoSnapshot>()
    val canUndo: Boolean get() = undoRedo.canUndo
    val canRedo: Boolean get() = undoRedo.canRedo

    /** Captured the moment a corner/endpoint drag begins and committed only once it ends, so undo reverts a whole drag rather than each intermediate frame — see the risk this guards against in the phase's own notes. Null between gestures. */
    private var dragStartSnapshot: PhotoSnapshot? = null

    private fun snapshotNow() = PhotoSnapshot(quad, homography, line, lineColor)

    private fun applySnapshot(snapshot: PhotoSnapshot) {
        quad = snapshot.quad
        homography = snapshot.homography
        line = snapshot.line
        lineColor = snapshot.lineColor
    }

    /** Undoes the last committed gesture, restoring the exact previous quad/homography/line. */
    fun undo() {
        val previous = undoRedo.popUndo() ?: return
        undoRedo.pushRedo(snapshotNow())
        applySnapshot(previous)
    }

    /** Redoes the gesture [undo] last reverted. */
    fun redo() {
        val next = undoRedo.popRedo() ?: return
        undoRedo.pushUndo(snapshotNow())
        applySnapshot(next)
    }

    /** Marks the end of a corner/endpoint drag gesture — commits the pre-drag snapshot for undo. No-op if no drag was in progress (e.g. a tap that never moved). */
    fun commitDrag() {
        dragStartSnapshot?.let { undoRedo.push(it) }
        dragStartSnapshot = null
    }

    private fun beginDragIfNeeded() {
        if (dragStartSnapshot == null) dragStartSnapshot = snapshotNow()
    }

    /** The live line's real-world length, or null before calibration/placement. */
    val currentDistanceMm: Float?
        get() {
            val h = homography ?: return null
            val l = line ?: return null
            return measureRealDistanceMm(h, Vec2(l.start.x, l.start.y), Vec2(l.end.x, l.end.y))
        }

    /** Loads a new photo and resets everything downstream of it — a new picture is a new plane. */
    fun loadPhoto(bitmap: Bitmap) {
        undoRedo.push(snapshotNow())
        photo = bitmap
        quad = emptyList()
        homography = null
        line = null
        isEditingQuad = false
    }

    /**
     * [PhotoMeasureScreen]'s "back" affordance once a photo is loaded: returns to the pick-photo
     * step without discarding the chosen [reference]. Undo history is cleared with it — an undo
     * across two different photos would restore quad/line coordinates that belong to a bitmap no
     * longer loaded, which is meaningless.
     */
    fun discardPhoto() {
        photo = null
        quad = emptyList()
        homography = null
        line = null
        isEditingQuad = false
        undoRedo.clear()
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
        beginDragIfNeeded()
        quad = quad.toMutableList().also { it[index] = position }
        homography = null
    }

    /**
     * Solves the homography from the current quad to [reference]'s real-world rectangle.
     *
     * First-time confirm (no [line] yet): places the measuring line straight away, centred on
     * screen — ARuler's own tool starts with a line already there to drag, not an empty canvas
     * waiting for a first tap. Re-confirm after [beginEditQuad] (line already exists): the line's
     * on-screen pixel position is left untouched — the photo and its aspect-fit letterboxing
     * haven't moved, only the calibration quad has, so nothing needs recomputing (see the class
     * doc's own note on why "Chỉnh sửa tỉ lệ" doesn't need to touch it).
     */
    fun confirmReference(canvasWidthPx: Float, canvasHeightPx: Float) {
        if (quad.size != 4) return
        undoRedo.push(snapshotNow())
        val long = reference.longSideMm
        val short = reference.shortSideMm
        val dst = listOf(
            Vec2(0f, 0f),
            Vec2(long, 0f),
            Vec2(long, short),
            Vec2(0f, short),
        )
        homography = computeHomography(quad.map { Vec2(it.x, it.y) }, dst) ?: return
        isEditingQuad = false
        if (line == null) resetLine(canvasWidthPx, canvasHeightPx)
    }

    /**
     * "Chỉnh sửa tỉ lệ" (Edit scale): re-opens the quad editor without discarding the photo or the
     * line — see the class doc's note on why this was architecturally blocked before this flag
     * existed. No-op before the first calibration, since there is nothing yet to re-edit.
     */
    fun beginEditQuad() {
        if (!isCalibrated) return
        isEditingQuad = true
    }

    /**
     * A hard user choice like [setUnit] — pushes its own undo entry so a colour change is
     * separately undoable/redoable (`ColorPickerBar`'s on-device check). `@JvmName` for the same
     * reason as [setUnit]: avoids a JVM signature clash with the `var lineColor` property's own
     * auto-generated bean setter.
     */
    @JvmName("setLineColorTo")
    fun setLineColor(newColor: Color) {
        if (newColor == lineColor) return
        undoRedo.push(snapshotNow())
        lineColor = newColor
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
        beginDragIfNeeded()
        line = if (isStart) current.copy(start = position) else current.copy(end = position)
    }

    /**
     * Replaces the display unit outright — a hard user choice, not a cycle through a fixed
     * order. `@JvmName` avoids a JVM signature clash with the `var unit` property's own
     * auto-generated bean setter (also `setUnit` at the bytecode level); the Kotlin-visible name
     * stays `setUnit`.
     */
    @JvmName("setUnitTo")
    fun setUnit(newUnit: LengthUnit) {
        unit = newUnit
    }
}
