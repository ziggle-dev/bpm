package bpm.client.mc

import bpm.net.MonitorDragPayload
import bpm.net.MonitorTextPayload
import bpm.world.devices.Widget
import net.minecraft.client.Minecraft
import bpm.platform.client.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import bpm.platform.net.Net
import bpm.platform.client.drawCenteredText

/**
 * The two things a monitor's controls need a client for.
 *
 * A held mouse and a keyboard are client-only facts: the server never sees a drag in progress, and the world
 * has no way to type into a block. Everything else about a monitor's inputs is decided server-side — see
 * `MonitorInput` — and both of these are re-checked there against the sender's own look ray, so neither is
 * taken on trust.
 */
object MonitorDrag {

    /** The last position sent, so holding still on a slider does not fill the wire with the same number. */
    private var lastId: String = ""
    private var lastAlong: Float = -1f

    /**
     * Report where along a slider the cursor is, if it has actually moved.
     *
     * A pixel of slack: a slider is at most a couple of hundred pixels wide, so anything finer than this is
     * a packet describing a change nobody can see.
     */
    fun send(origin: BlockPos, id: String, along: Float) {
        if (id == lastId && kotlin.math.abs(along - lastAlong) < STEP) return
        lastId = id
        lastAlong = along
        Net.sendToServer(MonitorDragPayload(origin, id, along))
    }

    fun forget() {
        lastId = ""
        lastAlong = -1f
    }

    /** Open the box for a monitor's text field; the string goes back when it is confirmed. */
    fun editField(origin: BlockPos, widget: Widget) {
        FieldScreen.open(widget) { text -> Net.sendToServer(MonitorTextPayload(origin, widget.id, text)) }
    }

    private const val STEP = 0.004f
}

/**
 * A one-line box for typing into a monitor's text field.
 *
 * Deliberately plain and small — this is the in-world equivalent of editing a sign, not a form. Enter
 * confirms and sends, Escape leaves the field as it was.
 */
class FieldScreen private constructor(private val widget: Widget, private val send: (String) -> Unit) :
    bpm.platform.client.InputScreen(Component.literal(widget.label.ifEmpty { widget.id })) {


    private lateinit var box: EditBox

    override fun init() {
        super.init()
        box = EditBox(font, width / 2 - 100, height / 2 - 10, 200, 20, Component.literal(""))
        box.setMaxLength(MonitorTextPayload.MAX_TEXT)
        box.value = widget.text
        addRenderableWidget(box)
        setInitialFocus(box)
    }

    override fun onDraw(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.drawCenteredText(font, title, width / 2, height / 2 - 26, 0xFFB8FFF0.toInt())
        g.drawCenteredText(font, HINT, width / 2, height / 2 + 16, 0xFF9AA3B5.toInt())
    }

    override fun onKeyDown(key: Int, scan: Int, modifiers: Int): Boolean {
        if (key != org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && key != org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) return false
        send(box.value)
        onClose()
        return true
    }

    /** The world carries on behind it, as it does for the panels. */
    override fun isPauseScreen(): Boolean = false

    companion object {
        private const val HINT = "Enter to set · Escape to cancel"

        /** Open the box for [widget]; [send] carries the string wherever it belongs. */
        fun open(widget: Widget, send: (String) -> Unit) {
            val mc = Minecraft.getInstance()
            mc.execute { bpm.platform.client.openScreen(FieldScreen(widget, send)) }
        }
    }
}
