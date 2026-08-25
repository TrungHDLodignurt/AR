package vn.quancua.artapemeasure.measure

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import vn.quancua.artapemeasure.R
import vn.quancua.artapemeasure.ui.MeasureBottomBar
import vn.quancua.artapemeasure.ui.MeasureTopBar

/**
 * The box/cylinder tab, shared by both shapes (see [ShapeMeasureState]).
 *
 * Same AR scaffolding as [MeasureScreen] — camera feed and hit-testing from ARCore, everything
 * drawn by a 2D Canvas overlay above it — the only difference is what a tap does and what gets
 * drawn, both of which live in [ShapeMeasureState]/[ShapeOverlay] rather than here.
 */
@Composable
fun ShapeMeasureScreen(kind: ShapeKind, modifier: Modifier = Modifier) {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    val state = remember(kind) { ShapeMeasureState(kind) }
    val projector = remember(kind) { PoseProjector() }

    var session by remember { mutableStateOf<Session?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { viewSize = it }) {

        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            planeRenderer = true,
            sessionConfiguration = { configuredSession, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                val supported = configuredSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                state.depthSupported = supported
                config.depthMode =
                    if (supported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
            },
            onSessionCreated = { session = it },
            onSessionUpdated = { updatedSession, frame ->
                state.cameraReady = true
                onShapeFrame(state, projector, updatedSession, frame, viewSize)
            },
            onTrackingFailureChanged = { state.trackingFailure = it },
        )

        ShapeOverlay(
            frameProvider = { state.overlay },
            modifier = Modifier.fillMaxSize(),
        )

        MeasureTopBar(
            canUndo = state.canUndo,
            onUndo = state::undo,
            onClear = state::clear,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        HintBanner(
            text = hintFor(state),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 72.dp),
        )

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
            addEnabled = state.canCommitStep,
            onAddPoint = { session?.let { state.commitStep(it) } },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** One line of guidance for whichever of the 3 taps is next. */
@Composable
private fun hintFor(state: ShapeMeasureState): String? {
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
    if (state.live != null && !state.liveStable) return stringResource(R.string.hint_reading_unsteady)
    if (!state.anyPlaneTracked) return stringResource(R.string.hint_move_to_find_surface)
    if (state.live == null) return stringResource(R.string.hint_aim_at_surface)

    val shapeName = state.kind.label.lowercase()
    val originNoun = if (state.kind == ShapeKind.Box) "corner" else "center"
    return when (state.phase) {
        is ShapePhase.AwaitingOrigin -> "Tap + to place the $shapeName's $originNoun"
        is ShapePhase.SizingBase -> "Move to size the base, tap + to fix it"
        is ShapePhase.SizingHeight -> "Tilt up to set height, tap + to finish the $shapeName"
    }
}
