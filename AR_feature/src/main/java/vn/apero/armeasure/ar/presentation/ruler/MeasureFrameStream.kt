package vn.apero.armeasure.ar.presentation.ruler

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import vn.apero.armeasure.ar.data.arcore.SurfaceSample
import vn.apero.armeasure.ar.domain.geometry.Vec3
import vn.apero.armeasure.ar.domain.geometry.measurePointsMoved
import vn.apero.armeasure.ar.domain.steadiness.SteadinessGate
import vn.apero.armeasure.ar.presentation.camera.components.PlaneDots

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
    /** The rubber-band segment from the last committed point to the reticle. Drawn dashed. */
    val committed: List<Segment2D> = emptyList(),
    val live: Segment2D? = null,
    val reticleOnSurface: Boolean = false,
    /** Screen position of the point currently being dragged, or null when nothing is. */
    val draggingPoint: Offset? = null,
    /**
     * The surface affordance under everything else — empty whenever the reticle resolved without a
     * plane normal (a depth-map or feature-point reading), which is correct rather than missing:
     * there is no plane to paint.
     */
    val planeDots: PlaneDots = PlaneDots.Empty,
    /** The reticle's projected world-space ring, or empty to fall back to the flat dot. */
    val reticleRing: List<Offset> = emptyList(),
    /** True while the reticle is locked onto an existing point — drawn as a double ring. */
    val snapped: Boolean = false,
)

/**
 * The distance tools' **frame stream**: everything ARCore rewrites at 30-60 Hz, plus the drag
 * gesture's per-touch-event samples. Owned by [MeasureViewModel], read directly by the renderer,
 * and deliberately **not** part of the emitted MVI `State`.
 *
 * Why it is not in `State` — written down so it is not re-litigated. `MeasureFrameLoop.onFrame` is
 * called from ARCore's frame callback, i.e. every frame. Routing that through
 * `processIntent -> SharedFlow -> handleIntent -> updateState { copy() } -> StateFlow` costs one
 * coroutine dispatch and one full state allocation per frame, and replaces Compose's per-field
 * invalidation (a new [overlay] invalidates only the draw scope that reads it) with whole-state
 * invalidation of every collector. On the low-end device this module targets — release cold start
 * 648 ms, debug 2.7 s — there is no headroom for that. Transient render state is not UI state:
 * [MeasureUiState] carries what the user drives at human speed, this carries what the camera does.
 *
 * The drag gesture lives here too, not in `State`. Its position arrives at touch-event rate and is
 * resolved against a surface *inside the frame loop* ([noteDragSample]), so it is the same class of
 * value; and because the writes are synchronous, `onDragStart` is visible to the very next
 * `onDrag`, which an intent round trip through a `SharedFlow` would not be.
 *
 * Backed by Compose state so the renderer and the chrome observe it exactly as they did before the
 * MVI conversion.
 */
internal class MeasureFrameStream {

    /** Live surface reading under the reticle, or null when the reticle is off-surface. */
    var live by mutableStateOf<SurfaceSample?>(null)
        private set

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

    /**
     * World positions mirroring the ViewModel's committed points, refreshed from the anchors only
     * when they actually move. Anchors drift as ARCore refines tracking, so this cannot be captured
     * once at tap time — but neither can it be written every frame without making the numbers
     * flicker.
     */
    var worldPoints by mutableStateOf<List<Vec3>>(emptyList())
        private set

    var overlay by mutableStateOf(OverlayFrame())

    /**
     * Index of the committed point currently being dragged to a new position, or null when nothing
     * is.
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

    /**
     * Index of the existing point the reticle is currently locked onto, or null when it is free.
     *
     * Read in composition (to fire one haptic tick per lock) as well as inside the frame loop, and
     * carried across frames because the release decision depends on what was held last frame — see
     * [vn.apero.armeasure.ar.domain.geometry.snapTarget].
     */
    var snappedIndex by mutableStateOf<Int?>(null)
        private set

    /**
     * Whether the reading is trustworthy enough to commit — the steadiness gate, or a snap.
     *
     * A snap bypasses the gate deliberately. The gate exists to distrust a *live depth estimate*,
     * which on a glossy or blank surface has been measured swinging metres between frames. A
     * snapped reading is not that: its position is an anchor ARCore has already placed and refined.
     * Asking the gate about it would make the user watch a visible lock refuse their tap for the
     * five frames the gate needs to agree with something it was never uncertain about.
     */
    val commitReady: Boolean get() = liveStable || snappedIndex != null

    /** Whether the bottom "+" button should be tappable right now. */
    val addEnabled: Boolean get() = draggingIndex == null && live != null && commitReady

    /** Records this frame's snap decision. See [snappedIndex]. */
    fun noteSnap(index: Int?) {
        snappedIndex = index
    }

    /** Feeds one frame's reading into [live] and the steadiness gate behind [liveStable]. */
    fun noteLiveSample(sample: SurfaceSample?, distanceMeters: Float?) {
        live = sample
        steadinessGate.note(sample, distanceMeters)
    }

    /**
     * Drops the live reading and the graphics for a frame with no camera pose, rather than leaving
     * them frozen at stale screen coordinates: with no pose there is no honest place to draw them,
     * and a line that keeps sitting on the floor while the phone moves reads as a measurement the
     * app still stands by.
     */
    fun clearForUntrackedFrame() {
        live = null
        overlay = OverlayFrame()
    }

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

    fun endDrag() {
        draggingIndex = null
        dragTouchPosition = null
        dragSample = null
    }

    fun publishWorldPoints(points: List<Vec3>) {
        worldPoints = points
    }

    /** Re-reads anchor poses, writing state only past the 1 mm dead-band. */
    fun refreshWorldPoints(next: List<Vec3>) {
        if (measurePointsMoved(worldPoints, next)) worldPoints = next
    }

    /**
     * Resets the steadiness gate and clears the live reading. Called when this tool becomes the
     * active one after a swap — tool state now lives across swaps instead of getting a fresh
     * instance (and a fresh gate) per mount, so a stale gate could otherwise read
     * `liveStable == true` for one frame off samples taken before the swap, long enough to enable
     * `+` and commit a false point the instant this tool becomes active.
     */
    fun onActivated() {
        steadinessGate.reset()
        live = null
        // Otherwise the incoming tool inherits a lock resolved against the outgoing tool's points.
        snappedIndex = null
    }
}
