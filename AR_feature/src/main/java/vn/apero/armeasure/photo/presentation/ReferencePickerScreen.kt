package vn.apero.armeasure.photo.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject

/**
 * "Choose a reference object" — ARuler's own first screen for this feature, and the reason it
 * has to come first rather than after picking a photo: the very next step asks the user to
 * photograph (or pick a photo of) the reference object together with whatever they want to
 * measure, so the app needs to know which real-world size it is calibrating against before that
 * photo even exists. Built to SCR-15.
 *
 * No thumbnails: confirmed against the real "Đối tượng tham chiếu mới" dialog (name + length +
 * width, nothing else) that custom objects are never tied to a photo at all — every card, built-in
 * or custom, shows initials only.
 */
@Composable
internal fun ReferencePickerScreen(
    builtIns: List<ReferenceObject>,
    customs: List<ReferenceObject>,
    unit: LengthUnit,
    onSelect: (ReferenceObject) -> Unit,
    onAddNew: () -> Unit,
    onEdit: (ReferenceObject) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(ArMeasureTokens.BgPrimary)) {
        TopNav(onBack = onBack)
        Text(
            text = stringResource(R.string.armeasure_reference_subtitle),
            color = ArMeasureTokens.TextSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 22.sp,
            modifier = Modifier.padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 18.dp),
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f).navigationBarsPadding(),
        ) {
            items(builtIns) { reference -> PresetCard(reference = reference, unit = unit, onClick = { onSelect(reference) }) }
            items(customs) { reference ->
                CustomCard(
                    reference = reference,
                    unit = unit,
                    onClick = { onSelect(reference) },
                    onEdit = { onEdit(reference) },
                )
            }
            item { AddReferenceCard(onClick = onAddNew) }
        }
    }
}

@Composable
private fun TopNav(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val backLabel = stringResource(R.string.armeasure_action_back)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .semantics { contentDescription = backLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "←", color = ArMeasureTokens.TextPrimary, fontSize = 22.sp)
        }
        Text(
            text = stringResource(R.string.armeasure_reference_title),
            color = ArMeasureTokens.TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
