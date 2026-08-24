package vn.quancua.artapemeasure.photomeasure

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Chọn ảnh": take a new photo or pick an existing one — same two options ARuler offers before
 * registering a reference object (and reused here for picking the measurement photo too, since
 * there is no reason the two flows should differ).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickPhotoSheet(onPhotoPicked: (Uri) -> Unit, onDismiss: () -> Unit) {
    val takePhoto = rememberCameraCaptureLauncher(onPhotoTaken = { onPhotoPicked(it); onDismiss() })
    val pickFromGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? -> if (uri != null) { onPhotoPicked(uri); onDismiss() } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "Chọn ảnh",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SheetOption(label = "Chụp ảnh", onClick = takePhoto)
            SheetOption(
                label = "Chọn từ thư viện",
                onClick = {
                    pickFromGallery.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
        }
    }
}

@Composable
private fun SheetOption(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
    )
}
