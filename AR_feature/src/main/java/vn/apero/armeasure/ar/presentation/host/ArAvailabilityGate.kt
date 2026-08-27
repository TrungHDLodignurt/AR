package vn.apero.armeasure.ar.presentation.host

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import vn.apero.armeasure.ar.ArAvailability
import vn.apero.armeasure.ar.ArMeasureKit

private const val RePollIntervalMs = 200L
private const val RePollBudgetMs = 3_000L

/**
 * Read-only availability, re-polled while [ArMeasureKit.checkAvailability] itself is still
 * resolving. Lifted verbatim from the pre-phase-04 `MainActivity.AppRoot`'s `LaunchedEffect`:
 * ARCore's own first call can return [ArAvailability.Checking] before it has a real answer, and
 * nothing here is a Google Play Store round trip that would need an `onResume` to notice — so a
 * plain re-poll, bounded to [RePollBudgetMs] before falling through to
 * [ArAvailability.Unsupported], is enough.
 *
 * Never calls ARCore during composition. `ArCoreApk.checkAvailability` is not the cheap accessor its
 * name suggests: measured on a Joy_4 it runs reflection over `ActivityInfo`, throws and catches a
 * `NoSuchFieldException`, and does JNI work — all synchronously on the calling thread. Called from a
 * `remember {}` block it cost 47 dropped frames, about 780 ms, on every cold entry to the tab. The
 * initial value is therefore [ArAvailability.Checking] and the first real check happens on
 * [Dispatchers.IO] like the re-polls that follow it. Nothing flickers as a result: the hub renders
 * both cards for `Checking` exactly as it does for `Ready`.
 *
 * Read-only on purpose: [requestInstall][ArMeasureKit.requestInstall] navigates to the Play
 * Store, whose return trip is another `onResume` — of whichever Activity launched it. This
 * composable has no Activity of its own to receive that, so it never calls `requestInstall`;
 * [ArCameraActivity] owns that half of the state machine (reusing [rePollArAvailability] below so
 * both call sites share one bounded-poll implementation).
 */
@Composable
internal fun rememberArAvailability(): ArAvailability {
    val context = LocalContext.current
    var availability by remember { mutableStateOf(ArAvailability.Checking) }

    LaunchedEffect(context) {
        availability = checkArAvailabilityOffMain(context)
        rePollArAvailability(context, availability) { availability = it }
    }

    return availability
}

/**
 * [ArMeasureKit.checkAvailability] on [Dispatchers.IO].
 *
 * Every caller goes through this rather than calling ARCore directly — see this file's header for
 * what that call actually costs on a real device.
 */
internal suspend fun checkArAvailabilityOffMain(context: Context): ArAvailability =
    withContext(Dispatchers.IO) { ArMeasureKit.checkAvailability(context) }

/**
 * Shared bounded re-poll: while [current] is [ArAvailability.Checking], calls
 * [ArMeasureKit.checkAvailability] every [RePollIntervalMs] for up to [RePollBudgetMs] and
 * invokes [onResolved] with the first non-[ArAvailability.Checking] answer, or with
 * [ArAvailability.Unsupported] once the budget runs out. A no-op when [current] has already
 * resolved. Shared by [rememberArAvailability] and [ArCameraActivity]'s own
 * `onResume`-plus-Checking-state handling so the 200ms x 15 cadence is defined exactly once.
 */
internal suspend fun rePollArAvailability(
    context: Context,
    current: ArAvailability,
    onResolved: (ArAvailability) -> Unit,
) {
    if (current != ArAvailability.Checking) return
    var elapsedMs = 0L
    while (elapsedMs < RePollBudgetMs) {
        delay(RePollIntervalMs)
        elapsedMs += RePollIntervalMs
        val next = checkArAvailabilityOffMain(context)
        if (next != ArAvailability.Checking) {
            onResolved(next)
            return
        }
    }
    onResolved(ArAvailability.Unsupported)
}
