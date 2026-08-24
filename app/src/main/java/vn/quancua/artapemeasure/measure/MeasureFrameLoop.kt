package vn.quancua.artapemeasure.measure

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState

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
    projector: PoseProjector,
    session: Session,
    frame: Frame,
    viewSize: IntSize,
) {
    state.tracking = frame.camera.trackingState == TrackingState.TRACKING

    if (!state.tracking || viewSize == IntSize.Zero) {
        state.live = null
        // Drop the graphics rather than leaving them frozen at stale screen coordinates: with
        // no camera pose there is no honest place to draw them, and a line that keeps sitting
        // on the floor while the phone moves reads as a measurement the app still stands by.
        state.overlay = OverlayFrame()
        return
    }

    state.anyPlaneTracked = session.getAllTrackables(Plane::class.java)
        .any { it.trackingState == TrackingState.TRACKING }

    // Before resolving, not after: the reticle's candidates are vetted by projecting them back
    // through this frame's matrices, so they have to be current.
    projector.update(frame)

    // Always the screen centre: the reticle lives there, and users aim far more precisely with
    // a centred crosshair than with a fingertip.
    val centre = Offset(viewSize.width / 2f, viewSize.height / 2f)
    val aimRay = projector.unprojectRay(centre.x, centre.y, viewSize.width, viewSize.height)
    state.live = resolveSurface(
        frame = frame,
        xPx = centre.x,
        yPx = centre.y,
        allowDepthFallback = state.depthSupported,
        aimRay = aimRay,
        onRay = { world ->
            val back = projector.project(world, viewSize.width, viewSize.height)
            back != null && (back - centre).getDistance() <= MaxOffRayPx
        },
    )

    state.noteLiveSample(
        sample = state.live,
        distanceMeters = state.live?.let {
            measureDistanceMeters(it.position, frame.camera.pose.toVec3())
        },
    )

    // Anchors drift as ARCore refines its map; re-read them, but only publish past 1 mm.
    state.refreshWorldPoints()

    state.overlay = buildOverlay(state, projector, viewSize)
}

/** Projects the current measurement into screen space and formats every label. */
internal fun buildOverlay(
    state: MeasureState,
    projector: PoseProjector,
    viewSize: IntSize,
): OverlayFrame {
    val width = viewSize.width
    val height = viewSize.height
    val world = state.worldPoints
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
                    label = formatLength(measureDistanceMeters(world[i], world[i + 1]), state.unit),
                ),
            )
        }
    }

    // The rubber band: last committed point -> the reticle, which is always screen centre.
    val live = buildLiveSegment(state, projector, width, height)

    return OverlayFrame(
        points = projected.filterNotNull(),
        committed = committed,
        live = live,
        // Only a reading steady enough to commit earns the solid reticle: the dot is a promise
        // that tapping now produces a point worth trusting.
        reticleOnSurface = state.live != null && state.liveStable,
    )
}

private fun buildLiveSegment(
    state: MeasureState,
    projector: PoseProjector,
    width: Int,
    height: Int,
): Segment2D? {
    val sample = state.live ?: return null
    val lastWorld = state.worldPoints.lastOrNull() ?: return null
    val lastScreen = projector.project(lastWorld, width, height) ?: return null
    val center = Offset(width / 2f, height / 2f)
    return Segment2D(
        start = lastScreen,
        end = center,
        midpoint = (lastScreen + center) / 2f,
        label = formatLength(measureDistanceMeters(lastWorld, sample.position), state.unit),
    )
}
