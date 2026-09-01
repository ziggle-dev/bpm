package bpm.client.editor

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImDrawFlags
import imgui.flag.ImGuiMouseButton
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.PanelBits
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.editor.graph.PanelField

/**
 * The look of the bpm panels: the same hand-drawn chrome as vscript's outline and drawer — flat panel
 * background, hairline edges, muted section captions, ghost-highlighted rows, pill toggles, tinted action
 * chips and rounded search fields. Nothing here is a stock ImGui widget; ImGui only supplies hit-testing.
 */
object Chrome {
    const val ROW_H = 21f
    const val PAD = 10f
    const val SECTION_H = 26f
    const val FIELD_H = 24f
    const val HEAD_H = 38f

    val SECTION_TEXT: Int = Theme.col(0x8F, 0x9A, 0xAD)
    val FIELD_BG: Int = Theme.col(0xFF, 0xFF, 0xFF, 0x0D)
    val SEL_BG: Int = Theme.col(0x4A, 0x7F, 0xD4, 0x24)

    /** Panel background with a hairline on the side facing the canvas. */
    fun panel(dl: ImDrawList, x: Float, y: Float, w: Float, h: Float, edgeRight: Boolean = true, edgeLeft: Boolean = false, edgeTop: Boolean = false) {
        dl.addRectFilled(x, y, x + w, y + h, PanelBits.BG)
        if (edgeRight) dl.addLine(x + w - 0.5f, y, x + w - 0.5f, y + h, PanelBits.EDGE, 1f)
        if (edgeLeft) dl.addLine(x + 0.5f, y, x + 0.5f, y + h, PanelBits.EDGE, 1f)
        if (edgeTop) dl.addLine(x, y + 0.5f, x + w, y + 0.5f, PanelBits.EDGE, 1f)
    }

    /** A section caption with a count and an optional `+` on hover, as the outline draws them. */
    fun section(dl: ImDrawList, x: Float, y: Float, w: Float, title: String, count: String = "", onAdd: (() -> Unit)? = null, addTip: String = ""): Float {
        val hot = ImGui.isMouseHoveringRect(x, y, x + w, y + SECTION_H)
        var cx = x + PAD
        cx += PanelBits.label(dl, cx, y, title, SECTION_TEXT, SECTION_H) + 7f
        if (count.isNotEmpty()) PanelBits.label(dl, cx, y, count, PanelBits.STAMP, SECTION_H)
        if (onAdd != null && (hot || addTip.isEmpty())) {
            val px = x + w - PAD - 12f
            ImGui.setCursorScreenPos(px - 4f, y)
            if (ImGui.invisibleButton("##add-$title", 20f, SECTION_H)) onAdd()
            val over = ImGui.isItemHovered()
            if (over && addTip.isNotEmpty()) ImGui.setTooltip(addTip)
            PanelBits.label(dl, px, y, "+", if (over) Theme.TEXT else PanelBits.STAMP, SECTION_H)
        }
        return SECTION_H
    }

    /** Row background: selected wash with an accent bar, or a ghost on hover. */
    fun rowBg(dl: ImDrawList, x: Float, y: Float, w: Float, selected: Boolean, hot: Boolean, h: Float = ROW_H) {
        if (selected) {
            dl.addRectFilled(x, y, x + w, y + h, SEL_BG)
            dl.addRectFilled(x, y, x + 2f, y + h, Theme.ACCENT)
        } else if (hot) {
            dl.addRectFilled(x, y, x + w, y + h, Theme.GHOST_REST)
        }
    }

    /** True while the mouse is over the row and the surrounding window is the one hovered. */
    fun rowHot(x: Float, y: Float, w: Float, h: Float = ROW_H): Boolean =
        ImGui.isWindowHovered() && ImGui.isMouseHoveringRect(x, y, x + w, y + h)

    fun clicked(): Boolean = ImGui.isMouseClicked(ImGuiMouseButton.Left) && !ImGui.isAnyItemHovered()
    fun doubleClicked(): Boolean = ImGui.isMouseDoubleClicked(ImGuiMouseButton.Left)
    fun rightClicked(): Boolean = ImGui.isMouseClicked(ImGuiMouseButton.Right)

    /** A rounded search field with a magnifier, live filtering into [field]. */
    fun searchField(dl: ImDrawList, field: PanelField, x: Float, y: Float, w: Float, placeholder: String = "Filter") {
        dl.addRectFilled(x, y, x + w, y + FIELD_H, FIELD_BG, 5f, ImDrawFlags.RoundCornersAll)
        val glyph = PanelBits.icon(Fonts.SEARCH)
        val gx = x + 8f
        dl.addText(gx, y + (FIELD_H - ImGui.getTextLineHeight()) * 0.5f, PanelBits.STAMP, glyph)
        val textX = gx + ImGui.calcTextSize(glyph).x + 7f
        field.render(dl, textX, y, x + w - textX, FIELD_H, placeholder, live = true, frame = false)
        if (field.focused) dl.addRect(x, y, x + w, y + FIELD_H, Theme.ACCENT, 5f, ImDrawFlags.RoundCornersAll, 1f)
    }

    /** A rounded text field that answers the committed text (Enter) or null. */
    fun textField(dl: ImDrawList, field: PanelField, x: Float, y: Float, w: Float, placeholder: String = ""): String? {
        dl.addRectFilled(x, y, x + w, y + FIELD_H, FIELD_BG, 5f, ImDrawFlags.RoundCornersAll)
        val out = field.render(dl, x + 8f, y, w - 16f, FIELD_H, placeholder, live = false, frame = false)
        dl.addRect(x, y, x + w, y + FIELD_H, if (field.focused) Theme.ACCENT else PanelBits.EDGE, 5f, ImDrawFlags.RoundCornersAll, 1f)
        return out
    }

    /** A tinted action chip; when not [enabled] it is drawn faded and ignores the click. */
    fun action(dl: ImDrawList, id: String, x: Float, y: Float, glyph: String, label: String, tint: Int, enabled: Boolean = true, tip: String = ""): Boolean {
        if (enabled) {
            val clicked = PanelBits.action(dl, id, x, y, glyph, label, tint)
            if (tip.isNotEmpty() && ImGui.isItemHovered()) ImGui.setTooltip(tip)
            return clicked
        }
        val w = PanelBits.actionWidth(glyph, label)
        val h = 20f
        val py = y + (PanelBits.HEADER_H - h) * 0.5f
        dl.addRect(x, py, x + w, py + h, Theme.withAlpha(tint, 0.25f), 4f, ImDrawFlags.RoundCornersAll, 1f)
        val fg = Theme.withAlpha(tint, 0.35f)
        val gs = ImGui.calcTextSize(glyph)
        dl.addText(x + 8f, y + (PanelBits.HEADER_H - gs.y) * 0.5f, fg, glyph)
        dl.addText(x + 8f + gs.x + 5f, y + (PanelBits.HEADER_H - gs.y) * 0.5f, fg, label)
        if (tip.isNotEmpty() && ImGui.isMouseHoveringRect(x, py, x + w, py + h)) ImGui.setTooltip(tip)
        return false
    }

    /** A small trash glyph at the right of a row; true when clicked. */
    fun rowTrash(dl: ImDrawList, id: String, x: Float, y: Float, tip: String): Boolean {
        ImGui.setCursorScreenPos(x - 4f, y)
        val clicked = ImGui.invisibleButton(id, 20f, ROW_H)
        val hot = ImGui.isItemHovered()
        if (hot && tip.isNotEmpty()) ImGui.setTooltip(tip)
        PanelBits.label(dl, x, y, PanelBits.icon(Fonts.TRASH), if (hot) PanelBits.ERROR else PanelBits.STAMP, ROW_H)
        return clicked
    }

    /** A coloured dot followed by text, for statuses. */
    fun dotLabel(dl: ImDrawList, x: Float, y: Float, text: String, col: Int, rowH: Float = ROW_H): Float {
        dl.addCircleFilled(x + 5f, y + rowH * 0.5f, 4f, col, 12)
        return 14f + PanelBits.label(dl, x + 14f, y, text, Theme.TEXT, rowH)
    }

    /** Text cut with an ellipsis to fit [maxW]. */
    fun fit(text: String, maxW: Float): String {
        if (ImGui.calcTextSize(text).x <= maxW) return text
        var n = text.length
        while (n > 1 && ImGui.calcTextSize(text.substring(0, n) + "…").x > maxW) n--
        return text.substring(0, n) + "…"
    }

    fun statusColour(status: String): Int = when (status) {
        "running" -> Theme.OK
        "error" -> Theme.BAD
        "asleep" -> Theme.ACCENT
        else -> PanelBits.MUTED
    }
}
