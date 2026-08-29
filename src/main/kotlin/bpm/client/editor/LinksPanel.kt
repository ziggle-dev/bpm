package bpm.client.editor

import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import io.osrsx.imgui.PanelBits
import io.osrsx.imgui.Theme
import io.osrsx.vscript.editor.graph.PanelField

/**
 * The controller the workbench is attached to: what it runs, whether it runs, and its link table (rename
 * from the row menu, remove with the trash; linking itself is done in the world with the Quantum Linker).
 */
class LinksPanel(private val host: WorkbenchHost) {
    /** A link released over the canvas: the workbench turns it into a `Link` node. */
    class LinkDrop(val name: String, val screenX: Float, val screenY: Float)

    var drop: LinkDrop? = null
    private var dragging: String? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragArmed = false
    private var selected: String? = null
    private var renaming: String? = null
    private var menuFor: String? = null
    private val nameField = PanelField("bpm-link-name")

    fun render(x: Float, y: Float, w: Float, h: Float, current: OpenDoc?) {
        val c = host.controller ?: return
        ImGui.setCursorScreenPos(x, y)
        ImGui.beginChild("##bpm-links", w, h, false, ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse)
        try {
            val dl = ImGui.getWindowDrawList()
            Chrome.panel(dl, x, y, w, h, edgeRight = false, edgeLeft = true)
            var cy = y
            cy += Chrome.section(dl, x, cy, w, "Controller")
            val lx = x + Chrome.PAD + 4f
            PanelBits.label(dl, lx, cy, Chrome.fit(c.label, w - Chrome.PAD * 2), Theme.TEXT, Chrome.ROW_H)
            cy += Chrome.ROW_H
            val info = c.info
            if (info == null) {
                PanelBits.label(dl, lx, cy, "waiting for status…", PanelBits.STAMP, Chrome.ROW_H)
                cy += Chrome.ROW_H
            } else {
                Chrome.dotLabel(dl, lx, cy, info.status, Chrome.statusColour(info.status))
                cy += Chrome.ROW_H
                if (info.docId != null) {
                    PanelBits.label(dl, lx, cy, "runs", PanelBits.STAMP, Chrome.ROW_H)
                    PanelBits.label(dl, lx + 44f, cy, Chrome.fit("${info.docName}  v${info.runningVersion}", w - Chrome.PAD * 2 - 48f), Theme.TEXT_DIM, Chrome.ROW_H)
                    cy += Chrome.ROW_H
                    if (info.runningVersion != info.docVersion && (info.status == "running" || info.status == "asleep")) {
                        PanelBits.label(dl, lx + 44f, cy, "v${info.docVersion} not deployed yet", PanelBits.WARN_TEXT, Chrome.ROW_H)
                        cy += Chrome.ROW_H
                    }
                }
                if (info.fibers > 0 || info.jobs > 0 || info.transfers > 0) {
                    PanelBits.label(dl, lx, cy, "${info.fibers} fibers · ${info.jobs} jobs · ${info.transfers} items", PanelBits.STAMP, Chrome.ROW_H)
                    cy += Chrome.ROW_H
                }
                info.lastError?.let { err ->
                    dl.addRectFilled(x, cy, x + w, cy + Chrome.ROW_H, PanelBits.ERROR_ROW)
                    PanelBits.label(dl, lx, cy, Chrome.fit(err, w - Chrome.PAD * 2), PanelBits.ERROR_TEXT, Chrome.ROW_H)
                    if (Chrome.rowHot(x, cy, w)) ImGui.setTooltip(err)
                    cy += Chrome.ROW_H
                }
            }
            cy += 6f
            dl.addLine(x, cy + 0.5f, x + w, cy + 0.5f, PanelBits.ROW_LINE, 1f)

            val links = c.links
            cy += Chrome.section(dl, x, cy, w, "Links", links.size.toString())
            ImGui.setCursorScreenPos(x, cy)
            ImGui.beginChild("##bpm-links-list", w, (y + h - cy).coerceAtLeast(Chrome.ROW_H), false, ImGuiWindowFlags.NoScrollbar)
            try {
                val ldl = ImGui.getWindowDrawList()
                val rx = ImGui.getCursorScreenPosX()
                var ry = ImGui.getCursorScreenPosY()
                if (links.isEmpty()) {
                    PanelBits.label(ldl, rx + Chrome.PAD + 14f, ry, "none — use the Quantum Linker", PanelBits.STAMP, Chrome.ROW_H)
                }
                for (l in links) {
                    val isSel = l.name == selected
                    val hot = Chrome.rowHot(rx, ry, w)
                    Chrome.rowBg(ldl, rx, ry, w, isSel, hot)
                    if (renaming == l.name) {
                        Chrome.textField(ldl, nameField, rx + Chrome.PAD, ry + 1f, w - Chrome.PAD * 2, l.name)?.let { name ->
                            if (name.trim().isNotEmpty() && name.trim() != l.name) c.renameLink(l.name, name.trim())
                            renaming = null
                        }
                        if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape)) renaming = null
                        ry += Chrome.ROW_H
                        continue
                    }
                    ldl.addCircleFilled(rx + Chrome.PAD + 4f, ry + Chrome.ROW_H * 0.5f, 3.5f, Theme.col(0x4d, 0xff, 0xd8), 12)
                    PanelBits.label(ldl, rx + Chrome.PAD + 14f, ry, Chrome.fit(l.name, w * 0.5f), if (isSel) Theme.TEXT else Theme.TEXT_DIM, Chrome.ROW_H)
                    val pos = "${l.x}, ${l.y}, ${l.z}"
                    PanelBits.label(ldl, rx + w - Chrome.PAD - 22f - ImGui.calcTextSize(pos).x - 6f, ry, pos, PanelBits.STAMP, Chrome.ROW_H)
                    if (hot && dragging == null) preview(ldl, l, rx, ry, w)
                    if (hot && ImGui.isMouseClicked(imgui.flag.ImGuiMouseButton.Left)) {
                        dragArmed = true
                        dragging = l.name
                        dragStartX = ImGui.getMousePosX()
                        dragStartY = ImGui.getMousePosY()
                    }
                    if (Chrome.rowTrash(ldl, "##bpm-link-rm-${l.name}", rx + w - Chrome.PAD - 16f, ry, "Remove the link")) {
                        c.removeLink(l.name)
                        if (selected == l.name) selected = null
                    }
                    if (hot && Chrome.clicked()) selected = if (isSel) null else l.name
                    if (hot && Chrome.doubleClicked()) {
                        nameField.set(l.name)
                        renaming = l.name
                    }
                    if (hot && Chrome.rightClicked()) {
                        selected = l.name
                        menuFor = l.name
                        ImGui.openPopup(ROW_MENU)
                    }
                    ry += Chrome.ROW_H
                }
                ImGui.dummy(1f, (ry - ImGui.getCursorScreenPosY()).coerceAtLeast(1f))
                dragChip()
                if (ImGui.beginPopup(ROW_MENU)) {
                    val name = menuFor
                    if (name == null || links.none { it.name == name }) {
                        ImGui.textDisabled("gone")
                    } else {
                        if (ImGui.menuItem("Rename")) {
                            nameField.set(name)
                            renaming = name
                        }
                        if (ImGui.menuItem("Remove")) c.removeLink(name)
                    }
                    ImGui.endPopup()
                }
            } finally {
                ImGui.endChild()
            }
        } finally {
            ImGui.endChild()
        }
    }

    /** The chip that follows the mouse while a link row is dragged; the drop fires on release. */
    private fun dragChip() {
        val name = dragging ?: return
        val mx = ImGui.getMousePosX()
        val my = ImGui.getMousePosY()
        if (!ImGui.isMouseDown(imgui.flag.ImGuiMouseButton.Left)) {
            val moved = Math.abs(mx - dragStartX) + Math.abs(my - dragStartY) > 6f
            if (moved && dragArmed) drop = LinkDrop(name, mx, my)
            dragging = null
            dragArmed = false
            return
        }
        if (Math.abs(mx - dragStartX) + Math.abs(my - dragStartY) < 6f) return
        val dl = ImGui.getForegroundDrawList()
        val label = "Link: $name"
        val tw = ImGui.calcTextSize(label).x
        dl.addRectFilled(mx + 12f, my + 8f, mx + 12f + tw + 16f, my + 8f + Chrome.ROW_H, Theme.withAlpha(Theme.PANEL_BG, 0.95f), 4f, imgui.flag.ImDrawFlags.RoundCornersAll)
        dl.addRect(mx + 12f, my + 8f, mx + 12f + tw + 16f, my + 8f + Chrome.ROW_H, Theme.ACCENT, 4f, imgui.flag.ImDrawFlags.RoundCornersAll, 1f)
        dl.addText(mx + 20f, my + 8f + (Chrome.ROW_H - ImGui.getTextLineHeight()) * 0.5f, Theme.TEXT, label)
    }

    /** A card beside the row with the block the link points at, rendered by the host, and where it is. */
    private fun preview(dl: imgui.ImDrawList, l: LinkView, rx: Float, ry: Float, w: Float) {
        val previews = host.previews
        previews?.want(l)
        val region = previews?.region(l)
        val fdl = ImGui.getForegroundDrawList()
        val cardW = 200f
        val cardH = if (region != null) 116f else 52f
        val x = rx - cardW - 8f
        val y = (ry - 8f).coerceAtLeast(ImGui.getWindowPosY())
        fdl.addRectFilled(x, y, x + cardW, y + cardH, Theme.withAlpha(Theme.PANEL_BG, 0.97f), 6f, imgui.flag.ImDrawFlags.RoundCornersAll)
        fdl.addRect(x, y, x + cardW, y + cardH, PanelBits.EDGE, 6f, imgui.flag.ImDrawFlags.RoundCornersAll, 1f)
        var ty = y + 4f
        PanelBits.label(fdl, x + Chrome.PAD, ty, l.name, Theme.TEXT, Chrome.ROW_H)
        ty += Chrome.ROW_H
        PanelBits.label(fdl, x + Chrome.PAD, ty, "${l.x}, ${l.y}, ${l.z}${l.side?.let { " · $it" } ?: ""}", PanelBits.STAMP, Chrome.ROW_H)
        ty += Chrome.ROW_H
        if (region != null) {
            val size = 64f
            val ix = x + Chrome.PAD
            fdl.addImage(region.texture.toLong(), ix, ty + 2f, ix + size, ty + 2f + size, region.u0, region.v0, region.u1, region.v1)
            PanelBits.label(fdl, ix + size + 8f, ty + 2f, Chrome.fit(previews.labelOf(l) ?: "", cardW - size - Chrome.PAD * 2 - 8f), Theme.TEXT_DIM, Chrome.ROW_H)
        }
        @Suppress("UNUSED_VARIABLE") val unusedW = w
        @Suppress("UNUSED_VARIABLE") val unusedDl = dl
    }

    companion object {
        const val WIDTH = 260f
        private const val ROW_MENU = "##bpm-link-row"
    }
}
