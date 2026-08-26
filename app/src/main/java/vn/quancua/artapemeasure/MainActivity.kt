package vn.quancua.artapemeasure

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import vn.apero.armeasure.ar.ArAvailability
import vn.apero.armeasure.ar.ArMeasureKit
import vn.apero.armeasure.ar.presentation.ruler.ArMeasureRulerScreen
import vn.apero.armeasure.ar.presentation.shapes.ArMeasureBoxScreen
import vn.apero.armeasure.ar.presentation.shapes.ArMeasureCylinderScreen
import vn.apero.armeasure.photo.data.CustomReferenceStore
import vn.apero.armeasure.photo.presentation.PhotoMeasureScreen
import vn.quancua.artapemeasure.ui.AppTab
import vn.quancua.artapemeasure.ui.AppTabBar

class MainActivity : ComponentActivity() {

    private var arAvailability by mutableStateOf(ArAvailability.Checking)
    private var cameraGranted by mutableStateOf(false)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 36 already forces edge-to-edge on API 35+; calling this explicitly makes
        // that behaviour consistent all the way down to minSdk 24 instead of differing by OS
        // version. The chrome that needs to stay clear of the status/nav bars (MeasureTopBar,
        // AppTabBar) pads for that itself via WindowInsets — see ui/AppTabBar.kt.
        enableEdgeToEdge()

        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) requestCamera.launch(Manifest.permission.CAMERA)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot(
                    arAvailability = arAvailability,
                    cameraGranted = cameraGranted,
                    onAvailabilityChange = { arAvailability = it },
                )
            }
        }
    }

    /**
     * Availability is resolved here, not in onCreate: `requestInstall` can navigate away and
     * come back, and the return trip is another onResume — which is when the install has
     * actually completed.
     */
    override fun onResume() {
        super.onResume()
        if (!ArMeasureKit.requestInstall(this)) {
            arAvailability = ArMeasureKit.checkAvailability(this)
        }
    }
}

@Composable
private fun AppRoot(
    arAvailability: ArAvailability,
    cameraGranted: Boolean,
    onAvailabilityChange: (ArAvailability) -> Unit,
) {
    val context = LocalContext.current

    // ArMeasureKit.checkAvailability can return Checking on its own first (async) call.
    // onResume never sees this settle if no further onResume arrives, so bound a re-poll here:
    // re-check every 200ms for up to ~3s, then fall through to Unsupported rather than leaving
    // the AR tabs blank forever.
    LaunchedEffect(arAvailability) {
        if (arAvailability != ArAvailability.Checking) return@LaunchedEffect
        var elapsedMs = 0
        while (elapsedMs < 3000) {
            delay(200)
            elapsedMs += 200
            val next = ArMeasureKit.checkAvailability(context)
            if (next != ArAvailability.Checking) {
                onAvailabilityChange(next)
                return@LaunchedEffect
            }
        }
        onAvailabilityChange(ArAvailability.Unsupported)
    }

    var tab by remember { mutableStateOf(AppTab.Measure) }
    val store = remember { CustomReferenceStore(context) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            when (tab) {
                AppTab.Measure -> when {
                    arAvailability == ArAvailability.Unsupported -> ArUnsupported()
                    // Checking and NeedsInstall both resolve off-screen (re-poll above, or the
                    // next onResume after requestInstall) — same blank placeholder either way.
                    arAvailability != ArAvailability.Ready -> Box(Modifier.fillMaxSize())
                    !cameraGranted -> CameraDenied()
                    else -> ArMeasureRulerScreen()
                }
                // No ARCore/camera-ar gating here on purpose: this tab measures from a still
                // photo via a reference object, so it works on every device this app installs
                // onto, including AR-unsupported ones.
                AppTab.PhotoMeasure -> PhotoMeasureScreen(referenceStore = store)
                AppTab.Box -> when {
                    arAvailability == ArAvailability.Unsupported -> ArUnsupported()
                    arAvailability != ArAvailability.Ready -> Box(Modifier.fillMaxSize())
                    !cameraGranted -> CameraDenied()
                    else -> ArMeasureBoxScreen()
                }
                AppTab.Cylinder -> when {
                    arAvailability == ArAvailability.Unsupported -> ArUnsupported()
                    arAvailability != ArAvailability.Ready -> Box(Modifier.fillMaxSize())
                    !cameraGranted -> CameraDenied()
                    else -> ArMeasureCylinderScreen()
                }
            }
        }
        AppTabBar(selected = tab, onSelect = { tab = it })
    }
}

@Composable
private fun ArUnsupported() {
    CenteredMessage(
        title = stringResource(R.string.ar_unsupported_title),
        body = stringResource(R.string.ar_unsupported_body),
    )
}

@Composable
private fun CameraDenied() {
    CenteredMessage(
        title = "Camera permission needed",
        body = "Measuring uses the camera to understand the room. Grant camera access in Settings to continue.",
    )
}

@Composable
private fun CenteredMessage(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(
            body,
            color = Color(0xB3FFFFFF),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
