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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R
import vn.apero.armeasure.common.ui.ArMeasureTokens

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
    // vertical=4dp around 48dp touch targets = 56dp content height, matching the design's TopNav
    // (its own 62dp StatusBar sits on top via the statusBars inset padding below, unchanged).
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BareIconButton(
            glyph = "‹",
            onClick = onBack,
            contentDescription = stringResource(R.string.armeasure_action_back),
            fontSize = MinIconSp,
        )

        if (showUndoRedoAndSave) {
            // Design's UndoForwardGroup gap (40dp between 24dp icons) shrinks a little now the
            // icons are floored to 30dp — 9dp touch-box inset each side instead of 12dp — but the
            // 48dp touch boxes themselves are untouched, so nothing clips.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                UndoRedoButton("↩", canUndo, onUndo, stringResource(R.string.armeasure_action_undo))
                UndoRedoButton("↪", canRedo, onRedo, stringResource(R.string.armeasure_action_redo))
            }
        } else {
            Box(modifier = Modifier.size(48.dp))
        }

        if (showUndoRedoAndSave) {
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

/** The floor every bare-glyph icon in this module's photo flow must clear: a 24dp (or smaller)
 * drawn glyph inside a ≥48dp touch box still reads as too small — this is about the *visible*
 * size, a separate thing from the touch target. Touch targets stay ≥48dp regardless. */
private val MinIconSp = 30.sp

/** Bare 30dp+ glyph in a 48dp touch box, no drawn pill — SCR-21/22/23/24 sit on the cream
 * [ArMeasureTokens.BgPrimary], unlike SCR-19's live camera feed, so the blurred chrome pill
 * ([vn.apero.armeasure.common.ui.ChromeLightButton]) is unnecessary here; [ArMeasureTokens]'
 * own contrast note confirms [ArMeasureTokens.TextPrimary] on [ArMeasureTokens.BgPrimary] clears
 * WCAG AA (~15:1). */
@Composable
private fun BareIconButton(
    glyph: String,
    onClick: () -> Unit,
    contentDescription: String,
    fontSize: TextUnit,
    enabled: Boolean = true,
    color: Color = ArMeasureTokens.TextPrimary,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = color, fontSize = fontSize)
    }
}

@Composable
private fun UndoRedoButton(glyph: String, enabled: Boolean, onClick: () -> Unit, contentDescription: String) {
    BareIconButton(
        glyph = glyph,
        onClick = onClick,
        contentDescription = contentDescription,
        fontSize = MinIconSp,
        enabled = enabled,
        color = if (enabled) ArMeasureTokens.TextPrimary else ArMeasureTokens.TextDisabled,
    )
}

/**
 * SCR-24's ("AR Adjust", design `kYLQt`) top nav — **only** X (discard) and ✓ (commit), no
 * undo/redo/save: those stay exclusive to SCR-23's [PhotoTopNav], since SCR-24 never edits history
 * or writes a file. The title sits in a true screen-centred [Box], not a 3-way [Row], so its
 * position never shifts if the two icon boxes were ever unequal widths.
 */
@Composable
internal fun LineDrawTopNav(title: String, onCancel: () -> Unit, onCommit: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        BareIconButton(
            glyph = "✕",
            onClick = onCancel,
            contentDescription = stringResource(R.string.armeasure_photo_segment_discard_cd),
            fontSize = MinIconSp,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(
            text = title,
            color = ArMeasureTokens.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center),
        )
        BareIconButton(
            glyph = "✓",
            onClick = onCommit,
            contentDescription = stringResource(R.string.armeasure_photo_segment_confirm_cd),
            fontSize = MinIconSp,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
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

/** SCR-21/22's instruction box (`fill #1a3a4a`, off-token raw hex — recorded, not promoted into
 * [ArMeasureTokens] for one screen's use). Design: a 354x96 wrapper (12dp side inset, 16dp
 * top/bottom) around a fixed 330x64 box — `fillMaxWidth` + `defaultMinSize(minHeight = 64.dp)`
 * so a short reference name doesn't collapse the box to its text height.
 *
 * Sized on an outer [Box], not the [Text] directly: a bare `Text(modifier = ...)`'s own semantics
 * node reports only its intrinsic glyph bounds, not the padding/background this composable adds
 * around it — a real (if minor) TalkBack bug too, since its focus highlight would otherwise
 * undersize the visible card. `semantics(mergeDescendants = true)` on the [Box] merges the
 * [Text]'s content up into a node sized to the full box, matching every other chrome element here
 * ([ChromeLightButton]-style buttons already do this by attaching semantics to their outer box). */
@Composable
internal fun InstructionBox(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 16.dp)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A3A4A))
            .border(1.dp, Color(0xFF4A6A7A), RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            lineHeight = 19.6.sp,
            textAlign = TextAlign.Center,
        )
    }
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
            // 30dp icon in the same 48dp circle — 9dp padding each side remains, same margin the
            // design's own 48dp LineIconCircle left around its (too-small) 24dp icon.
            Text(glyph, color = ArMeasureTokens.TextPrimary, fontSize = MinIconSp)
        }
        Text(label, color = ArMeasureTokens.TextSecondary, fontSize = 12.sp)
    }
}
