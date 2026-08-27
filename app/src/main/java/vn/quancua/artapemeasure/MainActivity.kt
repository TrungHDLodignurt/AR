package vn.quancua.artapemeasure

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import vn.apero.armeasure.ar.presentation.host.ArMeasureHub
import vn.quancua.artapemeasure.ui.AppTab
import vn.quancua.artapemeasure.ui.AppTabBar

/** `:app` is a demo host proving the module's integration contract: a Measure tab whose body is
 * just [ArMeasureHub] — every AR/availability/permission concern now lives inside the module. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // enableEdgeToEdge()'s default nav bar style is SystemBarStyle.auto(...), which leaves
        // Window.isNavigationBarContrastEnforced = true — on 3-button nav mode that makes the
        // platform itself paint a translucent contrast scrim behind the buttons, so the bar still
        // reads as "visibly painted" even though window.navigationBarColor is transparent. A
        // non-auto style (dark/light) sets that flag false, giving a genuinely transparent bar so
        // the app's own capsule nav (AppTabBar) reads as the visual bottom, per design.
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        hideNavigationBar()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { AppRoot() }
        }
    }

    // Mirrors the module's own ArNavBarHidingActivity (AR_feature is a separate module, so this
    // is intentionally a small, standalone duplicate rather than a shared dependency): this demo
    // host hides the navigation bar so it is a faithful reference integration matching the design
    // mock (no nav bar on any screen) and the module's own ArCameraActivity/ArPhotoActivity. The
    // transparent SystemBarStyle above is kept — it only stops the platform's contrast scrim, it
    // does not hide the bar by itself. Re-applied on onResume/onWindowFocusChanged because a
    // hidden bar returns after user interaction.
    private fun hideNavigationBar() {
        runCatching {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onResume() {
        super.onResume()
        hideNavigationBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }
}

@Composable
private fun AppRoot() {
    var tab by remember { mutableStateOf(AppTab.Measure) }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            when (tab) {
                AppTab.Home -> Box(Modifier.fillMaxSize()) {
                    Text("Home", modifier = Modifier.align(Alignment.Center))
                }
                AppTab.Measure -> ArMeasureHub()
            }
        }
        AppTabBar(selected = tab, onSelect = { tab = it })
    }
}
