package bpm.client.mc

import bpm.client.editor.Workbench
import net.minecraft.core.BlockPos

/**
 * The editor as a Minecraft screen: one persistent [Workbench] per connection (kept by [WorkbenchSession])
 * drawn by [BpmEditorScreen]'s ImGui frame. Opening from a controller attaches it; opening from `/bpm
 * editor` leaves it free-standing.
 */
class WorkbenchScreen(private val pos: BlockPos?) : BpmEditorScreen(
    body = { WorkbenchSession.workbench.render(); EditorPrefs.flush() },
    wantsEscape = { WorkbenchSession.workbench.wantsEscape },
) {
    override fun init() {
        super.init()
        WorkbenchSession.attach(pos)
        WorkbenchSession.workbench.onScreenOpened()
    }

    override fun render(graphics: net.minecraft.client.gui.GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Block pictures the panels asked for last frame, drawn into their textures before ImGui draws this one.
        BlockPreviewRenderer.renderPending()
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun removed() {
        super.removed()
        WorkbenchSession.workbench.onScreenClosed()
        EditorPrefs.flush(force = true)
    }
}
