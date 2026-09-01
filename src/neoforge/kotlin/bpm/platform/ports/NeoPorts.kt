package bpm.platform.ports

import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.energy.IEnergyStorage
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.items.IItemHandler
import net.neoforged.neoforge.items.IItemHandlerModifiable

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

class HandlerPort(private val handler: IItemHandler) : ItemPort {
    override val slots: Int get() = handler.slots
    override fun stackIn(slot: Int): ItemStack = handler.getStackInSlot(slot)
    override fun insert(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack = handler.insertItem(slot, stack, simulate)
    override fun extract(slot: Int, amount: Int, simulate: Boolean): ItemStack = handler.extractItem(slot, amount, simulate)
    override fun slotLimit(slot: Int): Int = handler.getSlotLimit(slot)
    override fun isValid(slot: Int, stack: ItemStack): Boolean = handler.isItemValid(slot, stack)

    override fun setStackIn(slot: Int, stack: ItemStack): Boolean {
        val modifiable = handler as? IItemHandlerModifiable ?: return false
        modifiable.setStackInSlot(slot, stack)
        return true
    }
}

class PortHandler(private val port: ItemPort) : IItemHandlerModifiable {
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

fun FluidStack.toVolume(): FluidVolume =
    if (isEmpty) FluidVolume.EMPTY else FluidVolume(fluid, Droplets.ofMb(amount), componentsPatch)

fun FluidVolume.toStack(): FluidStack =
    if (isEmpty) FluidStack.EMPTY else FluidStack(fluid.builtInRegistryHolder(), mb, components)

class HandlerFluidPort(private val handler: IFluidHandler) : FluidPort {
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
        if (simulate) IFluidHandler.FluidAction.SIMULATE else IFluidHandler.FluidAction.EXECUTE
}

class PortFluidHandler(private val port: FluidPort) : IFluidHandler {
    override fun getTanks(): Int = port.tanks
    override fun getFluidInTank(tank: Int): FluidStack = port.inTank(tank).toStack()
    override fun getTankCapacity(tank: Int): Int = Droplets.toMb(port.tankCapacity(tank))
    override fun isFluidValid(tank: Int, stack: FluidStack): Boolean = port.isValid(tank, stack.toVolume())

    override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int =
        Droplets.toMb(port.fill(resource.toVolume(), action.simulate()))

    override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack =
        port.drain(resource.toVolume(), action.simulate()).toStack()

    override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack =
        port.drain(Droplets.ofMb(maxDrain), action.simulate()).toStack()
}

// ---------------------------------------------------------------- energy

class StoragePort(private val storage: IEnergyStorage) : EnergyPort {
    override val stored: Long get() = storage.energyStored.toLong()
    override val capacity: Long get() = storage.maxEnergyStored.toLong()
    override fun canReceive(): Boolean = storage.canReceive()
    override fun canExtract(): Boolean = storage.canExtract()

    override fun receive(amount: Long, simulate: Boolean): Long =
        storage.receiveEnergy(clampToInt(amount), simulate).toLong()

    override fun extract(amount: Long, simulate: Boolean): Long =
        storage.extractEnergy(clampToInt(amount), simulate).toLong()
}

class PortStorage(private val port: EnergyPort) : IEnergyStorage {
    override fun getEnergyStored(): Int = clampToInt(port.stored)
    override fun getMaxEnergyStored(): Int = clampToInt(port.capacity)
    override fun canReceive(): Boolean = port.canReceive()
    override fun canExtract(): Boolean = port.canExtract()
    override fun receiveEnergy(toReceive: Int, simulate: Boolean): Int = clampToInt(port.receive(toReceive.toLong(), simulate))
    override fun extractEnergy(toExtract: Int, simulate: Boolean): Int = clampToInt(port.extract(toExtract.toLong(), simulate))
}

/** NeoForge counts energy in ints; a port may hold more. Saturate rather than wrap. */
private fun clampToInt(value: Long): Int = value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
