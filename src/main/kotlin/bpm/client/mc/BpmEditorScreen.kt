package bpm.client.mc

import bpm.client.mc.imgui.BpmImGui
import bpm.client.mc.imgui.ScreenInput
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * A Minecraft screen that is one ImGui frame per render.
 *
 * The screen is an input sink and a frame driver and nothing else: Minecraft routes every event to the open
 * screen, the screen hands them to [ScreenInput], and `render` draws [body] through [BpmImGui]. The world
 * keeps ticking behind it (`isPauseScreen` is false — the editor watches running graphs), and Escape is the
 * screen's to interpret, not Minecraft's, because a picker or a text field may want it first.
 *
 * Coordinates: Minecraft gives a screen GUI-scaled positions; the ImGui frame is sized in window units, so
 * a position is multiplied by `screenWidth / guiScaledWidth` on the way in.
 */
open class BpmEditorScreen(
    private val body: () -> Unit,
    /** Whether the body wants Escape this frame; when false the screen closes. */
    private val wantsEscape: () -> Boolean = { false },
    private val onFrame: (Int) -> Unit = {},
    /** Whether the world behind is dimmed and blurred; a small box hung over a block leaves it as it is. */
    private val dimsWorld: Boolean = true,
) : bpm.platform.client.InputScreen(Component.literal("bpm")) {

    private val input = ScreenInput({ v -> (v * pixelsPerGuiUnit()).toFloat() }, BpmImGui.typed)
    private var frames = 0

    private fun pixelsPerGuiUnit(): Double {
        val w = minecraft?.window ?: return 1.0
        return w.screenWidth.toDouble() / w.guiScaledWidth.toDouble()
    }

    override fun init() {
        BpmImGui.ensure()
        BpmImGui.resetClock()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (dimsWorld) renderBackground(graphics, mouseX, mouseY, partialTick)
        // Minecraft batches its GUI draws; flush them before raw GL draws over the top.
        graphics.flush()
        val w = minecraft!!.window
        val fbScale = w.width.toFloat() / w.screenWidth.toFloat()
        BpmImGui.frame(
            w.screenWidth.toFloat(), w.screenHeight.toFloat(), fbScale,
            pump = { io -> input.syncModifiers(io, w.window) },
            bareRoot = !dimsWorld,
            body = body,
        )
        frames++
        onFrame(frames)
    }

    override fun mouseMoved(mouseX: Double, mouseY: Double) {
        input.mouseMoved(mouseX, mouseY)
    }

    override fun onMouseDown(x: Double, y: Double, button: Int): Boolean {
        input.mouseMoved(x, y)
        input.mouseButton(button, true)
        return true
    }

    override fun onMouseUp(x: Double, y: Double, button: Int): Boolean {
        input.mouseButton(button, false)
        return true
    }

    override fun onMouseDrag(x: Double, y: Double, button: Int, dx: Double, dy: Double): Boolean {
        input.mouseMoved(x, y)
        return true
    }

    override fun onScroll(x: Double, y: Double, dx: Double, dy: Double): Boolean {
        input.scroll(dx, dy)
        return true
    }

    override fun onKeyDown(key: Int, scan: Int, modifiers: Int): Boolean {
        if (key == GLFW.GLFW_KEY_ESCAPE && !wantsEscape()) {
            onClose()
            return true
        }
        input.key(key, true, modifiers)
        return true
    }

    override fun onKeyUp(key: Int, scan: Int, modifiers: Int): Boolean {
        input.key(key, false, modifiers)
        return true
    }

    override fun onCharTyped(codePoint: Char, modifiers: Int): Boolean {
        input.charTyped(codePoint)
        return true
    }

    override fun shouldCloseOnEsc(): Boolean = false

    override fun isPauseScreen(): Boolean = false

    override fun removed() {
        input.focusLost()
    }
}
