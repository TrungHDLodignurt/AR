package vn.apero.armeasure.ar.presentation.shapes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlin.math.abs
import vn.apero.armeasure.ar.data.arcore.HitSource
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.data.arcore.SurfaceSample
import vn.apero.armeasure.ar.data.arcore.toVec3
import vn.apero.armeasure.ar.domain.geometry.Vec3
import vn.apero.armeasure.ar.domain.geometry.circleFromPoints
import vn.apero.armeasure.ar.domain.geometry.circleRing
import vn.apero.armeasure.ar.domain.geometry.formatBoxDimensions
import vn.apero.armeasure.ar.domain.geometry.formatCylinderDimensions
import vn.apero.armeasure.ar.domain.geometry.heightAlongAxis
import vn.apero.armeasure.ar.domain.geometry.heightConstructionPlaneNormal
import vn.apero.armeasure.ar.domain.geometry.intersectRayPlane
import vn.apero.armeasure.ar.domain.geometry.labelAnchor
import vn.apero.armeasure.ar.domain.geometry.length
import vn.apero.armeasure.ar.domain.geometry.loopEdges
import vn.apero.armeasure.ar.domain.geometry.measureDistanceMeters
import vn.apero.armeasure.ar.domain.geometry.normalized
import vn.apero.armeasure.ar.domain.geometry.parallelogramCorners
import vn.apero.armeasure.ar.domain.geometry.plus
import vn.apero.armeasure.ar.domain.geometry.projectedEdgeVector
import vn.apero.armeasure.ar.domain.geometry.times
import vn.apero.armeasure.ar.presentation.ruler.Segment2D
import vn.apero.armeasure.ar.presentation.ruler.resolveAt
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.formatLength

/**
 * The per-frame AR loop for the box/cylinder tools — the same three jobs as
 * [onFrame][vn.apero.armeasure.ar.presentation.ruler.onFrame]: resolve what the reticle is pointing at,
 * re-read anchors ARCore may have corrected, and rebuild the screen-space overlay. Kept in its
 * own file (rather than folded into [ShapeMeasureState]) for the same reason
 * `MeasureFrameLoop.kt` is separate from `MeasureState.kt`: the hot path stays plain functions
 * with no composition machinery around it.
 */
internal fun onShapeFrame(
    state: ShapeMeasureState,
    projector: PoseProjector,
    session: Session,
    frame: Frame,
    viewSize: IntSize,
) {
    state.tracking = frame.camera.trackingState == TrackingState.TRACKING

    if (!state.tracking || viewSize == IntSize.Zero) {
        state.live = null
        state.overlay = ShapeOverlayFrame()
        return
    }

    state.anyPlaneTracked = session.getAllTrackables(Plane::class.java)
        .any { it.trackingState == TrackingState.TRACKING }

    projector.update(frame)

    val centre = Offset(viewSize.width / 2f, viewSize.height / 2f)
    val sample = when (val phase = state.phase) {
        // Height has nothing real to hit-test against — resolve it analytically instead. See
        // heightConstructionPlaneNormal's doc for why.
        is ShapePhase.SizingHeight -> resolveHeightSample(frame, projector, viewSize, centre, phase)
        else -> resolveAt(frame, projector, viewSize, centre, state.depthSupported)
    }

    state.noteLiveSample(
        sample = sample,
        distanceMeters = sample?.let { measureDistanceMeters(it.position, frame.camera.pose.toVec3()) },
    )

    state.overlay = buildShapeOverlay(state, projector, viewSize, frame.camera.pose.toVec3())
}

/** A safe in-plane fallback direction for [heightConstructionPlaneNormal] — see its doc. */
private fun ShapeBase.fallbackAxis(): Vec3 = when (this) {
    is ShapeBase.Rect -> edgeU.normalized()
    is ShapeBase.Circle -> basis.u
}

/**
 * Resolves the height step's live reading analytically instead of by hit-testing — see
 * [heightConstructionPlaneNormal]'s doc for why the height tap needs this instead of the real
 * plane/depth/feature-point chain [resolveAt] uses for the origin and base taps.
 *
 * Returns a [SurfaceSample] tagged [HitSource.Plane] on purpose, not a new [HitSource] case: this
 * reading is exact and driven by tracked camera pose, not noisy depth data, so it earns the same
 * "trusted instantly" treatment [SteadinessGate] already gives a real plane hit — the whole point
 * is that raising the phone reports a height immediately, not after half a second of holding
 * still. `null` only when the aim ray is (near) exactly parallel to the construction plane, which
 * in practice means aiming the phone edge-on to it.
 */
private fun resolveHeightSample(
    frame: Frame,
    projector: PoseProjector,
    viewSize: IntSize,
    screen: Offset,
    phase: ShapePhase.SizingHeight,
): SurfaceSample? {
    val origin = phase.originAnchor.pose.toVec3()
    val cameraPosition = frame.camera.pose.toVec3()
    val planeNormal = heightConstructionPlaneNormal(
        origin = origin,
        towardPosition = cameraPosition,
        axis = phase.normal,
        fallback = phase.base.fallbackAxis(),
    )
    val ray = projector.unprojectRay(screen.x, screen.y, viewSize.width, viewSize.height)
    val hit = intersectRayPlane(ray, origin, planeNormal) ?: return null
    val pose = Pose(floatArrayOf(hit.x, hit.y, hit.z), floatArrayOf(0f, 0f, 0f, 1f))
    return SurfaceSample(hit, HitSource.Plane, hitResult = null, pose = pose)
}

/** A shape's base corners in world space — shared by the committed-shape and height-preview paths. */
private fun ShapeBase.corners(origin: Vec3): List<Vec3> = when (this) {
    is ShapeBase.Rect -> parallelogramCorners(origin, edgeU, edgeV)
    is ShapeBase.Circle -> circleRing(origin, basis, radius)
}

private fun ShapeBase.dimensionLabel(height: Float, unit: LengthUnit): String = when (this) {
    is ShapeBase.Rect -> formatBoxDimensions(edgeU.length(), edgeV.length(), height, unit)
    is ShapeBase.Circle -> formatCylinderDimensions(radius, height, unit)
}

/** Projects every committed shape plus whatever is currently being sized into screen space. */
internal fun buildShapeOverlay(
    state: ShapeMeasureState,
    projector: PoseProjector,
    viewSize: IntSize,
    cameraPosition: Vec3,
): ShapeOverlayFrame {
    val width = viewSize.width
    val height = viewSize.height
    fun project(point: Vec3) = projector.project(point, width, height)

    val committedEdges = mutableListOf<Segment2D>()
    val committedHiddenEdges = mutableListOf<Segment2D>()
    val committedLabels = mutableListOf<Pair<Offset, String>>()

    state.shapes.forEach { shape ->
        val origin = shape.originAnchor.pose.toVec3()
        val baseCorners = shape.base.corners(origin)
        val topCorners = baseCorners.map { it + shape.normal * shape.height }

        loopEdges(baseCorners, topCorners, shape.normal, cameraPosition).forEach { edge ->
            val a = project(edge.a) ?: return@forEach
            val b = project(edge.b) ?: return@forEach
            val segment = Segment2D(a, b, (a + b) / 2f, "")
            if (edge.visible) committedEdges += segment else committedHiddenEdges += segment
        }

        project(labelAnchor(topCorners))?.let { anchor ->
            committedLabels += anchor to shape.base.dimensionLabel(shape.height, state.unit)
        }
    }

    val liveEdges = mutableListOf<Segment2D>()
    val sample = state.live
    if (sample != null) {
        when (val phase = state.phase) {
            ShapePhase.AwaitingOrigin -> Unit
            is ShapePhase.SizingEdgeU -> buildEdgeUSegment(phase, sample, state.unit, ::project, liveEdges)
            is ShapePhase.SizingEdgeV -> buildEdgeVEdges(phase, sample, state.unit, ::project, liveEdges)
            is ShapePhase.SizingCircle -> buildSizingCircleEdges(phase, sample, state.unit, ::project, liveEdges)
            is ShapePhase.SizingHeight -> buildSizingHeightEdges(phase, sample, state.unit, ::project, liveEdges)
        }
    }

    return ShapeOverlayFrame(
        committedEdges = committedEdges,
        committedHiddenEdges = committedHiddenEdges,
        committedLabels = committedLabels,
        liveEdges = liveEdges,
        // Only a reading steady enough to commit earns the solid reticle — same rule as the
        // point-to-point ruler's overlay.
        reticleOnSurface = sample != null && state.liveStable,
    )
}

/** Box-only tap-2 preview: a single freehand line from the origin to the live reticle — the first edge. */
private fun buildEdgeUSegment(
    phase: ShapePhase.SizingEdgeU,
    sample: SurfaceSample,
    unit: LengthUnit,
    project: (Vec3) -> Offset?,
    out: MutableList<Segment2D>,
) {
    val origin = phase.originAnchor.pose.toVec3()
    val edge = projectedEdgeVector(origin, sample.position, phase.normal)
    val a = project(origin) ?: return
    val b = project(origin + edge) ?: return
    out += Segment2D(a, b, (a + b) / 2f, formatLength(edge.length(), unit))
}

/**
 * Box-only tap-3 preview: the parallelogram [phase.edgeU][ShapePhase.SizingEdgeV.edgeU] (already
 * fixed) plus a second freehand edge growing from the origin — independent of the first, not
 * forced perpendicular to it. See [ShapeBase.Rect].
 */
private fun buildEdgeVEdges(
    phase: ShapePhase.SizingEdgeV,
    sample: SurfaceSample,
    unit: LengthUnit,
    project: (Vec3) -> Offset?,
    out: MutableList<Segment2D>,
) {
    val origin = phase.originAnchor.pose.toVec3()
    val edgeV = projectedEdgeVector(origin, sample.position, phase.normal)
    val corners = parallelogramCorners(origin, phase.edgeU, edgeV)
    for (i in corners.indices) {
        val a = project(corners[i]) ?: continue
        val b = project(corners[(i + 1) % corners.size]) ?: continue
        // Only the two edges touching the origin carry a length — the far two are the same
        // lengths mirrored, and labelling all 4 would just repeat the same numbers.
        val label = when (i) {
            0 -> formatLength(phase.edgeU.length(), unit)
            3 -> formatLength(edgeV.length(), unit)
            else -> ""
        }
        out += Segment2D(a, b, (a + b) / 2f, label)
    }
}

/** Cylinder-only tap-2 preview: the base circle growing from the origin to the live reticle. */
private fun buildSizingCircleEdges(
    phase: ShapePhase.SizingCircle,
    sample: SurfaceSample,
    unit: LengthUnit,
    project: (Vec3) -> Offset?,
    out: MutableList<Segment2D>,
) {
    val origin = phase.originAnchor.pose.toVec3()
    val circle = circleFromPoints(origin, sample.position, phase.basis)
    val ring = circle.ring
    for (i in ring.indices) {
        val a = project(ring[i]) ?: continue
        val b = project(ring[(i + 1) % ring.size]) ?: continue
        out += Segment2D(a, b, (a + b) / 2f, "")
    }
    val originScreen = project(origin)
    val edgeScreen = project(origin + phase.basis.u * circle.radius)
    if (originScreen != null && edgeScreen != null) {
        out += Segment2D(
            originScreen,
            edgeScreen,
            (originScreen + edgeScreen) / 2f,
            formatLength(2f * circle.radius, unit),
        )
    }
}

/** How many of a cylinder's ring points get a live vertical preview edge while sizing height. */
private const val LiveVerticalCount = 8

/** Tap-3 (box) / tap-3 (cylinder) preview: the base held still, with a live vertical extrusion growing to the reticle. */
private fun buildSizingHeightEdges(
    phase: ShapePhase.SizingHeight,
    sample: SurfaceSample,
    unit: LengthUnit,
    project: (Vec3) -> Offset?,
    out: MutableList<Segment2D>,
) {
    val origin = phase.originAnchor.pose.toVec3()
    val signedHeight = heightAlongAxis(origin, sample.position, phase.normal)
    val baseCorners = phase.base.corners(origin)
    val topCorners = baseCorners.map { it + phase.normal * signedHeight }

    for (i in baseCorners.indices) {
        val a = project(baseCorners[i]) ?: continue
        val b = project(baseCorners[(i + 1) % baseCorners.size]) ?: continue
        out += Segment2D(a, b, (a + b) / 2f, "")
    }

    val n = baseCorners.size
    val step = maxOf(1, n / LiveVerticalCount)
    var labelled = false
    var i = 0
    while (i < n) {
        val a = project(baseCorners[i])
        val b = project(topCorners[i])
        if (a != null && b != null) {
            val label = if (!labelled) {
                labelled = true
                formatLength(abs(signedHeight), unit)
            } else {
                ""
            }
            out += Segment2D(a, b, (a + b) / 2f, label)
        }
        i += step
    }
}
