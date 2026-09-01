package bpm.platform.ports

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext
import net.minecraft.core.Direction
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType

/**
 * Telling Fabric what our own blocks offer.
 *
 * The lookup direction ([FabricPorts]) turns a `Storage` into a port; this is the other way round, and it
 * is the harder one, because a `Storage` has to take part in transactions. Rather than hand-roll that,
 * items go through a `Container` view and `InventoryStorage.of`, which is Fabric's own adapter and
 * already handles snapshots and rollback correctly. Our `SlotStore` supports `setStackIn`, which is
 * exactly what a `Container` needs, so the wrapper is thin and honest.
 *
 * Registration is deferred for the same reason as on NeoForge — the block entity types are named through
 * the deferred registers, so running the block during mod construction asks for holders nothing has
 * bound yet.
 */
object FabricPortProviders : PortProviders {

    private val pending = ArrayList<(PortSink) -> Unit>()

    override fun providers(block: (PortSink) -> Unit) {
        pending += block
    }

    /** Called once, after registration, from this loader's entry point. */
    fun register() {
        val sink = object : PortSink {
            override fun <T : BlockEntity> items(type: BlockEntityType<T>, port: (T, Direction?) -> ItemPort?) {
                ItemStorage.SIDED.registerForBlockEntity({ be, side ->
                    port(be, side)?.let { InventoryStorage.of(PortContainer(it), side) }
                }, type)
            }

            override fun <T : BlockEntity> fluids(type: BlockEntityType<T>, port: (T, Direction?) -> FluidPort?) {
                FluidStorage.SIDED.registerForBlockEntity({ be, side ->
                    port(be, side)?.let(::PortFluidStorage)
                }, type)
            }

            override fun <T : BlockEntity> energy(type: BlockEntityType<T>, port: (T, Direction?) -> EnergyPort?) {
                // FABRIC TODO: needs Team Reborn's Energy API; see the note in FabricPorts.
            }
        }
        for (block in pending) block(sink)
        pending.clear()
    }
}

/**
 * An [ItemPort] seen as a vanilla `Container`, so that Fabric's own `InventoryStorage` can wrap it.
 *
 * `stillValid` is true for everyone: these are not screens, and the reach checks that question exists for
 * are done long before anything gets here.
 */
private class PortContainer(private val port: ItemPort) : Container {
    override fun getContainerSize(): Int = port.slots
    override fun isEmpty(): Boolean = (0 until port.slots).all { port.stackIn(it).isEmpty }
    override fun getItem(slot: Int): ItemStack = port.stackIn(slot)
    override fun setItem(slot: Int, stack: ItemStack) {
        port.setStackIn(slot, stack)
    }

    override fun removeItem(slot: Int, amount: Int): ItemStack = port.extract(slot, amount, false)
    override fun removeItemNoUpdate(slot: Int): ItemStack = port.extract(slot, Int.MAX_VALUE, false)
    override fun getMaxStackSize(): Int = (0 until port.slots).minOfOrNull { port.slotLimit(it) } ?: 64
    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = port.isValid(slot, stack)
    override fun setChanged() {}
    override fun stillValid(player: Player): Boolean = true
    override fun clearContent() {
        for (i in 0 until port.slots) port.setStackIn(i, ItemStack.EMPTY)
    }
}

/**
 * A [FluidPort] as a Fabric fluid storage.
 *
 * Written out rather than adapted, because Fabric has no `Container` equivalent for fluids. It presents
 * the port as one storage over all its tanks: `fill` and `drain` already do the tank-picking, and
 * exposing each tank separately would only duplicate that logic on this side of the seam.
 *
 * `SingleVariantStorage` is not used for the same reason — the port may hold several different fluids at
 * once, which that class exists to rule out.
 */
private class PortFluidStorage(private val port: FluidPort) :
    net.fabricmc.fabric.api.transfer.v1.storage.Storage<FluidVariant> {

    override fun supportsInsertion(): Boolean = true
    override fun supportsExtraction(): Boolean = true

    override fun insert(resource: FluidVariant, maxAmount: Long, transaction: TransactionContext): Long {
        val volume = FluidVolume(resource.fluid, maxAmount, resource.components)
        // Simulate first, then let the transaction decide: a participant that commits does the real work
        // when the outer transaction closes, and Fabric gives us no cheaper way to be honest about that.
        val room = port.fill(volume, true)
        if (room <= 0) return 0
        transaction.addCloseCallback { _, result ->
            if (result.wasCommitted()) port.fill(volume.withDroplets(room), false)
        }
        return room
    }

    override fun extract(resource: FluidVariant, maxAmount: Long, transaction: TransactionContext): Long {
        val volume = FluidVolume(resource.fluid, maxAmount, resource.components)
        val available = port.drain(volume, true)
        if (available.isEmpty) return 0
        transaction.addCloseCallback { _, result ->
            if (result.wasCommitted()) port.drain(available, false)
        }
        return available.droplets
    }

    override fun iterator(): MutableIterator<net.fabricmc.fabric.api.transfer.v1.storage.StorageView<FluidVariant>> {
        val views = (0 until port.tanks).map { TankView(port, it) }
        return views.toMutableList().iterator() as MutableIterator<net.fabricmc.fabric.api.transfer.v1.storage.StorageView<FluidVariant>>
    }
}

private class TankView(private val port: FluidPort, private val tank: Int) :
    net.fabricmc.fabric.api.transfer.v1.storage.StorageView<FluidVariant> {

    override fun extract(resource: FluidVariant, maxAmount: Long, transaction: TransactionContext): Long {
        val volume = FluidVolume(resource.fluid, maxAmount, resource.components)
        val available = port.drain(volume, true)
        if (available.isEmpty) return 0
        transaction.addCloseCallback { _, result ->
            if (result.wasCommitted()) port.drain(available, false)
        }
        return available.droplets
    }

    override fun isResourceBlank(): Boolean = port.inTank(tank).isEmpty
    override fun getResource(): FluidVariant = port.inTank(tank).let { FluidVariant.of(it.fluid, it.components) }
    override fun getAmount(): Long = port.inTank(tank).droplets
    override fun getCapacity(): Long = port.tankCapacity(tank)
}
