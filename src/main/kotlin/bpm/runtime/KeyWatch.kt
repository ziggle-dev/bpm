package bpm.runtime

import bpm.net.KeyWatchDto
import bpm.net.KeyWatchPayload
import bpm.world.KeyNames
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

/**
 * Which raw keys each player's client is asked to report.
 *
 * A client that reported every keystroke would be a keylogger and a packet flood, so it reports only the keys
 * some running graph has actually asked about. The set is the union across every controller that can reach
 * that player, recomputed when a graph shows interest in a key it had not named before — which happens once
 * per key per run, not per tick.
 *
 * Server thread only. See `docs/DESIGN_PLAYER_LINK.md` §9.
 */
object KeyWatch {
    /** What each client was last told to watch, so an unchanged set costs no packet. */
    private val sent = HashMap<UUID, List<Triple<String, Boolean, Boolean>>>()

    /**
     * Recompute [player]'s watch set from every running controller and push it if it changed.
     *
     * Walking the controllers is O(n), which is fine because this is called when a *new* key is named, not
     * when one is polled.
     */
    fun refresh(player: ServerPlayer) {
        // Two graphs may want the same key differently — one bare, one with the modifier, one swallowing it.
        // The client is told the union: report it either way, and swallow it if anyone asked.
        val union = LinkedHashMap<String, BooleanArray>()
        for (be in RuntimeManager.all()) {
            val rt = be.runtime ?: continue
            for ((name, wish) in rt.keyWishes(player.uuid)) {
                if (name !in union && union.size >= KeyNames.MAX_WATCHED) continue
                val flags = union.getOrPut(name) { BooleanArray(2) }
                if (wish.consume && !wish.requireModifier) flags[0] = true
                if (wish.consume && wish.requireModifier) flags[1] = true
            }
        }
        val want = union.map { (name, f) -> Triple(name, f[0], f[1]) }
        if (sent[player.uuid] == want) return
        sent[player.uuid] = want
        PacketDistributor.sendToPlayer(player, KeyWatchPayload(want.map { KeyWatchDto(it.first, it.second, it.third) }))
    }

    /** A player left, or a controller stopped: drop what we think they know so the next push is unconditional. */
    fun forget(player: UUID) {
        sent.remove(player)
    }

    fun clear() = sent.clear()
}
