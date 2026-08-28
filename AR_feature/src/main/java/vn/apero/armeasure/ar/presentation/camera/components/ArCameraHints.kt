package vn.apero.armeasure.ar.presentation.camera.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.ar.core.TrackingFailureReason
import vn.apero.armeasure.R
import vn.apero.armeasure.ar.domain.geometry.hasOpenSegment
import vn.apero.armeasure.ar.presentation.ruler.MeasureFrameStream
import vn.apero.armeasure.ar.presentation.ruler.MeasureUiState
import vn.apero.armeasure.ar.presentation.shapes.ShapeFrameStream
import vn.apero.armeasure.ar.presentation.shapes.ShapeKind
import vn.apero.armeasure.ar.presentation.shapes.ShapePhase
import vn.apero.armeasure.ar.presentation.shapes.ShapeUiState
import vn.apero.armeasure.ar.presentation.camera.ArSessionFrameStream

/**
 * One line of guidance for whichever tool is active — the merge of the old `MeasureScreen.kt`'s and
 * `ShapeMeasureScreen.kt`'s separate `hintFor` functions.
 *
 * Each function reads from two places on purpose: the tool's MVI state for what the user has
 * committed, and its frame stream for what the camera is seeing right now. The hint is the one piece
 * of chrome that genuinely needs both.
 *
 * A tracking failure carries a specific, actionable reason and always wins over a tool-specific
 * hint — telling the user "not enough light" beats a generic "aim at a surface" that gives them
 * nothing to act on. Hence [trackingFailureHint] first, at the call site.
 */
@Composable
internal fun trackingFailureHint(sessionFrames: ArSessionFrameStream): String? {
    val reason = sessionFrames.trackingFailure ?: return null
    val res = when (reason) {
        TrackingFailureReason.BAD_STATE -> R.string.armeasure_tracking_bad_state
        TrackingFailureReason.INSUFFICIENT_LIGHT -> R.string.armeasure_tracking_insufficient_light
        TrackingFailureReason.EXCESSIVE_MOTION -> R.string.armeasure_tracking_excessive_motion
        TrackingFailureReason.INSUFFICIENT_FEATURES -> R.string.armeasure_tracking_insufficient_features
        TrackingFailureReason.CAMERA_UNAVAILABLE -> R.string.armeasure_tracking_camera_unavailable
        TrackingFailureReason.NONE -> null
    }
    return res?.let { stringResource(it) }
}

@Composable
internal fun distanceHint(
    sessionFrames: ArSessionFrameStream,
    state: MeasureUiState,
    frames: MeasureFrameStream,
    chained: Boolean,
): String? = when {
    // Direct manipulation is already happening — nothing about surface-hunting is relevant
    // while the user's finger is on a point they placed a moment ago.
    frames.draggingIndex != null -> stringResource(R.string.armeasure_hint_dragging_point)
    // Ahead of the plane hint: a reading that will not hold still is a specific, fixable
    // problem, and telling the user to keep hunting for a surface would be misleading when
    // the reticle is already on one that simply cannot be measured.
    frames.live != null && !frames.liveStable -> stringResource(R.string.armeasure_hint_reading_unsteady)
    !sessionFrames.anyPlaneTracked -> stringResource(R.string.armeasure_hint_move_to_find_surface)
    frames.live == null -> stringResource(R.string.armeasure_hint_aim_at_surface)
    state.pointCount == 0 -> stringResource(R.string.armeasure_hint_tap_to_start)
    // The unchained tool needs to say which end of a segment the next tap places — nothing else on
    // screen distinguishes "about to close this segment" from "about to start a new one", and
    // guessing wrong costs the user an undo. The hit source stays in the string either way: a
    // reading you cannot attribute is a reading you cannot calibrate.
    !chained -> state.lastSource?.let {
        val res = if (hasOpenSegment(state.pointCount, chained = false)) {
            R.string.armeasure_hint_segment_awaiting_end
        } else {
            R.string.armeasure_hint_segment_done
        }
        stringResource(res, stringResource(it.labelRes))
    }
    // Once measuring, show what the last point was resolved from: a reading you cannot
    // attribute is a reading you cannot calibrate.
    else -> state.lastSource?.let {
        stringResource(R.string.armeasure_hint_point_on_surface, state.pointCount, stringResource(it.labelRes))
    }
}

@Composable
internal fun shapeHint(
    sessionFrames: ArSessionFrameStream,
    state: ShapeUiState,
    frames: ShapeFrameStream,
    kind: ShapeKind,
): String? {
    if (frames.live != null && !frames.liveStable) {
        return stringResource(R.string.armeasure_hint_reading_unsteady)
    }
    if (!sessionFrames.anyPlaneTracked) return stringResource(R.string.armeasure_hint_move_to_find_surface)
    if (frames.live == null) return stringResource(R.string.armeasure_hint_aim_at_surface)

    val shapeName = stringResource(kind.nameRes)
    val originNoun = stringResource(
        if (kind == ShapeKind.Box) R.string.armeasure_shape_part_corner
        else R.string.armeasure_shape_part_center
    )
    return when (state.phase) {
        is ShapePhase.AwaitingOrigin ->
            stringResource(R.string.armeasure_hint_shape_awaiting_origin, shapeName, originNoun)
        is ShapePhase.SizingEdgeU -> stringResource(R.string.armeasure_hint_shape_sizing_edge_u)
        is ShapePhase.SizingEdgeV -> stringResource(R.string.armeasure_hint_shape_sizing_edge_v)
        is ShapePhase.SizingCircle -> stringResource(R.string.armeasure_hint_shape_sizing_circle)
        is ShapePhase.SizingHeight -> stringResource(R.string.armeasure_hint_shape_sizing_height, shapeName)
    }
}
