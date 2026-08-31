package bpm.runtime

import bpm.world.devices.Widget
import net.minecraft.core.BlockPos
import java.util.UUID

/**
 * The panels one controller is drawing on people's screens, and what they pressed back.
 *
 * Lives on the running controller, so a stop takes every panel down with it — a graph that is not running
 * should not still be on your screen. See `docs/DESIGN_PLAYER_LINK.md` §10.
 *
 * `on tick` runs twenty times a second and a graph *will* call `hud.show` every pass, so [show] answers with a
 * panel only when something is actually different: an identical list is never sent, and a changed one at most
 * once every [MIN_TICKS] ticks. Server thread only.
 */
class HudPanels(private val controller: () -> BlockPos) {

    class Panel(
        var widgets: List<Widget>,
        var anchor: String,
        var offsetX: Int,
        var offsetY: Int,
        var width: Int,
        var scale: Double,
    ) {
        /** The tick this was last put on the wire, so an eager graph cannot flood one player. */
        var sentAt: Long = Long.MIN_VALUE

        /** When the graph last asked for it, and how long it may outlive that — see [HudPanels.expired]. */
        var shownAt: Long = Long.MIN_VALUE
        var timeout: Int = DEFAULT_TIMEOUT

        /** Presses not yet read by a node, and the current value of every toggle, by widget id. */
        val pressed = HashSet<String>()
        val values = HashMap<String, Double>()
        val texts = HashMap<String, String>()

        /**
         * Whether it is actually on screen.
         *
         * True from the moment it is sent: the client only speaks up to say a player has *hidden* bpm's
         * panels. "Sent, and nothing has said otherwise" is the honest reading, and it is what a graph
         * drawing a warning needs to know.
         */
        var visible: Boolean = true
    }

    private val byPlayer = HashMap<UUID, Panel>()

    val players: Set<UUID> get() = byPlayer.keys

    operator fun get(player: UUID): Panel? = byPlayer[player]

    /**
     * Put [widgets] on [player]'s screen. Answers the panel when something actually needs sending — a
     * different list, or a moved one — and null when this call changed nothing worth a packet.
     */
    fun show(
        player: UUID,
        widgets: List<Widget>,
        anchor: String,
        offsetX: Int,
        offsetY: Int,
        width: Int,
        scale: Double,
        timeoutTicks: Int,
        now: Long,
    ): Panel? {
        val existing = byPlayer[player]
        if (existing == null) {
            val made = Panel(widgets, anchor, offsetX, offsetY, width, scale)
            made.sentAt = now
            made.shownAt = now
            made.timeout = timeoutTicks.coerceAtLeast(0)
            byPlayer[player] = made
            seedToggles(made)
            return made
        }
        // Stamped before the comparison: a panel whose contents held steady is still being kept alive by
        // the graph asking for it, and expiring it for that would blank the displays that are working.
        existing.shownAt = now
        existing.timeout = timeoutTicks.coerceAtLeast(0)
        val same = existing.anchor == anchor && existing.offsetX == offsetX && existing.offsetY == offsetY &&
            existing.width == width && existing.scale == scale && sameWidgets(existing.widgets, widgets)
        existing.widgets = widgets
        existing.anchor = anchor
        existing.offsetX = offsetX
        existing.offsetY = offsetY
        existing.width = width
        existing.scale = scale
        seedToggles(existing)
        if (same) return null
        if (now - existing.sentAt < MIN_TICKS) return null
        existing.sentAt = now
        return existing
    }

    /** Take [player]'s panel down. Answers whether there was one. */
    fun clear(player: UUID): Boolean = byPlayer.remove(player) != null

    /** A press came back from a client. */
    fun press(player: UUID, id: String) {
        byPlayer[player]?.pressed?.add(id)
    }

    /** A toggle or a slider moved on a client. */
    fun setValue(player: UUID, id: String, value: Double) {
        byPlayer[player]?.values?.put(id, value)
    }

    fun setVisible(player: UUID, visible: Boolean) {
        byPlayer[player]?.visible = visible
    }

    /** Was [id] pressed since anything last asked — and if so, take it. */
    fun takePress(player: UUID, id: String): Boolean = byPlayer[player]?.pressed?.remove(id) == true

    fun valueOf(player: UUID, id: String): Double = byPlayer[player]?.values?.get(id) ?: 0.0

    fun setText(player: UUID, id: String, text: String) {
        byPlayer[player]?.texts?.put(id, text)
    }

    fun textOf(player: UUID, id: String): String = byPlayer[player]?.texts?.get(id) ?: ""

    /**
     * Whoever's panel nothing has refreshed for its timeout — `hud.show` is a heartbeat, not a one-shot.
     *
     * A graph that stops drawing, or takes a branch that no longer does, would otherwise leave its last frame
     * on someone's screen forever; stale numbers that look live are worse than no numbers. A timeout of 0
     * means the panel stays until something replaces it.
     */
    fun expired(now: Long): List<UUID> =
        byPlayer.entries.filter { (_, p) -> p.timeout > 0 && now - p.shownAt > p.timeout }.map { it.key }

    /** Everyone with a panel, for taking them all down when the controller stops. */
    fun all(): Map<UUID, Panel> = byPlayer

    fun clearAll() = byPlayer.clear()

    /**
     * A control the graph declared but nobody has touched starts where the graph put it. Once someone has,
     * the panel owns it: redrawing every tick must not snap a switch back under their hand.
     */
    private fun seedToggles(panel: Panel) {
        for (w in panel.widgets) {
            if (w.id.isEmpty()) continue
            when (w.kind) {
                Widget.TOGGLE, Widget.SLIDER -> if (w.id !in panel.values) panel.values[w.id] = w.value
                Widget.FIELD -> if (w.id !in panel.texts) panel.texts[w.id] = w.text
            }
        }
    }

    private fun sameWidgets(a: List<Widget>, b: List<Widget>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (!a[i].sameAs(b[i])) return false
        return true
    }

    @Suppress("unused")
    private fun where(): BlockPos = controller()

    companion object {
        /** At most one update per player per this many ticks — `on tick` is twenty a second. */
        const val MIN_TICKS = 2L

        /** At most this many panels a player may be shown at once, across every controller. */
        const val MAX_PER_PLAYER = 4

        /** How long a panel outlives the graph that drew it — one second, as a monitor's screen does. */
        const val DEFAULT_TIMEOUT = 20
    }
}
