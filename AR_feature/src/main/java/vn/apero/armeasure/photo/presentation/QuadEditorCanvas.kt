package vn.apero.armeasure.photo.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import vn.apero.armeasure.common.ui.drawLabelPill
import vn.apero.armeasure.photo.domain.imaging.aspectFit

private val LongEdgeColor = Color(0xFF64D2FF)
private val ShortEdgeColor = Color(0xFFFFD60A)
private val EdgeLabelText = Color(0xFF1C1C1E)

/**
 * A photo with a draggable 4-corner quad on top of it — the calibration step in
 * `PhotoQuadCanvas`, lining a quad up with a rectangle of known proportions in a still photo.
 * Handle dragging and the magnifier loupe are [DraggableHandlesOverlay] — shared with the
 * measuring line's 2 endpoints, not duplicated here.
 *
 * Top/bottom edges are always the "long side", left/right the "short side" — labelled directly
 * on the edges the way ARuler does, rather than leaving orientation to be inferred from colour
 * alone.
 */
@Composable
internal fun QuadEditorCanvas(
    photo: ImageBitmap,
    quad: List<Offset>,
    onCornerDrag: (index: Int, newPosition: Offset) -> Unit,
    modifier: Modifier = Modifier,
    onCornerDragEnd: () -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
        Canvas(Modifier.fillMaxSize()) {
            val fit = aspectFit(photo.width.toFloat(), photo.height.toFloat(), size.width, size.height)
            drawImage(
                photo,
                dstOffset = IntOffset(fit.offsetX.roundToInt(), fit.offsetY.roundToInt()),
                dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
            )
            if (quad.size == 4) drawQuadWithEdgeLabels(quad, textMeasurer)
        }

        DraggableHandlesOverlay(
            photo = photo,
            points = quad,
            onPointDrag = onCornerDrag,
            canvasSize = canvasSize,
            modifier = Modifier.fillMaxSize(),
            onPointDragEnd = { onCornerDragEnd() },
        )
    }
}

private fun DrawScope.drawQuadWithEdgeLabels(quad: List<Offset>, textMeasurer: TextMeasurer) {
    val (topLeft, topRight, bottomRight, bottomLeft) = quad
    val stroke = 2.dp.toPx()
    drawLine(LongEdgeColor, topLeft, topRight, stroke)
    drawLine(LongEdgeColor, bottomLeft, bottomRight, stroke)
    drawLine(ShortEdgeColor, topLeft, bottomLeft, stroke)
    drawLine(ShortEdgeColor, topRight, bottomRight, stroke)

    val labelStyle = TextStyle(color = EdgeLabelText, fontSize = 12.sp, fontWeight = FontWeight.Medium)

    val bottomMid = Offset((bottomLeft.x + bottomRight.x) / 2f, (bottomLeft.y + bottomRight.y) / 2f)
    drawLabelPill(textMeasurer, "cạnh dài", bottomMid, labelStyle, backgroundColor = LongEdgeColor)

    // Vertical text along the right edge, same as the reference app — rotate the canvas rather
    // than trying to lay out sideways text some other way.
    val rightMid = Offset((topRight.x + bottomRight.x) / 2f, (topRight.y + bottomRight.y) / 2f)
    rotate(degrees = -90f, pivot = rightMid) {
        drawLabelPill(textMeasurer, "cạnh ngắn", rightMid, labelStyle, backgroundColor = ShortEdgeColor)
    }
}
