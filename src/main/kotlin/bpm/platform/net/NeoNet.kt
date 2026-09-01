package bpm.platform.net

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor

/** NeoForge's `PacketDistributor`, which is the whole of this loader's send side. */
object NeoNet : PlatformNet {
    override fun sendToServer(payload: CustomPacketPayload) = PacketDistributor.sendToServer(payload)

    override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) =
        PacketDistributor.sendToPlayer(player, payload)
}
