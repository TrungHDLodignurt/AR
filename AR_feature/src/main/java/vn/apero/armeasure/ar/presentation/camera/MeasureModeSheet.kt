package vn.apero.armeasure.ar.presentation.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R
import vn.apero.armeasure.common.ui.ArMeasureTokens

/**
 * The AR tool picker — exactly the three v1 tools (Distance / Box / Cylinder). The original mock
 * (`ebVJf`) lists six more entries (Angle, Polyline, Polyline smooth, Square, Poly smooth,
 * Auto-Detection); those are deliberately **not** implemented per phase-06 decision 3. Do not
 * "restore" them, disabled or otherwise — the phase's success criteria grep for their names.
 *
 * Selection is signalled by fill AND stroke AND label colour, not colour alone (insight 3): the
 * mock's olive-stroke-only cue is a colour-blind failure the UI review flagged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeasureModeSheet(
    selected: MeasureTool,
    onSelect: (MeasureTool) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // skipPartiallyExpanded: this sheet's content never exceeds half the screen, so a
    // PartiallyExpanded resting state would just be a second no-op stop between Hidden and
    // Expanded — dropping it means a single swipe-down always dismisses instead of settling
    // there first.
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.armeasure_mode_sheet_title),
                    color = ArMeasureTokens.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                val closeLabel = stringResource(R.string.armeasure_action_close_sheet)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .semantics { contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "×", color = ArMeasureTokens.TextSecondary, fontSize = 22.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ModeCard(MeasureTool.Distance, "↔", selected, onSelect, Modifier.weight(1f))
                ModeCard(MeasureTool.Box, "□", selected, onSelect, Modifier.weight(1f))
                ModeCard(MeasureTool.Cylinder, "○", selected, onSelect, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModeCard(
    tool: MeasureTool,
    glyph: String,
    selected: MeasureTool,
    onSelect: (MeasureTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSelected = tool == selected
    val label = stringResource(modeLabelRes(tool))
    val iconColor = if (isSelected) ArMeasureTokens.Signature else ArMeasureTokens.TextPrimary
    val labelColor = if (isSelected) ArMeasureTokens.SignatureText else ArMeasureTokens.TextSecondary
    Column(
        modifier = modifier
            .height(70.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (isSelected) ArMeasureTokens.SignatureMuted else ArMeasureTokens.BgSurface)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) ArMeasureTokens.Signature else ArMeasureTokens.BorderSoft,
                shape = RoundedCornerShape(percent = 50),
            )
            .clickable(onClick = { onSelect(tool) })
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        // Design's card stack: icon above label with a 4dp gap, centred as a group.
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Text(text = glyph, fontSize = 22.sp, color = iconColor, modifier = Modifier.clearAndSetSemantics {})
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = labelColor,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

private fun modeLabelRes(tool: MeasureTool): Int = when (tool) {
    MeasureTool.Distance -> R.string.armeasure_mode_distance
    MeasureTool.Box -> R.string.armeasure_mode_box
    MeasureTool.Cylinder -> R.string.armeasure_mode_cylinder
}
