package vn.apero.armeasure.photo.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.domain.formatLength
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject

/** Built-in card: an icon area plus name/dimensions — never editable or deletable. */
@Composable
internal fun PresetCard(reference: ReferenceObject, unit: LengthUnit, onClick: () -> Unit) {
    CardShell(onClick = onClick) {
        Text(text = "▭", color = ArMeasureTokens.Signature, fontSize = 30.sp)
        ReferenceTexts(reference = reference, unit = unit)
    }
}

/** Custom card: an avatar of the first two letters plus name/dimensions, with a 48dp edit target laid on top so tapping it never falls through to the card's own select click. */
@Composable
internal fun CustomCard(reference: ReferenceObject, unit: LengthUnit, onClick: () -> Unit, onEdit: () -> Unit) {
    Box {
        CardShell(onClick = onClick, contentPadding = 14.dp) {
            Box(
                modifier = Modifier.size(66.dp).clip(CircleShape).background(ArMeasureTokens.SignatureMuted),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = reference.label.take(2).uppercase(),
                    color = ArMeasureTokens.SignatureText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            ReferenceTexts(reference = reference, unit = unit)
        }
        val editLabel = stringResource(R.string.armeasure_reference_edit_cd, reference.label)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clickable(onClick = onEdit)
                .semantics { contentDescription = editLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "✎", color = ArMeasureTokens.TextSecondary, fontSize = 16.sp)
        }
    }
}

@Composable
internal fun AddReferenceCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .height(176.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, ArMeasureTokens.BorderStrong, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(58.dp).clip(CircleShape).background(ArMeasureTokens.SignatureMuted),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "+", color = ArMeasureTokens.Signature, fontSize = 28.sp)
        }
        Text(
            text = stringResource(R.string.armeasure_reference_add_card_label),
            color = ArMeasureTokens.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun CardShell(onClick: () -> Unit, contentPadding: Dp = 16.dp, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .height(176.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ArMeasureTokens.BgSurface)
            .border(1.dp, ArMeasureTokens.BorderSoft, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(contentPadding),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        content()
    }
}

@Composable
private fun ReferenceTexts(reference: ReferenceObject, unit: LengthUnit) {
    Column {
        Text(text = reference.label, color = ArMeasureTokens.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "${formatLength(reference.shortSideMm / 1000f, unit)} × ${formatLength(reference.longSideMm / 1000f, unit)}",
            color = ArMeasureTokens.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
