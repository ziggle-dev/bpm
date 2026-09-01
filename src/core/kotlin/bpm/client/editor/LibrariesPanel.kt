package bpm.client.editor

import imgui.ImDrawList
import imgui.ImGui
import imgui.flag.ImGuiWindowFlags
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.PanelBits
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.editor.graph.PanelField
import dev.ziggle.vscript.model.GraphImport
import dev.ziggle.vscript.runtime.EditorDoc
import java.util.UUID

/**
 * Library graphs: the world's shared, importable documents. Every controller has its own graph; what it
 * shares with others lives in a library it imports. Lists the libraries (double-click or `Edit` to open one
 * on the canvas, `Import` to add it to the graph being edited), makes new ones, renames and deletes from the
 * row menu, and lists the imports of the current graph below with a way to drop them.
 */
class LibrariesPanel(private val host: WorkbenchHost) {
    private val search = PanelField("bpm-libs-filter")
    private val nameField = PanelField("bpm-libs-name")
    private var selected: UUID? = null
    private var renaming: UUID? = null
    private var creating = false
    private var confirmDelete: UUID? = null
    private var menuFor: UUID? = null
    private var requested = false

    /** Set when the user chose a library to edit. */
    var editRequest: UUID? = null

    fun render(x: Float, y: Float, w: Float, h: Float, current: OpenDoc?) {
        if (!requested) {
            requested = true
            host.store.refresh()
        }
        val editor = current?.editor
        ImGui.setCursorScreenPos(x, y)
        ImGui.beginChild("##bpm-libs", w, h, false, ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse)
        try {
            val dl = ImGui.getWindowDrawList()
            Chrome.panel(dl, x, y, w, h)

            // Head: caption, count, the search field and the `+`.
            var cy = y
            PanelBits.label(dl, x + Chrome.PAD, cy, "LIBRARIES", Chrome.SECTION_TEXT, Chrome.SECTION_H)
            PanelBits.label(dl, x + Chrome.PAD + ImGui.calcTextSize("LIBRARIES").x + 7f, cy, host.store.libraries.size.toString(), PanelBits.STAMP, Chrome.SECTION_H)
            if (PanelBits.iconButton(dl, "##bpm-libs-refresh", x + w - Chrome.PAD - PanelBits.ICON * 2 - 4f, cy - 4f, PanelBits.icon(Fonts.RETURN), "Refresh the list")) host.store.refresh()
            if (PanelBits.iconButton(dl, "##bpm-libs-new", x + w - Chrome.PAD - PanelBits.ICON, cy - 4f, PanelBits.icon(Fonts.PLUS), "New library")) {
                creating = true
                nameField.set("library")
            }
            cy += Chrome.SECTION_H
            Chrome.searchField(dl, search, x + Chrome.PAD, cy + 2f, w - Chrome.PAD * 2, "Filter")
            cy += Chrome.FIELD_H + 8f
            if (creating) {
                PanelBits.label(dl, x + Chrome.PAD, cy, "Name", PanelBits.STAMP, Chrome.ROW_H + 4f)
                val fx = x + Chrome.PAD + 44f
                Chrome.textField(dl, nameField, fx, cy + 1f, w - Chrome.PAD * 2 - 44f, "library")?.let { name ->
                    host.store.createLibrary(name.trim().ifEmpty { "library" })
                    creating = false
                }
                if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape)) creating = false
                cy += Chrome.ROW_H + 6f
            }
            dl.addLine(x, cy + 0.5f, x + w, cy + 0.5f, PanelBits.ROW_LINE, 1f)
            cy += 1f

            // Body: the list, scrolling on its own.
            val importsH = if (editor != null) Chrome.SECTION_H + Chrome.ROW_H * maxOf(1, editor.imports.size) + 6f else 0f
            val footerH = PanelBits.HEADER_H + 4f
            val listH = (h - (cy - y) - footerH - importsH).coerceAtLeast(Chrome.ROW_H * 2)
            val q = search.text.trim().lowercase()
            val rows = host.store.libraries.filter { q.isEmpty() || q in it.name.lowercase() }.sortedBy { it.name.lowercase() }
            ImGui.setCursorScreenPos(x, cy)
            ImGui.beginChild("##bpm-libs-list", w, listH, false, ImGuiWindowFlags.NoScrollbar)
            try {
                val ldl = ImGui.getWindowDrawList()
                val lx = ImGui.getCursorScreenPosX()
                var ry = ImGui.getCursorScreenPosY()
                if (rows.isEmpty()) {
                    PanelBits.label(ldl, lx + Chrome.PAD, ry, if (host.store.libraries.isEmpty()) "no libraries yet — press +" else "nothing matches", PanelBits.STAMP, Chrome.ROW_H)
                }
                for (r in rows) {
                    val isCurrent = r.id == current?.id
                    val isSel = r.id == selected || isCurrent
                    val hot = Chrome.rowHot(lx, ry, w)
                    Chrome.rowBg(ldl, lx, ry, w, isSel, hot)
                    if (renaming == r.id) {
                        Chrome.textField(ldl, nameField, lx + Chrome.PAD, ry + 1f, w - Chrome.PAD * 2, r.name)?.let { name ->
                            if (name.trim().isNotEmpty() && name.trim() != r.name) host.store.rename(r.id, name.trim())
                            renaming = null
                        }
                        if (ImGui.isKeyPressed(imgui.flag.ImGuiKey.Escape)) renaming = null
                        ry += Chrome.ROW_H
                        continue
                    }
                    val col = Theme.col(0x8A, 0xB4, 0xF8)
                    ldl.addCircleFilled(lx + Chrome.PAD + 4f, ry + Chrome.ROW_H * 0.5f, 3.5f, if (r.hasErrors) PanelBits.ERROR else col, 12)
                    val nameW = PanelBits.label(ldl, lx + Chrome.PAD + 14f, ry, Chrome.fit(r.name, w * 0.55f), if (isSel) Theme.TEXT else Theme.TEXT_DIM, Chrome.ROW_H)
                    val note = buildString {
                        append("v").append(r.version)
                        if (r.holderName.isNotEmpty()) append("  ").append(if (r.holderName == host.playerName) "you" else r.holderName)
                    }
                    val nw = ImGui.calcTextSize(note).x
                    if (nw < w - Chrome.PAD * 3 - 14f - nameW) PanelBits.label(ldl, lx + w - nw - Chrome.PAD, ry, note, if (r.holderName.isNotEmpty()) PanelBits.WARN_TEXT else PanelBits.STAMP, Chrome.ROW_H)
                    if (hot && Chrome.clicked()) selected = r.id
                    if (hot && Chrome.doubleClicked()) editRequest = r.id
                    if (hot && Chrome.rightClicked()) {
                        selected = r.id
                        menuFor = r.id
                        ImGui.openPopup(ROW_MENU)
                    }
                    ry += Chrome.ROW_H
                }
                ImGui.dummy(1f, (ry - ImGui.getCursorScreenPosY()).coerceAtLeast(1f))
                rowMenu()
            } finally {
                ImGui.endChild()
            }
            cy += listH

            // Footer: the two things you do with a selected library.
            dl.addLine(x, cy + 0.5f, x + w, cy + 0.5f, PanelBits.ROW_LINE, 1f)
            val sel = selected?.let { id -> host.store.libraries.firstOrNull { it.id == id } }
            val canImport = sel != null && editor != null && current.canEdit && current.id != sel.id &&
                editor.imports.none { it.docId == sel.id.toString() || it.alias.equals(sel.name, true) }
            var ax = x + Chrome.PAD
            val importTip = when {
                sel == null -> "Select a library first"
                editor == null -> "Open a graph first"
                current.id == sel.id -> "A library cannot import itself"
                !current.canEdit -> "You do not hold the edit lease"
                !canImport -> "Already imported"
                else -> "Import '${sel.name}' into ${current.name}"
            }
            if (Chrome.action(dl, "##bpm-lib-import", ax, cy + 2f, PanelBits.icon(Fonts.RETURN), "Import", Theme.ACCENT, canImport, importTip) && sel != null && editor != null) importInto(editor, sel)
            ax += PanelBits.actionWidth(PanelBits.icon(Fonts.RETURN), "Import") + 8f
            if (Chrome.action(dl, "##bpm-lib-edit", ax, cy + 2f, PanelBits.icon(Fonts.CODE), "Edit", Theme.OK, sel != null, if (sel == null) "Select a library first" else "Open '${sel.name}' on the canvas") && sel != null) editRequest = sel.id
            cy += footerH

            confirmDelete?.let { id ->
                val r = host.store.libraries.firstOrNull { it.id == id }
                if (r == null) {
                    confirmDelete = null
                } else {
                    // Asked where the answer is needed: over the footer, in the warning tint.
                    val ty = cy - footerH
                    dl.addRectFilled(x, ty, x + w, ty + footerH, PanelBits.BG)
                    dl.addRectFilled(x, ty, x + w, ty + footerH, PanelBits.ERROR_ROW)
                    var bx = x + Chrome.PAD
                    bx += PanelBits.label(dl, bx, ty + 2f, "Delete '${Chrome.fit(r.name, w * 0.4f)}'?", PanelBits.ERROR_TEXT) + 8f
                    if (Chrome.action(dl, "##bpm-lib-del-yes", bx, ty + 2f, PanelBits.icon(Fonts.TRASH), "Delete", PanelBits.ERROR)) {
                        host.store.delete(id)
                        confirmDelete = null
                        if (selected == id) selected = null
                    }
                    bx += PanelBits.actionWidth(PanelBits.icon(Fonts.TRASH), "Delete") + 6f
                    if (Chrome.action(dl, "##bpm-lib-del-no", bx, ty + 2f, PanelBits.icon(Fonts.CLOSE), "Keep", PanelBits.MUTED)) confirmDelete = null
                }
            }

            // Imports of the graph being edited.
            if (editor != null && current != null) {
                cy += Chrome.section(dl, x, cy, w, "Imports", editor.imports.size.toString())
                if (editor.imports.isEmpty()) {
                    PanelBits.label(dl, x + Chrome.PAD + 14f, cy, "none — select a library and Import", PanelBits.STAMP, Chrome.ROW_H)
                    cy += Chrome.ROW_H
                }
                for (imp in editor.imports.toList()) {
                    val hot = Chrome.rowHot(x, cy, w)
                    Chrome.rowBg(dl, x, cy, w, false, hot)
                    val resolved = host.store.graphSource.load(imp)
                    val fns = resolved?.functions?.count { it.isExported } ?: 0
                    dl.addCircleFilled(x + Chrome.PAD + 4f, cy + Chrome.ROW_H * 0.5f, 3.5f, if (resolved == null) PanelBits.ERROR else Theme.OK, 12)
                    PanelBits.label(dl, x + Chrome.PAD + 14f, cy, imp.alias, Theme.TEXT_DIM, Chrome.ROW_H)
                    val note = if (resolved == null) "not found" else "$fns exported"
                    PanelBits.label(dl, x + w - Chrome.PAD - 22f - ImGui.calcTextSize(note).x - 6f, cy, note, if (resolved == null) PanelBits.ERROR_TEXT else PanelBits.STAMP, Chrome.ROW_H)
                    if (hot) ImGui.setTooltip("${imp.alias} = \"${imp.ref}\" — call its functions as ${imp.alias}::name")
                    if (current.canEdit && Chrome.rowTrash(dl, "##bpm-imp-rm-${imp.alias}", x + w - Chrome.PAD - 16f, cy, "Remove the import")) editor.removeImport(imp.alias)
                    cy += Chrome.ROW_H
                }
            }
        } finally {
            ImGui.endChild()
        }
    }

    private fun rowMenu() {
        if (!ImGui.beginPopup(ROW_MENU)) return
        val r = menuFor?.let { id -> host.store.libraries.firstOrNull { it.id == id } }
        if (r == null) {
            ImGui.textDisabled("gone")
        } else {
            if (ImGui.menuItem("Edit")) editRequest = r.id
            if (ImGui.menuItem("Rename")) {
                nameField.set(r.name)
                renaming = r.id
            }
            if (ImGui.menuItem("Duplicate")) host.store.duplicate(r.id, "${r.name} copy")
            ImGui.separator()
            if (ImGui.menuItem("Delete…")) confirmDelete = r.id
        }
        ImGui.endPopup()
    }

    private fun importInto(editor: EditorDoc, lib: DocRecord) {
        val alias = lib.name.replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "lib" }
        editor.edit("import ${lib.name}") {
            editor.imports.removeAll { it.alias == alias }
            editor.imports += GraphImport(alias, lib.name, lib.id.toString())
        }
        host.store.ensureLibraryGraph(lib.id)
    }

    companion object {
        const val WIDTH = 250f
        private const val ROW_MENU = "##bpm-lib-row"
    }

    @Suppress("unused")
    private fun unused(dl: ImDrawList) = dl
}
