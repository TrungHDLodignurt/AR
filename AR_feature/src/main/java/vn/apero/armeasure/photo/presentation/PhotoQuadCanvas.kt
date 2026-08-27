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

/**
 * The photo plus one of three things, depending on [PhotoMeasureState]:
 *  - no quad yet: the plain photo, waiting for a tap on the reference object (see
 *    [PhotoMeasureState.revealQuadAt] — nothing is pre-placed, same as ARuler)
 *  - a quad but not calibrated yet, OR [PhotoMeasureState.isEditingQuad] ("Chỉnh sửa tỉ lệ"): the
 *    draggable quad ([QuadEditorCanvas]) — re-checking `isEditingQuad` here, not just
 *    `!isCalibrated`, is what makes the quad editor reachable again after a first confirm; before
 *    this flag existed, a non-null homography made this branch permanently unreachable.
 *  - calibrated and not re-editing: every committed segment, drawn but not draggable (locked
 *    decision: a committed segment cannot be edited, only deleted via its label's trash
 *    affordance — see [SegmentLabelOverlay]). Drawing a *new* segment happens on SCR-24
 *    ([LineDrawScreen]), a separate screen, not here.
 *
 * Coordinates throughout are display pixels — this composable's own size — never the bitmap's
 * native pixel grid. See `Homography.kt` for why that is fine for the maths.
 */
@Composable
internal fun PhotoQuadCanvas(
    photo: ImageBitmap,
    state: PhotoMeasureState,
    modifier: Modifier = Modifier,
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
                    // line = null makes lineColor a no-op value — nothing is drawn but the plain photo yet.
                    drawPhotoAnnotations(photo, line = null, label = null, lineColor = Color.Unspecified, textMeasurer = textMeasurer)
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
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCommittedSegmentStrokes(photo, state.segments)
                }
                SegmentLabelOverlay(state = state)
            }
        }
    }
}
