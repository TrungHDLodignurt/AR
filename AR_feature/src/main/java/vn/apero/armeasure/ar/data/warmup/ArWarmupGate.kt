package vn.apero.armeasure.ar.data.warmup

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Warm-up gate for `ArCameraScreen`'s one shared `ARSceneView` (Distance/Box/Cylinder alike):
 * `true` once it is safe to mount it, `false` while the one-time warm-up delay is still running.
 *
 * Was only ever implemented in the old, separate ruler screen originally — the old, separate
 * shape screen mounted its own `ARSceneView` immediately with no equivalent guard, so a cold
 * launch straight into Box or Cylinder was exposed to the identical race unmitigated. Extracted
 * here once that gap was found, rather than duplicating the flag and delay a second time (the
 * exact bug class fixed in commit `6a5cb50`) — kept as a single `object` rather than per-file
 * `private var`s for the same reason. Phase 05's merge into one shared mount point makes that bug
 * class structurally impossible going forward, but the gate itself is unchanged.
 */
internal object ArWarmupGate {

    /**
     * How long to wait before ever mounting an `ARSceneView` for the first time in this process.
     *
     * Confirmed by hand on-device (Pixel 6 + POCO X7, both affected; a Samsung device was not): on a
     * cold app launch, ARCore's `Session.update()` races the GPU/camera driver's own init and can call
     * it before `setCameraTextureNames(...)` has actually completed, throwing `TextureNotSetException`
     * on every frame thereafter with no way back short of a remount. Switching away from an AR tab and
     * back — which fully unmounts and remounts everything, Engine included, with a couple seconds'
     * gap in between — reliably cleared it in testing, with no code change at all. This reproduces
     * that same gap on the very first AR mount instead of requiring the user to discover the
     * workaround themselves. Device-dependent (a fast enough driver never hits the race regardless),
     * so this is a blunt, generous margin, not a measured minimum.
     */
    private const val ArWarmupDelayMs = 2_000L

    /**
     * Set once this process has attempted the warm-up delay above — never reset except by a fresh
     * process (kill + relaunch), which is exactly the boundary that needs it: switching tabs back to
     * an AR screen later in the same process is already past the cold-start race window.
     *
     * Deliberately process-global (not per-screen state): the race is about the GPU/camera driver's
     * own readiness at process cold-start, not about which specific AR tab happens to mount first —
     * whichever of Measure/Box/Cylinder the user opens first pays the delay once, and the other two
     * never pay it again in the same process.
     */
    private var hasAttemptedArWarmup = false

    /**
     * Shared by every AR screen: `true` once it is safe to mount `ARSceneView`, `false` while the
     * one-time warm-up delay is still running.
     */
    @Composable
    fun rememberArWarmedUp(): Boolean {
        var isWarmedUp by remember { mutableStateOf(hasAttemptedArWarmup) }
        LaunchedEffect(Unit) {
            if (!hasAttemptedArWarmup) {
                hasAttemptedArWarmup = true
                delay(ArWarmupDelayMs)
                isWarmedUp = true
            }
        }
        return isWarmedUp
    }

    /** Resets the process-global flag — test-only, so each test starts from a cold-launch state. */
    @VisibleForTesting
    internal fun reset() {
        hasAttemptedArWarmup = false
    }
}
