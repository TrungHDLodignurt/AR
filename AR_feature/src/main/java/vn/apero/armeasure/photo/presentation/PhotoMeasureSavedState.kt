package vn.apero.armeasure.photo.presentation

import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.State

/**
 * Everything about this screen that survives process death, in one file, as two pure functions.
 *
 * This exists as its own unit rather than inline in [PhotoMeasureViewModel] for a reason specific to
 * this screen's history: restoration was previously six independent `rememberSaveable` patches, and
 * every one of them was a shipped bug before it was a patch. Written as a map/restore pair it is one
 * reviewable list, and — because it needs no `SavedStateHandle` — it is directly testable on the
 * plain JVM, which is what turns four of those six cases from "verify by hand" into unit tests.
 *
 * Keys are namespaced so a host's own `SavedStateHandle` entries can never collide.
 *
 * **What is NOT here: the photo bitmap, the quad, the segments, the homography, the undo history.**
 * A `SavedStateHandle` goes through a `Bundle`, which has a hard transaction size limit and throws
 * rather than truncating, so a multi-megapixel bitmap or a 20-deep snapshot history cannot go in one.
 * Same gap as before this refactor; closing it needs a file-backed store, which is a different
 * feature. The camera capture's `pendingUri` is also absent on purpose — it stays a
 * `rememberSaveable` in `CameraCapture.kt`, next to the `ActivityResult` launcher it belongs to.
 */
internal const val KeyChosenReference = "armeasure.photo.chosenReferenceId"
internal const val KeyPickPhotoSheet = "armeasure.photo.showPickPhotoSheet"
internal const val KeyReferenceSheet = "armeasure.photo.showReferenceSheet"
internal const val KeyEditingReference = "armeasure.photo.editingReferenceId"

/**
 * The four `Bundle`-safe values to write. Ids rather than objects: [vn.apero.armeasure.photo.domain.imaging.ReferenceObject]
 * is a plain data class, and an id always re-resolves against the freshest loaded list — including
 * one that was edited in between.
 */
internal fun State.saveableFields(): Map<String, Any?> = mapOf(
    KeyChosenReference to chosenReferenceId,
    KeyPickPhotoSheet to showPickPhotoSheet,
    KeyReferenceSheet to showReferenceSheet,
    KeyEditingReference to editingReferenceId,
)

/**
 * Reads them back. [saved] is a plain lookup so this works against a `SavedStateHandle`, a `Map`,
 * or a test fake.
 *
 * Note what is *not* here: any resolution of an id into an object. `State.reference` and
 * `State.editingReference` are derived, so restoring cannot race the asynchronous custom-reference
 * load — the bug that once brought a restored custom object back as "A4 paper".
 */
internal fun State.restoring(saved: (String) -> Any?): State = copy(
    chosenReferenceId = saved(KeyChosenReference) as? String,
    showPickPhotoSheet = saved(KeyPickPhotoSheet) as? Boolean ?: false,
    showReferenceSheet = saved(KeyReferenceSheet) as? Boolean ?: false,
    editingReferenceId = saved(KeyEditingReference) as? String,
)
