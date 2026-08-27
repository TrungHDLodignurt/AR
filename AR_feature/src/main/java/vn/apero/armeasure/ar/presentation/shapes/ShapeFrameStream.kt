package vn.apero.armeasure.ar.presentation.shapes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vn.apero.armeasure.ar.data.arcore.SurfaceSample
import vn.apero.armeasure.ar.domain.steadiness.SteadinessGate
import vn.apero.armeasure.ar.presentation.shapes.components.ShapeOverlayFrame

/**
 * The box/cylinder tools' **frame stream** — the wireframe counterpart of
 * [vn.apero.armeasure.ar.presentation.ruler.MeasureFrameStream], and outside [ShapeUiState] for the
 * identical reason:
 *
 * `onShapeFrame` runs from ARCore's frame callback at 30-60 Hz. Routing that through
 * `processIntent -> SharedFlow -> handleIntent -> updateState { copy() } -> StateFlow` would cost a
 * coroutine dispatch and a full state allocation per frame, and would replace Compose's per-field
 * invalidation (a new [overlay] invalidates only the draw scope reading it) with whole-state
 * invalidation of every collector. Transient render state is not UI state.
 *
 * Owned by [ShapeMeasureViewModel], read directly by the renderer and by the chrome.
 */
internal class ShapeFrameStream {

    /** Live surface reading under the reticle, or null when the reticle is off-surface. */
    var live by mutableStateOf<SurfaceSample?>(null)
        private set

    private val steadinessGate = SteadinessGate()

    /** Whether [live] has held still long enough to commit — see [SteadinessGate]. */
    val liveStable: Boolean get() = steadinessGate.stable

    var overlay by mutableStateOf(ShapeOverlayFrame())

    /** Whether the bottom "+" button should be tappable right now. */
    val addEnabled: Boolean get() = live != null && liveStable

    /** Feeds one frame's reading into [live] and the steadiness gate. */
    fun noteLiveSample(sample: SurfaceSample?, distanceMeters: Float?) {
        live = sample
        steadinessGate.note(sample, distanceMeters)
    }

    /** Drops the live reading and the wireframe for a frame with no camera pose. */
    fun clearForUntrackedFrame() {
        live = null
        overlay = ShapeOverlayFrame()
    }

    /**
     * Resets the steadiness gate and clears the live reading. Called when this tool becomes the
     * active one after a swap — see [vn.apero.armeasure.ar.presentation.ruler.MeasureFrameStream.onActivated]
     * for the exact bug this closes. Does NOT touch [ShapeUiState.phase] or the committed shapes: a
     * half-drawn shape must survive a tool swap intact.
     */
    fun onActivated() {
        steadinessGate.reset()
        live = null
    }
}
