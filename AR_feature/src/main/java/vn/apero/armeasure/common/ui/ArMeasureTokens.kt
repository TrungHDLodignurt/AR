package vn.apero.armeasure.common.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The AIP936 wireframes' colour tokens (`.pen` `SetVariables`), read once via the live-document
 * pencil MCP for phase 06 and kept as one internal object — this module owns its own palette and
 * must never read a host app's `MaterialTheme` (see [ArMeasureTheme]).
 *
 * Contrast, computed pair-by-pair (WCAG AA text needs ≥4.5:1):
 * - [TextPrimary] / [TextSecondary] on [BgSurface] or [BgPrimary]: ~15:1 / 5.7:1 — pass.
 * - [TextDisabled] on white: **2.34:1 — fails AA. Decorative only** (dimmed icons/glyphs a
 *   sighted user does not need to read); never use it for text that must be legible.
 * - [Signature] on white: **3.05:1 — fails AA, so it is a fill/stroke/icon colour, never text.**
 * - [SignatureText] on white: **~4.6:1 — passes**, added specifically so selected-state labels
 *   (e.g. [MeasureModeSheet]) have an accessible olive instead of reusing [Signature]. Do not
 *   "simplify" these back into one colour — that is the exact bug this token fixes.
 * - White text on [ChromeDark] (`#1A1D1F` @0.88): passes regardless of the camera feed behind it,
 *   because the feed never shows through enough to matter at that alpha.
 * - Dark text ([TextPrimary]) on [ChromeLight]/[ChromeLightFallback]: same — both fills are opaque
 *   enough that legibility never depends on what the live camera image happens to show.
 */
internal object ArMeasureTokens {
    val BgPrimary = Color(0xFFF4F4F2)
    val BgSecondary = Color(0xFFEAEAE7)
    val BgSurface = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF1A1D1F)
    val TextSecondary = Color(0xFF5C6166)

    /** Decorative only — 2.34:1 on white, below WCAG AA. Never use for text that must be read. */
    val TextDisabled = Color(0xFF9BA1A6)

    val BorderStrong = Color(0x4D1A1D1F)
    val BorderSoft = Color(0x241A1D1F)
    val BorderSubtle = Color(0x141A1D1F)

    /** Fills, strokes, icons only — **not text** (3.05:1 on white, fails AA). */
    val Signature = Color(0xFF8A9A5B)

    /** The AA-passing olive (~4.6:1) for any text/label that would otherwise use [Signature]. */
    val SignatureText = Color(0xFF6E7C42)
    val SignatureMuted = Color(0x1F8A9A5B)
    val OnSignature = Color(0xFFFFFFFF)
    val Error = Color(0xFFB4483C)
    val Scrim = Color(0xB81A1D1F)

    /** Camera-pill fill on API 31+, paired with [Modifier.chromeBlur]. */
    val ChromeLight = Color(0xCCFFFFFF)

    /** API<31 fallback: `Modifier.blur` no-ops on background content there, so this is more
     * opaque to stay readable without it — see phase 06's risk assessment. */
    val ChromeLightFallback = Color(0xE0FFFFFF)

    /** Toast + AR measure-label fill, `#1A1D1F` @0.88 — opaque enough to skip the API guard. */
    val ChromeDark = Color(0xE01A1D1F)

    /** [ChromeLight] on API 31+, [ChromeLightFallback] below — pair with [Modifier.chromeBlur]. */
    val chromeLightFill: Color
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ChromeLight else ChromeLightFallback
}

/**
 * `Modifier.blur` degrades to a no-op on background content below API 31 (Android 12) — callers
 * must pair this with a more opaque fallback fill on those OS versions rather than shipping
 * unreadable translucent chrome over a live camera feed.
 *
 * On-device verification also found `Modifier.blur` blurs its *own* rendered subtree, not
 * whatever is behind it — a node that carries both this modifier and an icon/text child blurs
 * that child into an unreadable smudge (confirmed on a Pixel 6: every top-bar glyph disappeared).
 * Every caller here therefore applies this to a background-only sibling layer via
 * [Modifier.matchParentSize], never to a node that also draws content.
 */
internal fun Modifier.chromeBlur(radius: Dp): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) this.blur(radius) else this

/**
 * The "blurred pill over the camera feed" chrome shared by every top-bar icon button
 * (Back/Mode/Unit): a [drawnSize] pill inside a ≥[touchTarget] click surface — the drawn pill
 * stays at the design's size, only the tappable area grows to meet the 48dp minimum.
 *
 * The fill+blur and the icon are drawn as separate sibling boxes (see [chromeBlur]'s KDoc) so the
 * icon stays crisp regardless of the blur.
 */
@Composable
internal fun ChromeLightButton(
    drawnSize: Dp,
    onClick: () -> Unit,
    contentDescription: String? = null,
    enabled: Boolean = true,
    touchTarget: Dp = 48.dp,
    shape: Shape = CircleShape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val fill = ArMeasureTokens.chromeLightFill
    Box(
        modifier = modifier
            .size(touchTarget)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(drawnSize)) {
            Box(modifier = Modifier.matchParentSize().clip(shape).background(fill).chromeBlur(8.dp))
            Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) { content() }
        }
    }
}
