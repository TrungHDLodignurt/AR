package vn.apero.armeasure.photo.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import vn.apero.armeasure.photo.domain.imaging.FittedRect

private val LoupeDiameter = 96.dp
private const val Zoom = 2.5f

/**
 * A circular magnified preview of [photo] around [target], shown while dragging a quad corner —
 * same trick ARuler uses so the fingertip doesn't hide the exact pixel being placed.
 *
 * Positioned above [target] rather than centred on it, and clamped to stay on screen, so the
 * loupe itself is never under the finger that is dragging it.
 */
@Composable
internal fun MagnifierLoupe(photo: ImageBitmap, fit: FittedRect, target: Offset, canvasSize: IntSize) {
    val diameterPx = with(LocalDensity.current) { LoupeDiameter.toPx() }
    val gapPx = diameterPx * 0.4f

    val topLeftX = (target.x - diameterPx / 2f).coerceIn(0f, (canvasSize.width - diameterPx).coerceAtLeast(0f))
    val topLeftY = (target.y - diameterPx - gapPx).coerceAtLeast(0f)

    Box(
        modifier = Modifier
            .offset { IntOffset(topLeftX.roundToInt(), topLeftY.roundToInt()) }
            .size(LoupeDiameter)
            .clip(CircleShape)
            .border(2.dp, Color.White, CircleShape),
    ) {
        Canvas(Modifier.size(LoupeDiameter)) {
            // Same display-space -> bitmap-pixel conversion as QuadCrop.kt, then a square crop
            // sized so that stretching it back up to the loupe's diameter reads as `Zoom`x
            // larger than it looked in the main canvas (not just "larger than the bitmap").
            val displayScale = fit.width / photo.width
            val cropSizeBitmapPx = ((diameterPx / Zoom) / displayScale).roundToInt().coerceAtLeast(1)
            val centerBitmapX = ((target.x - fit.offsetX) / fit.width * photo.width).roundToInt()
            val centerBitmapY = ((target.y - fit.offsetY) / fit.height * photo.height).roundToInt()

            val srcLeft = (centerBitmapX - cropSizeBitmapPx / 2).coerceIn(0, (photo.width - cropSizeBitmapPx).coerceAtLeast(0))
            val srcTop = (centerBitmapY - cropSizeBitmapPx / 2).coerceIn(0, (photo.height - cropSizeBitmapPx).coerceAtLeast(0))
            val clampedCropSize = minOf(cropSizeBitmapPx, photo.width - srcLeft, photo.height - srcTop).coerceAtLeast(1)

            drawImage(
                photo,
                srcOffset = IntOffset(srcLeft, srcTop),
                srcSize = IntSize(clampedCropSize, clampedCropSize),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )

            // Crosshair marking the exact point being placed, at the loupe's centre.
            val center = Offset(size.width / 2f, size.height / 2f)
            drawLine(Color.Red, Offset(center.x - 8.dp.toPx(), center.y), Offset(center.x + 8.dp.toPx(), center.y), 1.dp.toPx())
            drawLine(Color.Red, Offset(center.x, center.y - 8.dp.toPx()), Offset(center.x, center.y + 8.dp.toPx()), 1.dp.toPx())
        }
    }
}
