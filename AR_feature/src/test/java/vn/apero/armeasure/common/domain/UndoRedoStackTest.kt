package vn.apero.armeasure.common.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one generic undo/redo implementation shared by `MeasureState`, `ShapeMeasureState` and
 * `PhotoMeasureState` — see [UndoRedoStack]'s own doc for why it needs both the textbook
 * push/undo/redo/clear surface and the lower-level primitives.
 */
class UndoRedoStackTest {

    @Test
    fun `a fresh stack cannot undo or redo`() {
        val stack = UndoRedoStack<String>()
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
    }

    @Test
    fun `after one push, undo is possible and redo is not`() {
        val stack = UndoRedoStack<String>()
        stack.push("a")
        assertTrue(stack.canUndo)
        assertFalse(stack.canRedo)
    }

    @Test
    fun `undo returns the pushed entry and enables redo`() {
        val stack = UndoRedoStack<String>()
        stack.push("a")
        assertEquals("a", stack.undo())
        assertTrue(stack.canRedo)
    }

    @Test
    fun `redo returns that same entry again and enables undo`() {
        val stack = UndoRedoStack<String>()
        stack.push("a")
        stack.undo()
        assertEquals("a", stack.redo())
        assertTrue(stack.canUndo)
    }

    @Test
    fun `pushing after an undo clears the redo stack and evicts its entries`() {
        val evicted = mutableListOf<String>()
        val stack = UndoRedoStack<String>(onEvict = { evicted += it })
        stack.push("a")
        stack.undo() // "a" now sits on the redo side
        stack.push("b")
        assertFalse(stack.canRedo)
        assertEquals(listOf("a"), evicted)
    }

    @Test
    fun `undo on an empty stack returns null and does not throw`() {
        val stack = UndoRedoStack<String>()
        assertNull(stack.undo())
    }

    @Test
    fun `redo with nothing undone returns null and does not throw`() {
        val stack = UndoRedoStack<String>()
        stack.push("a")
        assertNull(stack.redo())
    }

    @Test
    fun `pushing past maxDepth evicts the oldest entry exactly once`() {
        val evicted = mutableListOf<String>()
        val stack = UndoRedoStack<String>(maxDepth = 3, onEvict = { evicted += it })
        stack.push("a")
        stack.push("b")
        stack.push("c")
        stack.push("d") // over the cap of 3 — "a" must go
        assertEquals(listOf("a"), evicted)
    }

    @Test
    fun `clear empties both sides and evicts every entry once`() {
        val evicted = mutableListOf<String>()
        val stack = UndoRedoStack<String>(onEvict = { evicted += it })
        stack.push("a")
        stack.push("b")
        stack.undo() // "b" moves to redo, "a" stays on undo
        stack.clear()
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
        assertEquals(setOf("a", "b"), evicted.toSet())
        assertEquals(2, evicted.size)
    }

    @Test
    fun `three pushes then three undos then three redos restores the original order`() {
        val stack = UndoRedoStack<String>()
        stack.push("a")
        stack.push("b")
        stack.push("c")
        assertEquals("c", stack.undo())
        assertEquals("b", stack.undo())
        assertEquals("a", stack.undo())
        assertEquals("a", stack.redo())
        assertEquals("b", stack.redo())
        assertEquals("c", stack.redo())
        assertFalse(stack.canRedo)
        assertTrue(stack.canUndo)
    }

    @Test
    fun `onEvict never fires twice for the same entry across a long mixed sequence`() {
        val evictedCounts = mutableMapOf<String, Int>()
        val stack = UndoRedoStack<String>(maxDepth = 2, onEvict = { evictedCounts[it] = (evictedCounts[it] ?: 0) + 1 })
        stack.push("a")
        stack.push("b")
        stack.undo()
        stack.undo()
        stack.push("c") // clears redo: "a" and "b" evicted
        stack.push("d")
        stack.push("e") // over cap of 2 — "c" evicted
        stack.undo()
        stack.redo()
        stack.clear() // "d" and "e" evicted
        assertTrue(evictedCounts.values.all { it == 1 })
    }
}
