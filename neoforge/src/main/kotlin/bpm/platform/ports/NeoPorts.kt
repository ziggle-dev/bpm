package bpm.platform.ports

import net.minecraft.world.item.ItemStack

//? if >=1.21.9 {
/*/*
 * NeoForge's transfer API, rebuilt around resources and transactions.
 *
 * 1.21.11 replaced `IItemHandler`, `IFluidHandler` and `IEnergyStorage` with one shape:
 * `ResourceHandler<T>` over an immutable `Resource` (an item or fluid WITHOUT an amount), with the
 * amount passed separately and every mutation scoped to a `TransactionContext`. It is, essentially,
 * Fabric's design -- which is why this file now reads like the Fabric adapter beside it.
 *
 * Two translations happen here, and both are the same one the Fabric adapter already makes:
 *
 * A SIMULATE FLAG IS A TRANSACTION NEVER COMMITTED. `ItemPort.insert(..., simulate = true)` becomes a
 * root transaction that is opened, asked, and closed without committing. That is exactly what simulate
 * meant, expressed in the newer vocabulary.
 *
 * A RESOURCE PLUS AN AMOUNT IS A STACK. `ItemResource` carries the item and its components but no
 * count, so every call site pairs it with an int and `toStack(n)` puts them back together.
 *
 * Going the other way -- exposing this mod's own inventories to other mods' pipes -- needs the handler
 * to PARTICIPATE in a caller's transaction, because the caller may roll it back. `SnapshotJournal` is
 * NeoForge's helper for that: snapshot before mutating, revert if the transaction aborts. The snapshot
 * is the whole port's contents, which is affordable because these are machine inventories of a dozen
 * slots, not chests.
 */

private typealias ItemHandler = net.neoforged.neoforge.transfer.ResourceHandler<net.neoforged.neoforge.transfer.item.ItemResource>
private typealias FluidHandler = net.neoforged.neoforge.transfer.ResourceHandler<net.neoforged.neoforge.transfer.fluid.FluidResource>

/** Run [block] inside a root transaction, committing it only when this is not a simulation. */
private inline fun <R> transacted(simulate: Boolean, block: (net.neoforged.neoforge.transfer.transaction.TransactionContext) -> R): R {
    val tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()
    try {
        val result = block(tx)
        if (!simulate) tx.commit()
        return result
    } finally {
        tx.close()
    }
}

// ---------------------------------------------------------------- items

class HandlerPort(private val handler: ItemHandler) : ItemPort {
    override val slots: Int get() = handler.size()

    override fun stackIn(slot: Int): ItemStack = handler.getResource(slot).toStack(handler.getAmountAsInt(slot))

    /** Answers the REMAINDER, as the port's contract requires, from the amount the handler accepted. */
    override fun insert(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
        if (stack.isEmpty) return ItemStack.EMPTY
        val accepted = transacted(simulate) { tx ->
            handler.insert(slot, net.neoforged.neoforge.transfer.item.ItemResource.of(stack), stack.count, tx)
        }
        return if (accepted >= stack.count) ItemStack.EMPTY else stack.copyWithCount(stack.count - accepted)
    }

    override fun extract(slot: Int, amount: Int, simulate: Boolean): ItemStack {
        val resource = handler.getResource(slot)
        if (resource.isEmpty) return ItemStack.EMPTY
        val got = transacted(simulate) { tx -> handler.extract(slot, resource, amount, tx) }
        return if (got <= 0) ItemStack.EMPTY else resource.toStack(got)
    }

    override fun slotLimit(slot: Int): Int = handler.getCapacityAsInt(slot, handler.getResource(slot))

    /** A resource handler has no "put this exact stack here"; a port that cannot be set says so. */
    override fun setStackIn(slot: Int, stack: ItemStack): Boolean = false
}

class PortHandler(private val port: ItemPort) :
    net.neoforged.neoforge.transfer.transaction.SnapshotJournal<List<ItemStack>>(), ItemHandler {

    override fun createSnapshot(): List<ItemStack> = (0 until port.slots).map { port.stackIn(it).copy() }

    override fun revertToSnapshot(snapshot: List<ItemStack>) {
        snapshot.forEachIndexed { slot, stack -> port.setStackIn(slot, stack) }
    }

    override fun size(): Int = port.slots

    override fun getResource(index: Int): net.neoforged.neoforge.transfer.item.ItemResource =
        net.neoforged.neoforge.transfer.item.ItemResource.of(port.stackIn(index))

    override fun getAmountAsLong(index: Int): Long = port.stackIn(index).count.toLong()

    override fun getCapacityAsLong(index: Int, resource: net.neoforged.neoforge.transfer.item.ItemResource): Long =
        port.slotLimit(index).toLong()

    /** Whether this index would accept that resource at all -- the port's own filter, asked directly. */
    override fun isValid(index: Int, resource: net.neoforged.neoforge.transfer.item.ItemResource): Boolean =
        port.isValid(index, resource.toStack(1))

    override fun insert(
        index: Int,
        resource: net.neoforged.neoforge.transfer.item.ItemResource,
        amount: Int,
        transaction: net.neoforged.neoforge.transfer.transaction.TransactionContext,
    ): Int {
        if (amount <= 0 || resource.isEmpty) return 0
        updateSnapshots(transaction)
        val remainder = port.insert(index, resource.toStack(amount), false)
        return amount - remainder.count
    }

    override fun extract(
        index: Int,
        resource: net.neoforged.neoforge.transfer.item.ItemResource,
        amount: Int,
        transaction: net.neoforged.neoforge.transfer.transaction.TransactionContext,
    ): Int {
        if (amount <= 0 || resource.isEmpty) return 0
        // Only the asked-for resource: a handler must never hand back something else.
        if (net.neoforged.neoforge.transfer.item.ItemResource.of(port.stackIn(index)) != resource) return 0
        updateSnapshots(transaction)
        return port.extract(index, amount, false).count
    }
}

// ---------------------------------------------------------------- fluids

/** Amounts stay millibuckets on this loader, so the droplet conversion is unchanged and still exact. */
private fun fluidResource(volume: FluidVolume): net.neoforged.neoforge.transfer.fluid.FluidResource =
    net.neoforged.neoforge.transfer.fluid.FluidResource.of(volume.fluid, volume.components)

private fun net.neoforged.neoforge.transfer.fluid.FluidResource.toVolume(mb: Int): FluidVolume =
    if (isEmpty || mb <= 0) FluidVolume.EMPTY else FluidVolume(fluid, Droplets.ofMb(mb), toStack(1).componentsPatch)

class HandlerFluidPort(private val handler: FluidHandler) : FluidPort {
    override val tanks: Int get() = handler.size()

    override fun inTank(tank: Int): FluidVolume = handler.getResource(tank).toVolume(handler.getAmountAsInt(tank))

    override fun tankCapacity(tank: Int): Long =
        Droplets.ofMb(handler.getCapacityAsInt(tank, handler.getResource(tank)))

    override fun fill(volume: FluidVolume, simulate: Boolean): Long {
        if (volume.isEmpty) return 0
        val filled = transacted(simulate) { tx -> handler.insert(fluidResource(volume), Droplets.toMb(volume.droplets), tx) }
        return Droplets.ofMb(filled)
    }

    override fun drain(volume: FluidVolume, simulate: Boolean): FluidVolume {
        if (volume.isEmpty) return FluidVolume.EMPTY
        val resource = fluidResource(volume)
        val got = transacted(simulate) { tx -> handler.extract(resource, Droplets.toMb(volume.droplets), tx) }
        return resource.toVolume(got)
    }

    override fun drain(maxDroplets: Long, simulate: Boolean): FluidVolume {
        // Without a fluid named, drain whatever the first non-empty tank holds -- the same rule the
        // amount-only overload has always followed.
        for (tank in 0 until handler.size()) {
            val resource = handler.getResource(tank)
            if (resource.isEmpty) continue
            val got = transacted(simulate) { tx -> handler.extract(resource, Droplets.toMb(maxDroplets), tx) }
            if (got > 0) return resource.toVolume(got)
        }
        return FluidVolume.EMPTY
    }
}

class PortFluidHandler(private val port: FluidPort) :
    net.neoforged.neoforge.transfer.transaction.SnapshotJournal<List<FluidVolume>>(), FluidHandler {

    override fun createSnapshot(): List<FluidVolume> = (0 until port.tanks).map { port.inTank(it) }

    override fun revertToSnapshot(snapshot: List<FluidVolume>) {
        // A fluid port has no "set tank", so the revert is expressed as the inverse transfer.
        snapshot.forEachIndexed { tank, was ->
            val now = port.inTank(tank)
            when {
                now.droplets > was.droplets -> port.drain(FluidVolume(now.fluid, now.droplets - was.droplets, now.components), false)
                now.droplets < was.droplets -> port.fill(FluidVolume(was.fluid, was.droplets - now.droplets, was.components), false)
            }
        }
    }

    override fun size(): Int = port.tanks

    override fun getResource(index: Int): net.neoforged.neoforge.transfer.fluid.FluidResource = fluidResource(port.inTank(index))

    override fun getAmountAsLong(index: Int): Long = Droplets.toMb(port.inTank(index).droplets).toLong()

    override fun getCapacityAsLong(index: Int, resource: net.neoforged.neoforge.transfer.fluid.FluidResource): Long =
        Droplets.toMb(port.tankCapacity(index)).toLong()

    override fun isValid(index: Int, resource: net.neoforged.neoforge.transfer.fluid.FluidResource): Boolean =
        port.isValid(index, resource.toVolume(1))

    override fun insert(
        index: Int,
        resource: net.neoforged.neoforge.transfer.fluid.FluidResource,
        amount: Int,
        transaction: net.neoforged.neoforge.transfer.transaction.TransactionContext,
    ): Int {
        if (amount <= 0 || resource.isEmpty) return 0
        updateSnapshots(transaction)
        return Droplets.toMb(port.fill(resource.toVolume(amount), false))
    }

    override fun extract(
        index: Int,
        resource: net.neoforged.neoforge.transfer.fluid.FluidResource,
        amount: Int,
        transaction: net.neoforged.neoforge.transfer.transaction.TransactionContext,
    ): Int {
        if (amount <= 0 || resource.isEmpty) return 0
        updateSnapshots(transaction)
        return Droplets.toMb(port.drain(resource.toVolume(amount), false).droplets)
    }
}

// ---------------------------------------------------------------- energy

class StoragePort(private val handler: net.neoforged.neoforge.transfer.energy.EnergyHandler) : EnergyPort {
    override val stored: Long get() = handler.getAmountAsInt().toLong()
    override val capacity: Long get() = handler.getCapacityAsInt().toLong()

    /**
     * The newer API dropped the two "can you at all" questions.
     *
     * A handler that refuses everything answers zero to a simulated move, which is the same information
     * arriving one call later, so these ask rather than assert.
     */
    override fun canReceive(): Boolean = receive(1, simulate = true) > 0

    override fun canExtract(): Boolean = extract(1, simulate = true) > 0

    override fun receive(amount: Long, simulate: Boolean): Long =
        transacted(simulate) { tx -> handler.insert(clampToInt(amount), tx) }.toLong()

    override fun extract(amount: Long, simulate: Boolean): Long =
        transacted(simulate) { tx -> handler.extract(clampToInt(amount), tx) }.toLong()
}

class PortStorage(private val port: EnergyPort) :
    net.neoforged.neoforge.transfer.transaction.SnapshotJournal<Long>(), net.neoforged.neoforge.transfer.energy.EnergyHandler {

    override fun createSnapshot(): Long = port.stored

    override fun revertToSnapshot(snapshot: Long) {
        val now = port.stored
        when {
            now > snapshot -> port.extract(now - snapshot, false)
            now < snapshot -> port.receive(snapshot - now, false)
        }
    }

    override fun getAmountAsLong(): Long = port.stored

    override fun getCapacityAsLong(): Long = port.capacity

    override fun insert(amount: Int, transaction: net.neoforged.neoforge.transfer.transaction.TransactionContext): Int {
        if (amount <= 0 || !port.canReceive()) return 0
        updateSnapshots(transaction)
        return clampToInt(port.receive(amount.toLong(), false))
    }

    override fun extract(amount: Int, transaction: net.neoforged.neoforge.transfer.transaction.TransactionContext): Int {
        if (amount <= 0 || !port.canExtract()) return 0
        updateSnapshots(transaction)
        return clampToInt(port.extract(amount.toLong(), false))
    }
}

/** NeoForge counts energy in ints; a port may hold more. Saturate rather than wrap. */
private fun clampToInt(value: Long): Int = value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
*///?} else {
/**
 * NeoForge's capabilities, in both directions.
 *
 * Wrapping *in* is what lets the nodes speak ports; wrapping *out* is what keeps the controller's own
 * stores visible to other mods' pipes. Both are needed, and they are each about twenty lines, which is
 * the whole cost of the abstraction on this loader.
 *
 * Fluid amounts convert on the boundary. NeoForge is millibucket-native, so nothing here can ever
 * produce a fraction of a millibucket and the conversion is exact in both directions — the droplet unit
 * only starts to earn its keep on Fabric.
 */

// ---------------------------------------------------------------- items

class HandlerPort(private val handler: net.neoforged.neoforge.items.IItemHandler) : ItemPort {
    override val slots: Int get() = handler.slots
    override fun stackIn(slot: Int): ItemStack = handler.getStackInSlot(slot)
    override fun insert(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack = handler.insertItem(slot, stack, simulate)
    override fun extract(slot: Int, amount: Int, simulate: Boolean): ItemStack = handler.extractItem(slot, amount, simulate)
    override fun slotLimit(slot: Int): Int = handler.getSlotLimit(slot)
    override fun isValid(slot: Int, stack: ItemStack): Boolean = handler.isItemValid(slot, stack)

    override fun setStackIn(slot: Int, stack: ItemStack): Boolean {
        val modifiable = handler as? net.neoforged.neoforge.items.IItemHandlerModifiable ?: return false
        modifiable.setStackInSlot(slot, stack)
        return true
    }
}

class PortHandler(private val port: ItemPort) : net.neoforged.neoforge.items.IItemHandlerModifiable {
    override fun getSlots(): Int = port.slots
    override fun getStackInSlot(slot: Int): ItemStack = port.stackIn(slot)
    override fun insertItem(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack = port.insert(slot, stack, simulate)
    override fun extractItem(slot: Int, amount: Int, simulate: Boolean): ItemStack = port.extract(slot, amount, simulate)
    override fun getSlotLimit(slot: Int): Int = port.slotLimit(slot)
    override fun isItemValid(slot: Int, stack: ItemStack): Boolean = port.isValid(slot, stack)

    override fun setStackInSlot(slot: Int, stack: ItemStack) {
        port.setStackIn(slot, stack)
    }
}

// ---------------------------------------------------------------- fluids

fun net.neoforged.neoforge.fluids.FluidStack.toVolume(): FluidVolume =
    if (isEmpty) FluidVolume.EMPTY else FluidVolume(fluid, Droplets.ofMb(amount), componentsPatch)

fun FluidVolume.toStack(): net.neoforged.neoforge.fluids.FluidStack =
    if (isEmpty) net.neoforged.neoforge.fluids.FluidStack.EMPTY else net.neoforged.neoforge.fluids.FluidStack(fluid.builtInRegistryHolder(), mb, components)

class HandlerFluidPort(private val handler: net.neoforged.neoforge.fluids.capability.IFluidHandler) : FluidPort {
    override val tanks: Int get() = handler.tanks
    override fun inTank(tank: Int): FluidVolume = handler.getFluidInTank(tank).toVolume()
    override fun tankCapacity(tank: Int): Long = Droplets.ofMb(handler.getTankCapacity(tank))
    override fun isValid(tank: Int, volume: FluidVolume): Boolean = handler.isFluidValid(tank, volume.toStack())

    override fun fill(volume: FluidVolume, simulate: Boolean): Long =
        Droplets.ofMb(handler.fill(volume.toStack(), action(simulate)))

    override fun drain(volume: FluidVolume, simulate: Boolean): FluidVolume =
        handler.drain(volume.toStack(), action(simulate)).toVolume()

    override fun drain(maxDroplets: Long, simulate: Boolean): FluidVolume =
        handler.drain(Droplets.toMb(maxDroplets), action(simulate)).toVolume()

    private fun action(simulate: Boolean) =
        if (simulate) net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE else net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE
}

class PortFluidHandler(private val port: FluidPort) : net.neoforged.neoforge.fluids.capability.IFluidHandler {
    override fun getTanks(): Int = port.tanks
    override fun getFluidInTank(tank: Int): net.neoforged.neoforge.fluids.FluidStack = port.inTank(tank).toStack()
    override fun getTankCapacity(tank: Int): Int = Droplets.toMb(port.tankCapacity(tank))
    override fun isFluidValid(tank: Int, stack: net.neoforged.neoforge.fluids.FluidStack): Boolean = port.isValid(tank, stack.toVolume())

    override fun fill(resource: net.neoforged.neoforge.fluids.FluidStack, action: net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction): Int =
        Droplets.toMb(port.fill(resource.toVolume(), action.simulate()))

    override fun drain(resource: net.neoforged.neoforge.fluids.FluidStack, action: net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction): net.neoforged.neoforge.fluids.FluidStack =
        port.drain(resource.toVolume(), action.simulate()).toStack()

    override fun drain(maxDrain: Int, action: net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction): net.neoforged.neoforge.fluids.FluidStack =
        port.drain(Droplets.ofMb(maxDrain), action.simulate()).toStack()
}

// ---------------------------------------------------------------- energy

class StoragePort(private val storage: net.neoforged.neoforge.energy.IEnergyStorage) : EnergyPort {
    override val stored: Long get() = storage.energyStored.toLong()
    override val capacity: Long get() = storage.maxEnergyStored.toLong()
    override fun canReceive(): Boolean = storage.canReceive()
    override fun canExtract(): Boolean = storage.canExtract()

    override fun receive(amount: Long, simulate: Boolean): Long =
        storage.receiveEnergy(clampToInt(amount), simulate).toLong()

    override fun extract(amount: Long, simulate: Boolean): Long =
        storage.extractEnergy(clampToInt(amount), simulate).toLong()
}

class PortStorage(private val port: EnergyPort) : net.neoforged.neoforge.energy.IEnergyStorage {
    override fun getEnergyStored(): Int = clampToInt(port.stored)
    override fun getMaxEnergyStored(): Int = clampToInt(port.capacity)
    override fun canReceive(): Boolean = port.canReceive()
    override fun canExtract(): Boolean = port.canExtract()
    override fun receiveEnergy(toReceive: Int, simulate: Boolean): Int = clampToInt(port.receive(toReceive.toLong(), simulate))
    override fun extractEnergy(toExtract: Int, simulate: Boolean): Int = clampToInt(port.extract(toExtract.toLong(), simulate))
}

/** NeoForge counts energy in ints; a port may hold more. Saturate rather than wrap. */
private fun clampToInt(value: Long): Int = value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
//?}
