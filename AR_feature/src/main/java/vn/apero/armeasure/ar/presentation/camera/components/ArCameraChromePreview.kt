package vn.apero.armeasure.ar.presentation.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.ui.ArMeasureTheme

/**
 * Previews for the camera chrome.
 *
 * `ArCameraScreen` itself cannot be previewed at all: it mounts a Filament `Engine` and an
 * `ARSceneView`, both of which need a real GL context and an ARCore session that Layoutlib does
 * not provide. The chrome is the layer that gets designed and re-designed, so it is previewed on
 * its own over a flat stand-in for the camera feed.
 *
 * `backgroundColor` is the muted brown the feed averages to on a typical indoor shot — a white
 * preview background would hide that these controls are translucent white pills.
 */
private const val FeedStandIn = 0xFF3A322FL

@Preview(name = "Camera top bar", showBackground = true, backgroundColor = FeedStandIn, widthDp = 360, heightDp = 150)
@Composable
private fun ArCameraTopBarPreview() {
    ArMeasureTheme(dark = true) {
        ArCameraTopBar(
            canUndo = true,
            onUndo = {},
            canRedo = false,
            onRedo = {},
            onClose = {},
            unit = LengthUnit.Cm,
            onSelectUnit = {},
            showUnitMenu = false,
            onUnitClick = {},
            onDismissUnitMenu = {},
            onModeClick = {},
        )
    }
}

/**
 * Undo enabled, redo disabled — the pair that shows both tint states of the same icon at once,
 * which is what a single all-enabled preview cannot tell you.
 */
@Preview(name = "Camera bottom bar", showBackground = true, backgroundColor = FeedStandIn, widthDp = 360, heightDp = 220)
@Composable
private fun ArCameraBottomBarPreview() {
    ArMeasureTheme(dark = true) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            ArCameraBottomBar(clearEnabled = true, onClear = {}, addEnabled = true, onAddPoint = {})
        }
    }
}

@Preview(name = "Camera toast", showBackground = true, backgroundColor = FeedStandIn, widthDp = 360, heightDp = 140)
@Composable
private fun ARToastPreview() {
    ArMeasureTheme(dark = true) {
        Box(modifier = Modifier.fillMaxSize().background(Color(FeedStandIn)), contentAlignment = Alignment.Center) {
            ARToast(text = "Not enough detail — point at a more textured surface")
        }
    }
}
