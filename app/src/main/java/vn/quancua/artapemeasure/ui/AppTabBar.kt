package vn.quancua.artapemeasure.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// This demo host mirrors AIP936's floating-capsule bottom nav shape (aip-bottom-nav.kt's
// AipBottomNav: a rounded capsule of equal-weight items, the selected one filled) using this
// repo's own plain Color literals instead of AIP936's design-token theme (Aip.colors/spacing) —
// that theme lives in AIP936, not here, and :app is only a demo host proving the module's
// integration contract, not a reimplementation of AIP936's own chrome.
private val CapsuleBackground = Color(0xF21C1C1E)
private val SelectedFill = Color(0xFF48484A)
private val ChromeContent = Color.White
private val Disabled = Color(0x99FFFFFF)

/** Demo host's 2-tab nav: Home | Measure — Measure is the tab `ArMeasureHub()` renders into. */
@Composable
fun AppTabBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(CircleShape)
                .background(CapsuleBackground)
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            AppTab.entries.forEach { tab ->
                val active = tab == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (active) SelectedFill else Color.Transparent)
                        .clickable { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(tab.glyph, color = if (active) ChromeContent else Disabled, fontSize = 16.sp)
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
}

enum class AppTab(val label: String, val glyph: String) {
    Home("Home", "⌂"),
    Measure("Measure", "▬"),
}
