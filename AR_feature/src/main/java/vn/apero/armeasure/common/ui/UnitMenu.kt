package vn.apero.armeasure.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import vn.apero.armeasure.R
import vn.apero.armeasure.common.domain.LengthUnit

private const val MinTouchTargetDp = 48
private val MenuShadowColor = Color(0x33000000)
private val MenuBorderColor = Color(0x22111111)

/**
 * Compact trigger showing the currently selected unit's symbol — "cm", "m", "in" or "ft" — as a
 * [ChromeLightButton] pill, same treatment as Back/Mode in [ArMeasureTokens]. Note the asymmetry
 * with [UnitMenu]'s rows, which spell the unit out ("Inches"): the button stays terse on purpose
 * (locked decision), the menu does not need to.
 */
@Composable
internal fun UnitBtn(unit: LengthUnit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChromeLightButton(drawnSize = 40.dp, onClick = onClick, modifier = modifier) {
        Text(text = unit.symbol, color = ArMeasureTokens.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A 4-row popup letting the user pick [LengthUnit.Cm]/[LengthUnit.M]/[LengthUnit.Inch]/
 * [LengthUnit.Ft]. A [Popup], not a bottom sheet — the design floats it near its trigger (`XCFlV`).
 *
 * Each row is at least [MinTouchTargetDp]dp tall, and the selected row is marked by a fill AND a
 * check glyph AND an accessible label colour — colour alone would be the colour-blind failure the
 * UI review flagged for [MeasureModeSheet]'s cards too.
 *
 * [openUpward] flips the anchor corner from `TopEnd` to `BottomEnd`: [Popup]'s alignment pins the
 * same corner of the popup to that corner of its anchor, so `TopEnd` grows the menu downward from
 * the anchor's top edge while `BottomEnd` grows it upward from the anchor's bottom edge. The
 * reference-object sheet's unit chip sits at the bottom of a bottom sheet, where a
 * downward-opening menu would be clipped or offscreen.
 */
@Composable
internal fun UnitMenu(
    selected: LengthUnit,
    onSelect: (LengthUnit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    openUpward: Boolean = false,
) {
    val menuTitle = stringResource(R.string.armeasure_unit_menu_title)
    Popup(
        alignment = if (openUpward) Alignment.BottomEnd else Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = modifier
                .semantics { contentDescription = menuTitle }
                .width(180.dp)
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(12.dp), ambientColor = MenuShadowColor, spotColor = MenuShadowColor)
                .clip(RoundedCornerShape(12.dp))
                .background(ArMeasureTokens.BgSurface)
                .border(1.dp, MenuBorderColor, RoundedCornerShape(12.dp)),
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
            .background(if (isSelected) ArMeasureTokens.SignatureMuted else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes(unit)),
            color = if (isSelected) ArMeasureTokens.SignatureText else ArMeasureTokens.TextPrimary,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Text(text = "✓", color = ArMeasureTokens.SignatureText, fontSize = 20.sp)
        }
    }
}

private fun labelRes(unit: LengthUnit): Int = when (unit) {
    LengthUnit.Cm -> R.string.armeasure_unit_cm
    LengthUnit.M -> R.string.armeasure_unit_m
    LengthUnit.Inch -> R.string.armeasure_unit_inch
    LengthUnit.Ft -> R.string.armeasure_unit_ft
}
