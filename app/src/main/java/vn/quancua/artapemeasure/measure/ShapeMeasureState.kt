package vn.quancua.artapemeasure.measure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.Anchor
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason

/** Which shape a [ShapeMeasureState] is building. Box and Cylinder share every state transition below — only which pure-math functions turn the live reading into a base differ. */
enum class ShapeKind(val label: String) { Box("Box"), Cylinder("Cylinder") }

/** A committed base: a rectangle's two edge lengths, or a circle's radius. */
sealed class ShapeBase {
    data class Rect(val lengthU: Float, val lengthV: Float) : ShapeBase()
    data class Circle(val radius: Float) : ShapeBase()
}

/** One finished box or cylinder: an anchored origin plus everything needed to redraw it every frame. */
class MeasuredShape(
    val kind: ShapeKind,
    val originAnchor: Anchor,
    val normal: Vec3,
    val basis: PlaneBasis,
    val base: ShapeBase,
    val height: Float,
)

/**
 * Which tap this shape is waiting on next.
 *
 * Only the origin gets an anchor — every later step is a plain number measured relative to it
 * (see [ShapeMath]) and re-derived from the anchor's current pose every frame, so a box never
 * costs more than one entry in ARCore's anchor budget, no matter how many corners it has.
 *
 * Box and Cylinder diverge for exactly one step: a circle has no orientation to get wrong, so its
 * base is one tap (center to edge, radius). A rectangle does — [SizingEdge] is the box-only step
 * that lets the user draw the first edge's own direction, rather than the box being forced onto a
 * fixed world axis unrelated to how the real object sits in the room. See [drawnEdgeBasis].
 */
sealed class ShapePhase {
    object AwaitingOrigin : ShapePhase()
    /** Box only: the user is dragging out the first edge's direction and length. */
    data class SizingEdge(val originAnchor: Anchor, val normal: Vec3) : ShapePhase()
    /**
     * Box: [basis] is already fixed to the edge drawn in [SizingEdge] and [lengthU] to its
     * length — only the perpendicular width is still live. Cylinder: [basis] is an arbitrary
     * (rotationally irrelevant) plane basis and [lengthU] is unused.
     */
    data class SizingBase(val originAnchor: Anchor, val normal: Vec3, val basis: PlaneBasis, val lengthU: Float = 0f) : ShapePhase()
    data class SizingHeight(val originAnchor: Anchor, val normal: Vec3, val basis: PlaneBasis, val base: ShapeBase) : ShapePhase()
}

/**
 * Mutable UI state for the box/cylinder tools.
 *
 * One class for both — cylinder is a strict subset of box's state machine (it skips
 * [ShapePhase.SizingEdge] since a circle has no edge to draw), and everywhere else the only real
 * difference is which of [drawnEdgeBasis]/[circleFromPoints] turns a live reticle reading into a
 * base — so a `BoxMeasureState`/`CylinderMeasureState` pair would mostly be the same state
 * machine typed out twice. [kind] picks the math and the phase transitions at the two call sites
 * ([commitStep], [undo]) where they diverge.
 */
class ShapeMeasureState(val kind: ShapeKind) {

    val shapes = mutableStateListOf<MeasuredShape>()

    var phase by mutableStateOf<ShapePhase>(ShapePhase.AwaitingOrigin)
        private set

    /** Live surface reading under the reticle, or null when the reticle is off-surface. */
    var live by mutableStateOf<SurfaceSample?>(null)

    private val steadinessGate = SteadinessGate()

    /** Whether [live] has held still long enough to commit — see [SteadinessGate]. */
    val liveStable: Boolean get() = steadinessGate.stable

    var overlay by mutableStateOf(ShapeOverlayFrame())

    var cameraReady by mutableStateOf(false)
    var tracking by mutableStateOf(false)
    var anyPlaneTracked by mutableStateOf(false)
    var depthSupported by mutableStateOf(false)
    var trackingFailure by mutableStateOf<TrackingFailureReason?>(null)
    var unit by mutableStateOf(LengthUnit.Metric)

    /** Feeds one frame's reading into the steadiness gate. */
    fun noteLiveSample(sample: SurfaceSample?, distanceMeters: Float?) {
        live = sample
        steadinessGate.note(sample, distanceMeters)
    }

    /** Whether the bottom "+" button should be tappable right now. */
    val canCommitStep: Boolean get() = live != null && liveStable

    val canUndo: Boolean get() = shapes.isNotEmpty() || phase != ShapePhase.AwaitingOrigin

    /**
     * Advances the shape currently in progress by one tap: places the origin, fixes the base,
     * or fixes the height and files the finished shape into [shapes]. A no-op when the live
     * reading is not steady enough to trust — see [SteadinessGate] — since committing an
     * unstable reading would bake a false number into the shape permanently.
     */
    fun commitStep(session: Session) {
        val sample = live ?: return
        if (!liveStable) return
        when (val current = phase) {
            is ShapePhase.AwaitingOrigin -> {
                // No plane under the tap (a depth/feature-point-only origin) still starts a
                // shape — world-up is the least-wrong assumption for "which way is normal",
                // since box/cylinder measuring is done standing on an approximately horizontal
                // surface far more often than not.
                val normal = (sample.planeNormal ?: Vec3(0f, 1f, 0f)).normalized()
                val anchor = sample.commit(session)
                phase = when (kind) {
                    // Box needs its first edge drawn before a basis means anything.
                    ShapeKind.Box -> ShapePhase.SizingEdge(anchor, normal)
                    // A circle has no edge to draw — planeBasis's arbitrary axes are fine since
                    // nothing about a circle's rendering depends on which way they point.
                    ShapeKind.Cylinder -> ShapePhase.SizingBase(anchor, normal, planeBasis(normal))
                }
            }
            is ShapePhase.SizingEdge -> {
                val origin = current.originAnchor.pose.toVec3()
                val basis = drawnEdgeBasis(origin, sample.position, current.normal)
                val lengthU = heightAlongAxis(origin, sample.position, basis.u)
                phase = ShapePhase.SizingBase(current.originAnchor, current.normal, basis, lengthU)
            }
            is ShapePhase.SizingBase -> {
                val origin = current.originAnchor.pose.toVec3()
                val base = when (kind) {
                    ShapeKind.Box -> {
                        // U is already fixed from SizingEdge — only V, the perpendicular width,
                        // comes from this tap's live reading.
                        val lengthV = heightAlongAxis(origin, sample.position, current.basis.v)
                        ShapeBase.Rect(current.lengthU, lengthV)
                    }
                    ShapeKind.Cylinder -> {
                        val circle = circleFromPoints(origin, sample.position, current.basis)
                        ShapeBase.Circle(circle.radius)
                    }
                }
                phase = ShapePhase.SizingHeight(current.originAnchor, current.normal, current.basis, base)
            }
            is ShapePhase.SizingHeight -> {
                val origin = current.originAnchor.pose.toVec3()
                val height = heightAlongAxis(origin, sample.position, current.normal)
                shapes.add(MeasuredShape(kind, current.originAnchor, current.normal, current.basis, current.base, height))
                phase = ShapePhase.AwaitingOrigin
            }
        }
    }

    /**
     * Steps back one tap: height -> base -> (box only: edge) -> origin -> the previous finished
     * shape, if any.
     */
    fun undo() {
        when (val current = phase) {
            is ShapePhase.SizingHeight -> {
                // Restore the width step exactly as it was — lengthU (box only) must survive
                // the trip back, or undo-then-redo would silently reset the drawn edge's length.
                val lengthU = (current.base as? ShapeBase.Rect)?.lengthU ?: 0f
                phase = ShapePhase.SizingBase(current.originAnchor, current.normal, current.basis, lengthU)
            }
            is ShapePhase.SizingBase -> phase = when (kind) {
                // Box has an edge-drawing step to go back to; cylinder does not.
                ShapeKind.Box -> ShapePhase.SizingEdge(current.originAnchor, current.normal)
                ShapeKind.Cylinder -> {
                    current.originAnchor.detach()
                    ShapePhase.AwaitingOrigin
                }
            }
            is ShapePhase.SizingEdge -> {
                current.originAnchor.detach()
                phase = ShapePhase.AwaitingOrigin
            }
            ShapePhase.AwaitingOrigin -> {
                val last = shapes.removeLastOrNull() ?: return
                last.originAnchor.detach()
            }
        }
    }

    fun clear() {
        (phase as? ShapePhase.SizingEdge)?.originAnchor?.detach()
        (phase as? ShapePhase.SizingBase)?.originAnchor?.detach()
        (phase as? ShapePhase.SizingHeight)?.originAnchor?.detach()
        phase = ShapePhase.AwaitingOrigin
        // Detaching matters: an undetached anchor keeps costing ARCore tracking work every
        // frame, same reasoning as MeasureState.clear.
        shapes.forEach { it.originAnchor.detach() }
        shapes.clear()
        overlay = ShapeOverlayFrame()
    }

    fun toggleUnit() {
        unit = if (unit == LengthUnit.Metric) LengthUnit.Imperial else LengthUnit.Metric
    }
}
