package vn.quancua.artapemeasure.measure

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import kotlin.math.abs

/**
 * The per-frame AR loop for the box/cylinder tools — the same three jobs as
 * [onFrame][vn.quancua.artapemeasure.measure.onFrame]: resolve what the reticle is pointing at,
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
    val sample = resolveAt(frame, projector, viewSize, centre, state.depthSupported)

    state.noteLiveSample(
        sample = sample,
        distanceMeters = sample?.let { measureDistanceMeters(it.position, frame.camera.pose.toVec3()) },
    )

    state.overlay = buildShapeOverlay(state, projector, viewSize)
}

/** Projects every committed shape plus whatever is currently being sized into screen space. */
internal fun buildShapeOverlay(state: ShapeMeasureState, projector: PoseProjector, viewSize: IntSize): ShapeOverlayFrame {
    val width = viewSize.width
    val height = viewSize.height
    fun project(point: Vec3) = projector.project(point, width, height)

    val committedEdges = mutableListOf<Segment2D>()
    val committedLabels = mutableListOf<Pair<Offset, String>>()

    state.shapes.forEach { shape ->
        val origin = shape.originAnchor.pose.toVec3()
        val baseCorners = when (val base = shape.base) {
            is ShapeBase.Rect -> rectangleCorners(origin, shape.basis, base.lengthU, base.lengthV)
            is ShapeBase.Circle -> circleRing(origin, shape.basis, base.radius)
        }
        val topCorners = baseCorners.map { it + shape.normal * shape.height }

        loopEdges(baseCorners, topCorners).forEach { edge ->
            val a = project(edge.a) ?: return@forEach
            val b = project(edge.b) ?: return@forEach
            committedEdges += Segment2D(a, b, (a + b) / 2f, "")
        }

        project(labelAnchor(topCorners))?.let { anchor ->
            val label = when (val base = shape.base) {
                is ShapeBase.Rect -> formatBoxDimensions(base.lengthU, base.lengthV, shape.height, state.unit)
                is ShapeBase.Circle -> formatCylinderDimensions(base.radius, shape.height, state.unit)
            }
            committedLabels += anchor to label
        }
    }

    val liveEdges = mutableListOf<Segment2D>()
    val sample = state.live
    if (sample != null) {
        when (val phase = state.phase) {
            ShapePhase.AwaitingOrigin -> Unit
            is ShapePhase.SizingBase -> buildSizingBaseEdges(state.kind, phase, sample, state.unit, ::project, liveEdges)
            is ShapePhase.SizingHeight -> buildSizingHeightEdges(phase, sample, state.unit, ::project, liveEdges)
        }
    }

    return ShapeOverlayFrame(
        committedEdges = committedEdges,
        committedLabels = committedLabels,
        liveEdges = liveEdges,
        // Only a reading steady enough to commit earns the solid reticle — same rule as the
        // point-to-point ruler's overlay.
        reticleOnSurface = sample != null && state.liveStable,
    )
}

/** Tap-2 preview: the rectangle/circle growing from the origin to the live reticle position. */
private fun buildSizingBaseEdges(
    kind: ShapeKind,
    phase: ShapePhase.SizingBase,
    sample: SurfaceSample,
    unit: LengthUnit,
    project: (Vec3) -> Offset?,
    out: MutableList<Segment2D>,
) {
    val origin = phase.originAnchor.pose.toVec3()
    when (kind) {
        ShapeKind.Box -> {
            val rect = rectangleFromPoints(origin, sample.position, phase.basis)
            val corners = rect.corners
            for (i in corners.indices) {
                val a = project(corners[i]) ?: continue
                val b = project(corners[(i + 1) % corners.size]) ?: continue
                // Only the two edges touching the origin carry a length — the far two are the
                // same lengths mirrored, and labelling all 4 would just repeat the same numbers.
                val label = when (i) {
                    0 -> formatLength(abs(rect.lengthU), unit)
                    3 -> formatLength(abs(rect.lengthV), unit)
                    else -> ""
                }
                out += Segment2D(a, b, (a + b) / 2f, label)
            }
        }
        ShapeKind.Cylinder -> {
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
    }
}

/** How many of a cylinder's ring points get a live vertical preview edge while sizing height. */
private const val LiveVerticalCount = 8

/** Tap-3 preview: the base held still, with a live vertical extrusion growing to the reticle. */
private fun buildSizingHeightEdges(
    phase: ShapePhase.SizingHeight,
    sample: SurfaceSample,
    unit: LengthUnit,
    project: (Vec3) -> Offset?,
    out: MutableList<Segment2D>,
) {
    val origin = phase.originAnchor.pose.toVec3()
    val signedHeight = heightAlongAxis(origin, sample.position, phase.normal)
    val baseCorners = when (val base = phase.base) {
        is ShapeBase.Rect -> rectangleCorners(origin, phase.basis, base.lengthU, base.lengthV)
        is ShapeBase.Circle -> circleRing(origin, phase.basis, base.radius)
    }
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
