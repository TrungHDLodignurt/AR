package vn.quancua.artapemeasure.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Restored from the pre-extraction ui/MeasureControls.kt: this is app-level navigation chrome,
// not part of the AR/photo measure library surface, so it stays here rather than becoming module
// public API. The 3 colour literals below are duplicated on purpose per the phase 03 plan ("3
// duplicated Color(...) literals is the correct price for not making chrome colours public API").
private val ChromeContent = Color.White
private val Disabled = Color(0x4DFFFFFF)

/** Bottom tab bar: Measure | Photo | Box | Cylinder | Level. */
@Composable
fun AppTabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // background before the inset padding, so the dark bar still reaches the physical
            // bottom edge (behind the gesture/nav bar) — only the tab labels get pushed up
            // clear of it by windowInsetsPadding.
            .background(Color(0xF21C1C1E))
            .windowInsetsPadding(WindowInsets.navigationBars)
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
    PhotoMeasure("Photo", "▨"),
    Box("Box", "⬚"),
    Cylinder("Cylinder", "◯"),
    Level("Level", "◎"),
}
