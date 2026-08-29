package bpm.net

import bpm.Bpm
import bpm.catalog.BpmCatalog
import bpm.client.net.ClientNet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.network.ConfigurationTask
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.function.Consumer

/**
 * Registers every payload with its handler. Handlers run on the main thread of their side (NeoForge's
 * default), which is the server thread for everything the server does and the render thread on the client —
 * so nothing here locks. Client handlers are reached through `ClientNet`, a class a dedicated server never
 * loads because the lambdas naming it never run there.
 */
object BpmNetwork {
    const val VERSION = "1"

    fun install(modBus: IEventBus) {
        modBus.addListener(RegisterPayloadHandlersEvent::class.java, Consumer(::register))
        modBus.addListener(RegisterConfigurationTasksEvent::class.java, Consumer { it.register(CatalogTask()) })
    }

    private fun register(event: RegisterPayloadHandlersEvent) {
        val r = event.registrar(Bpm.ID).versioned(VERSION)

        // Configuration: the catalogue handshake.
        r.configurationToClient(CatalogHelloPayload.TYPE, CatalogHelloPayload.CODEC) { p, ctx ->
            Bpm.LOGGER.info("catalogue handshake: server {} ({}), ours {}", p.hash.take(12), p.packs.joinToString(), BpmCatalog.hash.take(12))
            ctx.reply(CatalogAckPayload(BpmCatalog.hash, BpmCatalog.packList))
        }
        r.configurationToServer(CatalogAckPayload.TYPE, CatalogAckPayload.CODEC) { p, ctx -> ServerNet.onCatalogAck(p, ctx) }

        // Big messages, both ways.
        r.playBidirectional(ChunkPayload.TYPE, ChunkPayload.CODEC) { p, ctx ->
            if (ctx.flow() == PacketFlow.SERVERBOUND) ServerNet.onChunk(p, ctx) else ClientNet.onChunk(p)
        }

        // Client → server.
        r.playToServer(LibraryListRequestPayload.TYPE, LibraryListRequestPayload.CODEC) { p, ctx -> ServerNet.onLibraryListRequest(p, ctx) }
        r.playToServer(LinkerTrackPayload.TYPE, LinkerTrackPayload.CODEC) { p, ctx -> (ctx.player() as? net.minecraft.server.level.ServerPlayer)?.let { bpm.world.LinkerCombat.onTrackPayload(it, p.hand) } }
        r.playToServer(DocRenamePayload.TYPE, DocRenamePayload.CODEC) { p, ctx -> ServerNet.onDocRename(p, ctx) }
        r.playToServer(DocDeletePayload.TYPE, DocDeletePayload.CODEC) { p, ctx -> ServerNet.onDocDelete(p, ctx) }
        r.playToServer(DocDuplicatePayload.TYPE, DocDuplicatePayload.CODEC) { p, ctx -> ServerNet.onDocDuplicate(p, ctx) }
        r.playToServer(DocFetchPayload.TYPE, DocFetchPayload.CODEC) { p, ctx -> ServerNet.onDocFetch(p, ctx) }
        r.playToServer(EditorOpenPayload.TYPE, EditorOpenPayload.CODEC) { p, ctx -> ServerNet.onEditorOpen(p, ctx) }
        r.playToServer(EditorClosePayload.TYPE, EditorClosePayload.CODEC) { p, ctx -> ServerNet.onEditorClose(p, ctx) }
        r.playToServer(SessionHeartbeatPayload.TYPE, SessionHeartbeatPayload.CODEC) { p, ctx -> ServerNet.onHeartbeat(p, ctx) }
        r.playToServer(LeaseRequestPayload.TYPE, LeaseRequestPayload.CODEC) { p, ctx -> ServerNet.onLeaseRequest(p, ctx) }
        r.playToServer(LeaseReleasePayload.TYPE, LeaseReleasePayload.CODEC) { p, ctx -> ServerNet.onLeaseRelease(p, ctx) }
        r.playToServer(ControllerBindPayload.TYPE, ControllerBindPayload.CODEC) { p, ctx -> ServerNet.onControllerBind(p, ctx) }
        r.playToServer(ControllerFlagsPayload.TYPE, ControllerFlagsPayload.CODEC) { p, ctx -> ServerNet.onControllerFlags(p, ctx) }
        r.playToServer(RunControlPayload.TYPE, RunControlPayload.CODEC) { p, ctx -> ServerNet.onRunControl(p, ctx) }
        r.playToServer(LinkEditPayload.TYPE, LinkEditPayload.CODEC) { p, ctx -> ServerNet.onLinkEdit(p, ctx) }
        r.playToServer(ControllerWatchPayload.TYPE, ControllerWatchPayload.CODEC) { p, ctx -> ServerNet.onControllerWatch(p, ctx) }
        r.playToServer(RunSubscribePayload.TYPE, RunSubscribePayload.CODEC) { p, ctx -> ServerNet.onRunSubscribe(p, ctx) }
        r.playToServer(RunScopesRequestPayload.TYPE, RunScopesRequestPayload.CODEC) { p, ctx -> ServerNet.onRunScopesRequest(p, ctx) }
        r.playToServer(BreakpointSetPayload.TYPE, BreakpointSetPayload.CODEC) { p, ctx -> ServerNet.onBreakpointSet(p, ctx) }
        r.playToServer(SetVariablePayload.TYPE, SetVariablePayload.CODEC) { p, ctx -> ServerNet.onSetVariable(p, ctx) }
        r.playToServer(SetLiteralPayload.TYPE, SetLiteralPayload.CODEC) { p, ctx -> ServerNet.onSetLiteral(p, ctx) }

        // Server → client.
        r.playToClient(LibraryChangedPayload.TYPE, LibraryChangedPayload.CODEC) { p, _ -> ClientNet.onLibraryChanged(p) }
        r.playToClient(SessionStatePayload.TYPE, SessionStatePayload.CODEC) { p, _ -> ClientNet.onSessionState(p) }
        r.playToClient(DocCommitResultPayload.TYPE, DocCommitResultPayload.CODEC) { p, _ -> ClientNet.onCommitResult(p) }
        r.playToClient(ControllerStatusPayload.TYPE, ControllerStatusPayload.CODEC) { p, _ -> ClientNet.onControllerStatus(p) }
        r.playToClient(LinkTableSyncPayload.TYPE, LinkTableSyncPayload.CODEC) { p, _ -> ClientNet.onLinkTable(p) }
        r.playToClient(LinkRenamedPayload.TYPE, LinkRenamedPayload.CODEC) { p, _ -> ClientNet.onLinkRenamed(p) }
        r.playToClient(EffectPayload.TYPE, EffectPayload.CODEC) { p, _ -> ClientNet.onEffect(p) }
        r.playToClient(RunFramePayload.TYPE, RunFramePayload.CODEC) { p, _ -> ClientNet.onRunFrame(p) }
        r.playToClient(RunLogPayload.TYPE, RunLogPayload.CODEC) { p, _ -> ClientNet.onRunLog(p) }
        r.playToClient(RunScopesPayload.TYPE, RunScopesPayload.CODEC) { p, _ -> ClientNet.onRunScopes(p) }
        r.playToClient(BreakpointsPayload.TYPE, BreakpointsPayload.CODEC) { p, _ -> ClientNet.onBreakpoints(p) }
    }
}

/** Sends the server's catalogue during configuration; the client's `CatalogAck` finishes the task or ends the login. */
class CatalogTask : ICustomConfigurationTask {
    override fun run(sender: Consumer<CustomPacketPayload>) {
        sender.accept(CatalogHelloPayload(BpmCatalog.hash, BpmCatalog.packList))
    }

    override fun type(): ConfigurationTask.Type = TYPE

    companion object {
        val TYPE = ConfigurationTask.Type("${Bpm.ID}:catalog")
    }
}
