package bpm.client.editor

import bpm.catalog.BpmCatalog
import io.osrsx.vscript.editor.graph.CanvasTyping
import io.osrsx.vscript.model.BuiltinNodes
import io.osrsx.vscript.model.Graph
import io.osrsx.vscript.model.Link
import io.osrsx.vscript.model.Node
import io.osrsx.vscript.model.PinType
import io.osrsx.vscript.runtime.EditorDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** On the canvas, a loop's Element (and a map loop's Key / Value) carry the type of what is wired in, not a wildcard. */
class CanvasTypingTest {
    private val catalog = BpmCatalog.catalog

    @Test
    fun `for each over an area's positions has a BlockPos element`() {
        val doc = EditorDoc(Graph("g", "g",
            nodes = listOf(Node(1, "world.area"), Node(2, BuiltinNodes.FOR_EACH), Node(3, BuiltinNodes.FOR_EACH)),
            links = listOf(Link(1, 1, "Positions", 2, "List")),
        ))
        val forEach = doc.node(2)!!
        val element = CanvasTyping.descOf(doc, catalog, forEach)!!.output("Element")!!
        assertEquals("BlockPos", CanvasTyping.pinType(doc, catalog, forEach, element).name)
        // Nothing wired in: the wildcard stands, exactly as before.
        val bare = doc.node(3)!!
        assertTrue(CanvasTyping.pinType(doc, catalog, bare, CanvasTyping.descOf(doc, catalog, bare)!!.output("Element")!!).isWildcard)
    }

    @Test
    fun `for each entry over a block's properties has string keys and values`() {
        val doc = EditorDoc(Graph("g", "g",
            nodes = listOf(Node(1, "world.blockInfo"), Node(2, BuiltinNodes.MAP_FOR_EACH)),
            links = listOf(Link(1, 1, "Properties", 2, "Map")),
        ))
        val loop = doc.node(2)!!
        val desc = CanvasTyping.descOf(doc, catalog, loop)!!
        assertEquals(PinType.STRING, desc.output(BuiltinNodes.MAP_KEY_PIN)!!.type.builtin)
        assertEquals(PinType.STRING, desc.output(BuiltinNodes.MAP_VALUE_PIN)!!.type.builtin)
    }
}
