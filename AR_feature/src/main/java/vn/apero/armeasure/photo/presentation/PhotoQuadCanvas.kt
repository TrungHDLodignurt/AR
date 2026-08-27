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
import vn.apero.armeasure.R
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.Intent
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.State

/**
 * The photo plus one of three things, depending on [State]:
 *  - no quad yet: the plain photo, waiting for a tap on the reference object (see
 *    [Intent.TapToReveal] — nothing is pre-placed, same as ARuler)
 *  - a quad but not calibrated yet, OR [State.isEditingQuad] ("Chỉnh sửa tỉ lệ"): the
 *    draggable quad ([QuadEditorCanvas]) — re-checking `isEditingQuad` here, not just
 *    `!isCalibrated`, is what makes the quad editor reachable again after a first confirm; before
 *    this flag existed, a non-null homography made this branch permanently unreachable.
 *  - calibrated and not re-editing: every committed segment, drawn but not draggable (locked
 *    decision: a committed segment cannot be edited, only deleted via its label's trash
 *    affordance — see [SegmentLabelOverlay]). Drawing a *new* segment happens on SCR-24
 *    ([LineDrawScreen]), a separate screen, not here.
 *
 * Coordinates in [State] are the photo's own bitmap pixels, never this composable's
 * display pixels — so this composable is one of the two edges that convert. The tap below goes
 * display -> bitmap on the way in; [QuadEditorCanvas], `drawCommittedSegmentStrokes` and
 * [SegmentLabelOverlay] go bitmap -> display on the way out to be painted.
 */
@Composable
internal fun PhotoQuadCanvas(
    photo: ImageBitmap,
    state: State,
    onIntent: (Intent) -> Unit,
    // Drag is a direct callback rather than an Intent — see PhotoMeasureViewModel's drag methods.
    onCornerDrag: (index: Int, position: Vec2) -> Unit,
    onCornerDragEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val showQuadEditor = state.isEditingQuad || (!state.isCalibrated && state.quad.isNotEmpty())
    val showTapToPlace = !state.isCalibrated && state.quad.isEmpty() && !state.isEditingQuad

    Box(modifier = modifier.fillMaxSize().onSizeChanged { canvasSize = it }) {
        when {
            showTapToPlace -> {
                Canvas(
                    modifier = Modifier.fillMaxSize().pointerInput(canvasSize) {
                        detectTapGestures { point ->
                            // `point` is display space; the state only ever stores bitmap space.
                            // The detection this kicks off runs in the ViewModel's own scope, so it
                            // is no longer cancelled by a configuration change mid-detect.
                            onIntent(
                                Intent.TapToReveal(
                                    point.toBitmapSpaceIn(photo, canvasSize),
                                    photo.width.toFloat(),
                                    photo.height.toFloat(),
                                ),
                            )
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
                    onCornerDrag = onCornerDrag,
                    modifier = Modifier.fillMaxSize(),
                    onCornerDragEnd = onCornerDragEnded,
                )
            }

            else -> {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCommittedSegmentStrokes(photo, state.segments)
                }
                SegmentLabelOverlay(
                    state = state,
                    photo = photo,
                    canvasSize = canvasSize,
                    onDeleteSegment = { onIntent(Intent.DeleteSegment(it)) },
                )
            }
        }
    }
}
