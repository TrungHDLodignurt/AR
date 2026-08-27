package vn.apero.armeasure.photo.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.domain.imaging.toBitmapSpace
import vn.apero.armeasure.photo.domain.imaging.toDisplaySpace

internal fun Offset.toVec2() = Vec2(x, y)

internal fun Vec2.toOffset() = Offset(x, y)

/**
 * The two conversions every edge of this screen needs, and the only places display pixels and
 * bitmap pixels are allowed to meet: a gesture handler converts the incoming display [Offset] in
 * once ([toBitmapSpaceIn]), a draw scope or an overlay converts the stored [Vec2] back out for as
 * long as it takes to paint it ([toDisplayOffsetIn]). [canvas] is the size of the box the photo is
 * aspect-fitted into; both directions go through `ImageFit` so nothing here re-derives the letterbox
 * maths.
 */
internal fun Offset.toBitmapSpaceIn(photo: ImageBitmap, canvas: IntSize): Vec2 =
    toBitmapSpace(toVec2(), photo.width.toFloat(), photo.height.toFloat(), canvas.width.toFloat(), canvas.height.toFloat())

internal fun Vec2.toDisplayOffsetIn(photo: ImageBitmap, canvas: IntSize): Offset =
    toDisplaySpace(this, photo.width.toFloat(), photo.height.toFloat(), canvas.width.toFloat(), canvas.height.toFloat()).toOffset()
