package bpm.platform

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Where this mod's own persistence meets vanilla's.
 *
 * 1.21.6 replaced the `CompoundTag` handed to `saveAdditional` and `addAdditionalSaveData` with a
 * `ValueOutput` -- a codec-driven writer with a problem reporter behind it -- and the matching read with
 * a `ValueInput`. That is a real improvement to VANILLA's save plumbing, and it is also not the mod's
 * data model: `CompoundTag` is still what `getUpdateTag` returns on every band up to 26.2, still what
 * this mod's forty-one network payloads carry, and still what its scripting values serialise through.
 *
 * So the bridge is here rather than through the whole mod. A subclass writes a `CompoundTag` exactly as
 * it always did; this hands vanilla whatever vanilla is asking for that year. The one visible
 * consequence is that from 1.21.9 the mod's fields live one level down, under [BPM_DATA], rather than
 * flat beside vanilla's -- which is fine because saves do not cross bands (a stated clean break), and is
 * arguably tidier: it is now obvious in a save file which keys belong to this mod.
 *
 * The alternative -- teaching 1.21.1 the `ValueOutput` API and writing every save body against it -- is
 * the shape this project usually prefers, and was rejected here on the grounds that the shim would be a
 * reimplementation of a codec-driven writer rather than a rename, and the shared code speaks CompoundTag
 * to four other systems that are not going anywhere.
 */
private const val BPM_DATA = "bpm"

//? if >=1.21.6 {
/*/** Hand a whole compound to vanilla's writer, under one key. Empty compounds are not written. */
internal fun pushTag(output: net.minecraft.world.level.storage.ValueOutput, tag: CompoundTag) {
    if (!tag.isEmpty) output.store(BPM_DATA, CompoundTag.CODEC, tag)
}

/** Take it back, or an empty one for something saved before this mod was installed. */
internal fun pullTag(input: net.minecraft.world.level.storage.ValueInput): CompoundTag =
    input.read(BPM_DATA, CompoundTag.CODEC).orElseGet { CompoundTag() }
*///?}

/**
 * A block entity that saves a compound of its own.
 *
 * [saveExtra] and [loadExtra] are what `saveAdditional` and `loadAdditional` used to be. They are given
 * the registry lookup because half the things this mod persists -- inventories, fluid tanks -- need one.
 */
abstract class SavingBlockEntity(type: net.minecraft.world.level.block.entity.BlockEntityType<*>, pos: BlockPos, state: BlockState) :
    BlockEntity(type, pos, state) {

    /** Write this block entity's own fields. */
    protected open fun saveExtra(tag: CompoundTag, registries: HolderLookup.Provider) {}

    /** Read them back. The tag is empty when there was nothing saved. */
    protected open fun loadExtra(tag: CompoundTag, registries: HolderLookup.Provider) {}

    /** Take whatever this block entity keeps from the components on the stack it was placed from. */
    protected open fun readComponents(components: ComponentSource) {}

    /**
     * A tag for the client, in the same shape [loadExtra] will read it back from.
     *
     * `getUpdateTag` and `saveAdditional` are two ways of writing the same fields, and vanilla reads BOTH
     * back through `loadAdditional` -- an update packet is turned into a `ValueInput` and handed to the
     * same method a save is. So the two writers have to agree about shape, and from 1.21.9 this one nests
     * the mod's fields under a single key (see the note at the top of this file). A `getUpdateTag` that
     * writes them flat sends a tag the loader then finds nothing in.
     *
     * That is not a hypothetical: it is why a controller arrived on the client with an empty link table
     * and the linker drew the block it was aimed at but none of its links -- the one piece of the HUD
     * that needs no synced data was the only piece that worked.
     */
    protected fun syncTag(fill: (CompoundTag) -> Unit): CompoundTag {
        //? if >=1.21.6 {
        /*return CompoundTag().also { outer -> outer.put(BPM_DATA, CompoundTag().also(fill)) }
        *///?} else {
        return CompoundTag().also(fill)
        //?}
    }

    //? if >=1.21.5 {
    /*override fun applyImplicitComponents(input: net.minecraft.core.component.DataComponentGetter) {
        super.applyImplicitComponents(input)
        readComponents(object : ComponentSource {
            override fun <T : Any> get(type: net.minecraft.core.component.DataComponentType<T>): T? = input.get(type)
        })
    }
    *///?} else {
    override fun applyImplicitComponents(input: DataComponentInput) {
        super.applyImplicitComponents(input)
        readComponents(object : ComponentSource {
            override fun <T : Any> get(type: net.minecraft.core.component.DataComponentType<T>): T? = input.get(type)
        })
    }
    //?}

    //? if >=1.21.6 {
    /*override fun saveAdditional(output: net.minecraft.world.level.storage.ValueOutput) {
        super.saveAdditional(output)
        val tag = CompoundTag()
        saveExtra(tag, level?.registryAccess() ?: net.minecraft.core.RegistryAccess.EMPTY)
        pushTag(output, tag)
    }

    override fun loadAdditional(input: net.minecraft.world.level.storage.ValueInput) {
        super.loadAdditional(input)
        loadExtra(pullTag(input), input.lookup())
    }
    *///?} else {
    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        saveExtra(tag, registries)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        loadExtra(tag, registries)
    }
    //?}
}

/** The same for a projectile. Entities carry their own registry access, so nothing has to be guessed. */
abstract class SavingProjectile(type: EntityType<out Projectile>, level: Level) : Projectile(type, level) {

    protected open fun saveExtra(tag: CompoundTag, registries: HolderLookup.Provider) {}

    protected open fun loadExtra(tag: CompoundTag, registries: HolderLookup.Provider) {}

    //? if >=1.21.6 {
    /*override fun addAdditionalSaveData(output: net.minecraft.world.level.storage.ValueOutput) {
        super.addAdditionalSaveData(output)
        val tag = CompoundTag()
        saveExtra(tag, registryAccess())
        pushTag(output, tag)
    }

    override fun readAdditionalSaveData(input: net.minecraft.world.level.storage.ValueInput) {
        super.readAdditionalSaveData(input)
        loadExtra(pullTag(input), input.lookup())
    }
    *///?} else {
    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        saveExtra(tag, registryAccess())
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        loadExtra(tag, registryAccess())
    }
    //?}
}

/**
 * The components a block entity is given when it is placed from a stack.
 *
 * There is no typealias for the argument, because `BlockEntity.DataComponentInput` is PROTECTED: a
 * public alias to it does not compile, and it is only nameable from inside a subclass. That is also the
 * answer -- [SavingBlockEntity] is a subclass, so the override lives there and hands the shared code a
 * reader of its own. Which is the better shape anyway: 1.21.9 replaced that nested type with the
 * top-level `DataComponentGetter`, and a caller that asks for one component by type never cared.
 */
interface ComponentSource {
    /** The value for [type], or null when the stack carried none. */
    fun <T : Any> get(type: net.minecraft.core.component.DataComponentType<T>): T?
}
