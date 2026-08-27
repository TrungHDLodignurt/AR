package vn.apero.armeasure.ar.presentation.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R
import vn.apero.armeasure.ar.ArAvailability
import vn.apero.armeasure.common.ui.ArMeasureTheme
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.photo.presentation.ArPhotoActivity

/**
 * The module's one entry surface — a tab-root composable a host embeds in its own tab (design
 * `suhME`, SCR-14). Draws **no** bottom bar and needs **no** back affordance: the host's own tab
 * bar (design ref `yCnt6` -> `dNXIJ`) already owns that chrome, and drawing a second one here
 * would force this module to know the host's tabs, icons and selected state — exactly the
 * coupling a portable module must not have.
 *
 * Both cards always show, including on a device ARCore cannot run on. The AR card used to hide
 * itself there, which left the tab looking like the module only ever had one feature — the user
 * never learned the other one existed, nor why they could not have it. Tapping it on such a device
 * opens [ArUnsupportedDialog] instead of the camera. Picture Measure has no ARCore dependency at
 * all and works everywhere.
 */
@Composable
fun ArMeasureHub(modifier: Modifier = Modifier) {
    ArMeasureTheme(dark = false) {
        val context = LocalContext.current
        val availability = rememberArAvailability()
        var showUnsupportedDialog by remember { mutableStateOf(false) }

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(ArMeasureTokens.BgPrimary)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Column(
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 20.dp),
            ) {
                Text(
                    text = stringResource(R.string.armeasure_hub_title),
                    color = ArMeasureTokens.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.armeasure_hub_subtitle),
                    color = ArMeasureTokens.TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                HubCard(
                    badgeGlyph = "▬",
                    title = stringResource(R.string.armeasure_hub_ar_card_title),
                    description = stringResource(R.string.armeasure_hub_ar_card_desc),
                    // Checking is not a final answer, so it is treated as available and the
                    // Activity re-resolves — sending the user to the dialog on a state that is
                    // about to become Ready would be wrong.
                    onClick = {
                        if (availability == ArAvailability.Unsupported) {
                            showUnsupportedDialog = true
                        } else {
                            ArCameraActivity.start(context)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                HubCard(
                    badgeGlyph = "▨",
                    title = stringResource(R.string.armeasure_hub_photo_card_title),
                    description = stringResource(R.string.armeasure_hub_photo_card_desc),
                    onClick = { ArPhotoActivity.start(context) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (showUnsupportedDialog) {
            ArUnsupportedDialog(
                onOpenPlayStore = {
                    showUnsupportedDialog = false
                    openArCoreInPlayStore(context)
                },
                // Same destination as the Picture Measure card, so the dialog's advice is one tap
                // rather than an instruction to go and find the other card.
                onUsePictureMeasure = {
                    showUnsupportedDialog = false
                    ArPhotoActivity.start(context)
                },
                onDismiss = { showUnsupportedDialog = false },
            )
        }
    }
}

/**
 * One card: a badge, title+description, and a decorative chevron pinned to the trailing-bottom
 * edge. Design (SCR-14) instances are tall panels — 314x238, `fill_container` in both axes inside
 * the 493-tall cards region (2 x 238 + 18px gap ≈ 493) — not the compact 320x88 row the shared
 * `c/FeatureCard` component draws elsewhere; [modifier] carries the `Modifier.weight(1f)` that
 * makes each card stretch to fill its half of that region.
 */
@Composable
private fun HubCard(
    badgeGlyph: String,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ArMeasureTokens.BgSurface)
            .border(1.dp, ArMeasureTokens.BorderSoft, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(ArMeasureTokens.SignatureMuted),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = badgeGlyph, color = ArMeasureTokens.Signature, fontSize = 26.sp)
        }
        Column(modifier = Modifier.padding(top = 16.dp)) {
            Text(text = title, color = ArMeasureTokens.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = description,
                color = ArMeasureTokens.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "›",
            color = ArMeasureTokens.TextDisabled,
            fontSize = 20.sp,
            modifier = Modifier.align(Alignment.End),
        )
    }
}
