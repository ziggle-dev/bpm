package bpm.client.net

import bpm.Bpm
import bpm.library.DocumentCodec
import bpm.net.BigMessages
import bpm.net.ChunkPayload
import bpm.net.ControllerBindPayload
import bpm.net.ControllerFlagsPayload
import bpm.net.ControllerStatusPayload
import bpm.net.ControllerWatchPayload
import bpm.net.DocCommitMsg
import bpm.net.DocCommitResultPayload
import bpm.net.DocCreateMsg
import bpm.net.DocDeletePayload
import bpm.net.DocDuplicatePayload
import bpm.net.DocFetchPayload
import bpm.net.DocPushMsg
import bpm.net.DocRenamePayload
import bpm.net.EditorClosePayload
import bpm.net.EditorOpenPayload
import bpm.net.LeaseReleasePayload
import bpm.net.LeaseRequestPayload
import bpm.net.LibraryChangedPayload
import bpm.net.LibraryListMsg
import bpm.net.LibraryListRequestPayload
import bpm.net.LibraryRecordDto
import bpm.net.LinkDto
import bpm.net.LinkEditPayload
import bpm.net.LinkRenamedPayload
import bpm.net.EffectPayload
import bpm.net.LinkOp
import bpm.net.LinkTableSyncPayload
import bpm.net.NIL_UUID
import bpm.net.RunAction
import bpm.net.RunControlPayload
import bpm.net.SessionHeartbeatPayload
import bpm.net.SessionStatePayload
import bpm.net.chunk.ChunkAssembler
import bpm.net.chunk.Chunker
import bpm.session.CommitStatus
import bpm.session.Role
import bpm.session.SessionReason
import dev.ziggle.vscript.model.Graph
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.GraphSource
import dev.ziggle.vscript.runtime.EditorDoc
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import bpm.platform.net.BpmPayload
import bpm.platform.net.Net
import java.util.UUID

/** A document as the client holds it: the editor's copy plus what the server last said about it. */
class ClientDocument(val docId: UUID) {
    var name: String = ""
    var version: Int = 0
    var sha256: String = ""
    var role: Role = Role.VIEWER
    var holderName: String = ""
    var hasErrors: Boolean = false
    var lastReason: SessionReason = SessionReason.NONE
    var editor: EditorDoc? = null

    /** Local edits not yet committed. Set by the workbench; cleared by a successful commit or a push. */
    var dirty: Boolean = false

    /** The commit in flight, if any — commits never overlap. */
    var pendingCommit: Int? = null
    var lastResult: DocCommitResultPayload? = null
    var lastResultAt: Long = 0

    /** A newer version the server pushed while this copy had uncommitted edits: the conflict the UI resolves. */
    var conflict: DocPushMsg? = null

    val isHolder: Boolean get() = role == Role.HOLDER
    val canEdit: Boolean get() = isHolder && editor != null

    fun toJson(): String? = editor?.let { GraphDoc.toJson(it.toGraph()) }
}

class ControllerView(val pos: BlockPos) {
    var status: ControllerStatusPayload? = null
    var links: List<LinkDto> = emptyList()
}

class ClientLibrary {
    var version: Int = -1
    var records: List<LibraryRecordDto> = emptyList()
    var subscribed: Boolean = false

    operator fun get(id: UUID): LibraryRecordDto? = records.firstOrNull { it.id == id }

    /** The importable graphs only. */
    val libraries: List<LibraryRecordDto> get() = records.filter { it.isLibrary }
}

/** A library graph the client fetched to resolve imports (outline, validation); never edited through this. */
class LibraryGraph(val id: UUID, val name: String, val version: Int, val graph: Graph)

/** What the workbench listens to. Every callback is on the render thread. */
interface ClientNetListener {
    fun onLibrary(library: ClientLibrary) {}
    fun onDocument(doc: ClientDocument) {}
    fun onSession(doc: ClientDocument) {}
    fun onCommit(doc: ClientDocument, result: DocCommitResultPayload) {}
    fun onController(view: ControllerView) {}
    fun onCreateFailed(message: String) {}
}

/**
 * The client's side: requests out, state in. Handlers run on the render thread, so the maps here need no
 * locking and the editor can read them mid-frame. Cleared when the player logs out.
 */
object ClientNet {
    val library = ClientLibrary()
    val docs = LinkedHashMap<UUID, ClientDocument>()
    val controllers = HashMap<BlockPos, ControllerView>()
    val listeners = ArrayList<ClientNetListener>()

    /** Library graphs fetched for import resolution, by id. */
    val libraryGraphs = HashMap<UUID, LibraryGraph>()
    private val fetching = HashSet<UUID>()

    /** Run views by controller; created by the workbench session, fed here. */
    val runViews = HashMap<BlockPos, bpm.client.editor.RemoteRunView>()

    /** The run view of [pos], made on first use with a sink that speaks to the server. */
    fun runView(pos: BlockPos): bpm.client.editor.RemoteRunView = runViews.getOrPut(pos) {
        bpm.client.editor.RemoteRunView(object : bpm.client.editor.RunSink {
            override fun action(name: String) = runControl(pos, RunAction.valueOf(name))
            override fun breakpoint(nodeId: Int, enabled: Boolean, remove: Boolean) = send(bpm.net.BreakpointSetPayload(pos, nodeId, enabled, remove))
            override fun requestScopes(contextId: Int, frameIndex: Int) = send(bpm.net.RunScopesRequestPayload(pos, contextId, frameIndex))
            override fun setVariable(name: String, text: String) = send(bpm.net.SetVariablePayload(pos, name, text))
            override fun setLiteral(nodeId: Int, pin: String, text: String) = send(bpm.net.SetLiteralPayload(pos, nodeId, pin, text))
            override fun subscribe(on: Boolean) = send(bpm.net.RunSubscribePayload(pos, on))
        })
    }

    fun onRunFrame(p: bpm.net.RunFramePayload) {
        val v = runViews[p.pos] ?: return
        v.applyFrame(
            p.full, p.phase, p.paused, p.stopToken, p.addNodes, p.removeNodes, p.addLinks, p.removeLinks,
            p.contexts?.map { bpm.client.editor.RemoteRunView.context(it.id, it.name, it.entryNodeId, it.state, it.pauseReason, it.nodeId, it.error, it.sleepingForMs) },
            p.error,
        )
    }

    fun onRunLog(p: bpm.net.RunLogPayload) {
        val v = runViews[p.pos] ?: return
        v.applyLog(p.records.map { Triple(it.level, it.nodeId, it.message) }, p.records.map { it.repeats }, p.cleared)
    }

    fun onRunScopes(p: bpm.net.RunScopesPayload) {
        runViews[p.pos]?.applyScopes(p.contextId, p.frameIndex, p.scopes.map { scope(it) })
    }

    fun onBreakpoints(p: bpm.net.BreakpointsPayload) {
        runViews[p.pos]?.applyBreakpoints(p.nodeIds.indices.associate { p.nodeIds[it] to p.enabled[it] })
    }

    private fun onRunPause(m: bpm.net.RunPauseMsg) {
        val v = runViews[m.pos] ?: return
        v.applyPause(
            m.contextId, m.stopToken, m.reason,
            m.stack.map { dev.ziggle.vscript.runtime.StackFrame(it.index, it.chunkName, it.pc, it.nodeId, it.activation) },
            m.scopes.map { scope(it) },
            m.pinValues.map { Triple(it.nodeId, it.pin, it.display) },
            m.pureValues.map { it.nodeId to it.display },
        )
    }

    private fun scope(s: bpm.net.ScopeDto) = dev.ziggle.vscript.runtime.Scope(s.name, s.vars.map { dev.ziggle.vscript.runtime.Variable(it.name, null, it.nodeId, shown = it.display, typed = it.typeName) })

    /** Which document each controller's graph is, once the server has said. */
    val controllerDocs = HashMap<BlockPos, UUID>()
    private var pendingControllerOpen: BlockPos? = null

    /** `import` resolution over [libraryGraphs]: by id, then by name. */
    val graphSource: GraphSource = GraphSource { imp ->
        imp.docId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { libraryGraphs[it]?.graph }
            ?: libraryGraphs.values.firstOrNull { it.name.equals(imp.ref, ignoreCase = true) }?.graph
    }

    private val assembler = ChunkAssembler({ System.currentTimeMillis() }, maxTotalBytes = CLIENTBOUND_MAX_BYTES, maxRawBytes = DocumentCodec.MAX_RAW_BYTES)
    private var nextCommitId = 1
    private var ticks = 0L

    /** Set by the workbench while it is open so the heartbeat knows whom to keep alive. */
    val openDocuments: Collection<ClientDocument> get() = docs.values

    // ---- requests -------------------------------------------------------------------------------------------

    /** Nothing goes out without a connection — a screen that outlives the world must not throw on every frame. */
    private val connected: Boolean get() = Minecraft.getInstance().connection != null

    private fun send(payload: BpmPayload) {
        if (connected) Net.sendToServer(payload)
    }

    /** The linker's sneak + attack on air. */
    fun sendLinkerTrack(hand: net.minecraft.world.InteractionHand) = send(bpm.net.LinkerTrackPayload(hand))

    private fun sendBig(inner: String, bytes: ByteArray) {
        if (!connected) return
        for (c in Chunker.split(inner, bytes)) Net.sendToServer(ChunkPayload(c))
    }

    fun requestLibrary() {
        library.subscribed = true
        send(LibraryListRequestPayload())
    }

    fun create(name: String, json: String, library: Boolean = false) = sendBig(BigMessages.DOC_CREATE, BigMessages.encode { DocCreateMsg(name, json, library).write(it) })

    fun rename(docId: UUID, name: String) = send(DocRenamePayload(docId, name))

    fun delete(docId: UUID) = send(DocDeletePayload(docId))

    fun duplicate(docId: UUID, name: String) = send(DocDuplicatePayload(docId, name))

    fun fetch(docId: UUID) = send(DocFetchPayload(docId))

    /** Fetches the library graph [docId] unless a copy at the library's current version is already here. */
    fun ensureLibraryGraph(docId: UUID) {
        val have = libraryGraphs[docId]
        val listed = library[docId]
        if (have != null && (listed == null || listed.version == have.version)) return
        if (!fetching.add(docId)) return
        send(DocFetchPayload(docId))
    }

    /** Opens the graph of the controller at [pos] (the server creates it on first use); [controllerDocs] fills in on the reply. */
    fun openController(pos: BlockPos, wantEdit: Boolean) {
        controllers.getOrPut(pos) { ControllerView(pos) }
        pendingControllerOpen = pos
        send(EditorOpenPayload(NIL_UUID, wantEdit, pos))
    }

    /** Open [docId] in the editor; the server answers with a session state and the document. */
    fun open(docId: UUID, wantEdit: Boolean, controller: BlockPos? = null): ClientDocument {
        val doc = docs.getOrPut(docId) { ClientDocument(docId) }
        if (controller != null) controllers.getOrPut(controller) { ControllerView(controller) }
        send(EditorOpenPayload(docId, wantEdit, controller))
        return doc
    }

    fun close(docId: UUID) {
        if (docs.remove(docId) != null) send(EditorClosePayload(docId))
    }

    fun requestLease(docId: UUID, steal: Boolean) = send(LeaseRequestPayload(docId, steal))

    fun releaseLease(docId: UUID) = send(LeaseReleasePayload(docId))

    /**
     * Commits the editor's copy of [docId] as the next version (deploying it when [deploy]); the commit id,
     * or null when there is nothing to commit or one is already in flight.
     */
    fun commit(docId: UUID, deploy: Boolean): Int? {
        val doc = docs[docId] ?: return null
        if (doc.pendingCommit != null) return null
        val json = doc.toJson() ?: return null
        val id = nextCommitId++
        doc.pendingCommit = id
        sendBig(BigMessages.DOC_COMMIT, BigMessages.encode { DocCommitMsg(docId, doc.version, id, deploy, DocumentCodec.sha256(json), json).write(it) })
        return id
    }

    fun bindController(pos: BlockPos, docId: UUID?) = send(ControllerBindPayload(pos, docId))

    fun setControllerFlags(pos: BlockPos, enabled: Boolean, debugBuild: Boolean) = send(ControllerFlagsPayload(pos, enabled, debugBuild))

    fun runControl(pos: BlockPos, action: RunAction) = send(RunControlPayload(pos, action))

    fun editLink(pos: BlockPos, op: LinkOp, name: String, newName: String = "") = send(LinkEditPayload(pos, op, name, newName))

    fun watch(pos: BlockPos, on: Boolean) {
        if (on) controllers.getOrPut(pos) { ControllerView(pos) } else controllers.remove(pos)
        send(ControllerWatchPayload(pos, on))
    }

    // ---- handlers ---------------------------------------------------------------------------------------------

    fun onChunk(p: ChunkPayload) {
        when (val r = assembler.accept(p.chunk)) {
            is ChunkAssembler.Result.Rejected -> Bpm.LOGGER.warn("bad chunk from the server: {}", r.reason)
            ChunkAssembler.Result.Pending -> {}
            is ChunkAssembler.Result.Complete -> when (r.inner) {
                BigMessages.LIBRARY_LIST -> onLibraryList(BigMessages.decode(r.bytes, LibraryListMsg::read))
                BigMessages.DOC_PUSH -> onDocPush(BigMessages.decode(r.bytes, DocPushMsg::read))
                bpm.net.RunPauseMsg.NAME -> onRunPause(BigMessages.decode(r.bytes, bpm.net.RunPauseMsg::read))
                else -> Bpm.LOGGER.warn("unknown message '{}' from the server", r.inner)
            }
        }
    }

    private fun onLibraryList(msg: LibraryListMsg) {
        library.version = msg.libraryVersion
        library.records = msg.records
        for (r in msg.records) docs[r.id]?.let { d -> if (d.name != r.name) d.name = r.name }
        listeners.forEach { it.onLibrary(library) }
    }

    fun onLibraryChanged(p: LibraryChangedPayload) {
        if (p.deleted && p.docId != null) docs.remove(p.docId)
        if (library.subscribed && p.libraryVersion != library.version) requestLibrary()
    }

    private fun onDocPush(msg: DocPushMsg) {
        val graph = try {
            GraphDoc.fromJson(msg.json)
        } catch (e: Exception) {
            Bpm.LOGGER.warn("document {} from the server could not be parsed: {}", msg.docId, e.toString())
            fetching.remove(msg.docId)
            return
        }
        val doc = docs[msg.docId]
        if (doc == null) {
            // Not a document we have open: a library fetched to resolve imports.
            fetching.remove(msg.docId)
            libraryGraphs[msg.docId] = LibraryGraph(msg.docId, msg.name, msg.version, graph)
            listeners.forEach { it.onLibrary(library) }
            return
        }
        doc.name = msg.name
        doc.holderName = msg.holderName
        doc.role = msg.role
        doc.hasErrors = msg.hasErrors
        if (doc.dirty && doc.isHolder && doc.editor != null && msg.version != doc.version) {
            // The holder's own uncommitted work is never overwritten silently: the UI offers keep/replace.
            doc.conflict = msg
        } else {
            doc.editor = EditorDoc(graph)
            doc.version = msg.version
            doc.sha256 = msg.sha256
            doc.dirty = false
            doc.conflict = null
        }
        listeners.forEach { it.onDocument(doc) }
    }

    /** Resolves a [ClientDocument.conflict] by taking the server's version. */
    fun takeTheirs(docId: UUID) {
        val doc = docs[docId] ?: return
        val msg = doc.conflict ?: return
        doc.conflict = null
        doc.dirty = false
        onDocPush(msg)
    }

    fun onSessionState(p: SessionStatePayload) {
        pendingControllerOpen?.let { pos ->
            if (p.docId !in docs && p.reason != SessionReason.DELETED) {
                docs[p.docId] = ClientDocument(p.docId)
                controllerDocs[pos] = p.docId
                pendingControllerOpen = null
            }
        }
        val doc = docs[p.docId] ?: return
        doc.role = p.role
        doc.holderName = p.holderName
        doc.lastReason = p.reason
        if (p.reason == SessionReason.DELETED) docs.remove(p.docId)
        listeners.forEach { it.onSession(doc) }
    }

    fun onCommitResult(p: DocCommitResultPayload) {
        if (p.docId == NIL) {
            listeners.forEach { it.onCreateFailed(p.message) }
            return
        }
        val doc = docs[p.docId] ?: return
        if (doc.pendingCommit == p.commitId) doc.pendingCommit = null
        doc.lastResult = p
        doc.lastResultAt = System.currentTimeMillis()
        when (p.status) {
            CommitStatus.OK, CommitStatus.UNCHANGED -> {
                doc.version = p.version
                doc.sha256 = p.sha256
                doc.hasErrors = p.issues.any { it.error }
                doc.dirty = false
            }
            CommitStatus.CONFLICT -> {}
            else -> {}
        }
        listeners.forEach { it.onCommit(doc, p) }
    }

    fun onControllerStatus(p: ControllerStatusPayload) {
        val view = controllers.getOrPut(p.pos) { ControllerView(p.pos) }
        view.status = p
        listeners.forEach { it.onController(view) }
    }

    /** A link was renamed and the server rewrote the document: the same edit to the open copy, rebased on the new version. */
    fun onLinkRenamed(p: LinkRenamedPayload) {
        val doc = docs[p.docId] ?: return
        doc.editor?.let { editor ->
            for ((id, pin) in bpm.session.LinkRenames.references(editor.nodes, bpm.catalog.BpmCatalog.catalog, p.oldName)) editor.setLiteral(id, pin, p.newName)
        }
        doc.version = p.version
        if (!doc.dirty) doc.sha256 = p.sha256
        listeners.forEach { it.onDocument(doc) }
    }

    fun onEffect(p: EffectPayload) = bpm.client.fx.EffectManager.onPayload(p)

    fun onLinkTable(p: LinkTableSyncPayload) {
        val view = controllers.getOrPut(p.pos) { ControllerView(p.pos) }
        view.links = p.links
        listeners.forEach { it.onController(view) }
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------

    fun tick() {
        ticks++
        if (ticks % HEARTBEAT_TICKS == 0L) {
            for (d in docs.values) if (d.isHolder) send(SessionHeartbeatPayload(d.docId))
            assembler.expire()
        }
    }

    fun reset() {
        bpm.client.fx.EffectManager.clear()
        docs.clear()
        runViews.clear()
        controllers.clear()
        libraryGraphs.clear()
        fetching.clear()
        controllerDocs.clear()
        pendingControllerOpen = null
        library.version = -1
        library.records = emptyList()
        library.subscribed = false
    }

    private val NIL = NIL_UUID
    const val HEARTBEAT_TICKS = 100
    const val CLIENTBOUND_MAX_BYTES = 1 shl 20
}
