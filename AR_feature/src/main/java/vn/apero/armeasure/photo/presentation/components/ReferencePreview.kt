package vn.apero.armeasure.photo.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import vn.apero.armeasure.common.domain.LengthUnit
import vn.apero.armeasure.common.ui.ArMeasureTheme
import vn.apero.armeasure.common.ui.ArMeasureTokens
import vn.apero.armeasure.photo.domain.imaging.ReferenceObject
import vn.apero.armeasure.photo.domain.imaging.builtInReferenceObjects

/**
 * Previews for the reference-object picker's cards and the add/edit sheet's controls.
 *
 * `ReferencePickerScreen` is not previewed as a whole because it reads the custom-object store off
 * disk; the cards it lays out are previewed directly instead, one of each kind.
 */
@Preview(name = "Reference cards", showBackground = true, widthDp = 354)
@Composable
private fun ReferenceCardsPreview() {
    val custom = ReferenceObject(id = "preview:phone", label = "phone", shortSideMm = 70f, longSideMm = 150f)
    ArMeasureTheme(dark = false) {
        Column(
            modifier = Modifier.fillMaxWidth().background(ArMeasureTokens.BgPrimary).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The two built-ins side by side: this is the pair whose artwork comes from the
            // supplied icon set, keyed on ReferenceObject.id.
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                builtInReferenceObjects.forEach { reference ->
                    Column(modifier = Modifier.weight(1f)) {
                        PresetCard(reference = reference, unit = LengthUnit.Cm, onClick = {})
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    CustomCard(reference = custom, unit = LengthUnit.Cm, onClick = {}, onEdit = {})
                }
                Column(modifier = Modifier.weight(1f)) {
                    AddReferenceCard(onClick = {})
                }
            }
        }
    }
}

/**
 * The add/edit sheet's two icon-bearing controls, closed and at rest.
 *
 * [UnitChip] is previewed with `showMenu = false` on purpose: the open state is a popup, which
 * Layoutlib renders outside the preview's own bounds, so an open chip would look identical to a
 * closed one while hiding the menu somewhere off-canvas.
 */
@Preview(name = "Sheet unit chip + delete row", showBackground = true, widthDp = 354)
@Composable
private fun SheetControlsPreview() {
    ArMeasureTheme(dark = false) {
        Column(
            modifier = Modifier.fillMaxWidth().background(ArMeasureTokens.BgSurface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LengthUnit.entries.forEach { unit ->
                    UnitChip(unit = unit, showMenu = false, onClick = {}, onSelect = {}, onDismissMenu = {})
                }
            }
            DeleteRow(onClick = {})
        }
    }
}

/**
 * The empty/filled pair for one field, side by side.
 *
 * This is the preview that catches the field resizing between its two states: if the boxes ever
 * stop being the same height again, it shows here without needing a device and a keyboard.
 */
@Preview(name = "Sheet fields — empty vs filled", showBackground = true, widthDp = 354)
@Composable
private fun SheetFieldStatesPreview() {
    ArMeasureTheme(dark = false) {
        Column(
            modifier = Modifier.fillMaxWidth().background(ArMeasureTokens.BgSurface).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTextField(value = "", onValueChange = {}, placeholder = "e.g. Phone", modifier = Modifier.fillMaxWidth())
            SheetTextField(value = "Phone", onValueChange = {}, placeholder = "e.g. Phone", modifier = Modifier.fillMaxWidth())
            // The dimension row as the sheet lays it out: two fields plus the chip, one empty and
            // one filled, so the bottom edges are checkable against each other and the chip.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                DimensionField(label = "Length", value = "", onValueChange = {}, modifier = Modifier.weight(1f))
                DimensionField(label = "Width", value = "165", onValueChange = {}, modifier = Modifier.weight(1f))
                UnitChip(unit = LengthUnit.Cm, showMenu = false, onClick = {}, onSelect = {}, onDismissMenu = {})
            }
        }
    }
}
