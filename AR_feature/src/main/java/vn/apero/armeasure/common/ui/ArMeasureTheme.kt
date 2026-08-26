package vn.apero.armeasure.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** `bgPrimary` design token, the hub/photo screens' background. */
private val HubBackground = Color(0xFFF4F4F2)

private val ArMeasureDarkColors = darkColorScheme()
private val ArMeasureLightColors = lightColorScheme(background = HubBackground, surface = HubBackground)

/**
 * A thin `MaterialTheme` wrapper this module renders its own screens through, so it never
 * inherits a host's theme — the AR camera screens must stay dark (the camera feed is the
 * background) regardless of what the host app's theme looks like. No `:core` module: this file
 * is small enough that splitting it out would only add a Gradle module for one function.
 *
 * @param dark `true` for the full-screen camera Activities, `false` for the hub and photo
 *   screens (design background `#F4F4F2`).
 */
@Composable
internal fun ArMeasureTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) ArMeasureDarkColors else ArMeasureLightColors) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            content()
        }
    }
}
