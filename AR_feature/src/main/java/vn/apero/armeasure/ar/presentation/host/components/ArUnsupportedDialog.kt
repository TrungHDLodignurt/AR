package vn.apero.armeasure.ar.presentation.host.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import vn.apero.armeasure.R
import vn.apero.armeasure.common.ui.ArMeasureTokens

/**
 * Explains why AR measuring cannot run on this device, and offers the two things the user can
 * actually do about it.
 *
 * Shown instead of hiding the AR card. Hiding it left the tab looking like the module only ever had
 * one feature, so a user on an unsupported device never learned the other one existed or why they
 * could not have it; a card that answers when tapped is more use than a card that is not there.
 *
 * [onOpenPlayStore] leads to Google Play Services for AR. Worth being clear-eyed about it: on a
 * device reporting `UNSUPPORTED_DEVICE_NOT_CAPABLE` the install will not make AR work — the device
 * is not on ARCore's certified list — so this is an explanation route, not a fix. It does help the
 * other case that maps to the same state: an `UNKNOWN_ERROR`/`UNKNOWN_TIMED_OUT` device where the
 * services are genuinely just missing or stale.
 */
@Composable
internal fun ArUnsupportedDialog(
    onOpenPlayStore: () -> Unit,
    onUsePictureMeasure: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ArMeasureTokens.BgSurface)
                .border(1.dp, ArMeasureTokens.BorderSoft, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.armeasure_ar_unsupported_title),
                color = ArMeasureTokens.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.armeasure_ar_unsupported_body),
                color = ArMeasureTokens.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            // Stacked, not side by side. "Use Picture Measure" wraps to two lines in half a
            // dialog's width, which left the two buttons visibly different heights, and a larger
            // system font scale pushes it to three. Full-width rows are indifferent to how long
            // either label gets — including once these strings are translated.
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DialogButton(
                    label = stringResource(R.string.armeasure_ar_unsupported_use_photo),
                    filled = true,
                    onClick = onUsePictureMeasure,
                )
                DialogButton(
                    label = stringResource(R.string.armeasure_ar_unsupported_details),
                    filled = false,
                    onClick = onOpenPlayStore,
                )
            }
        }
    }
}

/** 48dp minimum height, per this repo's touch-target rule — these are the dialog's only actions. */
@Composable
private fun DialogButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
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

/**
 * Opens Google Play Services for AR in the Play Store.
 *
 * Tries the `market://` scheme first so an installed Play Store handles it directly, then falls
 * back to the https URL for a device where Play is absent or the scheme is unhandled. Both are
 * wrapped: on a device with no browser either, there is nothing sensible to do but stay put, and a
 * crash from an unresolved Intent is not it.
 */
internal fun openArCoreInPlayStore(context: Context) {
    val marketIntent = Intent(Intent.ACTION_VIEW, "market://details?id=$ArCorePackage".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(marketIntent)
        return
    } catch (_: ActivityNotFoundException) {
        // No Play Store app — fall through to the web listing.
    }
    val webIntent = Intent(
        Intent.ACTION_VIEW,
        "https://play.google.com/store/apps/details?id=$ArCorePackage".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(webIntent) }
}

/** Google Play Services for AR, the separate APK that provides ARCore. */
private const val ArCorePackage = "com.google.ar.core"
