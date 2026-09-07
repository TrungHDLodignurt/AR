package vn.apero.armeasure.ar.presentation.host

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R
import vn.apero.armeasure.ar.ArAvailability
import vn.apero.armeasure.common.ui.ArMeasureTheme
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.photo.presentation.ArPhotoActivity
import vn.apero.armeasure.ar.presentation.host.components.ArUnsupportedDialog
import vn.apero.armeasure.ar.presentation.host.components.openArCoreInPlayStore

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

            // Scrolls and keeps the cards at their design height instead of dividing the region
            // between them. The host's bottom banner takes a real row below this tab body, so it
            // appearing or reloading shortens the region — with weighted cards that shortening was
            // shared out and every card resized, moving the whole page under the user. The sibling
            // tabs never showed it because both are top-anchored scrollables (Home's `LazyColumn`,
            // Explore's `LazyVerticalGrid`): a shorter viewport just reveals less of the list.
            // This matches that behaviour. Scrolling only actually engages on a screen too short
            // for 494dp of cards; on a normal phone nothing moves at all.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                HubCard(
                    badgeIcon = R.drawable.armeasure_ic_scan,
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
                    modifier = Modifier.height(HubCardHeight),
                )
                HubCard(
                    badgeIcon = R.drawable.armeasure_ic_image,
                    title = stringResource(R.string.armeasure_hub_photo_card_title),
                    description = stringResource(R.string.armeasure_hub_photo_card_desc),
                    onClick = { ArPhotoActivity.start(context) },
                    modifier = Modifier.height(HubCardHeight),
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
 * The design's card height (`SE2jq`/`pGcGd` are 238 tall; two of them plus the 18dp gap make the
 * 494dp cards region the mock draws).
 *
 * Fixed rather than weighted on purpose — see the cards `Column` above for what weighting cost.
 *
 * `internal` only so `ArMeasureHubPreview` renders the cards at the height the screen uses instead
 * of a copy that can drift from it.
 */
internal val HubCardHeight = 238.dp

/**
 * One card: a badge, title+description and a trailing chevron, laid out as a **single centred
 * row** — design `SE2jq`/`pGcGd` are horizontal frames with `alignItems: center`, gap 14 and
 * padding 16, so all three children share one vertical centre line rather than stacking.
 *
 * This was a `Column` with the chevron pushed to the trailing-bottom edge by a weighted `Spacer`,
 * which is what left the chevron sitting under the description instead of beside it.
 *
 * Design (SCR-14) instances are tall panels — 314x238 — not the compact 320x88 row the shared
 * `c/FeatureCard` component draws elsewhere. [modifier] carries the height: [HubCardHeight], not a
 * `weight`, so the card keeps that height whatever happens to the region around it.
 */
@Composable
internal fun HubCard(
    @DrawableRes badgeIcon: Int,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ArMeasureTokens.BgSurface)
            .border(1.dp, ArMeasureTokens.BorderSoft, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(ArMeasureTokens.SignatureMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(badgeIcon),
                contentDescription = null,
                tint = ArMeasureTokens.Signature,
                modifier = Modifier.size(26.dp),
            )
        }
        // Takes the row's spare width so the chevron stays pinned to the trailing edge and the
        // description wraps instead of pushing it off the card.
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, color = ArMeasureTokens.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(text = description, color = ArMeasureTokens.TextSecondary, fontSize = 13.sp)
        }
        // Decorative: the whole card is the click target, so this must not be announced separately.
        Icon(
            painter = painterResource(R.drawable.armeasure_ic_chevron_right),
            contentDescription = null,
            tint = ArMeasureTokens.TextDisabled,
            modifier = Modifier.size(20.dp),
        )
    }
}
