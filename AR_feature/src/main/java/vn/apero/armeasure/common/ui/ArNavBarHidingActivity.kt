package vn.apero.armeasure.common.ui

import android.content.Context
import androidx.activity.ComponentActivity
import vn.apero.armeasure.ArMeasureConfig
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Base class for the module's own full-screen Activities (`ArCameraActivity`, `ArPhotoActivity`):
 * hides the navigation bar, transient-by-swipe, matching both the design mock (no nav bar on any
 * screen) and the host `AIP936-AIHomeDesign`'s `BaseComposeActivity.applySystemBarsVisibility()`,
 * which this mirrors — down to re-applying on [onResume] and [onWindowFocusChanged], because a
 * hidden bar returns after user interaction and a one-shot call in `onCreate` silently stops
 * working. `ArMeasureHub` itself needs none of this: it is embedded in the *host's* Activity, so
 * it inherits whatever bar behaviour the host applies there. Only `ArCameraActivity` and
 * `ArPhotoActivity` are module-owned Activities the host's base class never reaches.
 *
 * Only the navigation bar is hidden, never the status bar — the module's screens keep a
 * hint/toast row near the top, matching the design's status-bar row.
 *
 * Kept as a single base class rather than duplicating the same `onResume`/`onWindowFocusChanged`
 * overrides in both Activities — this module has already shipped that exact bug once, in commit
 * `6a5cb50`: the AR cold-start warm-up guard existed only in the ruler screen while the shape screen
 * mounted its own `ARSceneView` unguarded, so launching straight into Box or Cylinder hit the race
 * with no mitigation at all. Call
 * [hideNavigationBar] once more from `onCreate`, right after `enableEdgeToEdge()`, so the bar is
 * already hidden before the first frame; [onResume] always runs right after `onCreate`, so this is
 * a belt-and-braces call, not a required one.
 */
internal abstract class ArNavBarHidingActivity : ComponentActivity() {

    /**
     * Routes the base context through the host's [vn.apero.armeasure.ArMeasureContextWrapper], if it
     * installed one, so these Activities render in the language the host is showing rather than the
     * device's.
     *
     * The hub needs no equivalent: it is composed inside the *host's* Activity and so already reads
     * whatever context that Activity was given. These two Activities are the only ones the host's
     * own base class never reaches, which is exactly why the module used to come up in English on a
     * host whose language had been switched in-app.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ArMeasureConfig.wrapContext(newBase))
    }

    override fun onResume() {
        super.onResume()
        hideNavigationBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    /**
     * Wrapped in [runCatching] like the host does — a failure to hide the bar must never crash
     * the screen, it is a cosmetic nicety over a fully functional one.
     */
    protected fun hideNavigationBar() {
        runCatching {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
}
