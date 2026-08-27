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
import com.google.ar.core.Config
import com.google.ar.core.Session
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import kotlinx.coroutines.delay
import vn.apero.armeasure.R
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.data.warmup.ArWarmupGate
import vn.apero.armeasure.ar.presentation.ruler.MeasureState
import vn.apero.armeasure.ar.presentation.ruler.onFrame
import vn.apero.armeasure.ar.presentation.shapes.ShapeKind
import vn.apero.armeasure.ar.presentation.shapes.ShapeMeasureState
import vn.apero.armeasure.ar.presentation.shapes.ShapeOverlay
import vn.apero.armeasure.ar.presentation.shapes.onShapeFrame
import vn.apero.armeasure.common.data.UnitPreference
import vn.apero.armeasure.common.domain.MeasurementResult

/**
 * Which of the four AR tools is currently active. Swapping never remounts the view below.
 *
 * [Distance] and [DistanceChain] are the same measurement machinery under two pairing rules:
 * Distance consumes points as start/end pairs, so each segment stands alone; DistanceChain
 * continues the polyline from every committed point. See
 * [vn.apero.armeasure.ar.domain.geometry.segmentIndexPairs].
 */
internal enum class MeasureTool { Distance, DistanceChain, Box, Cylinder }

/** The active tool's chrome bindings — [MeasureState] and [ShapeMeasureState] share no supertype, so this is picked once per recomposition rather than re-`when`-ed at every call site below. */
private data class ToolActions(
    val canUndo: Boolean,
    val undo: () -> Unit,
    val canRedo: Boolean,
    val redo: () -> Unit,
    val clear: () -> Unit,
    val addEnabled: Boolean,
)

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
 * Structural rules — do not violate, see the phase-05 hazard record (§11) for what happens if you
 * do:
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
 *    the rare stale-texture bug either fix was trying to prevent.
 * 3. [ArWarmupGate] is consulted before the view ever mounts, so entering on any tool pays the same
 *    warm-up delay. It runs on **every** entry, not once per process: this screen is its own
 *    Activity and the photo path tears the AR session down, so each entry builds a fresh
 *    `Session`/`Engine` and faces the cold-start race again — see that gate's own KDoc.
 */
@Composable
internal fun ArCameraScreen(
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onResult: (MeasurementResult) -> Unit = {},
) {
    val context = LocalContext.current
    val unitPreference = remember { UnitPreference(context) }
    // Hoisted out of the tool state holders (insight 7): one unit, seeded from the persisted
    // choice, shared by all three tools so swapping tools never changes what unit is displayed.
    var unit by remember { mutableStateOf(unitPreference.unit) }
    LaunchedEffect(unit) { unitPreference.save(unit) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val sessionState = remember { ArSessionState() }
    // Scratch buffers only — update(frame) runs once per frame, from whichever tool is active,
    // so there is never any interleaving that would make sharing this unsafe (insight 9).
    val projector = remember { PoseProjector() }

    // Independent segments is the default: it is the tool a first-time user can predict, and
    // chaining is the advanced behaviour they opt into.
    var tool by remember { mutableStateOf(MeasureTool.Distance) }
    // Live for the screen's whole lifetime: a swap must not lose tracked points, a half-drawn
    // shape, or force a fresh holder (which would also mean a fresh, cold steadiness gate). The two
    // distance tools keep separate holders for the same reason Box and Cylinder do — reinterpreting
    // one point list under the other pairing rule would silently redraw the user's geometry.
    val distance = remember { MeasureState(chained = false) }
    val distanceChain = remember { MeasureState(chained = true) }
    val box = remember { ShapeMeasureState(ShapeKind.Box) }
    val cylinder = remember { ShapeMeasureState(ShapeKind.Cylinder) }

    fun selectTool(next: MeasureTool) {
        if (next == tool) return
        // Insight 6: reset the gate and clear the live reading of the tool becoming active, so a
        // sample taken before the swap can never read as an already-steady one right after it.
        when (next) {
            MeasureTool.Distance -> distance.onActivated()
            MeasureTool.DistanceChain -> distanceChain.onActivated()
            MeasureTool.Box -> box.onActivated()
            MeasureTool.Cylinder -> cylinder.onActivated()
        }
        tool = next
    }

    var session by remember { mutableStateOf<Session?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // Bumped by the watchdog only — recreates the ARCore Session (and its onSessionCreated
    // camera-texture registration) without touching the Engine above.
    var instanceKey by remember { mutableIntStateOf(0) }
    val isWarmedUp = ArWarmupGate.rememberArWarmedUp()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                sessionState.lastFrameAtMillis = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Releases every anchor every tool still holds when the screen goes away — mandatory now
    // that the session is long-lived and shared, so nothing else cleans up a half-drawn shape's
    // anchor incidentally on tab switch the way session teardown used to (see phase 03).
    DisposableEffect(Unit) {
        onDispose {
            distance.releaseAll()
            distanceChain.releaseAll()
            box.releaseAll()
            cylinder.releaseAll()
        }
    }

    var showModeSheet by remember { mutableStateOf(false) }
    var showUnitMenu by remember { mutableStateOf(false) }
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
                    // The union of what any of the three tools needs (insight 10) — the two
                    // pre-merge config blocks were already byte-identical.
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    val supported = configuredSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    sessionState.depthSupported = supported
                    config.depthMode =
                        if (supported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                    config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                },
                onSessionCreated = { session = it },
                onSessionUpdated = { updatedSession, frame ->
                    sessionState.noteFrame()
                    // Only the active tool's frame loop runs — an inactive tool costs nothing.
                    when (tool) {
                        MeasureTool.Distance ->
                            onFrame(distance, sessionState, projector, unit, updatedSession, frame, viewSize)
                        MeasureTool.DistanceChain ->
                            onFrame(distanceChain, sessionState, projector, unit, updatedSession, frame, viewSize)
                        MeasureTool.Box ->
                            onShapeFrame(box, sessionState, projector, unit, updatedSession, frame, viewSize)
                        MeasureTool.Cylinder ->
                            onShapeFrame(cylinder, sessionState, projector, unit, updatedSession, frame, viewSize)
                    }
                },
                onTrackingFailureChanged = { sessionState.trackingFailure = it },
            )
        }

        // Watchdog: recreates the session (not the Engine) after CameraWatchdogTimeoutMs of real
        // foreground time with no frame. shouldForceRemount is the pure decision two earlier fix
        // attempts got wrong — see its own KDoc.
        LaunchedEffect(instanceKey) {
            sessionState.lastFrameAtMillis = System.currentTimeMillis()
            while (true) {
                delay(CameraWatchdogPollIntervalMs)
                val isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (!isResumed) {
                    sessionState.lastFrameAtMillis = System.currentTimeMillis()
                    continue
                }
                if (
                    shouldForceRemount(
                        lastFrameAtMillis = sessionState.lastFrameAtMillis,
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
                DistanceOverlay(distance, projector, session, viewSize, Modifier.fillMaxSize())
            MeasureTool.DistanceChain ->
                DistanceOverlay(distanceChain, projector, session, viewSize, Modifier.fillMaxSize())
            MeasureTool.Box -> ShapeOverlay(frameProvider = { box.overlay }, modifier = Modifier.fillMaxSize())
            MeasureTool.Cylinder ->
                ShapeOverlay(frameProvider = { cylinder.overlay }, modifier = Modifier.fillMaxSize())
        }

        // One binding per active tool instead of a `when` at every callback below — the tool
        // holders don't share a supertype (kept that way to avoid a one-off interface for four
        // call sites), so this is the single place that reads which one is active right now.
        val actions = when (tool) {
            MeasureTool.Distance -> ToolActions(
                canUndo = distance.canUndo, undo = distance::undo,
                canRedo = distance.canRedo, redo = distance::redo,
                clear = distance::clear,
                addEnabled = distance.draggingIndex == null && distance.live != null && distance.liveStable,
            )
            MeasureTool.DistanceChain -> ToolActions(
                canUndo = distanceChain.canUndo, undo = distanceChain::undo,
                canRedo = distanceChain.canRedo, redo = distanceChain::redo,
                clear = distanceChain::clear,
                addEnabled = distanceChain.draggingIndex == null &&
                    distanceChain.live != null && distanceChain.liveStable,
            )
            MeasureTool.Box -> ToolActions(
                canUndo = box.canUndo, undo = box::undo,
                canRedo = box.canRedo, redo = box::redo,
                clear = box::clear, addEnabled = box.canCommitStep,
            )
            MeasureTool.Cylinder -> ToolActions(
                canUndo = cylinder.canUndo, undo = cylinder::undo,
                canRedo = cylinder.canRedo, redo = cylinder::redo,
                clear = cylinder::clear, addEnabled = cylinder.canCommitStep,
            )
        }

        ArCameraTopBar(
            canUndo = actions.canUndo,
            onUndo = actions.undo,
            canRedo = actions.canRedo,
            onRedo = actions.redo,
            onClose = onClose,
            unit = unit,
            onSelectUnit = { unit = it },
            showUnitMenu = showUnitMenu,
            onUnitClick = { showUnitMenu = true },
            onDismissUnitMenu = { showUnitMenu = false },
            onModeClick = { showModeSheet = true },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Defect 1 of the design update: the mock's toast overlaps the sheet by 52px — hidden
        // here while the sheet is open instead.
        if (!showModeSheet) {
            ARToast(
                text = commitToast ?: hintFor(tool, sessionState, distance, distanceChain, box, cylinder),
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
            onAddPoint = {
                session?.let { activeSession ->
                    val onCommit: (MeasurementResult) -> Unit = { result ->
                        commitToast = commitConfirmation
                        onResult(result)
                    }
                    when (tool) {
                        MeasureTool.Distance -> commitDistancePoint(distance, activeSession, unit, onCommit)
                        MeasureTool.DistanceChain ->
                            commitDistancePoint(distanceChain, activeSession, unit, onCommit)
                        MeasureTool.Box -> commitShapeStep(box, activeSession, unit, onCommit)
                        MeasureTool.Cylinder -> commitShapeStep(cylinder, activeSession, unit, onCommit)
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (showModeSheet) {
            // ModalBottomSheet renders in its own window (not this Box), so no .align() here —
            // Material3 positions and sizes the sheet itself.
            MeasureModeSheet(
                selected = tool,
                onSelect = {
                    selectTool(it)
                    showModeSheet = false
                },
                onDismiss = { showModeSheet = false },
            )
        }
    }
}

