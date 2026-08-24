package vn.quancua.artapemeasure.level

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A bubble level.
 *
 * No AR whatsoever — just the gravity vector. That matters for reach: this tab works on every
 * Android device, including the ones where ARCore is unavailable and the measure tab cannot
 * run at all. It is also why it is worth shipping early rather than last.
 */
@Composable
fun LevelScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Tilt of the device's screen plane away from horizontal, in degrees, plus the direction
    // the bubble should slide toward.
    var tiltDegrees by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // TYPE_GRAVITY is already low-pass filtered by the platform; raw accelerometer would
        // need smoothing of its own.
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                val magnitude = sqrt(gx * gx + gy * gy + gz * gz)
                if (magnitude < 1e-3f) return

                // Angle between the screen normal (device +Z) and gravity. 0° = screen facing
                // straight up, i.e. the device lying flat.
                val cosTilt = (gz / magnitude).coerceIn(-1f, 1f)
                tiltDegrees = Math.toDegrees(kotlin.math.acos(cosTilt).toDouble()).toFloat()

                // Bubble slides opposite the in-plane gravity component.
                offsetX = -gx / magnitude
                offsetY = gy / magnitude
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (sensor != null) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    val isFlat = abs(tiltDegrees) < 1.5f

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = minOf(size.width, size.height) * 0.28f
            val bubbleRadius = outerRadius * 0.22f
            val ringColor = if (isFlat) Color(0xFF32D74B) else Color.White

            drawCircle(
                color = ringColor.copy(alpha = 0.9f),
                radius = outerRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.25f),
                radius = bubbleRadius * 1.15f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )

            // Clamp the bubble inside the ring so it never escapes the dial.
            val travel = outerRadius - bubbleRadius
            val raw = Offset(offsetX * travel, offsetY * travel)
            val length = hypot(raw.x, raw.y)
            val clamped = if (length > travel && length > 0f) raw * (travel / length) else raw

            drawCircle(color = ringColor, radius = bubbleRadius, center = center + clamped)
        }

        Text(
            text = if (isFlat) "0°" else "${tiltDegrees.roundToInt()}°",
            color = if (isFlat) Color(0xFF32D74B) else Color.White,
            fontSize = 44.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
