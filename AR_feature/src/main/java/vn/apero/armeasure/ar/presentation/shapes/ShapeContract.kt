package vn.apero.armeasure.ar.presentation.shapes

import androidx.annotation.StringRes
import com.google.ar.core.Anchor
import vn.apero.armeasure.R
import vn.apero.armeasure.ar.domain.geometry.PlaneBasis
import vn.apero.armeasure.ar.domain.geometry.Vec3
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.common.presentation.mvi.MviEffect
import vn.apero.armeasure.common.presentation.mvi.MviIntent
import vn.apero.armeasure.common.presentation.mvi.MviState

/**
 * Which shape a [ShapeMeasureViewModel] is building. Box and Cylinder share every state transition
 * — only which pure-math functions turn the live reading into a base differ.
 */
internal enum class ShapeKind(@StringRes val nameRes: Int) {
    Box(R.string.armeasure_shape_name_box),
    Cylinder(R.string.armeasure_shape_name_cylinder),
}

/**
 * A committed base: a box's two independently drawn edge vectors (see
 * [vn.apero.armeasure.ar.domain.geometry.parallelogramCorners] — NOT forced to a right angle, a box
 * is whatever parallelogram the two actually drawn edges describe), or a circle's radius plus the
 * arbitrary basis its ring is drawn in.
 */
internal sealed class ShapeBase {
    data class Rect(val edgeU: Vec3, val edgeV: Vec3) : ShapeBase()
    data class Circle(val radius: Float, val basis: PlaneBasis) : ShapeBase()
}

/** One finished box or cylinder: an anchored origin plus everything needed to redraw it every frame. */
internal class MeasuredShape(
    val kind: ShapeKind,
    val originAnchor: Anchor,
    val normal: Vec3,
    val base: ShapeBase,
    val height: Float,
)

/**
 * Which tap this shape is waiting on next.
 *
 * Only the origin gets an anchor — every later step is a plain number (or vector) measured
 * relative to it (see `ShapeMath`) and re-derived from the anchor's current pose every frame, so
 * a box never costs more than one entry in ARCore's anchor budget, no matter how many corners it
 * has.
 *
 * Box and Cylinder diverge for the base: a circle is rotationally symmetric, so it has nothing to
 * orient — one tap (center to edge) is enough ([SizingCircle]). A box's two sides are each
 * something the user actually measured, so both are drawn freehand, independently
 * ([SizingEdgeU], then [SizingEdgeV]) — the base is whatever parallelogram those two edges
 * describe, not corrected to a right angle a fixed axis would have implied instead.
 */
internal sealed class ShapePhase {
    object AwaitingOrigin : ShapePhase()
    /** Box only: drawing the first edge's direction and length freehand. */
    data class SizingEdgeU(val originAnchor: Anchor, val normal: Vec3) : ShapePhase()
    /** Box only: drawing the second edge, independent of the first — see [ShapeBase.Rect]. */
    data class SizingEdgeV(val originAnchor: Anchor, val normal: Vec3, val edgeU: Vec3) : ShapePhase()
    /** Cylinder only: a circle has no edge to draw — one tap sets center-to-edge, i.e. the radius. */
    data class SizingCircle(val originAnchor: Anchor, val normal: Vec3, val basis: PlaneBasis) : ShapePhase()
    data class SizingHeight(val originAnchor: Anchor, val normal: Vec3, val base: ShapeBase) : ShapePhase()
}

/** The origin anchor a live (not yet finished) [ShapePhase] is built on, or null for [ShapePhase.AwaitingOrigin]. */
internal fun ShapePhase.originAnchorOrNull(): Anchor? = when (this) {
    is ShapePhase.SizingEdgeU -> originAnchor
    is ShapePhase.SizingEdgeV -> originAnchor
    is ShapePhase.SizingCircle -> originAnchor
    is ShapePhase.SizingHeight -> originAnchor
    ShapePhase.AwaitingOrigin -> null
}

/**
 * MVI state for the box/cylinder tools — **only** what a tap changes.
 *
 * [phase] advances one step per "+" tap, which is the whole state machine; the live reading, the
 * steadiness verdict and the projected wireframe are per-frame values and live in [ShapeFrameStream]
 * instead. That class's KDoc carries the reasoning.
 *
 * [phase] holds an ARCore [Anchor], so this state is not `Bundle`-safe and
 * [MviViewModel.persist][vn.apero.armeasure.common.presentation.mvi.MviViewModel.persist] is
 * deliberately not overridden: an anchor is meaningless once the session that issued it is gone, so
 * a half-drawn box has nothing worth restoring after process death.
 *
 * The shape *kind* is not here: it is fixed at construction, so it is configuration on
 * [ShapeMeasureViewModel] rather than state — and the base class calls `createInitialState()` before
 * a subclass's constructor-parameter fields are guaranteed assigned, so an initial state built from
 * a constructor argument would be a trap.
 */
internal data class ShapeUiState(
    val phase: ShapePhase = ShapePhase.AwaitingOrigin,
    val shapeCount: Int = 0,
    val canRedo: Boolean = false,
) : MviState {

    val canUndo: Boolean get() = shapeCount > 0 || phase != ShapePhase.AwaitingOrigin
}

internal sealed interface ShapeIntent : MviIntent {
    /**
     * Advance the shape in progress by one tap — the bottom bar's "+". Carries the unit for the same
     * reason [vn.apero.armeasure.ar.presentation.ruler.MeasureIntent.CommitLivePoint] does: the unit
     * belongs to the camera screen, not to this ViewModel.
     */
    data class CommitStep(val unit: LengthUnit) : ShapeIntent
    data object Undo : ShapeIntent
    data object Redo : ShapeIntent
    data object Clear : ShapeIntent
}

internal sealed interface ShapeEffect : MviEffect {
    /** A tap that actually *finished* a shape, with its dimensions. */
    data class Measured(val result: MeasurementResult) : ShapeEffect
}
