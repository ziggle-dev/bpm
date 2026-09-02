package bpm.platform.net

import bpm.platform.ResourceLocation
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.server.level.ServerPlayer

/**
 * The four things Fabric's networking does, under whichever API this band has.
 *
 * [FabricNet] keeps its shape across all of them -- declare a payload, register a receiver, send one
 * either way -- and every version difference lives here, which is the same arrangement `OffScreenAware`
 * uses for screens. Three eras, and the last is a different model rather than a rename:
 *
 *  - 26.1 and up: `PayloadTypeRegistry.serverboundPlay()` / `clientboundPlay()`.
 *  - 1.20.5 to 26.0: the same registries under their old names, `playC2S` / `playS2C`.
 *  - below 1.20.5: NO payload types at all. `CustomPacketPayload` does not exist, so Fabric works in raw
 *    channels -- an id and a buffer -- and the codec that turns one into the other is ours to keep.
 *    `rememberCodec` is that book, and it is why sending works on a band where the game cannot look a
 *    codec up from the payload alone.
 *
 * Decoding happens on the NETTY thread in every arm, before the handler is handed to the game thread.
 * That is not a shortcut: the buffer is released when the receiving call returns, so reading it later
 * reads freed memory. Only the decoded payload crosses the thread boundary.
 */

//? if >=26.1 {
/*/** Declare a client-to-server payload. */
internal fun <P : BpmPayload> declareToServer(type: PayloadType<P>, codec: PayloadCodec<P>) {
    net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(type, codec)
}

/** Declare a server-to-client payload. */
internal fun <P : BpmPayload> declareToClient(type: PayloadType<P>, codec: PayloadCodec<P>) {
    net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(type, codec)
}

/** Handle a client-to-server payload, on the game thread. */
internal fun <P : BpmPayload> receiveOnServer(
    type: PayloadType<P>,
    @Suppress("UNUSED_PARAMETER") codec: PayloadCodec<P>,
    handler: (P, ServerPlayer) -> Unit,
) {
    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(type) { payload, context ->
        context.server().execute { handler(payload, context.player()) }
    }
}

/** Send a payload to one player. */
internal fun sendPayloadToPlayer(player: ServerPlayer, payload: BpmPayload) {
    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload)
}
*///?} elif >=1.20.5 <26.1 {
/** Declare a client-to-server payload. */
internal fun <P : BpmPayload> declareToServer(type: PayloadType<P>, codec: PayloadCodec<P>) {
    net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(type, codec)
}

/** Declare a server-to-client payload. */
internal fun <P : BpmPayload> declareToClient(type: PayloadType<P>, codec: PayloadCodec<P>) {
    net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(type, codec)
}

/** Handle a client-to-server payload, on the game thread. */
internal fun <P : BpmPayload> receiveOnServer(
    type: PayloadType<P>,
    @Suppress("UNUSED_PARAMETER") codec: PayloadCodec<P>,
    handler: (P, ServerPlayer) -> Unit,
) {
    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(type) { payload, context ->
        context.server().execute { handler(payload, context.player()) }
    }
}

/** Send a payload to one player. */
internal fun sendPayloadToPlayer(player: ServerPlayer, payload: BpmPayload) {
    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload)
}
//?} else {
/*/**
 * Every codec this mod has declared, by payload id.
 *
 * The game holds this for us from 1.20.5 -- a payload knows its type and the type knows its codec. Below
 * that there is nothing to ask, and `sendToPlayer` is handed a payload and nothing else, so the mod has
 * to remember. Written once during registration and only read afterwards.
 */
private val codecs = HashMap<ResourceLocation, PayloadCodec<BpmPayload>>()

@Suppress("UNCHECKED_CAST")
private fun <P : BpmPayload> rememberCodec(type: PayloadType<P>, codec: PayloadCodec<P>) {
    codecs[type.id] = codec as PayloadCodec<BpmPayload>
}

/** A payload as bytes, ready for a raw channel. */
internal fun encodePayload(payload: BpmPayload): FriendlyByteBuf {
    val codec = codecs[payload.type().id]
        ?: error("no codec registered for " + payload.type().id + " -- declare the payload before sending it")
    val buf = FriendlyByteBuf(Unpooled.buffer())
    codec.write(buf, payload)
    return buf
}

internal fun <P : BpmPayload> declareToServer(type: PayloadType<P>, codec: PayloadCodec<P>) = rememberCodec(type, codec)

internal fun <P : BpmPayload> declareToClient(type: PayloadType<P>, codec: PayloadCodec<P>) = rememberCodec(type, codec)

internal fun <P : BpmPayload> receiveOnServer(
    type: PayloadType<P>,
    codec: PayloadCodec<P>,
    handler: (P, ServerPlayer) -> Unit,
) {
    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(type.id) {
        server, player, _, buf, _ ->
        // Decoded HERE, on the netty thread, because `buf` is released once this returns.
        val payload = codec.read(buf)
        server.execute { handler(payload, player) }
    }
}

internal fun sendPayloadToPlayer(player: ServerPlayer, payload: BpmPayload) {
    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload.type().id, encodePayload(payload))
}
*///?}
