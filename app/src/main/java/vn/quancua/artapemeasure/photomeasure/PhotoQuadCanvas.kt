package vn.quancua.artapemeasure.photomeasure

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import kotlinx.coroutines.launch
import vn.quancua.artapemeasure.measure.formatLength
import vn.quancua.artapemeasure.ui.drawLabelPill

private val LineColor = Color(0xFFFF453A)
private val LabelStyle = TextStyle(color = Color(0xFF1C1C1E), fontSize = 13.sp, fontWeight = FontWeight.Medium)

/**
 * The photo plus one of three things, depending on [PhotoMeasureState]:
 *  - no quad yet: the plain photo, waiting for a tap on the reference object (see
 *    [PhotoMeasureState.revealQuadAt] — nothing is pre-placed, same as ARuler)
 *  - a quad but not yet calibrated: the draggable quad ([QuadEditorCanvas])
 *  - calibrated: one measuring line with two draggable endpoints ([DraggableHandlesOverlay]),
 *    matching ARuler's "Chiều dài" tool — drag either end to measure, not tap-2-points
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
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
        when {
            !state.isCalibrated && state.quad.isEmpty() -> {
                Canvas(
                    modifier = Modifier.fillMaxSize().pointerInput(canvasSize) {
                        detectTapGestures { point ->
                            // revealQuadAt runs Canny+Hough auto-fit off the main thread and is
                            // a suspend fun for exactly that reason — detectTapGestures's own
                            // callback isn't a coroutine, so it has to be launched into one.
                            coroutineScope.launch {
                                state.revealQuadAt(point, canvasSize.width.toFloat(), canvasSize.height.toFloat())
                            }
                        }
                    },
                ) { drawPlainPhoto(photo) }
                if (state.isDetectingQuad) {
                    Text(
                        "Đang dò cạnh...",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color(0x8C000000))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            !state.isCalibrated -> {
                QuadEditorCanvas(
                    photo = photo,
                    quad = state.quad,
                    onCornerDrag = { index, position -> state.moveQuadCorner(index, position) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                val line = state.line
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawPlainPhoto(photo)
                    if (line != null) {
                        drawLine(LineColor, line.start, line.end, strokeWidth = 2.dp.toPx())
                        drawCircle(LineColor, 5.dp.toPx(), line.start)
                        drawCircle(LineColor, 5.dp.toPx(), line.end)
                        val mid = Offset((line.start.x + line.end.x) / 2f, (line.start.y + line.end.y) / 2f)
                        val distanceMm = state.currentDistanceMm
                        if (distanceMm != null) {
                            val label = formatLength(distanceMm / 1000f, state.unit)
                            drawLabelPill(textMeasurer, label, mid, LabelStyle, backgroundColor = LineColor)
                        }
                    }
                }

                if (line != null) {
                    DraggableHandlesOverlay(
                        photo = photo,
                        points = listOf(line.start, line.end),
                        onPointDrag = { index, position -> state.moveLineEndpoint(index == 0, position) },
                        canvasSize = canvasSize,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Aspect-fit rather than stretch-to-fill — see the class doc on `Homography` for why a squashed photo would still be internally consistent but is needlessly harder to align by eye. */
private fun DrawScope.drawPlainPhoto(photo: ImageBitmap) {
    val fit = aspectFit(photo.width.toFloat(), photo.height.toFloat(), size.width, size.height)
    drawImage(
        photo,
        dstOffset = IntOffset(fit.offsetX.roundToInt(), fit.offsetY.roundToInt()),
        dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
    )
}
