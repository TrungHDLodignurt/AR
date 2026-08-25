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
 * Which of the 3 taps this shape is waiting on next.
 *
 * Only the origin gets an anchor — the base and, later, the height are plain numbers measured
 * relative to it (see [ShapeMath]) and re-derived from the anchor's current pose every frame, so
 * a box never costs more than one entry in ARCore's anchor budget, no matter how many corners it
 * has.
 */
sealed class ShapePhase {
    object AwaitingOrigin : ShapePhase()
    data class SizingBase(val originAnchor: Anchor, val normal: Vec3, val basis: PlaneBasis) : ShapePhase()
    data class SizingHeight(val originAnchor: Anchor, val normal: Vec3, val basis: PlaneBasis, val base: ShapeBase) : ShapePhase()
}

/**
 * Mutable UI state for the box/cylinder tools.
 *
 * One class for both — the task description that motivated it called them "the same 3-tap
 * skeleton", and the only real difference is which of [rectangleFromPoints]/[circleFromPoints]
 * turns a live reticle reading into a base — so a `BoxMeasureState`/`CylinderMeasureState` pair
 * would have been the same state machine typed out twice. [kind] picks the math at the one call
 * site ([commitStep]) where it matters.
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
                phase = ShapePhase.SizingBase(anchor, normal, planeBasis(normal))
            }
            is ShapePhase.SizingBase -> {
                val origin = current.originAnchor.pose.toVec3()
                val base = when (kind) {
                    ShapeKind.Box -> {
                        val rect = rectangleFromPoints(origin, sample.position, current.basis)
                        ShapeBase.Rect(rect.lengthU, rect.lengthV)
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

    /** Steps back one tap: height -> base -> origin -> the previous finished shape, if any. */
    fun undo() {
        when (val current = phase) {
            is ShapePhase.SizingHeight -> phase = ShapePhase.SizingBase(current.originAnchor, current.normal, current.basis)
            is ShapePhase.SizingBase -> {
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
