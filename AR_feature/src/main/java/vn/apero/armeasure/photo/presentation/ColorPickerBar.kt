package vn.apero.armeasure.photo.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.common.ui.UnitMenu

/**
 * The photo measuring-line palette (design `M2m5jZ`). The first entry is `#D42D2D`, darkened from
 * the design's raw `#EB3232` — at the original hex neither white nor [ArMeasureTokens.TextPrimary]
 * label text clears WCAG AA against it (best case ~4.17:1; see `LabelContrastTest`), and per this
 * phase's own success criteria "if a palette colour cannot clear it, the palette changes, not the
 * test". The other four clear 4.5:1 unchanged. `PhotoMeasureContract.State` seeds its default line colour
 * from [first], matching the design's own selected dot.
 */
internal val PhotoLineColors = listOf(
    Color(0xFFD42D2D),
    Color(0xFFFF7700),
    Color(0xFFFFC700),
    Color(0xFF32D74B),
    Color(0xFF6057FF),
)

private val PhotoLineColorNameRes: List<Int> = listOf(
    R.string.armeasure_color_red,
    R.string.armeasure_color_orange,
    R.string.armeasure_color_yellow,
    R.string.armeasure_color_green,
    R.string.armeasure_color_purple,
)

private val CmBadgeFill = Color(0xFF4749A0)

/**
 * `ColorPickerBar`: 5 line-colour dots plus the `cm` unit badge. Photo-flow only (decision 10) —
 * the calibration [QuadEditorCanvas] never gets one, since its cyan/yellow are semantic (long
 * side/short side), not a user choice a colour picker could safely override.
 *
 * The `cm` badge is this flow's unit-menu entry point (decision), opening the exact same
 * [UnitMenu] the AR tools' `UnitBtn` opens — one menu, two triggers.
 */
@Composable
internal fun ColorPickerBar(
    selected: Color,
    onSelect: (Color) -> Unit,
    unit: LengthUnit,
    onSelectUnit: (LengthUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ArMeasureTokens.BgPrimary)
            // This is the screen's bottom-most element (edge-to-edge, no bar below it), so its
            // own touch targets otherwise land inside the system's mandatory-gesture inset —
            // verified on-device: taps at the very bottom of a 96dp bar with no inset padding
            // were silently swallowed by the nav-gesture area, not this composable's onClick.
            .navigationBarsPadding()
            .height(96.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoLineColors.forEachIndexed { index, color ->
            ColorDot(
                color = color,
                isSelected = color == selected,
                contentDescription = stringResource(PhotoLineColorNameRes[index]),
                onClick = { onSelect(color) },
            )
        }
        CmUnitBadge(unit = unit, onSelectUnit = onSelectUnit)
    }
}

/**
 * 20dp fill in a 48dp touch target; selected = a [ArMeasureTokens.TextPrimary] ring plus a size
 * bump to 24dp, replacing the design's own selection mark — a 3px *white* stroke on this bar's
 * near-white `#F4F4F2` background, which insight 7 flagged as very nearly invisible.
 */
@Composable
private fun ColorDot(color: Color, isSelected: Boolean, contentDescription: String, onClick: () -> Unit) {
    val dotSize = if (isSelected) 24.dp else 20.dp
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(color)
                .then(if (isSelected) Modifier.border(2.dp, ArMeasureTokens.TextPrimary, CircleShape) else Modifier),
        )
    }
}

/** 48dp touch target around the design's 40x36 pill — grown to the accessibility minimum without changing the drawn size. `#4749A0` with white text clears WCAG AA comfortably (~7.7:1). */
@Composable
private fun CmUnitBadge(unit: LengthUnit, onSelectUnit: (LengthUnit) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val label = stringResource(R.string.armeasure_unit_menu_title)
    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = { showMenu = true })
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(CmBadgeFill),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = unit.symbol, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Normal)
            }
        }
        if (showMenu) {
            UnitMenu(
                selected = unit,
                onSelect = onSelectUnit,
                onDismiss = { showMenu = false },
            )
        }
    }
}
