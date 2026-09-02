package bpm.platform.net

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/**
 * NeoForge's payload registration and `PacketDistributor`.
 *
 * Registrations are collected as they arrive and replayed against the registrar when
 * `RegisterPayloadHandlersEvent` fires, because that event is not open while the mod is wiring itself
 * up. Handlers run on the main thread of their side, which is the server thread for anything
 * server-bound and the render thread on the client — so nothing here needs a lock.
 */
object NeoNet : PlatformNet {

    private val pending = ArrayList<(PayloadRegistrar) -> Unit>()

    override fun <P : CustomPacketPayload> toServer(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, P>,
        handler: (P, ServerPlayer) -> Unit,
    ) {
        pending += { r ->
            r.playToServer(type, codec) { p, ctx -> (ctx.player() as? ServerPlayer)?.let { handler(p, it) } }
        }
    }

    override fun <P : CustomPacketPayload> toClient(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, P>,
        handler: (P) -> Unit,
    ) {
        pending += { r -> r.playToClient(type, codec) { p, _ -> handler(p) } }
    }

    override fun <P : CustomPacketPayload> bidirectional(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, P>,
        onServer: (P, ServerPlayer) -> Unit,
        onClient: (P) -> Unit,
    ) {
        /*
         * Two handlers from 1.21.9, one that inspects the flow before it.
         *
         * The single-handler `playBidirectional` used to register both directions. From 1.21.9 it
         * registers the SERVER side only and expects the client side through
         * `RegisterClientPayloadHandlersEvent` -- and NeoForge now VALIDATES that, refusing to load a mod
         * with a clientbound payload nobody handles.
         *
         * This mod has exactly one bidirectional payload, `bpm:chunk`, which carries every big message in
         * pieces: the whole editor protocol rides on it. So on that band the check is the difference
         * between a loud failure at load and an editor that silently receives nothing. The four-argument
         * form is also the clearer statement, since the two handlers were already separate functions --
         * but it does not exist below 1.21.9, so both spellings stay.
         */
        //? if >=1.21.9 {
        /*pending += { r ->
            r.playBidirectional(
                type,
                codec,
                { p, ctx -> (ctx.player() as? ServerPlayer)?.let { onServer(p, it) } },
                { p, _ -> onClient(p) },
            )
        }
        *///?} else {
        pending += { r ->
            r.playBidirectional(type, codec) { p, ctx ->
                if (ctx.flow() == PacketFlow.SERVERBOUND) (ctx.player() as? ServerPlayer)?.let { onServer(p, it) } else onClient(p)
            }
        }
        //?}
    }

    /**
     * Client to server.
     *
     * Split out of `PacketDistributor` into a client-only `ClientPacketDistributor` at 1.21.6, which is
     * honest -- this direction was only ever callable from a client, and the class it lived in is loaded
     * on dedicated servers too.
     */
    //? if >=1.21.6 {
    /*override fun sendToServer(payload: CustomPacketPayload) =
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(payload)
    *///?} else {
    override fun sendToServer(payload: CustomPacketPayload) = PacketDistributor.sendToServer(payload)
    //?}

    override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) =
        PacketDistributor.sendToPlayer(player, payload)

    fun onRegisterPayloads(event: net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent) {
        val registrar = event.registrar(bpm.Bpm.ID).versioned(bpm.net.BpmNetwork.VERSION)
        for (block in pending) block(registrar)
    }
}
