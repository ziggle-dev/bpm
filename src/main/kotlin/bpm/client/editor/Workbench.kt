package bpm.client.editor

import bpm.session.SessionReasonText
import imgui.ImGui
import imgui.flag.ImGuiKey
import imgui.flag.ImGuiWindowFlags
import dev.ziggle.imgui.EditorKeyboard
import dev.ziggle.imgui.Fonts
import dev.ziggle.imgui.PanelBits
import dev.ziggle.imgui.Theme
import dev.ziggle.vscript.compile.Issue
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.editor.graph.OutlinePanel
import dev.ziggle.vscript.editor.graph.OwnCanvas
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.runtime.EditorDoc
import java.util.UUID

/**
 * The bpm editor: vscript's canvas and outline in the middle, the world's libraries on the left, the
 * controller's links on the right, problems below, and one toolbar that says whose graph this is.
 *
 * Every controller owns one graph, which the workbench opens when attached to it. Libraries are the shared,
 * importable graphs; editing one switches the canvas to it, and "Back" returns to the controller's graph.
 *
 * ImGui only. Fills whatever window is current. State that should survive the screen closing (open
 * document, camera, panel toggles) lives here, so the Minecraft side keeps one instance per connection.
 *
 * Editing rules: the holder of the lease edits; everyone else watches a read-only canvas and may ask for
 * the lease. Edits autosave a few seconds after the last change; Save (Ctrl+S) commits now; Deploy
 * (Ctrl+Shift+S) commits and restarts every controller running or importing the document. A push from the
 * server over uncommitted edits is never applied silently — the conflict bar asks.
 */
class Workbench(host: WorkbenchHost) {
    var host: WorkbenchHost = host
        set(value) {
            val changed = field.controller !== value.controller
            val oldRun = field.run
            field = value
            links = LinksPanel(value)
            buffer = BufferPanel(value)
            if (changed) {
                // The graph on the canvas belonged to the previous controller: leave it, the new one's takes its place.
                oldRun?.subscribe(false)
                val old = controllerDoc
                if (old != null && current?.id == old.id) {
                    leave(old)
                    current = null
                    seenEditor = null
                }
                controllerDoc = null
            }
        }

    private val canvas = OwnCanvas(host.catalog)
    private val outline = OutlinePanel(host.catalog)
    private val libraries = LibrariesPanel(host)
    private var links = LinksPanel(host)
    private var buffer = BufferPanel(host)
    /** vscript's own drawer (console, stack, variables, breakpoints) over a client log that carries the validator's issues; the run view fills it in phase 6. */
    private val drawerRuntime = dev.ziggle.vscript.runtime.ScriptRuntime(host.catalog, dev.ziggle.vscript.vm.HostRegistry())
    private val drawerSession = dev.ziggle.vscript.runtime.DebugSession(drawerRuntime)
    private val drawer = dev.ziggle.vscript.runview.DebugPanel(host.catalog)
    private val log = dev.ziggle.vscript.log.ScriptLog()

    init {
        // The drawer's height is vscript's global; remembered in the mod's preferences like the panel toggles.
        val prefs = host.prefs
        dev.ziggle.vscript.runview.DebugPanel.remembered = object : dev.ziggle.vscript.runview.DebugPanel.Remembered {
            override fun get(): Float = prefs.getFloat("drawer.height", dev.ziggle.vscript.runview.DebugPanel.DEFAULT_H)
            override fun set(v: Float) = prefs.putFloat("drawer.height", v)
        }
    }

    fun toggleDrawer() = drawer.toggle()

    private var current: OpenDoc? = null
    private var controllerDoc: OpenDoc? = null
    private var seenEditor: EditorDoc? = null
    private var issues: List<Issue> = emptyList()
    private var lastJson: String? = null
    private var lastCheckAt = 0L
    private var lastEditAt = 0L
    private var seenCommitAt = 0L
    private var seenReason = ""
    private var seenImports: Set<String> = emptySet()
    private var askedLibrary = false

    private var status = ""
    private var statusError = false
    private var statusAt = 0L

    var showLibraries: Boolean = host.prefs.getBool("libs.open", true)
        private set
    var showLinks: Boolean = host.prefs.getBool("links.open", true)
    var showBuffer: Boolean = host.prefs.getBool("buffer.open", true)
        private set

    val document: OpenDoc? get() = current
    val currentIssues: List<Issue> get() = issues

    /** Esc should reach ImGui rather than close the screen while a picker, palette or text field has it. */
    val wantsEscape: Boolean
        get() = PickerRouting.anyOpen || EditorKeyboard.busy || canvas.keyboardCaptured || ImGui.getIO().wantTextInput

    private val OpenDoc.isLibraryGraph: Boolean get() = controllerDoc?.id != id

    // ---- documents --------------------------------------------------------------------------------------

    /** Edit a library. */
    fun open(id: UUID, wantEdit: Boolean = true) {
        val prev = current
        if (prev != null && prev.id != id) leave(prev)
        show(host.store.open(id, wantEdit))
        setStatus("opening…", false)
    }

    /** Back to the controller's own graph. */
    fun backToController() {
        val c = controllerDoc ?: return
        val prev = current
        if (prev != null && prev.id != c.id) leave(prev)
        show(c)
    }

    private fun show(doc: OpenDoc) {
        current = doc
        seenEditor = null
        lastJson = null
        issues = emptyList()
        canvas.reset()
    }

    /** Leaving a document: save what is unsaved; a library is closed, the controller's graph stays open. */
    private fun leave(d: OpenDoc) {
        if (d.dirty && d.canEdit && !d.committing) host.store.commit(d.id, false)
        if (d.isLibraryGraph) host.store.close(d.id) else if (d.isHolder) host.store.releaseLease(d.id)
    }

    /** The screen is closing: save what is unsaved, hand the lease back, keep the graph open for next time. */
    fun onScreenClosed() {
        val d = current ?: return
        if (d.dirty && d.canEdit && !d.committing) host.store.commit(d.id, false)
        if (d.isHolder) host.store.releaseLease(d.id)
    }

    /** The screen opened again: ask for the lease back. */
    fun onScreenOpened() {
        val d = current ?: return
        if (d.isLibraryGraph) host.store.open(d.id, wantEdit = true) else host.store.openControllerGraph()
    }

    /** Adds [id] to the graph being edited as an import named after the library; false when it cannot. */
    fun importLibrary(id: UUID): Boolean {
        val d = current ?: return false
        val editor = d.editor ?: return false
        if (!d.canEdit || d.id == id) return false
        val lib = host.store.libraries.firstOrNull { it.id == id } ?: return false
        if (editor.imports.any { it.docId == id.toString() || it.alias.equals(lib.name, true) }) return false
        val alias = lib.name.replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "lib" }
        editor.edit("import ${lib.name}") {
            editor.imports.removeAll { it.alias == alias }
            editor.imports += dev.ziggle.vscript.model.GraphImport(alias, lib.name, id.toString())
        }
        host.store.ensureLibraryGraph(id)
        return true
    }

    fun save(deploy: Boolean) {
        val d = current ?: return
        if (!d.canEdit) {
            setStatus("you do not hold the edit lease", true)
            return
        }
        if (d.committing) return
        if (host.store.commit(d.id, deploy)) setStatus(if (deploy) "deploying…" else "saving…", false)
    }

    private fun setStatus(text: String, error: Boolean) {
        status = text
        statusError = error
        statusAt = host.now()
    }

    // ---- frame --------------------------------------------------------------------------------------------

    fun render() {
        val now = host.now()
        if (!askedLibrary) {
            // The library list is needed whether or not its panel is showing: imports resolve names through it.
            askedLibrary = true
            host.store.refresh()
        }
        attachController()
        val d = current
        val editor = d?.editor
        if (editor != null && editor !== seenEditor) onEditorArrived(d, editor)
        if (d != null) noticeServer(d)
        if (editor != null) resolveImports(editor)

        toolbar(d)
        body(d, editor)
        PickerRouting.render(editor, d?.canEdit == true)
        shortcuts(d, editor)
        if (d != null && editor != null) trackEdits(d, editor, now)
        if (d != null && d.hasConflict) conflictBar(d)
    }

    /** Attached to a controller: its graph is the home document, shown as soon as the server names it. */
    private fun attachController() {
        if (host.controller == null) return
        if (controllerDoc == null) {
            controllerDoc = host.store.openControllerGraph() ?: return
            if (current == null) show(controllerDoc!!)
        }
        host.run?.let { run -> run.subscribe(current?.id == controllerDoc?.id) }
    }

    /** The live view, when the controller's own graph is on the canvas — a library shows the validator's log. */
    private fun liveRun(d: OpenDoc): RemoteRunView? = host.run?.takeIf { it.subscribed && d.id == controllerDoc?.id }

    private fun onEditorArrived(d: OpenDoc, editor: EditorDoc) {
        val first = seenEditor == null
        seenEditor = editor
        lastJson = GraphDoc.toJson(editor.toGraph())
        if (first) canvas.reset() else canvas.clearViewState()
        seenImports = emptySet()
        // A literal edited on the controller's own graph is applied to the running program at once — the
        // shell's live tuning, over the wire; structural edits wait for Deploy.
        editor.onLiteralCommitted = { nodeId, pin, value ->
            val run = liveRun(d)
            if (run != null && d.canEdit && host.controller?.info?.debugBuild != false) run.setLiteral(nodeId, pin, value?.toString() ?: "null")
        }
        validate(editor)
        setStatus(if (d.isHolder) "editing v${d.version}" else "viewing v${d.version}", false)
    }

    /** Every import the graph names is fetched once per library version, so the outline and validator can see it. */
    private fun resolveImports(editor: EditorDoc) {
        val wanted = editor.imports.mapNotNull { it.docId }.toSet()
        if (wanted == seenImports) return
        seenImports = wanted
        for (id in wanted) runCatching { UUID.fromString(id) }.getOrNull()?.let { host.store.ensureLibraryGraph(it) }
    }

    private fun noticeServer(d: OpenDoc) {
        d.lastCommit?.let { c ->
            if (c.atMs != seenCommitAt) {
                seenCommitAt = c.atMs
                val ok = c.status.name == "OK" || c.status.name == "UNCHANGED"
                setStatus(c.message, !ok)
            }
        }
        val reason = "${d.lastReason}:${d.role}:${d.holderName}"
        if (reason != seenReason) {
            seenReason = reason
            SessionReasonText.describe(d.lastReason, d.isHolder, d.holderName)?.let { setStatus(it, !d.isHolder) }
        }
    }

    private fun validate(editor: EditorDoc) {
        issues = runCatching { Validator(host.catalog, host.store.graphSource, tickMayWait = true).validate(editor.toGraph()) }.getOrElse { emptyList() }
        canvas.setIssues(issues)
        // The drawer's console shows the same problems, one record per issue, badged on the nodes they name.
        log.clear()
        for (i in issues) {
            log.add(if (i.severity == Severity.ERROR) dev.ziggle.vscript.log.LogLevel.ERROR else dev.ziggle.vscript.log.LogLevel.WARN, i.message, i.nodeId ?: -1)
        }
        drawer.describeNodes(editor)
    }

    /** Twice a second: has the document changed since the last look? If so it is dirty, and re-validated. */
    private fun trackEdits(d: OpenDoc, editor: EditorDoc, now: Long) {
        if (!d.canEdit) return
        if (now - lastCheckAt < CHECK_MS) return
        lastCheckAt = now
        val json = GraphDoc.toJson(editor.toGraph())
        if (json != lastJson) {
            lastJson = json
            d.dirty = true
            lastEditAt = now
            validate(editor)
        }
        if (d.dirty && !d.committing && now - lastEditAt >= AUTOSAVE_MS) {
            if (host.store.commit(d.id, false)) setStatus("autosaving…", false)
        }
    }

    // ---- chrome ---------------------------------------------------------------------------------------------

    private fun toolbar(d: OpenDoc?) {
        val h = TOOLBAR_H
        ImGui.beginChild("##bpm-toolbar", -1f, h, false, ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse)
        try {
            val dl = ImGui.getWindowDrawList()
            val x = ImGui.getWindowPosX()
            val y = ImGui.getWindowPosY()
            val w = ImGui.getWindowWidth()
            dl.addRectFilled(x, y, x + w, y + h, Theme.BAR_BG)
            dl.addLine(x, y + h - 0.5f, x + w, y + h - 0.5f, PanelBits.EDGE, 1f)
            val py = y + (h - PanelBits.HEADER_H) * 0.5f
            var cx = x + Chrome.PAD

            // Panel toggles.
            cx += PanelBits.pill(dl, "##bpm-tg-libs", cx, py, "Libraries", showLibraries, Theme.TEXT) {
                showLibraries = !showLibraries
                host.prefs.putBool("libs.open", showLibraries)
            }
            cx += PanelBits.pill(dl, "##bpm-tg-outline", cx, py, "Outline", outline.width() > 0f, Theme.TEXT) { outline.toggle() }
            if (host.controller != null) {
                cx += PanelBits.pill(dl, "##bpm-tg-buffer", cx, py, "Buffer", showBuffer, Theme.TEXT) {
                    showBuffer = !showBuffer
                    host.prefs.putBool("buffer.open", showBuffer)
                }
                cx += PanelBits.pill(dl, "##bpm-tg-links", cx, py, "Links", showLinks, Theme.TEXT) {
                    showLinks = !showLinks
                    host.prefs.putBool("links.open", showLinks)
                }
            }
            cx = divider(dl, cx, y, h)

            // The graph: whose it is, its version, and what you can do to it.
            when {
                d == null && host.controller != null -> cx += PanelBits.label(dl, cx, py, "waiting for the controller's graph…", PanelBits.STAMP) + Chrome.PAD
                d == null -> cx += PanelBits.label(dl, cx, py, "no graph open — edit a library on the left", PanelBits.STAMP) + Chrome.PAD
                else -> {
                    if (d.isLibraryGraph) {
                        if (controllerDoc != null) {
                            if (PanelBits.iconButton(dl, "##bpm-back", cx, py, PanelBits.icon(Fonts.CARET_LEFT), "Back to the controller's graph")) backToController()
                            cx += PanelBits.ICON + 4f
                        }
                        cx += PanelBits.label(dl, cx, py, "library", Theme.TEXT_ACCENT) + 6f
                    }
                    cx += PanelBits.label(dl, cx, py, Chrome.fit(d.name, 260f), Theme.TEXT) + 6f
                    cx += PanelBits.label(dl, cx, py, "v${d.version}${if (d.dirty) " *" else ""}", PanelBits.STAMP) + Chrome.PAD
                    val can = d.canEdit && !d.committing
                    if (Chrome.action(dl, "##bpm-save", cx, py, PanelBits.icon(Fonts.CHECK_SQUARE), "Save", Theme.ACCENT, can, "Commit this version (Ctrl+S)")) save(false)
                    cx += PanelBits.actionWidth(PanelBits.icon(Fonts.CHECK_SQUARE), "Save") + 6f
                    if (Chrome.action(dl, "##bpm-deploy", cx, py, PanelBits.icon(Fonts.BOLT), "Deploy", Theme.OK, can, "Commit and restart every controller running or importing it (Ctrl+Shift+S)")) save(true)
                    cx += PanelBits.actionWidth(PanelBits.icon(Fonts.BOLT), "Deploy") + Chrome.PAD
                    cx = lease(dl, d, cx, py)
                }
            }

            // The controller: run controls and its state.
            host.controller?.info?.let { info ->
                cx = divider(dl, cx, y, h)
                val c = host.controller!!
                val running = info.status == "running" || info.status == "asleep"
                if (running) {
                    if (Chrome.action(dl, "##bpm-stop", cx, py, PanelBits.icon(Fonts.STOP), "Stop", Theme.BAD, tip = "Disable the controller")) c.stop()
                    cx += PanelBits.actionWidth(PanelBits.icon(Fonts.STOP), "Stop") + 6f
                } else {
                    if (Chrome.action(dl, "##bpm-start", cx, py, PanelBits.icon(Fonts.PLAY), "Start", Theme.OK, tip = "Enable the controller")) c.start()
                    cx += PanelBits.actionWidth(PanelBits.icon(Fonts.PLAY), "Start") + 6f
                }
                if (PanelBits.iconButton(dl, "##bpm-restart", cx, py, PanelBits.icon(Fonts.UNDO), "Restart the program")) c.restart()
                cx += PanelBits.ICON + 6f
                d?.let { liveRun(it) }?.let { run ->
                    if (running) {
                        if (run.isPaused) {
                            if (Chrome.action(dl, "##bpm-resume", cx, py, PanelBits.icon(Fonts.PLAY), "Resume", Theme.OK, tip = "Continue (F5)")) run.resume()
                            cx += PanelBits.actionWidth(PanelBits.icon(Fonts.PLAY), "Resume") + 6f
                            if (PanelBits.iconButton(dl, "##bpm-step-over", cx, py, PanelBits.icon(Fonts.STEP_OVER), "Step over (F10)")) run.stepOver()
                            cx += PanelBits.ICON + 2f
                            if (PanelBits.iconButton(dl, "##bpm-step-into", cx, py, PanelBits.icon(Fonts.STEP_INTO), "Step into (F11)")) run.stepInto()
                            cx += PanelBits.ICON + 2f
                            if (PanelBits.iconButton(dl, "##bpm-step-out", cx, py, PanelBits.icon(Fonts.STEP_OUT), "Step out (Shift+F11)")) run.stepOut()
                            cx += PanelBits.ICON + 6f
                        } else {
                            if (PanelBits.iconButton(dl, "##bpm-pause", cx, py, PanelBits.icon(Fonts.HOURGLASS), "Pause every fiber (F6)")) run.pause()
                            cx += PanelBits.ICON + 6f
                        }
                    }
                }
                cx += PanelBits.pill(dl, "##bpm-build", cx, py, if (info.debugBuild) "debug" else "release", info.debugBuild, Theme.TEXT_DIM) {
                    c.setFlags(info.enabled, !info.debugBuild)
                }
                if (ImGui.isItemHovered()) ImGui.setTooltip(if (info.debugBuild) "Debug build: highlights, breakpoints and values. Click for a release build (faster, no tracing)." else "Release build: no tracing. Click for a debug build.")
                cx += Chrome.dotLabel(dl, cx, py, info.status, Chrome.statusColour(info.status), PanelBits.HEADER_H) + Chrome.PAD
            }

            // The last thing that happened, at the right; errors stay, notes fade after a while.
            if (status.isNotEmpty()) {
                val text = if (host.now() - statusAt > 8000 && !statusError) "" else Chrome.fit(status, w * 0.35f)
                if (text.isNotEmpty()) {
                    val tw = ImGui.calcTextSize(text).x
                    PanelBits.label(dl, x + w - Chrome.PAD - tw, py, text, if (statusError) PanelBits.ERROR_TEXT else PanelBits.STAMP)
                }
            }
        } finally {
            ImGui.endChild()
        }
    }

    private fun divider(dl: imgui.ImDrawList, cx: Float, y: Float, h: Float): Float {
        dl.addLine(cx + 2f, y + 8f, cx + 2f, y + h - 8f, PanelBits.ROW_LINE, 1f)
        return cx + 4f + Chrome.PAD
    }

    /** The lease pill: who is editing, and the way to ask for it. */
    private fun lease(dl: imgui.ImDrawList, d: OpenDoc, x: Float, py: Float): Float {
        var cx = x
        when {
            d.isHolder -> cx += PanelBits.pill(dl, "##bpm-lease", cx, py, "editing", true, Theme.OK) {}
            d.holderName.isNotEmpty() -> {
                cx += PanelBits.pill(dl, "##bpm-lease", cx, py, "viewing — ${d.holderName} is editing", true, Theme.WARN) {}
                if (Chrome.action(dl, "##bpm-steal", cx, py, PanelBits.icon(Fonts.BOLT), "Take over", Theme.WARN, tip = "Take the edit lease (operators and the owner)")) host.store.requestLease(d.id, steal = true)
                cx += PanelBits.actionWidth(PanelBits.icon(Fonts.BOLT), "Take over") + 6f
            }
            else -> {
                cx += PanelBits.pill(dl, "##bpm-lease", cx, py, "viewing", true, PanelBits.MUTED) {}
                if (Chrome.action(dl, "##bpm-edit", cx, py, PanelBits.icon(Fonts.CODE), "Edit", Theme.ACCENT, tip = "Take the edit lease")) host.store.requestLease(d.id, steal = false)
                cx += PanelBits.actionWidth(PanelBits.icon(Fonts.CODE), "Edit") + 6f
            }
        }
        return cx + Chrome.PAD
    }

    private fun body(d: OpenDoc?, editor: EditorDoc?) {
        val hostX = ImGui.getCursorScreenPosX()
        val hostY = ImGui.getCursorScreenPosY()
        val hostW = ImGui.getContentRegionAvailX()
        val hostH = ImGui.getContentRegionAvailY()
        val drawerH = drawer.height()
        val mainH = hostH - drawerH
        var x = hostX

        if (showLibraries) {
            libraries.render(x, hostY, LibrariesPanel.WIDTH, mainH, d)
            libraries.editRequest?.let { id ->
                libraries.editRequest = null
                if (id != d?.id) open(id)
            }
            x += LibrariesPanel.WIDTH
        }
        val sideW = outline.width()
        if (sideW > 0f) {
            outline.resolver = host.store.graphSource
            outline.onReveal = { id -> editor?.let { canvas.reveal(it, id) } }
            outline.render(editor, x, hostY, mainH)
            outline.drop?.let { canvas.pendingDrop = it; outline.drop = null }
            x += sideW
        }
        val rightW = if (host.controller != null && (showLinks || showBuffer)) LinksPanel.WIDTH else 0f
        val canvasW = (hostW - (x - hostX) - rightW).coerceAtLeast(64f)

        ImGui.setCursorScreenPos(x, hostY)
        ImGui.beginChild("##canvas-host", canvasW, mainH, false, ImGuiWindowFlags.NoScrollbar or ImGuiWindowFlags.NoScrollWithMouse)
        try {
            if (editor == null) {
                ImGui.setCursorPos(24f, 24f)
                ImGui.textDisabled(
                    when {
                        d != null -> "loading ${d.name}…"
                        host.controller != null -> "waiting for the controller's graph…"
                        else -> "Edit a library from the list, or open a controller in the world."
                    },
                )
            } else {
                canvas.readOnly = !d!!.canEdit
                val run = liveRun(d)
                val shown = run?.log ?: log
                canvas.logLevels = shown.worstByNode()
                canvas.logsForNode = { id -> shown.forNode(id).map { it.message } }
                canvas.onBadgeClicked = { id -> drawer.openConsoleFor(id) }
                if (run != null) {
                    run.syncBreakpoints()
                    canvas.activeNodes = run.activeNodes
                    canvas.activeLinks = run.activeLinks
                    canvas.breakpoints = run.breakpoints.entries().associate { it.first to it.second.enabled }
                    canvas.onToggleBreakpoint = { id -> run.toggleBreakpoint(id) }
                    canvas.pausedNode = run.pausedNode
                    canvas.pinValues = if (run.isPaused) run.pinValues else emptyMap()
                    canvas.pureValues = if (run.isPaused) run.pureValues else emptyMap()
                } else {
                    canvas.activeNodes = emptySet()
                    canvas.activeLinks = emptySet()
                    canvas.breakpoints = emptyMap()
                    canvas.onToggleBreakpoint = {}
                    canvas.pausedNode = -1
                    canvas.pinValues = emptyMap()
                    canvas.pureValues = emptyMap()
                }
                links.drop?.let { drop ->
                    links.drop = null
                    if (d.canEdit) canvas.pendingDrop = dev.ziggle.vscript.editor.graph.OutlineDrop(null, LINK_NODE, set = false, screenX = drop.screenX, screenY = drop.screenY, literals = mapOf(LINK_NODE_PIN to drop.name))
                }
                canvas.render(editor)
            }
        } finally {
            ImGui.endChild()
        }
        if (rightW > 0f) {
            // The right column: the buffer on top, sized to its contents, the links below it.
            var ry = hostY
            var rh = mainH
            if (showBuffer) {
                val bh = if (showLinks) buffer.height(host.controller?.info).coerceAtMost(mainH * 0.7f) else mainH
                buffer.render(x + canvasW, ry, rightW, bh)
                ry += bh
                rh -= bh
            }
            if (showLinks) links.render(x + canvasW, ry, rightW, rh, d)
        }

        val run = d?.let { liveRun(it) }
        val reveal = drawer.render(run?.log ?: log, run ?: drawerSession, editor, hostX, hostY + mainH, hostW)
        if (reveal >= 0 && editor != null) canvas.reveal(editor, reveal)
    }

    private fun conflictBar(d: OpenDoc) {
        val dl = ImGui.getForegroundDrawList()
        val w = 560f
        val h = PanelBits.HEADER_H * 2 + 8f
        val x = ImGui.getWindowPosX() + (ImGui.getWindowWidth() - w) * 0.5f
        val y = ImGui.getWindowPosY() + TOOLBAR_H + 10f
        dl.addRectFilled(x, y, x + w, y + h, Theme.PANEL_BG, 6f, imgui.flag.ImDrawFlags.RoundCornersAll)
        dl.addRect(x, y, x + w, y + h, Theme.WARN, 6f, imgui.flag.ImDrawFlags.RoundCornersAll, 1f)
        PanelBits.label(dl, x + Chrome.PAD, y + 4f, Chrome.fit("'${d.name}' changed on the server while you had unsaved edits.", w - Chrome.PAD * 2), PanelBits.WARN_TEXT)
        val row = y + 4f + PanelBits.HEADER_H
        var cx = x + Chrome.PAD
        if (PanelBits.action(dl, "##bpm-conflict-theirs", cx, row, PanelBits.icon(Fonts.RETURN), "Take theirs — drop my edits", Theme.WARN)) host.store.takeTheirs(d.id)
        cx += PanelBits.actionWidth(PanelBits.icon(Fonts.RETURN), "Take theirs — drop my edits") + 8f
        if (PanelBits.action(dl, "##bpm-conflict-mine", cx, row, PanelBits.icon(Fonts.CHECK_SQUARE), "Keep mine", Theme.ACCENT)) host.store.keepMine(d.id)
    }

    // ---- keyboard -------------------------------------------------------------------------------------------

    private fun shortcuts(d: OpenDoc?, editor: EditorDoc?) {
        val io = ImGui.getIO()
        if (io.wantTextInput || canvas.keyboardCaptured || EditorKeyboard.busy) return
        if (ImGui.isKeyPressed(ImGuiKey.GraveAccent)) drawer.toggle()
        d?.let { liveRun(it) }?.let { run ->
            if (run.isPaused) {
                if (ImGui.isKeyPressed(ImGuiKey.F5)) run.resume()
                if (ImGui.isKeyPressed(ImGuiKey.F10)) run.stepOver()
                if (ImGui.isKeyPressed(ImGuiKey.F11)) if (io.keyShift) run.stepOut() else run.stepInto()
                if (ImGui.isKeyPressed(ImGuiKey.F12)) run.stepIntoData()
            } else if (ImGui.isKeyPressed(ImGuiKey.F6)) {
                run.pause()
            }
        }
        if (!io.keyCtrl || d == null || editor == null) return
        val canEdit = d.canEdit
        if (ImGui.isKeyPressed(ImGuiKey.S)) save(deploy = io.keyShift)
        if (ImGui.isKeyPressed(ImGuiKey.A)) canvas.select(editor.nodes.map { it.id })
        if (ImGui.isKeyPressed(ImGuiKey.C)) NodeClipboard.encode(editor, canvas.selectedIds)?.let { ImGui.setClipboardText(it) }
        if (!canEdit) return
        if (ImGui.isKeyPressed(ImGuiKey.Z)) if (io.keyShift) redo(editor) else undo(editor)
        if (ImGui.isKeyPressed(ImGuiKey.Y)) redo(editor)
        if (ImGui.isKeyPressed(ImGuiKey.X)) NodeClipboard.cut(editor, canvas.selectedIds)?.let { ImGui.setClipboardText(it); canvas.clearViewState() }
        if (ImGui.isKeyPressed(ImGuiKey.V)) paste(editor, ImGui.getClipboardText())
        if (ImGui.isKeyPressed(ImGuiKey.D)) paste(editor, NodeClipboard.encode(editor, canvas.selectedIds), offset = true)
    }

    private fun paste(editor: EditorDoc, text: String?, offset: Boolean = false) {
        val clip = NodeClipboard.decode(text) ?: return
        val mouse = ImGui.getMousePos()
        val (gx, gy) = if (!offset && canvas.contains(mouse.x, mouse.y)) canvas.toGraph(mouse.x, mouse.y) else {
            val minX = clip.nodes.minOf { it.x }
            val minY = clip.nodes.minOf { it.y }
            (minX + 40f) to (minY + 40f)
        }
        val ids = NodeClipboard.paste(editor, clip, gx, gy)
        canvas.select(ids)
    }

    private fun undo(editor: EditorDoc) = applyStep(editor, editor.undo(canvas.view()))

    private fun redo(editor: EditorDoc) = applyStep(editor, editor.redo(canvas.view()))

    private fun applyStep(editor: EditorDoc, step: EditorDoc.Step?) {
        if (step == null) return
        canvas.applyStep(step)
        if (step.graphChanged) validate(editor)
    }

    companion object {
        const val TOOLBAR_H = 34f
        const val LINK_NODE = "controller.link"
        const val LINK_NODE_PIN = "Name"
        const val CHECK_MS = 500L
        const val AUTOSAVE_MS = 4000L

        /** How many errors a document has, for badges. */
        fun errors(issues: List<Issue>): Int = issues.count { it.severity == Severity.ERROR }
    }
}
