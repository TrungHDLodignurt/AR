package vn.quancua.artapemeasure.photomeasure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

private val LineColor = Color.White
private val LabelStyle = TextStyle(color = Color(0xFF1C1C1E), fontSize = 13.sp, fontWeight = FontWeight.Medium)

/**
 * The photo plus, depending on [PhotoMeasureState.isCalibrated]: either the draggable
 * calibration quad ([QuadEditorCanvas], shared with the "add custom reference object" flow),
 * or the tap-to-measure lines.
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
        if (!state.isCalibrated) {
            QuadEditorCanvas(
                photo = photo,
                quad = state.quad,
                onCornerDrag = { index, position -> state.moveQuadCorner(index, position) },
                modifier = Modifier.fillMaxSize(),
            )
            return@Box
        }

        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { state.onTap(it) }
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
    }
}
