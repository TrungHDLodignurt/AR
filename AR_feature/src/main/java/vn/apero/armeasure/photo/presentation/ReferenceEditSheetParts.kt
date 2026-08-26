package vn.apero.armeasure.photo.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.common.ui.UnitMenu

/** The title/close row shared by the add and edit states — see [ReferenceEditSheet]'s KDoc for why there is only one sheet. */
@Composable
internal fun SheetHeader(isEditing: Boolean, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(if (isEditing) R.string.armeasure_reference_sheet_title_edit else R.string.armeasure_reference_sheet_title_add),
            color = ArMeasureTokens.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        val closeLabel = stringResource(R.string.armeasure_action_close_sheet)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .semantics { contentDescription = closeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "×", color = ArMeasureTokens.TextSecondary, fontSize = 22.sp)
        }
    }
}

@Composable
internal fun DimensionField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            color = ArMeasureTokens.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        SheetTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "0",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Custom-drawn to hit the design's exact 48dp/`BgSecondary`/r10 field, rather than Material's `OutlinedTextField` box model. */
@Composable
internal fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ArMeasureTokens.BgSecondary)
            .border(1.dp, ArMeasureTokens.BorderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(text = placeholder, color = ArMeasureTokens.TextSecondary, fontSize = 15.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            textStyle = TextStyle(color = ArMeasureTokens.TextPrimary, fontSize = 15.sp),
            cursorBrush = SolidColor(ArMeasureTokens.TextPrimary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 67×48 `symbol` + chevron chip; the trigger and [UnitMenu] share a `Box` so the popup anchors to the chip itself, same pattern as `ModeUnitStack`. */
@Composable
internal fun UnitChip(
    unit: LengthUnit,
    showMenu: Boolean,
    onClick: () -> Unit,
    onSelect: (LengthUnit) -> Unit,
    onDismissMenu: () -> Unit,
) {
    val label = stringResource(R.string.armeasure_reference_unit_selector_cd)
    Box {
        Box(
            modifier = Modifier
                .size(width = 67.dp, height = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ArMeasureTokens.BgSecondary)
                .clickable(onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = unit.symbol, color = ArMeasureTokens.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(text = "⌄", color = ArMeasureTokens.TextPrimary, fontSize = 14.sp)
            }
        }
        if (showMenu) {
            UnitMenu(selected = unit, onSelect = onSelect, onDismiss = onDismissMenu)
        }
    }
}

@Composable
internal fun DeleteRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "🗑", color = ArMeasureTokens.Error, fontSize = 16.sp)
        Text(
            text = stringResource(R.string.armeasure_reference_delete),
            color = ArMeasureTokens.Error,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun SubmitButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) ArMeasureTokens.SignatureText else ArMeasureTokens.SignatureText.copy(alpha = 0.4f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = ArMeasureTokens.OnSignature, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun DeleteConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.armeasure_reference_delete_confirm_title)) },
        text = { Text(stringResource(R.string.armeasure_reference_delete_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.armeasure_reference_delete_confirm_action), color = ArMeasureTokens.Error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.armeasure_action_cancel)) }
        },
    )
}
