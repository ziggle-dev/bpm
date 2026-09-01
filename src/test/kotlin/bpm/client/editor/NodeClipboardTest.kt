package bpm.client.editor

import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.Link
import dev.ziggle.vscript.model.Node
import dev.ziggle.vscript.runtime.EditorDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeClipboardTest {
    private fun doc(): EditorDoc = EditorDoc(
        Graph(
            "g", "g",
            listOf(
                Node(1, BuiltinNodes.ENTRY_TICK, 10f, 20f),
                Node(2, "items.move", 200f, 20f, literals = linkedMapOf("From" to "chest", "To" to "hopper", "Max" to 8L)).also { it.comment = "note" },
                Node(3, "items.count", 200f, 200f, literals = linkedMapOf("Link" to "chest")),
            ),
            listOf(Link(1, 1, "Exec", 2, "Exec"), Link(2, 3, "Count", 2, "Max")),
        ),
    )

    @Test
    fun `copy takes the selected nodes and only the links between them`() {
        val d = doc()
        val text = NodeClipboard.encode(d, listOf(2, 3))!!
        assertTrue(text.startsWith(NodeClipboard.MARKER))
        val clip = NodeClipboard.decode(text)!!
        assertEquals(listOf(2, 3), clip.nodes.map { it.id })
        assertEquals(1, clip.links.size, "the link from the unselected entry is left out")
        assertEquals("Count", clip.links[0].fromPin)
        assertNull(NodeClipboard.encode(d, emptyList()))
        assertNull(NodeClipboard.decode("not ours"))
        assertNull(NodeClipboard.decode(NodeClipboard.MARKER + "{broken"))
    }

    @Test
    fun `paste gets fresh ids, keeps literals and internal links, lands at the point, and is one undo step`() {
        val d = doc()
        val clip = NodeClipboard.decode(NodeClipboard.encode(d, listOf(2, 3)))!!
        val before = d.nodes.size
        val ids = NodeClipboard.paste(d, clip, 500f, 600f)
        assertEquals(2, ids.size)
        assertTrue(ids.none { it in setOf(1, 2, 3) }, "fresh ids: $ids")
        assertEquals(before + 2, d.nodes.size)
        val moved = d.node(ids[0])!!
        val count = d.node(ids[1])!!
        assertEquals(500f, moved.x)
        assertEquals(600f, moved.y)
        assertEquals(780f, count.y, "relative layout is kept")
        assertEquals("chest", moved.literals["From"])
        assertEquals("note", moved.comment)
        val internal = d.links.filter { it.fromNode == count.id && it.toNode == moved.id }
        assertEquals(1, internal.size, "the internal link was re-created between the copies")
        assertTrue(d.links.none { it.fromNode == 1 && it.toNode == moved.id }, "no link to the original entry")
        assertTrue(d.history.canUndo)
        d.undo()
        assertEquals(before, d.nodes.size, "one undo removes the whole paste")
        assertTrue(d.links.none { it.toNode == moved.id })
    }

    @Test
    fun `cut copies then deletes as one step`() {
        val d = doc()
        val text = NodeClipboard.cut(d, listOf(3))!!
        assertTrue(text.contains("items.count"))
        assertNull(d.node(3))
        assertTrue(d.links.none { it.fromNode == 3 })
        d.undo()
        assertEquals(3, d.nodes.size)
    }
}
