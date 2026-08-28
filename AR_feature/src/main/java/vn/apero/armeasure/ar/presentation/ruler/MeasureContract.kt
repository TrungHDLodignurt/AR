package vn.apero.armeasure.ar.presentation.ruler

import com.google.ar.core.Anchor
import vn.apero.armeasure.ar.data.arcore.HitSource
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.common.presentation.mvi.MviEffect
import vn.apero.armeasure.common.presentation.mvi.MviIntent
import vn.apero.armeasure.common.presentation.mvi.MviState

/** A committed measurement point: an ARCore anchor plus how its position was obtained. */
internal class MeasuredPoint(val anchor: Anchor, val source: HitSource)

/**
 * MVI state for the two distance tools — **only** what the user drives at human speed.
 *
 * Everything ARCore rewrites per frame (the live reading, the projected overlay, the drag samples)
 * is in [MeasureFrameStream] instead; that class's KDoc carries the reasoning, and it is the whole
 * point of this split. What is left here changes on a tap: a committed point, an undo, a redo, a
 * clear. Small enough that a `copy()` per user action is free.
 *
 * Nothing here is persisted through [MviViewModel.persist][vn.apero.armeasure.common.presentation.mvi.MviViewModel.persist],
 * on purpose: the values that matter are ARCore [Anchor]s, which are meaningless once the session
 * that issued them is gone. A half-finished measurement is not worth restoring, and [pointCount]
 * without its anchors would be a lie about what is on screen.
 *
 * The tool's pairing rule (`chained`) is deliberately NOT here: it is fixed at construction and can
 * never change, so it is configuration on [MeasureViewModel], not state. It also *cannot* be here —
 * `chained`/`kind` are ViewModel configuration rather than state: they never change for the
 * lifetime of the instance, so putting them in an immutable state object would copy them on every
 * update for nothing. (An earlier note here warned that `createInitialState()` could not read a
 * constructor argument at all — that was true when the base built its state from a field
 * initializer, and stopped being true once `_state` became lazy.)
 */
internal data class MeasureUiState(
    val pointCount: Int = 0,
    val canRedo: Boolean = false,
    /** Last point's hit source, surfaced in the UI: a reading you cannot attribute is a reading you cannot calibrate. */
    val lastSource: HitSource? = null,
) : MviState {

    val canUndo: Boolean get() = pointCount > 0
}

/** Everything the user can ask the distance tools to do. */
internal sealed interface MeasureIntent : MviIntent {
    /**
     * Commit the current live reading as a new point — the bottom bar's "+".
     *
     * Carries the unit so a closing segment can be reported in it. The unit belongs to the camera
     * screen (one unit for all four tools), so it travels with the action rather than being mirrored
     * into this ViewModel where it could drift out of date.
     */
    data class CommitLivePoint(val unit: LengthUnit) : MeasureIntent
    data object Undo : MeasureIntent
    data object Redo : MeasureIntent
    data object Clear : MeasureIntent
}

internal sealed interface MeasureEffect : MviEffect {
    /**
     * A commit that actually *closed* a segment, with the length it measured.
     *
     * Emitted only for a closing point: in the unchained tool a point that *opens* a segment would
     * otherwise report the gap between the previous segment's end and this new start — a length
     * that is never drawn and that the user never asked to measure.
     */
    data class Measured(val result: MeasurementResult) : MeasureEffect
}
