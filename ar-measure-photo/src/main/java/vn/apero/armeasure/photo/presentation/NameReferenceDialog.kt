package vn.apero.armeasure.photo.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Đối tượng tham chiếu mới" — matches ARuler's real dialog exactly: name, length, width (both
 * in cm, side by side), an "Add" button. Nothing else — no photo, no thumbnail. Confirmed on
 * screen, not guessed from decompile: earlier versions of this port assumed a whole
 * photograph-the-object registration step existed here; it does not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NameReferenceDialog(
    onConfirm: (label: String, shortSideMm: Float, longSideMm: Float) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var lengthCm by remember { mutableStateOf("") }
    var widthCm by remember { mutableStateOf("") }

    val lengthValue = lengthCm.toFloatOrNull()
    val widthValue = widthCm.toFloatOrNull()
    val canAdd = name.isNotBlank() && lengthValue != null && lengthValue > 0f && widthValue != null && widthValue > 0f

    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "Đối tượng tham chiếu mới",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text("Tên", fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Chiều dài", fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                    OutlinedTextField(
                        value = lengthCm,
                        onValueChange = { lengthCm = it },
                        placeholder = { Text("cm") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Chiều rộng", fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                    OutlinedTextField(
                        value = widthCm,
                        onValueChange = { widthCm = it },
                        placeholder = { Text("cm") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Button(
                onClick = { onConfirm(name, widthValue!! * 10f, lengthValue!! * 10f) },
                enabled = canAdd,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
            ) { Text("Thêm") }
        }
    }
}
