package bpm.platform.net

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/**
 * Registering and sending payloads, without naming whose networking does it.
 *
 * The payloads themselves need no seam at all: `bpm.net.Payloads` and `bpm.net.RunPayloads` are written
 * against vanilla's `CustomPacketPayload` and `StreamCodec` and carry no loader types, so all forty-odd
 * of them cross unchanged. What differs is where you hand them in, and how you send one.
 *
 * Handlers take a `ServerPlayer` rather than a context object. That is not a simplification for its own
 * sake — once the catalogue handshake moved to the play phase, `player()` was the only thing any handler
 * still asked a context for, and passing the answer directly deletes the same three lines from the top
 * of twenty-five methods.
 */
interface PlatformNet {
    fun <P : CustomPacketPayload> toServer(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, P>,
        handler: (P, ServerPlayer) -> Unit,
    )

    fun <P : CustomPacketPayload> toClient(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, P>,
        handler: (P) -> Unit,
    )

    fun <P : CustomPacketPayload> bidirectional(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, P>,
        onServer: (P, ServerPlayer) -> Unit,
        onClient: (P) -> Unit,
    )

    fun sendToServer(payload: CustomPacketPayload)
    fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload)
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

    fun <P : CustomPacketPayload> toServer(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, P>,
        handler: (P, ServerPlayer) -> Unit,
    ) = backend.toServer(type, codec, handler)

    fun <P : CustomPacketPayload> toClient(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, P>,
        handler: (P) -> Unit,
    ) = backend.toClient(type, codec, handler)

    fun <P : CustomPacketPayload> bidirectional(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, P>,
        onServer: (P, ServerPlayer) -> Unit,
        onClient: (P) -> Unit,
    ) = backend.bidirectional(type, codec, onServer, onClient)

    fun sendToServer(payload: CustomPacketPayload) = backend.sendToServer(payload)

    fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) = backend.sendToPlayer(player, payload)
}
