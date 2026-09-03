package vn.apero.armeasure.ar.presentation.airdraw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vn.apero.armeasure.ar.domain.geometry.Vec3
import vn.apero.armeasure.ar.domain.geometry.measureDistanceMeters
import vn.apero.armeasure.ar.domain.geometry.plus
import vn.apero.armeasure.ar.domain.geometry.times
import vn.apero.armeasure.ar.presentation.airdraw.components.AirDrawOverlayFrame

/**
 * **Experiment.** Draw in the air by moving the phone: hold a volume key, walk the phone through
 * space, and a stroke is laid down behind the nib.
 *
 * Lives on `experiment/air-draw` and is deliberately not part of the measuring tools. Nothing here
 * measures anything — see [AirDrawFrameLoop]'s header for why accuracy is not a goal.
 *
 * ### Where the nib is, and why that is the whole design
 *
 * The nib sits [TipDistanceMeters] in front of the phone, along a direction **frozen when the
 * stroke begins** and held in world space until it ends. That one choice is what makes this usable,
 * and it is worth spelling out because the two obvious alternatives are both worse:
 *
 * - **Nib in camera space** (Google's *Just a Line* does this): the nib is always dead centre of the
 *   screen, so you can see it — but it swings with the phone's *attitude*, not just its position.
 *   Rotating the phone by θ about its own centre moves the nib `2·d·sin(θ/2)`. At d = 30 cm that
 *   is 5 cm for a 10° wrist wobble and 15 cm for a 30° tilt. Ordinary hand tremor is enough to
 *   scribble centimetres of line nobody asked for, because the lever arm turns angular noise into
 *   positional noise.
 * - **Nib at the phone itself**: rotation contributes exactly zero — `∂nib/∂θ = 0`, not merely
 *   small. But the nib is then at the camera's optical centre, behind the near plane
 *   (`PoseProjector` uses 0.05 m), so it cannot be projected at all: you never see the part of the
 *   stroke you are currently drawing, only what you already left behind.
 *
 * Freezing the direction takes the good half of each: the nib is a visible 30 cm ahead, and once
 * the stroke is running, turning the phone does not move it. Rotate a lot mid-stroke and the nib
 * drifts away from screen centre — but it drifts *on screen only*; in the world it stays exactly
 * where the phone's translation puts it, which is the honest thing to show.
 */
internal class AirDrawFrameStream {

    /** Finished strokes, each a list of world-space points in draw order. */
    var strokes by mutableStateOf<List<List<Vec3>>>(emptyList())
        private set

    /** The stroke being laid down right now; empty when the key is up. */
    var current by mutableStateOf<List<Vec3>>(emptyList())
        private set

    /** True while a stroke is open — drives the nib's own highlight. */
    val drawing: Boolean get() = strokeDirection != null

    /**
     * The stroke's frozen aim direction, captured at key-down and unit length. Null between
     * strokes. Kept private: nothing outside may reset it mid-stroke without ending the stroke,
     * because that is precisely the rotation coupling this class exists to avoid.
     */
    private var strokeDirection: Vec3? = null

    var overlay by mutableStateOf(AirDrawOverlayFrame())

    /** Where the nib is right now, or null when there is no pose to place it against. */
    var tip by mutableStateOf<Vec3?>(null)
        private set

    /**
     * One frame of the pen.
     *
     * @param holding whether the draw key is down this frame.
     * @param cameraPosition the phone's world position — the only term that may move the nib once a
     *   stroke is running.
     * @param cameraForward the phone's current forward axis, used **only** to seize the direction
     *   at key-down. Ignored for the rest of the stroke.
     */
    fun onFrame(holding: Boolean, cameraPosition: Vec3, cameraForward: Vec3) {
        if (holding && strokeDirection == null) strokeDirection = cameraForward
        if (!holding && strokeDirection != null) endStroke()

        val direction = strokeDirection
        tip = if (direction != null) {
            cameraPosition + direction * TipDistanceMeters
        } else {
            cameraPosition + cameraForward * TipDistanceMeters
        }

        if (direction != null) appendTip()
    }

    /**
     * Adds the nib's position, but only once it has actually travelled.
     *
     * At 60 Hz a hand held still still emits sixty points a second, all within tracking noise of
     * each other. Left in, they cost memory and turn every smoothing pass into an average over
     * jitter. The spacing gate is also free smoothing: sub-centimetre wobble never enters the data.
     */
    private fun appendTip() {
        val point = tip ?: return
        val last = current.lastOrNull()
        if (last != null && measureDistanceMeters(last, point) < MinPointSpacingMeters) return
        current = current + point
    }

    /** Closes the open stroke, discarding one too short to have been intended. */
    private fun endStroke() {
        if (current.size >= MinPointsPerStroke) strokes = strokes + listOf(current)
        current = emptyList()
        strokeDirection = null
    }

    /** Drops the most recent finished stroke. A stroke is the unit of undo — nothing finer. */
    fun undo() {
        if (strokes.isNotEmpty()) strokes = strokes.dropLast(1)
    }

    fun clear() {
        strokes = emptyList()
        current = emptyList()
        strokeDirection = null
    }

    /**
     * Abandons the open stroke when the pose is gone. The nib's position is meaningless without
     * tracking, so continuing to append would weld a jump into the middle of the line.
     */
    fun clearForUntrackedFrame() {
        current = emptyList()
        strokeDirection = null
        tip = null
        overlay = AirDrawOverlayFrame()
    }
}

/** How far ahead of the phone the nib floats. Far enough to see, near enough to aim. */
internal const val TipDistanceMeters = 0.30f

/** The nib must travel this far before another point is recorded. */
private const val MinPointSpacingMeters = 0.01f

/** Fewer points than this is a key-press, not a stroke. */
private const val MinPointsPerStroke = 2
