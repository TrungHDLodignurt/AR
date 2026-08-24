package vn.quancua.artapemeasure.photomeasure

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** The three steps of "add a custom reference object", in order. */
private sealed interface AddReferenceStep {
    data object PickingPhoto : AddReferenceStep
    data class Naming(val photo: Bitmap) : AddReferenceStep
    data class AdjustingQuad(val photo: Bitmap, val label: String, val shortSideMm: Float, val longSideMm: Float) : AddReferenceStep
}

/**
 * "Thêm đối tượng mới", replicating ARuler's own flow rather than a plain name+size form:
 * photograph the object → name it and give its real dimensions → drag a quad onto it in that
 * photo (same editor as calibrating the measurement photo) to crop a thumbnail out of it.
 *
 * The quad marked here never feeds into any measurement maths — a fresh quad is drawn on the
 * actual measurement photo later, every time. It exists purely to produce a recognisable
 * thumbnail for the reference picker instead of a generic icon.
 */
@Composable
fun AddCustomReferenceFlow(onSaved: (ReferenceObject) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CustomReferenceStore(context) }
    var step by remember { mutableStateOf<AddReferenceStep>(AddReferenceStep.PickingPhoto) }

    when (val current = step) {
        AddReferenceStep.PickingPhoto -> {
            PickPhotoSheet(
                onPhotoPicked = { uri ->
                    val bitmap = loadRotatedBitmap(context, uri)
                    step = if (bitmap != null) AddReferenceStep.Naming(bitmap) else AddReferenceStep.PickingPhoto
                    if (bitmap == null) onCancel()
                },
                onDismiss = onCancel,
            )
        }

        is AddReferenceStep.Naming -> {
            NameReferenceDialog(
                onConfirm = { label, shortSideMm, longSideMm ->
                    val validated = customReferenceObject(label, shortSideMm, longSideMm) ?: return@NameReferenceDialog
                    step = AddReferenceStep.AdjustingQuad(current.photo, validated.label, validated.shortSideMm, validated.longSideMm)
                },
                onCancel = onCancel,
            )
        }

        is AddReferenceStep.AdjustingQuad -> {
            AdjustQuadScreen(
                photo = current.photo,
                onConfirm = { cropRect ->
                    val saved = store.add(current.label, current.shortSideMm, current.longSideMm, current.photo, cropRect)
                    onSaved(saved)
                },
                onCancel = onCancel,
            )
        }
    }
}
