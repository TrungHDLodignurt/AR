package vn.apero.armeasure.photo.presentation

import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.apero.armeasure.MeasurementImageSaver
import vn.apero.armeasure.R
import vn.apero.armeasure.common.data.UnitPreference
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.common.domain.formatLength
import vn.apero.armeasure.photo.data.CustomReferenceStore
import vn.apero.armeasure.photo.data.loadRotatedBitmap
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject
import vn.apero.armeasure.photo.domain.imaging.builtInReferenceObjects

/**
 * "Measure from a photo" — no ARCore, no camera-ar feature, no depth. A rectangle of known size
 * (a sheet of paper, a payment card, or a custom object the user registers themselves) lets any
 * two points on a still photo be measured, distorted-perspective photo included. See
 * `Homography.kt` for the maths this ports from ARuler's "Photoruler".
 *
 * Built to SCR-21 (place the quad) / SCR-22 (adjust + confirm it) / SCR-23 (edit the measuring
 * line, which also hosts "Chỉnh sửa tỉ lệ" back into the SCR-22 quad editor without losing the
 * line — see [PhotoMeasureState.beginEditQuad]).
 *
 * @param referenceStore the host constructs and owns this — the module never creates or holds
 *   its own instance, so a host app controls exactly where/how the reference objects persist.
 * @param imageSaver where "Lưu" writes the finished annotated photo; the caller ([ArPhotoActivity])
 *   defaults this to the module's own `MediaStoreImageSaver` when a host hasn't installed one via
 *   `ArMeasureConfig`.
 * @param unit fallback display unit for the very first launch, before [UnitPreference] holds any
 *   value; the persisted, process-wide unit choice (shared with the AR tools) takes over from
 *   then on — see decision 8.
 * @param onResult emitted once per completed measurement gesture (drag-end or first placement),
 *   never per drag frame.
 * @param onClose null renders no close affordance (today's chrome, unchanged); non-null renders
 *   a "✕" the host can wire to its own navigation.
 */
@Composable
internal fun PhotoMeasureScreen(
    referenceStore: CustomReferenceStore,
    imageSaver: MeasurementImageSaver,
    modifier: Modifier = Modifier,
    unit: LengthUnit = LengthUnit.Cm,
    onResult: (MeasurementResult.Photo) -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val coroutineScope = rememberCoroutineScope()
    val unitPreference = remember { UnitPreference(context) }
    // unitPreference.unit already falls back to LengthUnit.Cm on a first-ever launch — same
    // value as the unit param's own default, so the persisted store is the single seed.
    val state = remember { PhotoMeasureState(initialUnit = unitPreference.unit) }
    LaunchedEffect(state.unit) { unitPreference.unit = state.unit }
    val customReferences = remember { mutableStateListOf<ReferenceObject>().apply { addAll(referenceStore.loadAll()) } }

    var referenceChosen by remember { mutableStateOf(false) }
    var showPickPhotoSheet by remember { mutableStateOf(false) }
    var showReferenceSheet by remember { mutableStateOf(false) }
    var editingReference by remember { mutableStateOf<ReferenceObject?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun selectReference(reference: ReferenceObject) {
        state.reference = reference
        referenceChosen = true
        showPickPhotoSheet = true
    }

    fun emitResult() {
        val distanceMm = state.currentDistanceMm ?: return
        onResult(MeasurementResult.Photo(distanceMm / 1000f, state.unit))
    }

    fun requestSave() {
        val photo = state.photo ?: return
        val label = state.currentDistanceMm?.let { formatLength(it / 1000f, state.unit) }
        coroutineScope.launch {
            val uri = performSave(photo, state.line, canvasSize, label, state.lineColor, textMeasurer, density, imageSaver)
            val messageRes = if (uri != null) R.string.armeasure_photo_save_success else R.string.armeasure_photo_save_failure
            Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF1C1C1E))) {
        when {
            !referenceChosen -> {
                ReferencePickerScreen(
                    builtIns = builtInReferenceObjects,
                    customs = customReferences,
                    unit = state.unit,
                    onSelect = { selectReference(it) },
                    onAddNew = { editingReference = null; showReferenceSheet = true },
                    onEdit = { editingReference = it; showReferenceSheet = true },
                    onBack = { onClose?.invoke() },
                )
            }

            state.photo == null -> {
                WaitingForPhoto(
                    referenceLabel = state.reference.label,
                    onPickPhoto = { showPickPhotoSheet = true },
                    onChangeReference = { referenceChosen = false },
                    onClose = onClose,
                )
            }

            else -> {
                val imageBitmap = remember(state.photo) { state.photo!!.asImageBitmap() }
                val hasEverCalibrated = state.isCalibrated || state.isEditingQuad
                val awaitingQuadConfirm = state.quad.size == 4 && (!state.isCalibrated || state.isEditingQuad)
                val saveSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

                Column(modifier = Modifier.fillMaxSize()) {
                    PhotoTopNav(
                        onBack = { state.discardPhoto() },
                        canUndo = state.canUndo,
                        onUndo = state::undo,
                        canRedo = state.canRedo,
                        onRedo = state::redo,
                        showUndoRedoAndSave = hasEverCalibrated,
                        saveSupported = saveSupported,
                        saveEnabled = state.isCalibrated,
                        onSave = { requestSave() },
                    )

                    if (!hasEverCalibrated) {
                        InstructionBox(
                            text = if (state.quad.isEmpty()) {
                                stringResource(R.string.armeasure_photo_instruction_place, state.reference.label)
                            } else {
                                stringResource(R.string.armeasure_photo_instruction_adjust, state.reference.label)
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        PhotoQuadCanvas(
                            photo = imageBitmap,
                            state = state,
                            modifier = Modifier.fillMaxSize().onSizeChanged { canvasSize = it },
                            onLineDragEnd = { emitResult() },
                        )
                        if (awaitingQuadConfirm) {
                            CheckmarkBtn(
                                onClick = {
                                    state.confirmReference(canvasSize.width.toFloat(), canvasSize.height.toFloat())
                                    emitResult()
                                },
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
                            )
                        }
                    }

                    if (hasEverCalibrated) {
                        PhotoBottomToolbar(
                            onLineSegment = {
                                state.resetLine(canvasSize.width.toFloat(), canvasSize.height.toFloat())
                                emitResult()
                            },
                            onEditScale = state::beginEditQuad,
                        )
                        ColorPickerBar(
                            selected = state.lineColor,
                            onSelect = state::setLineColor,
                            unit = state.unit,
                            onSelectUnit = state::setUnit,
                        )
                    }
                }
            }
        }

        if (showPickPhotoSheet) {
            PickPhotoSheet(
                onPhotoPicked = { uri -> loadRotatedBitmap(context, uri)?.let(state::loadPhoto) },
                onDismiss = { showPickPhotoSheet = false },
            )
        }
        if (showReferenceSheet) {
            val target = editingReference
            ReferenceEditSheet(
                editing = target,
                unit = state.unit,
                onDismiss = { showReferenceSheet = false },
                onSubmit = { label, shortSideMm, longSideMm ->
                    if (target == null) {
                        val newReference = referenceStore.add(label, shortSideMm, longSideMm)
                        customReferences.add(newReference)
                        selectReference(newReference)
                    } else {
                        val updated = referenceStore.update(target.id, label, shortSideMm, longSideMm)
                        if (updated != null) {
                            val index = customReferences.indexOfFirst { it.id == target.id }
                            if (index >= 0) customReferences[index] = updated
                            if (state.reference.id == target.id) state.reference = updated
                        }
                    }
                },
                onDelete = target?.let {
                    {
                        if (referenceStore.delete(it.id)) customReferences.removeAll { r -> r.id == it.id }
                    }
                },
            )
        }
    }
}

@Composable
private fun WaitingForPhoto(
    referenceLabel: String,
    onPickPhoto: () -> Unit,
    onChangeReference: () -> Unit,
    onClose: (() -> Unit)?,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.armeasure_photo_waiting_title, referenceLabel),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.armeasure_photo_waiting_body, referenceLabel),
                color = Color(0xB3FFFFFF),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            )
            Button(onClick = onPickPhoto) { Text(stringResource(R.string.armeasure_photo_waiting_pick_cta)) }
            Text(
                stringResource(R.string.armeasure_photo_waiting_change_reference),
                color = Color(0xB3FFFFFF),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 16.dp).clickable(onClick = onChangeReference),
            )
        }
        if (onClose != null) {
            Text(
                "✕",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .clickable(onClick = onClose)
                    .padding(12.dp),
            )
        }
    }
}

/**
 * Renders the annotated export bitmap and hands it to [imageSaver], entirely off the main thread
 * — [renderAnnotatedBitmap] does real drawing work and [MeasurementImageSaver.save] does file IO,
 * neither of which belongs on the UI thread. The export bitmap is always recycled, save or fail;
 * it never touches [PhotoMeasureState]'s undo stack (that stack only ever holds pixel coordinates
 * and a colour, never a [Bitmap]).
 */
private suspend fun performSave(
    photo: Bitmap,
    line: LiveLine?,
    canvasSize: IntSize,
    label: String?,
    lineColor: Color,
    textMeasurer: TextMeasurer,
    density: Density,
    imageSaver: MeasurementImageSaver,
): Uri? = withContext(Dispatchers.Default) {
    val exported = renderAnnotatedBitmap(photo, line, canvasSize, label, lineColor, textMeasurer, density)
    try {
        imageSaver.save(exported, "armeasure_${System.currentTimeMillis()}.png")
    } finally {
        exported.recycle()
    }
}
