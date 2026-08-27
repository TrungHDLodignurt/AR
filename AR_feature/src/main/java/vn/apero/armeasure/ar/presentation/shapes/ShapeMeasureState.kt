package vn.apero.armeasure.ar.presentation.shapes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.Anchor
import com.google.ar.core.Session
import vn.apero.armeasure.ar.data.arcore.SurfaceSample
import vn.apero.armeasure.ar.data.arcore.toVec3
import vn.apero.armeasure.ar.domain.geometry.PlaneBasis
import vn.apero.armeasure.ar.domain.geometry.Vec3
import vn.apero.armeasure.ar.domain.geometry.circleFromPoints
import vn.apero.armeasure.ar.domain.geometry.heightAlongAxis
import vn.apero.armeasure.ar.domain.geometry.normalized
import vn.apero.armeasure.ar.domain.geometry.planeBasis
import vn.apero.armeasure.ar.domain.geometry.projectedEdgeVector
import vn.apero.armeasure.ar.domain.steadiness.SteadinessGate
import vn.apero.armeasure.common.domain.UndoRedoStack

/** Which shape a [ShapeMeasureState] is building. Box and Cylinder share every state transition below — only which pure-math functions turn the live reading into a base differ. */
internal enum class ShapeKind(val label: String) { Box("Box"), Cylinder("Cylinder") }

/**
 * A committed base: a box's two independently drawn edge vectors (see [parallelogramCorners] —
 * NOT forced to a right angle, a box is whatever parallelogram the two actually drawn edges
 * describe), or a circle's radius plus the arbitrary basis its ring is drawn in.
 */
internal sealed class ShapeBase {
    data class Rect(val edgeU: Vec3, val edgeV: Vec3) : ShapeBase()
    data class Circle(val radius: Float, val basis: PlaneBasis) : ShapeBase()
}

/** One finished box or cylinder: an anchored origin plus everything needed to redraw it every frame. */
internal class MeasuredShape(
    val kind: ShapeKind,
    val originAnchor: Anchor,
    val normal: Vec3,
    val base: ShapeBase,
    val height: Float,
)

/**
 * Which tap this shape is waiting on next.
 *
 * Only the origin gets an anchor — every later step is a plain number (or vector) measured
 * relative to it (see [ShapeMath]) and re-derived from the anchor's current pose every frame, so
 * a box never costs more than one entry in ARCore's anchor budget, no matter how many corners it
 * has.
 *
 * Box and Cylinder diverge for the base: a circle is rotationally symmetric, so it has nothing to
 * orient — one tap (center to edge) is enough ([SizingCircle]). A box's two sides are each
 * something the user actually measured, so both are drawn freehand, independently
 * ([SizingEdgeU], then [SizingEdgeV]) — the base is whatever parallelogram those two edges
 * describe, not corrected to a right angle a fixed axis would have implied instead.
 */
internal sealed class ShapePhase {
    object AwaitingOrigin : ShapePhase()
    /** Box only: drawing the first edge's direction and length freehand. */
    data class SizingEdgeU(val originAnchor: Anchor, val normal: Vec3) : ShapePhase()
    /** Box only: drawing the second edge, independent of the first — see [ShapeBase.Rect]. */
    data class SizingEdgeV(val originAnchor: Anchor, val normal: Vec3, val edgeU: Vec3) : ShapePhase()
    /** Cylinder only: a circle has no edge to draw — one tap sets center-to-edge, i.e. the radius. */
    data class SizingCircle(val originAnchor: Anchor, val normal: Vec3, val basis: PlaneBasis) : ShapePhase()
    data class SizingHeight(val originAnchor: Anchor, val normal: Vec3, val base: ShapeBase) : ShapePhase()
}

/** The origin anchor a live (not yet finished) [ShapePhase] is built on, or null for [ShapePhase.AwaitingOrigin]. */
private fun ShapePhase.originAnchorOrNull(): Anchor? = when (this) {
    is ShapePhase.SizingEdgeU -> originAnchor
    is ShapePhase.SizingEdgeV -> originAnchor
    is ShapePhase.SizingCircle -> originAnchor
    is ShapePhase.SizingHeight -> originAnchor
    ShapePhase.AwaitingOrigin -> null
}

/**
 * One undo/redo entry for [ShapeMeasureState]: either a mid-shape step (stepping back from
 * [ShapePhase.SizingEdgeV] to [ShapePhase.SizingEdgeU], say — the exact previous [ShapePhase]
 * instance, so a value like `edgeU` that undo must not reset is simply restored, not recomputed)
 * or a whole finished shape being undone off the end of [ShapeMeasureState.shapes].
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
 * Mutable UI state for the box/cylinder tools.
 *
 * One class for both — most of the state machine is shared, and [kind] picks the few steps
 * (which phases exist for the base, which pure-math function turns a live reading into one) where
 * box and cylinder genuinely diverge, at the two call sites ([commitStep], [undo]) that need it.
 */
internal class ShapeMeasureState(val kind: ShapeKind) {

    val shapes = mutableStateListOf<MeasuredShape>()

    var phase by mutableStateOf<ShapePhase>(ShapePhase.AwaitingOrigin)
        private set

    /** Live surface reading under the reticle, or null when the reticle is off-surface. */
    var live by mutableStateOf<SurfaceSample?>(null)

    private val steadinessGate = SteadinessGate()

    /** Whether [live] has held still long enough to commit — see [SteadinessGate]. */
    val liveStable: Boolean get() = steadinessGate.stable

    var overlay by mutableStateOf(ShapeOverlayFrame())

    /** Feeds one frame's reading into the steadiness gate. */
    fun noteLiveSample(sample: SurfaceSample?, distanceMeters: Float?) {
        live = sample
        steadinessGate.note(sample, distanceMeters)
    }

    /** Whether the bottom "+" button should be tappable right now. */
    val canCommitStep: Boolean get() = live != null && liveStable

    val canUndo: Boolean get() = shapes.isNotEmpty() || phase != ShapePhase.AwaitingOrigin

    /**
     * Deferred-detach redo history — same reasoning as `MeasureState`'s: a redone shape must read
     * the exact same anchor, not a re-anchored pose that would drift independently.
     *
     * [isAnchorOrphaned] guards the actual detach: stepping `SizingHeight -> SizingEdgeV` reuses
     * the *same* origin anchor in the phase it lands on, so evicting the old [ShapeStep] entry
     * must not detach an anchor still live elsewhere.
     */
    private val undoRedo = UndoRedoStack<ShapeStep>(onEvict = { step ->
        step.originAnchorOrNull()?.let { anchor -> if (isAnchorOrphaned(anchor)) anchor.detach() }
    })
    val canRedo: Boolean get() = undoRedo.canRedo

    /** True once [anchor] is referenced by nothing live: not the current phase's origin, not held by any finished shape, and not sitting in the undo/redo history either. Only then is it safe to detach. */
    private fun isAnchorOrphaned(anchor: Anchor): Boolean {
        if (phase.originAnchorOrNull() === anchor) return false
        if (shapes.any { it.originAnchor === anchor }) return false
        if (undoRedo.any { it.originAnchorOrNull() === anchor }) return false
        return true
    }

    /**
     * Advances the shape currently in progress by one tap. A no-op when the live reading is not
     * steady enough to trust — see [SteadinessGate] — since committing an unstable reading would
     * bake a false number into the shape permanently.
     */
    fun commitStep(session: Session) {
        val sample = live ?: return
        if (!liveStable) return
        // A new step is a new committed action — any pending redo is now stale.
        undoRedo.dropRedo()
        when (val current = phase) {
            is ShapePhase.AwaitingOrigin -> {
                // No plane under the tap (a depth/feature-point-only origin) still starts a
                // shape — world-up is the least-wrong assumption for "which way is normal",
                // since box/cylinder measuring is done standing on an approximately horizontal
                // surface far more often than not.
                val normal = (sample.planeNormal ?: Vec3(0f, 1f, 0f)).normalized()
                val anchor = sample.commit(session)
                phase = when (kind) {
                    ShapeKind.Box -> ShapePhase.SizingEdgeU(anchor, normal)
                    ShapeKind.Cylinder -> ShapePhase.SizingCircle(anchor, normal, planeBasis(normal))
                }
            }
            is ShapePhase.SizingEdgeU -> {
                val origin = current.originAnchor.pose.toVec3()
                val edgeU = projectedEdgeVector(origin, sample.position, current.normal)
                phase = ShapePhase.SizingEdgeV(current.originAnchor, current.normal, edgeU)
            }
            is ShapePhase.SizingEdgeV -> {
                val origin = current.originAnchor.pose.toVec3()
                val edgeV = projectedEdgeVector(origin, sample.position, current.normal)
                phase = ShapePhase.SizingHeight(current.originAnchor, current.normal, ShapeBase.Rect(current.edgeU, edgeV))
            }
            is ShapePhase.SizingCircle -> {
                val origin = current.originAnchor.pose.toVec3()
                val circle = circleFromPoints(origin, sample.position, current.basis)
                phase = ShapePhase.SizingHeight(current.originAnchor, current.normal, ShapeBase.Circle(circle.radius, current.basis))
            }
            is ShapePhase.SizingHeight -> {
                val origin = current.originAnchor.pose.toVec3()
                val height = heightAlongAxis(origin, sample.position, current.normal)
                shapes.add(MeasuredShape(kind, current.originAnchor, current.normal, current.base, height))
                phase = ShapePhase.AwaitingOrigin
            }
        }
    }

    /**
     * Steps back one tap: height -> the base step(s) -> origin -> the previous finished shape, if
     * any.
     */
    fun undo() {
        when (val current = phase) {
            is ShapePhase.SizingHeight -> {
                undoRedo.pushRedo(ShapeStep.Progress(current))
                phase = when (val base = current.base) {
                    // Restore edgeU exactly as drawn — undo-then-redo must not silently reset it.
                    is ShapeBase.Rect -> ShapePhase.SizingEdgeV(current.originAnchor, current.normal, base.edgeU)
                    is ShapeBase.Circle -> ShapePhase.SizingCircle(current.originAnchor, current.normal, base.basis)
                }
            }
            is ShapePhase.SizingEdgeV -> {
                undoRedo.pushRedo(ShapeStep.Progress(current))
                phase = ShapePhase.SizingEdgeU(current.originAnchor, current.normal)
            }
            is ShapePhase.SizingEdgeU -> {
                // No detach — deferred until this step is actually evicted from the redo history.
                undoRedo.pushRedo(ShapeStep.Progress(current))
                phase = ShapePhase.AwaitingOrigin
            }
            is ShapePhase.SizingCircle -> {
                undoRedo.pushRedo(ShapeStep.Progress(current))
                phase = ShapePhase.AwaitingOrigin
            }
            ShapePhase.AwaitingOrigin -> {
                val last = shapes.removeLastOrNull() ?: return
                undoRedo.pushRedo(ShapeStep.Finished(last))
            }
        }
    }

    /** Restores exactly what [undo] last removed — the same anchor, so the same number. */
    fun redo() {
        val step = undoRedo.popRedo() ?: return
        phase = when (step) {
            is ShapeStep.Progress -> step.phase
            is ShapeStep.Finished -> {
                shapes.add(step.shape)
                ShapePhase.AwaitingOrigin
            }
        }
    }

    fun clear() {
        detachAllHeldAnchors()
        phase = ShapePhase.AwaitingOrigin
        shapes.clear()
        overlay = ShapeOverlayFrame()
    }

    /**
     * Detaches every anchor this state still holds — the in-progress phase's origin, every
     * finished shape's origin, and anything sitting in the undo/redo history. Call from a
     * `DisposableEffect` on screen dispose: today this is cleaned up incidentally by ARCore
     * session teardown, but once the session becomes long-lived and shared across tabs, nothing
     * else will do this.
     */
    fun releaseAll() {
        detachAllHeldAnchors()
    }

    /**
     * Collects every distinct anchor referenced anywhere in this state and detaches each exactly
     * once — bypasses the [undoRedo] `onEvict`/[isAnchorOrphaned] path entirely, since a full wipe
     * makes every anchor orphaned simultaneously rather than one at a time.
     */
    private fun detachAllHeldAnchors() {
        val anchors = buildSet {
            phase.originAnchorOrNull()?.let { add(it) }
            shapes.forEach { add(it.originAnchor) }
            undoRedo.drainWithoutEviction().forEach { step -> step.originAnchorOrNull()?.let { add(it) } }
        }
        anchors.forEach { it.detach() }
    }

    /**
     * Resets the steadiness gate and clears the live reading. Call when this tool becomes the
     * active one after a swap — see `MeasureState.onActivated`'s doc for the exact bug this
     * closes: a gate that still held pre-swap samples could read `liveStable == true` on a stale
     * reading for one frame, long enough to enable `+` and commit a false point. Does NOT touch
     * [phase] or [shapes] — a half-drawn shape must survive a tool swap intact.
     */
    fun onActivated() {
        steadinessGate.reset()
        live = null
    }
}
