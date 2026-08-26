package vn.apero.armeasure.common.data

import android.content.Context
import androidx.core.content.edit
import vn.apero.armeasure.common.domain.LengthUnit

// Namespaced exactly like photo.data.CustomReferenceStore's prefs file, so a host app's own
// prefs file of some generic name never collides with this module's storage.
private const val PrefsName = "vn.apero.armeasure.unit"
private const val KeyUnit = "length_unit"

/**
 * Persists the user's chosen [LengthUnit] across screens and across process restarts.
 *
 * Decision 8 calls the unit picker a *hard user choice* — a value that reset every time a
 * screen was entered would not be one, so this is the single, process-wide source of truth that
 * every measuring screen reads on enter and writes back to on change.
 */
internal class UnitPreference(context: Context) {

    private val prefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    var unit: LengthUnit
        get() {
            val stored = prefs.getString(KeyUnit, null) ?: return LengthUnit.Cm
            // A corrupted or renamed enum value must fall back, never crash a measuring screen.
            return enumValues<LengthUnit>().firstOrNull { it.name == stored } ?: LengthUnit.Cm
        }
        set(value) = prefs.edit { putString(KeyUnit, value.name) }
}
