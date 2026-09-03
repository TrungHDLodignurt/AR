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
import vn.apero.armeasure.ar.presentation.camera.ArSessionFrameStream
import vn.apero.armeasure.ar.presentation.ruler.Segment2D
import vn.apero.armeasure.ar.presentation.ruler.resolveAt
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.formatLength
import vn.apero.armeasure.ar.domain.geometry.planeBasis
import vn.apero.armeasure.ar.presentation.camera.components.MeasuringDotFade
import vn.apero.armeasure.ar.presentation.camera.components.PlaneDots
import vn.apero.armeasure.ar.presentation.camera.components.buildPlaneDots
import vn.apero.armeasure.ar.presentation.camera.components.buildReticleRing
import vn.apero.armeasure.ar.presentation.shapes.components.ShapeOverlayFrame

/**
 * The per-frame AR loop for the box/cylinder tools — the same three jobs as
 * [onMeasureFrame][vn.apero.armeasure.ar.presentation.ruler.onMeasureFrame]: resolve what the reticle
 * is pointing at, re-read anchors ARCore may have corrected, and rebuild the screen-space overlay.
 *
 * Kept out of both the composable and [ShapeMeasureViewModel]'s intent path for the same reason
 * `MeasureFrameLoop.kt` is: the hot path stays plain functions, with no composition and no coroutine
 * machinery around them, and every write lands in [ShapeFrameStream] rather than in [ShapeUiState].
 */
internal fun onShapeFrame(
    frames: ShapeFrameStream,
    phase: ShapePhase,
    shapes: List<MeasuredShape>,
    sessionFrames: ArSessionFrameStream,
    projector: PoseProjector,
    unit: LengthUnit,
    session: Session,
    frame: Frame,
    viewSize: IntSize,
) {
    sessionFrames.tracking = frame.camera.trackingState == TrackingState.TRACKING

    if (!sessionFrames.tracking || viewSize == IntSize.Zero) {
        frames.clearForUntrackedFrame()
        return
    }

    sessionFrames.anyPlaneTracked = session.getAllTrackables(Plane::class.java)
        .any { it.trackingState == TrackingState.TRACKING }

    projector.update(frame)

    val centre = Offset(viewSize.width / 2f, viewSize.height / 2f)
    val sample = when (phase) {
        // Height has nothing real to hit-test against — resolve it analytically instead. See
        // heightConstructionPlaneNormal's doc for why.
        is ShapePhase.SizingHeight -> resolveHeightSample(frame, projector, viewSize, centre, phase)
        else -> resolveAt(frame, projector, viewSize, centre, sessionFrames.depthSupported)
    }

    frames.noteLiveSample(
        sample = sample,
        distanceMeters = sample?.let { measureDistanceMeters(it.position, frame.camera.pose.toVec3()) },
    )

    frames.overlay = buildShapeOverlay(
        frames = frames,
        phase = phase,
        shapes = shapes,
        projector = projector,
        viewSize = viewSize,
        cameraPosition = frame.camera.pose.toVec3(),
        unit = unit,
    )
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
    frames: ShapeFrameStream,
    phase: ShapePhase,
    shapes: List<MeasuredShape>,
    projector: PoseProjector,
    viewSize: IntSize,
    cameraPosition: Vec3,
    unit: LengthUnit,
): ShapeOverlayFrame {
    val width = viewSize.width
    val height = viewSize.height
    fun project(point: Vec3) = projector.project(point, width, height)

    val committedEdges = mutableListOf<Segment2D>()
    val committedHiddenEdges = mutableListOf<Segment2D>()
    val committedLabels = mutableListOf<Pair<Offset, String>>()

    shapes.forEach { shape ->
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
            committedLabels += anchor to shape.base.dimensionLabel(shape.height, unit)
        }
    }

    val liveEdges = mutableListOf<Segment2D>()
    val sample = frames.live
    if (sample != null) {
        when (phase) {
            ShapePhase.AwaitingOrigin -> Unit
            is ShapePhase.SizingEdgeU -> buildEdgeUSegment(phase, sample, unit, ::project, liveEdges)
            is ShapePhase.SizingEdgeV -> buildEdgeVEdges(phase, sample, unit, ::project, liveEdges)
            is ShapePhase.SizingCircle -> buildSizingCircleEdges(phase, sample, unit, ::project, liveEdges)
            is ShapePhase.SizingHeight -> buildSizingHeightEdges(phase, sample, unit, ::project, liveEdges)
        }
    }

    // Same surface affordance as the ruler. Absent during SizingHeight by construction rather than
    // by a special case: that phase resolves against an analytic construction plane, not a tracked
    // Plane, so it carries no planeNormal — and the height axis is not a surface to paint anyway.
    val basis = sample?.planeNormal?.let { planeBasis(it) }
    val planeDots = if (sample != null && basis != null) {
        buildPlaneDots(
            hit = sample.position,
            basis = basis,
            cameraPosition = cameraPosition,
            projector = projector,
            viewSize = viewSize,
            // Dim as soon as there is geometry to read — a finished shape or one under construction.
            fade = if (shapes.isEmpty() && phase == ShapePhase.AwaitingOrigin) 1f else MeasuringDotFade,
        )
    } else {
        PlaneDots.Empty
    }

    return ShapeOverlayFrame(
        committedEdges = committedEdges,
        committedHiddenEdges = committedHiddenEdges,
        committedLabels = committedLabels,
        liveEdges = liveEdges,
        // Only a reading steady enough to commit earns the solid reticle — same rule as the
        // point-to-point ruler's overlay.
        reticleOnSurface = sample != null && frames.liveStable,
        planeDots = planeDots,
        reticleRing = if (sample != null && basis != null) {
            buildReticleRing(sample.position, basis, projector, viewSize)
        } else {
            emptyList()
        },
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
 * Box preview while the **third** tap is being aimed: the fixed
 * [phase.edgeU][ShapePhase.SizingEdgeV.edgeU] and the live second edge, both drawn from the shared
 * origin corner — and nothing else.
 *
 * It deliberately does **not** close the parallelogram yet. It used to, and that was wrong: after
 * only two taps the user has picked one edge, so drawing all four sides claimed the base was
 * already decided and left a whole quadrilateral flapping around the screen while they aimed. It
 * also hid which corner the reticle actually controlled, since three of the four corners moved at
 * once.
 *
 * Two taps is one edge; three taps is two edges, which is the first moment a parallelogram is
 * genuinely determined. So the shape closes exactly then — in [buildSizingHeightEdges], the phase
 * the third tap moves to — and never before.
 *
 * The three taps read as a **chain**, the same gesture as the distance-chain tool: corner, along
 * one side, then turn at that corner and go along the next. So the second edge grows from the end
 * of the first, not from the origin — a second edge sprouting back at tap 1 while the user is
 * standing at tap 2 is not how anyone traces a box.
 *
 * Neither edge is forced perpendicular to the other. See [ShapeBase.Rect] for why the base is
 * whatever parallelogram the two hand-drawn edges describe rather than a corrected right angle.
 */
private fun buildEdgeVEdges(
    phase: ShapePhase.SizingEdgeV,
    sample: SurfaceSample,
    unit: LengthUnit,
    project: (Vec3) -> Offset?,
    out: MutableList<Segment2D>,
) {
    val origin = phase.originAnchor.pose.toVec3()
    val turn = origin + phase.edgeU
    val edgeV = projectedEdgeVector(turn, sample.position, phase.normal)

    val a = project(origin) ?: return
    val b = project(turn) ?: return
    // Both edges carry their own length: unlike the closed parallelogram this replaced, neither
    // number is a mirror of another edge on screen, so both are worth reading.
    out += Segment2D(a, b, (a + b) / 2f, formatLength(phase.edgeU.length(), unit))

    val c = project(turn + edgeV) ?: return
    out += Segment2D(b, c, (b + c) / 2f, formatLength(edgeV.length(), unit))
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
