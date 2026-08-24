package vn.quancua.artapemeasure.photomeasure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import vn.quancua.artapemeasure.measure.formatLength
import vn.quancua.artapemeasure.ui.drawLabelPill

private val LongEdgeColor = Color(0xFF64D2FF)
private val ShortEdgeColor = Color(0xFFFFD60A)
private val LineColor = Color.White
private val LabelStyle = TextStyle(color = Color(0xFF1C1C1E), fontSize = 13.sp, fontWeight = FontWeight.Medium)

/**
 * The photo plus, depending on [PhotoMeasureState.isCalibrated]: either the draggable
 * calibration quad, or the tap-to-measure lines.
 *
 * Coordinates throughout are display pixels — this composable's own size — never the bitmap's
 * native pixel grid. See `Homography.kt` for why that is fine for the maths.
 */
@Composable
fun PhotoQuadCanvas(
    photo: ImageBitmap,
    state: PhotoMeasureState,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .fillMaxSize()
            // Layout phase, not draw phase: the quad is Compose state, and mutating state from
            // inside a Canvas draw lambda risks a read/write loop across recompositions.
            .onSizeChanged { size -> state.ensureQuad(size.width.toFloat(), size.height.toFloat()) },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.isCalibrated) {
                    if (state.isCalibrated) detectTapGestures { state.onTap(it) }
                },
        ) {
            // Aspect-fit rather than stretch-to-fill: a squashed photo makes it harder to line
            // the quad up with the reference object's true edges, even though the maths below
            // would still be internally consistent either way (see the class doc on Homography).
            val fit = aspectFit(photo.width.toFloat(), photo.height.toFloat(), size.width, size.height)
            drawImage(
                photo,
                dstOffset = IntOffset(fit.offsetX.roundToInt(), fit.offsetY.roundToInt()),
                dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
            )

            if (!state.isCalibrated && state.quad.size == 4) {
                drawQuad(state.quad)
            }

            state.lines.forEach { line ->
                drawLine(LineColor, line.start, line.end, strokeWidth = 2.dp.toPx())
                drawCircle(LineColor, 5.dp.toPx(), line.start)
                drawCircle(LineColor, 5.dp.toPx(), line.end)
                val mid = Offset((line.start.x + line.end.x) / 2f, (line.start.y + line.end.y) / 2f)
                val label = formatLength(line.distanceMm / 1000f, state.unit)
                drawLabelPill(textMeasurer, label, mid, LabelStyle)
            }
            state.pendingStart?.let { drawCircle(LineColor, 5.dp.toPx(), it) }
        }

        if (!state.isCalibrated) {
            state.quad.forEachIndexed { index, corner ->
                QuadCornerHandle(position = corner, onDrag = { state.moveQuadCorner(index, it) })
            }
        }
    }
}

/** Top/bottom = the reference object's long side, left/right = its short side — see `PhotoMeasureState`. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawQuad(quad: List<Offset>) {
    val (topLeft, topRight, bottomRight, bottomLeft) = quad
    val stroke = 2.dp.toPx()
    drawLine(LongEdgeColor, topLeft, topRight, stroke)
    drawLine(LongEdgeColor, bottomLeft, bottomRight, stroke)
    drawLine(ShortEdgeColor, topLeft, bottomLeft, stroke)
    drawLine(ShortEdgeColor, topRight, bottomRight, stroke)
}

/** A draggable corner marker. Position tracking uses `rememberUpdatedState` so drag deltas add onto the *latest* position, not the one captured when the gesture started. */
@Composable
private fun QuadCornerHandle(position: Offset, onDrag: (Offset) -> Unit) {
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
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(latestPosition + dragAmount)
                }
            },
    )
}
