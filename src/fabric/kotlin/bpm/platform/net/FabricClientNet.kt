package bpm.platform.net

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * The client half of [FabricNet], kept in its own class so a dedicated server never loads
 * `ClientPlayNetworking`.
 *
 * `@Environment(CLIENT)` is Fabric's own marker: the class is stripped from a server jar entirely, which
 * turns "must not be reached on a server" from a convention into something the loader enforces.
 */
@Environment(EnvType.CLIENT)
object FabricClientNet {

    fun <P : CustomPacketPayload> receive(type: CustomPacketPayload.Type<P>, handler: (P) -> Unit) {
        ClientPlayNetworking.registerGlobalReceiver(type) { payload, context ->
            // Onto the render thread, for the same reason the server side hops to the game thread.
            context.client().execute { handler(payload) }
        }
    }

    fun send(payload: CustomPacketPayload) = ClientPlayNetworking.send(payload)
}
