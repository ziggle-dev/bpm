package bpm.platform.ports

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant
import net.fabricmc.fabric.api.transfer.v1.storage.Storage
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack

/**
 * The component PATCH on a transfer variant, under whichever name this band gives it.
 *
 * 26.1 made `TransferVariant` a `DataComponentHolder`, which brought a `getComponents()` of its own
 * returning a `DataComponentMap`. The patch it used to return moved aside to `getComponentsPatch()`.
 * Kotlin spells the old one `.components` and the new one `.componentsPatch`, and the compiler catches
 * the difference as a type mismatch rather than a missing member -- so this names the patch explicitly
 * and the call sites stop caring. `FluidVolume` wants the patch, as it always did.
 */
//? if >=26.1 {
/*internal val net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.patch: net.minecraft.core.component.DataComponentPatch
    get() = componentsPatch
*///?} else {
internal val net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.patch: net.minecraft.core.component.DataComponentPatch
    get() = components
//?}

/**
 * Fabric's transfer API, behind the mod's ports.
 *
 * The two models differ in shape, not just in spelling. NeoForge hands you a handler with numbered slots
 * and a simulate flag on every call; Fabric hands you a `Storage` of variants and a transaction you
 * either commit or drop. `simulate = true` is therefore a transaction that is opened and never
 * committed, which is exactly what it means.
 *
 * **Fluid amounts need no conversion at all.** Fabric counts droplets, 81,000 to a bucket, and so does
 * this mod. That was the reason for choosing droplets over millibuckets in the first place — millibuckets
 * are not closed under Fabric's arithmetic, so a third of a bucket would have been lossy in a way that
 * compounds over repeated moves. Here the payoff is a cast and nothing else.
 *
 * Items are the awkward direction: our ports are slot-numbered and a `Storage` is not. A storage that
 * implements `SlottedStorage` maps straight across; anything else is presented through the views its
 * iterator yields, which gives the same reads and a whole-storage insert instead of a targeted one.
 */
object FabricPorts : PlatformPorts {

    override fun cache(level: ServerLevel, pos: BlockPos, side: Direction?): PortCache =
        FabricPortCache(level, pos, side)

    /*
     * playerInventory is deliberately NOT overridden. The seam's default is a ContainerPort over the
     * vanilla inventory, which is 41 slots in exactly the order a handler would expose them, and Fabric
     * has no entity-storage lookup to prefer over it. This is the case the default was written for.
     */
}

private class FabricPortCache(
    private val level: ServerLevel,
    private val pos: BlockPos,
    private val side: Direction?,
) : PortCache {
    /*
     * Looked up each time rather than held. NeoForge's BlockCapabilityCache watches the position and
     * invalidates itself, so caching there is both safe and worthwhile; Fabric's `find` has no such
     * invalidation, and a stale Storage across a block change would be worse than a lookup per tick.
     */
    override val items: ItemPort?
        get() = ItemStorage.SIDED.find(level, pos, side)?.let(::StorageItemPort)

    override val fluids: FluidPort?
        get() = FluidStorage.SIDED.find(level, pos, side)?.let(::StorageFluidPort)

    /**
     * Energy, through Team Reborn's API rather than Fabric's — Fabric has none of its own, and this is
     * what every mod on this loader that moves power actually implements.
     */
    override val energy: EnergyPort?
        get() = team.reborn.energy.api.EnergyStorage.SIDED.find(level, pos, side)?.let(::StorageEnergyPort)
}

/**
 * An `EnergyStorage` seen as an [EnergyPort].
 *
 * The two line up almost exactly — amount, capacity, and an insert and extract that return what moved.
 * The only translation is the one the whole transfer API needs: `simulate` is a transaction that is
 * opened and never committed.
 *
 * This is where the seam's choice of `Long` pays for itself. NeoForge's energy is an `int`; this API
 * counts in longs, and a port that had narrowed at the boundary would have reported a large buffer
 * wrongly rather than merely differently.
 */
private class StorageEnergyPort(private val storage: team.reborn.energy.api.EnergyStorage) : EnergyPort {

    override val stored: Long get() = storage.amount
    override val capacity: Long get() = storage.capacity

    override fun canReceive(): Boolean = storage.supportsInsertion()
    override fun canExtract(): Boolean = storage.supportsExtraction()

    override fun receive(amount: Long, simulate: Boolean): Long {
        if (amount <= 0) return 0
        Transaction.openOuter().use { tx ->
            val moved = storage.insert(amount, tx)
            if (!simulate) tx.commit()
            return moved
        }
    }

    override fun extract(amount: Long, simulate: Boolean): Long {
        if (amount <= 0) return 0
        Transaction.openOuter().use { tx ->
            val moved = storage.extract(amount, tx)
            if (!simulate) tx.commit()
            return moved
        }
    }
}

/** A `Storage<ItemVariant>` presented as numbered slots. */
private class StorageItemPort(private val storage: Storage<ItemVariant>) : ItemPort {

    private val views: List<StorageView<ItemVariant>>
        get() = (storage as? SlottedStorage<ItemVariant>)?.slots ?: storage.iterator().asSequence().toList()

    override val slots: Int get() = views.size

    override fun stackIn(slot: Int): ItemStack {
        val view = views.getOrNull(slot) ?: return ItemStack.EMPTY
        if (view.isResourceBlank) return ItemStack.EMPTY
        return view.resource.toStack(view.amount.toInt())
    }

    override fun insert(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
        if (stack.isEmpty) return ItemStack.EMPTY
        // A slotted storage can be told which slot; anything else takes it wherever it fits.
        val target: Storage<ItemVariant> = (views.getOrNull(slot) as? SingleSlotStorage<ItemVariant>) ?: storage
        Transaction.openOuter().use { tx ->
            val moved = target.insert(ItemVariant.of(stack), stack.count.toLong(), tx)
            if (!simulate) tx.commit()
            val left = stack.count - moved.toInt()
            return if (left <= 0) ItemStack.EMPTY else stack.copyWithCount(left)
        }
    }

    override fun extract(slot: Int, amount: Int, simulate: Boolean): ItemStack {
        val view = views.getOrNull(slot) ?: return ItemStack.EMPTY
        if (view.isResourceBlank) return ItemStack.EMPTY
        val resource = view.resource
        Transaction.openOuter().use { tx ->
            val moved = view.extract(resource, amount.toLong(), tx)
            if (!simulate) tx.commit()
            return if (moved <= 0) ItemStack.EMPTY else resource.toStack(moved.toInt())
        }
    }

    override fun slotLimit(slot: Int): Int = views.getOrNull(slot)?.capacity?.toInt() ?: 64
}

/** A `Storage<FluidVariant>` presented as numbered tanks. Amounts pass straight through: both count droplets. */
private class StorageFluidPort(private val storage: Storage<FluidVariant>) : FluidPort {

    private val views: List<StorageView<FluidVariant>>
        get() = (storage as? SlottedStorage<FluidVariant>)?.slots ?: storage.iterator().asSequence().toList()

    override val tanks: Int get() = views.size

    override fun inTank(tank: Int): FluidVolume {
        val view = views.getOrNull(tank) ?: return FluidVolume.EMPTY
        if (view.isResourceBlank) return FluidVolume.EMPTY
        return FluidVolume(view.resource.fluid, view.amount, view.resource.patch)
    }

    override fun tankCapacity(tank: Int): Long = views.getOrNull(tank)?.capacity ?: 0L

    override fun fill(volume: FluidVolume, simulate: Boolean): Long {
        if (volume.isEmpty) return 0L
        Transaction.openOuter().use { tx ->
            val moved = storage.insert(volume.toVariant(), volume.droplets, tx)
            if (!simulate) tx.commit()
            return moved
        }
    }

    override fun drain(volume: FluidVolume, simulate: Boolean): FluidVolume {
        if (volume.isEmpty) return FluidVolume.EMPTY
        Transaction.openOuter().use { tx ->
            val moved = storage.extract(volume.toVariant(), volume.droplets, tx)
            if (!simulate) tx.commit()
            return if (moved <= 0) FluidVolume.EMPTY else volume.withDroplets(moved)
        }
    }

    override fun drain(maxDroplets: Long, simulate: Boolean): FluidVolume {
        // No "any fluid" extract in the transfer API: take whatever the first non-empty view holds.
        val view = views.firstOrNull { !it.isResourceBlank } ?: return FluidVolume.EMPTY
        val resource = view.resource
        Transaction.openOuter().use { tx ->
            val moved = view.extract(resource, maxDroplets, tx)
            if (!simulate) tx.commit()
            return if (moved <= 0) FluidVolume.EMPTY else FluidVolume(resource.fluid, moved, resource.patch)
        }
    }
}

internal fun FluidVolume.toVariant(): FluidVariant = FluidVariant.of(fluid, components)
