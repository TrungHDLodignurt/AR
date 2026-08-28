package vn.apero.armeasure.ar.presentation.camera

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import vn.apero.armeasure.common.data.UnitPreference
import vn.apero.armeasure.common.presentation.mvi.MviViewModel

/** SavedStateHandle key for the active tool. */
private const val KeyTool = "ar_camera_tool"

/**
 * The camera screen's own state: which tool is active, which unit is displayed, which sheet is open.
 *
 * Holds no ARCore types and nothing per-frame — the tools' ViewModels own their measurements and
 * their frame streams, and the session's per-frame facts live in [ArSessionFrameStream].
 */
internal class ArCameraViewModel(
    private val unitPreference: UnitPreference,
    private val savedState: SavedStateHandle,
) : MviViewModel<ArCameraUiState, ArCameraIntent, ArCameraEffect>() {

    /**
     * Reads the persisted unit and the restored tool directly, which the base class allows because it
     * builds its initial state lazily — the first read happens when something collects `state`, by
     * which point this constructor has finished. An earlier version seeded these from an `init` block
     * instead, working around a field-initializer ordering trap that no longer exists; that also cost
     * a redundant state emission and a `persist` at construction.
     */
    override fun createInitialState(): ArCameraUiState {
        val restoredTool = savedState.get<String>(KeyTool)
            ?.let { name -> MeasureTool.entries.firstOrNull { it.name == name } }
        return ArCameraUiState(
            unit = unitPreference.unit,
            tool = restoredTool ?: ArCameraUiState().tool,
        )
    }

    override fun handleIntent(intent: ArCameraIntent) {
        when (intent) {
            is ArCameraIntent.SelectTool -> updateState { copy(tool = intent.tool) }
            is ArCameraIntent.SelectUnit -> selectUnit(intent)
            is ArCameraIntent.ShowModeSheet -> updateState { copy(showModeSheet = intent.show) }
            is ArCameraIntent.ShowUnitMenu -> updateState { copy(showUnitMenu = intent.show) }
        }
    }

    private fun selectUnit(intent: ArCameraIntent.SelectUnit) {
        updateState { copy(unit = intent.unit) }
        // The unit is a hard user choice (decision 8), so it outlives this screen entirely — that is
        // UnitPreference's job, not the SavedStateHandle's. Off the main thread: see its KDoc.
        viewModelScope.launch { unitPreference.save(intent.unit) }
    }

    /**
     * Only the tool goes into the handle.
     *
     * It is the one value here that both survives usefully and cannot be recovered from anywhere
     * else: the unit is already persisted process-wide by [UnitPreference], and an open bottom sheet
     * restored after process death would be a surprise rather than a courtesy. The measurements
     * themselves are ARCore anchors and cannot survive at all — see [ArCameraUiState].
     */
    override fun persist(state: ArCameraUiState) {
        savedState[KeyTool] = state.tool.name
    }

    companion object {
        /**
         * Explicit factory, no Koin — phase 01's decision. [UnitPreference] is built from a Context
         * by the caller, so this ViewModel never touches one.
         */
        fun factory(unitPreference: UnitPreference): ViewModelProvider.Factory = viewModelFactory {
            initializer { ArCameraViewModel(unitPreference, createSavedStateHandle()) }
        }
    }
}
