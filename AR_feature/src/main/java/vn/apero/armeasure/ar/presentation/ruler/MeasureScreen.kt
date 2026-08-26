package vn.apero.armeasure.ar.presentation.ruler

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import kotlinx.coroutines.delay
import vn.apero.armeasure.R
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.data.warmup.ArWarmupGate
import vn.apero.armeasure.ar.domain.geometry.measureDistanceMeters
import vn.apero.armeasure.ar.domain.geometry.nearestIndexWithin
import vn.apero.armeasure.ar.presentation.shared.MeasureBottomBar
import vn.apero.armeasure.ar.presentation.shared.MeasureTopBar
import vn.apero.armeasure.common.data.UnitPreference
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.common.ui.UnitBtn
import vn.apero.armeasure.common.ui.UnitMenu

/**
 * How often the watchdog below checks whether ARCore frames are still arriving.
 */
private const val CameraWatchdogPollIntervalMs = 1_000L

/**
 * How long without a single ARCore frame — of actual foreground time, see the gating in the
 * watchdog loop below — counts as "stuck", not just a slow start.
 *
 * This is the ONLY recovery mechanism now (an earlier attempt also force-remounted on every
 * plain resume, proactively — that was worse: it tore down and reopened the camera even after a
 * trivial round trip that was already working fine, and the close-then-reopen race that
 * introduces turned out to be a more frequent failure than the one it was meant to fix).
 * Root cause, confirmed on-device via a full Java stack trace while the screen was black:
 * ```
 * com.google.ar.core.exceptions.TextureNotSetException
 *     at com.google.ar.core.Session.update(Session.java)
 *     at io.github.sceneview.ar.arcore.ARSession.updateOrNull(ArSession.kt:155)
 *     at io.github.sceneview.SceneRenderer.renderFrame(SceneRenderer.kt:251)
 * ```
 * `arsceneview`'s `ARSceneView.kt` calls `session.setCameraTextureNames(...)` exactly ONCE,
 * inside `onSessionCreated` — which fires once per `ARSceneView` mount. Its lifecycle observer's
 * `onPause`/`onResume` only call `Session.pause()`/`Session.resume()`; neither ever re-registers
 * the camera texture. If backgrounding invalidates the underlying GL texture (common after a
 * long background — the surface gets torn down), the Session keeps a stale texture id forever,
 * and every `update()` call throws `TextureNotSetException` from the very first frame after
 * resume — permanently, regardless of camera hardware (confirmed separately: `CameraService`
 * logs show the camera opening and streaming normally the whole time) and regardless of
 * restarting the app process (confirmed by the user: force-killing and relaunching still showed
 * the bug — only clearing app storage, which resets more than our own negligible app data,
 * incidentally cleared it).
 *
 * Since a stuck session fails from its very first post-resume frame rather than degrading
 * gradually, this doesn't need to be long to tell "stuck" apart from "slow but working" — kept
 * moderate rather than generous now that it only ever measures genuine foreground stall time.
 */
private const val CameraWatchdogTimeoutMs = 10_000L

/**
 * The point-to-point ruler tool.
 *
 * Nothing is rendered as Filament geometry: the AR view supplies the camera feed, tracking and
 * hit tests, and every measurement graphic is drawn by [MeasureOverlay] in a 2D Canvas above
 * it. Perspective projection preserves straight lines, so a screen-space line between two
 * projected anchors is exact, not an approximation — and dashed strokes plus screen-constant
 * label pills, both of which the reference app has, are trivial in Canvas and awkward in 3D.
 *
 * @param unit fallback display unit for the very first launch, before any [UnitPreference] value
 *   has ever been written; the persisted, process-wide choice (read on enter, written back on
 *   every change via [UnitBtn]/[UnitMenu]) takes over from then on — see decision 8.
 * @param onResult fires once per committed segment (a new point that forms a pair with the
 *   previous one), never per frame.
 * @param onClose when non-null, shows a "✕" pill in the top bar that invokes it.
 */
@Composable
fun ArMeasureRulerScreen(
    modifier: Modifier = Modifier,
    unit: LengthUnit = LengthUnit.Cm,
    onResult: (MeasurementResult.Distance) -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val unitPreference = remember { UnitPreference(context) }
    // unitPreference.unit already falls back to LengthUnit.Cm on a first-ever launch — same
    // value as the unit param's own default, so the persisted store is the single seed.
    val state = remember { MeasureState(unitPreference.unit) }
    LaunchedEffect(state.unit) { unitPreference.unit = state.unit }
    val projector = remember { PoseProjector() }

    // Created ONCE for the screen's whole lifetime — this is how every other ARSceneView usage
    // (the library's own samples, other apps built on it) does it. An earlier attempt moved
    // these inside the key(instanceKey) block below so a watchdog remount would tear down and
    // recreate the whole Filament Engine, not just the ARCore Session — reasoning that the
    // Engine, not just the Session, owned the stale-texture state. That made recovery WORSE, not
    // better (confirmed: near-100% failure rate afterward, even on a fresh cold start) — Engine
    // objects are heavyweight, GPU-resource-owning, and destroying/recreating one on a tight
    // ~10s retry cadence very plausibly leaves GPU resources mid-teardown when the next Engine
    // tries to claim the camera texture, i.e. it looks like it was compounding the very race
    // it was meant to fix. Reverted to the standard, single-Engine usage.
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    var session by remember { mutableStateOf<Session?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Bumped by the watchdog below to force a remount of ARSceneView — recreates the ARCore
    // Session (and re-runs its onSessionCreated camera-texture registration) without touching
    // the Engine above.
    var instanceKey by remember { mutableIntStateOf(0) }

    // Tried forcing a full ARSceneView remount on every resume — WORSE, not better: it tore
    // down and reopened the camera even after a trivial, working background/resume round trip
    // (previously fine on its own), and the close-then-immediately-reopen race that introduces
    // on EVERY resume is a more frequent failure mode than the rare stale-texture case it was
    // meant to fix. Reverted to something less invasive: a resume just gives the *existing*
    // session's watchdog a fresh, fair timeout window (below) instead of judging it by time
    // elapsed while the app wasn't even in the foreground to produce frames — remounting stays
    // the watchdog's call, made only once a session actually fails to produce a frame after
    // really resuming.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.lastFrameAtMillis = System.currentTimeMillis()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Releases every anchor this state still holds (committed points plus anything on the redo
    // stack) when the screen goes away — see MeasureState.releaseAll's doc for why this can no
    // longer rely on incidental ARCore session teardown once phase 05 shares one long-lived
    // session across tabs.
    DisposableEffect(state) {
        onDispose { state.releaseAll() }
    }

    val isWarmedUp = ArWarmupGate.rememberArWarmedUp()

    Box(modifier = modifier.fillMaxSize().onSizeChanged { viewSize = it }) {

        if (!isWarmedUp) {
            HintBanner(
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
                    state.lastFrameAtMillis = System.currentTimeMillis()
                    onFrame(state, projector, updatedSession, frame, viewSize)
                },
                onTrackingFailureChanged = { state.trackingFailure = it },
            )
        }

        // Watchdog: if no ARCore frame arrives for CameraWatchdogTimeoutMs of actual foreground
        // time, the session is considered stuck (see the constant's doc) and gets
        // force-recreated. Keyed on instanceKey so each remount starts its own fresh timeout
        // window instead of tripping again immediately on the brand-new session before it has
        // had a chance to open the camera.
        //
        // Gated on the Activity actually being resumed: a `LaunchedEffect` keeps running while
        // backgrounded (Compose doesn't pause it just because the Activity did), so without this
        // check the stall clock keeps ticking through an arbitrarily long background period and
        // can fire — forcing a remount — while the app isn't even in the foreground to render
        // anything, which cannot possibly succeed and just wastes a cycle. Instead the deadline
        // is kept pushed out the whole time the app is backgrounded, so it only ever measures
        // real "resumed but no frame" time, same as the ON_RESUME reset above.
        LaunchedEffect(instanceKey) {
            state.lastFrameAtMillis = System.currentTimeMillis()
            while (true) {
                delay(CameraWatchdogPollIntervalMs)
                if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    state.lastFrameAtMillis = System.currentTimeMillis()
                    continue
                }
                val stalledFor = System.currentTimeMillis() - state.lastFrameAtMillis
                if (stalledFor > CameraWatchdogTimeoutMs) {
                    instanceKey++
                    break
                }
            }
        }

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
            canRedo = state.canRedo,
            onRedo = state::redo,
            onClear = state::clear,
            onClose = onClose,
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
            // Gated on draggingIndex too: a second finger tapping + mid-drag would add a point
            // and edit one at the same time, which is a confusing thing for one tap to do.
            addEnabled = state.draggingIndex == null && state.live != null && state.liveStable,
            onAddPoint = {
                session?.let {
                    val committed = state.commitLivePoint(it)
                    if (committed && state.worldPoints.size >= 2) {
                        val points = state.worldPoints
                        val meters = measureDistanceMeters(points[points.size - 2], points[points.size - 1])
                        onResult(MeasurementResult.Distance(meters, state.unit))
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Shared with [ShapeMeasureScreen][vn.apero.armeasure.ar.presentation.shapes.ShapeMeasureScreen] — the box/cylinder tools show the same kind of one-line hint. */
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
            TrackingFailureReason.BAD_STATE -> R.string.armeasure_tracking_bad_state
            TrackingFailureReason.INSUFFICIENT_LIGHT -> R.string.armeasure_tracking_insufficient_light
            TrackingFailureReason.EXCESSIVE_MOTION -> R.string.armeasure_tracking_excessive_motion
            TrackingFailureReason.INSUFFICIENT_FEATURES -> R.string.armeasure_tracking_insufficient_features
            TrackingFailureReason.CAMERA_UNAVAILABLE -> R.string.armeasure_tracking_camera_unavailable
            TrackingFailureReason.NONE -> null
        }
        if (res != null) return stringResource(res)
    }
    return when {
        // Direct manipulation is already happening — nothing about surface-hunting is relevant
        // while the user's finger is on a point they placed a moment ago.
        state.draggingIndex != null -> stringResource(R.string.armeasure_hint_dragging_point)
        // Ahead of the plane hint: a reading that will not hold still is a specific, fixable
        // problem, and telling the user to keep hunting for a surface would be misleading when
        // the reticle is already on one that simply cannot be measured.
        state.live != null && !state.liveStable -> stringResource(R.string.armeasure_hint_reading_unsteady)
        !state.anyPlaneTracked -> stringResource(R.string.armeasure_hint_move_to_find_surface)
        state.live == null -> stringResource(R.string.armeasure_hint_aim_at_surface)
        state.points.isEmpty() -> stringResource(R.string.armeasure_hint_tap_to_start)
        // Once measuring, show what the last point was resolved from: a reading you cannot
        // attribute is a reading you cannot calibrate.
        else -> state.lastSource?.let { "Point ${state.points.size} on ${it.label}" }
    }
}
