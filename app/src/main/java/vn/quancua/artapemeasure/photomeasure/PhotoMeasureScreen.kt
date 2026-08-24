package vn.quancua.artapemeasure.photomeasure

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.quancua.artapemeasure.ui.MeasureTopBar

/**
 * "Measure from a photo" — no ARCore, no camera-ar feature, no depth. A static photo plus a
 * rectangle of known size (a sheet of paper, a payment card) lets any two points on that same
 * plane be measured, distorted-perspective photo included. See `Homography.kt` for the maths
 * this ports from ARuler's "Photoruler", and the port-feasibility report in `plans/reports/`
 * for why this feature specifically sidesteps every ARCore device-support limitation this app
 * otherwise has: it needs a camera app and a bitmap, nothing ARCore-certification-gated.
 */
@Composable
fun PhotoMeasureScreen(modifier: Modifier = Modifier) {
    val state = remember { PhotoMeasureState() }
    val context = LocalContext.current

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        loadRotatedBitmap(context, uri)?.let { state.loadPhoto(it) }
    }
    val launchPicker = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF1C1C1E))) {
        val photo = state.photo
        if (photo == null) {
            EmptyState(onPickPhoto = launchPicker)
        } else {
            val imageBitmap = remember(photo) { photo.asImageBitmap() }
            PhotoQuadCanvas(photo = imageBitmap, state = state, modifier = Modifier.fillMaxSize())

            Column(modifier = Modifier.fillMaxSize()) {
                if (state.isCalibrated) {
                    MeasureTopBar(canUndo = state.canUndo, onUndo = state::undo, onClear = state::clear)
                }
                Box(modifier = Modifier.weight(1f))
                BottomPanel(state = state, onPickAnotherPhoto = launchPicker)
            }
        }
    }
}

@Composable
private fun BottomPanel(state: PhotoMeasureState, onPickAnotherPhoto: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xF21C1C1E)).padding(16.dp)) {
        if (!state.isCalibrated) {
            Text(
                "Kéo 4 góc để khớp với ${state.reference.label} trong ảnh — cạnh xanh là cạnh dài, cạnh vàng là cạnh ngắn.",
                color = Color.White,
                fontSize = 13.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                builtInReferenceObjects.forEach { reference ->
                    ReferenceChip(
                        reference = reference,
                        selected = reference == state.reference,
                        onClick = { state.reference = reference },
                    )
                }
            }
            Button(onClick = state::confirmReference, modifier = Modifier.fillMaxWidth()) {
                Text("Xác nhận vật tham chiếu")
            }
        } else {
            Text(
                if (state.pendingStart == null) {
                    "Chạm 2 điểm trên ảnh để đo khoảng cách thật"
                } else {
                    "Chạm điểm thứ 2 để hoàn tất đường đo"
                },
                color = Color.White,
                fontSize = 13.sp,
            )
            Button(onClick = onPickAnotherPhoto, modifier = Modifier.padding(top = 10.dp)) {
                Text("Chọn ảnh khác")
            }
        }
    }
}

@Composable
private fun ReferenceChip(reference: ReferenceObject, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White else Color(0x33FFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            reference.label,
            color = if (selected) Color(0xFF1C1C1E) else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EmptyState(onPickPhoto: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Đo qua ảnh", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(
            "Chọn ảnh có vật tham chiếu (tờ A4, thẻ ngân hàng...) để đo khoảng cách thật trên ảnh — " +
                "không cần AR, dùng được trên mọi máy.",
            color = Color(0xB3FFFFFF),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
        Button(onClick = onPickPhoto) { Text("Chọn ảnh") }
    }
}
