package vn.quancua.artapemeasure.photomeasure

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

private const val PrefsName = "custom_reference_objects"
private const val KeyObjects = "objects"

/**
 * Persists user-created reference objects — name, real dimensions in mm, and a thumbnail
 * cropped from whatever photo the user framed them in. `org.json` is part of the Android
 * platform (no Gradle dependency needed); SharedPreferences is plenty for a handful of these.
 */
class CustomReferenceStore(private val context: Context) {

    fun loadAll(): List<ReferenceObject> {
        val json = prefs().getString(KeyObjects, null) ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            ReferenceObject(
                label = obj.getString("label"),
                shortSideMm = obj.getDouble("shortSideMm").toFloat(),
                longSideMm = obj.getDouble("longSideMm").toFloat(),
                thumbnailPath = obj.getString("thumbnailPath"),
            )
        }
    }

    private fun saveAll(objects: List<ReferenceObject>) {
        val array = JSONArray()
        objects.forEach { o ->
            array.put(
                JSONObject().apply {
                    put("label", o.label)
                    put("shortSideMm", o.shortSideMm.toDouble())
                    put("longSideMm", o.longSideMm.toDouble())
                    put("thumbnailPath", o.thumbnailPath ?: "")
                },
            )
        }
        prefs().edit { putString(KeyObjects, array.toString()) }
    }

    /**
     * Crops [source] to [cropRect] (bitmap-native pixels — see `QuadCrop.kt`), saves it as a
     * small JPEG the reference picker can show as a thumbnail, and appends the new object.
     */
    fun add(label: String, shortSideMm: Float, longSideMm: Float, source: Bitmap, cropRect: Rect): ReferenceObject {
        val thumbnailPath = saveThumbnail(source, cropRect)
        val newObject = ReferenceObject(label, shortSideMm, longSideMm, thumbnailPath)
        saveAll(loadAll() + newObject)
        return newObject
    }

    private fun saveThumbnail(source: Bitmap, cropRect: Rect): String {
        val dir = File(context.filesDir, "reference-thumbnails").apply { mkdirs() }
        val file = File(dir, "ref-${System.currentTimeMillis()}.jpg")
        val cropped = Bitmap.createBitmap(source, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
        FileOutputStream(file).use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        cropped.recycle()
        return file.absolutePath
    }

    private fun prefs() = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
}
