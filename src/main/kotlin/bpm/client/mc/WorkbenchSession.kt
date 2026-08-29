package bpm.client.mc

import bpm.catalog.BpmCatalog
import bpm.client.editor.CommitInfo
import bpm.client.editor.CommitIssue
import bpm.client.editor.ControllerControl
import bpm.client.editor.ControllerInfo
import bpm.client.editor.DocRecord
import bpm.client.editor.DocumentStore
import bpm.client.editor.LinkView
import bpm.client.editor.OpenDoc
import bpm.client.editor.Workbench
import bpm.client.editor.WorkbenchHost
import bpm.client.net.ClientDocument
import bpm.client.net.ClientNet
import bpm.client.net.ControllerView
import bpm.library.Documents
import bpm.net.LinkOp
import bpm.net.RunAction
import bpm.session.Role
import bpm.session.SessionReason
import io.osrsx.vscript.model.GraphSource
import io.osrsx.vscript.runtime.EditorDoc
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.util.UUID

/**
 * The workbench's host on a real client: every interface in `bpm.client.editor` over [ClientNet]. One per
 * connection; the screen comes and goes, the session (open documents, cameras) stays so reopening is instant.
 * Discarded on logout.
 */
object WorkbenchSession {
    private val store = NetDocumentStore()
    private var controller: NetControllerControl? = null
    private var bench: Workbench? = null

    /** The one workbench of this connection, created on first use. */
    val workbench: Workbench
        get() = bench ?: Workbench(host(null)).also { bench = it }

    /** Point the workbench at the controller at [pos] (or none). */
    fun attach(pos: BlockPos?) {
        val h = host(pos)
        val b = bench
        if (b == null) bench = Workbench(h) else b.host = h
    }

    /** The host for the workbench attached to [pos] (or none). Watching the previous controller stops. */
    fun host(pos: BlockPos?): WorkbenchHost {
        val current = controller
        if (current != null && current.pos != pos) {
            ClientNet.watch(current.pos, false)
            controller = null
        }
        if (pos != null && controller == null) {
            controller = NetControllerControl(pos)
            ClientNet.watch(pos, true)
        }
        store.controllerPos = pos
        return WorkbenchHost(
            catalog = BpmCatalog.catalog,
            store = store,
            prefs = EditorPrefs,
            controller = controller,
            run = pos?.let { ClientNet.runView(it) },
            previews = BlockPreviewRenderer,
            icons = BlockPreviewRenderer,
            fluids = FluidLookProvider,
            playerName = Minecraft.getInstance().user.name,
        )
    }

    fun reset() {
        controller = null
        bench = null
    }

    private class NetDoc(val doc: ClientDocument) : OpenDoc {
        override val id: UUID get() = doc.docId
        override val name: String get() = doc.name
        override val version: Int get() = doc.version
        override val role: Role get() = doc.role
        override val holderName: String get() = doc.holderName
        override val hasErrors: Boolean get() = doc.hasErrors
        override val lastReason: SessionReason get() = doc.lastReason
        override val editor: EditorDoc? get() = doc.editor
        override var dirty: Boolean
            get() = doc.dirty
            set(value) {
                doc.dirty = value
            }
        override val committing: Boolean get() = doc.pendingCommit != null
        override val lastCommit: CommitInfo?
            get() = doc.lastResult?.let { r ->
                CommitInfo(r.status, r.version, r.message, r.issues.map { CommitIssue(it.error, it.message, it.nodeId) }, r.deployed, doc.lastResultAt)
            }
        override val hasConflict: Boolean get() = doc.conflict != null
    }

    private class NetDocumentStore : DocumentStore {
        private val open = HashMap<UUID, NetDoc>()

        override val libraries: List<DocRecord>
            get() = ClientNet.library.libraries.map { DocRecord(it.id, it.name, it.version, it.rawSize, it.hasErrors, it.holderName, true) }
        override val libraryVersion: Int get() = ClientNet.library.version
        override val graphSource: GraphSource get() = ClientNet.graphSource

        override fun refresh() = ClientNet.requestLibrary()
        override fun createLibrary(name: String) = ClientNet.create(name, Documents.blankLibrary(name), library = true)
        override fun ensureLibraryGraph(id: UUID) = ClientNet.ensureLibraryGraph(id)

        override fun openControllerGraph(): OpenDoc? {
            val pos = controllerPos ?: return null
            val id = ClientNet.controllerDocs[pos]
            if (id == null) {
                if (!askedController) {
                    askedController = true
                    ClientNet.openController(pos, wantEdit = true)
                }
                return null
            }
            val doc = ClientNet.docs[id] ?: return null
            val existing = open[id]
            if (existing != null && existing.doc === doc) return existing
            return NetDoc(doc).also { open[id] = it }
        }

        var controllerPos: BlockPos? = null
            set(value) {
                if (field != value) askedController = false
                field = value
            }
        private var askedController = false
        override fun rename(id: UUID, name: String) = ClientNet.rename(id, name)
        override fun delete(id: UUID) = ClientNet.delete(id)
        override fun duplicate(id: UUID, name: String) = ClientNet.duplicate(id, name)

        override fun open(id: UUID, wantEdit: Boolean): OpenDoc {
            val doc = ClientNet.open(id, wantEdit)
            val existing = open[id]
            if (existing != null && existing.doc === doc) return existing
            return NetDoc(doc).also { open[id] = it }
        }

        override fun close(id: UUID) {
            open.remove(id)
            ClientNet.close(id)
        }

        override fun requestLease(id: UUID, steal: Boolean) = ClientNet.requestLease(id, steal)
        override fun releaseLease(id: UUID) = ClientNet.releaseLease(id)
        override fun commit(id: UUID, deploy: Boolean): Boolean = ClientNet.commit(id, deploy) != null
        override fun takeTheirs(id: UUID) = ClientNet.takeTheirs(id)
        override fun keepMine(id: UUID) {
            ClientNet.docs[id]?.conflict = null
        }
    }

    private class NetControllerControl(val pos: BlockPos) : ControllerControl {
        private val view: ControllerView? get() = ClientNet.controllers[pos]
        override val label: String get() = "controller ${pos.x}, ${pos.y}, ${pos.z}"
        override val info: ControllerInfo?
            get() = view?.status?.let { s ->
                ControllerInfo(
                    s.status.name.lowercase(), s.docId, s.docName, s.docVersion, s.runningVersion, s.enabled, s.debugBuild, s.lastError, s.fibers, s.jobs, s.transfers, s.buffer,
                    s.tanks.map { bpm.client.editor.TankView(it.fluid, it.amount, it.capacity) }, s.energy, s.energyCapacity,
                )
            }
        override val links: List<LinkView>
            get() = view?.links?.map { l ->
                LinkView(l.name, l.pos.x, l.pos.y, l.pos.z, if (l.side < 0) null else Direction.from3DDataValue(l.side).name.lowercase(), l.dimension)
            } ?: emptyList()

        override fun bind(docId: UUID?) = ClientNet.bindController(pos, docId)
        override fun setFlags(enabled: Boolean, debugBuild: Boolean) = ClientNet.setControllerFlags(pos, enabled, debugBuild)
        override fun start() = ClientNet.runControl(pos, RunAction.START)
        override fun stop() = ClientNet.runControl(pos, RunAction.STOP)
        override fun restart() = ClientNet.runControl(pos, RunAction.RESTART)
        override fun renameLink(name: String, newName: String) = ClientNet.editLink(pos, LinkOp.RENAME, name, newName)
        override fun removeLink(name: String) = ClientNet.editLink(pos, LinkOp.REMOVE, name)
    }
}
