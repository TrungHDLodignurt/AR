package vn.apero.armeasure.photo.presentation

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.common.ui.ChromeLightButton

/**
 * The SCR-21/22/23 top nav. `showUndoRedoAndSave` follows the design literally: SCR-21/22 (before
 * the reference is ever confirmed) show only a back arrow; SCR-23's undo/redo + Save appear once
 * the user has calibrated at least once (including mid "Chỉnh sửa tỉ lệ" — insight 12 treats
 * SCR-23/24 as one screen carrying everything, not two).
 */
@Composable
internal fun PhotoTopNav(
    onBack: () -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    showUndoRedoAndSave: Boolean,
    saveSupported: Boolean,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeLightButton(
            drawnSize = 40.dp,
            onClick = onBack,
            contentDescription = stringResource(R.string.armeasure_action_back),
        ) {
            Text("‹", color = ArMeasureTokens.TextPrimary, fontSize = 22.sp)
        }

        if (showUndoRedoAndSave) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                UndoRedoButton("↩", canUndo, onUndo, stringResource(R.string.armeasure_action_undo))
                UndoRedoButton("↪", canRedo, onRedo, stringResource(R.string.armeasure_action_redo))
            }
            if (saveSupported) {
                SaveButton(enabled = saveEnabled, onClick = onSave)
            } else {
                Text(
                    text = stringResource(R.string.armeasure_photo_save_unsupported),
                    color = ArMeasureTokens.TextSecondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp).padding(start = 8.dp),
                )
            }
        } else {
            Box(modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun UndoRedoButton(glyph: String, enabled: Boolean, onClick: () -> Unit, contentDescription: String) {
    ChromeLightButton(drawnSize = 40.dp, onClick = onClick, enabled = enabled, contentDescription = contentDescription) {
        Text(glyph, color = if (enabled) ArMeasureTokens.TextPrimary else ArMeasureTokens.TextDisabled, fontSize = 20.sp)
    }
}

/** 48dp-tall filled button, `SignatureText` fill / `OnSignature` text — `SignatureText` (not `Signature`) is what actually clears WCAG AA with white text (~4.54:1 vs ~3.06:1), fixing the design's 2.78:1 bare-text "Lưu". */
@Composable
private fun SaveButton(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.armeasure_photo_save_cta)
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) ArMeasureTokens.SignatureText else ArMeasureTokens.SignatureText.copy(alpha = 0.5f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = ArMeasureTokens.OnSignature, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/** SCR-21/22's instruction box (`fill #1a3a4a`, off-token raw hex — recorded, not promoted into [ArMeasureTokens] for one screen's use). */
@Composable
internal fun InstructionBox(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        lineHeight = 19.6.sp,
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A3A4A))
            .border(1.dp, Color(0xFF4A6A7A), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** SCR-22's confirm affordance, corrected from the mock's 66-glyph-in-100dp (oversized vs any Material FAB) down to 44. */
@Composable
internal fun CheckmarkBtn(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.armeasure_photo_confirm_cd)
    Box(
        modifier = modifier
            .size(100.dp)
            .clip(CircleShape)
            .background(ArMeasureTokens.Signature)
            .border(3.dp, Color.White, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", color = ArMeasureTokens.OnSignature, fontSize = 44.sp, fontWeight = FontWeight.Bold)
    }
}

/** SCR-23's bottom toolbar: `LineSegmentBtn` re-centres the line (today's "Đặt lại vị trí"), `EditScaleBtn` re-opens the quad editor — both real actions, matching the design's two labelled circles. */
@Composable
internal fun PhotoBottomToolbar(onLineSegment: () -> Unit, onEditScale: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ArMeasureTokens.BgPrimary)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        ToolbarItem(
            glyph = "—",
            label = stringResource(R.string.armeasure_photo_toolbar_line_segment),
            onClick = onLineSegment,
            modifier = Modifier.weight(1f),
        )
        ToolbarItem(
            glyph = "⤢",
            label = stringResource(R.string.armeasure_photo_toolbar_edit_scale),
            onClick = onEditScale,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ToolbarItem(glyph: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(ArMeasureTokens.BgSecondary),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = ArMeasureTokens.TextPrimary, fontSize = 20.sp)
        }
        Text(label, color = ArMeasureTokens.TextSecondary, fontSize = 12.sp)
    }
}
