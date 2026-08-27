package vn.apero.armeasure.photo.presentation

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.apero.armeasure.MeasurementImageSaver
import vn.apero.armeasure.R
import vn.apero.armeasure.common.data.UnitPreference
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.common.domain.formatLength
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.photo.data.CustomReferenceStore
import vn.apero.armeasure.photo.data.discardCameraCapture
import vn.apero.armeasure.photo.data.loadRotatedBitmap
import vn.apero.armeasure.photo.domain.imaging.builtInReferenceObjects
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.Effect
import vn.apero.armeasure.photo.presentation.PhotoMeasureContract.Intent
import vn.apero.armeasure.photo.presentation.components.CheckmarkBtn
import vn.apero.armeasure.photo.presentation.components.InstructionBox
import vn.apero.armeasure.photo.presentation.components.PhotoBottomToolbar
import vn.apero.armeasure.photo.presentation.components.PhotoQuadCanvas
import vn.apero.armeasure.photo.presentation.components.PhotoTopNav
import vn.apero.armeasure.photo.presentation.components.PickPhotoSheet
import vn.apero.armeasure.photo.presentation.components.ReferenceEditSheet
import vn.apero.armeasure.photo.presentation.components.renderAnnotatedBitmap

/**
 * "Measure from a photo" — no ARCore, no camera-ar feature, no depth. A rectangle of known size
 * (a sheet of paper, a payment card, or a custom object the user registers themselves) lets any
 * two points on a still photo be measured, distorted-perspective photo included. See
 * `Homography.kt` for the maths this ports from ARuler's "Photoruler".
 *
 * Built to SCR-21 (place the quad) / SCR-22 (adjust + confirm it) / SCR-23 (the segment list, which
 * also hosts "Chỉnh sửa tỉ lệ" back into the SCR-22 quad editor) / SCR-24 ([LineDrawScreen]).
 *
 * All state lives in [PhotoMeasureViewModel]; this composable renders it and posts [Intent]s. The
 * six `rememberSaveable` patches this screen used to carry are gone — what survives process death
 * is now one decision, [PhotoMeasureViewModel.persist]. The single exception, deliberately left
 * where it is, is the camera capture's `pendingUri` in `CameraCapture.kt`.
 *
 * @param referenceStore passed in rather than constructed here so this composable stays
 *   host-agnostic; in practice the only caller, [ArPhotoActivity], constructs and owns the one
 *   instance itself.
 * @param imageSaver where "Lưu" writes the finished annotated photo.
 * @param onResult emitted once per completed measurement gesture, never per drag frame.
 * @param onClose null renders no close affordance; non-null renders a "✕" the host can wire to its
 *   own navigation.
 */
@Composable
internal fun PhotoMeasureScreen(
    referenceStore: CustomReferenceStore,
    imageSaver: MeasurementImageSaver,
    modifier: Modifier = Modifier,
    onResult: (MeasurementResult.Photo) -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val coroutineScope = rememberCoroutineScope()
    // Resolved here rather than in the save callback: a getString() off LocalContext.current is not
    // invalidated by a Configuration change, so it can report a stale locale.
    val saveSuccessMessage = stringResource(R.string.armeasure_photo_save_success)
    val saveFailureMessage = stringResource(R.string.armeasure_photo_save_failure)
    val unitPreference = remember { UnitPreference(context) }

    // The factory is remembered so it is not rebuilt every recomposition; viewModel() only consults
    // it on first creation anyway. createSavedStateHandle() is what ties the four persisted fields
    // to the Activity's saved state — the ViewModel itself survives a configuration change, and the
    // handle is what carries it through process death on top of that.
    val viewModel: PhotoMeasureViewModel = viewModel(
        factory = remember(referenceStore) {
            viewModelFactory {
                initializer {
                    PhotoMeasureViewModel(
                        savedState = createSavedStateHandle(),
                        referenceStore = referenceStore,
                        // unitPreference.unit already falls back to DefaultUnit on a first-ever
                        // launch, so the persisted store is the single seed for the unit.
                        initialUnit = unitPreference.unit,
                        persistUnit = { unitPreference.save(it) },
                    )
                }
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val photo by viewModel.photo.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is Effect.MeasurementCompleted -> onResult(effect.result)
            }
        }
    }

    fun requestSave() {
        val bitmap = photo ?: return
        val labeledSegments = state.segments.map { segment ->
            segment to state.distanceMmFor(segment)?.let { formatLength(it / 1000f, state.unit) }
        }
        coroutineScope.launch {
            val uri = performSave(bitmap, labeledSegments, textMeasurer, density, imageSaver)
            Toast.makeText(context, if (uri != null) saveSuccessMessage else saveFailureMessage, Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val reference = state.reference
        when {
            !state.referenceChosen -> {
                ReferencePickerScreen(
                    builtIns = builtInReferenceObjects,
                    customs = state.customReferences,
                    unit = state.unit,
                    onSelect = { viewModel.processIntent(Intent.SelectReference(it)) },
                    onAddNew = { viewModel.processIntent(Intent.AddNewReferenceRequested) },
                    onEdit = { viewModel.processIntent(Intent.EditReferenceRequested(it)) },
                    onBack = { onClose?.invoke() },
                )
            }

            // Chosen but not resolvable yet — only reachable for a CUSTOM reference in the frame or
            // two before the store's async load lands. Rendering the background rather than falling
            // back to A4 is the point: the old fallback is exactly how a restored custom reference
            // came back as "A4 paper" and measured against the wrong object.
            reference == null -> Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1C1C1E)))

            photo == null -> {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1C1C1E))) {
                    WaitingForPhoto(
                        referenceLabel = reference.label,
                        onPickPhoto = { viewModel.processIntent(Intent.PickPhotoRequested) },
                        onChangeReference = { viewModel.processIntent(Intent.ChangeReference) },
                        onClose = onClose,
                    )
                }
            }

            state.isDrawingSegment -> {
                val imageBitmap = remember(photo) { photo!!.asImageBitmap() }
                LineDrawScreen(
                    photo = imageBitmap,
                    state = state,
                    onIntent = viewModel::processIntent,
                    onDraftEndpointDrag = viewModel::onDraftEndpointDrag,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                val imageBitmap = remember(photo) { photo!!.asImageBitmap() }
                val hasEverCalibrated = state.isCalibrated || state.isEditingQuad
                val awaitingQuadConfirm = state.quad.size == 4 && (!state.isCalibrated || state.isEditingQuad)
                val saveSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

                // BgPrimary, not a screen-wide 0xFF1C1C1E — SCR-21/22/23 all show the cream app
                // background around the aspect-fit photo, not a black letterbox.
                Column(modifier = Modifier.fillMaxSize().background(ArMeasureTokens.BgPrimary)) {
                    PhotoTopNav(
                        onBack = { viewModel.processIntent(Intent.DiscardPhoto) },
                        canUndo = state.canUndo,
                        onUndo = { viewModel.processIntent(Intent.Undo) },
                        canRedo = state.canRedo,
                        onRedo = { viewModel.processIntent(Intent.Redo) },
                        showUndoRedoAndSave = hasEverCalibrated,
                        saveSupported = saveSupported,
                        saveEnabled = state.isCalibrated,
                        onSave = { requestSave() },
                    )

                    if (!hasEverCalibrated) {
                        val placeText = stringResource(R.string.armeasure_photo_instruction_place, reference.label)
                        val adjustText = stringResource(R.string.armeasure_photo_instruction_adjust, reference.label)
                        val showPlace = state.quad.isEmpty()
                        // The unused wording is handed over as the sizing ghost so this box always
                        // measures to the taller of the two: the swap the moment a quad appears must
                        // not change this box's height, or the weighted photo box below it resizes
                        // and the photo visibly jumps/shrinks.
                        InstructionBox(
                            text = if (showPlace) placeText else adjustText,
                            sizingText = if (showPlace) adjustText else placeText,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }

                    // The weighted Box takes exactly the space left between the top nav and the
                    // bottom slot/toolbar; PhotoQuadCanvas's own aspect-fit always centres the photo
                    // within whatever box it is given.
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        PhotoQuadCanvas(
                            photo = imageBitmap,
                            state = state,
                            onIntent = viewModel::processIntent,
                            onCornerDrag = viewModel::onCornerDrag,
                            onCornerDragEnded = viewModel::onCornerDragEnded,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (awaitingQuadConfirm && hasEverCalibrated) {
                            // Re-editing the quad from SCR-23: the confirm affordance stays an
                            // overlay here, so SCR-23/24's already-verified layout is untouched.
                            CheckmarkBtn(
                                onClick = { viewModel.processIntent(Intent.ConfirmReference) },
                                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp),
                            )
                        }
                    }

                    if (!hasEverCalibrated) {
                        // SCR-21 (no button yet) and SCR-22 (button confirmed) reserve this exact
                        // same height regardless of awaitingQuadConfirm, so the weighted photo box
                        // above never resizes and the photo does not jump.
                        Box(
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 32.dp).height(100.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (awaitingQuadConfirm) {
                                CheckmarkBtn(onClick = { viewModel.processIntent(Intent.ConfirmReference) })
                            }
                        }
                    }

                    if (hasEverCalibrated) {
                        PhotoBottomToolbar(
                            onLineSegment = { viewModel.processIntent(Intent.BeginDrawSegment) },
                            onEditScale = { viewModel.processIntent(Intent.EditQuadRequested) },
                        )
                    }
                }
            }
        }

        if (state.showPickPhotoSheet) {
            PickPhotoSheet(
                // Launched, not called inline: decoding is suspending precisely because it is
                // several megapixels of work on a content Uri. It stays at this edge rather than
                // moving into the ViewModel so the ViewModel needs no Context — the decoded pixels
                // arrive as Intent.PhotoPicked.
                onPhotoPicked = { uri ->
                    coroutineScope.launch {
                        loadRotatedBitmap(context, uri)?.let { viewModel.processIntent(Intent.PhotoPicked(it)) }
                        // The pixels are in memory now, so a camera capture's temp JPEG has done its
                        // job. No-op for a gallery Uri — see discardCameraCapture.
                        discardCameraCapture(context, uri)
                    }
                },
                onDismiss = { viewModel.processIntent(Intent.PickPhotoSheetDismissed) },
            )
        }
        if (state.showReferenceSheet) {
            val editing = state.editingReference
            ReferenceEditSheet(
                editing = editing,
                unit = state.unit,
                onDismiss = { viewModel.processIntent(Intent.ReferenceSheetDismissed) },
                onSubmit = { label, shortSideMm, longSideMm ->
                    viewModel.processIntent(Intent.SubmitReference(label, shortSideMm, longSideMm))
                },
                onDelete = editing?.let { { viewModel.processIntent(Intent.DeleteEditedReference) } },
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
 * neither of which belongs on the UI thread. The export bitmap is always recycled, save or fail.
 *
 * Not an [Intent]: it mutates no state, and it needs a `TextMeasurer` and a `Density`, both of which
 * only exist inside composition.
 */
private suspend fun performSave(
    photo: Bitmap,
    segments: List<Pair<Segment, String?>>,
    textMeasurer: TextMeasurer,
    density: Density,
    imageSaver: MeasurementImageSaver,
): Uri? = withContext(Dispatchers.Default) {
    val exported = renderAnnotatedBitmap(photo, segments, textMeasurer, density)
    try {
        imageSaver.save(exported, "armeasure_${System.currentTimeMillis()}.png")
    } finally {
        exported.recycle()
    }
}
