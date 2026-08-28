package vn.apero.armeasure.ar.presentation.camera.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.sin

/**
 * What the screen shows while ARCore has not found a plane yet.
 *
 * ### Why this exists
 *
 * Before it, the scanning state was a camera feed, an 8 dp dot and a 13 sp pill at the bottom of the
 * screen. First-time users read that as a broken app — nothing on screen claimed the app was doing
 * anything. The reference app puts a 162 dp animation dead centre for exactly this reason, and
 * tracks time-to-first-plane as a product metric.
 *
 * ### Why it is drawn rather than played
 *
 * The obvious implementation is a Lottie file, and it was the plan until the drawing below had been
 * reviewed and approved as-is. Drawing it costs no `lottie-compose` dependency (~300 KB, in a module
 * whose whole job is to be dropped into host apps), no asset to source or re-source, and lets the
 * colour come from the theme instead of being baked mono-white into a file.
 *
 * The signature is deliberately just `(label, modifier)` so that trade can be revisited without
 * touching a caller: if a designer delivers a real vector Lottie later, this file is the only thing
 * that changes.
 *
 * The reference app's own `ar_search.json` is worth not copying twice over — it is a hundred PNG
 * frames wrapped in a Lottie container, 827 KB for a three-second loop that is geometrically this.
 */

/** The footprint, matching the reference app's 162 dp. Large enough to read as the subject of the screen. */
private val IndicatorSize = 162.dp

/** One sweep out and back. Matches the 3 s loop the asset spec asked for. */
private const val SweepDurationMs = 3_000

/** The grid quad, as fractions of the box: a plane in shallow perspective. */
private const val QuadTopInset = 0.16f
private const val QuadTopY = 0.34f
private const val QuadBottomInset = 0.02f
private const val QuadBottomY = 0.70f

/** Interior grid density — enough to read as a surface, sparse enough not to turn into a solid. */
private const val QuadColumns = 6
private const val QuadRows = 4

private val PhoneSize = 26.dp to 48.dp
private const val PhoneSweepFraction = 0.20f
private const val PhoneRiseFraction = 0.05f
private const val PhoneTiltRadians = 0.22f

private val StrokeColor = Color(0xEBFFFFFF)
private val GridColor = Color(0x80FFFFFF)
private val PhoneFill = Color(0x29FFFFFF)

@Composable
internal fun ScanningIndicator(label: String, modifier: Modifier = Modifier) {
    val animated = rememberAnimationsEnabled()

    // 0..1 and back, so the phone sweeps out and returns without a jump at the loop seam.
    val phase = if (animated) {
        val transition = rememberInfiniteTransition(label = "scan")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = SweepDurationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sweep",
        )
        value
    } else {
        // Held mid-sweep rather than at 0: the still frame should look composed, not like a
        // paused animation caught at its extreme.
        0.125f
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Canvas(modifier = Modifier.size(IndicatorSize)) { drawScanSweep(phase) }
        Text(
            text = label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Whether the system wants animations at all.
 *
 * Android expresses "reduce motion" as an animator duration scale of 0 — the same switch developer
 * options and accessibility settings both write. Honouring it matters more here than for most
 * decoration: this thing sits in the middle of the screen and never stops on its own.
 */
@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}

private fun DrawScope.drawScanSweep(phase: Float) {
    val w = size.width
    val h = size.height

    val topLeft = Offset(w * QuadTopInset, h * QuadTopY)
    val topRight = Offset(w * (1f - QuadTopInset), h * QuadTopY)
    val bottomLeft = Offset(w * QuadBottomInset, h * QuadBottomY)
    val bottomRight = Offset(w * (1f - QuadBottomInset), h * QuadBottomY)

    val outline = Path().apply {
        moveTo(topLeft.x, topLeft.y)
        lineTo(topRight.x, topRight.y)
        lineTo(bottomRight.x, bottomRight.y)
        lineTo(bottomLeft.x, bottomLeft.y)
        close()
    }
    drawPath(outline, color = StrokeColor, style = Stroke(width = 1.6.dp.toPx()))

    // Interior lines dashed, outline solid: the quad's edge is the shape, the interior only has to
    // suggest a measurable surface.
    val dash = PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 3.5.dp.toPx()))
    val interior = Path().apply {
        for (k in 1 until QuadColumns) {
            val t = k.toFloat() / QuadColumns
            moveTo(lerp(topLeft.x, topRight.x, t), topLeft.y)
            lineTo(lerp(bottomLeft.x, bottomRight.x, t), bottomLeft.y)
        }
        for (k in 1 until QuadRows) {
            val t = k.toFloat() / QuadRows
            moveTo(lerp(topLeft.x, bottomLeft.x, t), lerp(topLeft.y, bottomLeft.y, t))
            lineTo(lerp(topRight.x, bottomRight.x, t), lerp(topRight.y, bottomRight.y, t))
        }
    }
    drawPath(interior, color = GridColor, style = Stroke(width = 1.dp.toPx(), pathEffect = dash))

    // The phone: across on a sine, lifting slightly at both extremes so the path reads as an arc
    // over the surface rather than a slide along it.
    val sweep = sin(phase * 2f * Math.PI.toFloat())
    val phoneWidth = PhoneSize.first.toPx()
    val phoneHeight = PhoneSize.second.toPx()
    val centre = Offset(
        x = w * (0.5f + sweep * PhoneSweepFraction),
        y = h * (0.58f - abs(sweep) * PhoneRiseFraction),
    )

    rotate(degrees = Math.toDegrees((sweep * PhoneTiltRadians).toDouble()).toFloat(), pivot = centre) {
        val corner = CornerRadius(5.dp.toPx())
        val origin = Offset(centre.x - phoneWidth / 2f, centre.y - phoneHeight / 2f)
        val phoneSize = Size(phoneWidth, phoneHeight)
        drawRoundRect(color = PhoneFill, topLeft = origin, size = phoneSize, cornerRadius = corner)
        drawRoundRect(
            color = Color.White,
            topLeft = origin,
            size = phoneSize,
            cornerRadius = corner,
            style = Stroke(width = 1.8.dp.toPx()),
        )
    }
}

private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction
