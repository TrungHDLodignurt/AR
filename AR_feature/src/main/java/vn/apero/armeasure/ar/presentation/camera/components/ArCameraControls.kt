package vn.apero.armeasure.ar.presentation.camera.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import vn.apero.armeasure.ar.presentation.ruler.MeasureIntent
import vn.apero.armeasure.ar.presentation.ruler.MeasureUiState
import vn.apero.armeasure.ar.presentation.shapes.ShapeEffect
import vn.apero.armeasure.ar.presentation.shapes.ShapeIntent
import vn.apero.armeasure.ar.presentation.shapes.ShapeMeasureViewModel
import vn.apero.armeasure.ar.presentation.shapes.ShapeUiState
import vn.apero.armeasure.ar.presentation.ruler.MeasureEffect
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.domain.geometry.nearestIndexWithin
import vn.apero.armeasure.ar.presentation.ruler.components.MeasureOverlay
import vn.apero.armeasure.ar.presentation.ruler.MeasureViewModel
import vn.apero.armeasure.ar.presentation.camera.ArSessionFrameStream

/**
 * Distance's overlay plus its point-drag gesture handling.
 *
 * The gesture talks to [MeasureViewModel]'s direct drag API rather than sending intents, and reads
 * `viewModel.frames` rather than a collected state snapshot. Both for the same reason, spelled out on
 * [vn.apero.armeasure.ar.presentation.ruler.MeasureFrameStream]: a drag produces values at
 * touch-event rate that are resolved against a surface inside the frame loop, and `onDragStart` must
 * be visible to the very next `onDrag` — which a `processIntent -> SharedFlow -> handleIntent` round
 * trip cannot promise, and which a state snapshot captured by `pointerInput(Unit)` would not see at
 * all.
 */
@Composable
internal fun DistanceOverlay(
    viewModel: MeasureViewModel,
    projector: PoseProjector,
    viewSize: IntSize,
    modifier: Modifier,
) {
    val frames = viewModel.frames
    MeasureOverlay(
        frameProvider = { frames.overlay },
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { touch ->
                    val positions = frames.worldPoints.map {
                        projector.project(it, viewSize.width, viewSize.height)?.let { p -> p.x to p.y }
                    }
                    val index = nearestIndexWithin(
                        positions,
                        touch = touch.x to touch.y,
                        maxDistancePx = 32.dp.toPx(),
                    )
                    if (index != null) viewModel.onDragStart(index, touch)
                },
                onDrag = { change, _ ->
                    if (frames.draggingIndex != null) {
                        change.consume()
                        viewModel.onDragMove(change.position)
                    }
                },
                onDragEnd = {
                    if (frames.draggingIndex != null) viewModel.onDragEnd()
                },
                onDragCancel = viewModel::onDragCancel,
            )
        },
    )
}

/** The active tool's chrome bindings — picked once per recomposition rather than re-`when`-ed at every call site below. */
internal data class ToolActions(
    val canUndo: Boolean,
    val undo: () -> Unit,
    val canRedo: Boolean,
    val redo: () -> Unit,
    val clear: () -> Unit,
    val addEnabled: Boolean,
    val add: () -> Unit,
    val hint: String?,
)

/** The one payload every tool's only effect carries — flattened so the screen has one collector. */
internal fun MeasureEffect.result(): MeasurementResult = when (this) {
    is MeasureEffect.Measured -> result
}

internal fun ShapeEffect.result(): MeasurementResult = when (this) {
    is ShapeEffect.Measured -> result
}

/**
 * Chrome bindings for a distance tool.
 *
 * `addEnabled` and the hint read the frame stream, so this composable recomposes whenever the live
 * reading changes — exactly as the pre-MVI version did, since it read the same two fields off the
 * old state holder. The overlay itself does not: it reads `frames.overlay` inside a draw lambda.
 */
@Composable
internal fun distanceActions(
    viewModel: MeasureViewModel,
    state: MeasureUiState,
    sessionFrames: ArSessionFrameStream,
    unit: LengthUnit,
) = ToolActions(
    canUndo = state.canUndo,
    undo = { viewModel.processIntent(MeasureIntent.Undo) },
    canRedo = state.canRedo,
    redo = { viewModel.processIntent(MeasureIntent.Redo) },
    clear = { viewModel.processIntent(MeasureIntent.Clear) },
    addEnabled = viewModel.frames.addEnabled,
    add = { viewModel.processIntent(MeasureIntent.CommitLivePoint(unit)) },
    hint = trackingFailureHint(sessionFrames)
        ?: distanceHint(sessionFrames, state, viewModel.frames, viewModel.chained),
)

/** Chrome bindings for a shape tool — see [distanceActions]. */
@Composable
internal fun shapeActions(
    viewModel: ShapeMeasureViewModel,
    state: ShapeUiState,
    sessionFrames: ArSessionFrameStream,
    unit: LengthUnit,
) = ToolActions(
    canUndo = state.canUndo,
    undo = { viewModel.processIntent(ShapeIntent.Undo) },
    canRedo = state.canRedo,
    redo = { viewModel.processIntent(ShapeIntent.Redo) },
    clear = { viewModel.processIntent(ShapeIntent.Clear) },
    addEnabled = viewModel.frames.addEnabled,
    add = { viewModel.processIntent(ShapeIntent.CommitStep(unit)) },
    hint = trackingFailureHint(sessionFrames)
        ?: shapeHint(sessionFrames, state, viewModel.frames, viewModel.kind),
)
