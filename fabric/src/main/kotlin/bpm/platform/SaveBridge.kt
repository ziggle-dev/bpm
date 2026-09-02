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

    /**
     * The registries this block entity can reach, or the empty set before it is placed in a level.
     *
     * The older band's `getUpdateTag()` and `saveAdditional(tag)` are handed no registries at all, so the
     * mod's own hooks -- which take them on every band -- get them from the level instead.
     */
    protected fun blockRegistries(): HolderLookup.Provider =
        level?.registryAccess() ?: net.minecraft.core.RegistryAccess.EMPTY

    /** The tag sent to a client watching this block, in the shape [loadExtra] reads back. */
    protected open fun updateTag(registries: HolderLookup.Provider): CompoundTag = CompoundTag()

    //? if >=1.20.5 {
    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = updateTag(registries)
    //?} else {
    /*override fun getUpdateTag(): CompoundTag = updateTag(blockRegistries())
    *///?}

    /** Write the components this block entity puts on the stack it is picked up as. */
    protected open fun writeComponents(sink: ComponentSink) {}

    //? if >=1.20.5 {
    override fun collectImplicitComponents(builder: net.minecraft.core.component.DataComponentMap.Builder) {
        super.collectImplicitComponents(builder)
        writeComponents(object : ComponentSink {
            override fun <T : Any> set(type: bpm.platform.registry.ComponentKey<T>, value: T) {
                builder.set(type, value)
            }
        })
    }
    //?} else {
    /*// Nothing to collect: a stack carries no components on this band, so there is nowhere to put them.
    *///?}

    //? if >=1.21.5 {
    /*override fun applyImplicitComponents(input: net.minecraft.core.component.DataComponentGetter) {
        super.applyImplicitComponents(input)
        readComponents(object : ComponentSource {
            override fun <T : Any> get(type: bpm.platform.registry.ComponentKey<T>): T? = input.get(type)
        })
    }
    *///?} elif >=1.20.5 {
    override fun applyImplicitComponents(input: DataComponentInput) {
        super.applyImplicitComponents(input)
        readComponents(object : ComponentSource {
            override fun <T : Any> get(type: bpm.platform.registry.ComponentKey<T>): T? = input.get(type)
        })
    }
    //?} else {
    /*// Nothing to override. Implicit components arrived with components themselves at 1.20.5; below
    // that a block entity's data comes from its tag, which `load` and `saveAdditional` already handle.
    *///?}

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
    *///?} elif >=1.20.5 {
    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        saveExtra(tag, registries)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        loadExtra(tag, registries)
    }
    //?} else {
    /*/**
     * One tag, no registries, and `load` rather than `loadAdditional` -- the "read only my own fields"
     * hook arrived with the registries at 1.20.5. Both still read and write the same flat tag.
     */
    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        saveExtra(tag, blockRegistries())
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        loadExtra(tag, blockRegistries())
    }
    *///?}
}

/** The same for a projectile. Entities carry their own registry access, so nothing has to be guessed. */
abstract class SavingProjectile(type: EntityType<out Projectile>, level: Level) : Projectile(type, level) {

    protected open fun saveExtra(tag: CompoundTag, registries: HolderLookup.Provider) {}

    protected open fun loadExtra(tag: CompoundTag, registries: HolderLookup.Provider) {}
    /** `Entity.registryAccess()` arrived with the components; before it the level is what holds them. */
    protected fun projectileRegistries(): HolderLookup.Provider {
        //? if >=1.20.5 {
        return registryAccess()
        //?} else {
        /*return level().registryAccess()
        *///?}
    }

    //? if >=1.21.6 {
    /*override fun addAdditionalSaveData(output: net.minecraft.world.level.storage.ValueOutput) {
        super.addAdditionalSaveData(output)
        val tag = CompoundTag()
        saveExtra(tag, projectileRegistries())
        pushTag(output, tag)
    }

    override fun readAdditionalSaveData(input: net.minecraft.world.level.storage.ValueInput) {
        super.readAdditionalSaveData(input)
        loadExtra(pullTag(input), input.lookup())
    }
    *///?} else {
    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        saveExtra(tag, projectileRegistries())
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        loadExtra(tag, projectileRegistries())
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
/** Where a block entity writes the components it wants on the stack it is picked up as. */
interface ComponentSink {
    fun <T : Any> set(type: bpm.platform.registry.ComponentKey<T>, value: T)
}

interface ComponentSource {
    /** The value for [type], or null when the stack carried none. */
    fun <T : Any> get(type: bpm.platform.registry.ComponentKey<T>): T?
}
