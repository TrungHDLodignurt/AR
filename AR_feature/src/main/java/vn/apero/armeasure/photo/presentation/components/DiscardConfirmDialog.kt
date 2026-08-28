package vn.apero.armeasure.photo.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import vn.apero.armeasure.R
import vn.apero.armeasure.common.ui.ArMeasureTokens

/**
 * Confirms before the back arrow throws a measuring session away.
 *
 * That arrow clears the photo, the quad, the calibration, every committed segment and both undo
 * stacks — and it sits in the same row as undo and redo, so a mis-tap costs everything with no way
 * back. The caller only raises this when there is something to lose; backing out of a photo nothing
 * has been done to still leaves immediately.
 *
 * Buttons are stacked full-width for the same reason as `ArUnsupportedDialog`: the labels are long
 * enough to wrap side by side, and a larger system font scale makes that worse. The destructive
 * action is the outlined one and the safe action is filled, so the emphasised button is the one that
 * keeps the user's work.
 */
@Composable
internal fun DiscardConfirmDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    Dialog(onDismissRequest = onKeep) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ArMeasureTokens.BgSurface)
                .border(1.dp, ArMeasureTokens.BorderSoft, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.armeasure_photo_discard_title),
                color = ArMeasureTokens.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.armeasure_photo_discard_body),
                color = ArMeasureTokens.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DialogAction(
                    label = stringResource(R.string.armeasure_photo_discard_cancel),
                    filled = true,
                    onClick = onKeep,
                )
                DialogAction(
                    label = stringResource(R.string.armeasure_photo_discard_confirm),
                    filled = false,
                    onClick = onDiscard,
                )
            }
        }
    }
}

/** 48dp minimum height, per this repo's touch-target rule. */
@Composable
private fun DialogAction(label: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) ArMeasureTokens.Signature else Color.Transparent)
            .border(
                1.dp,
                if (filled) Color.Transparent else ArMeasureTokens.BorderStrong,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (filled) ArMeasureTokens.OnSignature else ArMeasureTokens.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(name = "Discard confirmation", showBackground = true)
@Composable
private fun DiscardConfirmDialogPreview() {
    DiscardConfirmDialog(onDiscard = {}, onKeep = {})
}
