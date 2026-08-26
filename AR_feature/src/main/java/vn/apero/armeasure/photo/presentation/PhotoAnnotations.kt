package vn.apero.armeasure.photo.presentation

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import vn.apero.armeasure.common.ui.drawLabelPill
import vn.apero.armeasure.common.ui.labelTextColorFor
import vn.apero.armeasure.photo.domain.imaging.aspectFit

/** A dark halo behind the line/endpoints so a bright user-chosen colour still reads over a light photo (insight 6). */
private val LineHalo = Color(0x59000000)

/**
 * The one draw path for [photo] plus the measuring [line] and its [label] — shared by the
 * on-screen canvas ([PhotoQuadCanvas]) and the exported PNG ([renderAnnotatedBitmap]), so the two
 * can never drift apart (insight 10). Coordinates are whatever this [DrawScope]'s own pixel space
 * is; callers are responsible for placing [line] in that space before calling this.
 */
internal fun DrawScope.drawPhotoAnnotations(
    photo: ImageBitmap,
    line: LiveLine?,
    label: String?,
    lineColor: Color,
    textMeasurer: TextMeasurer,
) {
    drawPlainPhoto(photo)
    if (line == null) return

    drawLine(LineHalo, line.start, line.end, strokeWidth = 4.dp.toPx())
    drawLine(lineColor, line.start, line.end, strokeWidth = 2.dp.toPx())
    drawCircle(LineHalo, radius = 7.dp.toPx(), center = line.start)
    drawCircle(lineColor, radius = 5.dp.toPx(), center = line.start)
    drawCircle(LineHalo, radius = 7.dp.toPx(), center = line.end)
    drawCircle(lineColor, radius = 5.dp.toPx(), center = line.end)

    if (label != null) {
        val mid = Offset((line.start.x + line.end.x) / 2f, (line.start.y + line.end.y) / 2f)
        val style = TextStyle(color = labelTextColorFor(lineColor), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        drawLabelPill(textMeasurer, label, mid, style, backgroundColor = lineColor)
    }
}

/**
 * Aspect-fit rather than stretch-to-fill (see `Homography.kt` for why a squashed photo would
 * still be internally consistent but needlessly harder to align by eye). Degenerates to an exact
 * 1:1 fill when the draw target is already the photo's own resolution — [renderAnnotatedBitmap]'s
 * case, so no special-casing is needed there.
 */
private fun DrawScope.drawPlainPhoto(photo: ImageBitmap) {
    val fit = aspectFit(photo.width.toFloat(), photo.height.toFloat(), size.width, size.height)
    drawImage(
        photo,
        dstOffset = IntOffset(fit.offsetX.roundToInt(), fit.offsetY.roundToInt()),
        dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
    )
}

/**
 * Renders [photo] plus [line]/[label] into a fresh [Bitmap] at the photo's own resolution — the
 * file [PhotoMeasureScreen]'s "Lưu" saves: the original photo with the measurement drawn over it,
 * no watermark. [onScreenCanvasSize] is the size [line]'s coordinates were captured in (the live
 * [PhotoQuadCanvas]), used to map them into the export bitmap's own pixel grid before drawing —
 * [drawPhotoAnnotations] itself never needs to know two coordinate spaces exist.
 *
 * The caller recycles the result; this function never does, since a caller that also draws it to
 * screen first would then have nothing left to draw.
 */
internal fun renderAnnotatedBitmap(
    photo: Bitmap,
    line: LiveLine?,
    onScreenCanvasSize: IntSize,
    label: String?,
    lineColor: Color,
    textMeasurer: TextMeasurer,
    density: Density,
): Bitmap {
    val exportLine = line?.let { toBitmapSpace(it, photo, onScreenCanvasSize) }
    val imageBitmap = ImageBitmap(photo.width, photo.height)
    val canvas = Canvas(imageBitmap)
    val size = Size(photo.width.toFloat(), photo.height.toFloat())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, size) {
        drawPhotoAnnotations(photo.asImageBitmap(), exportLine, label, lineColor, textMeasurer)
    }
    return imageBitmap.asAndroidBitmap()
}

/** [line]'s on-screen pixels -> [photo]'s own pixel grid, undoing the aspect-fit letterbox [drawPlainPhoto] applies on screen. */
private fun toBitmapSpace(line: LiveLine, photo: Bitmap, canvasSize: IntSize): LiveLine {
    val fit = aspectFit(photo.width.toFloat(), photo.height.toFloat(), canvasSize.width.toFloat(), canvasSize.height.toFloat())
    fun convert(point: Offset) = Offset(
        (point.x - fit.offsetX) / fit.width * photo.width,
        (point.y - fit.offsetY) / fit.height * photo.height,
    )
    return LiveLine(convert(line.start), convert(line.end))
}
