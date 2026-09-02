package bpm.platform.net

import net.minecraft.server.level.ServerPlayer

/**
 * Registering and sending payloads, without naming whose networking does it.
 *
 * The payloads themselves need no LOADER seam: `bpm.net.Payloads` and `bpm.net.RunPayloads` carry no
 * loader types, so all forty-odd of them cross between NeoForge and Fabric unchanged. They do need a
 * VERSION seam, because `CustomPacketPayload` and `StreamCodec` only exist from 1.20.5 -- see
 * [bpm.platform.net.BpmPayload]. What differs per loader is where you hand one in, and how you send it.
 *
 * Handlers take a `ServerPlayer` rather than a context object. That is not a simplification for its own
 * sake — once the catalogue handshake moved to the play phase, `player()` was the only thing any handler
 * still asked a context for, and passing the answer directly deletes the same three lines from the top
 * of twenty-five methods.
 */
interface PlatformNet {
    fun <P : BpmPayload> toServer(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        handler: (P, ServerPlayer) -> Unit,
    )

    fun <P : BpmPayload> toClient(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        handler: (P) -> Unit,
    )

    fun <P : BpmPayload> bidirectional(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        onServer: (P, ServerPlayer) -> Unit,
        onClient: (P) -> Unit,
    )

    fun sendToServer(payload: BpmPayload)
    fun sendToPlayer(player: ServerPlayer, payload: BpmPayload)
}

/**
 * The installed networking.
 *
 * A `lateinit` rather than a service lookup on purpose: forgetting to wire it fails as
 * "lateinit property backend has not been initialized", which names the thing that is missing, at the
 * first send rather than somewhere inside a classloader.
 */
object Net {
    private lateinit var backend: PlatformNet

    fun install(impl: PlatformNet) {
        backend = impl
    }

    fun <P : BpmPayload> toServer(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        handler: (P, ServerPlayer) -> Unit,
    ) = backend.toServer(type, codec, handler)

    fun <P : BpmPayload> toClient(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        handler: (P) -> Unit,
    ) = backend.toClient(type, codec, handler)

    fun <P : BpmPayload> bidirectional(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        onServer: (P, ServerPlayer) -> Unit,
        onClient: (P) -> Unit,
    ) = backend.bidirectional(type, codec, onServer, onClient)

    fun sendToServer(payload: BpmPayload) = backend.sendToServer(payload)

    fun sendToPlayer(player: ServerPlayer, payload: BpmPayload) = backend.sendToPlayer(player, payload)
}
