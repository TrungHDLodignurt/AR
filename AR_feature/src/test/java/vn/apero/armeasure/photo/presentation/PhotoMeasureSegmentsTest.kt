package vn.apero.armeasure.photo.presentation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.apero.armeasure.common.domain.LengthUnit

/**
 * [PhotoMeasureState]'s segment bookkeeping — the part of the SCR-23/24 restructuring that is
 * genuinely pure-Kotlin logic.
 *
 * Calibration is reached via [PhotoMeasureState.revealQuadAt] with no photo loaded, which is
 * safe: with `photo == null` it skips the Canny+Hough path entirely and falls straight to its own
 * fallback branch — a plain centred quad, exactly the kind of input `computeHomography` needs —
 * without ever touching a real `android.graphics.Bitmap`. That matters here because the plain JVM
 * unit-test runner cannot construct one (`Bitmap.createBitmap` throws "not mocked" — verified
 * before writing this file — and this module has neither Robolectric nor a mocking library).
 * [PhotoMeasureState.commitDrawnSegment]/[PhotoMeasureState.draftDistanceMm] take the photo's
 * width/height as plain `Float`s rather than reading them off a stored `Bitmap` for exactly this
 * reason, so the segment commit/delete/undo/redo cycle below is fully testable without one.
 */
class PhotoMeasureSegmentsTest {

    private val photoWidth = 1000f
    private val photoHeight = 1500f
    private val scr24Canvas = IntSize(354, 480)
    private val scr23Canvas = IntSize(354, 690)

    private fun calibratedState(): PhotoMeasureState {
        val state = PhotoMeasureState(initialUnit = LengthUnit.Cm)
        runBlocking { state.revealQuadAt(Offset(150f, 100f), canvasWidthPx = 300f, canvasHeightPx = 200f) }
        state.confirmReference()
        return state
    }

    private fun PhotoMeasureState.drawAndCommitOneSegment() {
        beginDrawSegment()
        placeDraftInitial(scr24Canvas.width.toFloat(), scr24Canvas.height.toFloat())
        commitDrawnSegment(photoWidth, photoHeight, scr24Canvas, scr23Canvas)
    }

    @Test
    fun `revealQuadAt then confirmReference calibrates without a photo`() {
        assertTrue(calibratedState().isCalibrated)
    }

    @Test
    fun `committing the draft adds exactly one segment in the default red colour`() {
        val state = calibratedState()
        state.drawAndCommitOneSegment()

        assertEquals(1, state.segments.size)
        assertEquals(PhotoLineColors.first(), state.segments.single().color)
        assertFalse(state.isDrawingSegment)
    }

    @Test
    fun `committed segments accumulate across multiple draw cycles`() {
        val state = calibratedState()
        repeat(3) { state.drawAndCommitOneSegment() }
        assertEquals(3, state.segments.size)
    }

    @Test
    fun `a new draft always defaults to red regardless of the previous segment's colour`() {
        val state = calibratedState()
        state.drawAndCommitOneSegment()
        state.deleteSegment(0) // irrelevant to colour, just resets segments for a clean second read
        state.beginDrawSegment()
        state.setDraftColor(PhotoLineColors[3])
        state.placeDraftInitial(scr24Canvas.width.toFloat(), scr24Canvas.height.toFloat())
        state.commitDrawnSegment(photoWidth, photoHeight, scr24Canvas, scr23Canvas)
        assertEquals(PhotoLineColors[3], state.segments.single().color)

        // Opening SCR-24 again must reset to red, never remembering the colour just used above.
        state.beginDrawSegment()
        assertEquals(PhotoLineColors.first(), state.draftColor)
    }

    @Test
    fun `cancelDrawSegment discards the draft and leaves committed segments untouched`() {
        val state = calibratedState()
        state.drawAndCommitOneSegment()

        state.beginDrawSegment()
        state.placeDraftInitial(scr24Canvas.width.toFloat(), scr24Canvas.height.toFloat())
        state.cancelDrawSegment()

        assertEquals(1, state.segments.size)
        assertNull(state.draftLine)
        assertFalse(state.isDrawingSegment)
    }

    @Test
    fun `deleteSegment removes exactly the targeted segment`() {
        val state = calibratedState()
        repeat(2) { state.drawAndCommitOneSegment() }
        val survivor = state.segments[1]

        state.deleteSegment(0)

        assertEquals(listOf(survivor), state.segments)
    }

    @Test
    fun `undo restores the most recently deleted segment`() {
        val state = calibratedState()
        state.drawAndCommitOneSegment()
        val committed = state.segments.single()

        state.deleteSegment(0)
        assertTrue(state.segments.isEmpty())

        state.undo()
        assertEquals(listOf(committed), state.segments)
    }

    @Test
    fun `undo also reverts a segment commit, and redo re-applies it`() {
        val state = calibratedState()
        state.drawAndCommitOneSegment()
        assertEquals(1, state.segments.size)

        state.undo()
        assertTrue(state.segments.isEmpty())

        state.redo()
        assertEquals(1, state.segments.size)
    }

    @Test
    fun `distanceMmFor converts a segment's pixels into real-world millimetres via the solved homography`() {
        val state = calibratedState()
        // The quad's own top edge must measure as exactly the reference's long side (A4: 297mm) —
        // the same sanity check HomographyTest itself uses, run here through PhotoMeasureState's
        // own wiring instead of calling the domain function directly.
        val quad = state.quad
        val segment = Segment(quad[0], quad[1], PhotoLineColors.first())

        val distanceMm = state.distanceMmFor(segment)

        assertNotNull(distanceMm)
        assertEquals(297f, distanceMm!!, 1f)
    }

    @Test
    fun `distanceMmFor is null before calibration`() {
        val state = PhotoMeasureState(initialUnit = LengthUnit.Cm)
        val segment = Segment(Offset(0f, 0f), Offset(100f, 0f), PhotoLineColors.first())
        assertNull(state.distanceMmFor(segment))
    }
}
