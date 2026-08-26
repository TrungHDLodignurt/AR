package vn.apero.armeasure.photo.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vn.apero.armeasure.MeasurementImageSaver

/**
 * The module's own [MeasurementImageSaver]: inserts into the shared Pictures collection under
 * `Pictures/<app label>` via MediaStore's API 29+ pending-write flow — [fileName] is expected to
 * already be sanitised (a timestamp, never user-supplied text; see [PhotoMeasureScreen]'s save
 * wiring). A host overrides this entirely through [vn.apero.armeasure.ArMeasureConfig] ; this
 * class is only ever resolved as the fallback at the moment of saving, never installed as a
 * process-wide singleton itself.
 *
 * API 29+ only, matching this module's decision to skip `WRITE_EXTERNAL_STORAGE` entirely — no
 * legacy write path, no second permission. [PhotoMeasureScreen] hides its save affordance below
 * API 29 and explains why instead, so [save] returning `null` there is defensive, not a path this
 * module's own UI ever actually exercises.
 */
internal class MediaStoreImageSaver(private val context: Context) : MeasurementImageSaver {

    override suspend fun save(bitmap: Bitmap, fileName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return withContext(Dispatchers.IO) { insertAndWrite(bitmap, fileName) }
    }

    private fun insertAndWrite(bitmap: Bitmap, fileName: String): Uri? {
        val resolver = context.contentResolver
        val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$appLabel")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val wrote = resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
            if (wrote) uri else { resolver.delete(uri, null, null); null }
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            null
        } finally {
            // Cleared in a finally so a failed save never leaves an invisible IS_PENDING=1 orphan
            // row — harmless no-op on the already-deleted-uri branch above.
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
    }
}
