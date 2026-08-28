package vn.apero.armeasure.ar.presentation.host.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R
import vn.apero.armeasure.common.ui.ArMeasureTokens

/**
 * Shown by [ArCameraActivity] when [vn.apero.armeasure.ar.ArAvailability.Unsupported] — moved
 * out of the demo host's old `MainActivity` verbatim, since the module now owns the Activity that
 * needs it.
 */
@Composable
internal fun ArUnsupported() {
    CenteredMessage(
        title = stringResource(R.string.armeasure_ar_unsupported_title),
        body = stringResource(R.string.armeasure_ar_unsupported_body),
    )
}

/**
 * Shown by [ArCameraActivity] when the CAMERA permission has been denied.
 *
 * The button matters more than it looks. Once a user has denied twice, Android stops showing the
 * system prompt entirely, so the only way back is the app's own settings page — and telling someone
 * to "grant access in Settings" without taking them there leaves them to find it. `ArCameraActivity`
 * re-reads the permission in `onResume`, so returning from that page lights the screen up with no
 * further tap.
 */
@Composable
internal fun CameraDenied(onOpenSettings: () -> Unit) {
    CenteredMessage(
        title = stringResource(R.string.armeasure_camera_denied_title),
        body = stringResource(R.string.armeasure_camera_denied_body),
        action = stringResource(R.string.armeasure_camera_denied_open_settings) to onOpenSettings,
    )
}

@Composable
internal fun CenteredMessage(
    title: String,
    body: String,
    /** Optional label-plus-handler for a single action button under the message. */
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        Text(
            body,
            color = Color(0xB3FFFFFF),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (action != null) {
            val (label, onClick) = action
            Box(
                modifier = Modifier
                    .padding(top = 24.dp)
                    // 48dp minimum touch target, per this repo's rule — on this screen it is the
                    // only way forward.
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ArMeasureTokens.Signature)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = ArMeasureTokens.OnSignature,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Visual check for both gate screens without a device or a permission state to reproduce:
 * open this file in Android Studio and use the split/design pane.
 */
@Preview(name = "Camera denied", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun CameraDeniedPreview() {
    CameraDenied(onOpenSettings = {})
}

@Preview(name = "AR unsupported", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ArUnsupportedPreview() {
    ArUnsupported()
}
