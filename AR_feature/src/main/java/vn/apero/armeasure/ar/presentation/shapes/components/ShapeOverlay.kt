package vn.apero.armeasure.ar.presentation.shapes.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.ar.presentation.ruler.Segment2D
import vn.apero.armeasure.ar.presentation.ruler.components.drawReticle
import vn.apero.armeasure.ar.presentation.ruler.components.drawSegment
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.common.ui.drawLabelPill

/**
 * Everything the box/cylinder overlay needs for one frame, in screen pixels — the wireframe
 * counterpart of [OverlayFrame].
 *
 * Edges carry an empty [Segment2D.label] far more often than the point-to-point ruler's segments
 * do: a box has 12 edges but only 3 distinct lengths worth a reader's attention, so most edges
 * are drawn plain and only the ones next to a label actually carry one.
 */
internal data class ShapeOverlayFrame(
    /** Edges facing the camera — drawn solid. */
    val committedEdges: List<Segment2D> = emptyList(),
    /**
     * Edges occluded by the shape's own front side (see [prismEdgeVisibility]) — drawn dashed,
     * the usual "hidden line" wireframe convention, so a finished box or cylinder still reads as
     * a 3D solid instead of a flat tangle of lines.
     */
    val committedHiddenEdges: List<Segment2D> = emptyList(),
    /** One combined "L x W x H" (or "⌀D x H") pill per finished shape, at its top face centre. */
    val committedLabels: List<Pair<Offset, String>> = emptyList(),
    /** The shape currently being sized — dashed, with per-edge labels where they matter. */
    val liveEdges: List<Segment2D> = emptyList(),
    val reticleOnSurface: Boolean = false,
)

/**
 * Wireframe overlay for the box/cylinder tools.
 *
 * Reuses [drawSegment]/[drawLabelPill]/[drawReticle] from the point-to-point ruler's overlay
 * ([MeasureOverlay]) rather than inventing a second drawing vocabulary for what is, at the
 * screen-space level, still just lines, pills and a reticle.
 */
@Composable
internal fun ShapeOverlay(
    frameProvider: () -> ShapeOverlayFrame,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    // AR MeasureLabel spec: ChromeDark pill, white 13/700 — same treatment as MeasureOverlay's
    // point-to-point labels, deliberately not the mock's red (see ArMeasureTokens' KDoc).
    val labelStyle = TextStyle(
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )

    Canvas(modifier = modifier) {
        val frame = frameProvider()

        frame.committedEdges.forEach { drawSegment(it, dashed = false) }
        frame.committedHiddenEdges.forEach { drawSegment(it, dashed = true) }
        frame.liveEdges.forEach { drawSegment(it, dashed = true) }

        // Labels last, same as MeasureOverlay — nothing should overlap a number.
        frame.liveEdges.forEach { edge ->
            if (edge.label.isNotEmpty()) {
                drawLabelPill(textMeasurer, edge.label, edge.midpoint, labelStyle, backgroundColor = ArMeasureTokens.ChromeDark)
            }
        }
        frame.committedLabels.forEach { (anchor, label) ->
            drawLabelPill(textMeasurer, label, anchor, labelStyle, backgroundColor = ArMeasureTokens.ChromeDark)
        }

        drawReticle(
            center = Offset(size.width / 2f, size.height / 2f),
            onSurface = frame.reticleOnSurface,
        )
    }
}
