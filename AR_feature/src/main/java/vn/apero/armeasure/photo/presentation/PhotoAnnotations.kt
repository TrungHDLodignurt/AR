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
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.domain.imaging.aspectFit
import vn.apero.armeasure.photo.domain.imaging.toDisplaySpace

/** A dark halo behind the line/endpoints so a bright user-chosen colour still reads over a light photo (insight 6). */
private val LineHalo = Color(0x59000000)

/**
 * The draw edge: a stored bitmap-space [point] projected into wherever this draw scope is painting
 * the photo. On screen that undoes the aspect-fit letterbox; in [renderAnnotatedBitmap], where the
 * draw target IS the photo's own resolution, it is the identity — which is exactly why the export
 * path no longer converts anything itself.
 */
private fun DrawScope.displayOf(point: Vec2, photo: ImageBitmap): Offset =
    toDisplaySpace(point, photo.width.toFloat(), photo.height.toFloat(), size.width, size.height).toOffset()

/** Just the line + its two endpoint dots, no label — the part every draw path below shares. */
private fun DrawScope.drawSegmentStroke(start: Offset, end: Offset, color: Color) {
    drawLine(LineHalo, start, end, strokeWidth = 4.dp.toPx())
    drawLine(color, start, end, strokeWidth = 2.dp.toPx())
    drawCircle(LineHalo, radius = 7.dp.toPx(), center = start)
    drawCircle(color, radius = 5.dp.toPx(), center = start)
    drawCircle(LineHalo, radius = 7.dp.toPx(), center = end)
    drawCircle(color, radius = 5.dp.toPx(), center = end)
}

private fun DrawScope.drawSegmentLabel(start: Offset, end: Offset, label: String, color: Color, textMeasurer: TextMeasurer) {
    val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
    val style = TextStyle(color = labelTextColorFor(color), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    drawLabelPill(textMeasurer, label, mid, style, backgroundColor = color)
}

/**
 * The photo plus one [line] and its [label] — SCR-24's draft segment, which is always exactly one
 * line being actively dragged, so a plain Canvas-drawn pill (no trash affordance needed; nothing
 * is committed yet) is all it needs. Shared by the live canvas and (indirectly, via
 * [drawExportSegments] for the *committed* case) the exported PNG so on-screen and saved drawing
 * can never drift apart.
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
    val start = displayOf(line.start, photo)
    val end = displayOf(line.end, photo)
    drawSegmentStroke(start, end, lineColor)
    if (label != null) drawSegmentLabel(start, end, label, lineColor, textMeasurer)
}

/**
 * SCR-23's live on-screen draw for every *committed* [segments] — strokes only, deliberately no
 * label pill: [SegmentLabelOverlay] renders each label (with its trash affordance) as a real
 * composable on top instead, since a `Canvas` draw cannot receive taps. Drawing a pill here too
 * would double it.
 */
internal fun DrawScope.drawCommittedSegmentStrokes(photo: ImageBitmap, segments: List<Segment>) {
    drawPlainPhoto(photo)
    segments.forEach { drawSegmentStroke(displayOf(it.start, photo), displayOf(it.end, photo), it.color) }
}

/**
 * The exported-PNG draw for "Lưu" — strokes AND plain label pills, but never a trash icon: a
 * delete affordance baked into a saved photo would make no sense. [segments] pairs each committed
 * segment with its already-formatted length label (or null if calibration was somehow lost).
 */
internal fun DrawScope.drawExportSegments(photo: ImageBitmap, segments: List<Pair<Segment, String?>>, textMeasurer: TextMeasurer) {
    drawPlainPhoto(photo)
    segments.forEach { (segment, label) ->
        val start = displayOf(segment.start, photo)
        val end = displayOf(segment.end, photo)
        drawSegmentStroke(start, end, segment.color)
        if (label != null) drawSegmentLabel(start, end, label, segment.color, textMeasurer)
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
 * Renders [photo] plus every committed segment into a fresh [Bitmap] at the photo's own
 * resolution — the file [PhotoMeasureScreen]'s "Lưu" saves: the original photo with every
 * measurement drawn over it, no watermark. [segments] are already in the photo's own pixel grid, and
 * the draw target here is that same grid, so there is no on-screen canvas size to pass in and
 * nothing to remap: what was measured is drawn exactly where it was measured.
 *
 * The caller recycles the result; this function never does, since a caller that also draws it to
 * screen first would then have nothing left to draw.
 */
internal fun renderAnnotatedBitmap(
    photo: Bitmap,
    segments: List<Pair<Segment, String?>>,
    textMeasurer: TextMeasurer,
    density: Density,
): Bitmap {
    val imageBitmap = ImageBitmap(photo.width, photo.height)
    val canvas = Canvas(imageBitmap)
    val size = Size(photo.width.toFloat(), photo.height.toFloat())
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, size) {
        drawExportSegments(photo.asImageBitmap(), segments, textMeasurer)
    }
    return imageBitmap.asAndroidBitmap()
}
