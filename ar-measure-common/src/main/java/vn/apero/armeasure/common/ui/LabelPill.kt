package vn.apero.armeasure.common.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp

private val PillBackground = Color.White

/**
 * A rounded white pill with [label], centred on [center].
 *
 * Screen-space rather than a 3D/photo-space label, so it keeps one size regardless of zoom or
 * camera distance — shared between the AR measure overlay and the photo-reference measure
 * screen, which both draw the same kind of distance readout.
 */
fun DrawScope.drawLabelPill(
    textMeasurer: TextMeasurer,
    label: String,
    center: Offset,
    style: TextStyle,
    backgroundColor: Color = PillBackground,
) {
    val layout = textMeasurer.measure(AnnotatedString(label), style)
    val paddingX = 8.dp.toPx()
    val paddingY = 4.dp.toPx()
    val pillWidth = layout.size.width + paddingX * 2
    val pillHeight = layout.size.height + paddingY * 2
    val topLeft = Offset(center.x - pillWidth / 2f, center.y - pillHeight / 2f)

    drawRoundRect(
        color = backgroundColor,
        topLeft = topLeft,
        size = Size(pillWidth, pillHeight),
        cornerRadius = CornerRadius(pillHeight / 2f),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(topLeft.x + paddingX, topLeft.y + paddingY),
    )
}
