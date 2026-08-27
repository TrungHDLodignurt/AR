package vn.apero.armeasure.common.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import vn.apero.armeasure.common.domain.LengthUnit

// Namespaced exactly like photo.data.CustomReferenceStore's prefs file, so a host app's own
// prefs file of some generic name never collides with this module's storage.
private const val PrefsName = "vn.apero.armeasure.unit"
private const val KeyUnit = "length_unit"

/** The unit a user sees before ever choosing one. Change here — nothing else hardcodes a default. */
internal val DefaultUnit = LengthUnit.Cm

/**
 * Persists the user's chosen [LengthUnit] across screens and across process restarts.
 *
 * Decision 8 calls the unit picker a *hard user choice* — a value that reset every time a
 * screen was entered would not be one, so this is the single, process-wide source of truth that
 * every measuring screen reads on enter and writes back to on change.
 *
 * [DefaultUnit] is what a first-ever launch gets, and is also the fallback for unreadable stored
 * data — every other default in the module defers to this one value.
 */
internal class UnitPreference(context: Context) {

    // Lazy so merely constructing this — which happens inside composition — touches no disk. The
    // file is only opened when a unit is actually read or written.
    private val prefs by lazy { context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE) }

    var unit: LengthUnit
        get() {
            val stored = prefs.getString(KeyUnit, null) ?: return DefaultUnit
            // A corrupted or renamed enum value must fall back, never crash a measuring screen.
            return enumValues<LengthUnit>().firstOrNull { it.name == stored } ?: DefaultUnit
        }
        set(value) = prefs.edit { putString(KeyUnit, value.name) }

    /**
     * Persists [value] off the main thread.
     *
     * `edit {}` uses `apply()`, so the write itself is already asynchronous — but the first call has
     * to wait for SharedPreferences to finish loading the file before it can edit it, and that wait
     * is on whichever thread asks. Callers persist from a `LaunchedEffect`, which runs on the main
     * thread.
     *
     * The matching read is deliberately NOT moved off the main thread: it seeds the unit shown on
     * the very first frame, and making it asynchronous would render the default unit and then visibly
     * correct it. One `getString` on a file holding a single short string is the cheaper trade.
     */
    suspend fun save(value: LengthUnit) = withContext(Dispatchers.IO) { unit = value }
}
