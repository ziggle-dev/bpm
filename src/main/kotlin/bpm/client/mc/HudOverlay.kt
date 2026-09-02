package bpm.client.mc

import bpm.platform.compoundAt
import bpm.net.HudInputPayload
import bpm.net.HudPanelPayload
import bpm.runtime.HudPanels
import bpm.world.devices.Widget
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import bpm.platform.net.Net

/**
 * The panels controllers are drawing on this player's screen — `docs/DESIGN_PLAYER_LINK.md` §10.
 *
 * Drawn straight onto the game's HUD with its own font and item renderer — see [PanelDraw], which is not
 * ImGui, so there is no frame here to collide with the workbench's and nothing to blur when a panel is scaled.
 *
 * Never interactive here: the cursor is locked to the game while the HUD is up, so pressing a button would
 * mean wrestling it away. The panels are a readout in-world; the panel-focus key opens [PanelScreen], which
 * draws the same thing with the cursor free and sends the presses.
 */
object HudOverlay {

    /** One controller's panel, as the client holds it. */
    class Panel(
        val controller: BlockPos,
        val anchor: String,
        val offsetX: Int,
        val offsetY: Int,
        val width: Int,
        val scale: Float,
        val widgets: List<Widget>,
    )

    private val panels = LinkedHashMap<BlockPos, Panel>()

    /** A player may take their screen back; a graph learns that through `hud.visible`. */
    var hidden: Boolean = false

    val all: Collection<Panel> get() = panels.values

    fun onPanel(p: HudPanelPayload) {
        val mc = Minecraft.getInstance()
        mc.execute {
            if (p.widgets.isEmpty()) {
                panels.remove(p.controller)
                return@execute
            }
            val registries = mc.level?.registryAccess() ?: return@execute
            val widgets = (0 until p.widgets.size).mapNotNull { i ->
                runCatching { Widget.load(p.widgets.compoundAt(i), registries) }.getOrNull()
            }
            panels[p.controller] = Panel(p.controller, p.anchor, p.offsetX, p.offsetY, p.width, p.scale, widgets)
            // Oldest out when a player is shown more than they can read.
            while (panels.size > HudPanels.MAX_PER_PLAYER) {
                val first = panels.keys.firstOrNull() ?: break
                panels.remove(first)
            }
        }
    }

    fun reset() {
        panels.clear()
        hidden = false
    }

    /** Hung off the top of the HUD: draws every panel, and nothing else. */
    fun render(g: net.minecraft.client.gui.GuiGraphics, delta: net.minecraft.client.DeltaTracker) {
        if (panels.isEmpty() || hidden) return
        val mc = Minecraft.getInstance()
        // A screen draws its own copy, with a cursor; the HUD would only be a second one underneath.
        if (mc.screen != null || mc.level == null) return
        val w = mc.window
        // -1 for the mouse: the HUD is a readout, and nothing on it can be pressed.
        PanelDraw.drawAll(g, all, w.guiScaledWidth, w.guiScaledHeight, -1, -1)
    }

    /** Send a press home. Called by [PanelScreen], which is the only place a panel can be touched. */
    fun press(controller: BlockPos, id: String) {
        Net.sendToServer(HudInputPayload(controller, id, true, 0f))
    }

    /** Send a toggle's position, or how far along a slider was grabbed. */
    fun setValue(controller: BlockPos, id: String, value: Float) {
        Net.sendToServer(HudInputPayload(controller, id, false, value))
    }

    /** Send what someone typed into a panel's field. */
    fun setText(controller: BlockPos, id: String, text: String) {
        Net.sendToServer(HudInputPayload(controller, id, false, 0f, text))
    }
}
