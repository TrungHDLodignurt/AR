package vn.apero.armeasure.ar.presentation.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import vn.apero.armeasure.R

private val ChromeBackground = Color(0x66000000)
private val ChromeContent = Color.White
private val Disabled = Color(0x4DFFFFFF)

/**
 * Top row: undo on the left, Clear on the right — the reference app's chrome. When [onClose] is
 * non-null, a leading "✕" pill is shown too; the layout is otherwise identical to the `onClose ==
 * null` case, today's default.
 */
@Composable
internal fun MeasureTopBar(
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    Row(
        // Host app targets an SDK where edge-to-edge is enforced — content draws behind the
        // status bar unless it pads for it itself. Inset padding goes first so the 16/12dp
        // visual padding stacks on top of it, not underneath.
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row {
            if (onClose != null) {
                ChromePill(enabled = true, onClick = onClose) {
                    Text("✕", color = ChromeContent, fontSize = 18.sp)
                }
            }
            ChromePill(
                enabled = canUndo,
                onClick = onUndo,
                contentDescription = stringResource(R.string.armeasure_action_undo),
            ) {
                // "↩" rather than a Material icon: the extended icon set is a large dependency
                // for one glyph, and this is the exact shape the reference app uses.
                Text("↩", color = if (canUndo) ChromeContent else Disabled, fontSize = 20.sp)
            }
            ChromePill(
                enabled = canRedo,
                onClick = onRedo,
                contentDescription = stringResource(R.string.armeasure_action_redo),
            ) {
                // "↪" pairs with "↩" above — same reasoning: one glyph is not worth the extended
                // Material icon set as a dependency.
                Text("↪", color = if (canRedo) ChromeContent else Disabled, fontSize = 20.sp)
            }
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
internal fun ChromePill(
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ChromeBackground)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
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
internal fun MeasureBottomBar(
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
