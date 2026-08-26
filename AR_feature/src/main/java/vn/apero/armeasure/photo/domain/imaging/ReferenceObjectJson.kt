package vn.apero.armeasure.photo.domain.imaging

import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure JSON codec for the custom reference objects persisted by `CustomReferenceStore` — kept
 * free of `Context`/`SharedPreferences` so it can be driven by plain JUnit, no Robolectric.
 *
 * [decodeReferences] also carries the one-time id migration: entries written before [id] existed
 * get a freshly minted [UUID]. `CustomReferenceStore.loadAll` re-encodes the decoded list and
 * writes it back only when that re-encoding differs from what was stored, which is exactly the
 * "migrate once" behaviour without needing any separate dirty-tracking here.
 */

internal fun encodeReferences(objects: List<ReferenceObject>): String {
    val array = JSONArray()
    objects.forEach { o ->
        array.put(
            JSONObject().apply {
                put("id", o.id)
                put("label", o.label)
                put("shortSideMm", o.shortSideMm.toDouble())
                put("longSideMm", o.longSideMm.toDouble())
            },
        )
    }
    return array.toString()
}

/**
 * Never throws: malformed JSON, or a JSON value that is not an array, decodes to an empty list.
 * Any entry missing either dimension is skipped outright rather than defaulted to `0f` — a 0mm
 * reference would divide the homography by zero and produce an absurd measurement.
 */
internal fun decodeReferences(json: String?): List<ReferenceObject> {
    if (json.isNullOrBlank()) return emptyList()
    val array = try {
        JSONArray(json)
    } catch (e: JSONException) {
        return emptyList()
    }
    val result = mutableListOf<ReferenceObject>()
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        if (!obj.has("shortSideMm") || !obj.has("longSideMm")) continue
        val shortSideMm = obj.optDouble("shortSideMm", Double.NaN).toFloat()
        val longSideMm = obj.optDouble("longSideMm", Double.NaN).toFloat()
        if (shortSideMm.isNaN() || longSideMm.isNaN()) continue
        val label = obj.optString("label", "Custom")
        val id = obj.optString("id", "").ifBlank { UUID.randomUUID().toString() }
        result += ReferenceObject(id = id, label = label, shortSideMm = shortSideMm, longSideMm = longSideMm)
    }
    return result
}
