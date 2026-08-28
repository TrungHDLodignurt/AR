package vn.apero.armeasure.common.presentation.mvi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Guards the two properties of the base that are easy to break and impossible to see.
 *
 * Both of these shipped: `state` was briefly a `get()` accessor returning a fresh `asStateFlow()`
 * wrapper each call, which made Compose restart collection on every recomposition; and `_state` was
 * briefly a plain field initializer, which ran before subclass constructor properties were assigned
 * so `createInitialState()` read type defaults. Neither produces a compiler warning and neither is
 * visible in a screenshot.
 */
class MviViewModelTest {

    // The base's init block launches on viewModelScope, which needs a Main dispatcher off-device.
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private data class State(val value: String) : MviState
    private object Intent : MviIntent
    private object Effect : MviEffect

    private class Subject(private val seed: String) : MviViewModel<State, Intent, Effect>() {
        var persisted: State? = null
        override fun createInitialState() = State(seed)
        override fun handleIntent(intent: Intent) = Unit
        override fun persist(state: State) { persisted = state }
        fun set(value: String) = updateState { copy(value = value) }
    }

    @Test
    fun `state is one instance, not a new wrapper per access`() {
        val subject = Subject("a")
        assertSame("a fresh flow per access restarts every Compose collector", subject.state, subject.state)
    }

    @Test
    fun `createInitialState can read a constructor argument`() {
        assertEquals("seeded", Subject("seeded").stateValue.value)
    }

    @Test
    fun `updateState reaches state, stateValue and persist`() {
        val subject = Subject("a")
        subject.set("b")
        assertEquals("b", subject.stateValue.value)
        assertEquals("b", subject.state.value.value)
        assertEquals(State("b"), subject.persisted)
    }
}
