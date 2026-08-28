package vn.apero.armeasure.photo.presentation

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.common.presentation.mvi.MviViewModel
import vn.apero.armeasure.photo.data.CustomReferenceStore
import vn.apero.armeasure.photo.data.autoFitQuad
import vn.apero.armeasure.photo.data.segmentQuad
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.Effect
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.Intent
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.State

/**
 * The photo-measure screen's ViewModel: intent dispatch, the three impure collaborators (the
 * detector, `CustomReferenceStore`, the unit preference), and the photo bitmap. Every state
 * transition itself is a pure function in `PhotoMeasureReducers.kt`.
 *
 * Obtained with androidx's `viewModel()` plus an explicit factory rather than `koinViewModel()` —
 * see `build.gradle.kts`'s comment and phase 01's DI decision.
 *
 * @param initialUnit seed for the display unit, read from `UnitPreference` by the caller so this
 *   class needs no `Context`.
 * @param persistUnit writes a unit change back to that preference, off the main thread.
 */
internal class PhotoMeasureViewModel(
    private val savedState: SavedStateHandle,
    private val referenceStore: CustomReferenceStore,
    private val initialUnit: LengthUnit,
    private val persistUnit: suspend (LengthUnit) -> Unit,
) : MviViewModel<State, Intent, Effect>() {

    /**
     * The photo's pixels — the one piece of this screen that is deliberately NOT in [State].
     *
     * A `Bitmap` in a `data class` is compared by reference, so it would make state equality
     * meaningless for the only field that costs megabytes, and a `State` held across a configuration
     * change would hold those megabytes with it. A `StateFlow` is still observable, so the UI
     * recomposes on load exactly as a `State` field would have made it. It is not persisted and
     * never will be: a `Bundle` has a hard transaction size limit and would throw.
     */
    private val _photo = MutableStateFlow<Bitmap?>(null)
    val photo: StateFlow<Bitmap?> = _photo.asStateFlow()

    init {
        // Asynchronous on purpose: the store reads a prefs file and parses JSON, and can write a
        // migration back. Until it lands, `State.customReferencesLoaded` is false — which is what
        // lets a restored CUSTOM reference be told apart from a stale id instead of quietly
        // resolving to A4.
        viewModelScope.launch {
            val loaded = referenceStore.loadAll()
            updateState { customReferencesLoaded(loaded) }
        }
    }

    /**
     * Restores the four persisted fields. Safe to read constructor arguments here: the base class
     * creates its `MutableStateFlow` lazily precisely so this runs after construction — see
     * `MviViewModel._state`.
     */
    override fun createInitialState() = State(unit = initialUnit).restoring { savedState.get<Any>(it) }

    /**
     * What survives process death, as one decision instead of six scattered `rememberSaveable`
     * patches — each of which existed here because a user hit the bug first. The list itself, and
     * what is deliberately left out of it, is `PhotoMeasureSavedState.kt`.
     */
    /**
     * Only writes what changed. `persist` runs after every `updateState`, and a corner drag calls
     * that 60 to 120 times a second — none of these four fields can change during a drag, so without
     * this the drag path pays four `SavedStateHandle` writes per pointer event to store what is
     * already there. The drag deliberately bypasses the intent channel to avoid exactly that kind of
     * per-frame cost.
     */
    override fun persist(state: State) {
        state.saveableFields().forEach { (key, value) ->
            if (savedState.get<Any?>(key) != value) savedState[key] = value
        }
    }

    /**
     * Drag is a direct call, not an [Intent], and that is the one deliberate hole in "everything goes
     * through the intent channel".
     *
     * `detectDragGestures` fires on every pointer move — 60 to 120 events per second while a finger is
     * down. Routed through `processIntent` each one costs an intent allocation, a `viewModelScope`
     * dispatch, and an `emit` into a zero-buffer `MutableSharedFlow`, which suspends until the
     * collector has processed the previous event. That serialises a gesture through a coroutine hop
     * per frame, on a device where release cold start is 648 ms and there is no headroom to spare.
     *
     * `updateState` still allocates one state object per event, which is inherent to immutable state
     * and cheap — the coroutine round trip is the part worth removing. The AR half of this module
     * reached the same conclusion independently for its own drag path.
     *
     * The gesture's *end* is a direct call too, for ordering rather than cost: it commits the undo
     * entry, and an intent could be processed after a subsequent discrete intent had already landed.
     */
    fun onCornerDrag(index: Int, position: Vec2) = updateState { moveQuadCorner(index, position) }

    fun onCornerDragEnded() = updateState { commitDrag() }

    fun onDraftEndpointDrag(isStart: Boolean, position: Vec2) =
        updateState { moveDraftEndpoint(isStart, position) }

    override fun handleIntent(intent: Intent) {
        when (intent) {
            is Intent.SelectReference -> updateState { selectReference(intent.reference) }
            Intent.ChangeReference -> updateState { changeReference() }
            Intent.AddNewReferenceRequested -> updateState { requestAddReference() }
            is Intent.EditReferenceRequested -> updateState { requestEditReference(intent.reference) }
            Intent.ReferenceSheetDismissed -> updateState { dismissReferenceSheet() }
            is Intent.SubmitReference -> submitReference(intent)
            Intent.DeleteEditedReference -> deleteEditedReference()

            Intent.PickPhotoRequested -> updateState { requestPickPhoto() }
            Intent.PickPhotoSheetDismissed -> updateState { dismissPickPhotoSheet() }
            is Intent.PhotoPicked -> {
                _photo.value = intent.bitmap
                updateState { photoPicked() }
            }
            Intent.DiscardPhoto -> {
                _photo.value = null
                updateState { discardPhoto() }
            }

            is Intent.TapToReveal -> revealQuadAt(intent)
            Intent.ConfirmReference -> updateState { confirmReference() }
            Intent.EditQuadRequested -> updateState { beginEditQuad() }

            Intent.BeginDrawSegment -> updateState { beginDrawSegment() }
            is Intent.PlaceDraftInitial -> updateState { placeDraftInitial(intent.photoWidthPx, intent.photoHeightPx) }
            is Intent.SetDraftColor -> updateState { setDraftColor(intent.color) }
            Intent.CommitDraft -> commitDraft()
            Intent.CancelDraft -> updateState { cancelDrawSegment() }
            is Intent.DeleteSegment -> updateState { deleteSegment(intent.index) }

            Intent.Undo -> updateState { undo() }
            Intent.Redo -> updateState { redo() }
            is Intent.SetUnit -> {
                updateState { setUnit(intent.unit) }
                viewModelScope.launch { persistUnit(intent.unit) }
            }
        }
    }

    private fun submitReference(intent: Intent.SubmitReference) {
        val editing = stateValue.editingReference
        viewModelScope.launch {
            if (editing == null) {
                // Stay on the reference grid after creating an object — the new card lands right
                // before the "Add new" tile the user just tapped, so tapping it is what advances.
                val added = referenceStore.add(intent.label, intent.shortSideMm, intent.longSideMm)
                updateState { referenceAdded(added) }
            } else {
                val updated = referenceStore.update(editing.id, intent.label, intent.shortSideMm, intent.longSideMm)
                if (updated != null) updateState { referenceUpdated(updated) }
            }
        }
    }

    private fun deleteEditedReference() {
        val editing = stateValue.editingReference ?: return
        viewModelScope.launch {
            if (referenceStore.delete(editing.id)) updateState { referenceDeleted(editing.id) }
        }
    }

    /**
     * Drops a quad near the tapped point — nothing is shown until the user taps roughly where the
     * reference object is, matching ARuler's own flow: a quad pre-placed on every fresh photo would
     * sit somewhere arbitrary far more often than not. No-op once a quad exists; this only ever
     * creates the *first* one.
     *
     * Segmentation first, edges as fallback. Not the other way round: an edge detector needs the
     * object's boundary to exist as a gradient, and on real photos it often does not (a black
     * phone's body merging into its own shadow measured 1/255 of luminance difference across its
     * true bottom edge). Segmentation decides which pixels are the object instead. Edges still win
     * where they ARE visible — an exact line beats an approximate mask boundary — and they are the
     * only path on a device with no Play Services.
     *
     * Launched into `viewModelScope` rather than the composition's scope, which is a real gain over
     * the state-holder version: a few hundred ms of detection now survives a configuration change
     * instead of being cancelled by it.
     */
    private fun revealQuadAt(intent: Intent.TapToReveal) {
        if (stateValue.quad.isNotEmpty()) return
        val bitmap = _photo.value
        // The reference object is always a rectangle whose real proportions we know, so hand that
        // ratio to the detectors — it is what separates the object from the many clutter rectangles
        // a real photo contains. Unresolvable reference (the custom list has not landed yet) means
        // no ratio, so the fallback box is used rather than a detection against the wrong shape.
        val reference = stateValue.reference
        viewModelScope.launch {
            // Re-checked inside the launch, not only before it: the outer guard runs at tap time and
            // the tap surface stays live for the whole detection, so a second tap during a run that
            // can take seconds used to start a second segmenter and Canny pass in parallel — another
            // scaled bitmap, another megapixel FloatArray, another flood fill. The quad was never
            // wrong (the write is guarded), but memory spiked and whichever finished first cleared
            // the spinner while the other was still working.
            if (bitmap != null && reference != null && !stateValue.isDetectingQuad) {
                updateState { copy(isDetectingQuad = true) }
                val targetRatio = reference.longSideMm / reference.shortSideMm
                val detected = try {
                    segmentQuad(bitmap, intent.point, targetRatio)
                        ?: withContext(Dispatchers.Default) { autoFitQuad(bitmap, intent.point, targetRatio) }
                } finally {
                    updateState { copy(isDetectingQuad = false) }
                }
                if (detected != null) {
                    updateState { quadDetected(detected) }
                    return@launch
                }
            }
            updateState { fallbackQuadAt(intent.point, intent.photoWidthPx, intent.photoHeightPx) }
        }
    }

    private fun commitDraft() {
        updateState { commitDrawnSegment() }
        val committed = stateValue.segments.lastOrNull() ?: return
        val distanceMm = stateValue.distanceMmFor(committed) ?: return
        sendEffect(Effect.MeasurementCompleted(MeasurementResult.Photo(distanceMm / 1000f, stateValue.unit)))
    }

    /** Drops the bitmap reference so the pixels are collectable as soon as the screen is gone for good. */
    override fun onCleared() {
        _photo.value = null
        super.onCleared()
    }
}
