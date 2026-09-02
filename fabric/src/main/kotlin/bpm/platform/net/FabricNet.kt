package bpm.platform.net

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
object FabricNet : PlatformNet {

    override fun <P : BpmPayload> toServer(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        handler: (P, ServerPlayer) -> Unit,
    ) {
        declareToServer(type, codec)
        receiveOnServer(type, codec, handler)
    }

    override fun <P : BpmPayload> toClient(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        handler: (P) -> Unit,
    ) {
        declareToClient(type, codec)
        if (bpm.platform.Platform.isClient) FabricClientNet.receive(type, codec, handler)
    }

    override fun <P : BpmPayload> bidirectional(
        type: PayloadType<P>,
        codec: PayloadCodec<P>,
        onServer: (P, ServerPlayer) -> Unit,
        onClient: (P) -> Unit,
    ) {
        declareToServer(type, codec)
        declareToClient(type, codec)
        receiveOnServer(type, codec, onServer)
        if (bpm.platform.Platform.isClient) FabricClientNet.receive(type, codec, onClient)
    }

    override fun sendToServer(payload: BpmPayload) = FabricClientNet.send(payload)

    override fun sendToPlayer(player: ServerPlayer, payload: BpmPayload) = sendPayloadToPlayer(player, payload)
}
