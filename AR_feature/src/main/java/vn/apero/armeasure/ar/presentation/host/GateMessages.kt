package vn.apero.armeasure.ar.presentation.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R

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

/** Shown by [ArCameraActivity] when the CAMERA permission has been denied. */
@Composable
internal fun CameraDenied() {
    CenteredMessage(
        title = stringResource(R.string.armeasure_camera_denied_title),
        body = stringResource(R.string.armeasure_camera_denied_body),
    )
}

@Composable
internal fun CenteredMessage(title: String, body: String) {
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
    }
}
