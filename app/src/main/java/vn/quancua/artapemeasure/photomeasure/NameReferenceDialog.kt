package vn.quancua.artapemeasure.photomeasure

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * "Đối tượng tham chiếu mới": name plus the two real-world side lengths, in cm to match how
 * ARuler labels its own built-in presets ("giấy A4 — 21 x 30 cm"). Converted to mm internally —
 * see [ReferenceObject], which stores everything in mm for consistency with the built-ins.
 */
@Composable
fun NameReferenceDialog(
    onConfirm: (label: String, shortSideMm: Float, longSideMm: Float) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var shortCm by remember { mutableStateOf("") }
    var longCm by remember { mutableStateOf("") }

    val shortValue = shortCm.toFloatOrNull()
    val longValue = longCm.toFloatOrNull()
    val canConfirm = name.isNotBlank() && shortValue != null && shortValue > 0f && longValue != null && longValue > 0f

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Đối tượng tham chiếu mới") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tên") },
                    singleLine = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = longCm,
                    onValueChange = { longCm = it },
                    label = { Text("Cạnh dài (cm)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = shortCm,
                    onValueChange = { shortCm = it },
                    label = { Text("Cạnh ngắn (cm)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(name, shortValue!! * 10f, longValue!! * 10f) },
            ) { Text("Tiếp tục") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Huỷ") } },
    )
}
