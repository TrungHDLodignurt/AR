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
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.Intent
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.State
import vn.apero.armeasure.photo.presentation.components.ColorPickerBar
import vn.apero.armeasure.photo.presentation.components.DraggableHandlesOverlay
import vn.apero.armeasure.photo.presentation.components.LineDrawTopNav
import vn.apero.armeasure.photo.presentation.components.drawPhotoAnnotations

/**
 * SCR-24 ("AR Adjust", design `kYLQt`) — a distinct screen (not an overlay) for drawing exactly
 * one segment, selected the same way [State.isEditingQuad] already swaps SCR-22 back
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
 * This screen's photo box is a *different* size from SCR-23's, which used to matter a great deal:
 * the draft had to be remapped between the two canvases at commit time and for every distance
 * readout. It no longer does. The draft is stored in the photo's own bitmap pixels, so this screen
 * needs to know nothing about SCR-23's canvas — only its own, and only to paint with.
 */
@Composable
internal fun LineDrawScreen(
    photo: ImageBitmap,
    state: State,
    onIntent: (Intent) -> Unit,
    // Direct callback, not an Intent — see PhotoMeasureViewModel's drag methods.
    onDraftEndpointDrag: (isStart: Boolean, position: Vec2) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // The initial draft is placed in bitmap space, so it no longer waits on this screen's own box
    // being measured — the photo's dimensions are all it needs.
    LaunchedEffect(photo) {
        onIntent(Intent.PlaceDraftInitial(photo.width.toFloat(), photo.height.toFloat()))
    }

    Column(modifier = modifier.fillMaxSize().background(ArMeasureTokens.BgPrimary)) {
        LineDrawTopNav(
            title = stringResource(R.string.armeasure_photo_line_draw_title),
            onCancel = { onIntent(Intent.CancelDraft) },
            // The committed segment is reported to the host by
            // PhotoMeasureContract.Effect.MeasurementCompleted, not from here — this screen no
            // longer has to read the segment list back to find out what it just committed.
            onCommit = { onIntent(Intent.CommitDraft) },
        )

        Box(modifier = Modifier.weight(1f).fillMaxSize().onSizeChanged { canvasSize = it }) {
            val draft = state.draftLine
            Canvas(modifier = Modifier.fillMaxSize()) {
                val label = state.draftDistanceMm()?.let { formatLength(it / 1000f, state.unit) }
                drawPhotoAnnotations(photo, draft, label, state.draftColor, textMeasurer)
            }
            if (draft != null && canvasSize != IntSize.Zero) {
                DraggableHandlesOverlay(
                    photo = photo,
                    // bitmap space -> display space, to position the two handles on this canvas.
                    points = listOf(draft.start.toDisplayOffsetIn(photo, canvasSize), draft.end.toDisplayOffsetIn(photo, canvasSize)),
                    // ...and display space -> bitmap space on the way back out.
                    onPointDrag = { index, position ->
                        onDraftEndpointDrag(index == 0, position.toBitmapSpaceIn(photo, canvasSize))
                    },
                    canvasSize = canvasSize,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        ColorPickerBar(
            selected = state.draftColor,
            onSelect = { onIntent(Intent.SetDraftColor(it)) },
            unit = state.unit,
            onSelectUnit = { onIntent(Intent.SetUnit(it)) },
        )
    }
}
