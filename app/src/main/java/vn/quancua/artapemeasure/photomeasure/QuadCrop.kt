package vn.quancua.artapemeasure.photomeasure

import android.graphics.Rect
import androidx.compose.ui.geometry.Offset

/**
 * The quad's bounding box, converted from display-space pixels (where the user dragged the
 * corners) into the bitmap's native pixel grid — what `Bitmap.createBitmap`'s crop overload
 * actually needs. Used to make a thumbnail out of whatever the user framed inside the quad when
 * registering a new reference object.
 */
fun quadBoundingBoxInBitmapPixels(
    quad: List<Offset>,
    fit: FittedRect,
    bitmapWidth: Int,
    bitmapHeight: Int,
): Rect {
    fun toBitmapX(displayX: Float) = ((displayX - fit.offsetX) / fit.width * bitmapWidth)
    fun toBitmapY(displayY: Float) = ((displayY - fit.offsetY) / fit.height * bitmapHeight)

    val xs = quad.map { toBitmapX(it.x) }
    val ys = quad.map { toBitmapY(it.y) }

    val left = xs.min().toInt().coerceIn(0, bitmapWidth - 1)
    val top = ys.min().toInt().coerceIn(0, bitmapHeight - 1)
    val right = xs.max().toInt().coerceIn(left + 1, bitmapWidth)
    val bottom = ys.max().toInt().coerceIn(top + 1, bitmapHeight)
    return Rect(left, top, right, bottom)
}
