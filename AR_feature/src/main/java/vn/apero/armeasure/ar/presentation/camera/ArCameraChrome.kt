package vn.apero.armeasure.ar.presentation.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.common.ui.ChromeLightButton
import vn.apero.armeasure.common.ui.UnitBtn
import vn.apero.armeasure.common.ui.UnitMenu
import vn.apero.armeasure.common.ui.chromeBlur

/**
 * Top bar, bottom bar and coaching toast for [ArCameraScreen], laid out per the design-update
 * appended to phase-06's plan file (2026-08-26 14:06) — it supersedes the phase file body's
 * original button-arrangement reasoning:
 *
 * - `ModeBtn` moved out of the bottom bar into the top bar, stacked above `UnitBtn`
 *   (`ModeUnitStack`), so the bottom bar carries only [CaptureBtn].
 * - The live document has no slot for undo/redo or Clear anywhere (neither did the original mock
 *   body this supersedes) — decision 11 requires both regardless, so they fill the top bar's
 *   remaining centre (undo/redo) and the bottom bar's freed left side (Clear).
 *
 * Every tappable element here has a ≥48dp touch target per the locked accessibility decision,
 * even where the drawn pill is smaller (Back/Unit 40dp, Mode 44dp) — grow the click surface only.
 */
@Composable
internal fun ArCameraTopBar(
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    onClose: (() -> Unit)?,
    unit: LengthUnit,
    onSelectUnit: (LengthUnit) -> Unit,
    showUnitMenu: Boolean,
    onUnitClick: () -> Unit,
    onDismissUnitMenu: () -> Unit,
    onModeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        // Top-aligned, not centred: the design pins BackBtn and ModeUnitStack to the same y (both
        // at 12 in the mock's TopBar). Centring would push the shorter Back/Undo buttons down by
        // half of ModeUnitStack's extra height (it's taller — two stacked buttons vs one).
        verticalAlignment = Alignment.Top,
    ) {
        if (onClose != null) {
            ChromeLightButton(
                drawnSize = 40.dp,
                onClick = onClose,
                contentDescription = stringResource(R.string.armeasure_action_back),
            ) {
                Text("‹", color = ArMeasureTokens.TextPrimary, fontSize = 22.sp)
            }
        } else {
            Box(modifier = Modifier.size(48.dp))
        }

        UndoForwardGroup(canUndo, onUndo, canRedo, onRedo)

        ModeUnitStack(
            unit = unit,
            onUnitClick = onUnitClick,
            onModeClick = onModeClick,
            showUnitMenu = showUnitMenu,
            onSelectUnit = onSelectUnit,
            onDismissUnitMenu = onDismissUnitMenu,
        )
    }
}

@Composable
private fun UndoForwardGroup(
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        ChromeLightButton(
            drawnSize = 40.dp,
            onClick = onUndo,
            enabled = canUndo,
            contentDescription = stringResource(R.string.armeasure_action_undo),
        ) {
            Text(
                "↩",
                color = if (canUndo) ArMeasureTokens.TextPrimary else ArMeasureTokens.TextDisabled,
                fontSize = 20.sp,
            )
        }
        ChromeLightButton(
            drawnSize = 40.dp,
            onClick = onRedo,
            enabled = canRedo,
            contentDescription = stringResource(R.string.armeasure_action_redo),
        ) {
            Text(
                "↪",
                color = if (canRedo) ArMeasureTokens.TextPrimary else ArMeasureTokens.TextDisabled,
                fontSize = 20.sp,
            )
        }
    }
}

/**
 * `ModeBtn` stacked above `UnitBtn` — the design's `ModeUnitStack` frame. The [UnitMenu] popup is
 * anchored here rather than at some unrelated point in [ArCameraScreen], so it opens from its
 * actual trigger instead of an approximate screen corner.
 */
@Composable
private fun ModeUnitStack(
    unit: LengthUnit,
    onUnitClick: () -> Unit,
    onModeClick: () -> Unit,
    showUnitMenu: Boolean,
    onSelectUnit: (LengthUnit) -> Unit,
    onDismissUnitMenu: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ChromeLightButton(
            drawnSize = 44.dp,
            onClick = onModeClick,
            contentDescription = stringResource(R.string.armeasure_action_open_mode_sheet),
        ) {
            Text("▦", color = ArMeasureTokens.TextPrimary, fontSize = 20.sp)
        }
        Box {
            UnitBtn(unit = unit, onClick = onUnitClick)
            if (showUnitMenu) {
                UnitMenu(selected = unit, onSelect = onSelectUnit, onDismiss = onDismissUnitMenu)
            }
        }
    }
}

/**
 * How far the bottom bar sits above the navigation-bar inset.
 *
 * [ArCameraScreen]'s hint toast is positioned from the same bottom edge, so raising this without
 * raising the toast's own offset by the same amount puts the two on top of each other.
 */
private val BottomBarLift = 50.dp

/**
 * Only [CaptureBtn] plus the freed-up Clear slot — see [ArCameraTopBar]'s KDoc for why Clear
 * lands here even though the live document's bottom bar shows just the capture button.
 * `CaptureBtn` is centred on `fillMaxWidth()`, not at the mock's `x=142` — that value centres a
 * 360-wide bar, but the screen is 354 wide (locked decision).
 */
@Composable
internal fun ArCameraBottomBar(
    clearEnabled: Boolean,
    onClear: () -> Unit,
    addEnabled: Boolean,
    onAddPoint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .navigationBarsPadding()
            .fillMaxWidth()
            // Lifted clear of the navigation bar's own inset: on a gesture-nav device
            // navigationBarsPadding() alone leaves the buttons close enough to the swipe-up area
            // that a tap near the capture button's lower edge reads as a system gesture.
            .padding(bottom = BottomBarLift)
            .padding(horizontal = 32.dp)
            .height(87.dp),
    ) {
        ClearBtn(
            enabled = clearEnabled,
            onClick = onClear,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        CaptureBtn(
            enabled = addEnabled,
            onClick = onAddPoint,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * The fill+blur and the label are separate sibling boxes, same reasoning as
 * [vn.apero.armeasure.common.ui.ChromeLightButton]: `Modifier.blur` blurs its own subtree, so a
 * node that carried both the blur and the label text would blur the text into a smudge.
 */
@Composable
private fun ClearBtn(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.armeasure_action_clear)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(999.dp))
                .background(ArMeasureTokens.chromeLightFill)
                .chromeBlur(8.dp),
        )
        Text(
            text = label,
            color = if (enabled) ArMeasureTokens.TextPrimary else ArMeasureTokens.TextDisabled,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun CaptureBtn(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.armeasure_action_capture)
    Box(
        modifier = modifier
            .size(77.dp)
            .clip(CircleShape)
            .background(if (enabled) ArMeasureTokens.Signature else ArMeasureTokens.Signature.copy(alpha = 0.38f))
            .then(
                if (enabled) {
                    Modifier.border(3.dp, Color.White, CircleShape)
                } else {
                    Modifier
                },
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = ArMeasureTokens.OnSignature, fontSize = 30.sp, fontWeight = FontWeight.Normal)
    }
}

/**
 * The coaching/tracking hint pill and the commit-confirmation message — insight 6 maps this 1:1
 * onto the mock's `ARToast` (`#1A1D1F` @0.88, r16, 12dp blur, white 13/500). Hug height rather
 * than the mock's fixed ~77px slab, which floats and off-centres a single line of text.
 */
@Composable
internal fun ARToast(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(16.dp))
                .background(ArMeasureTokens.ChromeDark)
                .chromeBlur(12.dp),
        )
        Text(
            text = text,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
