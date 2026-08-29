package bpm.client.mc.imgui

import imgui.ImGuiIO
import imgui.callback.ImStrConsumer
import imgui.callback.ImStrSupplier
import net.minecraft.client.Minecraft

/**
 * ImGui's clipboard is Minecraft's clipboard.
 *
 * The GL3 backend installs no clipboard handler (the GLFW one would), and the editor's text fields call
 * `ImGui.getClipboardText()` / `setClipboardText()` directly — so without this, copy and paste inside a node
 * field silently do nothing.
 */
object McClipboard {
    fun install(io: ImGuiIO) {
        io.setGetClipboardTextFn(object : ImStrSupplier() {
            override fun get(): String = runCatching { Minecraft.getInstance().keyboardHandler.clipboard }.getOrDefault("")
        })
        io.setSetClipboardTextFn(object : ImStrConsumer() {
            override fun accept(str: String) {
                runCatching { Minecraft.getInstance().keyboardHandler.setClipboard(str) }
            }
        })
    }
}
