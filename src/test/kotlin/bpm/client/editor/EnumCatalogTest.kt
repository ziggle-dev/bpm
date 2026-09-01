package bpm.client.editor

import dev.ziggle.vscript.model.HostEnum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnumCatalogTest {
    private val click = EnumCatalog(HostEnum("Click", listOf("Left", "Right"), "a mouse button"))

    @Test
    fun `a host enum's members are its rows, searchable, and only members have a label`() {
        assertEquals(listOf("Left", "Right"), click.browse(10).map { it.value })
        assertEquals(listOf("Right"), click.search("ri", 10).map { it.value })
        assertEquals("", click.browse(10).first().note, "the description is the pin's tooltip, not a note on every row")
        assertEquals("Left", click.labelOf("left"))
        assertNull(click.labelOf("Middle"))
    }
}
