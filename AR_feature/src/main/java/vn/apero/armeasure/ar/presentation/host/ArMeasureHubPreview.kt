package vn.apero.armeasure.ar.presentation.host

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.apero.armeasure.R
import vn.apero.armeasure.common.ui.ArMeasureTheme
import vn.apero.armeasure.common.ui.ArMeasureTokens

/**
 * Previews for the hub's two cards.
 *
 * [ArMeasureHub] itself is deliberately not previewed: it reads `rememberArAvailability()`, which
 * needs a real ARCore install check, and Layoutlib has no ARCore — the preview would either render
 * the Unsupported branch or fail outright, telling you nothing about the layout. The cards are the
 * part worth looking at, so they are previewed directly.
 *
 * Cards use the same [HubCardHeight] the screen does, so the preview cannot drift from it.
 */
@Preview(name = "Hub cards", showBackground = true, widthDp = 354, heightDp = 540)
@Composable
private fun HubCardsPreview() {
    ArMeasureTheme(dark = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ArMeasureTokens.BgPrimary)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            HubCard(
                badgeIcon = R.drawable.armeasure_ic_scan,
                title = stringResource(R.string.armeasure_hub_ar_card_title),
                description = stringResource(R.string.armeasure_hub_ar_card_desc),
                onClick = {},
                modifier = Modifier.height(HubCardHeight),
            )
            HubCard(
                badgeIcon = R.drawable.armeasure_ic_image,
                title = stringResource(R.string.armeasure_hub_photo_card_title),
                description = stringResource(R.string.armeasure_hub_photo_card_desc),
                onClick = {},
                modifier = Modifier.height(HubCardHeight),
            )
        }
    }
}

/**
 * The same card at its natural height, so the centred-row alignment is obvious without the tall
 * panel's empty space around it — this is the check that the badge, text block and chevron all sit
 * on one vertical centre line.
 */
@Preview(name = "Hub card — row alignment", showBackground = true, widthDp = 354)
@Composable
private fun HubCardRowPreview() {
    ArMeasureTheme(dark = false) {
        Column(modifier = Modifier.background(ArMeasureTokens.BgPrimary).padding(20.dp)) {
            HubCard(
                badgeIcon = R.drawable.armeasure_ic_scan,
                title = stringResource(R.string.armeasure_hub_ar_card_title),
                description = stringResource(R.string.armeasure_hub_ar_card_desc),
                onClick = {},
            )
        }
    }
}
