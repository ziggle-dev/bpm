package bpm.net

import bpm.platform.events.BpmEvents
import bpm.platform.ports.Droplets
import bpm.Bpm
import bpm.catalog.BpmCatalog
import bpm.library.BpmLibrary
import bpm.library.DocumentCodec
import bpm.net.chunk.ChunkAssembler
import bpm.net.chunk.Chunker
import bpm.runtime.RunViewPublisher
import bpm.runtime.RuntimeManager
import bpm.session.CommitPipeline
import bpm.session.LinkRenames
import bpm.session.CommitRequest
import bpm.session.CommitStatus
import bpm.session.EditorSessions
import bpm.session.LeaseOutcome
import bpm.session.Role
import bpm.session.SessionReason
import bpm.world.ControllerBlockEntity
import bpm.world.Link
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.model.GraphDoc
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import bpm.platform.net.Net
import java.util.UUID
import java.util.function.Consumer
import bpm.platform.keyId

/**
 * The server's side of every conversation: sessions and leases, the library, commits, controller control,
 * and who is watching which controller. Everything runs on the server thread (payload handlers and the
 * tick), so the state here is plain maps.
 */
object ServerNet {
    val sessions = EditorSessions({ RuntimeManager.clock.ticks })

    private val assemblers = HashMap<UUID, ChunkAssembler>()
    private val watches = HashMap<UUID, LinkedHashSet<GlobalPos>>()
    private val runWatches = HashMap<UUID, LinkedHashSet<GlobalPos>>()
    private var ticks = 0L

    const val STATUS_EVERY_TICKS = 20
    const val CONTROL_RANGE = 64.0

    fun install() {
        BpmEvents.serverStarting.listen { reset() }
        BpmEvents.serverTickEnd.listen(::tick)
        BpmEvents.playerLeave.listen(::onLogout)
    }

    private fun reset() {
        assemblers.clear()
        watches.clear()
        runWatches.clear()
        publishedRuntimes.clear()
        ticks = 0
        sessions.playerLeftAll()
    }

    // ---- sending ------------------------------------------------------------------------------------------

    private fun send(player: ServerPlayer, payload: CustomPacketPayload) = Net.sendToPlayer(player, payload)

    private fun sendBig(player: ServerPlayer, inner: String, bytes: ByteArray) {
        for (c in Chunker.split(inner, bytes)) Net.sendToPlayer(player, ChunkPayload(c))
    }

    private fun player(server: MinecraftServer, id: UUID): ServerPlayer? = server.playerList.getPlayer(id)

    /** Controllers to restart on deploy: the ones running the document, and the ones whose graph imports it. */
    private fun pipeline(server: MinecraftServer): CommitPipeline {
        val lib = BpmLibrary.get(server)
        return CommitPipeline(lib, BpmCatalog.catalog, { id ->
            val importers = lib.importers(id).toSet()
            RuntimeManager.all().filter { it.docId == id || it.docId in importers }
        })
    }

    private fun sendLibrary(player: ServerPlayer) {
        val lib = BpmLibrary.get(bpm.platform.serverOf(player))
        val records = lib.all.map { r ->
            LibraryRecordDto(r.id, r.name, r.version, r.rawSize, r.hasErrors, r.updatedAt, sessions[r.id]?.holderName ?: "", r.isLibrary)
        }
        sendBig(player, BigMessages.LIBRARY_LIST, BigMessages.encode { LibraryListMsg(lib.libraryVersion, records).write(it) })
    }

    private fun pushDoc(player: ServerPlayer, docId: UUID) {
        val lib = BpmLibrary.get(bpm.platform.serverOf(player))
        val r = lib[docId] ?: return
        val text = lib.text(docId) ?: return
        val role = sessions.roleOf(player.uuid, docId) ?: Role.VIEWER
        val msg = DocPushMsg(r.id, r.version, r.name, r.sha256, text, role, sessions[docId]?.holderName ?: "", r.hasErrors)
        sendBig(player, BigMessages.DOC_PUSH, BigMessages.encode { msg.write(it) })
    }

    private fun sessionState(player: ServerPlayer, docId: UUID, reason: SessionReason) {
        val role = sessions.roleOf(player.uuid, docId) ?: Role.VIEWER
        val version = BpmLibrary.get(bpm.platform.serverOf(player))[docId]?.version ?: 0
        send(player, SessionStatePayload(docId, role, sessions[docId]?.holderName ?: "", version, reason))
    }

    /** Everyone with the document open hears about a lease change; [special] gets its own reason. */
    private fun broadcastSession(server: MinecraftServer, docId: UUID, reason: SessionReason, special: UUID? = null, specialReason: SessionReason = reason) {
        for (id in sessions.participantsOf(docId)) {
            val p = player(server, id) ?: continue
            sessionState(p, docId, if (id == special) specialReason else reason)
        }
    }

    private fun libraryChanged(server: MinecraftServer, docId: UUID?, deleted: Boolean = false) {
        val version = BpmLibrary.get(server).libraryVersion
        for (id in sessions.librarySubscribers) player(server, id)?.let { send(it, LibraryChangedPayload(version, docId, deleted)) }
    }

    fun status(be: ControllerBlockEntity): ControllerStatusPayload {
        val server = be.level?.server
        val name = be.docId?.let { id -> server?.let { BpmLibrary.get(it)[id]?.name } } ?: ""
        val rt = be.runtime
        val buffer = (0 until be.inventory.slots).map { i ->
            val stack = be.inventory.stackIn(i)
            if (stack.isEmpty) "" to 0 else net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).toString() to stack.count
        }
        val tanks = (0 until be.tanks.tanks).map { i ->
            val f = be.tanks.inTank(i)
            // The panel speaks millibuckets, like everything a player sees.
            TankDto(
                if (f.isEmpty) "" else net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(f.fluid).toString(),
                f.mb,
                Droplets.toMb(be.tanks.tankCapacity(i)),
            )
        }
        return ControllerStatusPayload(
            be.blockPos, be.status, be.docId, name, be.docVersion, be.runningVersion, be.enabled, be.debugBuild, be.lastError,
            rt?.runtime?.fibers?.size ?: 0, rt?.jobs?.size ?: 0, rt?.transfers ?: 0, buffer,
            tanks, be.energy.stored.toInt(), be.energy.capacity.toInt(), be.maxLinks, be.maxPlayerLinks,
        )
    }

    private fun links(be: ControllerBlockEntity) = LinkTableSyncPayload(
        be.blockPos,
        be.links.all.map { it.toDto() },
    )

    /** Status (and links when [withLinks]) to everyone watching [be]. */
    fun broadcastController(be: ControllerBlockEntity, withLinks: Boolean = false) {
        val server = be.level?.server ?: return
        val key = GlobalPos.of(be.level!!.dimension(), be.blockPos)
        val status = status(be)
        val links = if (withLinks) links(be) else null
        for ((id, set) in watches) {
            if (key !in set) continue
            val p = player(server, id) ?: continue
            send(p, status)
            if (links != null) send(p, links)
        }
    }

    // ---- configuration --------------------------------------------------------------------------------------

    // ---- library --------------------------------------------------------------------------------------------

    fun onLibraryListRequest(@Suppress("UNUSED_PARAMETER") p: LibraryListRequestPayload, player: ServerPlayer) {
        sessions.subscribeLibrary(player.uuid)
        sendLibrary(player)
    }

    private fun onDocCreate(msg: DocCreateMsg, player: ServerPlayer) {
        val lib = BpmLibrary.get(bpm.platform.serverOf(player))
        val graph = try {
            GraphDoc.fromJson(msg.json)
        } catch (e: Exception) {
            send(player, DocCommitResultPayload(NIL, 0, CommitStatus.BAD_FORMAT, 0, "", "not a graph document: ${e.message}", emptyList(), 0))
            return
        }
        val record = lib.create(msg.name, msg.json, player.uuid, isLibrary = msg.library)
        val errors = Validator(BpmCatalog.catalog, lib.graphSource(), tickMayWait = true).validate(graph).count { it.severity == Severity.ERROR }
        if (errors > 0) lib.store(record.id, msg.json, hasErrors = true)
        libraryChanged(bpm.platform.serverOf(player), record.id)
        // The creator is editing it: open with the lease, push it.
        sessions.open(player.uuid, player.gameProfile.name, record.id, wantEdit = true)
        sessionState(player, record.id, SessionReason.GRANTED)
        pushDoc(player, record.id)
        Bpm.LOGGER.info("{} created {} '{}' ({})", player.gameProfile.name, if (msg.library) "library" else "document", record.name, record.id)
    }

    fun onDocRename(p: DocRenamePayload, player: ServerPlayer) {
        val lib = BpmLibrary.get(bpm.platform.serverOf(player))
        if (!mayManage(player, p.docId)) return
        if (lib.rename(p.docId, p.name)) libraryChanged(bpm.platform.serverOf(player), p.docId)
    }

    fun onDocDelete(p: DocDeletePayload, player: ServerPlayer) {
        val lib = BpmLibrary.get(bpm.platform.serverOf(player))
        if (!mayManage(player, p.docId)) return
        if (lib[p.docId] == null) return
        deleteDocument(bpm.platform.serverOf(player), p.docId)
    }

    /** Deletes a document everywhere: unbinds controllers, ends sessions, tells the library screens. */
    fun deleteDocument(server: MinecraftServer, docId: UUID) {
        val lib = BpmLibrary.get(server)
        if (lib[docId] == null) return
        RuntimeManager.all().filter { it.docId == docId }.forEach { it.bind(null) }
        val participants = sessions.documentDeleted(docId)
        lib.delete(docId)
        for (id in participants) player(server, id)?.let { send(it, SessionStatePayload(docId, Role.VIEWER, "", 0, SessionReason.DELETED)) }
        libraryChanged(server, docId, deleted = true)
    }

    fun onDocDuplicate(p: DocDuplicatePayload, player: ServerPlayer) {
        val lib = BpmLibrary.get(bpm.platform.serverOf(player))
        val src = lib[p.docId] ?: return
        val text = lib.text(src.id) ?: return
        val copy = lib.create(p.name.ifBlank { "${src.name} copy" }, text, player.uuid)
        if (src.hasErrors) lib.store(copy.id, text, hasErrors = true)
        libraryChanged(bpm.platform.serverOf(player), copy.id)
    }

    fun onDocFetch(p: DocFetchPayload, player: ServerPlayer) {
        pushDoc(player, p.docId)
    }

    /** Operators and the document's owner may rename, delete and steal. */
    private fun mayManage(player: ServerPlayer, docId: UUID): Boolean {
        if (player.hasPermissions(2)) return true
        val owner = BpmLibrary.get(bpm.platform.serverOf(player))[docId]?.owner
        return owner == null || owner == player.uuid
    }

    // ---- sessions ---------------------------------------------------------------------------------------------

    fun onEditorOpen(p: EditorOpenPayload, player: ServerPlayer) {
        val lib = BpmLibrary.get(bpm.platform.serverOf(player))
        val docId = if (p.isControllerGraph) {
            // The controller's own graph, made on first use.
            val be = controller(player, p.controller!!) ?: return
            be.ensureGraph(player.uuid)
        } else {
            p.docId
        }
        if (lib[docId] == null) {
            send(player, SessionStatePayload(docId, Role.VIEWER, "", 0, SessionReason.DELETED))
            return
        }
        val before = sessions.holderOf(docId)
        val open = sessions.open(player.uuid, player.gameProfile.name, docId, p.wantEdit)
        sessionState(player, docId, if (open.role == Role.HOLDER) SessionReason.GRANTED else SessionReason.NONE)
        if (open.role == Role.HOLDER && before != player.uuid) broadcastSession(bpm.platform.serverOf(player), docId, SessionReason.NONE, special = player.uuid, specialReason = SessionReason.GRANTED)
        pushDoc(player, docId)
        p.controller?.let { pos -> watch(player, pos, true) }
    }

    fun onEditorClose(p: EditorClosePayload, player: ServerPlayer) {
        if (sessions.close(player.uuid, p.docId)) broadcastSession(bpm.platform.serverOf(player), p.docId, SessionReason.RELEASED)
    }

    fun onHeartbeat(p: SessionHeartbeatPayload, player: ServerPlayer) {
        sessions.heartbeat(player.uuid, p.docId)
    }

    fun onLeaseRequest(p: LeaseRequestPayload, player: ServerPlayer) {
        val lease = sessions.requestLease(player.uuid, player.gameProfile.name, p.docId, p.steal, maySteal = mayManage(player, p.docId))
        when (lease.outcome) {
            LeaseOutcome.HELD_BY_OTHER -> sessionState(player, p.docId, SessionReason.NONE)
            LeaseOutcome.GRANTED -> broadcastSession(bpm.platform.serverOf(player), p.docId, SessionReason.NONE, special = player.uuid, specialReason = SessionReason.GRANTED)
            LeaseOutcome.STOLEN -> {
                for (id in sessions.participantsOf(p.docId)) {
                    val other = player(bpm.platform.serverOf(player), id) ?: continue
                    val reason = when (id) {
                        player.uuid -> SessionReason.GRANTED
                        lease.previous -> SessionReason.STOLEN
                        else -> SessionReason.NONE
                    }
                    sessionState(other, p.docId, reason)
                }
            }
        }
    }

    fun onLeaseRelease(p: LeaseReleasePayload, player: ServerPlayer) {
        if (sessions.release(player.uuid, p.docId)) broadcastSession(bpm.platform.serverOf(player), p.docId, SessionReason.RELEASED)
    }

    // ---- chunks and commits ----------------------------------------------------------------------------------

    fun onChunk(p: ChunkPayload, player: ServerPlayer) {
        val assembler = assemblers.getOrPut(player.uuid) {
            ChunkAssembler({ System.currentTimeMillis() }, maxTotalBytes = SERVERBOUND_MAX_BYTES, maxRawBytes = DocumentCodec.MAX_RAW_BYTES)
        }
        when (val r = assembler.accept(p.chunk)) {
            is ChunkAssembler.Result.Rejected -> {
                Bpm.LOGGER.warn("{} sent a bad chunk: {}", player.gameProfile.name, r.reason)
                player.connection.disconnect(Component.literal("bpm: ${r.reason}"))
            }
            ChunkAssembler.Result.Pending -> {}
            is ChunkAssembler.Result.Complete -> when (r.inner) {
                BigMessages.DOC_CREATE -> onDocCreate(BigMessages.decode(r.bytes, DocCreateMsg::read), player)
                BigMessages.DOC_COMMIT -> onDocCommit(BigMessages.decode(r.bytes, DocCommitMsg::read), player)
                else -> player.connection.disconnect(Component.literal("bpm: unknown message '${r.inner}'"))
            }
        }
    }

    private fun onDocCommit(msg: DocCommitMsg, player: ServerPlayer) {
        val outcome = pipeline(bpm.platform.serverOf(player)).commit(
            sessions.isHolder(player.uuid, msg.docId),
            CommitRequest(msg.docId, msg.baseVersion, msg.sha256, msg.json, msg.deploy),
        )
        val issues = outcome.issues.take(IssueDto.MAX).map { IssueDto(it.severity == Severity.ERROR, it.message, it.nodeId ?: -1) }
        send(player, DocCommitResultPayload(msg.docId, msg.commitId, outcome.status, outcome.version, outcome.sha256, outcome.message, issues, outcome.deployed))
        if (outcome.status == CommitStatus.OK) {
            for (id in sessions.participantsOf(msg.docId)) {
                if (id == player.uuid) continue
                player(bpm.platform.serverOf(player), id)?.let { pushDoc(it, msg.docId) }
            }
            libraryChanged(bpm.platform.serverOf(player), msg.docId)
        }
        if (outcome.deployed > 0) {
            for (be in RuntimeManager.all()) if (be.docId == msg.docId) broadcastController(be)
        }
    }

    // ---- controllers -----------------------------------------------------------------------------------------

    private fun controller(player: ServerPlayer, pos: BlockPos): ControllerBlockEntity? {
        if (!player.blockPosition().closerThan(pos, CONTROL_RANGE)) return null
        if (!bpm.platform.levelOf(player).isLoaded(pos)) return null
        return bpm.platform.levelOf(player).getBlockEntity(pos) as? ControllerBlockEntity
    }

    fun onControllerBind(p: ControllerBindPayload, player: ServerPlayer) {
        val be = controller(player, p.pos) ?: return
        if (p.docId != null && BpmLibrary.get(bpm.platform.serverOf(player))[p.docId] == null) return
        be.bind(p.docId)
        broadcastController(be)
    }

    fun onControllerFlags(p: ControllerFlagsPayload, player: ServerPlayer) {
        val be = controller(player, p.pos) ?: return
        val debugChanged = be.debugBuild != p.debugBuild
        be.debugBuild = p.debugBuild
        if (be.enabled != p.enabled) be.setEnabled(p.enabled) else if (debugChanged) be.requestRestart()
        be.setChanged()
        broadcastController(be)
    }

    /**
     * A watched key moved: hand it to every running controller that has a presence link for this player and
     * was granted `input`. Everything else — offline, out of range, tether pocketed, grant off — is `mayI`
     * saying no, and the key simply goes nowhere.
     */
    fun onKeyEdge(p: KeyEdgePayload, player: ServerPlayer) {
        val key = bpm.world.KeyNames.normalise(p.key)
        if (key.isEmpty()) return
        for (be in bpm.runtime.RuntimeManager.all()) {
            val rt = be.runtime ?: continue
            if (be.links.byPlayer(player.uuid) == null) continue
            if (rt.presence(player.uuid)?.mayI(bpm.world.Grant.INPUT) != true) continue
            rt.key(player.uuid, key, p.down, p.modifier)
        }
    }

    /**
     * A panel was pressed. Only the controller the payload names hears it, and only when that controller's
     * tether grants `input` — a client cannot press a panel it was never shown.
     */
    fun onHudInput(p: HudInputPayload, player: ServerPlayer) {
        val be = player.level().getBlockEntity(p.controller) as? ControllerBlockEntity ?: return
        val rt = be.runtime ?: return
        if (rt.presence(player.uuid)?.mayI(bpm.world.Grant.INPUT) != true) return
        if (p.press) {
            rt.hud.press(player.uuid, p.id)
            return
        }
        // What the number means is the WIDGET's to say, not the client's: a slider's range lives here.
        val widget = rt.hud[player.uuid]?.widgets?.firstOrNull { it.id == p.id } ?: return
        when (widget.kind) {
            bpm.world.devices.Widget.SLIDER -> rt.hud.setValue(player.uuid, p.id, p.value.coerceIn(0f, 1f).toDouble() * widget.max)
            bpm.world.devices.Widget.FIELD -> rt.hud.setText(player.uuid, p.id, p.text.take(MonitorTextPayload.MAX_TEXT))
            else -> rt.hud.setValue(player.uuid, p.id, p.value.toDouble())
        }
    }

    /**
     * A slider was dragged. The number is re-derived from the player's own look ray rather than believed:
     * a client that could name a value could set any slider on any wall from anywhere.
     */
    fun onMonitorDrag(p: MonitorDragPayload, player: ServerPlayer) {
        val be = bpm.world.devices.MonitorInput.originLookedAt(player, p.origin) ?: return
        val widget = be.widgets.firstOrNull { it.id == p.id && it.kind == bpm.world.devices.Widget.SLIDER } ?: return
        val ray = player.pick(bpm.world.devices.MonitorInput.REACH, 0f, false) as? net.minecraft.world.phys.BlockHitResult ?: return
        val looking = bpm.world.devices.MonitorInput.hit(player, ray.blockPos) ?: return
        if (looking.first.blockPos != be.blockPos || looking.second.widget.id != p.id) return
        be.setValue(p.id, looking.second.along * widget.max)
    }

    /** Text typed into a field. Same rule: the player has to be looking at that field. */
    fun onMonitorText(p: MonitorTextPayload, player: ServerPlayer) {
        val be = bpm.world.devices.MonitorInput.originLookedAt(player, p.origin) ?: return
        if (be.widgets.none { it.id == p.id && it.kind == bpm.world.devices.Widget.FIELD }) return
        be.setText(p.id, p.text.take(MonitorTextPayload.MAX_TEXT))
        be.press(p.id)
    }

    fun onRunControl(p: RunControlPayload, player: ServerPlayer) {
        val be = controller(player, p.pos) ?: return
        if (!mayControl(player, be)) return
        val rt = be.runtime
        when (p.action) {
            RunAction.START -> be.setEnabled(true)
            RunAction.STOP -> be.setEnabled(false)
            RunAction.RESTART -> be.requestRestart()
            RunAction.PAUSE -> rt?.debug?.pause(null)
            RunAction.RESUME -> rt?.debug?.resume()
            RunAction.STEP_OVER -> rt?.debug?.stepOver()
            RunAction.STEP_INTO -> rt?.debug?.stepInto()
            RunAction.STEP_OUT -> rt?.debug?.stepOut()
            RunAction.STEP_DATA -> rt?.debug?.stepIntoData()
            RunAction.SLEEP -> rt?.requestSleep("editor", 2_000)
            RunAction.WAKE -> if (rt == null || rt.isAsleep) be.requestRestart()
        }
        broadcastController(be)
    }

    fun onLinkEdit(p: LinkEditPayload, player: ServerPlayer) {
        val be = controller(player, p.pos) ?: return
        val changed = when (p.op) {
            LinkOp.RENAME -> p.newName.isNotBlank() && be.links.rename(p.name, p.newName.trim())
            LinkOp.REMOVE -> be.links.remove(p.name)
        }
        if (changed) {
            be.setChanged()
            broadcastController(be, withLinks = true)
            if (p.op == LinkOp.RENAME) renameInGraph(bpm.platform.serverOf(player), be, p.name, p.newName.trim())
        }
    }

    /**
     * The link renamed on [be] is renamed in its document too: the stored text is rewritten (a new version),
     * the document's holder is told to make the same edit to their copy and rebase on it (uncommitted work
     * survives), the viewers get the new text, and the enabled controllers running it restart on it.
     */
    private fun renameInGraph(server: MinecraftServer, be: ControllerBlockEntity, old: String, new: String) {
        val docId = be.docId ?: return
        val lib = BpmLibrary.get(server)
        val record = lib[docId] ?: return
        val text = lib.text(docId) ?: return
        val graph = runCatching { dev.ziggle.vscript.model.GraphDoc.fromJson(text) }.getOrNull() ?: return
        if (LinkRenames.rewrite(graph.nodes, BpmCatalog.catalog, old, new) == 0) return
        val stored = lib.store(docId, dev.ziggle.vscript.model.GraphDoc.toJson(graph), record.hasErrors) ?: return
        for (id in sessions.participantsOf(docId)) {
            val p = player(server, id) ?: continue
            if (sessions.isHolder(id, docId)) send(p, LinkRenamedPayload(docId, old, new, stored.version, stored.sha256)) else pushDoc(p, docId)
        }
        var restarted = 0
        if (!stored.hasErrors) for (c in RuntimeManager.all()) if (c.docId == docId && c.enabled) { c.requestRestart(); restarted++ }
        Bpm.LOGGER.info("bpm: link '{}' is now '{}' in '{}' (v{}); {} controllers restarting", old, new, record.name, stored.version, restarted)
    }

    fun onControllerWatch(p: ControllerWatchPayload, player: ServerPlayer) {
        watch(player, p.pos, p.watch)
    }

    private fun watch(player: ServerPlayer, pos: BlockPos, on: Boolean) {
        val key = GlobalPos.of(bpm.platform.levelOf(player).dimension(), pos)
        val set = watches.getOrPut(player.uuid) { LinkedHashSet() }
        if (on) {
            if (set.size >= MAX_WATCHES) return
            set.add(key)
            (bpm.platform.levelOf(player).getBlockEntity(pos) as? ControllerBlockEntity)?.let { be ->
                send(player, status(be))
                send(player, links(be))
            }
        } else {
            set.remove(key)
        }
    }

    // ---- run view ---------------------------------------------------------------------------------------------

    /** The lease holder of the controller's graph, or an operator, may drive its program. */
    private fun mayControl(player: ServerPlayer, be: ControllerBlockEntity): Boolean =
        player.hasPermissions(2) || be.docId?.let { sessions.isHolder(player.uuid, it) } == true

    private fun runWatchersOf(be: ControllerBlockEntity): List<ServerPlayer> {
        val server = be.level?.server ?: return emptyList()
        val key = GlobalPos.of(be.level!!.dimension(), be.blockPos)
        return runWatches.entries.filter { key in it.value }.mapNotNull { player(server, it.key) }
    }

    private fun context(c: dev.ziggle.vscript.runtime.Context) =
        ContextDto(c.id, c.name, c.entryNodeId, c.state.ordinal, c.pauseReason.ordinal, c.nodeId, c.error, c.sleepingForMs)

    private fun frame(be: ControllerBlockEntity, f: RunViewPublisher.Frame) = RunFramePayload(
        be.blockPos, f.full, f.phase, f.paused, f.stopToken, f.addNodes, f.removeNodes, f.addLinks, f.removeLinks,
        f.contexts?.map(::context), f.error,
    )

    private fun scopes(list: List<dev.ziggle.vscript.runtime.Scope>) =
        list.take(32).map { s -> ScopeDto(s.name, s.variables.take(512).map { VarDto(it.name, it.display, it.typeName, it.nodeId) }) }

    private fun pauseMsg(be: ControllerBlockEntity, p: RunViewPublisher.Pause) = RunPauseMsg(
        be.blockPos, p.contextId, p.stopToken, p.reason.ordinal,
        p.stack.map { FrameDto(it.index, it.chunkName, it.pc, it.nodeId, it.activation) },
        scopes(p.scopes),
        p.pinValues.map { (n, pin, d) -> PinValueDto(n, pin, d) },
        p.pureValues.map { (n, d) -> PinValueDto(n, "", d) },
    )

    private fun breakpointsPayload(be: ControllerBlockEntity): BreakpointsPayload {
        val ids = be.breakpoints.keys.toIntArray()
        return BreakpointsPayload(be.blockPos, ids, BooleanArray(ids.size) { be.breakpoints[ids[it]] == true })
    }

    fun onRunSubscribe(p: RunSubscribePayload, player: ServerPlayer) {
        val key = GlobalPos.of(bpm.platform.levelOf(player).dimension(), p.pos)
        val mine = runWatches.getOrPut(player.uuid) { LinkedHashSet() }
        if (!p.on) {
            mine.remove(key)
            return
        }
        val be = controller(player, p.pos) ?: return
        if (mine.size >= MAX_RUN_WATCHES_PER_PLAYER && key !in mine) return
        if (runWatchersOf(be).size >= MAX_RUN_WATCHERS && key !in mine) return
        mine.add(key)
        // The newcomer's picture: breakpoints, the whole live state, the recent log, and the stop if any.
        send(player, breakpointsPayload(be))
        val rt = be.runtime ?: return
        publishedRuntimes[key] = rt
        send(player, frame(be, rt.publisher.snapshot()))
        val recent = rt.runtime.log.records.takeLast(LogDto.MAX).map { LogDto(it.level.ordinal, it.nodeId, it.message, it.repeats) }
        send(player, RunLogPayload(be.blockPos, recent, cleared = true))
        rt.publisher.pause(force = true)?.let { sendBig(player, RunPauseMsg.NAME, BigMessages.encode { m -> pauseMsg(be, it).write(m) }) }
    }

    fun onRunScopesRequest(p: RunScopesRequestPayload, player: ServerPlayer) {
        val be = controller(player, p.pos) ?: return
        val rt = be.runtime ?: return
        send(player, RunScopesPayload(be.blockPos, p.contextId, p.frameIndex, scopes(rt.publisher.scopes(p.contextId, p.frameIndex))))
    }

    fun onBreakpointSet(p: BreakpointSetPayload, player: ServerPlayer) {
        val be = controller(player, p.pos) ?: return
        if (!mayControl(player, be)) return
        be.setBreakpoint(p.nodeId, p.enabled, p.remove)
        val table = breakpointsPayload(be)
        for (w in runWatchersOf(be)) send(w, table)
    }

    fun onSetVariable(p: SetVariablePayload, player: ServerPlayer) {
        val be = controller(player, p.pos) ?: return
        if (!mayControl(player, be)) return
        be.runtime?.runtime?.setVariable(p.name, parseDebugValue(p.text))
    }

    fun onSetLiteral(p: SetLiteralPayload, player: ServerPlayer) {
        val be = controller(player, p.pos) ?: return
        if (!mayControl(player, be) || !be.debugBuild) return
        be.runtime?.runtime?.setLiteral(p.nodeId, p.pin, parseDebugValue(p.text))
    }

    private val publishedRuntimes = HashMap<GlobalPos, Any?>()

    /** Every tick: what changed for each watched, running controller. */
    private fun publishRunViews(server: MinecraftServer) {
        if (runWatches.isEmpty()) return
        val byController = HashMap<GlobalPos, ArrayList<ServerPlayer>>()
        for ((id, set) in runWatches) {
            val p = player(server, id) ?: continue
            for (key in set) byController.getOrPut(key) { ArrayList() }.add(p)
        }
        for ((key, players) in byController) {
            val level = server.getLevel(key.dimension()) ?: continue
            if (!level.isLoaded(key.pos())) continue
            val be = level.getBlockEntity(key.pos()) as? ControllerBlockEntity ?: continue
            val rt = be.runtime
            if (publishedRuntimes[key] !== rt) {
                // A restart (or a stop): the watchers' picture is of a program that no longer exists.
                publishedRuntimes[key] = rt
                if (rt == null) {
                    val empty = RunFramePayload(be.blockPos, true, "IDLE", false, -1L, IntArray(0), IntArray(0), IntArray(0), IntArray(0), emptyList(), be.lastError)
                    players.forEach { send(it, empty); send(it, RunLogPayload(be.blockPos, emptyList(), cleared = true)) }
                    continue
                }
                val snap = frame(be, rt.publisher.snapshot())
                players.forEach { send(it, snap); send(it, RunLogPayload(be.blockPos, emptyList(), cleared = true)) }
                rt.publisher.pause(force = true)?.let { p -> val bytes = BigMessages.encode { m -> pauseMsg(be, p).write(m) }; players.forEach { sendBig(it, RunPauseMsg.NAME, bytes) } }
                continue
            }
            if (rt == null) continue
            rt.publisher.frame()?.let { f -> val payload = frame(be, f); players.forEach { send(it, payload) } }
            rt.publisher.logs()?.let { l ->
                val payload = RunLogPayload(be.blockPos, l.records.map { LogDto(it.level.ordinal, it.nodeId, it.message, it.repeats) }, l.cleared)
                players.forEach { send(it, payload) }
            }
            rt.publisher.pause()?.let { p ->
                val bytes = BigMessages.encode { m -> pauseMsg(be, p).write(m) }
                players.forEach { sendBig(it, RunPauseMsg.NAME, bytes) }
            }
            // Variables, a few times a second, only when they changed.
            if (ticks % LIVE_SCOPES_EVERY_TICKS == 0L) {
                for ((ctxId, sc) in rt.publisher.liveScopes()) {
                    val payload = RunScopesPayload(be.blockPos, ctxId, 0, scopes(sc))
                    players.forEach { send(it, payload) }
                }
            }
        }
    }

    // ---- tick / lifecycle ---------------------------------------------------------------------------------------

    private fun tick(server: MinecraftServer) {
        ticks++
        publishRunViews(server)
        for (e in sessions.expire()) {
            Bpm.LOGGER.info("lease on {} expired for {}", e.docId, e.holderName)
            broadcastSession(server, e.docId, SessionReason.NONE, special = e.holder, specialReason = SessionReason.EXPIRED)
        }
        if (ticks % STATUS_EVERY_TICKS == 0L) {
            for ((id, set) in watches) {
                if (set.isEmpty()) continue
                val p = player(server, id) ?: continue
                for (key in set) {
                    val level = server.getLevel(key.dimension()) ?: continue
                    if (!level.isLoaded(key.pos())) continue
                    val be = level.getBlockEntity(key.pos()) as? ControllerBlockEntity ?: continue
                    send(p, status(be))
                }
            }
            for (a in assemblers.values) a.expire()
        }
    }

    private fun onLogout(player: ServerPlayer) {
        assemblers.remove(player.uuid)
        watches.remove(player.uuid)
        runWatches.remove(player.uuid)
        val freed = sessions.playerLeft(player.uuid)
        for (docId in freed) broadcastSession(bpm.platform.serverOf(player), docId, SessionReason.HOLDER_LEFT)
    }

    private val NIL = UUID(0, 0)
    const val SERVERBOUND_MAX_BYTES = 256 * 1024
    const val MAX_WATCHES = 8
    const val MAX_RUN_WATCHERS = 8
    const val LIVE_SCOPES_EVERY_TICKS = 5
    const val MAX_RUN_WATCHES_PER_PLAYER = 2
}

/** The link table as the client draws it; kept here so `Link` stays a server type. */
fun Link.toDto() = LinkDto(name, pos, side?.get3DDataValue() ?: -1, dimension.keyId().toString(), player?.toString().orEmpty())
