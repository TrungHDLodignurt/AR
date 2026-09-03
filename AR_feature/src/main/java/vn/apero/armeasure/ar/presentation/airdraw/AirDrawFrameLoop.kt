package vn.apero.armeasure.ar.presentation.airdraw

import androidx.compose.ui.unit.IntSize
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.data.arcore.toVec3
import vn.apero.armeasure.ar.domain.geometry.Vec3
import vn.apero.armeasure.ar.domain.geometry.measureDistanceMeters
import vn.apero.armeasure.ar.presentation.airdraw.components.AirDrawOverlayFrame
import vn.apero.armeasure.ar.presentation.airdraw.components.AirSegment
import vn.apero.armeasure.ar.presentation.camera.ArSessionFrameStream

/**
 * The air-pen's per-frame loop.
 *
 * ### What this deliberately does not do
 *
 * It never hit-tests, never touches the depth map, never waits for a plane. The pen's position is
 * `frame.camera.pose` — the phone's own 6DoF pose, which ARCore has from the moment tracking
 * starts. Everything the measuring tools spend their frame budget on is irrelevant here.
 *
 * It also makes no attempt at accuracy. ARCore's pose drifts, so a stroke laid down thirty seconds
 * apart from another will sit centimetres off it. For measuring that is disqualifying; for drawing
 * nobody can tell, and pretending otherwise would only add gates that make the pen feel broken.
 */
internal fun onAirDrawFrame(
    frames: AirDrawFrameStream,
    sessionFrames: ArSessionFrameStream,
    projector: PoseProjector,
    frame: Frame,
    viewSize: IntSize,
    holding: Boolean,
) {
    sessionFrames.tracking = frame.camera.trackingState == TrackingState.TRACKING

    if (!sessionFrames.tracking || viewSize == IntSize.Zero) {
        frames.clearForUntrackedFrame()
        return
    }

    projector.update(frame)

    val cameraPose = frame.camera.pose
    frames.onFrame(
        holding = holding,
        cameraPosition = cameraPose.toVec3(),
        cameraForward = cameraPose.forward(),
    )

    frames.overlay = buildAirDrawOverlay(frames, projector, viewSize, cameraPose.toVec3())
}

/**
 * The direction the phone is pointing, unit length.
 *
 * ARCore poses are right-handed with the camera looking down **-Z**, so forward is the negated Z
 * axis. Getting this sign wrong puts the nib behind the user's head, where it still draws
 * perfectly well and is never once visible.
 */
private fun Pose.forward(): Vec3 {
    val z = zAxis
    return Vec3(-z[0], -z[1], -z[2])
}

/** Stroke width at one metre. Nearer ink is fatter, further ink thinner — see [AirSegment]. */
private const val BaseWidthDp = 4f
private const val MinWidthDp = 1f
private const val MaxWidthDp = 14f

/** Nib radius at one metre, scaled the same way so it reads as part of the same pen. */
private const val BaseNibRadiusDp = 5f

private fun buildAirDrawOverlay(
    frames: AirDrawFrameStream,
    projector: PoseProjector,
    viewSize: IntSize,
    cameraPosition: Vec3,
): AirDrawOverlayFrame {
    val width = viewSize.width
    val height = viewSize.height

    fun strokeSegments(points: List<Vec3>): List<AirSegment> {
        if (points.size < 2) return emptyList()
        return buildList {
            for (i in 0 until points.size - 1) {
                // A null projection means the point is behind the camera. Skip that segment rather
                // than clamping it to an edge, which would draw a line the user never made.
                val a = projector.project(points[i], width, height) ?: continue
                val b = projector.project(points[i + 1], width, height) ?: continue
                val midDistance = measureDistanceMeters(cameraPosition, points[i])
                add(AirSegment(a, b, widthDpAt(midDistance)))
            }
        }
    }

    val tipWorld = frames.tip
    return AirDrawOverlayFrame(
        committed = frames.strokes.flatMap { strokeSegments(it) },
        current = strokeSegments(frames.current),
        tip = tipWorld?.let { projector.project(it, width, height) },
        tipRadiusDp = tipWorld
            ?.let { BaseNibRadiusDp / maxOf(0.3f, measureDistanceMeters(cameraPosition, it)) }
            ?.coerceIn(3f, 12f)
            ?: BaseNibRadiusDp,
        drawing = frames.drawing,
    )
}

private fun widthDpAt(distanceMeters: Float): Float =
    (BaseWidthDp / maxOf(0.3f, distanceMeters)).coerceIn(MinWidthDp, MaxWidthDp)
