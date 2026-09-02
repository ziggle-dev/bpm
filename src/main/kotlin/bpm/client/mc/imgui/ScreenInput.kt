package bpm.client.mc.imgui

import com.mojang.blaze3d.platform.InputConstants
import imgui.ImGui
import imgui.ImGuiIO
import imgui.flag.ImGuiKey
import dev.ziggle.vscript.editor.host.TypedText
import org.lwjgl.glfw.GLFW

/**
 * Turns what a Minecraft `Screen` is told into what `ImGuiIO` wants to hear.
 *
 * Deliberately not `ImGuiImplGlfw`: that backend installs its own GLFW callbacks on the window, which is
 * exactly what Minecraft's `KeyboardHandler` and `MouseHandler` did first — the old mod attached it and the
 * two fought over every event. A `Screen` already receives everything while it is open, in GUI-scaled
 * coordinates, so the translation is a handful of one-liners plus a key table.
 *
 * [toImGui] converts a GUI-scaled coordinate into the window coordinate the frame was sized in.
 */
class ScreenInput(private val toImGui: (Double) -> Float, private val typed: ScreenTypedText) {

    private fun io(): ImGuiIO? = if (BpmImGui.ready) ImGui.getIO() else null

    fun mouseMoved(x: Double, y: Double) {
        io()?.addMousePosEvent(toImGui(x), toImGui(y))
    }

    fun mouseButton(button: Int, down: Boolean) {
        // GLFW's button numbers are ImGui's: 0 left, 1 right, 2 middle.
        if (button in 0..4) io()?.addMouseButtonEvent(button, down)
    }

    fun scroll(dx: Double, dy: Double) {
        io()?.addMouseWheelEvent(dx.toFloat(), dy.toFloat())
    }

    fun key(glfwKey: Int, down: Boolean, mods: Int) {
        val io = io() ?: return
        val key = GlfwKeys.toImGui(glfwKey)
        if (key != ImGuiKey.None) io.addKeyEvent(key, down)
        // ImGui 1.90 derives io.keyCtrl and friends from Mod key EVENTS, so they are submitted explicitly
        // rather than left to be inferred from the physical keys.
        submitModifiers(io, mods)
    }

    fun charTyped(c: Char) {
        val io = io() ?: return
        // Minecraft hands astral code points over as two calls; the binding pairs the surrogates.
        io.addInputCharacterUTF16(c.code.toShort())
        if (c.code >= 32) typed.append(c)
    }

    /** Mouse events carry no modifier bits, so the state is read once per frame from the window. */
    fun syncModifiers(io: ImGuiIO, window: Long) {
        io.addKeyEvent(ImGuiKey.ModCtrl, isDown(window, GLFW.GLFW_KEY_LEFT_CONTROL) || isDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL))
        io.addKeyEvent(ImGuiKey.ModShift, isDown(window, GLFW.GLFW_KEY_LEFT_SHIFT) || isDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT))
        io.addKeyEvent(ImGuiKey.ModAlt, isDown(window, GLFW.GLFW_KEY_LEFT_ALT) || isDown(window, GLFW.GLFW_KEY_RIGHT_ALT))
        io.addKeyEvent(ImGuiKey.ModSuper, isDown(window, GLFW.GLFW_KEY_LEFT_SUPER) || isDown(window, GLFW.GLFW_KEY_RIGHT_SUPER))
    }

    fun focusLost() {
        io()?.clearInputKeys()
        typed.clear()
    }

    private fun submitModifiers(io: ImGuiIO, mods: Int) {
        io.addKeyEvent(ImGuiKey.ModCtrl, mods and GLFW.GLFW_MOD_CONTROL != 0)
        io.addKeyEvent(ImGuiKey.ModShift, mods and GLFW.GLFW_MOD_SHIFT != 0)
        io.addKeyEvent(ImGuiKey.ModAlt, mods and GLFW.GLFW_MOD_ALT != 0)
        io.addKeyEvent(ImGuiKey.ModSuper, mods and GLFW.GLFW_MOD_SUPER != 0)
    }

    private fun isDown(window: Long, key: Int) = bpm.platform.client.isKeyHeld(key)
}

/**
 * The characters typed since the editor last drained them.
 *
 * imgui-java does not expose `InputQueueCharacters`, and the canvas draws its text fields by hand, so the host
 * keeps the typed text itself and hands it over through `EditorHost.typed` — the same shape the OSRS client's
 * AWT bridge has. Same thread throughout (Minecraft's client thread renders the screen), so no lock.
 */
class ScreenTypedText : TypedText {
    private val buffer = StringBuilder()

    fun append(c: Char) {
        buffer.append(c)
    }

    override fun drain(): String {
        val s = buffer.toString()
        buffer.setLength(0)
        return s
    }

    override fun clear() {
        buffer.setLength(0)
    }
}

/** GLFW key codes → `ImGuiKey`. Mirrors vscript's AWT table, for the codes Minecraft delivers. */
object GlfwKeys {
    fun toImGui(k: Int): Int = when (k) {
        in GLFW.GLFW_KEY_A..GLFW.GLFW_KEY_Z -> ImGuiKey.A + (k - GLFW.GLFW_KEY_A)
        in GLFW.GLFW_KEY_0..GLFW.GLFW_KEY_9 -> ImGuiKey._0 + (k - GLFW.GLFW_KEY_0)
        in GLFW.GLFW_KEY_F1..GLFW.GLFW_KEY_F12 -> ImGuiKey.F1 + (k - GLFW.GLFW_KEY_F1)
        in GLFW.GLFW_KEY_KP_0..GLFW.GLFW_KEY_KP_9 -> ImGuiKey.Keypad0 + (k - GLFW.GLFW_KEY_KP_0)
        GLFW.GLFW_KEY_KP_DECIMAL -> ImGuiKey.KeypadDecimal
        GLFW.GLFW_KEY_KP_DIVIDE -> ImGuiKey.KeypadDivide
        GLFW.GLFW_KEY_KP_MULTIPLY -> ImGuiKey.KeypadMultiply
        GLFW.GLFW_KEY_KP_SUBTRACT -> ImGuiKey.KeypadSubtract
        GLFW.GLFW_KEY_KP_ADD -> ImGuiKey.KeypadAdd
        GLFW.GLFW_KEY_KP_ENTER -> ImGuiKey.KeypadEnter
        GLFW.GLFW_KEY_KP_EQUAL -> ImGuiKey.KeypadEqual
        GLFW.GLFW_KEY_SPACE -> ImGuiKey.Space
        GLFW.GLFW_KEY_APOSTROPHE -> ImGuiKey.Apostrophe
        GLFW.GLFW_KEY_COMMA -> ImGuiKey.Comma
        GLFW.GLFW_KEY_MINUS -> ImGuiKey.Minus
        GLFW.GLFW_KEY_PERIOD -> ImGuiKey.Period
        GLFW.GLFW_KEY_SLASH -> ImGuiKey.Slash
        GLFW.GLFW_KEY_SEMICOLON -> ImGuiKey.Semicolon
        GLFW.GLFW_KEY_EQUAL -> ImGuiKey.Equal
        GLFW.GLFW_KEY_LEFT_BRACKET -> ImGuiKey.LeftBracket
        GLFW.GLFW_KEY_BACKSLASH -> ImGuiKey.Backslash
        GLFW.GLFW_KEY_RIGHT_BRACKET -> ImGuiKey.RightBracket
        GLFW.GLFW_KEY_GRAVE_ACCENT -> ImGuiKey.GraveAccent
        GLFW.GLFW_KEY_ESCAPE -> ImGuiKey.Escape
        GLFW.GLFW_KEY_ENTER -> ImGuiKey.Enter
        GLFW.GLFW_KEY_TAB -> ImGuiKey.Tab
        GLFW.GLFW_KEY_BACKSPACE -> ImGuiKey.Backspace
        GLFW.GLFW_KEY_INSERT -> ImGuiKey.Insert
        GLFW.GLFW_KEY_DELETE -> ImGuiKey.Delete
        GLFW.GLFW_KEY_RIGHT -> ImGuiKey.RightArrow
        GLFW.GLFW_KEY_LEFT -> ImGuiKey.LeftArrow
        GLFW.GLFW_KEY_DOWN -> ImGuiKey.DownArrow
        GLFW.GLFW_KEY_UP -> ImGuiKey.UpArrow
        GLFW.GLFW_KEY_PAGE_UP -> ImGuiKey.PageUp
        GLFW.GLFW_KEY_PAGE_DOWN -> ImGuiKey.PageDown
        GLFW.GLFW_KEY_HOME -> ImGuiKey.Home
        GLFW.GLFW_KEY_END -> ImGuiKey.End
        GLFW.GLFW_KEY_CAPS_LOCK -> ImGuiKey.CapsLock
        GLFW.GLFW_KEY_SCROLL_LOCK -> ImGuiKey.ScrollLock
        GLFW.GLFW_KEY_NUM_LOCK -> ImGuiKey.NumLock
        GLFW.GLFW_KEY_PRINT_SCREEN -> ImGuiKey.PrintScreen
        GLFW.GLFW_KEY_PAUSE -> ImGuiKey.Pause
        GLFW.GLFW_KEY_LEFT_SHIFT -> ImGuiKey.LeftShift
        GLFW.GLFW_KEY_LEFT_CONTROL -> ImGuiKey.LeftCtrl
        GLFW.GLFW_KEY_LEFT_ALT -> ImGuiKey.LeftAlt
        GLFW.GLFW_KEY_LEFT_SUPER -> ImGuiKey.LeftSuper
        GLFW.GLFW_KEY_RIGHT_SHIFT -> ImGuiKey.RightShift
        GLFW.GLFW_KEY_RIGHT_CONTROL -> ImGuiKey.RightCtrl
        GLFW.GLFW_KEY_RIGHT_ALT -> ImGuiKey.RightAlt
        GLFW.GLFW_KEY_RIGHT_SUPER -> ImGuiKey.RightSuper
        GLFW.GLFW_KEY_MENU -> ImGuiKey.Menu
        else -> ImGuiKey.None
    }
}
