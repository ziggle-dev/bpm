package bpm.client.mc

import bpm.world.devices.Widget
import net.minecraft.client.Minecraft
import bpm.platform.client.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * The panels, with the cursor free, so they can be pressed.
 *
 * The design first had this as "hold a key and the panel becomes clickable", which needs the cursor wrestled
 * away from a locked game mid-frame. A screen is what Minecraft already has for "give the player their mouse
 * back": the focus key opens this, Escape closes it.
 *
 * A plain [Screen], not the editor's ImGui one — [PanelDraw] draws with the game's own font and item renderer,
 * so this needs nothing but `render` and `mouseClicked`, and the mouse arrives already GUI-scaled.
 *
 * The world keeps running behind it: a panel that paused the game would be useless for watching a graph.
 */
class PanelScreen : bpm.platform.client.InputScreen(Component.literal("bpm")) {

    /** What the last frame drew under the cursor, so a click knows what it landed on. */
    private var hover: PanelDraw.Hit? = null

    override fun onDraw(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        hover = PanelDraw.drawAll(g, HudOverlay.all, width, height, mouseX, mouseY)
    }

    override fun onMouseDown(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val hit = hover
        if (button == 0 && hit != null && hit.widget.id.isNotEmpty()) {
            when (hit.widget.kind) {
                Widget.BUTTON -> HudOverlay.press(hit.controller, hit.widget.id)
                // Flipped here so the switch answers the finger at once; the server is told, and its own copy
                // is what a graph reads.
                Widget.TOGGLE -> HudOverlay.setValue(hit.controller, hit.widget.id, if (hit.widget.value >= 0.5) 0f else 1f)
                // The FRACTION, not the number: the server owns the slider's range.
                Widget.SLIDER -> HudOverlay.setValue(hit.controller, hit.widget.id, hit.along.toFloat())
                Widget.FIELD -> {
                    val target = hit.controller
                    val id = hit.widget.id
                    FieldScreen.open(hit.widget) { text -> HudOverlay.setText(target, id, text) }
                    return true
                }
                else -> return false
            }
            Minecraft.getInstance().soundManager.play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f),
            )
            return true
        }
        return false
    }

    /**
     * Dragging a slider: the same fraction the first click sent, streamed while the mouse is down.
     *
     * The panel is a screen, so this is just the mouse — none of the held-click machinery a monitor needs to
     * follow a drag on a block in the world.
     */
    override fun onMouseDrag(x: Double, y: Double, button: Int, dx: Double, dy: Double): Boolean {
        val hit = hover
        if (button != 0 || hit == null || hit.widget.kind != Widget.SLIDER || hit.widget.id.isEmpty()) return false
        HudOverlay.setValue(hit.controller, hit.widget.id, hit.along.toFloat())
        return true
    }

    /** The world is what the player is watching; dimming it would defeat the point. */
    override fun onBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) = Unit

    override fun isPauseScreen(): Boolean = false

    companion object {
        /** Whether there is anything to open for — an empty screen would just be a way to lose the cursor. */
        fun hasPanels(): Boolean = HudOverlay.all.isNotEmpty() && !HudOverlay.hidden

        fun open() {
            if (!hasPanels()) return
            bpm.platform.client.openScreen(PanelScreen())
        }
    }
}
