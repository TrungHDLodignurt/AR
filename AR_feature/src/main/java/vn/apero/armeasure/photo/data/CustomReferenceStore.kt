package vn.apero.armeasure.photo.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject

// Namespaced (rather than the generic "custom_reference_objects") so a host app that has its
// own unrelated prefs file of that name never collides with this module's storage.
private const val PrefsName = "vn.apero.armeasure.photo.custom_reference_objects"
private const val KeyObjects = "objects"

/**
 * Persists user-created reference objects — name plus the two real-world side lengths in mm.
 * Confirmed against ARuler's actual "Đối tượng tham chiếu mới" dialog: name, length, width, an
 * "Add" button — no photo, no thumbnail, nothing else. `org.json` is part of the Android
 * platform (no Gradle dependency needed); SharedPreferences is plenty for a handful of these.
 */
internal class CustomReferenceStore(private val context: Context) {

    internal fun loadAll(): List<ReferenceObject> {
        val json = prefs().getString(KeyObjects, null) ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            ReferenceObject(
                label = obj.getString("label"),
                shortSideMm = obj.getDouble("shortSideMm").toFloat(),
                longSideMm = obj.getDouble("longSideMm").toFloat(),
            )
        }
    }

    internal fun add(label: String, shortSideMm: Float, longSideMm: Float): ReferenceObject {
        val newObject = ReferenceObject(label, shortSideMm, longSideMm)
        saveAll(loadAll() + newObject)
        return newObject
    }

    private fun saveAll(objects: List<ReferenceObject>) {
        val array = JSONArray()
        objects.forEach { o ->
            array.put(
                JSONObject().apply {
                    put("label", o.label)
                    put("shortSideMm", o.shortSideMm.toDouble())
                    put("longSideMm", o.longSideMm.toDouble())
                },
            )
        }
        prefs().edit { putString(KeyObjects, array.toString()) }
    }

    private fun prefs() = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
}
