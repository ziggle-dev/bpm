package bpm.platform.net

import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer

/**
 * Sending a payload, without naming whose networking does it.
 *
 * The payloads themselves need no seam at all: `bpm.net.Payloads` and `bpm.net.RunPayloads` are written
 * against vanilla's `CustomPacketPayload` and `StreamCodec` and carry no loader types, so all forty-odd
 * of them cross unchanged. Only the two verbs that put one on the wire differ, and this is them.
 *
 * Registration is a separate problem and is not here: it is entangled with the configuration-phase
 * handshake, which has to become a play-phase one before it can be shared.
 */
interface PlatformNet {
    fun sendToServer(payload: CustomPacketPayload)
    fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload)
}

/**
 * The installed sender.
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

    fun sendToServer(payload: CustomPacketPayload) = backend.sendToServer(payload)

    fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) = backend.sendToPlayer(player, payload)
}
