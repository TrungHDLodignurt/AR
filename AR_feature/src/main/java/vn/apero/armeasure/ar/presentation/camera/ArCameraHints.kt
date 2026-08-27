package vn.apero.armeasure.ar.presentation.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.ar.core.TrackingFailureReason
import vn.apero.armeasure.R
import vn.apero.armeasure.ar.presentation.ruler.MeasureState
import vn.apero.armeasure.ar.presentation.shapes.ShapeKind
import vn.apero.armeasure.ar.presentation.shapes.ShapeMeasureState
import vn.apero.armeasure.ar.presentation.shapes.ShapePhase

/**
 * One line of guidance for whichever tool is active — the merge of `MeasureScreen.kt`'s and
 * `ShapeMeasureScreen.kt`'s previously-separate `hintFor` functions.
 *
 * A tracking failure carries a specific, actionable reason and always wins over a tool-specific
 * hint — telling the user "not enough light" beats a generic "aim at a surface" that gives them
 * nothing to act on.
 */
@Composable
internal fun hintFor(
    tool: MeasureTool,
    sessionState: ArSessionState,
    distance: MeasureState,
    box: ShapeMeasureState,
    cylinder: ShapeMeasureState,
): String? {
    sessionState.trackingFailure?.let { reason ->
        val res = when (reason) {
            TrackingFailureReason.BAD_STATE -> R.string.armeasure_tracking_bad_state
            TrackingFailureReason.INSUFFICIENT_LIGHT -> R.string.armeasure_tracking_insufficient_light
            TrackingFailureReason.EXCESSIVE_MOTION -> R.string.armeasure_tracking_excessive_motion
            TrackingFailureReason.INSUFFICIENT_FEATURES -> R.string.armeasure_tracking_insufficient_features
            TrackingFailureReason.CAMERA_UNAVAILABLE -> R.string.armeasure_tracking_camera_unavailable
            TrackingFailureReason.NONE -> null
        }
        if (res != null) return stringResource(res)
    }
    return when (tool) {
        MeasureTool.DistanceChain -> hintForDistance(sessionState, distance)
        MeasureTool.Box -> hintForShape(sessionState, box)
        MeasureTool.Cylinder -> hintForShape(sessionState, cylinder)
    }
}

@Composable
private fun hintForDistance(sessionState: ArSessionState, state: MeasureState): String? = when {
    // Direct manipulation is already happening — nothing about surface-hunting is relevant
    // while the user's finger is on a point they placed a moment ago.
    state.draggingIndex != null -> stringResource(R.string.armeasure_hint_dragging_point)
    // Ahead of the plane hint: a reading that will not hold still is a specific, fixable
    // problem, and telling the user to keep hunting for a surface would be misleading when
    // the reticle is already on one that simply cannot be measured.
    state.live != null && !state.liveStable -> stringResource(R.string.armeasure_hint_reading_unsteady)
    !sessionState.anyPlaneTracked -> stringResource(R.string.armeasure_hint_move_to_find_surface)
    state.live == null -> stringResource(R.string.armeasure_hint_aim_at_surface)
    state.points.isEmpty() -> stringResource(R.string.armeasure_hint_tap_to_start)
    // Once measuring, show what the last point was resolved from: a reading you cannot
    // attribute is a reading you cannot calibrate.
    else -> state.lastSource?.let {
        stringResource(R.string.armeasure_hint_point_on_surface, state.points.size, it.label)
    }
}

@Composable
private fun hintForShape(sessionState: ArSessionState, state: ShapeMeasureState): String? {
    if (state.live != null && !state.liveStable) return stringResource(R.string.armeasure_hint_reading_unsteady)
    if (!sessionState.anyPlaneTracked) return stringResource(R.string.armeasure_hint_move_to_find_surface)
    if (state.live == null) return stringResource(R.string.armeasure_hint_aim_at_surface)

    val shapeName = state.kind.label.lowercase()
    val originNoun = if (state.kind == ShapeKind.Box) "corner" else "center"
    return when (state.phase) {
        is ShapePhase.AwaitingOrigin ->
            stringResource(R.string.armeasure_hint_shape_awaiting_origin, shapeName, originNoun)
        is ShapePhase.SizingEdgeU -> stringResource(R.string.armeasure_hint_shape_sizing_edge_u)
        is ShapePhase.SizingEdgeV -> stringResource(R.string.armeasure_hint_shape_sizing_edge_v)
        is ShapePhase.SizingCircle -> stringResource(R.string.armeasure_hint_shape_sizing_circle)
        is ShapePhase.SizingHeight -> stringResource(R.string.armeasure_hint_shape_sizing_height, shapeName)
    }
}
