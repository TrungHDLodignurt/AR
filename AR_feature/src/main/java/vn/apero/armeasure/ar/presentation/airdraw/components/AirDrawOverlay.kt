package vn.apero.armeasure.ar.presentation.airdraw.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import vn.apero.armeasure.common.ui.ArMeasureTokens

/**
 * One drawn segment, already projected. [widthDp] is carried per segment rather than per stroke
 * because a single stroke can run from arm's length to across the room, and a constant screen width
 * would make the far end read as being just as close as the near end — the one perspective cue a
 * 2D-Canvas-over-scene overlay has to supply by hand.
 */
internal data class AirSegment(val a: Offset, val b: Offset, val widthDp: Float)

internal data class AirDrawOverlayFrame(
    val committed: List<AirSegment> = emptyList(),
    val current: List<AirSegment> = emptyList(),
    /** The nib, or null when it is behind the camera. */
    val tip: Offset? = null,
    val tipRadiusDp: Float = 5f,
    val drawing: Boolean = false,
)

/** Committed ink. White, because it has to survive whatever the camera happens to be looking at. */
private val InkColor = Color.White

/** The open stroke, in the brand colour — the one mark on screen whose job is to say "this is live". */
private val LiveInkColor = ArMeasureTokens.Signature

private val InkShadow = Color(0x4D000000)

@Composable
internal fun AirDrawOverlay(
    frameProvider: () -> AirDrawOverlayFrame,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val frame = frameProvider()
        frame.committed.forEach { drawInk(it, InkColor) }
        frame.current.forEach { drawInk(it, LiveInkColor) }
        frame.tip?.let { drawNib(it, frame.tipRadiusDp, frame.drawing) }
    }
}

/**
 * A dark underlay behind every segment, same trick the measurement overlay uses on its endpoints:
 * white ink vanishes against a white wall, and a drawing that disappears on half the surfaces in a
 * room is not a drawing.
 */
private fun DrawScope.drawInk(segment: AirSegment, color: Color) {
    val width = segment.widthDp * density
    drawLine(
        color = InkShadow,
        start = segment.a + Offset(0.6f, 0.6f),
        end = segment.b + Offset(0.6f, 0.6f),
        strokeWidth = width + 1.2f * density,
        cap = StrokeCap.Round,
    )
    drawLine(color = color, start = segment.a, end = segment.b, strokeWidth = width, cap = StrokeCap.Round)
}

/**
 * The nib. Hollow while idle, filled while a stroke is running — the same "armed / not armed"
 * grammar the measuring reticle uses, so the two screens do not teach contradictory vocabularies.
 */
private fun DrawScope.drawNib(at: Offset, radiusDp: Float, drawing: Boolean) {
    val radius = radiusDp * density
    if (drawing) {
        drawCircle(color = InkShadow, radius = radius + 1.5.dp.toPx(), center = at)
        drawCircle(color = LiveInkColor, radius = radius, center = at)
        drawCircle(color = Color.White, radius = radius, center = at, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
    } else {
        drawCircle(
            color = Color(0xB3FFFFFF),
            radius = radius,
            center = at,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
        )
    }
}
