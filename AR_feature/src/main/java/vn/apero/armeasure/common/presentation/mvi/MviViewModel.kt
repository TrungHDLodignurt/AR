package vn.apero.armeasure.common.presentation.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Base ViewModel for the Model-View-Intent pattern, matching the shape used by the Apero apps'
 * `core` module member for member — same generics, same `state`/`effect`/`processIntent`/
 * `updateState`/`sendEffect` surface — so screens here read the same as screens there.
 *
 * @param S immutable UI state, a data class
 * @param I user actions, a sealed interface
 * @param E one-shot events such as navigation or a toast, a sealed interface
 *
 * One deliberate addition over the shared version: [persist]. See its KDoc for why.
 */
internal abstract class MviViewModel<S : MviState, I : MviIntent, E : MviEffect> : ViewModel() {

    /**
     * Lazy, and that is load-bearing rather than a style choice.
     *
     * As a plain initializer this runs during *this* class's construction, i.e. before a subclass's
     * own constructor properties have been assigned — so any `createInitialState()` that reads a
     * constructor argument silently sees the type default (null, 0, false) instead of the value
     * passed in. It compiles, it does not warn, and the screen simply starts in the wrong state.
     * The shared base in `core` has this trap; deferring the first read until something actually
     * collects `state` removes it, by which point the subclass is fully built.
     */
    private val _state: MutableStateFlow<S> by lazy { MutableStateFlow(createInitialState()) }
    val state: StateFlow<S> get() = _state.asStateFlow()
    val stateValue: S get() = _state.value

    private val _effect = Channel<E>(Channel.Factory.BUFFERED)
    val effect = _effect.receiveAsFlow()

    private val _intent = MutableSharedFlow<I>()

    init {
        viewModelScope.launch {
            _intent.collect { handleIntent(it) }
        }
    }

    /** The state this screen starts from — or resumes from, when a subclass restores it. */
    protected abstract fun createInitialState(): S

    protected abstract fun handleIntent(intent: I)

    /** Called from the UI to trigger an action. */
    fun processIntent(intent: I) {
        viewModelScope.launch { _intent.emit(intent) }
    }

    /** Usage: `updateState { copy(isLoading = false) }`. */
    protected fun updateState(reducer: S.() -> S) {
        val next = _state.value.reducer()
        _state.value = next
        persist(next)
    }

    /** A one-shot event: navigation, a toast. Not state. */
    protected fun sendEffect(effect: E) {
        viewModelScope.launch { _effect.send(effect) }
    }

    /**
     * Hook for writing whatever must outlive the process into a `SavedStateHandle`. Called after
     * every state change; a no-op unless a subclass overrides it.
     *
     * The shared base has no equivalent, and its absence is not academic. A ViewModel survives a
     * configuration change but dies with the process, so without this every screen that hands the
     * foreground to another app — a camera, a photo picker — has to remember which individual fields
     * to persist. This module accumulated six separate `rememberSaveable` patches that way, and each
     * one existed because a user hit the bug first. A single hook per screen makes "what survives"
     * one reviewable decision instead of a habit.
     *
     * Only small, `Parcelable`-safe values belong in the handle: ids, flags, enum names. Never a
     * `Bitmap` and never a large list — the handle goes through a `Bundle`, which has a hard
     * transaction size limit and will throw rather than truncate.
     */
    protected open fun persist(state: S) = Unit
}
