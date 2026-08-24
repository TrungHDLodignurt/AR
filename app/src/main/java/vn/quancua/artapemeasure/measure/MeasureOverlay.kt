package vn.quancua.artapemeasure.measure

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.quancua.artapemeasure.ui.drawLabelPill

private val LineColor = Color.White
private val PillText = Color(0xFF1C1C1E)
private val DragAccent = Color(0xFF0A84FF)

/** How far the lifted drag preview sits above the actual point, in dp. */
private val DragLiftHeight = 56.dp

/**
 * All measurement graphics, drawn in screen space.
 *
 * [frameProvider] is invoked INSIDE the draw lambda on purpose. Reading the snapshot state
 * there invalidates only the draw phase, so a new ARCore frame repaints without recomposing
 * or re-laying out anything.
 */
@Composable
fun MeasureOverlay(
    frameProvider: () -> OverlayFrame,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = PillText,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )

    Canvas(modifier = modifier) {
        val frame = frameProvider()


        frame.committed.forEach { drawSegment(it, dashed = false) }
        frame.live?.let { drawSegment(it, dashed = true) }

        // Endpoint dots after the lines, so they sit on top of the stroke ends.
        frame.points.forEach { point ->
            drawCircle(color = LineColor, radius = 5.dp.toPx(), center = point)
        }

        // Labels last — nothing should overlap a number.
        frame.committed.forEach { drawLabelPill(textMeasurer, it.label, it.midpoint, labelStyle) }
        frame.live?.let { drawLabelPill(textMeasurer, it.label, it.midpoint, labelStyle) }

        frame.draggingPoint?.let { drawDragPreview(it) }

        // The reticle and the drag preview both mean "here is the point that matters right
        // now" — showing both at once would split the user's attention between two claims.
        if (frame.draggingPoint == null) {
            drawReticle(
                center = Offset(size.width / 2f, size.height / 2f),
                onSurface = frame.reticleOnSurface,
            )
        }
    }
}

/**
 * Highlights the point being dragged, and lifts a second preview of it above the touch —
 * the fingertip sits exactly on top of the real point otherwise, hiding the one thing the
 * user is trying to see precisely.
 *
 * The reference app (ARuler) solves the same occlusion problem with a live magnified crop of
 * the camera feed rendered by a custom GPU shader, positioned by the same logic: away from
 * the fingertip. Reproducing that needs a per-frame texture read-back from the AR camera
 * feed, which this app's 2D Canvas-over-SceneView overlay has no cheap access to — a
 * disproportionate amount of GPU plumbing for a demo app. This keeps the actual goal (see
 * where the point will land despite the finger covering it) and drops the camera-zoom fidelity.
 */
private fun DrawScope.drawDragPreview(anchor: Offset) {
    drawCircle(color = LineColor, radius = 7.dp.toPx(), center = anchor)
    drawCircle(color = DragAccent, radius = 11.dp.toPx(), center = anchor, style = Stroke(width = 2.dp.toPx()))

    val lifted = anchor.copy(y = anchor.y - DragLiftHeight.toPx())
    drawLine(color = DragAccent.copy(alpha = 0.6f), start = anchor, end = lifted, strokeWidth = 1.5.dp.toPx())
    drawCircle(color = Color.White, radius = 14.dp.toPx(), center = lifted)
    drawCircle(color = DragAccent, radius = 14.dp.toPx(), center = lifted, style = Stroke(width = 2.dp.toPx()))
}

private fun DrawScope.drawSegment(segment: Segment2D, dashed: Boolean) {
    drawLine(
        color = LineColor,
        start = segment.start,
        end = segment.end,
        strokeWidth = 2.dp.toPx(),
        pathEffect = if (dashed) {
            val dash = 6.dp.toPx()
            PathEffect.dashPathEffect(floatArrayOf(dash, dash))
        } else {
            null
        },
    )
}

/**
 * The aiming reticle, fixed at the screen centre.
 *
 * Solid dot when a surface is locked, hollow ring when not — so "nothing measurable here" is
 * visible before the user taps, rather than after they read a wrong number.
 */
private fun DrawScope.drawReticle(center: Offset, onSurface: Boolean) {
    val ringRadius = 11.dp.toPx()
    val ringStroke = Stroke(width = 1.5.dp.toPx())
    if (onSurface) {
        drawCircle(color = LineColor, radius = 4.dp.toPx(), center = center)
        drawCircle(
            color = LineColor.copy(alpha = 0.35f),
            radius = ringRadius,
            center = center,
            style = ringStroke,
        )
    } else {
        drawCircle(
            color = LineColor.copy(alpha = 0.45f),
            radius = ringRadius,
            center = center,
            style = ringStroke,
        )
    }
}
