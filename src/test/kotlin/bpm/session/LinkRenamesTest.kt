package bpm.session

import bpm.catalog.BpmCatalog
import io.osrsx.vscript.model.BuiltinNodes
import io.osrsx.vscript.model.Node
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkRenamesTest {
    @Test
    fun `only link pins that name the old link are rewritten`() {
        val nodes = listOf(
            Node(1, BuiltinNodes.ENTRY_TICK),
            Node(2, "items.move", literals = linkedMapOf("From" to "chest", "To" to "hopper", "Max" to 8L)),
            Node(3, "items.move", literals = linkedMapOf("From" to "hopper", "To" to "chest")),
            Node(4, "chat.say", literals = linkedMapOf("Message" to "chest")),
        )
        assertEquals(listOf(2 to "From", 3 to "To"), LinkRenames.references(nodes, BpmCatalog.catalog, "chest"))
        assertEquals(2, LinkRenames.rewrite(nodes, BpmCatalog.catalog, "chest", "input"))
        assertEquals("input", nodes[1].literals["From"])
        assertEquals("hopper", nodes[1].literals["To"])
        assertEquals(8L, nodes[1].literals["Max"])
        assertEquals("input", nodes[2].literals["To"])
        assertEquals("chest", nodes[3].literals["Message"], "a plain string that happens to match is not a link")
        assertEquals(0, LinkRenames.rewrite(nodes, BpmCatalog.catalog, "chest", "x"), "nothing names the old link any more")
    }
}
