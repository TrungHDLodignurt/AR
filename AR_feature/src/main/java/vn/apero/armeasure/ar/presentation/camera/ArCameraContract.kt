package vn.apero.armeasure.ar.presentation.camera

import vn.apero.armeasure.common.data.DefaultUnit
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.presentation.mvi.MviEffect
import vn.apero.armeasure.common.presentation.mvi.MviIntent
import vn.apero.armeasure.common.presentation.mvi.MviState

/**
 * Which of the four AR tools is currently active. Swapping never remounts the view below.
 *
 * [Distance] and [DistanceChain] are the same measurement machinery under two pairing rules:
 * Distance consumes points as start/end pairs, so each segment stands alone; DistanceChain
 * continues the polyline from every committed point. See
 * [vn.apero.armeasure.ar.domain.geometry.segmentIndexPairs].
 */
internal enum class MeasureTool { Distance, DistanceChain, Box, Cylinder }

/**
 * MVI state for the camera screen itself — the chrome around the four tools, as opposed to any one
 * tool's measurement.
 *
 * Every field here changes only when the user taps something, which is why the screen's state can be
 * a normal MVI `State` while the tools' per-frame values cannot: nothing ARCore writes appears here.
 * The session's own per-frame facts live in [ArSessionFrameStream].
 *
 * [unit] is one value for all four tools on purpose: swapping tools must never change the unit the
 * numbers are displayed in.
 */
internal data class ArCameraUiState(
    val tool: MeasureTool = MeasureTool.Distance,
    val unit: LengthUnit = DefaultUnit,
    val showModeSheet: Boolean = false,
    val showUnitMenu: Boolean = false,
) : MviState

internal sealed interface ArCameraIntent : MviIntent {
    data class SelectTool(val tool: MeasureTool) : ArCameraIntent
    data class SelectUnit(val unit: LengthUnit) : ArCameraIntent
    data class ShowModeSheet(val show: Boolean) : ArCameraIntent
    data class ShowUnitMenu(val show: Boolean) : ArCameraIntent
}

/**
 * No cases — deliberately.
 *
 * The screen has exactly one piece of transient feedback, the commit-confirmation toast, and it is
 * driven by the *tools'* `Measured` effect rather than by anything this ViewModel decides. The type
 * exists because [vn.apero.armeasure.common.presentation.mvi.MviViewModel] is parameterised on one,
 * and an empty sealed interface says "this screen emits no one-shot events" more precisely than a
 * placeholder case would.
 */
internal sealed interface ArCameraEffect : MviEffect
