package vn.apero.armeasure.ar.presentation.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.TrackingFailureReason

/**
 * The **session-level frame stream**: facts about the one shared ARCore session, not about any
 * single tool, every one of them written from an ARCore callback rather than by a user action.
 *
 * Deliberately NOT an MVI `State` and deliberately not its own Contract/ViewModel pair (phase 04
 * asked the question explicitly; this is the answer). Nothing here is a screen and nothing here is
 * a user decision: [tracking] and [anyPlaneTracked] are rewritten from `onSessionUpdated` at
 * 30-60 Hz, [depthSupported] once from `sessionConfiguration`, [trackingFailure] from
 * `onTrackingFailureChanged`, [lastFrameAtMillis]/[cameraReady] from every frame that arrives.
 * Routing that through `processIntent -> SharedFlow -> handleIntent -> updateState { copy() } ->
 * StateFlow` would cost a coroutine dispatch and a full state allocation *per frame*, and would
 * replace Compose's per-field invalidation with whole-state invalidation. Transient render state
 * is not UI state.
 *
 * Owned by `ArCameraScreen`'s `remember` rather than by a ViewModel, because the thing it describes
 * — one `ARSceneView`, one `Session` — is itself created and destroyed with the composition. A
 * ViewModel-scoped copy would outlive the session it describes and start every re-entry by
 * reporting the *previous* session's tracking flags.
 *
 * Backed by Compose state (not plain `var`s) because the AR library's frame callback may not run on
 * the same thread as the watchdog's polling coroutine in `ArCameraScreen`, and because the chrome
 * reads these values inside composition.
 */
internal class ArSessionFrameStream {

    /** Wall-clock time of the last ARCore frame that actually arrived — the watchdog's clock. */
    var lastFrameAtMillis by mutableStateOf(System.currentTimeMillis())

    var cameraReady by mutableStateOf(false)
    var tracking by mutableStateOf(false)
    var anyPlaneTracked by mutableStateOf(false)
    var depthSupported by mutableStateOf(false)
    var trackingFailure by mutableStateOf<TrackingFailureReason?>(null)

    /** Marks a frame as having arrived: flips [cameraReady] and refreshes the watchdog's clock. */
    fun noteFrame() {
        cameraReady = true
        lastFrameAtMillis = System.currentTimeMillis()
    }
}

/**
 * Pure decision behind the stall watchdog in `ArCameraScreen` — extracted so the one piece of
 * logic that two earlier, confident fix attempts got wrong (see
 * `report-260825-1703-session-handoff-box-cylinder-measure.md` §11) is finally testable.
 *
 * §11.1 recreated the whole Filament `Engine` on this cadence instead of just the ARCore
 * `Session` — near-100% failure rate afterward. §11.2 forced a remount on every plain
 * `ON_RESUME` instead of only on a genuine stall — regressed the common case with a close-then-
 * reopen race. Neither touched this decision function; both changed what happened once it fired,
 * or fired it too eagerly. This function is deliberately unchanged by either wrong turn.
 *
 * @param isResumed gates the whole check: a `LaunchedEffect` keeps running while the app is
 *   backgrounded, so without this a long background period would itself look like a stall and
 *   trigger a pointless remount of a session that was never given a chance to produce a frame.
 * @return `true` only when resumed AND the elapsed time since the last frame exceeds [timeoutMs]
 *   — strict `>`, so landing exactly on the timeout is not yet a stall.
 */
internal fun shouldForceRemount(
    lastFrameAtMillis: Long,
    nowMillis: Long,
    isResumed: Boolean,
    timeoutMs: Long,
): Boolean = isResumed && (nowMillis - lastFrameAtMillis) > timeoutMs
