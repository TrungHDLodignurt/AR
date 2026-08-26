package vn.apero.armeasure.photo.data

import android.content.Context
import androidx.core.content.edit
import java.util.UUID
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject
import vn.apero.armeasure.photo.domain.imaging.decodeReferences
import vn.apero.armeasure.photo.domain.imaging.encodeReferences

// Namespaced (rather than the generic "custom_reference_objects") so a host app that has its
// own unrelated prefs file of that name never collides with this module's storage.
private const val PrefsName = "vn.apero.armeasure.photo.custom_reference_objects"
private const val KeyObjects = "objects"

/**
 * Persists user-created reference objects — name plus the two real-world side lengths in mm, now
 * keyed by a stable [ReferenceObject.id] so two objects sharing a label (the design shows two
 * "điện thoại" cards) can still be addressed distinctly for [update]/[delete]. `org.json` is part
 * of the Android platform (no Gradle dependency needed); SharedPreferences is plenty for a
 * handful of these.
 */
internal class CustomReferenceStore(private val context: Context) {

    /**
     * Decoding mints an id for any legacy entry that predates [ReferenceObject.id]. When that
     * happens the re-encoded JSON differs from what is stored, so it is written back once here —
     * a one-time migration, not a per-launch rewrite.
     */
    internal fun loadAll(): List<ReferenceObject> {
        val stored = prefs().getString(KeyObjects, null)
        val decoded = decodeReferences(stored)
        if (stored != null) {
            val reEncoded = encodeReferences(decoded)
            if (reEncoded != stored) prefs().edit { putString(KeyObjects, reEncoded) }
        }
        return decoded
    }

    internal fun add(label: String, shortSideMm: Float, longSideMm: Float): ReferenceObject {
        val newObject = ReferenceObject(id = UUID.randomUUID().toString(), label = label, shortSideMm = shortSideMm, longSideMm = longSideMm)
        saveAll(loadAll() + newObject)
        return newObject
    }

    /** Null (and no write) if [id] is unknown — built-in ids are never in this store's list, so this is already a safe no-op for them. */
    internal fun update(id: String, label: String, shortSideMm: Float, longSideMm: Float): ReferenceObject? {
        val current = loadAll()
        val target = current.firstOrNull { it.id == id } ?: return null
        val updated = target.copy(label = label, shortSideMm = shortSideMm, longSideMm = longSideMm)
        saveAll(current.map { if (it.id == id) updated else it })
        return updated
    }

    /** False if [id] is unknown, including any built-in id — same reasoning as [update]. */
    internal fun delete(id: String): Boolean {
        val current = loadAll()
        if (current.none { it.id == id }) return false
        saveAll(current.filterNot { it.id == id })
        return true
    }

    private fun saveAll(objects: List<ReferenceObject>) {
        prefs().edit { putString(KeyObjects, encodeReferences(objects)) }
    }

    private fun prefs() = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
}
