package vn.apero.armeasure.photo.presentation

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
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
import vn.apero.armeasure.photo.domain.imaging.toBitmapSpace
import vn.apero.armeasure.photo.domain.imaging.toDisplaySpace

/** Two draggable endpoints in display-space pixels — used for SCR-24's in-progress segment. */
internal data class LiveLine(val start: Offset, val end: Offset)

/**
 * One committed measuring segment, drawn on SCR-23 (design `jwRjx`'s photo) once the user has
 * confirmed it on SCR-24 (`kYLQt`). Coordinates are SCR-23's own display-space pixels — the same
 * space [quad]/[homography] already live in — never SCR-24's, whose canvas is a different size;
 * see [PhotoMeasureState.remapToCanvas] for the conversion a segment goes through exactly once, at
 * commit time. Immutable by design (locked decision: committed segments cannot be edited, only
 * deleted via [PhotoMeasureState.deleteSegment] or undone).
 */
internal data class Segment(val start: Offset, val end: Offset, val color: Color)

internal fun Offset.toVec2() = Vec2(x, y)
internal fun Vec2.toOffset() = Offset(x, y)

/**
 * A whole-state snapshot for [PhotoMeasureState] undo/redo — simpler and more honest than
 * per-field undo, and it covers everything a mutating gesture can change. Deliberately excludes
 * the photo [Bitmap] itself: snapshotting bitmaps would multiply memory by the undo depth and
 * could OOM on a large photo, and `loadPhoto` is the only gesture that changes it anyway. Also
 * excludes SCR-24's in-progress draft — undo is scoped to *committed* segments only (locked
 * decision), never a still-being-dragged one that hasn't been confirmed yet.
 */
internal data class PhotoSnapshot(
    val quad: List<Offset>,
    val homography: Homography?,
    val segments: List<Segment>,
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

    /** Every segment the user has confirmed so far (SCR-24's ✓), drawn together on SCR-23. */
    var segments by mutableStateOf<List<Segment>>(emptyList())
        private set

    /** True while SCR-24 ("AR Adjust", design `kYLQt`) is showing in place of SCR-23. */
    var isDrawingSegment by mutableStateOf(false)
        private set

    /** SCR-24's in-progress segment, in SCR-24's OWN canvas-space pixels. Null until [placeDraftInitial] runs, and always null again once [isDrawingSegment] goes false. */
    var draftLine by mutableStateOf<LiveLine?>(null)
        private set

    /** Always reset to the palette's first (red) entry every time SCR-24 opens — locked decision: a new segment never inherits the previously used colour. */
    var draftColor by mutableStateOf(PhotoLineColors.first())
        private set

    var unit by mutableStateOf(initialUnit)

    /** True while [revealQuadAt] is running the Canny+Hough auto-fit — a few hundred ms of real work. */
    var isDetectingQuad by mutableStateOf(false)
        private set

    /**
     * True once the user has asked to re-open the quad editor after already calibrating once —
     * "Chỉnh sửa tỉ lệ" in SCR-23's bottom toolbar (see [beginEditQuad]). Set back to `false` by
     * [confirmReference]. The quad and segments survive the round trip untouched: dragging a
     * corner only clears [homography] (via [moveQuadCorner]), never [segments].
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

    /** Captured the moment a quad-corner drag begins and committed only once it ends, so undo reverts a whole drag rather than each intermediate frame. Null between gestures. */
    private var dragStartSnapshot: PhotoSnapshot? = null

    private fun snapshotNow() = PhotoSnapshot(quad, homography, segments)

    private fun applySnapshot(snapshot: PhotoSnapshot) {
        quad = snapshot.quad
        homography = snapshot.homography
        segments = snapshot.segments
    }

    /** Undoes the last committed gesture (a quad edit, a segment commit, or a segment delete), restoring the exact previous quad/homography/segment list. */
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

    /** Marks the end of a quad-corner drag gesture — commits the pre-drag snapshot for undo. No-op if no drag was in progress (e.g. a tap that never moved). */
    fun commitDrag() {
        dragStartSnapshot?.let { undoRedo.push(it) }
        dragStartSnapshot = null
    }

    private fun beginDragIfNeeded() {
        if (dragStartSnapshot == null) dragStartSnapshot = snapshotNow()
    }

    /** [segment]'s real-world length, using the homography already solved for SCR-23's own canvas-space — every committed segment lives in that same space, so no remapping is needed here (only [remapToCanvas], at commit time, needs one). Null before calibration. */
    fun distanceMmFor(segment: Segment): Float? {
        val h = homography ?: return null
        return measureRealDistanceMm(h, segment.start.toVec2(), segment.end.toVec2())
    }

    /**
     * SCR-24's in-progress segment's live real-world length. [draftLine] lives in SCR-24's own
     * canvas-space, which is a *different* aspect-fit box than the one [homography] was solved
     * against (SCR-23's) — so unlike [distanceMmFor], this must remap through [remapToCanvas]
     * first. [photoWidthPx]/[photoHeightPx] are the loaded photo's own intrinsic pixel size —
     * passed in rather than read off a stored `Bitmap` so this stays pure Float geometry, testable
     * with no `Bitmap` in play at all (only its two dimensions matter to the maths). Null before
     * calibration or before [placeDraftInitial] runs.
     */
    fun draftDistanceMm(photoWidthPx: Float, photoHeightPx: Float, draftCanvasSize: IntSize, targetCanvasSize: IntSize): Float? {
        val h = homography ?: return null
        val draft = draftLine ?: return null
        val start = remapToCanvas(draft.start, photoWidthPx, photoHeightPx, draftCanvasSize, targetCanvasSize)
        val end = remapToCanvas(draft.end, photoWidthPx, photoHeightPx, draftCanvasSize, targetCanvasSize)
        return measureRealDistanceMm(h, start.toVec2(), end.toVec2())
    }

    /**
     * Re-expresses [point] from a [fromCanvas]-sized aspect-fit box into the equivalent point in a
     * [toCanvas]-sized one, via the photo's own [photoWidthPx]x[photoHeightPx] pixel grid (see
     * `ImageFit.toBitmapSpace`/`toDisplaySpace`) — the bridge SCR-24's draft and SCR-23's committed
     * segments need since the two screens' photo boxes are different sizes but show the same photo.
     */
    private fun remapToCanvas(point: Offset, photoWidthPx: Float, photoHeightPx: Float, fromCanvas: IntSize, toCanvas: IntSize): Offset {
        val bitmapPoint = toBitmapSpace(point.toVec2(), photoWidthPx, photoHeightPx, fromCanvas.width.toFloat(), fromCanvas.height.toFloat())
        return toDisplaySpace(bitmapPoint, photoWidthPx, photoHeightPx, toCanvas.width.toFloat(), toCanvas.height.toFloat()).toOffset()
    }

    /** Loads a new photo and resets everything downstream of it — a new picture is a new plane. */
    fun loadPhoto(bitmap: Bitmap) {
        undoRedo.push(snapshotNow())
        photo = bitmap
        quad = emptyList()
        homography = null
        segments = emptyList()
        isEditingQuad = false
        isDrawingSegment = false
        draftLine = null
    }

    /**
     * [PhotoMeasureScreen]'s "back" affordance once a photo is loaded: returns to the pick-photo
     * step without discarding the chosen [reference]. Undo history is cleared with it — an undo
     * across two different photos would restore quad/segment coordinates that belong to a bitmap
     * no longer loaded, which is meaningless.
     */
    fun discardPhoto() {
        photo = null
        quad = emptyList()
        homography = null
        segments = emptyList()
        isEditingQuad = false
        isDrawingSegment = false
        draftLine = null
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
     * Solves the homography from the current quad to [reference]'s real-world rectangle. Never
     * creates a segment itself (unlike the old single-line flow) — SCR-23 shows no line-drawing UI
     * until the user explicitly taps "Đoạn thẳng", per the target flow.
     */
    fun confirmReference() {
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
    }

    /**
     * "Chỉnh sửa tỉ lệ" (Edit scale): re-opens the quad editor without discarding the photo or the
     * committed segments — see the class doc's note on why this was architecturally blocked before
     * this flag existed. No-op before the first calibration, since there is nothing yet to re-edit.
     */
    fun beginEditQuad() {
        if (!isCalibrated) return
        isEditingQuad = true
    }

    /** "Đoạn thẳng" on SCR-23: opens SCR-24. [placeDraftInitial] pre-places the two endpoints once SCR-24's own canvas is measured (its size isn't known yet at the moment this is called). No-op before calibration — there is nothing to measure against yet. */
    fun beginDrawSegment() {
        if (!isCalibrated) return
        draftColor = PhotoLineColors.first()
        draftLine = null
        isDrawingSegment = true
    }

    /** Pre-places SCR-24's two endpoints near the centre of its own (freshly measured) canvas — locked decision: no tap-to-place step. No-op once already placed. */
    fun placeDraftInitial(canvasWidthPx: Float, canvasHeightPx: Float) {
        if (draftLine != null) return
        val halfSpan = canvasWidthPx * 0.2f
        val midX = canvasWidthPx / 2f
        val midY = canvasHeightPx / 2f
        draftLine = LiveLine(Offset(midX - halfSpan, midY), Offset(midX + halfSpan, midY))
    }

    /** Drags one of SCR-24's two endpoints. `isStart` picks which. */
    fun moveDraftEndpoint(isStart: Boolean, position: Offset) {
        val current = draftLine ?: return
        draftLine = if (isStart) current.copy(start = position) else current.copy(end = position)
    }

    /**
     * SCR-24's colour bar — scoped to the in-progress segment only, never the previously
     * committed ones. `@JvmName` avoids a JVM signature clash with the `var draftColor` property's
     * own auto-generated bean setter, same reasoning as [setUnit].
     */
    @JvmName("setDraftColorTo")
    fun setDraftColor(color: Color) {
        draftColor = color
    }

    /**
     * SCR-24's ✓: converts [draftLine] out of its own canvas-space and into SCR-23's (see
     * [remapToCanvas]) and commits it as a new, undoable [Segment]. No-op without a placed draft.
     * [targetCanvasSize] is SCR-23's own last-measured canvas size, supplied by the caller since
     * SCR-24 has no way to measure a screen it isn't showing; [photoWidthPx]/[photoHeightPx] are
     * the loaded photo's intrinsic size (see [draftDistanceMm] for why this takes dimensions
     * rather than a `Bitmap`).
     */
    fun commitDrawnSegment(photoWidthPx: Float, photoHeightPx: Float, draftCanvasSize: IntSize, targetCanvasSize: IntSize) {
        val draft = draftLine ?: return
        val start = remapToCanvas(draft.start, photoWidthPx, photoHeightPx, draftCanvasSize, targetCanvasSize)
        val end = remapToCanvas(draft.end, photoWidthPx, photoHeightPx, draftCanvasSize, targetCanvasSize)
        undoRedo.push(snapshotNow())
        segments = segments + Segment(start, end, draftColor)
        draftLine = null
        isDrawingSegment = false
    }

    /** SCR-24's X: discards the in-progress segment and returns to SCR-23. Every already-committed segment is untouched — this never touches [segments] or the undo history. */
    fun cancelDrawSegment() {
        draftLine = null
        isDrawingSegment = false
    }

    /** The trash affordance inside a committed segment's label (SCR-23) — deletes just that one. Pushes its own undo entry, same convention as [moveQuadCorner]/[commitDrawnSegment]. */
    fun deleteSegment(index: Int) {
        if (index !in segments.indices) return
        undoRedo.push(snapshotNow())
        segments = segments.toMutableList().also { it.removeAt(index) }
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
