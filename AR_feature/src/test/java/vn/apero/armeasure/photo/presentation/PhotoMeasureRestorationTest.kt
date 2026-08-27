package vn.apero.armeasure.photo.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject
import vn.apero.armeasure.photo.domain.imaging.Vec2
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.State

/**
 * The six restoration cases this screen shipped as bugs, four and a half of them now asserted
 * instead of hand-checked.
 *
 * A `SavedStateHandle` is not involved: `saveableFields()`/`restoring()` are pure, so a `Map` stands
 * in for the handle exactly, and `PhotoMeasureViewModel` does nothing to those four values but hand
 * them over. What a test cannot reach from here is the platform half — that the Activity really is
 * given its bundle back after `am kill`, and that the camera's `pendingUri` `rememberSaveable`
 * survives the OEM camera app. Those two stay in the final device round.
 */
class PhotoMeasureRestorationTest {

    private val custom = ReferenceObject(id = "custom-1", label = "phỏn", shortSideMm = 70f, longSideMm = 150f)

    /** Round-trips [State] through the same map `persist` writes and `createInitialState` reads. */
    private fun State.afterProcessDeath(): State {
        val bundle = saveableFields()
        return State().restoring { bundle[it] }
    }

    /** B1 — reference chosen, no photo yet: the restored state is past the picker with that reference. */
    @Test
    fun `a chosen built-in reference survives and lands past the picker`() {
        val restored = State().selectReference(ReferenceObject("builtin:a4", "A4 paper", 210f, 297f, isBuiltIn = true))
            .dismissPickPhotoSheet()
            .afterProcessDeath()

        assertTrue(restored.referenceChosen)
        assertEquals("A4 paper", restored.reference?.label)
    }

    /**
     * B2 — the case that regressed once. A restored CUSTOM reference must never read as "A4 paper".
     *
     * The old shape resolved the id into a field from a `LaunchedEffect`, so the resolve could run
     * before the asynchronous custom-reference load arrived and left the screen on the A4 default
     * while displaying the restored flow. Here `reference` is derived, so the pre-load state reports
     * "not resolvable yet" — never a wrong object — and resolves the moment the list lands. The
     * order of the two events is asserted both ways round to pin that there is no ordering
     * dependency left to reintroduce.
     */
    @Test
    fun `a restored custom reference resolves once the list loads and never falls back to A4`() {
        val restored = State().selectReference(custom).afterProcessDeath()

        // Before the store's load: chosen, unresolved, and above all NOT A4.
        assertTrue(restored.referenceChosen)
        assertFalse(restored.customReferencesLoaded)
        assertNull(restored.reference)

        assertEquals(custom, restored.customReferencesLoaded(listOf(custom)).reference)
    }

    @Test
    fun `resolution does not depend on the load landing after the restore`() {
        val loadedFirst = State().customReferencesLoaded(listOf(custom))
        val restoredFields = State().selectReference(custom).saveableFields()

        assertEquals(custom, loadedFirst.restoring { restoredFields[it] }.reference)
    }

    /** An id that resolves to nothing once the list IS known is stale, and returns to the picker rather than leaving a screen with no reference. */
    @Test
    fun `a stale reference id degrades to the picker, not to a wrong object`() {
        val restored = State().selectReference(custom).afterProcessDeath().customReferencesLoaded(emptyList())

        assertFalse(restored.referenceChosen)
        assertNull(restored.reference)
    }

    /** B3 — the photo picker sheet reopens. Restoring this true is what makes the picked Uri land at all: `PickPhotoSheet` owns the `ActivityResult` launchers. */
    @Test
    fun `an open photo picker sheet reopens`() {
        assertTrue(State().requestPickPhoto().afterProcessDeath().showPickPhotoSheet)
        assertFalse(State().requestPickPhoto().dismissPickPhotoSheet().afterProcessDeath().showPickPhotoSheet)
    }

    /** B4 — the reference edit sheet reopens, in "add new" mode. */
    @Test
    fun `an open add-new reference sheet reopens with no target`() {
        val restored = State().requestAddReference().afterProcessDeath()

        assertTrue(restored.showReferenceSheet)
        assertNull(restored.editingReferenceId)
        assertNull(restored.editingReference)
    }

    /** B5 — editing an existing custom reference comes back editing the same one, again only once the list has loaded. */
    @Test
    fun `an open edit sheet comes back editing the same custom reference`() {
        val restored = State().requestEditReference(custom).afterProcessDeath()

        assertTrue(restored.showReferenceSheet)
        assertEquals(custom.id, restored.editingReferenceId)
        assertNull(restored.editingReference) // list not loaded yet
        assertEquals(custom, restored.customReferencesLoaded(listOf(custom)).editingReference)
    }

    /**
     * The explicit statement of the gap, as a test so it cannot rot silently: the bitmap, quad,
     * segments, homography and undo history are NOT saved. A `Bundle` has a hard transaction size
     * limit and throws rather than truncating, so this is a size constraint, not an oversight.
     */
    @Test
    fun `the quad, the segments and the undo history are deliberately not persisted`() {
        val measured = State(chosenReferenceId = "builtin:a4")
            .fallbackQuadAt(Vec2(500f, 700f), 1000f, 1500f)
            .confirmReference()
            .beginDrawSegment()
            .placeDraftInitial(1000f, 1500f)
            .commitDrawnSegment()
        assertEquals(4, measured.quad.size)
        assertEquals(1, measured.segments.size)

        val restored = measured.afterProcessDeath()

        assertTrue(restored.quad.isEmpty())
        assertTrue(restored.segments.isEmpty())
        assertFalse(restored.isCalibrated)
        assertFalse(restored.canUndo)
        assertEquals(setOf(KeyChosenReference, KeyPickPhotoSheet, KeyReferenceSheet, KeyEditingReference), measured.saveableFields().keys)
    }
}
