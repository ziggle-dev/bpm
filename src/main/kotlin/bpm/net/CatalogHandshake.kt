package bpm.net

import bpm.Bpm
import bpm.catalog.BpmCatalog
import bpm.platform.events.BpmEvents
import bpm.platform.net.Net
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * The catalogue handshake, in the play phase.
 *
 * It used to run during the configuration phase, through NeoForge's `ICustomConfigurationTask`. Fabric
 * has no configuration-phase task API and 1.20.1 would not have had one either, so it had to move — and
 * moving it uniformly, on every loader, is better than two code paths for a security check.
 *
 * **This changes what a mismatched client sees.** Before, they were refused during login and never
 * entered the world. Now they join, and are kicked within [DEADLINE_TICKS] with the same message. The
 * catalogue is what a graph's nodes are validated against, so a client whose catalogue disagrees would
 * render an editor that lies about what the server will accept; kicking a moment late is a worse
 * experience than refusing at login, but it is not a weaker check.
 *
 * The gate is what keeps that true: an unverified player's payloads are ignored, so the window between
 * joining and answering is not a window in which anything can be done.
 */
object CatalogHandshake {

    /** How long a client has to answer. Ten seconds is generous for a packet round trip. */
    const val DEADLINE_TICKS = 200L

    private val verified = HashSet<UUID>()
    private val waiting = HashMap<UUID, Long>()
    private var ticks = 0L

    fun install() {
        BpmEvents.serverStarting.listen { reset() }
        BpmEvents.playerJoin.listen(::onJoin)
        BpmEvents.playerLeave.listen { forget(it.uuid) }
        BpmEvents.serverTickEnd.listen(::onTick)
    }

    private fun reset() {
        verified.clear()
        waiting.clear()
        ticks = 0
    }

    fun forget(player: UUID) {
        verified.remove(player)
        waiting.remove(player)
    }

    /**
     * Whether this player has proved their catalogue matches.
     *
     * Every server-bound handler asks this first. A player who has not answered yet is not hostile — they
     * are usually a tick or two from answering — so this is silent rather than logged.
     */
    fun isVerified(player: ServerPlayer): Boolean = player.uuid in verified

    private fun onJoin(player: ServerPlayer) {
        waiting[player.uuid] = ticks
        Net.sendToPlayer(player, CatalogHelloPayload(BpmCatalog.hash, BpmCatalog.packList))
    }

    private fun onTick(server: MinecraftServer) {
        ticks++
        if (waiting.isEmpty()) return
        val late = waiting.filterValues { ticks - it > DEADLINE_TICKS }.keys.toList()
        for (id in late) {
            waiting.remove(id)
            val player = server.playerList.getPlayer(id) ?: continue
            Bpm.LOGGER.warn("catalogue handshake: {} did not answer", player.gameProfile.name)
            player.connection.disconnect(Component.literal("bpm: the catalogue handshake was not answered"))
        }
    }

    /** The client's answer. Either it matches and they may talk to us, or they are told why not. */
    fun onAck(p: CatalogAckPayload, player: ServerPlayer) {
        waiting.remove(player.uuid)
        val problem = CatalogCompare.mismatch(BpmCatalog.hash, BpmCatalog.packList, p.hash, p.packs)
        if (problem != null) {
            Bpm.LOGGER.warn("catalogue handshake refused for {}: {}", player.gameProfile.name, problem)
            player.connection.disconnect(Component.literal(problem))
            return
        }
        Bpm.LOGGER.info("catalogue handshake ok for {} ({})", player.gameProfile.name, p.hash.take(12))
        verified += player.uuid
    }

    /** The server's greeting, on the client. */
    fun onHello(p: CatalogHelloPayload) {
        Bpm.LOGGER.info(
            "catalogue handshake: server {} ({}), ours {}",
            p.hash.take(12), p.packs.joinToString(), BpmCatalog.hash.take(12),
        )
        Net.sendToServer(CatalogAckPayload(BpmCatalog.hash, BpmCatalog.packList))
    }
}
