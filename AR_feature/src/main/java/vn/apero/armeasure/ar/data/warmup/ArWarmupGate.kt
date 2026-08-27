package vn.apero.armeasure.ar.data.warmup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Warm-up gate for `ArCameraScreen`'s one shared `ARSceneView` (Distance/Box/Cylinder alike):
 * `true` once it is safe to mount it, `false` while the warm-up delay is still running.
 *
 * The race it guards: ARCore's `Session.update()` can beat the GPU/camera driver's own init and be
 * called before `setCameraTextureNames(...)` has completed, throwing `TextureNotSetException` on
 * every frame thereafter with no recovery short of a remount. Confirmed by hand on-device (Pixel 6
 * and POCO X7 both affected, a Samsung device not), and matches a long-standing open ARCore SDK
 * issue rather than anything specific to this app. Delaying the first mount reproduces the gap that
 * a manual navigate-away-and-back was found to create, which reliably cleared a live repro.
 */
internal object ArWarmupGate {

    /**
     * How long to wait before mounting an `ARSceneView`.
     *
     * **Tune here.** This is a blunt, generous margin, not a measured minimum — the race window's
     * width depends on how fast each vendor's GPU/camera driver initialises, so a value measured on
     * one device does not transfer. Lowering it needs repeated trials (the race is probabilistic: one
     * clean run at a shorter delay proves nothing) on more than one affected device.
     */
    private const val ArWarmupDelayMs = 2_000L

    /**
     * `true` once it is safe to mount `ARSceneView`, `false` while the delay runs.
     *
     * **The delay runs on every entry to the AR screen, deliberately — not once per process.**
     *
     * It used to be gated behind a process-global `hasAttemptedArWarmup` flag, on the reasoning that
     * the race belonged to process cold-start and that returning to an AR *tab* later in the same
     * process was already past the danger window. That reasoning was correct when AR was one tab in a
     * long-lived Activity whose ARCore session persisted. Two later changes invalidated it:
     *
     * 1. AR became its own Activity (`ArCameraActivity`), so leaving the screen destroys the session.
     * 2. Entering the photo path deliberately tears the AR session down, since photo measuring needs
     *    no ARCore and the session's depth + two-orientation plane finding is the app's main heat
     *    source.
     *
     * So every entry now builds a brand-new `Session` and `Engine` — exactly the cold-start condition
     * this gate exists for — while the one-shot flag was still reporting "already warmed up" and
     * mounting immediately. The first entry was protected and every subsequent one was not, which
     * surfaced as the camera failing to initialise after a photo → AR round trip.
     */
    @Composable
    fun rememberArWarmedUp(): Boolean {
        var isWarmedUp by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(ArWarmupDelayMs)
            isWarmedUp = true
        }
        return isWarmedUp
    }
}
