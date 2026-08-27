package vn.apero.armeasure.photo.presentation

import androidx.compose.ui.graphics.Color
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.domain.imaging.computeHomography
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.State
import vn.apero.armeasure.photo.presentation.components.PhotoLineColors

/**
 * Every state transition on the photo-measure screen, as pure `State -> State` functions.
 *
 * Split out of [PhotoMeasureViewModel] for two reasons. It keeps the ViewModel down to dispatch plus
 * the three things that genuinely are not pure — bitmap decoding results, `CustomReferenceStore` IO
 * and the Canny/segmentation detector. And it keeps this screen's logic testable on the plain JVM:
 * these functions need no `Dispatchers.Main`, no `SavedStateHandle` and no `android.graphics.Bitmap`,
 * which matters because `PhotoMeasureSegmentsTest` is this screen's only automated coverage and the
 * module has neither Robolectric nor `kotlinx-coroutines-test`.
 *
 * Undo/redo is part of the state rather than a side-holder: with `State` immutable, a snapshot is
 * just a value, so two lists and one nullable field replace the mutable `UndoRedoStack` this screen
 * used to own — and every undo transition becomes a pure function like the rest.
 */

/** Same bound as `UndoRedoStack`'s default; a photo session's history is small and bounded on purpose. */
private const val MaxUndoDepth = 20

private fun State.snapshotNow() = PhotoSnapshot(quad, homography, segments)

/** Records the current snapshot as the newest undo entry, discarding any pending redo — the standard "a new action invalidates the redo branch" rule. */
private fun State.pushUndo(): State = copy(
    undoStack = (undoStack + snapshotNow()).takeLast(MaxUndoDepth),
    redoStack = emptyList(),
)

private fun State.applying(snapshot: PhotoSnapshot): State = copy(
    quad = snapshot.quad,
    homography = snapshot.homography,
    segments = snapshot.segments,
)

/** Undoes the last committed gesture (a quad edit, a segment commit, a segment delete), restoring the exact previous quad/homography/segment list. */
internal fun State.undo(): State {
    val previous = undoStack.lastOrNull() ?: return this
    val current = snapshotNow()
    return applying(previous).copy(
        undoStack = undoStack.dropLast(1),
        redoStack = (redoStack + current).takeLast(MaxUndoDepth),
    )
}

/** Redoes the gesture [undo] last reverted. */
internal fun State.redo(): State {
    val next = redoStack.lastOrNull() ?: return this
    val current = snapshotNow()
    return applying(next).copy(
        redoStack = redoStack.dropLast(1),
        undoStack = (undoStack + current).takeLast(MaxUndoDepth),
    )
}

// ---------------------------------------------------------------- reference selection

/** SCR-15: picking a card advances straight to the photo picker, exactly as before. */
internal fun State.selectReference(reference: ReferenceObject): State =
    copy(chosenReferenceId = reference.id, showPickPhotoSheet = true)

/** "Đổi vật tham chiếu": back to SCR-15. */
internal fun State.changeReference(): State = copy(chosenReferenceId = null)

internal fun State.requestAddReference(): State = copy(editingReferenceId = null, showReferenceSheet = true)

internal fun State.requestEditReference(reference: ReferenceObject): State =
    copy(editingReferenceId = reference.id, showReferenceSheet = true)

internal fun State.dismissReferenceSheet(): State = copy(showReferenceSheet = false)

internal fun State.requestPickPhoto(): State = copy(showPickPhotoSheet = true)

internal fun State.dismissPickPhotoSheet(): State = copy(showPickPhotoSheet = false)

/**
 * Applies the asynchronous `CustomReferenceStore` load.
 *
 * Also the one place a stale [State.chosenReferenceId] is normalised away: once the list is known,
 * an id that resolves to nothing cannot be a not-loaded-yet custom object any more, so the only
 * honest reading of it is "gone", and the flow returns to the picker rather than sitting on a
 * screen with no reference. Unreachable in practice today (deleting is only offered on SCR-15,
 * where nothing is chosen) — it exists so [State.reference] can stay fallback-free.
 */
internal fun State.customReferencesLoaded(loaded: List<ReferenceObject>): State {
    val next = copy(customReferences = loaded, customReferencesLoaded = true)
    return if (next.chosenReferenceId != null && next.reference == null) next.copy(chosenReferenceId = null) else next
}

internal fun State.referenceAdded(reference: ReferenceObject): State =
    copy(customReferences = customReferences + reference)

/** No re-pointing of the chosen reference needed: [State.reference] is derived, so an edit to the object currently in use is visible the moment the list changes. */
internal fun State.referenceUpdated(reference: ReferenceObject): State =
    copy(customReferences = customReferences.map { if (it.id == reference.id) reference else it })

internal fun State.referenceDeleted(id: String): State =
    customReferencesLoaded(customReferences.filterNot { it.id == id })

// ---------------------------------------------------------------- photo

/** A new picture is a new plane: everything downstream of the photo resets. */
internal fun State.photoPicked(): State = pushUndo().copy(
    quad = emptyList(),
    homography = null,
    segments = emptyList(),
    isEditingQuad = false,
    isDrawingSegment = false,
    draftLine = null,
    showPickPhotoSheet = false,
)

/**
 * The "back" affordance once a photo is loaded: returns to the pick-photo step without discarding
 * the chosen reference. Undo history goes with it — an undo across two different photos would
 * restore coordinates belonging to a bitmap no longer loaded.
 */
internal fun State.discardPhoto(): State = copy(
    quad = emptyList(),
    homography = null,
    segments = emptyList(),
    isEditingQuad = false,
    isDrawingSegment = false,
    draftLine = null,
    undoStack = emptyList(),
    redoStack = emptyList(),
    dragStartSnapshot = null,
)

// ---------------------------------------------------------------- quad

/** The detectors work in, and return, the bitmap's own pixel grid — the space this quad is stored in — so their output is taken verbatim. */
internal fun State.quadDetected(detected: List<Vec2>): State =
    if (quad.isNotEmpty()) this else copy(quad = detected)

/**
 * The plain centred box used when no detector found four confident edges (glare, low contrast,
 * clutter) — the user drags the corners from there. Fractions are of the *photo*, not of a canvas,
 * so the box lands on the same part of the picture whatever size the box drawing it happens to be.
 */
internal fun State.fallbackQuadAt(tap: Vec2, photoWidthPx: Float, photoHeightPx: Float): State {
    if (quad.isNotEmpty()) return this // a drag or a second tap could have raced ahead while detecting
    val halfWidth = photoWidthPx * 0.22f
    val halfHeight = photoHeightPx * 0.14f
    return copy(
        quad = listOf(
            Vec2(tap.x - halfWidth, tap.y - halfHeight),
            Vec2(tap.x + halfWidth, tap.y - halfHeight),
            Vec2(tap.x + halfWidth, tap.y + halfHeight),
            Vec2(tap.x - halfWidth, tap.y + halfHeight),
        ),
    )
}

/** Dragging a corner invalidates any prior calibration — it must be confirmed again. */
internal fun State.moveQuadCorner(index: Int, position: Vec2): State {
    if (index !in quad.indices) return this
    return copy(
        dragStartSnapshot = dragStartSnapshot ?: snapshotNow(),
        quad = quad.toMutableList().also { it[index] = position },
        homography = null,
    )
}

/** End of a quad-corner drag: commits the pre-drag snapshot, so undo reverts the whole drag rather than each intermediate frame. No-op for a tap that never moved. */
internal fun State.commitDrag(): State {
    val start = dragStartSnapshot ?: return this
    return copy(
        undoStack = (undoStack + start).takeLast(MaxUndoDepth),
        redoStack = emptyList(),
        dragStartSnapshot = null,
    )
}

/**
 * Solves the homography from the current quad to the chosen reference's real rectangle. Never
 * creates a segment itself — SCR-23 shows no line-drawing UI until the user taps "Đoạn thẳng".
 * No-op on a degenerate quad, and no-op while [State.reference] is still unresolved, which is what
 * replaces the old silent "calibrate against A4" fallback.
 */
internal fun State.confirmReference(): State {
    if (quad.size != 4) return this
    val target = reference ?: return this
    val destination = listOf(
        Vec2(0f, 0f),
        Vec2(target.longSideMm, 0f),
        Vec2(target.longSideMm, target.shortSideMm),
        Vec2(0f, target.shortSideMm),
    )
    val solved = computeHomography(quad, destination) ?: return pushUndo()
    return pushUndo().copy(homography = solved, isEditingQuad = false)
}

/** "Chỉnh sửa tỉ lệ": re-opens the quad editor without discarding the photo or the committed segments. No-op before the first calibration. */
internal fun State.beginEditQuad(): State = if (isCalibrated) copy(isEditingQuad = true) else this

// ---------------------------------------------------------------- segments

/** "Đoạn thẳng" on SCR-23: opens SCR-24, always with the palette's first (red) colour — a new segment never inherits the previously used one (locked decision). */
internal fun State.beginDrawSegment(): State =
    if (isCalibrated) copy(draftColor = PhotoLineColors.first(), draftLine = null, isDrawingSegment = true) else this

/** Pre-places SCR-24's two endpoints horizontally across the middle of the photo (locked decision: no tap-to-place step). No-op once already placed. */
internal fun State.placeDraftInitial(photoWidthPx: Float, photoHeightPx: Float): State {
    if (draftLine != null) return this
    val halfSpan = photoWidthPx * 0.2f
    return copy(draftLine = LiveLine(Vec2(photoWidthPx / 2f - halfSpan, photoHeightPx / 2f), Vec2(photoWidthPx / 2f + halfSpan, photoHeightPx / 2f)))
}

internal fun State.moveDraftEndpoint(isStart: Boolean, position: Vec2): State {
    val current = draftLine ?: return this
    return copy(draftLine = if (isStart) current.copy(start = position) else current.copy(end = position))
}

internal fun State.setDraftColor(color: Color): State = copy(draftColor = color)

/** SCR-24's ✓. Nothing is converted — the draft is already in the photo's own pixel grid, which is exactly what a committed segment is stored in. */
internal fun State.commitDrawnSegment(): State {
    val draft = draftLine ?: return this
    return pushUndo().copy(
        segments = segments + Segment(draft.start, draft.end, draftColor),
        draftLine = null,
        isDrawingSegment = false,
    )
}

/** SCR-24's X: discards the in-progress segment. Committed segments and the undo history are untouched. */
internal fun State.cancelDrawSegment(): State = copy(draftLine = null, isDrawingSegment = false)

/** The trash affordance inside a committed segment's label. Pushes its own undo entry, same convention as a corner drag or a commit. */
internal fun State.deleteSegment(index: Int): State {
    if (index !in segments.indices) return this
    return pushUndo().copy(segments = segments.toMutableList().also { it.removeAt(index) })
}

internal fun State.setUnit(unit: LengthUnit): State = copy(unit = unit)
