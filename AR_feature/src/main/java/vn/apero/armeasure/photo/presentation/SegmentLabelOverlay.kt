package vn.apero.armeasure.photo.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import vn.apero.armeasure.R
import vn.apero.armeasure.common.domain.formatLength
import vn.apero.armeasure.common.ui.labelTextColorFor
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.State

/**
 * SCR-23's interactive replacement for a Canvas-drawn label pill: a `Canvas` draw cannot receive
 * taps, so each committed [Segment]'s length label is a real composable here instead, letting it
 * carry a trash affordance the plain export drawing ([drawExportSegments]) never does — baking a
 * delete button into the saved PNG would make no sense.
 *
 * **Overlap**: when two segments' midpoints land close enough that their pills overlap, Compose's
 * own hit-testing gives the intersecting area to whichever pill was placed LAST in this loop (the
 * most recently *added* segment — later items are composed on top, same z-order rule as any two
 * overlapping clickable views). The covered pill is not lost: any part of its own bounds outside
 * the overlap still receives taps normally, so its trash remains reachable as long as the two
 * pills are not fully coincident. Two segments whose midpoints land on the exact same pixel is a
 * genuine corner case (the user measured the same segment twice in the same spot) — nudging either
 * line apart resolves it; this overlay does not attempt anything fancier (e.g. auto-offsetting
 * pills), which would fight the "label sits on its segment" design intent for a case that barely
 * ever happens in practice.
 *
 * A segment's endpoints are bitmap-space, so its midpoint is computed there and projected into
 * [canvasSize]'s display pixels only to position the pill — the same conversion the stroke drawing
 * does, so pill and line cannot drift apart.
 */
@Composable
internal fun SegmentLabelOverlay(
    state: State,
    photo: ImageBitmap,
    canvasSize: IntSize,
    onDeleteSegment: (Int) -> Unit,
) {
    if (canvasSize == IntSize.Zero) return
    state.segments.forEachIndexed { index, segment ->
        val distanceMm = state.distanceMmFor(segment) ?: return@forEachIndexed
        val label = formatLength(distanceMm / 1000f, state.unit)
        // bitmap space
        val midBitmap = Vec2((segment.start.x + segment.end.x) / 2f, (segment.start.y + segment.end.y) / 2f)
        // display space, for placement only
        CenteredAt(midBitmap.toDisplayOffsetIn(photo, canvasSize)) {
            SegmentLabelPill(label = label, color = segment.color, onDelete = { onDeleteSegment(index) })
        }
    }
}

/** Places [content] centred on [point] without affecting its parent's own size — the parent here is always a full-size overlay [androidx.compose.foundation.layout.Box] already sized to the photo canvas. */
@Composable
private fun CenteredAt(point: Offset, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints)
        layout(0, 0) {
            placeable.placeRelative(
                (point.x - placeable.width / 2f).roundToInt(),
                (point.y - placeable.height / 2f).roundToInt(),
            )
        }
    }
}

/**
 * A pill matching [vn.apero.armeasure.common.ui.drawLabelPill]'s look, plus a trash target. The
 * icon-size floor (≥30dp drawn, ≥48dp tappable) means this pill is ~48dp tall — considerably
 * taller than the Canvas-drawn version's ~21dp — since the 48dp touch box is now the tallest
 * child; that growth is a deliberate consequence of the floor, not an oversight.
 */
@Composable
private fun SegmentLabelPill(label: String, color: Color, onDelete: () -> Unit) {
    val textColor = labelTextColorFor(color)
    val deleteDescription = stringResource(R.string.armeasure_photo_segment_delete_cd)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 2.dp),
        )
        Box(
            modifier = Modifier
                .size(32.dp)
                .clickable(onClick = onDelete)
                .semantics { contentDescription = deleteDescription },
            contentAlignment = Alignment.Center,
        ) {
            Text("🗑", fontSize = 13.sp)
        }
    }
}
