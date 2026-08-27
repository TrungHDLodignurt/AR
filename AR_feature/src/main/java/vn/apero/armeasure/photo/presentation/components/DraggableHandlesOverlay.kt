package vn.apero.armeasure.photo.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import vn.apero.armeasure.photo.domain.imaging.aspectFit

/**
 * A set of draggable circular handles over [photo], with the magnifier loupe shown above
 * whichever one is currently being dragged. Shared by the calibration quad (4 handles) and the
 * measuring line (2 handles) — same interaction, same precision aid, in one place instead of two.
 */
@Composable
internal fun DraggableHandlesOverlay(
    photo: ImageBitmap,
    points: List<Offset>,
    onPointDrag: (index: Int, position: Offset) -> Unit,
    canvasSize: IntSize,
    modifier: Modifier = Modifier,
    onPointDragEnd: (Int) -> Unit = {},
) {
    var dragTarget by remember { mutableStateOf<Offset?>(null) }

    Box(modifier = modifier) {
        points.forEachIndexed { index, point ->
            DragHandle(
                position = point,
                onDrag = { newPosition -> dragTarget = newPosition; onPointDrag(index, newPosition) },
                onDragEnd = { dragTarget = null; onPointDragEnd(index) },
            )
        }

        val target = dragTarget
        if (target != null && canvasSize != IntSize.Zero) {
            val fit = aspectFit(photo.width.toFloat(), photo.height.toFloat(), canvasSize.width.toFloat(), canvasSize.height.toFloat())
            MagnifierLoupe(photo = photo, fit = fit, target = target, canvasSize = canvasSize)
        }
    }
}

/** One draggable handle. Position tracking uses `rememberUpdatedState` so drag deltas add onto the *latest* position, not the one captured when the gesture started. */
@Composable
private fun DragHandle(position: Offset, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit) {
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
