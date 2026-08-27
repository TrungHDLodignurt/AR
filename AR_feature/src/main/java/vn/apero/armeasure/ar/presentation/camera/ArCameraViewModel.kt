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

    init {
        // Seeded here rather than in createInitialState(): the base class builds its initial state
        // from a field initializer, which runs *before* a subclass's constructor-parameter fields
        // are assigned — so reading unitPreference/savedState there would read null. Doing it in an
        // init block is the one ordering that is guaranteed safe, and it keeps
        // createInitialState() a pure default.
        val restoredTool = savedState.get<String>(KeyTool)
            ?.let { name -> MeasureTool.entries.firstOrNull { it.name == name } }
        updateState {
            copy(unit = unitPreference.unit, tool = restoredTool ?: tool)
        }
    }

    override fun createInitialState() = ArCameraUiState()

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
