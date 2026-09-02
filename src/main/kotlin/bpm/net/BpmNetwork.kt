package bpm.net

import bpm.client.mc.HudOverlay
import bpm.client.net.ClientNet
import bpm.platform.net.Net

/**
 * Every payload with its handler.
 *
 * Handlers run on the main thread of their side, so nothing here locks. Client handlers are reached
 * through `ClientNet`, a class a dedicated server never loads because the lambdas naming it never run
 * there.
 *
 * **Every server-bound handler is gated on the catalogue handshake.** A client that has not yet proved
 * its catalogue matches is ignored rather than answered — see [CatalogHandshake] for why the check moved
 * out of the login sequence and what that costs.
 */
object BpmNetwork {
    const val VERSION = "1"

    fun install() {
        CatalogHandshake.install()
        register()
    }

    /** Server-bound, and only from a client whose catalogue we have checked. */
    private inline fun <P : bpm.platform.net.BpmPayload> guarded(
        crossinline handler: (P, net.minecraft.server.level.ServerPlayer) -> Unit,
    ): (P, net.minecraft.server.level.ServerPlayer) -> Unit = { p, player ->
        if (CatalogHandshake.isVerified(player)) handler(p, player)
    }

    private fun register() {
        // The handshake itself, which cannot be gated on itself.
        Net.toClient(CatalogHelloPayload.TYPE, CatalogHelloPayload.CODEC) { p -> CatalogHandshake.onHello(p) }
        Net.toServer(CatalogAckPayload.TYPE, CatalogAckPayload.CODEC) { p, player -> CatalogHandshake.onAck(p, player) }

        // Big messages, both ways.
        Net.bidirectional(ChunkPayload.TYPE, ChunkPayload.CODEC, guarded(ServerNet::onChunk)) { p -> ClientNet.onChunk(p) }

        Net.toServer(BreakpointSetPayload.TYPE, BreakpointSetPayload.CODEC, guarded(ServerNet::onBreakpointSet))
        Net.toServer(ControllerBindPayload.TYPE, ControllerBindPayload.CODEC, guarded(ServerNet::onControllerBind))
        Net.toServer(ControllerFlagsPayload.TYPE, ControllerFlagsPayload.CODEC, guarded(ServerNet::onControllerFlags))
        Net.toServer(ControllerWatchPayload.TYPE, ControllerWatchPayload.CODEC, guarded(ServerNet::onControllerWatch))
        Net.toServer(DocDeletePayload.TYPE, DocDeletePayload.CODEC, guarded(ServerNet::onDocDelete))
        Net.toServer(DocDuplicatePayload.TYPE, DocDuplicatePayload.CODEC, guarded(ServerNet::onDocDuplicate))
        Net.toServer(DocFetchPayload.TYPE, DocFetchPayload.CODEC, guarded(ServerNet::onDocFetch))
        Net.toServer(DocRenamePayload.TYPE, DocRenamePayload.CODEC, guarded(ServerNet::onDocRename))
        Net.toServer(EditorClosePayload.TYPE, EditorClosePayload.CODEC, guarded(ServerNet::onEditorClose))
        Net.toServer(EditorOpenPayload.TYPE, EditorOpenPayload.CODEC, guarded(ServerNet::onEditorOpen))
        Net.toServer(HudInputPayload.TYPE, HudInputPayload.CODEC, guarded(ServerNet::onHudInput))
        Net.toServer(KeyEdgePayload.TYPE, KeyEdgePayload.CODEC, guarded(ServerNet::onKeyEdge))
        Net.toServer(LeaseReleasePayload.TYPE, LeaseReleasePayload.CODEC, guarded(ServerNet::onLeaseRelease))
        Net.toServer(LeaseRequestPayload.TYPE, LeaseRequestPayload.CODEC, guarded(ServerNet::onLeaseRequest))
        Net.toServer(LibraryListRequestPayload.TYPE, LibraryListRequestPayload.CODEC, guarded(ServerNet::onLibraryListRequest))
        Net.toServer(LinkEditPayload.TYPE, LinkEditPayload.CODEC, guarded(ServerNet::onLinkEdit))
        Net.toServer(LinkerTrackPayload.TYPE, LinkerTrackPayload.CODEC, guarded { p, player -> bpm.world.LinkerCombat.onTrackPayload(player, p.hand) })
        Net.toServer(MonitorDragPayload.TYPE, MonitorDragPayload.CODEC, guarded(ServerNet::onMonitorDrag))
        Net.toServer(MonitorTextPayload.TYPE, MonitorTextPayload.CODEC, guarded(ServerNet::onMonitorText))
        Net.toServer(RunControlPayload.TYPE, RunControlPayload.CODEC, guarded(ServerNet::onRunControl))
        Net.toServer(RunScopesRequestPayload.TYPE, RunScopesRequestPayload.CODEC, guarded(ServerNet::onRunScopesRequest))
        Net.toServer(RunSubscribePayload.TYPE, RunSubscribePayload.CODEC, guarded(ServerNet::onRunSubscribe))
        Net.toServer(SessionHeartbeatPayload.TYPE, SessionHeartbeatPayload.CODEC, guarded(ServerNet::onHeartbeat))
        Net.toServer(SetLiteralPayload.TYPE, SetLiteralPayload.CODEC, guarded(ServerNet::onSetLiteral))
        Net.toServer(SetVariablePayload.TYPE, SetVariablePayload.CODEC, guarded(ServerNet::onSetVariable))

        // Server -> client.
        Net.toClient(LibraryChangedPayload.TYPE, LibraryChangedPayload.CODEC) { p -> ClientNet.onLibraryChanged(p) }
        Net.toClient(SessionStatePayload.TYPE, SessionStatePayload.CODEC) { p -> ClientNet.onSessionState(p) }
        Net.toClient(DocCommitResultPayload.TYPE, DocCommitResultPayload.CODEC) { p -> ClientNet.onCommitResult(p) }
        Net.toClient(ControllerStatusPayload.TYPE, ControllerStatusPayload.CODEC) { p -> ClientNet.onControllerStatus(p) }
        Net.toClient(LinkTableSyncPayload.TYPE, LinkTableSyncPayload.CODEC) { p -> ClientNet.onLinkTable(p) }
        Net.toClient(LinkRenamedPayload.TYPE, LinkRenamedPayload.CODEC) { p -> ClientNet.onLinkRenamed(p) }
        Net.toClient(EffectPayload.TYPE, EffectPayload.CODEC) { p -> ClientNet.onEffect(p) }
        Net.toClient(HudPanelPayload.TYPE, HudPanelPayload.CODEC) { p -> HudOverlay.onPanel(p) }
        Net.toClient(KeyWatchPayload.TYPE, KeyWatchPayload.CODEC) { p -> bpm.client.Keys.onWatch(p) }
        Net.toClient(RunFramePayload.TYPE, RunFramePayload.CODEC) { p -> ClientNet.onRunFrame(p) }
        Net.toClient(RunLogPayload.TYPE, RunLogPayload.CODEC) { p -> ClientNet.onRunLog(p) }
        Net.toClient(RunScopesPayload.TYPE, RunScopesPayload.CODEC) { p -> ClientNet.onRunScopes(p) }
        Net.toClient(BreakpointsPayload.TYPE, BreakpointsPayload.CODEC) { p -> ClientNet.onBreakpoints(p) }
    }
}
