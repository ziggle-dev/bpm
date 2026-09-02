package bpm.platform.net

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/**
 * Fabric's play-phase networking.
 *
 * Two differences from NeoForge worth naming, both of which this seam absorbs:
 *
 * The payload TYPE and the RECEIVER are registered separately here — `PayloadTypeRegistry` says a
 * payload exists and in which direction, `ServerPlayNetworking`/`ClientPlayNetworking` say what to do
 * with it — where NeoForge's registrar takes both at once. Nothing above this cares.
 *
 * There is no `PayloadRegistrar` to wait for, so unlike the NeoForge backend nothing is queued: Fabric
 * accepts registrations during mod initialisation, which is exactly when this is called.
 *
 * Client receivers go through [FabricClientNet], which is a separate class so that a dedicated server
 * never loads `ClientPlayNetworking`. The lambdas naming it never run there, but a class is loaded when
 * it is first *referenced*, not when it is called, so the reference has to be behind a check too.
 */
/*
 * The two play-phase registries, under whichever name this band gives them.
 *
 * 26.1 renamed all four by direction rather than by endpoint: `playC2S` became `serverboundPlay` and
 * `playS2C` became `clientboundPlay` (and the configuration pair likewise). Only the name moved -- both
 * still answer a `PayloadTypeRegistry` and `register` is unchanged -- so a pair of accessors covers it
 * and the four call sites below read the same on every band.
 */
//? if >=26.1 {
/*private fun toServerTypes(): PayloadTypeRegistry<RegistryFriendlyByteBuf> = PayloadTypeRegistry.serverboundPlay()

private fun toClientTypes(): PayloadTypeRegistry<RegistryFriendlyByteBuf> = PayloadTypeRegistry.clientboundPlay()
*///?} else {
private fun toServerTypes(): PayloadTypeRegistry<RegistryFriendlyByteBuf> = PayloadTypeRegistry.playC2S()

private fun toClientTypes(): PayloadTypeRegistry<RegistryFriendlyByteBuf> = PayloadTypeRegistry.playS2C()
//?}

object FabricNet : PlatformNet {

    override fun <P : CustomPacketPayload> toServer(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, P>,
        handler: (P, ServerPlayer) -> Unit,
    ) {
        toServerTypes().register(type, codec)
        ServerPlayNetworking.registerGlobalReceiver(type) { payload, context ->
            // Fabric hands the payload to the netty thread; the game thread is where our handlers belong.
            context.server().execute { handler(payload, context.player()) }
        }
    }

    override fun <P : CustomPacketPayload> toClient(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, P>,
        handler: (P) -> Unit,
    ) {
        toClientTypes().register(type, codec)
        if (bpm.platform.Platform.isClient) FabricClientNet.receive(type, handler)
    }

    override fun <P : CustomPacketPayload> bidirectional(
        type: CustomPacketPayload.Type<P>,
        codec: StreamCodec<in RegistryFriendlyByteBuf, P>,
        onServer: (P, ServerPlayer) -> Unit,
        onClient: (P) -> Unit,
    ) {
        toServerTypes().register(type, codec)
        toClientTypes().register(type, codec)
        ServerPlayNetworking.registerGlobalReceiver(type) { payload, context ->
            context.server().execute { onServer(payload, context.player()) }
        }
        if (bpm.platform.Platform.isClient) FabricClientNet.receive(type, onClient)
    }

    override fun sendToServer(payload: CustomPacketPayload) = FabricClientNet.send(payload)

    override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) =
        ServerPlayNetworking.send(player, payload)
}
