package vn.apero.armeasure.ar.presentation.shapes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import kotlin.math.abs
import vn.apero.armeasure.R
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.data.warmup.ArWarmupGate
import vn.apero.armeasure.ar.domain.geometry.length
import vn.apero.armeasure.ar.presentation.ruler.HintBanner
import vn.apero.armeasure.ar.presentation.shared.MeasureBottomBar
import vn.apero.armeasure.ar.presentation.shared.MeasureTopBar
import vn.apero.armeasure.common.data.UnitPreference
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.common.ui.UnitBtn
import vn.apero.armeasure.common.ui.UnitMenu

/**
 * The box/cylinder tab, shared by both shapes (see [ShapeMeasureState]).
 *
 * Same AR scaffolding as the ruler screen — camera feed and hit-testing from ARCore, everything
 * drawn by a 2D Canvas overlay above it — the only difference is what a tap does and what gets
 * drawn, both of which live in [ShapeMeasureState]/[ShapeOverlay] rather than here.
 *
 * [onShapeCommitted] fires once per finished shape (not per frame), at the same tap that closes
 * out [ShapePhase.SizingHeight]. Public callers never see [MeasuredShape] directly — see
 * [ArMeasureBoxScreen]/[ArMeasureCylinderScreen] below for the [MeasurementResult] mapping.
 */
@Composable
internal fun ShapeMeasureScreen(
    kind: ShapeKind,
    modifier: Modifier = Modifier,
    unit: LengthUnit = LengthUnit.Cm,
    onShapeCommitted: (MeasuredShape, LengthUnit) -> Unit = { _, _ -> },
    onClose: (() -> Unit)? = null,
) {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    val context = LocalContext.current
    val unitPreference = remember { UnitPreference(context) }
    // unitPreference.unit already falls back to LengthUnit.Cm on a first-ever launch — same
    // value as the unit param's own default, so the persisted store is the single seed.
    val state = remember(kind) { ShapeMeasureState(kind, unitPreference.unit) }
    LaunchedEffect(state.unit) { unitPreference.unit = state.unit }
    val projector = remember(kind) { PoseProjector() }

    var session by remember { mutableStateOf<Session?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // See ArWarmupGate's doc: shared with the ruler screen so whichever AR tab is opened first
    // after a cold launch pays this once, not per-tab. Was missing here entirely before — a cold
    // launch straight into Box/Cylinder was exposed to the same TextureNotSetException race the
    // ruler screen already guards against.
    val isWarmedUp = ArWarmupGate.rememberArWarmedUp()

    Box(modifier = modifier.fillMaxSize().onSizeChanged { viewSize = it }) {

        if (!isWarmedUp) {
            HintBanner(
                text = stringResource(R.string.armeasure_hint_warming_up),
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

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
            onClose = onClose,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        HintBanner(
            text = hintFor(state),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 72.dp),
        )

        // Tapping the unit button opens the 4-unit menu; picking a row updates state.unit,
        // which the LaunchedEffect above persists.
        var showUnitMenu by remember { mutableStateOf(false) }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp)) {
            UnitBtn(unit = state.unit, onClick = { showUnitMenu = true })
            if (showUnitMenu) {
                UnitMenu(
                    selected = state.unit,
                    onSelect = state::setUnit,
                    onDismiss = { showUnitMenu = false },
                )
            }
        }

        MeasureBottomBar(
            addEnabled = state.canCommitStep,
            onAddPoint = {
                session?.let {
                    val before = state.shapes.size
                    state.commitStep(it)
                    if (state.shapes.size > before) onShapeCommitted(state.shapes.last(), state.unit)
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Public entry for the Box tool: same AR scaffolding as [ArMeasureCylinderScreen], the free-hand
 * two-edge box tool.
 *
 * @param unit fallback display unit for the very first launch, before [UnitPreference] holds any
 *   value; the persisted, process-wide unit choice takes over from then on — see decision 8.
 * @param onResult fires once per finished box, never per frame.
 * @param onClose when non-null, shows a "✕" pill in the top bar that invokes it.
 */
@Composable
fun ArMeasureBoxScreen(
    modifier: Modifier = Modifier,
    unit: LengthUnit = LengthUnit.Cm,
    onResult: (MeasurementResult.Box) -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    ShapeMeasureScreen(
        kind = ShapeKind.Box,
        modifier = modifier,
        unit = unit,
        onShapeCommitted = { shape, liveUnit ->
            val rect = shape.base as? ShapeBase.Rect ?: return@ShapeMeasureScreen
            onResult(MeasurementResult.Box(rect.edgeU.length(), rect.edgeV.length(), abs(shape.height), liveUnit))
        },
        onClose = onClose,
    )
}

/**
 * Public entry for the Cylinder tool: same AR scaffolding as [ArMeasureBoxScreen], the
 * center-to-edge circular-base tool.
 *
 * @param unit fallback display unit for the very first launch, before [UnitPreference] holds any
 *   value; the persisted, process-wide unit choice takes over from then on — see decision 8.
 * @param onResult fires once per finished cylinder, never per frame.
 * @param onClose when non-null, shows a "✕" pill in the top bar that invokes it.
 */
@Composable
fun ArMeasureCylinderScreen(
    modifier: Modifier = Modifier,
    unit: LengthUnit = LengthUnit.Cm,
    onResult: (MeasurementResult.Cylinder) -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    ShapeMeasureScreen(
        kind = ShapeKind.Cylinder,
        modifier = modifier,
        unit = unit,
        onShapeCommitted = { shape, liveUnit ->
            val circle = shape.base as? ShapeBase.Circle ?: return@ShapeMeasureScreen
            onResult(MeasurementResult.Cylinder(circle.radius, abs(shape.height), liveUnit))
        },
        onClose = onClose,
    )
}

/** One line of guidance for whichever of the 3 taps is next. */
@Composable
private fun hintFor(state: ShapeMeasureState): String? {
    state.trackingFailure?.let { reason ->
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
    if (state.live != null && !state.liveStable) return stringResource(R.string.armeasure_hint_reading_unsteady)
    if (!state.anyPlaneTracked) return stringResource(R.string.armeasure_hint_move_to_find_surface)
    if (state.live == null) return stringResource(R.string.armeasure_hint_aim_at_surface)

    val shapeName = state.kind.label.lowercase()
    val originNoun = if (state.kind == ShapeKind.Box) "corner" else "center"
    return when (state.phase) {
        is ShapePhase.AwaitingOrigin -> "Tap + to place the $shapeName's $originNoun"
        is ShapePhase.SizingEdgeU -> "Move to draw the first edge, tap + to fix it"
        is ShapePhase.SizingEdgeV -> "Move to draw the second edge, tap + to fix it"
        is ShapePhase.SizingCircle -> "Move to size the base, tap + to fix it"
        is ShapePhase.SizingHeight -> "Tilt up to set height, tap + to finish the $shapeName"
    }
}
