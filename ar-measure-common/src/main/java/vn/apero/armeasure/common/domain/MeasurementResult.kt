package vn.apero.armeasure.common.domain

/**
 * A finished measurement handed back to the host, decoupled from any AR/photo internals.
 *
 * Every length here is in metres regardless of [LengthUnit] — `unit` is only a display
 * preference for how the host should render the number, never a conversion already applied
 * to the field.
 */
sealed interface MeasurementResult {
    /** Two-point distance from the ruler tool. */
    data class Distance(val meters: Float, val unit: LengthUnit) : MeasurementResult

    /** Footprint (two edges) plus height from the Box shape screen. */
    data class Box(val lengthU: Float, val lengthV: Float, val height: Float, val unit: LengthUnit) : MeasurementResult

    /** Radius and height from the Cylinder shape screen. */
    data class Cylinder(val radius: Float, val height: Float, val unit: LengthUnit) : MeasurementResult

    /** Single distance from the photo-reference measure screen. */
    data class Photo(val distanceMeters: Float, val unit: LengthUnit) : MeasurementResult
}
