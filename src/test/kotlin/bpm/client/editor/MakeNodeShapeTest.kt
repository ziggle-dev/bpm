package bpm.client.editor

import bpm.catalog.BpmCatalog
import io.osrsx.vscript.model.BuiltinNodes
import io.osrsx.vscript.model.Graph
import io.osrsx.vscript.model.Node
import io.osrsx.vscript.model.resolveNode
import io.osrsx.vscript.runtime.EditorDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A Make node naming one of the host's records grows a pin per field, as the canvas shapes it. */
class MakeNodeShapeTest {
    private fun shape(of: String): List<String> {
        val doc = EditorDoc(Graph("g", "g", nodes = emptyList(), links = emptyList()))
        val node = Node(1, BuiltinNodes.STRUCT_MAKE, 0f, 0f, literals = linkedMapOf(BuiltinNodes.STRUCT_OF to of))
        val desc = resolveNode(node, BpmCatalog.catalog[BuiltinNodes.STRUCT_MAKE]!!, { null }, types = { doc.visibleTypes() })
        return desc.inputs.map { it.name }
    }

    @Test
    fun `the host's data records are on offer and shape the node`() {
        val names = EditorDoc(Graph("g", "g", nodes = emptyList(), links = emptyList())).visibleTypes().map { it.name }
        assertTrue("BlockPos" in names && "ItemStack" in names && "Filter" in names, "visible: $names")
        assertEquals(listOf(BuiltinNodes.STRUCT_OF, "x", "y", "z"), shape("BlockPos"))
        assertTrue("item" in shape("Filter") && "not" in shape("Filter"), "filter pins: ${shape("Filter")}")
        // An unknown name leaves only the naming pin, as before.
        assertEquals(listOf(BuiltinNodes.STRUCT_OF), shape("NoSuchType"))
    }
}
