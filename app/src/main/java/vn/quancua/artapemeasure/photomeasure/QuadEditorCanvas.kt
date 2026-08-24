package vn.quancua.artapemeasure.photomeasure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
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
import vn.quancua.artapemeasure.ui.drawLabelPill

private val LongEdgeColor = Color(0xFF64D2FF)
private val ShortEdgeColor = Color(0xFFFFD60A)
private val EdgeLabelText = Color(0xFF1C1C1E)

/**
 * A photo with a draggable 4-corner quad on top of it — the calibration step in
 * `PhotoQuadCanvas`, lining a quad up with a rectangle of known proportions in a still photo.
 *
 * Top/bottom edges are always the "long side", left/right the "short side" — labelled directly
 * on the edges the way ARuler does, rather than leaving orientation to be inferred from colour
 * alone.
 */
@Composable
fun QuadEditorCanvas(
    photo: ImageBitmap,
    quad: List<Offset>,
    onCornerDrag: (index: Int, newPosition: Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dragTarget by remember { mutableStateOf<Offset?>(null) }

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

        quad.forEachIndexed { index, corner ->
            QuadCornerHandle(
                position = corner,
                onDrag = { newPosition -> dragTarget = newPosition; onCornerDrag(index, newPosition) },
                onDragEnd = { dragTarget = null },
            )
        }

        val target = dragTarget
        if (target != null && canvasSize != IntSize.Zero) {
            val fit = aspectFit(photo.width.toFloat(), photo.height.toFloat(), canvasSize.width.toFloat(), canvasSize.height.toFloat())
            MagnifierLoupe(photo = photo, fit = fit, target = target, canvasSize = canvasSize)
        }
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

/** A draggable corner marker, with a magnifier shown (by the caller) while [onDrag] is firing. */
@Composable
private fun QuadCornerHandle(position: Offset, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit) {
    val radiusDp = 14.dp
    val latestPosition by rememberUpdatedState(position)

    Box(
        modifier = Modifier
            .offset { IntOffset((position.x - radiusDp.toPx()).roundToInt(), (position.y - radiusDp.toPx()).roundToInt()) }
            .size(radiusDp * 2)
            .clip(CircleShape)
            .background(Color.White)
            .border(2.dp, Color(0xFF1C1C1E), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(latestPosition + dragAmount)
                }
            },
    )
}
