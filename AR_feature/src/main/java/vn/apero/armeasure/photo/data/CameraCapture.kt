package vn.apero.armeasure.photo.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.apero.armeasure.R

/**
 * Launches the system camera app to take one photo, delivered as a `content://` Uri.
 *
 * `TakePicture` needs a destination Uri to already exist before it launches — unlike the photo
 * picker, it doesn't hand back a Uri of its own choosing. That Uri has to be a `content://` one
 * (not a raw `file://`) for the camera app to be allowed to write to it at all on API 24+, which
 * is what the FileProvider declared in the manifest is for.
 *
 * Asks for CAMERA first, which is not optional here even though the system camera app is the thing
 * that actually opens the lens. Because this module DECLARES `android.permission.CAMERA` (ARCore
 * needs it), the platform requires the app to also HOLD it before it may start
 * `ACTION_IMAGE_CAPTURE` — an app that never declared the permission needs no grant, but one that
 * declares it and lacks it gets a `SecurityException` and dies. Observed on a Joy_4 with the
 * permission denied: `Permission Denial: starting Intent { act=android.media.action.IMAGE_CAPTURE }
 * ... with revoked permission android.permission.CAMERA`, crashing the app on the "take a photo"
 * tap.
 */
@Composable
internal fun rememberCameraCaptureLauncher(onPhotoTaken: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    // Resolved during composition, not inside the callbacks: Compose does not invalidate
    // LocalContext.current reads when the Configuration changes, so a getString() from a callback can
    // hand back a string in the locale that was current when the context was captured.
    val cameraDeniedMessage = stringResource(R.string.armeasure_camera_denied_body)
    // Saveable, not plain remember: the destination Uri has to outlive whatever the OEM camera app
    // does to this Activity while it is in front. Lose it and the captured photo is unreachable —
    // the file is on disk but nothing knows where.
    var pendingUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingUri
        pendingUri = null
        when {
            uri == null ->
                Log.w(DiagTag, "capture returned with no pending Uri — the destination was lost")
            success -> onPhotoTaken(uri)
            // Not a paranoid fallback: several OEM camera apps write the requested file and still
            // return RESULT_CANCELED, which `TakePicture` reports as failure. Observed on a Joy_4
            // (com.vinsmart.camera) — two 6 MB photos landed in the cache directory while the app
            // ignored both and re-showed the picker. Trusting the result flag alone throws away a
            // photo the user did take.
            hasCapturedContent(context, uri) -> {
                Log.w(DiagTag, "camera reported cancel but wrote the file — using it anyway")
                onPhotoTaken(uri)
            }
            else -> Log.w(DiagTag, "capture cancelled and nothing was written")
        }
    }

    fun launchCapture() {
        val uri = createCameraCaptureUri(context)
        pendingUri = uri
        // Belt and braces. The permission is checked above, but the platform can still refuse this
        // intent for reasons outside this module's control (an OEM camera app with its own
        // restrictions, a permission revoked between the check and the launch), and none of those is
        // worth killing the app over — the user can still pick from the gallery.
        runCatching { launcher.launch(uri) }.onFailure { error ->
            pendingUri = null
            Log.w(DiagTag, "camera capture refused: ${error.javaClass.simpleName}: ${error.message}")
            Toast.makeText(context, cameraDeniedMessage, Toast.LENGTH_LONG).show()
        }
    }

    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCapture()
        } else {
            // Says why nothing happened. Without this the affordance is simply dead once the user
            // has denied permanently, since the system dialog stops appearing at that point.
            Toast.makeText(context, cameraDeniedMessage, Toast.LENGTH_LONG).show()
        }
    }

    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchCapture() else requestCamera.launch(Manifest.permission.CAMERA)
    }
}

/** Logcat tag for the refusal paths above — both are silent to the user otherwise. */
private const val DiagTag = "ArMeasureCameraCapture"

/**
 * True when [uri] holds a non-empty file, i.e. the camera really did capture something.
 *
 * One `stat`-sized descriptor open, so it stays on the callback's thread rather than becoming another
 * dispatch; it is nowhere near the cost of the decode that follows it.
 */
private fun hasCapturedContent(context: Context, uri: Uri): Boolean =
    runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length > 0L } ?: false
    }.getOrDefault(false)

private fun createCameraCaptureUri(context: Context): Uri {
    val dir = captureDir(context).apply { mkdirs() }
    // Sweep whatever is already here before adding to it. Each capture is a full-resolution JPEG —
    // about 6 MB on the test device — and nothing used to remove them, so the directory grew by that
    // much every time the user took a photo. Anything still present at this point belongs to an
    // earlier capture that was either already decoded or abandoned; neither is wanted again.
    runCatching { dir.listFiles()?.forEach { it.delete() } }
    val file = File(dir, "ref-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
}

/**
 * Deletes the temp JPEG behind [uri], once its pixels have been decoded and are no longer needed.
 *
 * A no-op for any Uri this module did not create — the same callback also receives gallery Uris the
 * user picked, and deleting one of those would destroy a real photo out of the user's library. The
 * authority check is what separates the two.
 */
internal suspend fun discardCameraCapture(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    if (uri.authority != fileProviderAuthority(context)) return@withContext
    val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return@withContext
    runCatching { File(captureDir(context), name).delete() }
    Unit
}

private fun captureDir(context: Context) = File(context.cacheDir, "camera-capture")

private fun fileProviderAuthority(context: Context) = "${context.packageName}.armeasure.fileprovider"
