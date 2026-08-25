package vn.apero.armeasure.photo.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject

/**
 * "Chọn đối tượng tham chiếu" — ARuler's own first screen for this feature, and the reason it
 * has to come first rather than after picking a photo: the very next step asks the user to
 * photograph (or pick a photo of) the reference object together with whatever they want to
 * measure, so the app needs to know which real-world size it is calibrating against before that
 * photo even exists.
 *
 * No thumbnails: confirmed against the real "Đối tượng tham chiếu mới" dialog (name + length +
 * width, nothing else) that custom objects are never tied to a photo at all — every card, built-in
 * or custom, shows initials only.
 */
@Composable
internal fun ReferencePickerScreen(
    builtIns: List<ReferenceObject>,
    customs: List<ReferenceObject>,
    onSelect: (ReferenceObject) -> Unit,
    onAddNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        Text("Chọn đối tượng tham chiếu", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(
            "Chọn đối tượng tham chiếu mà bạn có thể chụp ảnh cùng với đối tượng cần đo.",
            color = Color(0xB3FFFFFF),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(builtIns + customs) { reference ->
                ReferenceCard(reference = reference, onClick = { onSelect(reference) })
            }
            item { AddReferenceCard(onClick = onAddNew) }
        }
    }
}

@Composable
private fun ReferenceCard(reference: ReferenceObject, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x26FFFFFF))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            reference.label.take(2).lowercase(),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(reference.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            "${(reference.shortSideMm / 10).roundToInt()} x ${(reference.longSideMm / 10).roundToInt()} cm",
            color = Color(0xB3FFFFFF),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun AddReferenceCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x14FFFFFF))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("+", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light)
        Text(
            "Thêm đối tượng mới",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
