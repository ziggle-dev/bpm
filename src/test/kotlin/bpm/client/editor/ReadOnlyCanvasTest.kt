package bpm.client.editor

import bpm.catalog.BpmCatalog
import imgui.flag.ImGuiKey
import io.osrsx.vscript.editor.graph.OwnCanvas
import io.osrsx.vscript.model.BuiltinNodes
import io.osrsx.vscript.model.Graph
import io.osrsx.vscript.model.Link
import io.osrsx.vscript.model.Node
import io.osrsx.vscript.runtime.EditorDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real ImGui frames, no GL: the canvas must ignore edits from a viewer and take them from a holder. */
class ReadOnlyCanvasTest {
    private fun doc() = EditorDoc(
        Graph(
            "g", "g",
            listOf(Node(1, BuiltinNodes.ENTRY_TICK, 40f, 40f), Node(2, "items.move", 300f, 40f, literals = linkedMapOf("From" to "chest", "To" to "hopper"))),
            listOf(Link(1, 1, "Exec", 2, "Exec")),
        ),
    )

    private fun canvas() = OwnCanvas(BpmCatalog.catalog)

    @Test
    fun `delete does nothing on a read-only canvas and works otherwise`() {
        ImGuiHarness.start()
        for (readOnly in listOf(true, false)) {
            val d = doc()
            val c = canvas()
            c.readOnly = readOnly
            ImGuiHarness.frame { c.render(d) }
            c.select(listOf(2))
            assertEquals(listOf(2), c.selectedIds)
            ImGuiHarness.press(ImGuiKey.Delete) { c.render(d) }
            if (readOnly) {
                assertEquals(2, d.nodes.size, "a viewer's Delete must not remove the node")
                assertFalse(d.history.canUndo)
            } else {
                assertEquals(1, d.nodes.size, "the holder's Delete removes the selected node")
                assertTrue(d.history.canUndo)
            }
        }
    }

    @Test
    fun `screen to graph mapping is available after a frame`() {
        ImGuiHarness.start()
        val d = doc()
        val c = canvas()
        ImGuiHarness.frame { c.render(d) }
        assertTrue(c.contains(100f, 100f))
        val (gx, gy) = c.toGraph(100f, 100f)
        assertTrue(gx.isFinite() && gy.isFinite())
    }
}
