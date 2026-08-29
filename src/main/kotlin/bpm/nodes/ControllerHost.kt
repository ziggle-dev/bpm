package bpm.nodes

import bpm.catalog.values.FilterValue
import bpm.runtime.TickJobs
import bpm.world.LinkTable
import bpm.world.ResolvedLink
import io.osrsx.vscript.log.LogLevel
import io.osrsx.vscript.vm.StructValue
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.neoforged.neoforge.energy.IEnergyStorage
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.items.IItemHandler

/**
 * What a node body may ask of the controller running it — the pack's own seam, as `NodeHost` is the
 * OSRS pack's.
 *
 * One per running controller. Everything here is server-thread only and answers about THIS controller: its
 * links, its inventory buffer, its jobs, its log. A node never sees a `BlockEntity`.
 */
interface ControllerHost {
    val level: ServerLevel
    val pos: BlockPos
    val links: LinkTable
    val jobs: TickJobs

    /** The server's tick count — the clock the script's `delay` runs on. */
    val tickCount: Long

    /** The registries stacks and predicates are read against. */
    val registries: RegistryAccess get() = level.registryAccess()

    /** A link by name, resolved against the world — null when there is no such link. */
    fun link(name: String): ResolvedLink?

    /** The capability a link's face offers right now, or null (unlinked, unloaded, or nothing there). */
    fun items(name: String): IItemHandler?
    fun fluids(name: String): IFluidHandler?
    fun energy(name: String): IEnergyStorage?

    /** The controller's own buffer, reachable as the reserved link `"self"`. */
    val selfInventory: IItemHandler

    /** The controller's own tanks and energy cell — what `self` names for the fluid and energy verbs. */
    val selfTanks: IFluidHandler
    val selfEnergy: IEnergyStorage

    fun entity(handle: Any?): Entity?

    /** A filter compiled against this world's registries — see [FilterValue.matcher]. */
    fun matcher(filter: StructValue?): FilterValue.Matcher = FilterValue.matcher(filter, registries)

    /** The same filter, asked of blocks — see [FilterValue.blockMatcher]. */
    fun blockMatcher(filter: StructValue?): FilterValue.BlockMatcher = FilterValue.blockMatcher(filter, registries)

    /** The redstone the controller emits on [side], and its current output. */
    fun emitSignal(side: Direction, strength: Int)
    fun emitted(side: Direction): Int

    /** Ask this script to finish its pass and sleep — `controller.sleep`. */
    fun requestSleep(reason: String)

    /** A line in the script's console. */
    fun log(level: LogLevel, message: String)

    /** A toast for the people watching this controller. */
    fun notify(level: String, message: String)

    /**
     * Something moved between two links — what the world draws as a stream between two rifts. [item] is a
     * registry id to show (an item, a fluid), or empty.
     */
    fun transferred(from: String, to: String, amount: Int, kind: bpm.net.EffectKind = bpm.net.EffectKind.ITEMS, item: String = "")

    /** An id for an action's BEGIN / PULSE / END — see [action]. */
    fun newEffectId(): Int = 0

    /** One step of a job at a block — a rift there with the tool at work; [item] is the tool's registry id. */
    fun action(stream: Int, op: bpm.net.EffectOp, kind: bpm.net.EffectKind, at: BlockPos, face: Int, item: String) {}

    /** Something happened in the world worth drawing — see the effect system. */
    fun effect(id: ResourceLocation, data: CompoundTag)

    companion object {
        const val SELF = "self"
    }
}

/**
 * The host the CLIENT builds descriptors with: it can declare every node and run none.
 *
 * Node bodies are only ever called by a VM on the server, and the client has none; what the client needs
 * is the catalogue, which the same declarations produce without running anything. Every member throws, so
 * a body that does run here fails loudly rather than answering about a world that is not there.
 */
object DetachedHost : ControllerHost {
    private fun none(): Nothing = throw IllegalStateException("bpm: node bodies do not run on the client")

    override val level: ServerLevel get() = none()
    override val pos: BlockPos get() = none()
    override val links: LinkTable get() = none()
    override val jobs: TickJobs get() = none()
    override val tickCount: Long get() = none()
    override val registries: RegistryAccess get() = none()
    override fun link(name: String): ResolvedLink? = none()
    override fun items(name: String): IItemHandler? = none()
    override fun fluids(name: String): IFluidHandler? = none()
    override fun energy(name: String): IEnergyStorage? = none()
    override val selfInventory: IItemHandler get() = none()
    override val selfTanks: IFluidHandler get() = none()
    override val selfEnergy: IEnergyStorage get() = none()
    override fun entity(handle: Any?): Entity? = none()
    override fun emitSignal(side: Direction, strength: Int) = none()
    override fun emitted(side: Direction): Int = none()
    override fun requestSleep(reason: String) = none()
    override fun log(level: LogLevel, message: String) = none()
    override fun notify(level: String, message: String) = none()
    override fun transferred(from: String, to: String, amount: Int, kind: bpm.net.EffectKind, item: String) = none()
    override fun effect(id: ResourceLocation, data: CompoundTag) = none()
}
