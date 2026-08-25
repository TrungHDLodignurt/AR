package vn.quancua.artapemeasure.measure

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import vn.quancua.artapemeasure.R
import vn.quancua.artapemeasure.ui.MeasureBottomBar
import vn.quancua.artapemeasure.ui.MeasureTopBar

/**
 * The measure tab.
 *
 * Nothing is rendered as Filament geometry: the AR view supplies the camera feed, tracking and
 * hit tests, and every measurement graphic is drawn by [MeasureOverlay] in a 2D Canvas above
 * it. Perspective projection preserves straight lines, so a screen-space line between two
 * projected anchors is exact, not an approximation — and dashed strokes plus screen-constant
 * label pills, both of which the reference app has, are trivial in Canvas and awkward in 3D.
 */
@Composable
fun MeasureScreen(modifier: Modifier = Modifier) {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    val state = remember { MeasureState() }
    val projector = remember { PoseProjector() }

    var session by remember { mutableStateOf<Session?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { viewSize = it }) {

        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            // The plane grid is genuine feedback here: it shows the user which surfaces are
            // actually measurable before they commit a point.
            planeRenderer = true,
            sessionConfiguration = { configuredSession, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                // Probe before assigning: requesting an unsupported depth mode fails session
                // configuration outright rather than degrading quietly.
                val supported = configuredSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                state.depthSupported = supported
                config.depthMode =
                    if (supported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                // Instant placement is deliberately off: its initial pose is an estimate that
                // refines later, which is exactly wrong for a tool whose whole output is a
                // number the user reads immediately.
                config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
            },
            onSessionCreated = { session = it },
            onSessionUpdated = { updatedSession, frame ->
                state.cameraReady = true
                onFrame(state, projector, updatedSession, frame, viewSize)
            },
            onTrackingFailureChanged = { state.trackingFailure = it },
        )

        MeasureOverlay(
            frameProvider = { state.overlay },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { touch ->
                        // Hit-test against the same screen positions the overlay just drew, so
                        // "grabbed the wrong point" can never happen from stale projections.
                        val positions = state.worldPoints.map {
                            projector.project(it, viewSize.width, viewSize.height)?.let { p -> p.x to p.y }
                        }
                        val index = nearestIndexWithin(
                            positions,
                            touch = touch.x to touch.y,
                            maxDistancePx = 32.dp.toPx(),
                        )
                        if (index != null) state.beginDrag(index, touch)
                    },
                    onDrag = { change, _ ->
                        if (state.draggingIndex != null) {
                            change.consume()
                            state.updateDragTouch(change.position)
                        }
                    },
                    onDragEnd = {
                        if (state.draggingIndex != null) {
                            session?.let { state.commitDrag(it) } ?: state.cancelDrag()
                        }
                    },
                    onDragCancel = state::cancelDrag,
                )
            },
        )

        MeasureTopBar(
            canUndo = state.canUndo,
            onUndo = state::undo,
            onClear = state::clear,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        HintBanner(
            text = hintFor(state),
            // Same statusBars inset as MeasureTopBar above, so the 72dp clearance below it
            // stays correct regardless of status bar height instead of the banner drifting
            // under (or too far below) the top bar on different devices.
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 72.dp),
        )


        // Tapping the unit chip switches metric/imperial. Imperial is not optional for US users.
        Text(
            text = if (state.unit == LengthUnit.Metric) "m" else "ft",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x66000000))
                .clickable { state.toggleUnit() }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )

        MeasureBottomBar(
            // Gated on draggingIndex too: a second finger tapping + mid-drag would add a point
            // and edit one at the same time, which is a confusing thing for one tap to do.
            addEnabled = state.draggingIndex == null && state.live != null && state.liveStable,
            onAddPoint = { session?.let { state.commitLivePoint(it) } },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Shared with [ShapeMeasureScreen] — the box/cylinder tools show the same kind of one-line hint. */
@Composable
internal fun HintBanner(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(
        text = text,
        color = Color.White,
        fontSize = 13.sp,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x8C000000))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * One line of guidance, chosen by what is actually blocking the user.
 *
 * A tracking failure carries a specific, actionable reason — surfacing it beats a generic
 * "AR unavailable", which tells the user nothing they can act on.
 */
@Composable
private fun hintFor(state: MeasureState): String? {
    state.trackingFailure?.let { reason ->
        val res = when (reason) {
            TrackingFailureReason.BAD_STATE -> R.string.tracking_bad_state
            TrackingFailureReason.INSUFFICIENT_LIGHT -> R.string.tracking_insufficient_light
            TrackingFailureReason.EXCESSIVE_MOTION -> R.string.tracking_excessive_motion
            TrackingFailureReason.INSUFFICIENT_FEATURES -> R.string.tracking_insufficient_features
            TrackingFailureReason.CAMERA_UNAVAILABLE -> R.string.tracking_camera_unavailable
            TrackingFailureReason.NONE -> null
        }
        if (res != null) return stringResource(res)
    }
    return when {
        // Direct manipulation is already happening — nothing about surface-hunting is relevant
        // while the user's finger is on a point they placed a moment ago.
        state.draggingIndex != null -> stringResource(R.string.hint_dragging_point)
        // Ahead of the plane hint: a reading that will not hold still is a specific, fixable
        // problem, and telling the user to keep hunting for a surface would be misleading when
        // the reticle is already on one that simply cannot be measured.
        state.live != null && !state.liveStable -> stringResource(R.string.hint_reading_unsteady)
        !state.anyPlaneTracked -> stringResource(R.string.hint_move_to_find_surface)
        state.live == null -> stringResource(R.string.hint_aim_at_surface)
        state.points.isEmpty() -> stringResource(R.string.hint_tap_to_start)
        // Once measuring, show what the last point was resolved from: a reading you cannot
        // attribute is a reading you cannot calibrate.
        else -> state.lastSource?.let { "Point ${state.points.size} on ${it.label}" }
    }
}
