package vn.apero.armeasure.common.domain

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A bounded redo history, generic over the entry type, shared by the AR ruler and shape ViewModels.
 *
 * **Only the redo half is in use.** Both callers keep their own unbounded "currently active" list
 * (`points`, `shapes`) rather than mirroring it here, so what this stack holds is the entries that
 * have been undone — bounded by [maxDepth] and normally empty. That is deliberate: an overflow
 * eviction can then never detach an ARCore anchor that is still on screen, which is what [onEvict]
 * is for. In practice the live members are [pushRedo], [popRedo], [dropRedo], [canRedo], [any],
 * [clear] and [drainWithoutEviction].
 *
 * The textbook [push]/[undo]/[redo] pair-of-deques operations and [pushUndo]/[popUndo] have **no
 * callers** and [undoDeque] is permanently empty. The photo screen used them until it moved its
 * undo and redo stacks inside its own immutable `State` during the MVI conversion. They are kept
 * because they are tested and cost nothing; delete them if nothing has adopted them by the next
 * clean-up.
 *
 * Pure Kotlin — the only non-JVM dependency is [mutableStateOf] for [canUndo]/[canRedo], so a
 * toolbar button reading them recomposes without polling. Compiles into the JVM test source set; no
 * Android, ARCore or Compose UI import beyond that.
 */
internal class UndoRedoStack<T>(
    private val maxDepth: Int = 20,
    private val onEvict: (T) -> Unit = {},
) {
    private val undoDeque = ArrayDeque<T>()
    private val redoDeque = ArrayDeque<T>()

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** Pushes [entry] as the newest undo-able action, discarding (evicting) any pending redo. */
    fun push(entry: T) {
        dropRedo()
        pushUndo(entry)
    }

    /** Moves the newest undo entry onto the redo side and returns it, or null if there is none. */
    fun undo(): T? {
        val entry = popUndo() ?: return null
        pushRedo(entry)
        return entry
    }

    /** Moves the newest redo entry back onto the undo side and returns it, or null if there is none. */
    fun redo(): T? {
        val entry = popRedo() ?: return null
        pushUndo(entry)
        return entry
    }

    /** Adds [entry] to the undo side directly, without touching redo. Evicts the oldest undo entry past [maxDepth]. */
    fun pushUndo(entry: T) {
        undoDeque.addLast(entry)
        evictOverflow(undoDeque)
        canUndo = true
    }

    /** Removes and returns the newest undo entry, or null if there is none. Does not touch redo. */
    fun popUndo(): T? {
        val entry = undoDeque.removeLastOrNull()
        canUndo = undoDeque.isNotEmpty()
        return entry
    }

    /** Adds [entry] to the redo side directly, without touching undo. Evicts the oldest redo entry past [maxDepth]. */
    fun pushRedo(entry: T) {
        redoDeque.addLast(entry)
        evictOverflow(redoDeque)
        canRedo = true
    }

    /** Removes and returns the newest redo entry, or null if there is none. Does not touch undo. */
    fun popRedo(): T? {
        val entry = redoDeque.removeLastOrNull()
        canRedo = redoDeque.isNotEmpty()
        return entry
    }

    /** Discards every pending redo entry, calling [onEvict] once for each. */
    fun dropRedo() {
        while (redoDeque.isNotEmpty()) onEvict(redoDeque.removeLast())
        canRedo = false
    }

    /** Empties both sides, calling [onEvict] exactly once for every entry either held. */
    fun clear() {
        dropRedo()
        while (undoDeque.isNotEmpty()) onEvict(undoDeque.removeLast())
        canUndo = false
    }

    /**
     * True if [predicate] matches any entry currently held on either side — for a caller that
     * needs to know whether a resource (e.g. an anchor) is still referenced elsewhere in this
     * history before releasing it.
     */
    fun any(predicate: (T) -> Boolean): Boolean = undoDeque.any(predicate) || redoDeque.any(predicate)

    /**
     * Removes and returns every entry from both sides, in no particular order, WITHOUT calling
     * [onEvict] — for a caller doing its own bulk cleanup (e.g. de-duplicating a resource shared
     * across several entries before releasing each exactly once) instead of the per-entry
     * [onEvict] path.
     */
    fun drainWithoutEviction(): List<T> {
        val all = undoDeque.toList() + redoDeque.toList()
        undoDeque.clear()
        redoDeque.clear()
        canUndo = false
        canRedo = false
        return all
    }

    private fun evictOverflow(deque: ArrayDeque<T>) {
        while (deque.size > maxDepth) onEvict(deque.removeFirst())
    }
}
