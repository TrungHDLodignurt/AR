package vn.apero.armeasure.photo.domain.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceObjectJsonTest {

    @Test
    fun `encode then decode round-trips every field exactly`() {
        val objects = listOf(
            ReferenceObject(id = "abc-123", label = "Phone", shortSideMm = 70f, longSideMm = 160f),
            ReferenceObject(id = "def-456", label = "Card", shortSideMm = 60f, longSideMm = 100f),
        )

        val decoded = decodeReferences(encodeReferences(objects))

        assertEquals(objects, decoded)
    }

    @Test
    fun `decoding legacy JSON with no id field mints a non-blank distinct id per entry`() {
        val legacyJson = """
            [
                {"label":"Phone","shortSideMm":70.0,"longSideMm":160.0},
                {"label":"Phone","shortSideMm":70.0,"longSideMm":150.0}
            ]
        """.trimIndent()

        val decoded = decodeReferences(legacyJson)

        assertEquals(2, decoded.size)
        decoded.forEach { assertTrue(it.id.isNotBlank()) }
        assertNotEquals(decoded[0].id, decoded[1].id)
    }

    @Test
    fun `id migration preserves label and both dimensions bit-exactly`() {
        val legacyJson = """[{"label":"Phone","shortSideMm":70.0,"longSideMm":160.0}]"""

        val decoded = decodeReferences(legacyJson)

        assertEquals(1, decoded.size)
        assertEquals("Phone", decoded[0].label)
        assertEquals(70f, decoded[0].shortSideMm)
        assertEquals(160f, decoded[0].longSideMm)
    }

    @Test
    fun `malformed JSON returns an empty list and does not throw`() {
        assertEquals(emptyList<ReferenceObject>(), decodeReferences("not json at all"))
        assertEquals(emptyList<ReferenceObject>(), decodeReferences(null))
        assertEquals(emptyList<ReferenceObject>(), decodeReferences(""))
    }

    @Test
    fun `an entry missing a dimension is skipped rather than defaulted to zero`() {
        val json = """
            [
                {"label":"Phone","shortSideMm":70.0,"longSideMm":160.0},
                {"label":"NoWidth","longSideMm":160.0},
                {"label":"NoLength","shortSideMm":70.0}
            ]
        """.trimIndent()

        val decoded = decodeReferences(json)

        assertEquals(1, decoded.size)
        assertEquals("Phone", decoded[0].label)
        assertFalse(decoded.any { it.shortSideMm == 0f || it.longSideMm == 0f })
    }

    @Test
    fun `encoding the same list twice produces identical output`() {
        val objects = listOf(
            ReferenceObject(id = "abc-123", label = "Phone", shortSideMm = 70f, longSideMm = 160f),
            ReferenceObject(id = "def-456", label = "Card", shortSideMm = 60f, longSideMm = 100f),
        )

        assertEquals(encodeReferences(objects), encodeReferences(objects))
    }
}
