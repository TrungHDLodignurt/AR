package vn.apero.armeasure.ar.presentation.shapes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.Anchor
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
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
import vn.apero.armeasure.common.domain.LengthUnit

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

/**
 * Mutable UI state for the box/cylinder tools.
 *
 * One class for both — most of the state machine is shared, and [kind] picks the few steps
 * (which phases exist for the base, which pure-math function turns a live reading into one) where
 * box and cylinder genuinely diverge, at the two call sites ([commitStep], [undo]) that need it.
 */
internal class ShapeMeasureState(val kind: ShapeKind, initialUnit: LengthUnit = LengthUnit.Metric) {

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
    var unit by mutableStateOf(initialUnit)

    /** Feeds one frame's reading into the steadiness gate. */
    fun noteLiveSample(sample: SurfaceSample?, distanceMeters: Float?) {
        live = sample
        steadinessGate.note(sample, distanceMeters)
    }

    /** Whether the bottom "+" button should be tappable right now. */
    val canCommitStep: Boolean get() = live != null && liveStable

    val canUndo: Boolean get() = shapes.isNotEmpty() || phase != ShapePhase.AwaitingOrigin

    /**
     * Advances the shape currently in progress by one tap. A no-op when the live reading is not
     * steady enough to trust — see [SteadinessGate] — since committing an unstable reading would
     * bake a false number into the shape permanently.
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
            is ShapePhase.SizingHeight -> phase = when (val base = current.base) {
                // Restore edgeU exactly as drawn — undo-then-redo must not silently reset it.
                is ShapeBase.Rect -> ShapePhase.SizingEdgeV(current.originAnchor, current.normal, base.edgeU)
                is ShapeBase.Circle -> ShapePhase.SizingCircle(current.originAnchor, current.normal, base.basis)
            }
            is ShapePhase.SizingEdgeV -> phase = ShapePhase.SizingEdgeU(current.originAnchor, current.normal)
            is ShapePhase.SizingEdgeU -> {
                current.originAnchor.detach()
                phase = ShapePhase.AwaitingOrigin
            }
            is ShapePhase.SizingCircle -> {
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
        (phase as? ShapePhase.SizingEdgeU)?.originAnchor?.detach()
        (phase as? ShapePhase.SizingEdgeV)?.originAnchor?.detach()
        (phase as? ShapePhase.SizingCircle)?.originAnchor?.detach()
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
