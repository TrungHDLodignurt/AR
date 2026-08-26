package vn.apero.armeasure.ar.presentation.ruler

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import vn.apero.armeasure.ar.data.arcore.SurfaceSample
import vn.apero.armeasure.ar.data.arcore.resolveSurface
import vn.apero.armeasure.ar.data.arcore.toVec3
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.domain.geometry.measureDistanceMeters
import vn.apero.armeasure.ar.presentation.camera.ArSessionState
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.formatLength

/**
 * The per-frame AR loop, kept out of the composable so the hot path is plain functions with no
 * composition machinery around them.
 *
 * Three jobs per frame: resolve what the reticle is pointing at, re-read anchors that ARCore
 * may have corrected, and rebuild the screen-space overlay.
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

internal fun onFrame(
    state: MeasureState,
    sessionState: ArSessionState,
    projector: PoseProjector,
    unit: LengthUnit,
    session: Session,
    frame: Frame,
    viewSize: IntSize,
) {
    sessionState.tracking = frame.camera.trackingState == TrackingState.TRACKING

    if (!sessionState.tracking || viewSize == IntSize.Zero) {
        state.live = null
        // Drop the graphics rather than leaving them frozen at stale screen coordinates: with
        // no camera pose there is no honest place to draw them, and a line that keeps sitting
        // on the floor while the phone moves reads as a measurement the app still stands by.
        state.overlay = OverlayFrame()
        return
    }

    sessionState.anyPlaneTracked = session.getAllTrackables(Plane::class.java)
        .any { it.trackingState == TrackingState.TRACKING }

    // Before resolving, not after: the reticle's candidates are vetted by projecting them back
    // through this frame's matrices, so they have to be current.
    projector.update(frame)

    // Always the screen centre: the reticle lives there, and users aim far more precisely with
    // a centred crosshair than with a fingertip.
    val centre = Offset(viewSize.width / 2f, viewSize.height / 2f)
    state.live = resolveAt(frame, projector, viewSize, centre, sessionState.depthSupported)

    state.noteLiveSample(
        sample = state.live,
        distanceMeters = state.live?.let {
            measureDistanceMeters(it.position, frame.camera.pose.toVec3())
        },
    )

    // Same resolution the reticle gets, but at the finger's position while an existing point
    // is being dragged. Sticky in MeasureState: a momentary miss keeps the last good hit
    // rather than making the dragged point vanish for a frame.
    state.dragTouchPosition?.let { touch ->
        state.noteDragSample(resolveAt(frame, projector, viewSize, touch, sessionState.depthSupported))
    }

    // Anchors drift as ARCore refines its map; re-read them, but only publish past 1 mm.
    state.refreshWorldPoints()

    state.overlay = buildOverlay(state, projector, viewSize, unit)
}

/**
 * Resolves the surface at an arbitrary screen point, the same way the reticle is resolved.
 *
 * Used both for the reticle (always screen centre) and, while an existing point is being
 * dragged, for the finger's current position — the two are the same problem (what is under
 * this pixel, resolved analytically for a plane hit) at a different screen location.
 */
/** Shared with [onShapeFrame] — the box/cylinder tools resolve their reticle the same way. */
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
    state: MeasureState,
    projector: PoseProjector,
    viewSize: IntSize,
    unit: LengthUnit,
): OverlayFrame {
    val width = viewSize.width
    val height = viewSize.height
    val draggingIndex = state.draggingIndex

    // A preview list, not the anchors themselves: while dragging, the point being edited shows
    // where the finger currently resolves to, so its segments' labels update live. The anchor
    // backing [MeasureState.worldPoints] is untouched until the drag is committed, so cancelling
    // (or a sample that never resolves) leaves the original point exactly where it was.
    val world = state.worldPoints.toMutableList()
    if (draggingIndex != null) {
        state.dragSample?.position?.let { world[draggingIndex] = it }
    }
    val projected = world.map { projector.project(it, width, height) }

    val committed = buildList {
        for (i in 0 until projected.size - 1) {
            // A null projection means the point is behind the camera: skip the segment rather
            // than clamping it to an edge, which would draw a line that is simply not there.
            val start = projected[i] ?: continue
            val end = projected[i + 1] ?: continue
            add(
                Segment2D(
                    start = start,
                    end = end,
                    midpoint = (start + end) / 2f,
                    label = formatLength(measureDistanceMeters(world[i], world[i + 1]), unit),
                ),
            )
        }
    }

    // The rubber band only makes sense when the reticle — not an existing point — is what is
    // moving; suppress it while dragging so the two interactions never draw on top of each other.
    val live = if (draggingIndex == null) buildLiveSegment(state, projector, width, height, unit) else null

    return OverlayFrame(
        points = projected.filterNotNull(),
        committed = committed,
        live = live,
        // Only a reading steady enough to commit earns the solid reticle: the dot is a promise
        // that tapping now produces a point worth trusting.
        reticleOnSurface = draggingIndex == null && state.live != null && state.liveStable,
        draggingPoint = draggingIndex?.let { projected.getOrNull(it) },
    )
}

private fun buildLiveSegment(
    state: MeasureState,
    projector: PoseProjector,
    width: Int,
    height: Int,
    unit: LengthUnit,
): Segment2D? {
    val sample = state.live ?: return null
    val lastWorld = state.worldPoints.lastOrNull() ?: return null
    val lastScreen = projector.project(lastWorld, width, height) ?: return null
    val center = Offset(width / 2f, height / 2f)
    return Segment2D(
        start = lastScreen,
        end = center,
        midpoint = (lastScreen + center) / 2f,
        label = formatLength(measureDistanceMeters(lastWorld, sample.position), unit),
    )
}
