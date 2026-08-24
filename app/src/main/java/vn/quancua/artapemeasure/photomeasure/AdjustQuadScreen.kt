package vn.quancua.artapemeasure.photomeasure

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The registration step of "add custom reference object": drag a quad onto the object in
 * [photo], same interaction as calibrating the measurement photo. Confirming crops the quad's
 * bounding box out of [photo] to use as that object's thumbnail — see `QuadCrop.kt`.
 */
@Composable
fun AdjustQuadScreen(photo: Bitmap, onConfirm: (cropRect: Rect) -> Unit, onCancel: () -> Unit) {
    val imageBitmap = remember(photo) { photo.asImageBitmap() }
    var canvasWidthPx by remember { mutableStateOf(0f) }
    var canvasHeightPx by remember { mutableStateOf(0f) }
    var quad by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            QuadEditorCanvas(
                photo = imageBitmap,
                quad = quad,
                onCornerDrag = { index, position -> quad = quad.toMutableList().also { it[index] = position } },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        canvasWidthPx = size.width.toFloat()
                        canvasHeightPx = size.height.toFloat()
                        if (quad.isEmpty()) quad = defaultQuad(imageBitmap, canvasWidthPx, canvasHeightPx)
                    },
            )
            Text(
                "Kéo 4 góc để khớp với đối tượng — cạnh trên/dưới là cạnh dài, cạnh trái/phải là cạnh ngắn.",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Button(
                onClick = {
                    val fit = aspectFit(imageBitmap.width.toFloat(), imageBitmap.height.toFloat(), canvasWidthPx, canvasHeightPx)
                    onConfirm(quadBoundingBoxInBitmapPixels(quad, fit, photo.width, photo.height))
                },
                enabled = quad.size == 4,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Xong") }
            Text(
                "Huỷ",
                color = Color(0xB3FFFFFF),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

private fun defaultQuad(photo: ImageBitmap, canvasWidthPx: Float, canvasHeightPx: Float): List<Offset> {
    val fit = aspectFit(photo.width.toFloat(), photo.height.toFloat(), canvasWidthPx, canvasHeightPx)
    val insetX = fit.width * 0.2f
    val insetY = fit.height * 0.25f
    return listOf(
        Offset(fit.offsetX + insetX, fit.offsetY + insetY),
        Offset(fit.offsetX + fit.width - insetX, fit.offsetY + insetY),
        Offset(fit.offsetX + fit.width - insetX, fit.offsetY + fit.height - insetY),
        Offset(fit.offsetX + insetX, fit.offsetY + fit.height - insetY),
    )
}
