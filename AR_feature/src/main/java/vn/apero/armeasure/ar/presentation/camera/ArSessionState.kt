package vn.apero.armeasure.ar.presentation.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.TrackingFailureReason

/**
 * Facts about the one shared ARCore session, not about any single tool.
 *
 * Before phase 05, `MeasureState` and `ShapeMeasureState` each carried their own copy of these
 * six fields — one writer each ([depthSupported] from `sessionConfiguration`, [trackingFailure]
 * from `onTrackingFailureChanged`, the rest from the per-frame callback). Now that
 * Distance/Box/Cylinder share one `ARSceneView`, there is exactly one session, so there is
 * exactly one of each fact — same values, same writers, just a new, single owner instead of one
 * duplicate per tool.
 */
internal class ArSessionState {

    /**
     * Wall-clock time of the last ARCore frame that actually arrived. Backed by Compose state
     * (not a plain `var`) because the AR library's frame callback may not run on the same thread
     * as the watchdog's polling coroutine in `ArCameraScreen` — a plain field would risk the
     * watchdog reading a stale value across threads.
     */
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
