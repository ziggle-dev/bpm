package bpm.platform.net

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.server.level.ServerPlayer

/**
 * The client half of [FabricNet], kept in its own class so a dedicated server never loads
 * `ClientPlayNetworking`.
 *
 * `@Environment(CLIENT)` is Fabric's own marker: the class is stripped from a server jar entirely, which
 * turns "must not be reached on a server" from a convention into something the loader enforces.
 *
 * It takes the codec as well as the type from 1.20.1, for the reason given in [FabricNetOps]: below
 * 1.20.5 there are no payload types on the wire, only an id and a buffer, so the codec has to come with
 * the registration rather than being looked up from the payload.
 */
@Environment(EnvType.CLIENT)
object FabricClientNet {

    //? if >=1.20.5 {
    fun <P : BpmPayload> receive(
        type: PayloadType<P>,
        @Suppress("UNUSED_PARAMETER") codec: PayloadCodec<P>,
        handler: (P) -> Unit,
    ) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(type) { payload, context ->
            // Onto the render thread, for the same reason the server side hops to the game thread.
            context.client().execute { handler(payload) }
        }
    }

    fun send(payload: BpmPayload) =
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(payload)
    //?} else {
    /*fun <P : BpmPayload> receive(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        handler: (P) -> Unit,
    ) {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(type.id) {
            client, _, buf, _ ->
            // Decoded on the netty thread; `buf` is released when this returns.
            val payload = codec.read(buf)
            client.execute { handler(payload) }
        }
    }

    fun send(payload: BpmPayload) =
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
            payload.type().id,
            encodePayload(payload),
        )
    *///?}
}
