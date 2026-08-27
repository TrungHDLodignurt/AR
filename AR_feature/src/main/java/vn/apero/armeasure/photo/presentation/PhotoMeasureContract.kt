package vn.apero.armeasure.photo.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import vn.apero.armeasure.common.data.DefaultUnit
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.common.presentation.mvi.MviEffect
import vn.apero.armeasure.common.presentation.mvi.MviIntent
import vn.apero.armeasure.common.presentation.mvi.MviState
import vn.apero.armeasure.photo.domain.imaging.Homography
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.domain.imaging.builtInReferenceObjects
import vn.apero.armeasure.photo.domain.imaging.measureRealDistanceMm
import vn.apero.armeasure.photo.presentation.components.PhotoLineColors

/**
 * Two draggable endpoints of SCR-24's in-progress segment, in the photo's OWN pixel grid — see
 * [PhotoMeasureContract.State] for why every stored coordinate lives there. [Vec2] rather than
 * `Offset` on purpose: the type itself says "not a screen coordinate".
 */
internal data class LiveLine(val start: Vec2, val end: Vec2)

/**
 * One committed measuring segment, drawn on SCR-23 (design `jwRjx`'s photo) once the user has
 * confirmed it on SCR-24 (`kYLQt`). Coordinates are the photo's own bitmap pixels — the same space
 * the quad and the homography live in — so a segment needs no conversion when it moves between
 * SCR-24's canvas and SCR-23's differently-sized one, and none when it is drawn into the exported
 * PNG at the photo's full resolution. Immutable by design (locked decision: committed segments
 * cannot be edited, only deleted or undone).
 */
internal data class Segment(val start: Vec2, val end: Vec2, val color: Color)

/**
 * The undoable slice of [PhotoMeasureContract.State] — quad, calibration, committed segments.
 *
 * Narrower than the whole `State` on purpose, even though `State` is now an immutable value that
 * could be pushed wholesale: undo must not rewind the display unit, a bottom sheet, or which screen
 * the user is on. Deliberately excludes the photo `Bitmap` (snapshotting bitmaps would multiply
 * memory by the undo depth) and SCR-24's in-progress draft — undo is scoped to *committed* segments
 * only (locked decision), never a still-being-dragged one.
 */
internal data class PhotoSnapshot(
    val quad: List<Vec2>,
    val homography: Homography?,
    val segments: List<Segment>,
)

/**
 * The MVI contract for "measure from a photo" (SCR-15 reference grid, SCR-21/22 quad, SCR-23
 * segments, SCR-24 line editor). Reducers live in `PhotoMeasureReducers.kt`, the dispatcher in
 * [PhotoMeasureViewModel].
 */
internal object PhotoMeasureContract {

    /**
     * Immutable UI state.
     *
     * **Every coordinate here is in the photo's own bitmap pixel grid, never display pixels.** That
     * is what makes a relayout a non-event: quad, segments and homography are all expressed against
     * the photo itself, so growing a button or rotating the device changes only where the photo is
     * *drawn*, not what was measured. Conversion happens at the two edges that genuinely deal in
     * screen pixels — draw scopes and gesture handlers — via `PhotoCoordinates.kt`.
     *
     * ### What is NOT here, and why
     *
     * The photo `Bitmap` is not a field of this class. It lives in [PhotoMeasureViewModel.photo],
     * a `StateFlow<Bitmap?>`. Two reasons, both concrete: a `data class` compares a `Bitmap` by
     * reference, so `equals`/`copy` would silently make state comparison meaningless for the one
     * field that costs megabytes; and a `State` retained across a configuration change would retain
     * those megabytes with it. The flow is still observable, so the UI recomposes on load exactly as
     * before — there is deliberately no mirrored `photoLoaded` boolean here, because a second source
     * of truth for "is there a photo" is a drift risk with no reader the flow cannot serve.
     *
     * ### What is persisted
     *
     * Only [chosenReferenceId], [showPickPhotoSheet], [showReferenceSheet] and [editingReferenceId]
     * — see [PhotoMeasureViewModel.persist]. **Not persisted: the photo bitmap, the [quad], the
     * [segments], the [homography], the undo history.** That is the same gap this screen has always
     * had and it is not an oversight: a `SavedStateHandle` goes through a `Bundle`, which has a hard
     * transaction size limit and throws rather than truncating, so a multi-megapixel bitmap or a
     * 20-deep snapshot history cannot go in one. Restoring a half-finished calibration would need a
     * real file-backed store, which is a different feature.
     *
     * @param chosenReferenceId id of the reference object the user picked, or null while still on
     *   the picker. Stored as an id, never as the object: [ReferenceObject] is a plain data class
     *   (not `Parcelable`) and an id always re-resolves against the freshest [customReferences],
     *   including one edited in between.
     * @param customReferences user-created reference objects, loaded asynchronously from
     *   `CustomReferenceStore`. Empty until [customReferencesLoaded].
     * @param customReferencesLoaded false until that load lands. The distinction matters: without
     *   it a restored *custom* [chosenReferenceId] would be indistinguishable from a stale one.
     * @param dragStartSnapshot captured when a quad-corner drag begins and pushed onto [undoStack]
     *   only when it ends, so one drag is one undo step rather than one per frame.
     */
    data class State(
        val chosenReferenceId: String? = null,
        val customReferences: List<ReferenceObject> = emptyList(),
        val customReferencesLoaded: Boolean = false,
        val quad: List<Vec2> = emptyList(),
        val homography: Homography? = null,
        val segments: List<Segment> = emptyList(),
        val isDrawingSegment: Boolean = false,
        val draftLine: LiveLine? = null,
        val draftColor: Color = PhotoLineColors.first(),
        val unit: LengthUnit = DefaultUnit,
        val isDetectingQuad: Boolean = false,
        val isEditingQuad: Boolean = false,
        val showPickPhotoSheet: Boolean = false,
        val showReferenceSheet: Boolean = false,
        val editingReferenceId: String? = null,
        val undoStack: List<PhotoSnapshot> = emptyList(),
        val redoStack: List<PhotoSnapshot> = emptyList(),
        val dragStartSnapshot: PhotoSnapshot? = null,
    ) : MviState {

        /** True once the user has left SCR-15. Carries what the old `referenceChosen` boolean said. */
        val referenceChosen: Boolean get() = chosenReferenceId != null

        /**
         * The chosen reference, **derived** rather than stored — which is the whole point.
         *
         * The regression this prevents: resolving the id into a field, in a `LaunchedEffect` keyed
         * on the id, meant a *custom* reference restored after process death was resolved before
         * [customReferences] had arrived, so the screen sat on the A4 default while displaying the
         * restored flow — silently measuring against the wrong object. A derivation cannot have an
         * ordering dependency: it re-evaluates the moment the list lands.
         *
         * Null means "chosen, not resolvable yet" — only reachable while [customReferencesLoaded]
         * is false. There is deliberately **no fallback to A4 anywhere**; every caller that needs
         * real millimetres treats null as "not ready" instead.
         */
        val reference: ReferenceObject?
            get() = chosenReferenceId?.let { id ->
                (builtInReferenceObjects + customReferences).firstOrNull { it.id == id }
            }

        /** The custom object the edit sheet is editing; null = "add new", which is also what a stale id degrades to. Derived for the same reason as [reference]. */
        val editingReference: ReferenceObject?
            get() = editingReferenceId?.let { id -> customReferences.firstOrNull { it.id == id } }

        val isCalibrated: Boolean get() = homography != null

        /** Derived, not stored: two booleans that can disagree with the stacks they describe are two bugs waiting. */
        val canUndo: Boolean get() = undoStack.isNotEmpty()
        val canRedo: Boolean get() = redoStack.isNotEmpty()

        /** [segment]'s real-world length. Both the segment and [homography] are in bitmap space, so there is nothing to convert and no canvas size to know. Null before calibration. */
        fun distanceMmFor(segment: Segment): Float? {
            val h = homography ?: return null
            return measureRealDistanceMm(h, segment.start, segment.end)
        }

        /** SCR-24's in-progress segment's live length — the same one-liner as [distanceMmFor], because the draft is stored in the same space. Null before calibration or before the draft is placed. */
        fun draftDistanceMm(): Float? {
            val h = homography ?: return null
            val draft = draftLine ?: return null
            return measureRealDistanceMm(h, draft.start, draft.end)
        }
    }

    /**
     * Every user action on this screen. Named after the gesture, not the field it happens to write,
     * so the list reads as the flow: pick a reference, pick a photo, tap, drag, confirm, draw, save.
     */
    sealed interface Intent : MviIntent {
        data class SelectReference(val reference: ReferenceObject) : Intent
        data object ChangeReference : Intent
        data object AddNewReferenceRequested : Intent
        data class EditReferenceRequested(val reference: ReferenceObject) : Intent
        data object ReferenceSheetDismissed : Intent
        data class SubmitReference(val label: String, val shortSideMm: Float, val longSideMm: Float) : Intent
        data object DeleteEditedReference : Intent

        data object PickPhotoRequested : Intent
        data object PickPhotoSheetDismissed : Intent

        /**
         * The picked photo's pixels, already decoded.
         *
         * The decode stays at the UI edge rather than becoming a ViewModel dependency: it needs a
         * `Context` and a `content://` Uri, and routing those through here would put an Android
         * `Context` inside the ViewModel to save nothing. A `Bitmap` as an intent payload is
         * honest — "the photo I just picked" *is* the user's action — and it never reaches
         * `State` or the `SavedStateHandle`.
         */
        data class PhotoPicked(val bitmap: Bitmap) : Intent
        data object DiscardPhoto : Intent

        /** [point] is already in bitmap space; the caller converts. Width/height are passed rather than read off the bitmap so the fallback path is exercisable on the JVM. */
        data class TapToReveal(val point: Vec2, val photoWidthPx: Float, val photoHeightPx: Float) : Intent
        // MoveCorner / CornerDragEnded / MoveDraftEndpoint are deliberately NOT intents — see
        // PhotoMeasureViewModel's drag methods for why a per-touch-event gesture does not go
        // through the intent channel.
        data object ConfirmReference : Intent
        data object EditQuadRequested : Intent

        data object BeginDrawSegment : Intent
        data class PlaceDraftInitial(val photoWidthPx: Float, val photoHeightPx: Float) : Intent
        data class SetDraftColor(val color: Color) : Intent
        data object CommitDraft : Intent
        data object CancelDraft : Intent
        data class DeleteSegment(val index: Int) : Intent

        data object Undo : Intent
        data object Redo : Intent
        data class SetUnit(val unit: LengthUnit) : Intent
    }

    /** One-shot events. Measuring results go to the host; nothing else here is a notification. */
    sealed interface Effect : MviEffect {
        data class MeasurementCompleted(val result: MeasurementResult.Photo) : Effect
    }
}
