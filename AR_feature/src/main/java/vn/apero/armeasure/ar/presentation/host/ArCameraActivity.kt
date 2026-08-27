package vn.apero.armeasure.ar.presentation.host

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import vn.apero.armeasure.ar.ArAvailability
import vn.apero.armeasure.ar.ArMeasureKit
import vn.apero.armeasure.ar.presentation.camera.ArCameraScreen
import vn.apero.armeasure.common.ui.ArMeasureTheme
import vn.apero.armeasure.common.ui.ArNavBarHidingActivity

/**
 * Module-owned, full-screen AR camera Activity — the hub only decides whether to show the card
 * ([rememberArAvailability] is read-only there); everything that can only run from inside an
 * Activity lives here: [ArMeasureKit.requestInstall] (a Play Store round trip whose return is
 * this Activity's own `onResume`), the CAMERA permission request, and the
 * Unsupported/permission-denied screens.
 *
 * The body renders [ArCameraScreen] — one shared ARCore session across Distance/Box/Cylinder
 * (phase 05); the design's real tool-picker sheet replacing today's debug switcher is phase 06.
 *
 * Backing out of this Activity finishes it, which tears down the whole composition — and with it
 * the Filament Engine and the ARCore Session it owns — for free. That is the entire mechanism
 * behind "entering Photo tears AR down": there is no teardown code to write as long as this
 * Activity is not `singleTask`/retained (it is not — see the manifest).
 */
internal class ArCameraActivity : ArNavBarHidingActivity() {

    private var arAvailability by mutableStateOf(ArAvailability.Checking)
    private var cameraGranted by mutableStateOf(false)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideNavigationBar()

        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) requestCamera.launch(Manifest.permission.CAMERA)

        setContent {
            ArMeasureTheme(dark = true) {
                // ARCore's own first checkAvailability call can return Checking with no further
                // onResume to notice it settle (the user never left the app) — bounded re-poll,
                // shared with the hub's rememberArAvailability so the cadence is defined once.
                val context = LocalContext.current
                LaunchedEffect(arAvailability) {
                    rePollArAvailability(context, arAvailability) { arAvailability = it }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        arAvailability == ArAvailability.Unsupported -> ArUnsupported()
                        // Checking and NeedsInstall both resolve off-screen: the re-poll in
                        // rememberArAvailability, or the next onResume after requestInstall
                        // below. Same blank placeholder either way.
                        arAvailability != ArAvailability.Ready -> Box(Modifier.fillMaxSize())
                        !cameraGranted -> CameraDenied()
                        else -> ArCameraScreen(onClose = { finish() })
                    }
                }
            }
        }
    }

    /**
     * Availability is resolved here, not in onCreate: [ArMeasureKit.requestInstall] can navigate
     * to the Play Store and back, and the return trip is another onResume — which is when the
     * install has actually completed. Lifted verbatim from the pre-phase-04 `MainActivity`.
     */
    override fun onResume() {
        super.onResume()
        // requestInstall stays on the main thread — it may start an Activity, and it is cheap when
        // there is nothing to install. checkAvailability does not: see ArAvailabilityGate's header
        // for what it costs, which is why it is dispatched instead of called here.
        if (!ArMeasureKit.requestInstall(this)) {
            lifecycleScope.launch { arAvailability = checkArAvailabilityOffMain(this@ArCameraActivity) }
        }
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, ArCameraActivity::class.java)
        fun start(context: Context) = context.startActivity(newIntent(context))
    }
}
