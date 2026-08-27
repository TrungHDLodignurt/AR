package vn.apero.armeasure.photo.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import vn.apero.armeasure.R
import vn.apero.armeasure.common.domain.formatLength
import vn.apero.armeasure.common.ui.ArMeasureTokens

/**
 * SCR-24 ("AR Adjust", design `kYLQt`) — a distinct screen (not an overlay) for drawing exactly
 * one segment, selected the same way [PhotoMeasureState.isEditingQuad] already swaps SCR-22 back
 * in over SCR-23: an in-Activity state flag, not a second `Activity` or a nav-graph destination.
 * [ArPhotoActivity]'s own KDoc already documents "no NavHost" as this module's navigation model,
 * and a full `Activity` per screen would tear down and rebuild the loaded photo `Bitmap` for no
 * reason — a state-flag screen switch is the smallest change consistent with what's already here.
 *
 * Structurally this is the OLD combined SCR-23 (photo + one draggable line + colour bar) minus the
 * bottom toolbar, under a different top nav — [LineDrawTopNav]'s X/✓ instead of back/undo/redo/Save
 * — which is why it reuses [drawPhotoAnnotations], [DraggableHandlesOverlay], [MagnifierLoupe] and
 * [ColorPickerBar] verbatim rather than rebuilding any of them.
 *
 * [targetCanvasSize] is SCR-23's own last-measured canvas size (this screen has no way to measure
 * a screen it isn't showing) — needed because this screen's own photo box is a *different* size,
 * so every distance readout and the eventual commit must remap through
 * [PhotoMeasureState.commitDrawnSegment] / [PhotoMeasureState.draftDistanceMm] rather than treating
 * the two canvases as interchangeable.
 */
@Composable
internal fun LineDrawScreen(
    photo: ImageBitmap,
    state: PhotoMeasureState,
    targetCanvasSize: IntSize,
    onCommitted: (Segment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(canvasSize) {
        if (canvasSize != IntSize.Zero) state.placeDraftInitial(canvasSize.width.toFloat(), canvasSize.height.toFloat())
    }

    Column(modifier = modifier.fillMaxSize().background(ArMeasureTokens.BgPrimary)) {
        LineDrawTopNav(
            title = stringResource(R.string.armeasure_photo_line_draw_title),
            onCancel = { state.cancelDrawSegment() },
            onCommit = {
                state.commitDrawnSegment(photo.width.toFloat(), photo.height.toFloat(), canvasSize, targetCanvasSize)
                state.segments.lastOrNull()?.let(onCommitted)
            },
        )

        Box(modifier = Modifier.weight(1f).fillMaxSize().onSizeChanged { canvasSize = it }) {
            val draft = state.draftLine
            Canvas(modifier = Modifier.fillMaxSize()) {
                val label = state.draftDistanceMm(photo.width.toFloat(), photo.height.toFloat(), canvasSize, targetCanvasSize)
                    ?.let { formatLength(it / 1000f, state.unit) }
                drawPhotoAnnotations(photo, draft, label, state.draftColor, textMeasurer)
            }
            if (draft != null) {
                DraggableHandlesOverlay(
                    photo = photo,
                    points = listOf(draft.start, draft.end),
                    onPointDrag = { index, position -> state.moveDraftEndpoint(index == 0, position) },
                    canvasSize = canvasSize,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        ColorPickerBar(
            selected = state.draftColor,
            onSelect = state::setDraftColor,
            unit = state.unit,
            onSelectUnit = state::setUnit,
        )
    }
}
