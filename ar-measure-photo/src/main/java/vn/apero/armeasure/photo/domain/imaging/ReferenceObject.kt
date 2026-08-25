package vn.apero.armeasure.photo.domain.imaging

/**
 * A rectangle of known real-world size, used to calibrate a still photo.
 *
 * Same idea as ARuler's "Photoruler": drag a quad onto something whose true dimensions are
 * known — a sheet of paper, a payment card — and every other line drawn on that photo scales
 * off it. [shortSideMm]/[longSideMm] name which side is which; the quad editor always maps its
 * top/bottom edges to the long side and its left/right edges to the short side (see
 * `PhotoMeasureState.confirmReference`), so orientation is fixed by convention rather than
 * guessed from pixel lengths under perspective distortion.
 */
internal data class ReferenceObject(
    val label: String,
    val shortSideMm: Float,
    val longSideMm: Float,
)

/** ISO 216 A4 and ISO/IEC 7810 ID-1 (payment card) — the same two defaults ARuler ships. */
internal val builtInReferenceObjects = listOf(
    ReferenceObject(label = "A4 paper", shortSideMm = 210f, longSideMm = 297f),
    ReferenceObject(label = "Payment card", shortSideMm = 53.98f, longSideMm = 85.60f),
)

/** Null when either side is non-positive — a degenerate rectangle can't calibrate anything. */
internal fun customReferenceObject(label: String, shortSideMm: Float, longSideMm: Float): ReferenceObject? {
    if (shortSideMm <= 0f || longSideMm <= 0f) return null
    val short = minOf(shortSideMm, longSideMm)
    val long = maxOf(shortSideMm, longSideMm)
    return ReferenceObject(label.ifBlank { "Custom" }, short, long)
}
