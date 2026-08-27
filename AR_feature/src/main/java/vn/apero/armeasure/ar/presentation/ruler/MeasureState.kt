package vn.apero.armeasure.ar.presentation.ruler

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Anchor
import com.google.ar.core.Session
import vn.apero.armeasure.ar.data.arcore.HitSource
import vn.apero.armeasure.ar.data.arcore.SurfaceSample
import vn.apero.armeasure.ar.data.arcore.toVec3
import vn.apero.armeasure.ar.domain.geometry.Vec3
import vn.apero.armeasure.ar.domain.geometry.measurePointsMoved
import vn.apero.armeasure.ar.domain.steadiness.SteadinessGate
import vn.apero.armeasure.common.domain.UndoRedoStack

/** A committed measurement point: an ARCore anchor plus how its position was obtained. */
internal class MeasuredPoint(val anchor: Anchor, val source: HitSource)

/** One screen-space segment ready to draw, with its label already formatted. */
internal data class Segment2D(
    val start: Offset,
    val end: Offset,
    val midpoint: Offset,
    val label: String,
)

/**
 * Everything the overlay needs for one frame, in screen pixels.
 *
 * Recomputed each ARCore frame and read inside the Canvas draw lambda, so a new frame
 * invalidates only the draw phase — no recomposition, no relayout.
 */
internal data class OverlayFrame(
    val points: List<Offset> = emptyList(),
    val committed: List<Segment2D> = emptyList(),
    /** The rubber-band segment from the last committed point to the reticle. Drawn dashed. */
    val live: Segment2D? = null,
    val reticleOnSurface: Boolean = false,
    /** Screen position of the point currently being dragged, or null when nothing is. */
    val draggingPoint: Offset? = null,
)

/**
 * Mutable UI state for the measure screen.
 *
 * A plain state holder rather than a ViewModel: nothing here outlives the screen and nothing
 * needs to survive process death — a half-finished measurement is not worth restoring, since
 * the ARCore session that gave the anchors meaning is gone anyway.
 *
 * One class serves both distance tools, mirroring how `ShapeMeasureState` serves Box and Cylinder:
 * [chained] is the entire difference. Everything else here — undo/redo over the original anchors,
 * drag-to-edit, the steadiness gate, anchor lifetime — is pairing-agnostic, and a second class
 * would only duplicate the anchor-detach paths that keep ARCore's frame budget honest.
 *
 * @param chained true for the polyline tool, where each committed point continues the line; false
 *   for the independent-segment tool, where points are consumed in start/end pairs. See
 *   [vn.apero.armeasure.ar.domain.geometry.segmentIndexPairs].
 */
internal class MeasureState(val chained: Boolean) {

    val points = mutableStateListOf<MeasuredPoint>()

    /**
     * World positions mirroring [points], refreshed from the anchors only when they actually
     * move. Anchors drift as ARCore refines tracking, so this cannot be captured once at tap
     * time — but neither can it be written every frame without making the numbers flicker.
     */
    var worldPoints by mutableStateOf<List<Vec3>>(emptyList())
        private set

    /** Live surface reading under the reticle, or null when the reticle is off-surface. */
    var live by mutableStateOf<SurfaceSample?>(null)

    /**
     * Whether the live reading has held still long enough to be worth committing.
     *
     * Plane readings are steady by construction. Depth-map readings are not: on a glossy or
     * blank surface — a television, a painted wall — the depth estimate for one fixed target
     * has been measured swinging between 0.46 m and 3.73 m from frame to frame. A point
     * committed from one of those samples is anchored firmly at a distance that was never
     * real, and parallax then slides it across the scene as the phone moves, which reads as
     * the anchor having come loose. No surface moves metres in a thirtieth of a second, so
     * that jump is the tell, and refusing the point beats reporting a length nobody can tell
     * is wrong.
     */
    private val steadinessGate = SteadinessGate()
    val liveStable: Boolean get() = steadinessGate.stable

    /** Feeds one frame's reading into the steadiness gate behind [liveStable]. */
    fun noteLiveSample(sample: SurfaceSample?, distanceMeters: Float?) {
        steadinessGate.note(sample, distanceMeters)
    }

    /**
     * Index into [points] currently being dragged to a new position, or null when nothing is.
     *
     * Editing a placed point this way — rather than only undo/redo the whole thing — is the
     * one gap the reference app (ARuler) has that this one did not: its "Edit measurements"
     * instruction is exactly picking up an already-placed point and moving it.
     */
    var draggingIndex by mutableStateOf<Int?>(null)
        private set

    /**
     * Latest resolved surface position under the drag touch. Sticky on purpose: only a
     * successful hit overwrites it (see [noteDragSample]), so a momentary gap in the surface
     * under the finger does not make the dragged point vanish for a frame.
     */
    var dragSample by mutableStateOf<SurfaceSample?>(null)
        private set

    /** Raw screen position of the finger while dragging. Read every AR frame to resolve [dragSample]. */
    var dragTouchPosition by mutableStateOf<Offset?>(null)
        private set

    fun beginDrag(index: Int, at: Offset) {
        draggingIndex = index
        dragTouchPosition = at
        dragSample = null
    }

    fun updateDragTouch(at: Offset) {
        dragTouchPosition = at
    }

    /** Feeds one frame's resolved surface under the drag touch into [dragSample]. */
    fun noteDragSample(sample: SurfaceSample?) {
        if (sample != null) dragSample = sample
    }

    /**
     * Ends the drag, replacing the point's anchor with one at the last resolved position.
     *
     * A no-op — the point stays exactly where it was — if the drag never resolved a surface,
     * since committing a null position would either crash or silently drop the point.
     */
    fun commitDrag(session: Session) {
        val index = draggingIndex
        val sample = dragSample
        if (index != null && sample != null) {
            // A drag is a new committed action too — any pending redo refers to points that no
            // longer describe the current picture once one of them has moved.
            undoRedo.dropRedo()
            points[index].anchor.detach()
            points[index] = MeasuredPoint(sample.commit(session), sample.source)
            worldPoints = points.map { it.anchor.pose.toVec3() }
            lastSource = sample.source
        }
        cancelDrag()
    }

    fun cancelDrag() {
        draggingIndex = null
        dragTouchPosition = null
        dragSample = null
    }

    var overlay by mutableStateOf(OverlayFrame())

    /** Last point's hit source, surfaced in the UI: a reading you cannot attribute is a reading you cannot calibrate. */
    var lastSource by mutableStateOf<HitSource?>(null)

    /**
     * Holds points that [undo] removed, so [redo] can restore the exact same ARCore anchor — a
     * re-anchored pose would drift independently and could read a different number than it did
     * before the undo, which is a correctness defect in a measuring tool. Detaching is deferred
     * to [UndoRedoStack]'s `onEvict`, fired only once an entry is truly discarded (a new commit,
     * [clear], overflow past the depth cap, or [releaseAll]).
     */
    private val undoRedo = UndoRedoStack<MeasuredPoint>(onEvict = { it.anchor.detach() })

    val canUndo: Boolean get() = points.isNotEmpty()
    val canRedo: Boolean get() = undoRedo.canRedo
    val isDrawing: Boolean get() = points.isNotEmpty()

    /** Commits the current live reading as a new point. No-op when off-surface. */
    fun commitLivePoint(session: Session): Boolean {
        val sample = live ?: return false
        // A new point is a new committed action — any pending redo is now stale.
        undoRedo.dropRedo()
        points.add(MeasuredPoint(sample.commit(session), sample.source))
        lastSource = sample.source
        worldPoints = points.map { it.anchor.pose.toVec3() }
        return true
    }

    fun undo() {
        // A stale draggingIndex pointing past the shrunk list is a crash waiting to happen.
        cancelDrag()
        val last = points.removeLastOrNull() ?: return
        undoRedo.pushRedo(last)
        worldPoints = points.map { it.anchor.pose.toVec3() }
        lastSource = points.lastOrNull()?.source
    }

    fun redo() {
        val restored = undoRedo.popRedo() ?: return
        points.add(restored)
        worldPoints = points.map { it.anchor.pose.toVec3() }
        lastSource = restored.source
    }

    fun clear() {
        cancelDrag()
        // Detaching matters: an undetached anchor keeps costing ARCore tracking work every
        // frame, so a session of measure-and-clear slowly starves the frame budget.
        points.forEach { it.anchor.detach() }
        points.clear()
        undoRedo.clear()
        worldPoints = emptyList()
        lastSource = null
        overlay = OverlayFrame()
    }

    /**
     * Detaches every anchor this state still holds — the committed points plus anything sitting
     * on the redo stack. Call from a `DisposableEffect` on screen dispose: today a half-drawn
     * measurement's anchors are cleaned up incidentally by ARCore session teardown, but once the
     * session becomes long-lived and shared across tabs, nothing else will do this.
     */
    fun releaseAll() {
        cancelDrag()
        points.forEach { it.anchor.detach() }
        undoRedo.clear()
    }

    /** Re-reads anchor poses, writing state only past the 1 mm dead-band. */
    fun refreshWorldPoints() {
        if (points.isEmpty()) return
        val next = points.map { it.anchor.pose.toVec3() }
        if (measurePointsMoved(worldPoints, next)) worldPoints = next
    }

    /**
     * Resets the steadiness gate and clears the live reading. Call when this tool becomes the
     * active one after a swap — with tool state holders now living across swaps instead of
     * getting a fresh instance (and a fresh gate) per mount, a stale gate could otherwise read
     * `liveStable == true` for one frame off samples taken before the swap, long enough to enable
     * `+` and commit a false point the instant this tool becomes active.
     */
    fun onActivated() {
        steadinessGate.reset()
        live = null
    }
}
