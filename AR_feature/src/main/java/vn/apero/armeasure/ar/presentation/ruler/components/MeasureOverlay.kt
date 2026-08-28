package vn.apero.armeasure.ar.presentation.ruler.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.ar.presentation.camera.components.drawPlaneDots
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.common.ui.drawLabelPill
import vn.apero.armeasure.ar.presentation.ruler.OverlayFrame
import vn.apero.armeasure.ar.presentation.ruler.Segment2D

private val LineColor = Color.White
private val DragAccent = Color(0xFF0A84FF)
/** 2dp halo beyond the solid endpoint dot's own radius — keeps a white point visible even over a
 * bright/white real-world surface, where a bare white dot alone would disappear. */
private val EndpointHaloColor = Color(0x59FFFFFF)

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
internal fun MeasureOverlay(
    frameProvider: () -> OverlayFrame,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )

    Canvas(modifier = modifier) {
        val frame = frameProvider()

        // Under everything: the surface itself. Drawn first so no measurement graphic ever has to
        // compete with it.
        drawPlaneDots(frame.planeDots)

        frame.committed.forEach { drawSegment(it, dashed = false) }
        frame.live?.let { drawSegment(it, dashed = true) }

        // Endpoint dots after the lines, so they sit on top of the stroke ends — each with a 2dp
        // white halo (insight 7) so it stays visible over a bright real-world surface too.
        frame.points.forEach { point -> drawEndpointDot(point) }

        // Labels last — nothing should overlap a number. AR MeasureLabel spec: ChromeDark pill,
        // white 13/700 text (deliberately not the mock's red — see ArMeasureTokens' KDoc).
        frame.committed.forEach {
            drawLabelPill(textMeasurer, it.label, it.midpoint, labelStyle, backgroundColor = ArMeasureTokens.ChromeDark)
        }
        frame.live?.let {
            drawLabelPill(textMeasurer, it.label, it.midpoint, labelStyle, backgroundColor = ArMeasureTokens.ChromeDark)
        }

        frame.draggingPoint?.let { drawDragPreview(it) }

        // The reticle and the drag preview both mean "here is the point that matters right
        // now" — showing both at once would split the user's attention between two claims.
        if (frame.draggingPoint == null) {
            drawReticle(
                center = Offset(size.width / 2f, size.height / 2f),
                onSurface = frame.reticleOnSurface,
                ring = frame.reticleRing,
                snapped = frame.snapped,
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

/** Shared with [ShapeOverlay] — the box/cylinder tools draw the same kind of line. */
internal fun DrawScope.drawSegment(segment: Segment2D, dashed: Boolean) {
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
 * Two things are layered here and both matter:
 *
 * 1. **The on/off-surface variant** (insight 7): solid when a surface is locked, hollow ring when
 *    not, so "nothing measurable here" is visible before the user taps rather than after they read
 *    a wrong number. Losing that signal to chase the mock's bare dot would be a regression dressed
 *    as a design fix — it survives every change below.
 * 2. **[ring]**, when non-empty: the projection of a 3 cm circle lying *on* the plane, so it draws
 *    as an ellipse that tilts with the surface. A screen-space dot looks identical whether it is on
 *    a floor, a wall, or nothing at all; the tilt is what tells the user they are on a surface at a
 *    glance. Empty (a depth-map/feature-point reading, or a ring clipped by the near plane) falls
 *    back to the flat dot alone.
 */
internal fun DrawScope.drawReticle(
    center: Offset,
    onSurface: Boolean,
    ring: List<Offset> = emptyList(),
    snapped: Boolean = false,
) {
    val ringed = ring.isNotEmpty()
    if (ringed) drawReticleRing(ring)
    // A locked reticle gets a second, screen-space ring: the ellipse alone changes shape with the
    // surface, so growing it would be ambiguous, while an extra concentric ring reads as "held"
    // at any tilt.
    if (snapped) drawSnapLock(center)
    // 8dp diameter per the design when the dot stands alone; shrunk inside a ring, where the
    // ellipse already carries the "here" and a full-size dot would fill it in.
    val dotRadius = if (ringed) 2.2.dp.toPx() else 4.dp.toPx()
    if (onSurface) {
        drawCircle(color = LineColor, radius = dotRadius, center = center)
        drawCircle(
            color = Color(0x40000000),
            radius = dotRadius,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
    } else {
        drawCircle(
            color = Color(0xB3FFFFFF),
            radius = dotRadius,
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

/**
 * The screen-space lock ring drawn over a snapped reticle — the visible half of the promise that
 * tapping now reproduces the existing point exactly.
 */
private fun DrawScope.drawSnapLock(center: Offset) {
    drawCircle(color = Color(0x57000000), radius = 9.dp.toPx(), center = center, style = Stroke(width = 3.dp.toPx()))
    drawCircle(color = LineColor, radius = 9.dp.toPx(), center = center, style = Stroke(width = 1.6.dp.toPx()))
}

/**
 * Strokes the reticle's projected ring as a closed path — dark underlay first, then white, the same
 * two-pass trick [drawEndpointDot] uses so the stroke survives a bright real-world surface.
 *
 * The points already describe an ellipse: they are a world-space circle put through the camera
 * matrices one by one, so the tilt comes out of the projection rather than being faked with a
 * squashed circle that would be wrong at every angle but head-on.
 */
private fun DrawScope.drawReticleRing(ring: List<Offset>) {
    val path = Path().apply {
        moveTo(ring[0].x, ring[0].y)
        for (i in 1 until ring.size) lineTo(ring[i].x, ring[i].y)
        close()
    }
    drawPath(path, color = Color(0x57000000), style = Stroke(width = 2.6.dp.toPx()))
    drawPath(path, color = LineColor, style = Stroke(width = 1.5.dp.toPx()))
}

/** A committed endpoint: a solid white dot plus a 2dp halo (insight 7) so it reads over a bright
 * real-world surface as well as a dark one. */
internal fun DrawScope.drawEndpointDot(point: Offset) {
    drawCircle(color = EndpointHaloColor, radius = 7.dp.toPx(), center = point)
    drawCircle(color = LineColor, radius = 5.dp.toPx(), center = point)
}
