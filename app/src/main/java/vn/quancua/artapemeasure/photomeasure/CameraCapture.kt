package vn.quancua.artapemeasure.photomeasure

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * Launches the system camera app to take one photo, delivered as a `content://` Uri.
 *
 * `TakePicture` needs a destination Uri to already exist before it launches — unlike the photo
 * picker, it doesn't hand back a Uri of its own choosing. That Uri has to be a `content://` one
 * (not a raw `file://`) for the camera app to be allowed to write to it at all on API 24+, which
 * is what the FileProvider declared in the manifest is for.
 */
@Composable
fun rememberCameraCaptureLauncher(onPhotoTaken: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingUri
        pendingUri = null
        if (success && uri != null) onPhotoTaken(uri)
    }

    return {
        val uri = createCameraCaptureUri(context)
        pendingUri = uri
        launcher.launch(uri)
    }
}

private fun createCameraCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera-capture").apply { mkdirs() }
    val file = File(dir, "ref-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
