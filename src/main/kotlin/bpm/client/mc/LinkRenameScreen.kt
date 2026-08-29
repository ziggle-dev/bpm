package bpm.client.mc

import bpm.client.net.ClientNet
import bpm.client.render.LinkerHud
import bpm.net.LinkOp
import bpm.world.Link
import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiInputTextFlags
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImString
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * The linker's in-place rename: a one-line ImGui box hung over the linked block (ctrl-use on a link with the
 * wand), Enter to rename through the controller's link-edit payload, Escape to leave it. The world stays as
 * it is behind it — no dimming — so the box reads as a label on the block.
 */
class LinkRenameScreen private constructor(private val state: State) :
    BpmEditorScreen(body = { state.draw() }, wantsEscape = { false }, dimsWorld = false) {

    private class State(val controllerPos: BlockPos, val link: Link, val taken: Set<String>) {
        val name = ImString(link.name, 64)
        var focus = true
        var done = false

        fun draw() {
            val w = Minecraft.getInstance().window
            // Hung over the block when it is on screen, else mid-screen; always wholly on screen.
            val at = LinkerHud.screenPointOf(Vec3.atCenterOf(link.pos).add(0.0, 1.6, 0.0))
            val x = ((at?.x ?: (w.screenWidth / 2f)) - BOX_W / 2f).coerceIn(8f, (w.screenWidth - BOX_W - 8f).coerceAtLeast(8f))
            val y = ((at?.y ?: (w.screenHeight / 2f)) - BOX_H - 6f).coerceIn(8f, (w.screenHeight - BOX_H - 8f).coerceAtLeast(8f))
            ImGui.setCursorScreenPos(x, y)
            ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.09f, 0.10f, 0.13f, 0.96f)
            ImGui.pushStyleColor(ImGuiCol.Border, 0.30f, 1.00f, 0.85f, 0.85f)
            ImGui.beginChild("##bpm-rename", BOX_W, BOX_H, true, FLAGS)
            ImGui.textDisabled("rename '${link.name}'")
            ImGui.setNextItemWidth(-1f)
            if (focus) {
                ImGui.setKeyboardFocusHere()
                focus = false
            }
            val enter = ImGui.inputText("##bpm-rename-name", name, ImGuiInputTextFlags.EnterReturnsTrue or ImGuiInputTextFlags.AutoSelectAll)
            val text = name.get().trim()
            val problem = when {
                text.isEmpty() -> "a name is needed"
                text != link.name && text in taken -> "'$text' is taken"
                else -> null
            }
            if (problem != null) ImGui.textColored(0.95f, 0.4f, 0.4f, 1f, problem) else ImGui.textDisabled("Enter to rename · Esc to cancel")
            if (enter && problem == null) {
                if (text != link.name) ClientNet.editLink(controllerPos, LinkOp.RENAME, link.name, text)
                done = true
            }
            ImGui.endChild()
            ImGui.popStyleColor(2)
        }
    }

    override fun tick() {
        if (state.done) onClose()
    }

    companion object {
        private const val BOX_W = 260f
        private const val BOX_H = 76f
        private const val FLAGS = ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse

        /** Opens the box for [link] of the controller at [controllerPos]; [taken] are the names it cannot take. */
        fun open(controllerPos: BlockPos, link: Link, taken: Set<String>) {
            Minecraft.getInstance().setScreen(LinkRenameScreen(State(controllerPos, link, taken)))
        }
    }
}
