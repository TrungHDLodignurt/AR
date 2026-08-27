package vn.apero.armeasure.photo.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.State
import vn.apero.armeasure.photo.presentation.components.PhotoLineColors

/**
 * The photo screen's segment bookkeeping — the part of the SCR-23/24 flow that is genuinely
 * pure-Kotlin logic.
 *
 * Since phase 03 these are the reducers in `PhotoMeasureReducers.kt` rather than methods on a
 * mutable state holder, which is what makes them reachable from a plain JUnit test at all: no
 * `Dispatchers.Main` (the module has no `kotlinx-coroutines-test`), no `SavedStateHandle`, and no
 * `android.graphics.Bitmap` — the JVM runner cannot construct one (`Bitmap.createBitmap` throws
 * "not mocked") and this module has neither Robolectric nor a mocking library.
 *
 * Calibration is reached via [fallbackQuadAt], the no-detector branch of `Intent.TapToReveal`: a
 * plain centred quad, exactly the kind of input `computeHomography` needs, with no bitmap involved.
 * Every coordinate below is in the photo's own bitmap pixels — the only space `State` stores — so no
 * canvas size appears anywhere in this file.
 */
class PhotoMeasureSegmentsTest {

    private val photoWidth = 1000f
    private val photoHeight = 1500f

    /** A4 is a built-in, so it resolves with no asynchronous custom-reference load involved. */
    private fun calibratedState(): State =
        State(chosenReferenceId = "builtin:a4", unit = LengthUnit.Cm)
            .fallbackQuadAt(Vec2(500f, 700f), photoWidth, photoHeight)
            .confirmReference()

    private fun State.drawAndCommitOneSegment(): State =
        beginDrawSegment().placeDraftInitial(photoWidth, photoHeight).commitDrawnSegment()

    @Test
    fun `a tapped fallback quad then confirmReference calibrates without a photo`() {
        assertTrue(calibratedState().isCalibrated)
    }

    @Test
    fun `committing the draft adds exactly one segment in the default red colour`() {
        val state = calibratedState().drawAndCommitOneSegment()

        assertEquals(1, state.segments.size)
        assertEquals(PhotoLineColors.first(), state.segments.single().color)
        assertFalse(state.isDrawingSegment)
    }

    @Test
    fun `committed segments accumulate across multiple draw cycles`() {
        var state = calibratedState()
        repeat(3) { state = state.drawAndCommitOneSegment() }
        assertEquals(3, state.segments.size)
    }

    @Test
    fun `a new draft always defaults to red regardless of the previous segment's colour`() {
        val state = calibratedState()
            .drawAndCommitOneSegment()
            .deleteSegment(0) // irrelevant to colour, just resets segments for a clean second read
            .beginDrawSegment()
            .setDraftColor(PhotoLineColors[3])
            .placeDraftInitial(photoWidth, photoHeight)
            .commitDrawnSegment()
        assertEquals(PhotoLineColors[3], state.segments.single().color)

        // Opening SCR-24 again must reset to red, never remembering the colour just used above.
        assertEquals(PhotoLineColors.first(), state.beginDrawSegment().draftColor)
    }

    @Test
    fun `cancelDrawSegment discards the draft and leaves committed segments untouched`() {
        val state = calibratedState()
            .drawAndCommitOneSegment()
            .beginDrawSegment()
            .placeDraftInitial(photoWidth, photoHeight)
            .cancelDrawSegment()

        assertEquals(1, state.segments.size)
        assertNull(state.draftLine)
        assertFalse(state.isDrawingSegment)
    }

    @Test
    fun `deleteSegment removes exactly the targeted segment`() {
        val state = calibratedState().drawAndCommitOneSegment().drawAndCommitOneSegment()
        val survivor = state.segments[1]

        assertEquals(listOf(survivor), state.deleteSegment(0).segments)
    }

    @Test
    fun `undo restores the most recently deleted segment`() {
        val state = calibratedState().drawAndCommitOneSegment()
        val committed = state.segments.single()

        val deleted = state.deleteSegment(0)
        assertTrue(deleted.segments.isEmpty())

        assertEquals(listOf(committed), deleted.undo().segments)
    }

    @Test
    fun `undo also reverts a segment commit, and redo re-applies it`() {
        val state = calibratedState().drawAndCommitOneSegment()
        assertEquals(1, state.segments.size)

        val undone = state.undo()
        assertTrue(undone.segments.isEmpty())

        assertEquals(1, undone.redo().segments.size)
    }

    /**
     * One drag is one undo step. `moveQuadCorner` captures the pre-drag snapshot on the first frame
     * and every later frame reuses it; only `commitDrag` pushes. Twenty intermediate frames must
     * therefore leave exactly one entry on the undo stack — the invariant that got cheap to assert
     * once the history became part of an immutable `State`.
     */
    @Test
    fun `a multi-frame corner drag is a single undo entry`() {
        var state = calibratedState()
        val before = state.quad
        val depthBefore = state.undoStack.size

        repeat(20) { frame -> state = state.moveQuadCorner(0, Vec2(10f + frame, 20f + frame)) }
        state = state.commitDrag()

        assertEquals(depthBefore + 1, state.undoStack.size)
        assertNull(state.dragStartSnapshot)
        assertEquals(before, state.undo().quad)
    }

    /** A tap that never moved must not leave an undo entry behind. */
    @Test
    fun `commitDrag without a drag is a no-op`() {
        val state = calibratedState()
        assertEquals(state, state.commitDrag())
    }

    @Test
    fun `dragging a corner invalidates the calibration until it is confirmed again`() {
        val dragged = calibratedState().moveQuadCorner(0, Vec2(1f, 2f))
        assertFalse(dragged.isCalibrated)
        assertTrue(dragged.confirmReference().isCalibrated)
    }

    @Test
    fun `distanceMmFor converts a segment's pixels into real-world millimetres via the solved homography`() {
        val state = calibratedState()
        // The quad's own top edge must measure as exactly the reference's long side (A4: 297mm) —
        // the same sanity check HomographyTest itself uses, run here through the screen's own wiring
        // instead of calling the domain function directly.
        val segment = Segment(state.quad[0], state.quad[1], PhotoLineColors.first())

        val distanceMm = state.distanceMmFor(segment)

        assertNotNull(distanceMm)
        assertEquals(297f, distanceMm!!, 1f)
    }

    @Test
    fun `distanceMmFor is null before calibration`() {
        val state = State(chosenReferenceId = "builtin:a4", unit = LengthUnit.Cm)
        val segment = Segment(Vec2(0f, 0f), Vec2(100f, 0f), PhotoLineColors.first())
        assertNull(state.distanceMmFor(segment))
    }

    /**
     * The regression guard for phase 02: committing must move the draft's coordinates by exactly
     * nothing. Both the draft and a committed segment are in the photo's own pixel grid, so any
     * conversion sneaking back into `commitDrawnSegment` — or one applied in the wrong direction —
     * shows up here as endpoints that no longer match what `placeDraftInitial` put down.
     */
    @Test
    fun `committing keeps the draft's bitmap-space endpoints verbatim`() {
        val placed = calibratedState().beginDrawSegment().placeDraftInitial(photoWidth, photoHeight)
        val draft = placed.draftLine!!

        val committed = placed.commitDrawnSegment().segments.single()

        assertEquals(draft.start, committed.start)
        assertEquals(draft.end, committed.end)
        // placeDraftInitial's own contract: a horizontal line 40% of the photo's width, centred.
        assertEquals(Vec2(300f, 750f), committed.start)
        assertEquals(Vec2(700f, 750f), committed.end)
    }

    /**
     * The draft readout on SCR-24 and the committed readout on SCR-23 are the same number by
     * construction — no canvas sizes involved on either side. Before phase 02 the two screens'
     * differently-sized photo boxes made that a remapping problem, and a wrong remap there is
     * precisely the "looks plausible, measures wrong" failure this test exists to catch.
     */
    @Test
    fun `the draft's live distance equals the committed segment's distance`() {
        val placed = calibratedState().beginDrawSegment().placeDraftInitial(photoWidth, photoHeight)
        val draftMm = placed.draftDistanceMm()

        val committed = placed.commitDrawnSegment()

        assertNotNull(draftMm)
        assertEquals(draftMm!!, committed.distanceMmFor(committed.segments.single())!!, 0.001f)
    }
}
