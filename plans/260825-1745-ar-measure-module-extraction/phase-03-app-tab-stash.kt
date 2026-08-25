// Stashed from app/src/main/java/vn/quancua/artapemeasure/ui/MeasureControls.kt during phase 03
// extraction (:ar-measure-ar). Phase 05 recreates these in :app — record §4 drops them from the
// module's public surface, they are app-level navigation, not part of the AR measure library.
//
// Needs these imports when recreated in :app:
//   androidx.compose.foundation.background
//   androidx.compose.foundation.clickable
//   androidx.compose.foundation.layout.Arrangement
//   androidx.compose.foundation.layout.Column
//   androidx.compose.foundation.layout.Row
//   androidx.compose.foundation.layout.WindowInsets
//   androidx.compose.foundation.layout.fillMaxWidth
//   androidx.compose.foundation.layout.navigationBars
//   androidx.compose.foundation.layout.padding
//   androidx.compose.foundation.layout.windowInsetsPadding
//   androidx.compose.material3.Text
//   androidx.compose.runtime.Composable
//   androidx.compose.ui.Alignment
//   androidx.compose.ui.Modifier
//   androidx.compose.ui.graphics.Color
//   androidx.compose.ui.text.font.FontWeight
//   androidx.compose.ui.unit.dp
//   androidx.compose.ui.unit.sp
//
// Also needs its own ChromeContent/Disabled colour constants — duplicated on purpose per the
// phase 03 plan ("3 duplicated Color(...) literals is the correct price for not making chrome
// colours public API").

private val ChromeContent = Color.White
private val Disabled = Color(0x4DFFFFFF)

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
