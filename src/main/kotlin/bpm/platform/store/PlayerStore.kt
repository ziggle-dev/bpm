package bpm.platform.store

import com.mojang.serialization.Codec
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * Per-player data that outlives death, dimension changes and logging out.
 *
 * This replaces NeoForge's data attachments, and it is worth saying why it replaces them with *nothing*
 * rather than with a seam. Attachments are per-loader — NeoForge has `AttachmentType`, Fabric's
 * equivalent arrived separately and behaves differently, and neither exists on every version we want to
 * reach. But the thing they are being used for here is three small values keyed by player UUID, and a
 * `SavedData` does that on plain vanilla.
 *
 * It is also the simpler of the two. An attachment needs `copyOnDeath()` to survive dying and a
 * `COPY_FROM` bridge on Fabric to survive at all; data that was never attached to the entity does not
 * need to be copied off it. The player object is replaced on death and on a dimension change; the UUID
 * is not, and that is what this is keyed on.
 *
 * Server thread only, like everything else that touches a `SavedData`.
 */
class PlayerKey<T>(val id: String, val codec: Codec<T>, val default: () -> T)

class PlayerStoreData : SavedData() {

    private val byPlayer = HashMap<UUID, CompoundTag>()

    private fun tagFor(player: UUID): CompoundTag = byPlayer.getOrPut(player) { CompoundTag() }

    fun <T> read(player: UUID, key: PlayerKey<T>): T {
        val stored = byPlayer[player]?.get(key.id) ?: return key.default()
        return key.codec.parse(NbtOps.INSTANCE, stored).result().orElse(null) ?: key.default()
    }

    fun <T> write(player: UUID, key: PlayerKey<T>, value: T) {
        val encoded = key.codec.encodeStart(NbtOps.INSTANCE, value).result().orElse(null) ?: return
        tagFor(player).put(key.id, encoded)
        setDirty()
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val list = net.minecraft.nbt.ListTag()
        for ((id, data) in byPlayer) {
            if (data.isEmpty) continue
            list.add(CompoundTag().also { it.putUUID("player", id); it.put("data", data) })
        }
        tag.put("players", list)
        return tag
    }

    companion object {
        private const val NAME = "bpm_players"

        fun load(tag: CompoundTag, @Suppress("UNUSED_PARAMETER") registries: HolderLookup.Provider): PlayerStoreData {
            val store = PlayerStoreData()
            val list = tag.getList("players", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val entry = list.getCompound(i)
                if (!entry.hasUUID("player")) continue
                store.byPlayer[entry.getUUID("player")] = entry.getCompound("data")
            }
            return store
        }

        private val FACTORY = Factory(::PlayerStoreData, ::load, null)

        fun get(server: MinecraftServer): PlayerStoreData = server.overworld().dataStorage.computeIfAbsent(FACTORY, NAME)
    }
}

object PlayerStore {
    fun <T> get(player: ServerPlayer, key: PlayerKey<T>): T =
        PlayerStoreData.get(player.server).read(player.uuid, key)

    fun <T> set(player: ServerPlayer, key: PlayerKey<T>, value: T) {
        PlayerStoreData.get(player.server).write(player.uuid, key, value)
    }
}
