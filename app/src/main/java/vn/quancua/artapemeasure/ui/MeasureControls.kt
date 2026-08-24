package vn.quancua.artapemeasure.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

private val ChromeBackground = Color(0x66000000)
private val ChromeContent = Color.White
private val Disabled = Color(0x4DFFFFFF)

/** Top row: undo on the left, Clear on the right — the reference app's chrome. */
@Composable
fun MeasureTopBar(
    canUndo: Boolean,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ChromePill(enabled = canUndo, onClick = onUndo) {
            // "↩" rather than a Material icon: the extended icon set is a large dependency
            // for one glyph, and this is the exact shape the reference app uses.
            Text("↩", color = if (canUndo) ChromeContent else Disabled, fontSize = 20.sp)
        }
        ChromePill(enabled = canUndo, onClick = onClear) {
            Text(
                "Clear",
                color = if (canUndo) ChromeContent else Disabled,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ChromePill(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ChromeBackground)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Bottom row: the big "+" that commits a point, and the shutter.
 *
 * The shutter is rendered but inert — capturing the AR frame buffer plus this overlay is a
 * separate piece of work, and a button that lies about being wired up is worse than one that
 * visibly is not. The reference app also greys it out until a measurement exists.
 */
@Composable
fun MeasureBottomBar(
    addEnabled: Boolean,
    onAddPoint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(68.dp)
                .clip(CircleShape)
                .background(if (addEnabled) Color.White else Color(0x80FFFFFF))
                .then(if (addEnabled) Modifier.clickable(onClick = onAddPoint) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "+",
                color = Color(0xFF1C1C1E),
                fontSize = 34.sp,
                fontWeight = FontWeight.Light,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 28.dp)
                .size(46.dp)
                .clip(CircleShape)
                .border(2.dp, Disabled, CircleShape),
        )
    }
}

/** Bottom tab bar: Measure | Level. */
@Composable
fun AppTabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xF21C1C1E))
            .padding(top = 8.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        AppTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                modifier = Modifier.clickable { onSelect(tab) }.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(tab.glyph, color = if (active) ChromeContent else Disabled, fontSize = 17.sp)
                Text(
                    tab.label,
                    color = if (active) ChromeContent else Disabled,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

enum class AppTab(val label: String, val glyph: String) {
    Measure("Measure", "▬"),
    Level("Level", "◎"),
}
