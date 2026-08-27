package vn.apero.armeasure.photo.domain.imaging

/**
 * Aspect-fit rectangle: the largest box with [imageWidth]x[imageHeight]'s aspect ratio that
 * fits inside [boxWidth]x[boxHeight], centred — the classic "letterbox" fit.
 *
 * Shared by every path that has to know where the photo actually sits inside its box — the draw
 * scopes, the magnifier crop, and [toBitmapSpace]/[toDisplaySpace] — so none of them can disagree
 * about the letterbox.
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
 * This is the **gesture edge**. `PhotoMeasureContract.State` stores every coordinate — quad, segments, draft
 * line, and the homography's source points — in the photo's own pixel grid, so a tap or a drag is
 * converted through here exactly once, on the way in, and never stored as it arrived. That is what
 * makes a relayout harmless: it changes only where the photo is drawn, not what was measured.
 * SCR-23's full-screen photo box and SCR-24's shorter one therefore need no bridge between each
 * other any more — each converts against its own size, and the stored value is common to both.
 */
internal fun toBitmapSpace(point: Vec2, photoWidth: Float, photoHeight: Float, canvasWidth: Float, canvasHeight: Float): Vec2 {
    val fit = aspectFit(photoWidth, photoHeight, canvasWidth, canvasHeight)
    return Vec2(
        (point.x - fit.offsetX) / fit.width * photoWidth,
        (point.y - fit.offsetY) / fit.height * photoHeight,
    )
}

/**
 * The inverse of [toBitmapSpace], and the **draw edge**: places a stored photo-pixel [point] into a
 * [canvasWidth]x[canvasHeight] aspect-fit canvas, for as long as it takes to paint it or to place a
 * handle over it. Degenerates to the identity when the canvas IS the photo's own resolution, which
 * is why `renderAnnotatedBitmap` can draw stored coordinates into the exported PNG unconverted.
 */
internal fun toDisplaySpace(point: Vec2, photoWidth: Float, photoHeight: Float, canvasWidth: Float, canvasHeight: Float): Vec2 {
    val fit = aspectFit(photoWidth, photoHeight, canvasWidth, canvasHeight)
    return Vec2(
        fit.offsetX + point.x / photoWidth * fit.width,
        fit.offsetY + point.y / photoHeight * fit.height,
    )
}
