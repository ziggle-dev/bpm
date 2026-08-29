package bpm.client.editor

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags
import imgui.flag.ImGuiWindowFlags
import io.osrsx.imgui.PanelBits
import io.osrsx.imgui.Theme

/**
 * The controller's own stores — what the reserved link `self` names: nine item slots, the tanks and the
 * energy cell — drawn as the server last reported them. Slots carry their number because that number is
 * what `Tool` and `Slot` pins take; tanks are gauges in the fluid's own colour; energy is one bar.
 */
class BufferPanel(private val host: WorkbenchHost) {

    /** How tall the panel wants to be for [info]; the links panel takes the rest of the column. */
    fun height(info: ControllerInfo?): Float {
        val slots = info?.buffer?.size?.takeIf { it > 0 } ?: 9
        val rows = (slots + COLS - 1) / COLS
        var h = Chrome.SECTION_H + rows * CELL + GAP
        h += Chrome.SECTION_H + (info?.tanks?.size ?: 0).coerceAtLeast(1) * (BAR_H + GAP) + GAP
        h += Chrome.SECTION_H + BAR_H + GAP * 2
        return h
    }

    fun render(x: Float, y: Float, w: Float, h: Float) {
        val c = host.controller ?: return
        ImGui.setCursorScreenPos(x, y)
        ImGui.beginChild("##bpm-buffer", w, h, false, ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse)
        try {
            val dl = ImGui.getWindowDrawList()
            Chrome.panel(dl, x, y, w, h, edgeRight = false, edgeLeft = true)
            dl.addLine(x, y + h - 0.5f, x + w, y + h - 0.5f, PanelBits.EDGE, 1f)
            val info = c.info
            var cy = y
            cy += Chrome.section(dl, x, cy, w, "Buffer", "self")
            cy = items(dl, x, cy, w, info?.buffer?.takeIf { it.isNotEmpty() } ?: List(9) { "" to 0 })
            cy += Chrome.section(dl, x, cy, w, "Fluids", info?.tanks?.let { "${it.size} tanks" } ?: "")
            cy = fluids(dl, x, cy, w, info?.tanks ?: emptyList())
            cy += Chrome.section(dl, x, cy, w, "Energy", info?.let { "${fmt(it.energyCapacity)} FE" } ?: "")
            energy(dl, x, cy, w, info)
        } finally {
            ImGui.endChild()
        }
    }

    // ---- items ------------------------------------------------------------------------------------------

    private fun items(dl: ImDrawList, x: Float, y: Float, w: Float, buffer: List<Pair<String, Int>>): Float {
        val icons = host.icons
        val lineH = ImGui.getTextLineHeight()
        var bx = x + Chrome.PAD
        var by = y
        var stacks = 0
        var count = 0
        for ((i, slot) in buffer.withIndex()) {
            if (i > 0 && i % COLS == 0) {
                bx = x + Chrome.PAD
                by += CELL
            }
            val x0 = bx
            val y0 = by
            val x1 = bx + CELL - GAP
            val y1 = by + CELL - GAP
            val (id, n) = slot
            val hot = ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(x0, y0, x1, y1)
            dl.addRectFilled(x0, y0, x1, y1, if (hot) Theme.GHOST_HOVER else Chrome.FIELD_BG, 4f, ImDrawFlags.RoundCornersAll)
            dl.addRect(x0, y0, x1, y1, PanelBits.EDGE, 4f, ImDrawFlags.RoundCornersAll, 1f)
            dl.addText(x0 + 4f, y0 + 2f, PanelBits.STAMP, i.toString())
            if (id.isNotEmpty()) {
                stacks++
                count += n
                icons?.want(id)
                val region = icons?.region(id)
                if (region != null) {
                    dl.addImage(region.texture.toLong(), x0 + 6f, y0 + 6f, x1 - 6f, y1 - 6f, region.u0, region.v0, region.u1, region.v1)
                } else {
                    val t = Chrome.fit(id.substringAfter(':'), CELL - GAP - 10f)
                    dl.addText(x0 + 5f, y0 + (CELL - GAP - lineH) * 0.5f, Theme.TEXT_DIM, t)
                }
                if (n > 1) {
                    val t = n.toString()
                    val tw = ImGui.calcTextSize(t).x
                    dl.addRectFilled(x1 - tw - 7f, y1 - lineH - 2f, x1 - 2f, y1 - 2f, Theme.withAlpha(Theme.PANEL_BG, 0.9f), 3f, ImDrawFlags.RoundCornersAll)
                    dl.addText(x1 - tw - 4f, y1 - lineH - 2f, Theme.TEXT, t)
                }
                if (hot) ImGui.setTooltip("slot $i · ${icons?.labelOf(id) ?: id} × $n")
            } else if (hot) {
                ImGui.setTooltip("slot $i · empty")
            }
            bx += CELL
        }
        // The totals beside the grid, where there is room for them.
        val gridW = COLS * CELL
        val tx = x + Chrome.PAD + gridW + 6f
        if (tx + 60f < x + w) {
            PanelBits.label(dl, tx, y, "$stacks / ${buffer.size} slots", PanelBits.STAMP, Chrome.ROW_H)
            PanelBits.label(dl, tx, y + Chrome.ROW_H, "$count items", PanelBits.STAMP, Chrome.ROW_H)
        }
        return by + CELL + GAP
    }

    // ---- fluids -----------------------------------------------------------------------------------------

    private fun fluids(dl: ImDrawList, x: Float, y: Float, w: Float, tanks: List<TankView>): Float {
        val looks = host.fluids
        val gx = x + Chrome.PAD
        val gw = w - Chrome.PAD * 2
        var cy = y
        if (tanks.isEmpty()) {
            PanelBits.label(dl, gx + 4f, cy, "waiting for status…", PanelBits.STAMP, BAR_H)
            return cy + BAR_H + GAP * 2
        }
        for ((i, t) in tanks.withIndex()) {
            val empty = t.fluid.isEmpty() || t.amount <= 0
            val name = if (empty) "empty" else (looks?.labelOf(t.fluid) ?: t.fluid.substringAfter(':'))
            val col = if (empty) Theme.ACCENT else looks?.colour(t.fluid)?.let { Theme.argb(it) } ?: Theme.ACCENT
            gauge(dl, gx, cy, gw, if (empty) 0f else t.amount.toFloat() / t.capacity.coerceAtLeast(1), col, name, if (empty) "${fmt(t.capacity)} mB" else "${fmt(t.amount)} / ${fmt(t.capacity)} mB", empty)
            if (ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(gx, cy, gx + gw, cy + BAR_H)) {
                val extra = if (empty) null else looks?.describe(t.fluid, t.amount)
                ImGui.setTooltip(if (empty) "tank $i · empty" else "tank $i · $name · ${fmt(t.amount)} mB${extra?.let { " · $it" } ?: ""}")
            }
            cy += BAR_H + GAP
        }
        return cy + GAP
    }

    // ---- energy -----------------------------------------------------------------------------------------

    private fun energy(dl: ImDrawList, x: Float, y: Float, w: Float, info: ControllerInfo?) {
        val gx = x + Chrome.PAD
        val gw = w - Chrome.PAD * 2
        if (info == null) {
            PanelBits.label(dl, gx + 4f, y, "waiting for status…", PanelBits.STAMP, BAR_H)
            return
        }
        val empty = info.energy <= 0
        gauge(dl, gx, y, gw, info.energy.toFloat() / info.energyCapacity.coerceAtLeast(1), ENERGY, if (empty) "empty" else "energy", "${fmt(info.energy)} / ${fmt(info.energyCapacity)} FE", empty)
        if (ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(gx, y, gx + gw, y + BAR_H)) {
            ImGui.setTooltip("${fmt(info.energy)} FE of ${fmt(info.energyCapacity)}")
        }
    }

    /** A rounded track with a coloured fill, a lighter meniscus along its top, and a caption at each end. */
    private fun gauge(dl: ImDrawList, x: Float, y: Float, w: Float, fraction: Float, col: Int, left: String, right: String, empty: Boolean) {
        dl.addRectFilled(x, y, x + w, y + BAR_H, Chrome.FIELD_BG, 4f, ImDrawFlags.RoundCornersAll)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            val fw = (w * f).coerceAtLeast(4f)
            dl.addRectFilled(x, y, x + fw, y + BAR_H, Theme.withAlpha(col, 0.78f), 4f, ImDrawFlags.RoundCornersAll)
            dl.addRectFilled(x + 1f, y + 1f, x + fw - 1f, y + 3f, Theme.withAlpha(Theme.shade(col, 1.35f), 0.85f), 2f, ImDrawFlags.RoundCornersAll)
        }
        dl.addRect(x, y, x + w, y + BAR_H, PanelBits.EDGE, 4f, ImDrawFlags.RoundCornersAll, 1f)
        val rw = ImGui.calcTextSize(right).x
        PanelBits.label(dl, x + 8f, y, Chrome.fit(left, w - rw - 24f), if (empty) PanelBits.STAMP else Theme.TEXT, BAR_H)
        PanelBits.label(dl, x + w - 8f - rw, y, right, if (empty) PanelBits.STAMP else Theme.TEXT_DIM, BAR_H)
    }

    private fun fmt(n: Int): String = String.format("%,d", n)

    companion object {
        const val COLS = 3
        const val CELL = 40f
        const val GAP = 4f
        const val BAR_H = 22f
        val ENERGY: Int = Theme.col(0xF2, 0xB1, 0x4C)
    }
}
