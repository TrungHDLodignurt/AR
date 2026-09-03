package vn.apero.armeasure.ar.presentation.ruler

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.data.arcore.SurfaceSample
import vn.apero.armeasure.ar.data.arcore.resolveSurface
import vn.apero.armeasure.ar.data.arcore.toVec3
import vn.apero.armeasure.ar.domain.geometry.Vec3
import vn.apero.armeasure.ar.domain.geometry.hasOpenSegment
import vn.apero.armeasure.ar.domain.geometry.measureDistanceMeters
import vn.apero.armeasure.ar.domain.geometry.segmentIndexPairs
import vn.apero.armeasure.ar.domain.geometry.snapTarget
import vn.apero.armeasure.ar.domain.geometry.planeBasis
import vn.apero.armeasure.ar.presentation.camera.ArSessionFrameStream
import vn.apero.armeasure.ar.presentation.camera.components.MeasuringDotFade
import vn.apero.armeasure.ar.presentation.camera.components.PlaneDots
import vn.apero.armeasure.ar.presentation.camera.components.buildPlaneDots
import vn.apero.armeasure.ar.presentation.camera.components.buildReticleRing
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.formatLength

/**
 * The per-frame AR loop, kept out of both the composable and the ViewModel's intent path so the hot
 * path is plain functions with no composition and no coroutine machinery around them.
 *
 * Three jobs per frame: resolve what the reticle is pointing at, re-read anchors that ARCore
 * may have corrected, and rebuild the screen-space overlay. Every write lands in
 * [MeasureFrameStream] — never in [MeasureUiState] — for the reason that class's KDoc gives.
 */
/**
 * How far a resolved point may land from the pixel it was queried from, in pixels.
 *
 * Plane hits round-trip to well under a pixel even while the phone is moving, so this is not a
 * tolerance for honest noise — it is the line between a hit that lies on the aim ray and one
 * that does not. Depth-map hits that miss the ray were measured tens to nearly two hundred
 * pixels out, so the two populations are far apart and the exact threshold is not delicate.
 */
private const val MaxOffRayPx = 12f

/**
 * Radius the reticle must come within to lock onto an existing point, in dp.
 *
 * Tighter than the reference app's 45 dp (`f08.z0 = 45 × density`) on purpose: 28 dp is ~4.6 mm of
 * glass, close enough that reaching a point is easy and far enough that placing a point deliberately
 * *near* another one still works. See [snapTarget] for why there are two radii and not one.
 */
private const val SnapEnterDp = 28f

/** Radius a held snap must leave before it is dropped. The hysteresis; see [snapTarget]. */
private const val SnapReleaseDp = 45f

internal fun onMeasureFrame(
    frames: MeasureFrameStream,
    points: List<MeasuredPoint>,
    chained: Boolean,
    sessionFrames: ArSessionFrameStream,
    projector: PoseProjector,
    unit: LengthUnit,
    session: Session,
    frame: Frame,
    viewSize: IntSize,
    density: Float,
) {
    sessionFrames.tracking = frame.camera.trackingState == TrackingState.TRACKING

    if (!sessionFrames.tracking || viewSize == IntSize.Zero) {
        frames.clearForUntrackedFrame()
        return
    }

    sessionFrames.anyPlaneTracked = session.getAllTrackables(Plane::class.java)
        .any { it.trackingState == TrackingState.TRACKING }

    // Before resolving, not after: the reticle's candidates are vetted by projecting them back
    // through this frame's matrices, so they have to be current.
    projector.update(frame)

    // Always the screen centre: the reticle lives there, and users aim far more precisely with
    // a centred crosshair than with a fingertip.
    val centre = Offset(viewSize.width / 2f, viewSize.height / 2f)
    val cameraPosition = frame.camera.pose.toVec3()
    val rawSample = resolveAt(frame, projector, viewSize, centre, sessionFrames.depthSupported)

    // Snap before publishing the sample, so every consumer — the rubber band, the label, the
    // anchor created on tap — sees the snapped position rather than the raw one.
    val snapped = if (frames.draggingIndex != null) {
        // No snap while a placed point is being dragged: the reticle is not what is being aimed
        // then, and snapping one placed point onto another is a different feature.
        null
    } else {
        resolveSnap(points, chained, projector, viewSize, centre, density, frames.snappedIndex)
    }
    frames.noteSnap(snapped)
    val sample = when {
        snapped == null -> rawSample
        // Normal snap: keep the reading's plane and rotation, move it onto the existing point.
        rawSample != null -> rawSample.snappedTo(points[snapped].anchor.pose.toVec3())
        // The aim ray resolved nothing, but the reticle is locked onto a point we already have an
        // anchor for. Commit from the anchor instead of refusing: the surface hit exists only to
        // *learn* a position, and here the position is already known — better than the raw hit,
        // since the anchor has been refined ever since it was placed. Drawing the lock and then
        // rejecting the tap, which is what this used to do, told the user the app was broken.
        else -> SurfaceSample.atAnchor(points[snapped].anchor, points[snapped].source)
    }

    frames.noteLiveSample(
        sample = sample,
        distanceMeters = sample?.let { measureDistanceMeters(it.position, cameraPosition) },
    )

    // Same resolution the reticle gets, but at the finger's position while an existing point
    // is being dragged. Sticky in the frame stream: a momentary miss keeps the last good hit
    // rather than making the dragged point vanish for a frame.
    frames.dragTouchPosition?.let { touch ->
        frames.noteDragSample(resolveAt(frame, projector, viewSize, touch, sessionFrames.depthSupported))
    }

    // Anchors drift as ARCore refines its map; re-read them, but only publish past 1 mm.
    if (points.isNotEmpty()) frames.refreshWorldPoints(points.map { it.anchor.pose.toVec3() })

    frames.overlay = buildOverlay(frames, chained, projector, viewSize, unit, cameraPosition)
}

/**
 * This frame's snap decision, or null when the reticle is free.
 *
 * The unchained tool excludes the open segment's own start. Without that, the tool would helpfully
 * guide the user into measuring a point against itself and reporting 0 cm.
 */
private fun resolveSnap(
    points: List<MeasuredPoint>,
    chained: Boolean,
    projector: PoseProjector,
    viewSize: IntSize,
    centre: Offset,
    density: Float,
    currentlySnapped: Int?,
): Int? {
    if (points.isEmpty()) return null

    // Projected from the anchors themselves, not from MeasureFrameStream.worldPoints. Those two
    // lists normally agree, but worldPoints is refreshed later in the frame and behind a 1 mm
    // dead-band, so indexing one by an index resolved against the other is a hazard for no gain —
    // and the caller needs `points[i].anchor` anyway.
    val positions = points.map { point ->
        projector.project(point.anchor.pose.toVec3(), viewSize.width, viewSize.height)
            ?.let { it.x to it.y }
    }
    val excluded = if (hasOpenSegment(points.size, chained)) setOf(points.lastIndex) else emptySet()

    return snapTarget(
        positions = positions,
        reticle = centre.x to centre.y,
        currentlySnapped = currentlySnapped,
        enterPx = SnapEnterDp * density,
        releasePx = SnapReleaseDp * density,
        excluded = excluded,
    )
}

/**
 * Resolves the surface at an arbitrary screen point, the same way the reticle is resolved.
 *
 * Used both for the reticle (always screen centre) and, while an existing point is being
 * dragged, for the finger's current position — the two are the same problem (what is under
 * this pixel, resolved analytically for a plane hit) at a different screen location.
 */
/** Shared with `onShapeFrame` — the box/cylinder tools resolve their reticle the same way. */
internal fun resolveAt(
    frame: Frame,
    projector: PoseProjector,
    viewSize: IntSize,
    screen: Offset,
    allowDepthFallback: Boolean,
): SurfaceSample? {
    val aimRay = projector.unprojectRay(screen.x, screen.y, viewSize.width, viewSize.height)
    return resolveSurface(
        frame = frame,
        xPx = screen.x,
        yPx = screen.y,
        allowDepthFallback = allowDepthFallback,
        aimRay = aimRay,
        onRay = { world ->
            val back = projector.project(world, viewSize.width, viewSize.height)
            back != null && (back - screen).getDistance() <= MaxOffRayPx
        },
    )
}

/** Projects the current measurement into screen space and formats every label. */
internal fun buildOverlay(
    frames: MeasureFrameStream,
    chained: Boolean,
    projector: PoseProjector,
    viewSize: IntSize,
    unit: LengthUnit,
    cameraPosition: Vec3,
): OverlayFrame {
    val width = viewSize.width
    val height = viewSize.height
    val draggingIndex = frames.draggingIndex

    // A preview list, not the anchors themselves: while dragging, the point being edited shows
    // where the finger currently resolves to, so its segments' labels update live. The anchor
    // backing [MeasureFrameStream.worldPoints] is untouched until the drag is committed, so
    // cancelling (or a sample that never resolves) leaves the original point exactly where it was.
    val world = frames.worldPoints.toMutableList()
    if (draggingIndex != null) {
        frames.dragSample?.position?.let { world[draggingIndex] = it }
    }
    val projected = world.map { projector.project(it, width, height) }

    val committed = buildList {
        for ((i, j) in segmentIndexPairs(projected.size, chained)) {
            // A null projection means the point is behind the camera: skip the segment rather
            // than clamping it to an edge, which would draw a line that is simply not there.
            val start = projected[i] ?: continue
            val end = projected[j] ?: continue
            add(
                Segment2D(
                    start = start,
                    end = end,
                    midpoint = (start + end) / 2f,
                    label = formatLength(measureDistanceMeters(world[i], world[j]), unit),
                ),
            )
        }
    }

    // The rubber band only makes sense when the reticle — not an existing point — is what is
    // moving; suppress it while dragging so the two interactions never draw on top of each other.
    val live = if (draggingIndex == null) {
        buildLiveSegment(frames, chained, projector, width, height, unit)
    } else {
        null
    }

    // The surface affordance follows whatever the user is manipulating: the reticle normally, the
    // finger's own reading while a placed point is being dragged — that is where the attention is,
    // and the reticle is hidden in that case anyway.
    val focus = if (draggingIndex != null) frames.dragSample else frames.live
    val basis = focus?.planeNormal?.let { planeBasis(it) }
    val planeDots = if (focus != null && basis != null) {
        buildPlaneDots(
            hit = focus.position,
            basis = basis,
            cameraPosition = cameraPosition,
            projector = projector,
            viewSize = viewSize,
            // Dim once a measurement exists: the dots are an aiming aid, not part of the result.
            fade = if (frames.worldPoints.isEmpty()) 1f else MeasuringDotFade,
        )
    } else {
        PlaneDots.Empty
    }
    // No ring while dragging — drawReticle is not called then, so building one would be dead work.
    val reticleRing = if (focus != null && basis != null && draggingIndex == null) {
        buildReticleRing(focus.position, basis, projector, viewSize)
    } else {
        emptyList()
    }

    return OverlayFrame(
        points = projected.filterNotNull(),
        committed = committed,
        live = live,
        // Only a reading steady enough to commit earns the solid reticle: the dot is a promise
        // that tapping now produces a point worth trusting.
        reticleOnSurface = draggingIndex == null && frames.live != null && frames.commitReady,
        draggingPoint = draggingIndex?.let { projected.getOrNull(it) },
        planeDots = planeDots,
        reticleRing = reticleRing,
        snapped = frames.snappedIndex != null,
    )
}

private fun buildLiveSegment(
    frames: MeasureFrameStream,
    chained: Boolean,
    projector: PoseProjector,
    width: Int,
    height: Int,
    unit: LengthUnit,
): Segment2D? {
    val sample = frames.live ?: return null
    // Only while a segment is actually open. In the unchained tool an even point count means every
    // segment is closed, and a band trailing the reticle from the last point would claim a
    // connection to the next measurement that does not exist.
    if (!hasOpenSegment(frames.worldPoints.size, chained)) return null
    val lastWorld = frames.worldPoints.lastOrNull() ?: return null
    val lastScreen = projector.project(lastWorld, width, height) ?: return null
    val center = Offset(width / 2f, height / 2f)
    return Segment2D(
        start = lastScreen,
        end = center,
        midpoint = (lastScreen + center) / 2f,
        label = formatLength(measureDistanceMeters(lastWorld, sample.position), unit),
    )
}
