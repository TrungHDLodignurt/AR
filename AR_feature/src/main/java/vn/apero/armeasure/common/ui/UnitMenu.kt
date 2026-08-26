package vn.apero.armeasure.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import vn.apero.armeasure.R
import vn.apero.armeasure.ar.presentation.shared.ChromePill
import vn.apero.armeasure.common.domain.LengthUnit

private val MenuBackground = Color(0xFF2C2C2E)
private val MenuRowSelectedFill = Color(0xFF48484A)
private const val MinTouchTargetDp = 48

/**
 * Compact trigger showing the currently selected unit's symbol — "cm", "m", "in" or "ft" — in
 * the same chrome-pill chip style as the rest of the AR chrome. Note the asymmetry with
 * [UnitMenu]'s rows, which spell the unit out ("Inches"): the button stays terse on purpose, the
 * menu does not need to.
 */
@Composable
internal fun UnitBtn(unit: LengthUnit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        ChromePill(enabled = true, onClick = onClick) {
            Text(text = unit.symbol, color = Color.White, fontSize = 14.sp)
        }
    }
}

/**
 * A 4-row popup letting the user pick [LengthUnit.Cm]/[LengthUnit.M]/[LengthUnit.Inch]/
 * [LengthUnit.Ft]. A [Popup], not a bottom sheet — the design floats it near its trigger.
 *
 * Each row is at least [MinTouchTargetDp]dp tall (the design's own touch-target size), and the
 * selected row is marked by both a check glyph AND a background fill — colour alone would be a
 * colour-blind failure the UI review specifically flagged.
 */
@Composable
internal fun UnitMenu(
    selected: LengthUnit,
    onSelect: (LengthUnit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val menuTitle = stringResource(R.string.armeasure_unit_menu_title)
    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = modifier
                .semantics { contentDescription = menuTitle }
                .width(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MenuBackground),
        ) {
            LengthUnit.entries.forEach { unit ->
                UnitMenuRow(
                    unit = unit,
                    isSelected = unit == selected,
                    onClick = {
                        onSelect(unit)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun UnitMenuRow(unit: LengthUnit, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTargetDp.dp)
            .background(if (isSelected) MenuRowSelectedFill else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes(unit)),
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Text(text = "✓", color = Color.White, fontSize = 15.sp)
        }
    }
}

private fun labelRes(unit: LengthUnit): Int = when (unit) {
    LengthUnit.Cm -> R.string.armeasure_unit_cm
    LengthUnit.M -> R.string.armeasure_unit_m
    LengthUnit.Inch -> R.string.armeasure_unit_inch
    LengthUnit.Ft -> R.string.armeasure_unit_ft
}
