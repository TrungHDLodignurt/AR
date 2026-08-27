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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { AppRoot() }
        }
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
