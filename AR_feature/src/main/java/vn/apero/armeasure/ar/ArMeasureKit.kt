package vn.apero.armeasure.ar

import android.app.Activity
import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableException

/** Whether AR can run at all on this device+install. */
internal enum class ArAvailability {
    /**
     * ARCore's own async first-call state: the availability check has not resolved yet. Not a
     * final answer — the caller should re-poll [ArMeasureKit.checkAvailability] shortly rather
     * than treating this as either ready or unsupported.
     */
    Checking,
    Ready,
    NeedsInstall,
    Unsupported,
}

/**
 * Entry point for checking AR availability and, if needed, requesting the ARCore install before
 * mounting any of this module's AR screens ([ArMeasureRulerScreen], [ArMeasureBoxScreen],
 * [ArMeasureCylinderScreen]).
 */
internal object ArMeasureKit {

    /**
     * ARCore ships as a separate APK (Google Play Services for AR), so a first run may need to
     * send the user to the Play Store. After that redirect this must become false, otherwise
     * every return to the app re-opens the install dialog and the user can never get in.
     */
    private var userRequestedInstall = true

    /**
     * Maps ARCore's own availability check to this module's [ArAvailability].
     *
     * [ArAvailability.Checking] is ARCore's own async first-call state — it has not resolved yet,
     * not a final answer. The caller should re-poll shortly rather than treating it as either
     * ready or unsupported.
     */
    fun checkAvailability(context: Context): ArAvailability =
        when (ArCoreApk.getInstance().checkAvailability(context)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArAvailability.Ready
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            -> ArAvailability.NeedsInstall
            ArCoreApk.Availability.UNKNOWN_CHECKING -> ArAvailability.Checking
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
            ArCoreApk.Availability.UNKNOWN_ERROR,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
            -> ArAvailability.Unsupported
        }

    /**
     * Requests the ARCore install if needed. Call from an Activity that has just resolved
     * [checkAvailability] to [ArAvailability.NeedsInstall] — `requestInstall` can navigate away
     * to the Play Store and back, and the return trip is another onResume, which is when the
     * install has actually completed.
     *
     * @return `true` when the user was redirected to the Play Store install flow, `false`
     *   otherwise (already installed, or the device/user cannot proceed).
     */
    fun requestInstall(activity: Activity): Boolean {
        return try {
            when (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)) {
                ArCoreApk.InstallStatus.INSTALLED -> false
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    userRequestedInstall = false
                    true
                }
            }
        } catch (_: UnavailableException) {
            // Device not capable, user declined the install, SDK too old, and so on. Caught, not
            // thrown — a non-capable device is a degraded app, not a crash.
            false
        }
    }
}
