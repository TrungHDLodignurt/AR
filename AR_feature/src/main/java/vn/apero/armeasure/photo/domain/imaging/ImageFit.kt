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

/**
 * Maps [point], given in an aspect-fit canvas of [canvasWidth]x[canvasHeight] pixels around a
 * [photoWidth]x[photoHeight] photo, into that photo's own pixel grid — undoing [aspectFit]'s
 * letterbox. [toDisplaySpace] is the inverse.
 *
 * The bridge that lets two *different-sized* canvases (SCR-23's full-screen photo box and SCR-24's
 * shorter one) agree on where "the same point on the photo" is: convert out of one canvas's pixels
 * into the photo's own grid, then back into the other canvas's pixels (see
 * `PhotoMeasureState.remapToCanvas`). Kept here, not duplicated per caller, so the on-screen canvas
 * (`PhotoQuadCanvas`/`LineDrawScreen`) and the exported bitmap (`renderAnnotatedBitmap`) — which
 * already used this exact conversion under a different name — share one implementation.
 */
internal fun toBitmapSpace(point: Vec2, photoWidth: Float, photoHeight: Float, canvasWidth: Float, canvasHeight: Float): Vec2 {
    val fit = aspectFit(photoWidth, photoHeight, canvasWidth, canvasHeight)
    return Vec2(
        (point.x - fit.offsetX) / fit.width * photoWidth,
        (point.y - fit.offsetY) / fit.height * photoHeight,
    )
}

/** The inverse of [toBitmapSpace]: places a photo-pixel [point] into a [canvasWidth]x[canvasHeight] aspect-fit canvas. */
internal fun toDisplaySpace(point: Vec2, photoWidth: Float, photoHeight: Float, canvasWidth: Float, canvasHeight: Float): Vec2 {
    val fit = aspectFit(photoWidth, photoHeight, canvasWidth, canvasHeight)
    return Vec2(
        fit.offsetX + point.x / photoWidth * fit.width,
        fit.offsetY + point.y / photoHeight * fit.height,
    )
}
