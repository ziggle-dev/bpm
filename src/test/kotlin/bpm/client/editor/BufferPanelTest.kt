package bpm.client.editor

import bpm.catalog.BpmCatalog
import dev.ziggle.vscript.editor.host.IconRegion
import dev.ziggle.vscript.model.GraphSource
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The buffer panel drawn through the ImGui harness with a controller reporting items, tanks and energy —
 * it must lay out, size itself from the report, and answer hover tooltips without touching Minecraft.
 */
class BufferPanelTest {

    private object NoStore : DocumentStore {
        override val libraries: List<DocRecord> get() = emptyList()
        override val libraryVersion: Int get() = 0
        override val graphSource: GraphSource get() = GraphSource { null }
        override fun refresh() {}
        override fun createLibrary(name: String) {}
        override fun rename(id: UUID, name: String) {}
        override fun delete(id: UUID) {}
        override fun duplicate(id: UUID, name: String) {}
        override fun ensureLibraryGraph(id: UUID) {}
        override fun open(id: UUID, wantEdit: Boolean): OpenDoc = throw UnsupportedOperationException()
        override fun openControllerGraph(): OpenDoc? = null
        override fun close(id: UUID) {}
        override fun requestLease(id: UUID, steal: Boolean) {}
        override fun releaseLease(id: UUID) {}
        override fun commit(id: UUID, deploy: Boolean): Boolean = false
        override fun takeTheirs(id: UUID) {}
        override fun keepMine(id: UUID) {}
    }

    private class Controller(override val info: ControllerInfo?) : ControllerControl {
        override val label: String = "controller 1, 2, 3"
        override val links: List<LinkView> = emptyList()
        override fun bind(docId: UUID?) {}
        override fun setFlags(enabled: Boolean, debugBuild: Boolean) {}
        override fun start() {}
        override fun stop() {}
        override fun restart() {}
        override fun renameLink(name: String, newName: String) {}
        override fun removeLink(name: String) {}
    }

    private object Looks : FluidLooks {
        override fun colour(fluidId: String): Int? = if (fluidId == "bpm:experience") 0xFFA8F04A.toInt() else null
        override fun labelOf(fluidId: String): String? = if (fluidId == "bpm:experience") "Liquid Experience" else null
        override fun describe(fluidId: String, amountMb: Int): String? = if (fluidId == "bpm:experience") "${amountMb / 20} experience points" else null
    }

    private object NoIcons : ItemIcons {
        override fun want(itemId: String) {}
        override fun region(itemId: String): IconRegion? = null
        override fun labelOf(itemId: String): String? = null
    }

    private fun info() = ControllerInfo(
        status = "running", docId = null, docName = "", docVersion = 1, runningVersion = 1, enabled = true, debugBuild = true,
        lastError = null, fibers = 1, jobs = 0, transfers = 0,
        buffer = listOf("minecraft:diamond_pickaxe" to 1, "minecraft:diamond_ore" to 12) + List(7) { "" to 0 },
        tanks = listOf(TankView("bpm:experience", 3200, 16000), TankView("minecraft:water", 16000, 16000), TankView("", 0, 16000), TankView("", 0, 16000)),
        energy = 12500, energyCapacity = 100000,
    )

    @Test
    fun `renders items, tanks and energy and sizes itself from the report`() {
        ImGuiHarness.start()
        run {
            val host = WorkbenchHost(BpmCatalog.catalog, NoStore, Prefs.Memory, controller = Controller(info()), icons = NoIcons, fluids = Looks)
            val panel = BufferPanel(host)
            val h = panel.height(info())
            assertTrue(h > 200f && h < 600f, "height $h")
            // A frame with the pointer off the panel, then one hovering the experience tank and one on slot 1.
            ImGuiHarness.frame { panel.render(0f, 0f, 260f, h) }
            ImGuiHarness.frame(mouseX = 100f, mouseY = Chrome.SECTION_H * 2 + 3 * BufferPanel.CELL + BufferPanel.GAP + 10f) { panel.render(0f, 0f, 260f, h) }
            ImGuiHarness.frame(mouseX = Chrome.PAD + BufferPanel.CELL + 10f, mouseY = Chrome.SECTION_H + 10f) { panel.render(0f, 0f, 260f, h) }
            // No report yet: the panel still lays out with placeholders.
            val waiting = BufferPanel(WorkbenchHost(BpmCatalog.catalog, NoStore, Prefs.Memory, controller = Controller(null), icons = NoIcons, fluids = Looks))
            ImGuiHarness.frame { waiting.render(0f, 0f, 260f, waiting.height(null)) }
        }
    }
}
