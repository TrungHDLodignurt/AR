package vn.apero.armeasure.ar.presentation.shapes

import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Session
import kotlin.math.abs
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.data.arcore.toVec3
import vn.apero.armeasure.ar.domain.geometry.Vec3
import vn.apero.armeasure.ar.domain.geometry.circleFromPoints
import vn.apero.armeasure.ar.domain.geometry.heightAlongAxis
import vn.apero.armeasure.ar.domain.geometry.length
import vn.apero.armeasure.ar.domain.geometry.normalized
import vn.apero.armeasure.ar.domain.geometry.planeBasis
import vn.apero.armeasure.ar.domain.geometry.plus
import vn.apero.armeasure.ar.domain.geometry.projectedEdgeVector
import vn.apero.armeasure.ar.presentation.camera.ArSessionFrameStream
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.common.domain.UndoRedoStack
import vn.apero.armeasure.common.presentation.mvi.MviViewModel
import vn.apero.armeasure.ar.presentation.shapes.components.ShapeOverlayFrame

/**
 * One undo/redo entry: either a mid-shape step (stepping back from [ShapePhase.SizingEdgeV] to
 * [ShapePhase.SizingEdgeU], say — the exact previous [ShapePhase] instance, so a value like `edgeU`
 * that undo must not reset is simply restored, not recomputed) or a whole finished shape being
 * undone off the end of the committed list.
 */
private sealed class ShapeStep {
    data class Progress(val phase: ShapePhase) : ShapeStep()
    data class Finished(val shape: MeasuredShape) : ShapeStep()
}

/** The anchor this step is keeping alive, or null if it never referenced one. */
private fun ShapeStep.originAnchorOrNull(): Anchor? = when (this) {
    is ShapeStep.Progress -> phase.originAnchorOrNull()
    is ShapeStep.Finished -> shape.originAnchor
}

/**
 * The box and cylinder tools. One ViewModel for both — most of the state machine is shared, and
 * [kind] picks the few steps (which phases exist for the base, which pure-math function turns a live
 * reading into one) where box and cylinder genuinely diverge, at the two places ([commitStep],
 * [undo]) that need it.
 *
 * The state/frame split, and why it exists, is documented on [ShapeFrameStream] and on
 * [vn.apero.armeasure.ar.presentation.ruler.MeasureViewModel]: taps go through [processIntent], the
 * ARCore frame callback goes straight to [onFrame], and nothing per-frame is ever put in
 * [ShapeUiState].
 */
internal class ShapeMeasureViewModel(
    val kind: ShapeKind,
) : MviViewModel<ShapeUiState, ShapeIntent, ShapeEffect>() {

    /** The per-frame stream, outside [ShapeUiState] on purpose. */
    val frames = ShapeFrameStream()

    /**
     * Finished shapes, kept out of [ShapeUiState] because the renderer needs their *anchors*, whose
     * poses ARCore corrects between frames — so the list is re-projected every frame from the
     * anchors themselves ([buildShapeOverlay]) rather than from a copy in an immutable state object.
     * [ShapeUiState.shapeCount] is what the chrome actually asks about.
     */
    private val shapes = mutableListOf<MeasuredShape>()

    /**
     * Deferred-detach redo history — same reasoning as the distance tools': a redone shape must read
     * the exact same anchor, not a re-anchored pose that would drift independently.
     *
     * [isAnchorOrphaned] guards the actual detach: stepping `SizingHeight -> SizingEdgeV` reuses
     * the *same* origin anchor in the phase it lands on, so evicting the old [ShapeStep] entry
     * must not detach an anchor still live elsewhere.
     */
    private val undoRedo = UndoRedoStack<ShapeStep>(onEvict = { step ->
        step.originAnchorOrNull()?.let { anchor -> if (isAnchorOrphaned(anchor)) anchor.detach() }
    })

    /** See [vn.apero.armeasure.ar.presentation.ruler.MeasureViewModel.onSessionChanged]. */
    private var session: Session? = null

    override fun createInitialState() = ShapeUiState()

    override fun handleIntent(intent: ShapeIntent) {
        when (intent) {
            is ShapeIntent.CommitStep -> commitStep(intent.unit)
            ShapeIntent.Undo -> undo()
            ShapeIntent.Redo -> redo()
            ShapeIntent.Clear -> clear()
        }
    }

    fun onSessionChanged(session: Session?) {
        this.session = session
    }

    /** One ARCore frame. Called directly from `onSessionUpdated`, never through [processIntent]. */
    fun onFrame(
        sessionFrames: ArSessionFrameStream,
        projector: PoseProjector,
        unit: LengthUnit,
        session: Session,
        frame: Frame,
        viewSize: IntSize,
    ) = onShapeFrame(
        frames = frames,
        phase = stateValue.phase,
        shapes = shapes,
        sessionFrames = sessionFrames,
        projector = projector,
        unit = unit,
        session = session,
        frame = frame,
        viewSize = viewSize,
    )

    /** True once [anchor] is referenced by nothing live: not the current phase's origin, not held by any finished shape, and not sitting in the undo/redo history either. Only then is it safe to detach. */
    private fun isAnchorOrphaned(anchor: Anchor): Boolean {
        if (stateValue.phase.originAnchorOrNull() === anchor) return false
        if (shapes.any { it.originAnchor === anchor }) return false
        if (undoRedo.any { it.originAnchorOrNull() === anchor }) return false
        return true
    }

    /**
     * Advances the shape currently in progress by one tap. A no-op when the live reading is not
     * steady enough to trust — see [vn.apero.armeasure.ar.domain.steadiness.SteadinessGate] — since
     * committing an unstable reading would bake a false number into the shape permanently.
     */
    private fun commitStep(unit: LengthUnit) {
        val activeSession = session ?: return
        val sample = frames.live ?: return
        if (!frames.liveStable) return
        // A new step is a new committed action — any pending redo is now stale. Before the phase
        // moves, so isAnchorOrphaned still sees the phase the evicted entries belong to.
        undoRedo.dropRedo()
        val shapesBefore = shapes.size
        val nextPhase = when (val current = stateValue.phase) {
            is ShapePhase.AwaitingOrigin -> {
                // No plane under the tap (a depth/feature-point-only origin) still starts a
                // shape — world-up is the least-wrong assumption for "which way is normal",
                // since box/cylinder measuring is done standing on an approximately horizontal
                // surface far more often than not.
                val normal = (sample.planeNormal ?: Vec3(0f, 1f, 0f)).normalized()
                val anchor = sample.commit(activeSession)
                when (kind) {
                    ShapeKind.Box -> ShapePhase.SizingEdgeU(anchor, normal)
                    ShapeKind.Cylinder -> ShapePhase.SizingCircle(anchor, normal, planeBasis(normal))
                }
            }
            is ShapePhase.SizingEdgeU -> {
                val origin = current.originAnchor.pose.toVec3()
                val edgeU = projectedEdgeVector(origin, sample.position, current.normal)
                ShapePhase.SizingEdgeV(current.originAnchor, current.normal, edgeU)
            }
            is ShapePhase.SizingEdgeV -> {
                val origin = current.originAnchor.pose.toVec3()
                // Measured from the END of the first edge, not from the origin: the three taps are
                // a chain — corner, along one side, then turn and go along the next — exactly like
                // the distance-chain tool. Measuring the second edge from the origin instead made
                // the third tap jump its start point back to tap 1, which is not how anyone traces
                // a box.
                //
                // The parallelogram is unaffected: with edgeU = B - A and edgeV = C - B,
                // parallelogramCorners(A, edgeU, edgeV) is [A, B, C, A + C - B] — the same
                // parallelogram, now with B as the corner between the two drawn edges.
                val edgeV = projectedEdgeVector(origin + current.edgeU, sample.position, current.normal)
                ShapePhase.SizingHeight(
                    current.originAnchor,
                    current.normal,
                    ShapeBase.Rect(current.edgeU, edgeV),
                )
            }
            is ShapePhase.SizingCircle -> {
                val origin = current.originAnchor.pose.toVec3()
                val circle = circleFromPoints(origin, sample.position, current.basis)
                ShapePhase.SizingHeight(
                    current.originAnchor,
                    current.normal,
                    ShapeBase.Circle(circle.radius, current.basis),
                )
            }
            is ShapePhase.SizingHeight -> {
                val origin = current.originAnchor.pose.toVec3()
                val height = heightAlongAxis(origin, sample.position, current.normal)
                shapes.add(MeasuredShape(kind, current.originAnchor, current.normal, current.base, height))
                ShapePhase.AwaitingOrigin
            }
        }
        updateState { copy(phase = nextPhase, shapeCount = shapes.size, canRedo = undoRedo.canRedo) }
        // Only a tap that actually finished a shape reports a measurement — the earlier taps of the
        // same shape have no dimensions to report yet.
        if (shapes.size > shapesBefore) emitMeasured(shapes.last(), unit)
    }

    private fun emitMeasured(shape: MeasuredShape, unit: LengthUnit) {
        val result = when (val base = shape.base) {
            is ShapeBase.Rect ->
                MeasurementResult.Box(base.edgeU.length(), base.edgeV.length(), abs(shape.height), unit)
            is ShapeBase.Circle -> MeasurementResult.Cylinder(base.radius, abs(shape.height), unit)
        }
        sendEffect(ShapeEffect.Measured(result))
    }

    /**
     * Steps back one tap: height -> the base step(s) -> origin -> the previous finished shape, if
     * any.
     */
    private fun undo() {
        val nextPhase = when (val current = stateValue.phase) {
            is ShapePhase.SizingHeight -> {
                undoRedo.pushRedo(ShapeStep.Progress(current))
                when (val base = current.base) {
                    // Restore edgeU exactly as drawn — undo-then-redo must not silently reset it.
                    is ShapeBase.Rect ->
                        ShapePhase.SizingEdgeV(current.originAnchor, current.normal, base.edgeU)
                    is ShapeBase.Circle ->
                        ShapePhase.SizingCircle(current.originAnchor, current.normal, base.basis)
                }
            }
            is ShapePhase.SizingEdgeV -> {
                undoRedo.pushRedo(ShapeStep.Progress(current))
                ShapePhase.SizingEdgeU(current.originAnchor, current.normal)
            }
            is ShapePhase.SizingEdgeU -> {
                // No detach — deferred until this step is actually evicted from the redo history.
                undoRedo.pushRedo(ShapeStep.Progress(current))
                ShapePhase.AwaitingOrigin
            }
            is ShapePhase.SizingCircle -> {
                undoRedo.pushRedo(ShapeStep.Progress(current))
                ShapePhase.AwaitingOrigin
            }
            ShapePhase.AwaitingOrigin -> {
                val last = shapes.removeLastOrNull() ?: return
                undoRedo.pushRedo(ShapeStep.Finished(last))
                ShapePhase.AwaitingOrigin
            }
        }
        updateState { copy(phase = nextPhase, shapeCount = shapes.size, canRedo = undoRedo.canRedo) }
    }

    /** Restores exactly what [undo] last removed — the same anchor, so the same number. */
    private fun redo() {
        val step = undoRedo.popRedo() ?: return
        val nextPhase = when (step) {
            is ShapeStep.Progress -> step.phase
            is ShapeStep.Finished -> {
                shapes.add(step.shape)
                ShapePhase.AwaitingOrigin
            }
        }
        updateState { copy(phase = nextPhase, shapeCount = shapes.size, canRedo = undoRedo.canRedo) }
    }

    private fun clear() {
        detachAllHeldAnchors()
        shapes.clear()
        frames.overlay = ShapeOverlayFrame()
        updateState { copy(phase = ShapePhase.AwaitingOrigin, shapeCount = 0, canRedo = false) }
    }

    /** See [vn.apero.armeasure.ar.presentation.ruler.MeasureViewModel.onActivated]. */
    fun onActivated() = frames.onActivated()

    /**
     * Detaches every anchor this tool still holds — the in-progress phase's origin, every finished
     * shape's origin, and anything in the undo/redo history. Called from the screen's
     * `DisposableEffect` on dispose *and* from [onCleared], for the reason spelled out on
     * [vn.apero.armeasure.ar.presentation.ruler.MeasureViewModel.releaseAll].
     */
    fun releaseAll() {
        detachAllHeldAnchors()
        shapes.clear()
        updateState { copy(phase = ShapePhase.AwaitingOrigin, shapeCount = 0, canRedo = false) }
    }

    override fun onCleared() {
        releaseAll()
        super.onCleared()
    }

    /**
     * Collects every distinct anchor referenced anywhere and detaches each exactly once — bypasses
     * the [undoRedo] `onEvict`/[isAnchorOrphaned] path entirely, since a full wipe makes every
     * anchor orphaned simultaneously rather than one at a time.
     */
    private fun detachAllHeldAnchors() {
        val anchors = buildSet {
            stateValue.phase.originAnchorOrNull()?.let { add(it) }
            shapes.forEach { add(it.originAnchor) }
            undoRedo.drainWithoutEviction().forEach { step -> step.originAnchorOrNull()?.let { add(it) } }
        }
        anchors.forEach { it.detach() }
    }

    companion object {
        /** Explicit factory — see [vn.apero.armeasure.ar.presentation.ruler.MeasureViewModel.Companion.factory]. */
        fun factory(kind: ShapeKind): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShapeMeasureViewModel(kind) }
        }
    }
}
