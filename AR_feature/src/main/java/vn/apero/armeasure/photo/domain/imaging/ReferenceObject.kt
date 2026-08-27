package vn.apero.armeasure.photo.domain.imaging

/**
 * A rectangle of known real-world size, used to calibrate a still photo.
 *
 * Same idea as ARuler's "Photoruler": drag a quad onto something whose true dimensions are
 * known — a sheet of paper, a payment card — and every other line drawn on that photo scales
 * off it. [shortSideMm]/[longSideMm] name which side is which; the quad editor always maps its
 * top/bottom edges to the long side and its left/right edges to the short side (see
 * `PhotoMeasureReducers.confirmReference`), so orientation is fixed by convention rather than
 * guessed from pixel lengths under perspective distortion.
 *
 * [id] is a stable identifier distinct from [label] — two custom objects can share a label (the
 * design itself shows two "điện thoại" cards with different dimensions), so [label] alone cannot
 * address one for edit/delete. [isBuiltIn] gates both: built-ins are never persisted, edited or
 * deleted (see `CustomReferenceStore`).
 */
internal data class ReferenceObject(
    val id: String,
    val label: String,
    val shortSideMm: Float,
    val longSideMm: Float,
    val isBuiltIn: Boolean = false,
)

/** ISO 216 A4 and ISO/IEC 7810 ID-1 (payment card) — the same two defaults ARuler ships. */
internal val builtInReferenceObjects = listOf(
    ReferenceObject(id = "builtin:a4", label = "A4 paper", shortSideMm = 210f, longSideMm = 297f, isBuiltIn = true),
    ReferenceObject(id = "builtin:card", label = "Payment card", shortSideMm = 53.98f, longSideMm = 85.60f, isBuiltIn = true),
)

/**
 * Null when either side is non-positive — a degenerate rectangle can't calibrate anything.
 *
 * Returns a transient [ReferenceObject] with a blank [id] — this function only normalises
 * label/short/long ordering, it never mints or preserves an id. Callers (`CustomReferenceStore`)
 * are responsible for the real id: a fresh [java.util.UUID] on add, or the existing one on
 * update.
 */
internal fun customReferenceObject(label: String, shortSideMm: Float, longSideMm: Float): ReferenceObject? {
    if (shortSideMm <= 0f || longSideMm <= 0f) return null
    val short = minOf(shortSideMm, longSideMm)
    val long = maxOf(shortSideMm, longSideMm)
    return ReferenceObject(id = "", label = label.ifBlank { "Custom" }, shortSideMm = short, longSideMm = long)
}
