package vn.apero.armeasure.ar.presentation.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.ar.core.Config
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import vn.apero.armeasure.R
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.data.warmup.ArWarmupGate
import vn.apero.armeasure.ar.presentation.ruler.MeasureEffect
import vn.apero.armeasure.ar.presentation.ruler.MeasureIntent
import vn.apero.armeasure.ar.presentation.ruler.MeasureUiState
import vn.apero.armeasure.ar.presentation.ruler.MeasureViewModel
import vn.apero.armeasure.ar.presentation.shapes.ShapeEffect
import vn.apero.armeasure.ar.presentation.shapes.ShapeIntent
import vn.apero.armeasure.ar.presentation.shapes.ShapeKind
import vn.apero.armeasure.ar.presentation.shapes.ShapeMeasureViewModel
import vn.apero.armeasure.ar.presentation.shapes.components.ShapeOverlay
import vn.apero.armeasure.ar.presentation.shapes.ShapeUiState
import vn.apero.armeasure.common.data.UnitPreference
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.ar.presentation.camera.components.ARToast
import vn.apero.armeasure.ar.presentation.camera.components.ArCameraBottomBar
import vn.apero.armeasure.ar.presentation.camera.components.ArCameraTopBar
import vn.apero.armeasure.ar.presentation.camera.components.DistanceOverlay
import vn.apero.armeasure.ar.presentation.camera.components.MeasureModeSheet
import vn.apero.armeasure.ar.presentation.camera.components.distanceActions
import vn.apero.armeasure.ar.presentation.camera.components.result
import vn.apero.armeasure.ar.presentation.camera.components.shapeActions
import vn.apero.armeasure.photo.presentation.redo
import vn.apero.armeasure.photo.presentation.undo

/** How often the watchdog below checks whether ARCore frames are still arriving. */
private const val CameraWatchdogPollIntervalMs = 1_000L

/**
 * How long without a single ARCore frame — of actual foreground time — counts as "stuck".
 * Moved here verbatim from the old `MeasureScreen.kt`; see [shouldForceRemount] for the decision
 * this feeds and `report-260825-1703-session-handoff-box-cylinder-measure.md` §10-§11 for why the
 * fix is a delayed first mount plus this watchdog, not a proactive remount on every resume.
 */
private const val CameraWatchdogTimeoutMs = 10_000L

/** How long the commit-confirmation [ARToast] stays up before falling back to the tool's usual
 * coaching hint (insight 8's terminal state). */
private const val CommitToastDurationMs = 1_500L

/**
 * One `ARSceneView`, one Filament `Engine`, one ARCore `Session`, shared by every tool.
 *
 * Structural rules — do not violate, see the phase-05 hazard record (§11) and README §12 for what
 * happens if you do:
 * 1. The Engine/MaterialLoader constructors below run exactly once, here, never inside
 *    `key(instanceKey)`. §11.1: moving the Engine's creation inside a remount block produced a
 *    near-100% failure rate afterward, including on cold starts that previously worked — a
 *    heavyweight, GPU-resource-owning native object torn down on a ~10s retry cadence left GPU
 *    resources mid-teardown for the next Engine. The Engine is created once for the screen's
 *    whole lifetime, matching the library's own sample usage.
 * 2. The `ARSceneView` below sits outside every `when (tool)` branch, and `tool` never appears in
 *    `key(...)`. A tool swap must not remount the view — §11.2 found that forcing a remount on
 *    every plain resume (a much rarer event than a tool swap would be) already regressed the
 *    common case: closing and immediately reopening the camera is a *more frequent* failure than
 *    the rare stale-texture bug either fix was trying to prevent. The MVI conversion changed where
 *    the tools' state lives, not this: `tool` is a field of [ArCameraUiState] and is read only to
 *    choose a frame loop, an overlay and a set of chrome bindings.
 * 3. [ArWarmupGate] is consulted before the view ever mounts, so entering on any tool pays the same
 *    warm-up delay. It runs on **every** entry, not once per process: this screen is its own
 *    Activity and the photo path tears the AR session down, so each entry builds a fresh
 *    `Session`/`Engine` and faces the cold-start race again — see that gate's own KDoc.
 *
 * ### Where state lives (phase 04)
 *
 * Five ViewModels, no DI: [ArCameraViewModel] for the chrome (tool, unit, sheets) and one per tool.
 * Each tool ViewModel exposes an MVI `state` for what the user has committed and a plain `frames`
 * holder for what ARCore writes per frame; the frame loops are called directly from
 * `onSessionUpdated` below and never through `processIntent`. The reasoning is on
 * [vn.apero.armeasure.ar.presentation.ruler.MeasureFrameStream] and must not be undone casually: at
 * 30-60 Hz an intent round trip costs a coroutine dispatch and a whole-state allocation per frame.
 */
@Composable
internal fun ArCameraScreen(
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onResult: (MeasurementResult) -> Unit = {},
) {
    val context = LocalContext.current
    val unitPreference = remember { UnitPreference(context) }

    val camera: ArCameraViewModel = viewModel(
        factory = remember(unitPreference) { ArCameraViewModel.factory(unitPreference) },
    )
    // The `key` is what gives four instances of two ViewModel classes. Without it the two distance
    // tools would share one store entry, and reinterpreting one point list under the other pairing
    // rule would silently redraw the user's geometry.
    val distance: MeasureViewModel = viewModel(
        key = "ar-distance",
        factory = remember { MeasureViewModel.factory(chained = false) },
    )
    val distanceChain: MeasureViewModel = viewModel(
        key = "ar-distance-chain",
        factory = remember { MeasureViewModel.factory(chained = true) },
    )
    val box: ShapeMeasureViewModel = viewModel(
        key = "ar-box",
        factory = remember { ShapeMeasureViewModel.factory(ShapeKind.Box) },
    )
    val cylinder: ShapeMeasureViewModel = viewModel(
        key = "ar-cylinder",
        factory = remember { ShapeMeasureViewModel.factory(ShapeKind.Cylinder) },
    )

    val cameraState by camera.state.collectAsStateWithLifecycle()
    val distanceState by distance.state.collectAsStateWithLifecycle()
    val distanceChainState by distanceChain.state.collectAsStateWithLifecycle()
    val boxState by box.state.collectAsStateWithLifecycle()
    val cylinderState by cylinder.state.collectAsStateWithLifecycle()
    val tool = cameraState.tool
    val unit = cameraState.unit

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    // Not a ViewModel and not an MVI State: every field is written from an ARCore callback. See its
    // KDoc — it is also why it is remembered here rather than owned by a ViewModel, since it
    // describes the session this composition owns.
    val sessionFrames = remember { ArSessionFrameStream() }
    // Scratch buffers only — the frame loop runs once per frame, from whichever tool is active,
    // so there is never any interleaving that would make sharing this unsafe (insight 9).
    val projector = remember { PoseProjector() }

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // Bumped by the watchdog only — recreates the ARCore Session (and its onSessionCreated
    // camera-texture registration) without touching the Engine above.
    var instanceKey by remember { mutableIntStateOf(0) }
    val isWarmedUp = ArWarmupGate.rememberArWarmedUp()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                sessionFrames.lastFrameAtMillis = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Releases every anchor every tool still holds when the composition goes away — mandatory now
    // that the session is long-lived and shared, so nothing else cleans up a half-drawn shape's
    // anchor incidentally on tab switch the way session teardown used to (see phase 03). The
    // ViewModels repeat this in onCleared, because they can outlive this composition.
    DisposableEffect(Unit) {
        onDispose {
            distance.releaseAll()
            distanceChain.releaseAll()
            box.releaseAll()
            cylinder.releaseAll()
        }
    }

    // Insight 8: the AR branch has no terminal state, so a commit is confirmed by a transient
    // toast rather than a save flow — cleared automatically after CommitToastDurationMs.
    var commitToast by remember { mutableStateOf<String?>(null) }
    val commitConfirmation = stringResource(R.string.armeasure_toast_point_added)
    LaunchedEffect(commitToast) {
        if (commitToast != null) {
            delay(CommitToastDurationMs)
            commitToast = null
        }
    }

    // One collector for all four tools' `Measured` effects: a finished measurement is reported to
    // the host and confirmed to the user in exactly one place, whichever tool produced it.
    val latestOnResult by rememberUpdatedState(onResult)
    LaunchedEffect(distance, distanceChain, box, cylinder) {
        merge(
            distance.effect.map { it.result() },
            distanceChain.effect.map { it.result() },
            box.effect.map { it.result() },
            cylinder.effect.map { it.result() },
        ).collect { result ->
            commitToast = commitConfirmation
            latestOnResult(result)
        }
    }

    /** Resets the newly active tool's steadiness gate before it becomes visible (insight 6). */
    fun selectTool(next: MeasureTool) {
        if (next == tool) return
        when (next) {
            MeasureTool.Distance -> distance.onActivated()
            MeasureTool.DistanceChain -> distanceChain.onActivated()
            MeasureTool.Box -> box.onActivated()
            MeasureTool.Cylinder -> cylinder.onActivated()
        }
        camera.processIntent(ArCameraIntent.SelectTool(next))
    }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
        if (!isWarmedUp) {
            ARToast(
                text = stringResource(R.string.armeasure_hint_warming_up),
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        key(instanceKey) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = materialLoader,
                planeRenderer = true,
                sessionConfiguration = { configuredSession, config ->
                    // The union of what any of the four tools needs (insight 10) — the two
                    // pre-merge config blocks were already byte-identical.
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    val supported = configuredSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    sessionFrames.depthSupported = supported
                    config.depthMode =
                        if (supported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                    config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                },
                onSessionCreated = { created ->
                    // Pushed into the ViewModels rather than held in composition: the session is a
                    // handle, not state, and keeping it there is what lets every intent stay free
                    // of ARCore types.
                    distance.onSessionChanged(created)
                    distanceChain.onSessionChanged(created)
                    box.onSessionChanged(created)
                    cylinder.onSessionChanged(created)
                },
                onSessionUpdated = { updatedSession, frame ->
                    sessionFrames.noteFrame()
                    // Only the active tool's frame loop runs — an inactive tool costs nothing.
                    // A direct call, never processIntent: see this file's header.
                    when (tool) {
                        MeasureTool.Distance ->
                            distance.onFrame(sessionFrames, projector, unit, updatedSession, frame, viewSize)
                        MeasureTool.DistanceChain ->
                            distanceChain.onFrame(sessionFrames, projector, unit, updatedSession, frame, viewSize)
                        MeasureTool.Box ->
                            box.onFrame(sessionFrames, projector, unit, updatedSession, frame, viewSize)
                        MeasureTool.Cylinder ->
                            cylinder.onFrame(sessionFrames, projector, unit, updatedSession, frame, viewSize)
                    }
                },
                onTrackingFailureChanged = { sessionFrames.trackingFailure = it },
            )
        }

        // Watchdog: recreates the session (not the Engine) after CameraWatchdogTimeoutMs of real
        // foreground time with no frame. shouldForceRemount is the pure decision two earlier fix
        // attempts got wrong — see its own KDoc.
        LaunchedEffect(instanceKey) {
            sessionFrames.lastFrameAtMillis = System.currentTimeMillis()
            while (true) {
                delay(CameraWatchdogPollIntervalMs)
                val isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (!isResumed) {
                    sessionFrames.lastFrameAtMillis = System.currentTimeMillis()
                    continue
                }
                if (
                    shouldForceRemount(
                        lastFrameAtMillis = sessionFrames.lastFrameAtMillis,
                        nowMillis = System.currentTimeMillis(),
                        isResumed = isResumed,
                        timeoutMs = CameraWatchdogTimeoutMs,
                    )
                ) {
                    instanceKey++
                    break
                }
            }
        }

        when (tool) {
            MeasureTool.Distance ->
                DistanceOverlay(distance, projector, viewSize, Modifier.fillMaxSize())
            MeasureTool.DistanceChain ->
                DistanceOverlay(distanceChain, projector, viewSize, Modifier.fillMaxSize())
            MeasureTool.Box ->
                ShapeOverlay(frameProvider = { box.frames.overlay }, modifier = Modifier.fillMaxSize())
            MeasureTool.Cylinder ->
                ShapeOverlay(frameProvider = { cylinder.frames.overlay }, modifier = Modifier.fillMaxSize())
        }

        // One binding per active tool instead of a `when` at every callback below. The two tool
        // ViewModels share no supertype (kept that way to avoid a one-off interface for four call
        // sites), so this is the single place that reads which one is active right now.
        val actions = when (tool) {
            MeasureTool.Distance -> distanceActions(distance, distanceState, sessionFrames, unit)
            MeasureTool.DistanceChain ->
                distanceActions(distanceChain, distanceChainState, sessionFrames, unit)
            MeasureTool.Box -> shapeActions(box, boxState, sessionFrames, unit)
            MeasureTool.Cylinder -> shapeActions(cylinder, cylinderState, sessionFrames, unit)
        }

        ArCameraTopBar(
            canUndo = actions.canUndo,
            onUndo = actions.undo,
            canRedo = actions.canRedo,
            onRedo = actions.redo,
            onClose = onClose,
            unit = unit,
            onSelectUnit = { camera.processIntent(ArCameraIntent.SelectUnit(it)) },
            showUnitMenu = cameraState.showUnitMenu,
            onUnitClick = { camera.processIntent(ArCameraIntent.ShowUnitMenu(true)) },
            onDismissUnitMenu = { camera.processIntent(ArCameraIntent.ShowUnitMenu(false)) },
            onModeClick = { camera.processIntent(ArCameraIntent.ShowModeSheet(true)) },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Defect 1 of the design update: the mock's toast overlaps the sheet by 52px — hidden
        // here while the sheet is open instead.
        if (!cameraState.showModeSheet) {
            ARToast(
                text = commitToast ?: actions.hint,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Tracks ArCameraChrome's BottomBarLift: the bar is measured from the same
                    // bottom edge, so the two offsets have to move together or the toast lands on
                    // top of the capture button.
                    .padding(bottom = 160.dp),
            )
        }

        ArCameraBottomBar(
            clearEnabled = actions.canUndo,
            onClear = actions.clear,
            addEnabled = actions.addEnabled,
            onAddPoint = actions.add,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (cameraState.showModeSheet) {
            // ModalBottomSheet renders in its own window (not this Box), so no .align() here —
            // Material3 positions and sizes the sheet itself.
            MeasureModeSheet(
                selected = tool,
                onSelect = {
                    selectTool(it)
                    camera.processIntent(ArCameraIntent.ShowModeSheet(false))
                },
                onDismiss = { camera.processIntent(ArCameraIntent.ShowModeSheet(false)) },
            )
        }
    }
}
