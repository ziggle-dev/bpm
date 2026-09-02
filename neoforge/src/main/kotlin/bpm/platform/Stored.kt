package bpm.platform

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData

/**
 * A blob of per-world data this mod keeps, said as "a compound out, a compound in".
 *
 * 1.21.5 made `SavedData` codec-driven: instead of a `save(CompoundTag, Provider)` override and a
 * loader function handed to a factory, a store is now declared as a `SavedDataType` carrying a
 * `Codec<T>`, and the storage does the reading and writing itself.
 *
 * A hand-written save/load pair IS a codec -- it is a `Codec<CompoundTag>` with an xmap either side --
 * so nothing here has to be redesigned to satisfy that. [writeTo] and the reader on [StoreType] are the
 * two halves, and each band assembles them the way it wants: as a `Factory` before 1.21.5, and as
 * `CompoundTag.CODEC.xmap` after. The four stores in this mod keep the bodies they already had.
 */
abstract class TagStore : SavedData() {

    /** Write everything this store keeps into [tag]. */
    abstract fun writeTo(tag: CompoundTag)

    // Nothing to override from 1.21.5: the codec on the store's StoreType does the writing, and it is
    // built out of writeTo at the point the type is declared. Before that, this is the override -- and
    // this comment is line-shaped on purpose, because a block comment sitting where a Stonecutter arm
    // would put its own is eaten by the switch.
    //? if <1.21.5 {
    final override fun save(tag: CompoundTag, registries: net.minecraft.core.HolderLookup.Provider): CompoundTag {
        writeTo(tag)
        return tag
    }
    //?}
}

/**
 * A store's name, how to make an empty one, and how to read one back.
 *
 * One instance per store, held as a `val` beside the class it describes -- [storeOf] keys its cache on
 * that identity, so a store type must be a singleton and not built per call.
 */
class StoreType<T : TagStore>(
    val name: String,
    val create: () -> T,
    val read: (CompoundTag) -> T,
)

//? if >=1.21.5 {
/*private val savedTypes = HashMap<StoreType<*>, net.minecraft.world.level.saveddata.SavedDataType<*>>()
*///?}

/**
 * The store of that type for [server], created on first use. Server thread only.
 *
 * Every store this mod keeps lives in the overworld's data storage rather than per dimension, because
 * every one of them is about the SERVER -- a player's slot in the boss dimension, the shared document
 * library -- and none of them is about a particular world.
 */
fun <T : TagStore> storeOf(server: MinecraftServer, type: StoreType<T>): T {
    //? if >=1.21.5 {
    /*@Suppress("UNCHECKED_CAST")
    val savedType = savedTypes.getOrPut(type) {
        net.minecraft.world.level.saveddata.SavedDataType(
            // An `Identifier` from 26.1, where it was a bare string before.
            //? if >=26.1 {
            /*bpm.platform.idOf(bpm.Bpm.ID, type.name),
            *///?} else {
            type.name,
            //?}
            java.util.function.Supplier { type.create() },
            CompoundTag.CODEC.xmap({ tag -> type.read(tag) }, { store -> CompoundTag().also(store::writeTo) }),
        )
    } as net.minecraft.world.level.saveddata.SavedDataType<T>
    return server.overworld().dataStorage.computeIfAbsent(savedType)
    *///?} else {
    val factory = SavedData.Factory({ type.create() }, { tag, _ -> type.read(tag) }, null)
    return server.overworld().dataStorage.computeIfAbsent(factory, type.name)
    //?}
}
