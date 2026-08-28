package vn.apero.armeasure.ar.presentation.ruler

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.ar.core.Frame
import com.google.ar.core.Session
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.data.arcore.toVec3
import vn.apero.armeasure.ar.domain.geometry.measureDistanceMeters
import vn.apero.armeasure.ar.domain.geometry.segmentIndexPairs
import vn.apero.armeasure.ar.presentation.camera.ArSessionFrameStream
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.common.domain.UndoRedoStack
import vn.apero.armeasure.common.presentation.mvi.MviViewModel

/**
 * The two distance tools: Distance (independent start/end pairs) and Distance chain (a polyline).
 *
 * One ViewModel serves both, mirroring how [vn.apero.armeasure.ar.presentation.shapes.ShapeMeasureViewModel]
 * serves Box and Cylinder: [chained] is the entire difference. Everything else — undo/redo over the
 * original anchors, drag-to-edit, the steadiness gate, anchor lifetime — is pairing-agnostic, and a
 * second class would only duplicate the anchor-detach paths that keep ARCore's frame budget honest.
 *
 * ### What is state and what is not
 *
 * [MeasureUiState] holds only what a tap changes. The per-frame values live in [frames], a plain
 * holder outside the emitted state — see [MeasureFrameStream]'s KDoc for the full reasoning, which
 * in one line is: a coroutine dispatch plus a whole-state allocation per ARCore frame, with
 * whole-state invalidation replacing Compose's per-field invalidation, is not affordable on the
 * low-end device this module targets.
 *
 * Consequently three groups of members exist here, and the distinction is the design:
 * - `processIntent(...)` for user actions ([MeasureIntent]) — the MVI path.
 * - [onFrame] for the ARCore callback — a direct call, no intent, by design.
 * - [onDragStart]/[onDragMove]/[onDragEnd]/[onDragCancel]/[onActivated]/[onSessionChanged]/
 *   [releaseAll] for the render-rate gesture stream and for lifecycle wiring. Direct calls too:
 *   they are either per-frame or they must be visible to the *next* touch event, which an intent
 *   round trip through a `SharedFlow` cannot promise.
 *
 * [persist] is deliberately not overridden: the committed points are ARCore [com.google.ar.core.Anchor]s,
 * meaningless once the session that issued them is gone, so there is nothing here worth surviving
 * process death.
 */
internal class MeasureViewModel(
    val chained: Boolean,
) : MviViewModel<MeasureUiState, MeasureIntent, MeasureEffect>() {

    /** The per-frame stream, outside [MeasureUiState] on purpose. See the class KDoc. */
    val frames = MeasureFrameStream()

    /**
     * Committed points, kept as a plain list rather than in [MeasureUiState].
     *
     * They are ARCore anchors: the renderer never wants the anchors, it wants their current world
     * positions, which drift and are therefore republished through [MeasureFrameStream.worldPoints]
     * at frame rate. Putting the anchor list in the state as well would mean two sources of truth
     * for the same measurement, one of them always a frame behind.
     */
    private val points = mutableListOf<MeasuredPoint>()

    /**
     * Holds points that [MeasureIntent.Undo] removed, so a redo can restore the exact same ARCore
     * anchor — a re-anchored pose would drift independently and could read a different number than
     * it did before the undo, which is a correctness defect in a measuring tool. Detaching is
     * deferred to [UndoRedoStack]'s `onEvict`, fired only once an entry is truly discarded (a new
     * commit, a clear, overflow past the depth cap, or [releaseAll]).
     */
    private val undoRedo = UndoRedoStack<MeasuredPoint>(onEvict = { it.anchor.detach() })

    /**
     * The live ARCore session, pushed in by the screen rather than taken as an intent parameter.
     *
     * The session is not a user decision and it is not state: it is a handle that appears when
     * `ARSceneView` creates it and disappears when the watchdog recreates it. Holding it here keeps
     * every intent free of ARCore types.
     */
    private var session: Session? = null

    override fun createInitialState() = MeasureUiState()

    override fun handleIntent(intent: MeasureIntent) {
        when (intent) {
            is MeasureIntent.CommitLivePoint -> commitLivePoint(intent.unit)
            MeasureIntent.Undo -> undo()
            MeasureIntent.Redo -> redo()
            MeasureIntent.Clear -> clear()
        }
    }

    fun onSessionChanged(session: Session?) {
        this.session = session
    }

    /**
     * One ARCore frame. Called directly from `onSessionUpdated`, never through [processIntent] —
     * see the class KDoc.
     */
    fun onFrame(
        sessionFrames: ArSessionFrameStream,
        projector: PoseProjector,
        unit: LengthUnit,
        session: Session,
        frame: Frame,
        viewSize: IntSize,
        density: Float,
    ) = onMeasureFrame(
        frames = frames,
        points = points,
        chained = chained,
        sessionFrames = sessionFrames,
        projector = projector,
        unit = unit,
        session = session,
        frame = frame,
        viewSize = viewSize,
        density = density,
    )

    private fun commitLivePoint(unit: LengthUnit) {
        val activeSession = session ?: return
        val sample = frames.live ?: return
        // A new point is a new committed action — any pending redo is now stale.
        undoRedo.dropRedo()
        points.add(MeasuredPoint(sample.commit(activeSession), sample.source))
        frames.publishWorldPoints(points.map { it.anchor.pose.toVec3() })
        updateState { copy(pointCount = points.size, canRedo = undoRedo.canRedo, lastSource = sample.source) }
        emitClosedSegment(unit)
    }

    /**
     * Reports the segment this commit closed, if it closed one. The old "two or more points" test
     * was equivalent while the only tool was the chained one, but in the unchained tool a point that
     * opens a new segment would have reported the gap between the previous segment's end and this
     * new start — a length that is never drawn and that the user never asked to measure.
     */
    private fun emitClosedSegment(unit: LengthUnit) {
        val world = frames.worldPoints
        val closed = segmentIndexPairs(world.size, chained)
            .lastOrNull()
            ?.takeIf { (_, end) -> end == world.lastIndex }
            ?: return
        val meters = measureDistanceMeters(world[closed.first], world[closed.second])
        sendEffect(MeasureEffect.Measured(MeasurementResult.Distance(meters, unit)))
    }

    private fun undo() {
        // A stale draggingIndex pointing past the shrunk list is a crash waiting to happen.
        frames.endDrag()
        val last = points.removeLastOrNull() ?: return
        undoRedo.pushRedo(last)
        frames.publishWorldPoints(points.map { it.anchor.pose.toVec3() })
        updateState {
            copy(pointCount = points.size, canRedo = undoRedo.canRedo, lastSource = points.lastOrNull()?.source)
        }
    }

    private fun redo() {
        val restored = undoRedo.popRedo() ?: return
        points.add(restored)
        frames.publishWorldPoints(points.map { it.anchor.pose.toVec3() })
        updateState { copy(pointCount = points.size, canRedo = undoRedo.canRedo, lastSource = restored.source) }
    }

    private fun clear() {
        frames.endDrag()
        // Detaching matters: an undetached anchor keeps costing ARCore tracking work every
        // frame, so a session of measure-and-clear slowly starves the frame budget.
        points.forEach { it.anchor.detach() }
        points.clear()
        undoRedo.clear()
        frames.publishWorldPoints(emptyList())
        frames.overlay = OverlayFrame()
        updateState { copy(pointCount = 0, canRedo = false, lastSource = null) }
    }

    // ---- Drag-to-edit: render-rate, so direct calls rather than intents (see class KDoc) ----

    fun onDragStart(index: Int, at: Offset) = frames.beginDrag(index, at)

    fun onDragMove(at: Offset) = frames.updateDragTouch(at)

    /**
     * Ends the drag, replacing the point's anchor with one at the last resolved position.
     *
     * A no-op — the point stays exactly where it was — if the drag never resolved a surface or the
     * session went away, since committing a null position would either crash or silently drop the
     * point.
     */
    fun onDragEnd() {
        val index = frames.draggingIndex
        val sample = frames.dragSample
        val activeSession = session
        if (index != null && sample != null && activeSession != null) {
            // A drag is a new committed action too — any pending redo refers to points that no
            // longer describe the current picture once one of them has moved.
            undoRedo.dropRedo()
            points[index].anchor.detach()
            points[index] = MeasuredPoint(sample.commit(activeSession), sample.source)
            frames.publishWorldPoints(points.map { it.anchor.pose.toVec3() })
            updateState { copy(canRedo = undoRedo.canRedo, lastSource = sample.source) }
        }
        frames.endDrag()
    }

    fun onDragCancel() = frames.endDrag()

    /** See [MeasureFrameStream.onActivated] — touches only the frame stream, so no intent. */
    fun onActivated() = frames.onActivated()

    /**
     * Detaches every anchor this tool still holds — the committed points plus anything sitting on
     * the redo stack.
     *
     * Called from the screen's `DisposableEffect` on dispose *and* from [onCleared]. Both, not
     * either: the composition going away means the ARCore session is gone, which is when the
     * anchors must be released; but a ViewModel outlives the composition, so without [onCleared]
     * too an Activity recreation would leave this instance holding anchors from a session that no
     * longer exists.
     */
    fun releaseAll() {
        frames.endDrag()
        points.forEach { it.anchor.detach() }
        points.clear()
        undoRedo.clear()
        frames.publishWorldPoints(emptyList())
        // The state has to follow the anchors out: a retained ViewModel re-entered after an Activity
        // recreation would otherwise report a point count — and an enabled undo — for points that no
        // longer exist.
        updateState { copy(pointCount = 0, canRedo = false, lastSource = null) }
    }

    override fun onCleared() {
        releaseAll()
        super.onCleared()
    }

    companion object {
        /**
         * Explicit factory, used with `viewModel(key = ..., factory = ...)`.
         *
         * No Koin: phase 01 decided against adding a DI dependency to a module whose portability
         * claim (§13's reflection-free R8 story, a 3-symbol public API) is load-bearing for the
         * apply skill. The `key` at the call site is what gives the four tools four instances.
         */
        fun factory(chained: Boolean): ViewModelProvider.Factory = viewModelFactory {
            initializer { MeasureViewModel(chained) }
        }
    }
}
