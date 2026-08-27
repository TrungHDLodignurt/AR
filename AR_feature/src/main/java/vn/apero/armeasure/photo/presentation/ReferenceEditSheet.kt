package vn.apero.armeasure.photo.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import vn.apero.armeasure.R
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject
import vn.apero.armeasure.photo.domain.imaging.customReferenceObject

/**
 * The one sheet for both "add" and "edit" (design `YBV5s`/`fgWMc`) — the live document has two
 * divergent copies (the edit sheet is an unparented orphan, 14px wider, missing the close `×`
 * the add sheet has, and only it carries the delete row). Reproducing that divergence would be a
 * DRY violation baked into the UI, so [editing] switches title/CTA/delete-row instead.
 *
 * [unit] seeds the length/width fields' unit selector with the app's current shared unit choice
 * (decision 8's "hard user choice", the same one `UnitMenu` edits everywhere else); the selector
 * only changes how *this sheet's* two numbers are interpreted, not the global preference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReferenceEditSheet(
    editing: ReferenceObject?,
    unit: LengthUnit,
    onDismiss: () -> Unit,
    onSubmit: (label: String, shortSideMm: Float, longSideMm: Float) -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var name by remember(editing) { mutableStateOf(editing?.label ?: "") }
    var fieldUnit by remember(editing) { mutableStateOf(unit) }
    var lengthText by remember(editing) { mutableStateOf(editing?.let { plainNumber(it.longSideMm / mmPerUnit(unit)) } ?: "") }
    var widthText by remember(editing) { mutableStateOf(editing?.let { plainNumber(it.shortSideMm / mmPerUnit(unit)) } ?: "") }
    var showUnitMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val lengthValue = lengthText.toFloatOrNull()
    val widthValue = widthText.toFloatOrNull()
    val canSubmit = name.isNotBlank() && lengthValue != null && lengthValue > 0f && widthValue != null && widthValue > 0f

    fun submit() {
        val length = lengthValue ?: return
        val width = widthValue ?: return
        val validated = customReferenceObject(name, width * mmPerUnit(fieldUnit), length * mmPerUnit(fieldUnit)) ?: return
        onSubmit(validated.label, validated.shortSideMm, validated.longSideMm)
        onDismiss()
    }

    // skipPartiallyExpanded: the form's content is short and fixed-height (no scrolling list),
    // so — same as MeasureModeSheet — a PartiallyExpanded resting stop would just be an extra
    // no-op state between Hidden and Expanded.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = ArMeasureTokens.BgSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 16.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ArMeasureTokens.BorderSoft),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader(isEditing = editing != null, onClose = onDismiss)

            Column {
                Text(
                    text = stringResource(R.string.armeasure_reference_name_label),
                    color = ArMeasureTokens.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                SheetTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.armeasure_reference_name_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                // Design `DimRow` is `alignItems: end` — the Unit chip is shorter than a
                // label+field column, so its bottom edge (not its top) lines up with the two
                // fields' bottoms.
                verticalAlignment = Alignment.Bottom,
            ) {
                DimensionField(
                    label = stringResource(R.string.armeasure_reference_length_label),
                    value = lengthText,
                    onValueChange = { lengthText = it },
                    modifier = Modifier.weight(1f),
                )
                DimensionField(
                    label = stringResource(R.string.armeasure_reference_width_label),
                    value = widthText,
                    onValueChange = { widthText = it },
                    modifier = Modifier.weight(1f),
                )
                UnitChip(
                    unit = fieldUnit,
                    showMenu = showUnitMenu,
                    onClick = { showUnitMenu = true },
                    onSelect = { fieldUnit = it; showUnitMenu = false },
                    onDismissMenu = { showUnitMenu = false },
                )
            }

            if (editing != null && onDelete != null) {
                DeleteRow(onClick = { showDeleteConfirm = true })
            }

            SubmitButton(
                label = stringResource(if (editing == null) R.string.armeasure_reference_cta_add else R.string.armeasure_reference_cta_save),
                enabled = canSubmit,
                onClick = ::submit,
            )
        }
    }

    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            onConfirm = { showDeleteConfirm = false; onDelete?.invoke(); onDismiss() },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

private fun mmPerUnit(unit: LengthUnit): Float = unit.metersPerUnit * 1000f

/** Renders a rounded-to-2-decimal float without a trailing ".0"/".00" — for prefilling an editable field, never for a read-only label (`formatLength` covers that). */
private fun plainNumber(value: Float): String {
    val rounded = (value * 100).roundToInt() / 100f
    return if (rounded == rounded.toLong().toFloat()) rounded.toLong().toString() else rounded.toString()
}
