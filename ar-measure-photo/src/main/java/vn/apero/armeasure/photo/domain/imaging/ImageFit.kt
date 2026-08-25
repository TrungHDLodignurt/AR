package vn.apero.armeasure.photo.domain.imaging

/**
 * Aspect-fit rectangle: the largest box with [imageWidth]x[imageHeight]'s aspect ratio that
 * fits inside [boxWidth]x[boxHeight], centred — the classic "letterbox" fit.
 *
 * Shared between [PhotoQuadCanvas] (where to draw the photo) and [PhotoMeasureState] (where to
 * place the default calibration quad) so the two never disagree about where the photo actually
 * sits on screen.
 */
internal data class FittedRect(val offsetX: Float, val offsetY: Float, val width: Float, val height: Float)

internal fun aspectFit(imageWidth: Float, imageHeight: Float, boxWidth: Float, boxHeight: Float): FittedRect {
    val scale = minOf(boxWidth / imageWidth, boxHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return FittedRect(
        offsetX = (boxWidth - width) / 2f,
        offsetY = (boxHeight - height) / 2f,
        width = width,
        height = height,
    )
}
