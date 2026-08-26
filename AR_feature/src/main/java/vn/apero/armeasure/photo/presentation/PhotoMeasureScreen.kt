package vn.apero.armeasure.photo.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.common.data.UnitPreference
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.MeasurementResult
import vn.apero.armeasure.photo.data.CustomReferenceStore
import vn.apero.armeasure.photo.data.loadRotatedBitmap
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject
import vn.apero.armeasure.photo.domain.imaging.builtInReferenceObjects
import vn.apero.armeasure.photo.domain.imaging.customReferenceObject

/**
 * "Measure from a photo" — no ARCore, no camera-ar feature, no depth. A rectangle of known size
 * (a sheet of paper, a payment card, or a custom object the user registers themselves) lets any
 * two points on a still photo be measured, distorted-perspective photo included. See
 * `Homography.kt` for the maths this ports from ARuler's "Photoruler", and the port-feasibility
 * report in `plans/reports/` for why this feature specifically sidesteps every ARCore
 * device-support limitation this app otherwise has: it needs a camera app and a bitmap, nothing
 * ARCore-certification-gated.
 *
 * Flow order matches ARuler's own, not the more obvious "pick a photo, then say what's in it":
 * the reference object has to be chosen FIRST, because the next step is taking one photo that
 * shows it *together with* whatever is being measured — the app can't ask for that photo
 * without already knowing what to tell the user to include in it.
 *
 * @param referenceStore the host constructs and owns this — the module never creates or holds
 *   its own instance, so a host app controls exactly where/how the reference objects persist.
 * @param unit fallback display unit for the very first launch, before [UnitPreference] holds any
 *   value; the persisted, process-wide unit choice (shared with the AR tools) takes over from
 *   then on — see decision 8. This screen has no unit control of its own yet (that lands in
 *   phase 08's `ColorPickerBar` `cm` badge); it still reads/writes the shared preference so a
 *   unit picked on an AR tab is honoured here too.
 * @param onResult emitted once per completed measurement gesture (drag-end or first placement),
 *   never per drag frame.
 * @param onClose null renders no close affordance (today's chrome, unchanged); non-null renders
 *   a "✕" the host can wire to its own navigation.
 */
@Composable
fun PhotoMeasureScreen(
    referenceStore: CustomReferenceStore,
    modifier: Modifier = Modifier,
    unit: LengthUnit = LengthUnit.Cm,
    onResult: (MeasurementResult.Photo) -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val unitPreference = remember { UnitPreference(context) }
    // unitPreference.unit already falls back to LengthUnit.Cm on a first-ever launch — same
    // value as the unit param's own default, so the persisted store is the single seed.
    val state = remember { PhotoMeasureState(initialUnit = unitPreference.unit) }
    LaunchedEffect(state.unit) { unitPreference.unit = state.unit }
    val customReferences = remember { mutableStateListOf<ReferenceObject>().apply { addAll(referenceStore.loadAll()) } }

    var referenceChosen by remember { mutableStateOf(false) }
    var showPickPhotoSheet by remember { mutableStateOf(false) }
    var showAddReferenceFlow by remember { mutableStateOf(false) }
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

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF1C1C1E))) {
        when {
            !referenceChosen -> {
                ReferencePickerScreen(
                    builtIns = builtInReferenceObjects,
                    customs = customReferences,
                    onSelect = { selectReference(it) },
                    onAddNew = { showAddReferenceFlow = true },
                )
            }

            state.photo == null -> {
                WaitingForPhoto(
                    referenceLabel = state.reference.label,
                    onPickPhoto = { showPickPhotoSheet = true },
                    onChangeReference = { referenceChosen = false },
                )
            }

            else -> {
                val imageBitmap = remember(state.photo) { state.photo!!.asImageBitmap() }
                PhotoQuadCanvas(
                    photo = imageBitmap,
                    state = state,
                    modifier = Modifier.fillMaxSize().onSizeChanged { canvasSize = it },
                    onLineDragEnd = { emitResult() },
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    HintBanner(state = state)
                    Box(modifier = Modifier.weight(1f))
                    BottomPanel(
                        state = state,
                        canvasSize = canvasSize,
                        onPickAnotherPhoto = { showPickPhotoSheet = true },
                        onConfirmReference = {
                            state.confirmReference(canvasSize.width.toFloat(), canvasSize.height.toFloat())
                            emitResult()
                        },
                    )
                }
            }
        }

        if (showPickPhotoSheet) {
            PickPhotoSheet(
                onPhotoPicked = { uri -> loadRotatedBitmap(context, uri)?.let(state::loadPhoto) },
                onDismiss = { showPickPhotoSheet = false },
            )
        }
        if (showAddReferenceFlow) {
            NameReferenceDialog(
                onConfirm = { label, shortSideMm, longSideMm ->
                    // Normalises short/long ordering in case the two fields got swapped —
                    // see customReferenceObject()'s doc for why that matters.
                    val validated = customReferenceObject(label, shortSideMm, longSideMm) ?: return@NameReferenceDialog
                    val newReference = referenceStore.add(validated.label, validated.shortSideMm, validated.longSideMm)
                    customReferences.add(newReference)
                    showAddReferenceFlow = false
                    selectReference(newReference)
                },
                onCancel = { showAddReferenceFlow = false },
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
                    .clickable(onClick = onClose),
            )
        }
    }
}

@Composable
private fun WaitingForPhoto(referenceLabel: String, onPickPhoto: () -> Unit, onChangeReference: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Chụp ảnh có $referenceLabel", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(
            "Ảnh cần có cả $referenceLabel và vật cần đo cùng trong khung hình.",
            color = Color(0xB3FFFFFF),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Button(onClick = onPickPhoto) { Text("Chọn ảnh") }
        Text(
            "Đổi vật tham chiếu",
            color = Color(0xB3FFFFFF),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 16.dp).clickable(onClick = onChangeReference),
        )
    }
}

@Composable
private fun HintBanner(state: PhotoMeasureState) {
    if (state.isCalibrated) return
    val text = if (state.quad.isEmpty()) {
        "Nhấp vào ${state.reference.label} để đánh dấu nó và điều chỉnh kích thước"
    } else {
        "Giữ và kéo các góc để điều chỉnh vùng ${state.reference.label}"
    }
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x8C000000))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun BottomPanel(
    state: PhotoMeasureState,
    canvasSize: IntSize,
    onPickAnotherPhoto: () -> Unit,
    onConfirmReference: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xF21C1C1E)).padding(16.dp)) {
        if (!state.isCalibrated) {
            Button(
                onClick = onConfirmReference,
                enabled = state.quad.size == 4,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Xác nhận vật tham chiếu") }
        } else {
            Text("Kéo 2 đầu đoạn thẳng để đo khoảng cách thật", color = Color.White, fontSize = 13.sp)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Button(onClick = onPickAnotherPhoto) { Text("Chọn ảnh khác") }
                Button(
                    onClick = { state.resetLine(canvasSize.width.toFloat(), canvasSize.height.toFloat()) },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Đặt lại vị trí") }
            }
        }
    }
}
