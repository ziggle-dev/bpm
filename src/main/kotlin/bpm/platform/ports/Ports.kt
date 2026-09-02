package bpm.platform.ports

import bpm.platform.ComponentPatch
import bpm.platform.emptyPatch
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids

/**
 * What a controller can move things through: items, fluid and energy, named without saying whose API.
 *
 * These are the types `ControllerHost` hands to every verb in `bpm.nodes`, and the reason they exist is
 * that the loader's own interfaces cannot be. NeoForge spells this `IItemHandler`/`IFluidHandler`/
 * `IEnergyStorage`; Fabric spells it `Storage<ItemVariant>`, `Storage<FluidVariant>` and Team Reborn's
 * `EnergyStorage`, with transactions instead of a simulate flag. Nothing about the mod's own verbs
 * depends on that difference, so it stops here: one adapter per loader, and 3,000-odd lines of node
 * definitions that never learn which game they are running in.
 *
 * The slot model is kept deliberately. Slots are already part of the mod's public surface — `items.slot`
 * and `items.moveSlot(from, slot, to, max)` are things a player writes in a graph — so a slotless port
 * would not be a simpler abstraction, it would be a different feature.
 */

/**
 * Fluid amounts, in the finest unit any loader speaks.
 *
 * NeoForge counts millibuckets; Fabric counts droplets, at 81,000 to the bucket. **81 droplets is
 * exactly one millibucket**, so the conversion loses nothing in either direction — but the division
 * only goes one way cleanly, and that is the whole reason the internal unit is droplets rather than mB.
 *
 * A third of a bucket is 27,000 droplets, which is 333.33 mB. If the port spoke millibuckets, reading a
 * Fabric tank holding a third of a bucket would truncate *inside the mod*, and a graph moving fluid in a
 * loop would shed a third of a millibucket per pass until the tank quietly disagreed with itself.
 * Counting droplets internally and rounding once, at the scripting boundary where the documentation
 * already promises millibuckets, bounds that error at under 1 mB per call and never compounds.
 */
object Droplets {
    const val PER_MB: Long = 81L
    const val PER_BUCKET: Long = 81_000L

    /** A bucket, in the unit the scripting language speaks. Both loaders agree on this one. */
    const val MB_PER_BUCKET: Int = 1_000

    fun ofMb(mb: Int): Long = mb.toLong() * PER_MB

    /** Floors, deliberately: reporting fluid you do not have is the worse of the two errors. */
    fun toMb(droplets: Long): Int = (droplets / PER_MB).toInt()
}

/**
 * A quantity of one fluid. The loader-free stand-in for NeoForge's `FluidStack`.
 *
 * Carries a [ComponentPatch] because a fluid can be more than its id — NeoForge tanks compare
 * components when deciding whether two stacks may share a tank, and dropping that would silently let
 * unlike fluids merge.
 */
data class FluidVolume(
    val fluid: Fluid,
    val droplets: Long,
    val components: ComponentPatch = emptyPatch(),
) {
    val isEmpty: Boolean get() = droplets <= 0L || fluid == Fluids.EMPTY

    /** What the scripting language sees. Floors — see [Droplets]. */
    val mb: Int get() = Droplets.toMb(droplets)

    fun withDroplets(droplets: Long): FluidVolume = copy(droplets = droplets)

    /** Same fluid and same components: the test for "may these share a tank". Amount is not considered. */
    fun sameKindAs(other: FluidVolume): Boolean = fluid == other.fluid && components == other.components

    companion object {
        val EMPTY = FluidVolume(Fluids.EMPTY, 0L)

        fun ofMb(fluid: Fluid, mb: Int, components: ComponentPatch = emptyPatch()) =
            FluidVolume(fluid, Droplets.ofMb(mb), components)

        fun bucket(fluid: Fluid) = FluidVolume(fluid, Droplets.PER_BUCKET)
    }
}

/** A slotted item store. */
interface ItemPort {
    val slots: Int

    fun stackIn(slot: Int): ItemStack

    /** Returns what would NOT fit — the remainder, as `IItemHandler.insertItem` does. */
    fun insert(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack

    fun extract(slot: Int, amount: Int, simulate: Boolean): ItemStack

    fun slotLimit(slot: Int): Int = 64

    fun isValid(slot: Int, stack: ItemStack): Boolean = true

    /**
     * Overwrite a slot outright, answering whether the port allowed it.
     *
     * A store that cannot be written wholesale says no and the caller decides what to do with what it
     * was holding — which is the point. The NeoForge path used to cast to `IItemHandlerModifiable` and
     * do nothing when the cast failed, so a stack handed to a read-only handler vanished.
     */
    fun setStackIn(slot: Int, stack: ItemStack): Boolean = false
}

/**
 * Insert across every slot, stacking onto partial stacks first.
 *
 * Mod-owned so that it behaves identically on every loader: this is a port of NeoForge's
 * `ItemHandlerHelper.insertItemStacked`, which Fabric has no equivalent of. Returns the remainder.
 */
fun ItemPort.insertStacked(stack: ItemStack, simulate: Boolean): ItemStack {
    if (stack.isEmpty || slots <= 0) return stack
    var left = stack

    if (left.isStackable) {
        for (slot in 0 until slots) {
            val existing = stackIn(slot)
            if (existing.isEmpty || !bpm.platform.sameItemAndData(existing, left)) continue
            left = insert(slot, left, simulate)
            if (left.isEmpty) return ItemStack.EMPTY
        }
    }

    for (slot in 0 until slots) {
        if (!stackIn(slot).isEmpty) continue
        left = insert(slot, left, simulate)
        if (left.isEmpty) return ItemStack.EMPTY
    }
    return left
}

/** A store of one or more fluid tanks. Amounts are droplets — see [Droplets]. */
interface FluidPort {
    val tanks: Int

    fun inTank(tank: Int): FluidVolume

    fun tankCapacity(tank: Int): Long

    fun isValid(tank: Int, volume: FluidVolume): Boolean = true

    /** Returns the droplets actually accepted. */
    fun fill(volume: FluidVolume, simulate: Boolean): Long

    /** Drain a specific fluid, up to [volume]'s amount. */
    fun drain(volume: FluidVolume, simulate: Boolean): FluidVolume

    /** Drain whatever is held, up to [maxDroplets]. */
    fun drain(maxDroplets: Long, simulate: Boolean): FluidVolume
}

/**
 * An energy cell.
 *
 * Widened to `Long` from NeoForge's `int` because Fabric's energy API counts in longs and clamping at
 * the boundary would make a large buffer read wrong rather than merely differently.
 */
interface EnergyPort {
    val stored: Long
    val capacity: Long

    fun canReceive(): Boolean
    fun canExtract(): Boolean

    /** Returns the amount actually received. */
    fun receive(amount: Long, simulate: Boolean): Long

    /** Returns the amount actually extracted. */
    fun extract(amount: Long, simulate: Boolean): Long
}

/**
 * An [ItemPort] over a vanilla [Container].
 *
 * This is the reason a player's inventory needs no seam at all. Vanilla `Inventory` reports 41 slots in
 * exactly the order NeoForge's `PlayerInvWrapper` exposes them — 0–35 the pack, 36–39 armour, 40 the
 * offhand — so a presence link and a tether resolve identically on both loaders through this one class.
 * NeoForge's `Capabilities.ItemHandler.ENTITY`, which Fabric's transfer API has no answer for, stops
 * being a problem rather than needing one solved.
 */
class ContainerPort(private val container: Container) : ItemPort {
    override val slots: Int get() = container.containerSize

    override fun stackIn(slot: Int): ItemStack =
        if (slot in 0 until slots) container.getItem(slot) else ItemStack.EMPTY

    override fun slotLimit(slot: Int): Int = container.maxStackSize

    override fun isValid(slot: Int, stack: ItemStack): Boolean =
        slot in 0 until slots && container.canPlaceItem(slot, stack)

    override fun setStackIn(slot: Int, stack: ItemStack): Boolean {
        if (slot !in 0 until slots) return false
        container.setItem(slot, stack)
        container.setChanged()
        return true
    }

    override fun insert(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
        if (stack.isEmpty || slot !in 0 until slots || !container.canPlaceItem(slot, stack)) return stack
        val existing = container.getItem(slot)
        val limit = minOf(container.maxStackSize, stack.maxStackSize)
        if (!existing.isEmpty) {
            if (!bpm.platform.sameItemAndData(existing, stack)) return stack
            val room = limit - existing.count
            if (room <= 0) return stack
            val moved = minOf(room, stack.count)
            if (!simulate) {
                container.setItem(slot, existing.copyWithCount(existing.count + moved))
                container.setChanged()
            }
            return if (moved >= stack.count) ItemStack.EMPTY else stack.copyWithCount(stack.count - moved)
        }
        val moved = minOf(limit, stack.count)
        if (!simulate) {
            container.setItem(slot, stack.copyWithCount(moved))
            container.setChanged()
        }
        return if (moved >= stack.count) ItemStack.EMPTY else stack.copyWithCount(stack.count - moved)
    }

    override fun extract(slot: Int, amount: Int, simulate: Boolean): ItemStack {
        if (amount <= 0 || slot !in 0 until slots) return ItemStack.EMPTY
        val existing = container.getItem(slot)
        if (existing.isEmpty) return ItemStack.EMPTY
        val taken = minOf(amount, existing.count)
        if (simulate) return existing.copyWithCount(taken)
        val out = existing.copyWithCount(taken)
        container.setItem(slot, if (taken >= existing.count) ItemStack.EMPTY else existing.copyWithCount(existing.count - taken))
        container.setChanged()
        return out
    }
}

/**
 * An [ItemPort] that passes everything to another one.
 *
 * On its own it does nothing; it exists so that the things which restrict access — a presence link
 * granting only some slots — can override one method instead of reimplementing a store.
 */
open class ForwardingPort(protected val delegate: ItemPort) : ItemPort {
    override val slots: Int get() = delegate.slots
    override fun stackIn(slot: Int): ItemStack = delegate.stackIn(slot)
    override fun insert(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack = delegate.insert(slot, stack, simulate)
    override fun extract(slot: Int, amount: Int, simulate: Boolean): ItemStack = delegate.extract(slot, amount, simulate)
    override fun slotLimit(slot: Int): Int = delegate.slotLimit(slot)
    override fun isValid(slot: Int, stack: ItemStack): Boolean = delegate.isValid(slot, stack)
    override fun setStackIn(slot: Int, stack: ItemStack): Boolean = delegate.setStackIn(slot, stack)
}
