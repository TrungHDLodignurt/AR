package vn.apero.armeasure.ar.presentation.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stall-watchdog decision behind `ArCameraScreen`'s remount logic — the exact piece of logic
 * two previous "confident" fixes got wrong on-device (see the phase-05 hazard record, §11). Kept
 * as a pure function specifically so it can be tested at all.
 */
class ArSessionFrameStreamTest {

    private val timeoutMs = 10_000L

    @Test
    fun `not resumed never remounts, however long the stall`() {
        assertFalse(
            shouldForceRemount(
                lastFrameAtMillis = 0L,
                nowMillis = 1_000_000L,
                isResumed = false,
                timeoutMs = timeoutMs,
            ),
        )
    }

    @Test
    fun `resumed and stalled past the timeout remounts`() {
        assertTrue(
            shouldForceRemount(
                lastFrameAtMillis = 0L,
                nowMillis = timeoutMs + 1,
                isResumed = true,
                timeoutMs = timeoutMs,
            ),
        )
    }

    @Test
    fun `resumed and stalled exactly at the timeout does not remount`() {
        assertFalse(
            shouldForceRemount(
                lastFrameAtMillis = 0L,
                nowMillis = timeoutMs,
                isResumed = true,
                timeoutMs = timeoutMs,
            ),
        )
    }

    @Test
    fun `resumed with a fresh frame does not remount`() {
        assertFalse(
            shouldForceRemount(
                lastFrameAtMillis = 1_000L,
                nowMillis = 1_000L,
                isResumed = true,
                timeoutMs = timeoutMs,
            ),
        )
    }

    @Test
    fun `a clock that went backwards does not remount`() {
        assertFalse(
            shouldForceRemount(
                lastFrameAtMillis = 5_000L,
                nowMillis = 1_000L,
                isResumed = true,
                timeoutMs = timeoutMs,
            ),
        )
    }

    @Test
    fun `noteFrame sets cameraReady and advances lastFrameAtMillis`() {
        val state = ArSessionFrameStream()
        state.lastFrameAtMillis = 0L
        state.cameraReady = false

        state.noteFrame()

        assertTrue(state.cameraReady)
        assertTrue(state.lastFrameAtMillis > 0L)
    }
}
