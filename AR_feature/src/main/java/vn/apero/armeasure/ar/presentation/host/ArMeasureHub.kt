package vn.apero.armeasure.ar.presentation.host

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.apero.armeasure.R
import vn.apero.armeasure.ar.ArAvailability
import vn.apero.armeasure.common.ui.ArMeasureTheme
import vn.apero.armeasure.photo.presentation.ArPhotoActivity

private val BgPrimary = Color(0xFFF4F4F2)
private val CardBorder = Color(0x241A1D1F)
private val TitleColor = Color(0xFF1A1D1F)
private val DescColor = Color(0xFF5C6166)
private val BadgeBackground = Color(0x1F8A9A5B)
private val BadgeContent = Color(0xFF8A9A5B)
private val ChevronColor = Color(0xFF9BA1A6)

/**
 * The module's one entry surface — a tab-root composable a host embeds in its own tab (design
 * `suhME`, SCR-14). Draws **no** bottom bar and needs **no** back affordance: the host's own tab
 * bar (design ref `yCnt6` -> `dNXIJ`) already owns that chrome, and drawing a second one here
 * would force this module to know the host's tabs, icons and selected state — exactly the
 * coupling a portable module must not have.
 *
 * The AR Measure card hides itself when [ArAvailability.Unsupported] (decision: ARCore
 * unavailable hides the AR tools, never the whole module); Picture Measure has no ARCore
 * dependency at all and always shows.
 */
@Composable
fun ArMeasureHub(modifier: Modifier = Modifier) {
    ArMeasureTheme(dark = false) {
        val context = LocalContext.current
        val availability = rememberArAvailability()

        Column(
            modifier = modifier.fillMaxSize().background(BgPrimary),
        ) {
            Column(
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 20.dp),
            ) {
                Text(
                    text = stringResource(R.string.armeasure_hub_title),
                    color = TitleColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.armeasure_hub_subtitle),
                    color = DescColor,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (availability != ArAvailability.Unsupported) {
                    HubCard(
                        badgeGlyph = "▬",
                        title = stringResource(R.string.armeasure_hub_ar_card_title),
                        description = stringResource(R.string.armeasure_hub_ar_card_desc),
                        onClick = { ArCameraActivity.start(context) },
                    )
                }
                HubCard(
                    badgeGlyph = "▨",
                    title = stringResource(R.string.armeasure_hub_photo_card_title),
                    description = stringResource(R.string.armeasure_hub_photo_card_desc),
                    onClick = { ArPhotoActivity.start(context) },
                )
            }
        }
    }
}

/**
 * One card: a badge, title+description, and a decorative chevron. Hug height (design shipped
 * `fill_container` inside a `fill_container` column, which was a resize artefact, not intent —
 * the actual content is ~88dp regardless of how tall the column around it is).
 */
@Composable
private fun HubCard(
    badgeGlyph: String,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(BadgeBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = badgeGlyph, color = BadgeContent, fontSize = 26.sp)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = TitleColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = description,
                color = DescColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(text = "›", color = ChevronColor, fontSize = 20.sp)
    }
}
