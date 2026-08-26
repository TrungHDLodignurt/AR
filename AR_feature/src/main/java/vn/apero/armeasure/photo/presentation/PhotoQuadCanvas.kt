package vn.apero.armeasure.photo.presentation

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vn.apero.armeasure.R
import vn.apero.armeasure.common.domain.formatLength

/**
 * The photo plus one of three things, depending on [PhotoMeasureState]:
 *  - no quad yet: the plain photo, waiting for a tap on the reference object (see
 *    [PhotoMeasureState.revealQuadAt] — nothing is pre-placed, same as ARuler)
 *  - a quad but not calibrated yet, OR [PhotoMeasureState.isEditingQuad] ("Chỉnh sửa tỉ lệ"): the
 *    draggable quad ([QuadEditorCanvas]) — re-checking `isEditingQuad` here, not just
 *    `!isCalibrated`, is what makes the quad editor reachable again after a first confirm; before
 *    this flag existed, a non-null homography made this branch permanently unreachable.
 *  - calibrated and not re-editing: one measuring line with two draggable endpoints
 *    ([DraggableHandlesOverlay]), matching ARuler's "Chiều dài" tool — drag either end to
 *    measure, not tap-2-points.
 *
 * Coordinates throughout are display pixels — this composable's own size — never the bitmap's
 * native pixel grid. See `Homography.kt` for why that is fine for the maths.
 */
@Composable
internal fun PhotoQuadCanvas(
    photo: ImageBitmap,
    state: PhotoMeasureState,
    modifier: Modifier = Modifier,
    onLineDragEnd: () -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val coroutineScope = rememberCoroutineScope()

    val showQuadEditor = state.isEditingQuad || (!state.isCalibrated && state.quad.isNotEmpty())
    val showTapToPlace = !state.isCalibrated && state.quad.isEmpty() && !state.isEditingQuad

    Box(modifier = modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
        when {
            showTapToPlace -> {
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
                ) {
                    drawPhotoAnnotations(photo, line = null, label = null, lineColor = state.lineColor, textMeasurer = textMeasurer)
                }
                if (state.isDetectingQuad) {
                    Text(
                        stringResource(R.string.armeasure_photo_detecting_edges),
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color(0x8C000000))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            showQuadEditor -> {
                QuadEditorCanvas(
                    photo = photo,
                    quad = state.quad,
                    onCornerDrag = { index, position -> state.moveQuadCorner(index, position) },
                    modifier = Modifier.fillMaxSize(),
                    onCornerDragEnd = { state.commitDrag() },
                )
            }

            else -> {
                val line = state.line
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val label = state.currentDistanceMm?.let { formatLength(it / 1000f, state.unit) }
                    drawPhotoAnnotations(photo, line, label, state.lineColor, textMeasurer)
                }

                if (line != null) {
                    DraggableHandlesOverlay(
                        photo = photo,
                        points = listOf(line.start, line.end),
                        onPointDrag = { index, position -> state.moveLineEndpoint(index == 0, position) },
                        canvasSize = canvasSize,
                        modifier = Modifier.fillMaxSize(),
                        onPointDragEnd = {
                            state.commitDrag()
                            onLineDragEnd()
                        },
                    )
                }
            }
        }
    }
}
