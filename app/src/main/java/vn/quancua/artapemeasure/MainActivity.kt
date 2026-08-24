package vn.quancua.artapemeasure

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableException
import vn.quancua.artapemeasure.level.LevelScreen
import vn.quancua.artapemeasure.measure.MeasureScreen
import vn.quancua.artapemeasure.photomeasure.PhotoMeasureScreen
import vn.quancua.artapemeasure.ui.AppTab
import vn.quancua.artapemeasure.ui.AppTabBar

/** Whether AR can run at all on this device+install. */
private enum class ArAvailability { Checking, Ready, Unsupported }

class MainActivity : ComponentActivity() {

    /**
     * ARCore ships as a separate APK (Google Play Services for AR), so a first run may need to
     * send the user to the Play Store. After that redirect this must become false, otherwise
     * every return to the app re-opens the install dialog and the user can never get in.
     */
    private var userRequestedInstall = true
    private var arAvailability by mutableStateOf(ArAvailability.Checking)
    private var cameraGranted by mutableStateOf(false)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) requestCamera.launch(Manifest.permission.CAMERA)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot(
                    arAvailability = arAvailability,
                    cameraGranted = cameraGranted,
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
        try {
            when (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                ArCoreApk.InstallStatus.INSTALLED -> arAvailability = ArAvailability.Ready
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> userRequestedInstall = false
            }
        } catch (_: UnavailableException) {
            // Device not capable, user declined the install, SDK too old, and so on. The Level
            // tab still works, so this is a degraded app rather than a dead one.
            arAvailability = ArAvailability.Unsupported
        }
    }
}

@Composable
private fun AppRoot(arAvailability: ArAvailability, cameraGranted: Boolean) {
    var tab by remember { mutableStateOf(AppTab.Measure) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            when (tab) {
                AppTab.Measure -> when {
                    arAvailability == ArAvailability.Unsupported -> ArUnsupported()
                    arAvailability == ArAvailability.Checking -> Box(Modifier.fillMaxSize())
                    !cameraGranted -> CameraDenied()
                    else -> MeasureScreen()
                }
                // No ARCore/camera-ar gating here on purpose: this tab measures from a still
                // photo via a reference object (see photomeasure/PhotoMeasureScreen.kt), so it
                // works on every device this app installs onto, including AR-unsupported ones.
                AppTab.PhotoMeasure -> PhotoMeasureScreen()
                AppTab.Level -> LevelScreen()
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
