package vn.quancua.artapemeasure.measure

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import vn.quancua.artapemeasure.ui.drawLabelPill

/**
 * Everything the box/cylinder overlay needs for one frame, in screen pixels — the wireframe
 * counterpart of [OverlayFrame].
 *
 * Edges carry an empty [Segment2D.label] far more often than the point-to-point ruler's segments
 * do: a box has 12 edges but only 3 distinct lengths worth a reader's attention, so most edges
 * are drawn plain and only the ones next to a label actually carry one.
 */
data class ShapeOverlayFrame(
    val committedEdges: List<Segment2D> = emptyList(),
    /** One combined "L x W x H" (or "⌀D x H") pill per finished shape, at its top face centre. */
    val committedLabels: List<Pair<Offset, String>> = emptyList(),
    /** The shape currently being sized — dashed, with per-edge labels where they matter. */
    val liveEdges: List<Segment2D> = emptyList(),
    val reticleOnSurface: Boolean = false,
)

private val PillText = Color(0xFF1C1C1E)

/**
 * Wireframe overlay for the box/cylinder tools.
 *
 * Reuses [drawSegment]/[drawLabelPill]/[drawReticle] from the point-to-point ruler's overlay
 * ([MeasureOverlay]) rather than inventing a second drawing vocabulary for what is, at the
 * screen-space level, still just lines, pills and a reticle.
 */
@Composable
fun ShapeOverlay(
    frameProvider: () -> ShapeOverlayFrame,
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

        frame.committedEdges.forEach { drawSegment(it, dashed = false) }
        frame.liveEdges.forEach { drawSegment(it, dashed = true) }

        // Labels last, same as MeasureOverlay — nothing should overlap a number.
        frame.liveEdges.forEach { edge ->
            if (edge.label.isNotEmpty()) drawLabelPill(textMeasurer, edge.label, edge.midpoint, labelStyle)
        }
        frame.committedLabels.forEach { (anchor, label) ->
            drawLabelPill(textMeasurer, label, anchor, labelStyle)
        }

        drawReticle(
            center = Offset(size.width / 2f, size.height / 2f),
            onSurface = frame.reticleOnSurface,
        )
    }
}
