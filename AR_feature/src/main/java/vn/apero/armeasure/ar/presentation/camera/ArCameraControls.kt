package vn.apero.armeasure.ar.presentation.camera

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Session
import kotlin.math.abs
import vn.apero.armeasure.ar.data.arcore.PoseProjector
import vn.apero.armeasure.ar.domain.geometry.length
import vn.apero.armeasure.ar.domain.geometry.measureDistanceMeters
import vn.apero.armeasure.ar.domain.geometry.nearestIndexWithin
import vn.apero.armeasure.ar.presentation.ruler.MeasureOverlay
import vn.apero.armeasure.ar.presentation.ruler.MeasureState
import vn.apero.armeasure.ar.presentation.shapes.ShapeBase
import vn.apero.armeasure.ar.presentation.shapes.ShapeMeasureState
import vn.apero.armeasure.ar.presentation.shared.ChromePill
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult

/**
 * Bottom-bar "+" handling for Distance, folded out of [ArCameraScreen] to keep that file's
 * orchestration readable. Unchanged from the old `MeasureScreen.kt`'s `onAddPoint` body.
 */
internal fun commitDistancePoint(
    state: MeasureState,
    session: Session,
    unit: LengthUnit,
    onResult: (MeasurementResult) -> Unit,
) {
    val committed = state.commitLivePoint(session)
    if (committed && state.worldPoints.size >= 2) {
        val points = state.worldPoints
        val meters = measureDistanceMeters(points[points.size - 2], points[points.size - 1])
        onResult(MeasurementResult.Distance(meters, unit))
    }
}

/**
 * Bottom-bar "+" handling for Box/Cylinder — the [MeasurementResult] mapping that used to live in
 * `ArMeasureBoxScreen`/`ArMeasureCylinderScreen` (`ShapeMeasureScreen.kt`), now a shared branch
 * since both shapes commute through the same [ShapeMeasureState].
 */
internal fun commitShapeStep(
    state: ShapeMeasureState,
    session: Session,
    unit: LengthUnit,
    onResult: (MeasurementResult) -> Unit,
) {
    val before = state.shapes.size
    state.commitStep(session)
    if (state.shapes.size <= before) return
    val shape = state.shapes.last()
    when (val base = shape.base) {
        is ShapeBase.Rect ->
            onResult(MeasurementResult.Box(base.edgeU.length(), base.edgeV.length(), abs(shape.height), unit))
        is ShapeBase.Circle ->
            onResult(MeasurementResult.Cylinder(base.radius, abs(shape.height), unit))
    }
}

/** Distance's overlay plus its point-drag gesture handling — unchanged from the old `MeasureScreen.kt`. */
@Composable
internal fun DistanceOverlay(
    state: MeasureState,
    projector: PoseProjector,
    session: Session?,
    viewSize: IntSize,
    modifier: Modifier,
) {
    MeasureOverlay(
        frameProvider = { state.overlay },
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { touch ->
                    val positions = state.worldPoints.map {
                        projector.project(it, viewSize.width, viewSize.height)?.let { p -> p.x to p.y }
                    }
                    val index = nearestIndexWithin(
                        positions,
                        touch = touch.x to touch.y,
                        maxDistancePx = 32.dp.toPx(),
                    )
                    if (index != null) state.beginDrag(index, touch)
                },
                onDrag = { change, _ ->
                    if (state.draggingIndex != null) {
                        change.consume()
                        state.updateDragTouch(change.position)
                    }
                },
                onDragEnd = {
                    if (state.draggingIndex != null) {
                        session?.let { state.commitDrag(it) } ?: state.cancelDrag()
                    }
                },
                onDragCancel = state::cancelDrag,
            )
        },
    )
}

/** Debug-only tool picker for this phase — phase 06 replaces it with the design's real chrome. */
@Composable
internal fun ToolSwitcher(tool: MeasureTool, onSelect: (MeasureTool) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MeasureTool.entries.forEach { candidate ->
            ChromePill(enabled = candidate != tool, onClick = { onSelect(candidate) }) {
                Text(
                    text = candidate.name,
                    color = if (candidate == tool) Color(0xFF34C759) else Color.White,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
