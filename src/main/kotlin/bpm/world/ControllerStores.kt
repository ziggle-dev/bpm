package bpm.world

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.neoforged.neoforge.energy.EnergyStorage
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.fluids.capability.templates.FluidTank

/**
 * The controller's own stores — what the reserved link `self` names for each kind of thing: nine item
 * slots ([ControllerBlockEntity.inventory]), [TANKS] fluid tanks and one energy cell. Pipes, cables and
 * other mods reach them through the block capabilities; scripts through `self`.
 */
object ControllerStores {
    const val ITEM_SLOTS = 9
    const val TANKS = 4
    const val TANK_MB = 16_000
    const val ENERGY_FE = 100_000
    const val ENERGY_RATE = 10_000

    /** Liquid experience: one experience point is this many millibuckets (the rate the other mods use). */
    const val XP_MB_PER_POINT = 20
}

/**
 * Several tanks behind one handler. Filling prefers a tank already holding that fluid, then an empty one;
 * draining by kind finds the tank holding it, draining by amount takes from the first tank that has
 * anything. Each tank holds one fluid at a time.
 */
class MultiTank(count: Int, private val capacity: Int, private val onChange: () -> Unit) : IFluidHandler {
    private val tanks: List<FluidTank> = List(count) { FluidTank(capacity) }

    override fun getTanks(): Int = tanks.size
    override fun getFluidInTank(tank: Int): FluidStack = tanks.getOrNull(tank)?.fluid ?: FluidStack.EMPTY
    override fun getTankCapacity(tank: Int): Int = if (tank in tanks.indices) capacity else 0
    override fun isFluidValid(tank: Int, stack: FluidStack): Boolean = tank in tanks.indices

    /** How much of [stack]'s fluid is held across every tank. */
    fun amountOf(stack: FluidStack): Int = tanks.filter { FluidStack.isSameFluidSameComponents(it.fluid, stack) }.sumOf { it.fluidAmount }

    override fun fill(resource: FluidStack, action: IFluidHandler.FluidAction): Int {
        if (resource.isEmpty) return 0
        var left = resource.amount
        val order = tanks.filter { FluidStack.isSameFluidSameComponents(it.fluid, resource) } + tanks.filter { it.isEmpty }
        for (t in order) {
            if (left <= 0) break
            left -= t.fill(resource.copyWithAmount(left), action)
        }
        val filled = resource.amount - left
        if (filled > 0 && action.execute()) onChange()
        return filled
    }

    override fun drain(resource: FluidStack, action: IFluidHandler.FluidAction): FluidStack {
        if (resource.isEmpty) return FluidStack.EMPTY
        var left = resource.amount
        var out = FluidStack.EMPTY
        for (t in tanks) {
            if (left <= 0) break
            if (!FluidStack.isSameFluidSameComponents(t.fluid, resource)) continue
            val got = t.drain(resource.copyWithAmount(left), action)
            if (got.isEmpty) continue
            out = if (out.isEmpty) got else out.copyWithAmount(out.amount + got.amount)
            left -= got.amount
        }
        if (!out.isEmpty && action.execute()) onChange()
        return out
    }

    override fun drain(maxDrain: Int, action: IFluidHandler.FluidAction): FluidStack {
        val first = tanks.firstOrNull { !it.isEmpty } ?: return FluidStack.EMPTY
        return drain(first.fluid.copyWithAmount(maxDrain), action)
    }

    fun clear() {
        tanks.forEach { it.fluid = FluidStack.EMPTY }
        onChange()
    }

    fun save(registries: HolderLookup.Provider): ListTag = ListTag().also { list ->
        for (t in tanks) list.add(t.writeToNBT(registries, CompoundTag()))
    }

    fun load(registries: HolderLookup.Provider, list: ListTag) {
        tanks.forEach { it.fluid = FluidStack.EMPTY }
        for (i in 0 until minOf(list.size, tanks.size)) tanks[i].readFromNBT(registries, list.getCompound(i))
    }
}

/** The controller's energy cell, with the setter the NBT and the `/bpm energy` command need. */
class ControllerEnergy(capacity: Int, rate: Int, private val onChange: () -> Unit) : EnergyStorage(capacity, rate, rate) {
    override fun receiveEnergy(toReceive: Int, simulate: Boolean): Int = super.receiveEnergy(toReceive, simulate).also { if (it > 0 && !simulate) onChange() }
    override fun extractEnergy(toExtract: Int, simulate: Boolean): Int = super.extractEnergy(toExtract, simulate).also { if (it > 0 && !simulate) onChange() }

    fun set(value: Int) {
        energy = value.coerceIn(0, capacity)
        onChange()
    }

    fun save(registries: HolderLookup.Provider): Tag = serializeNBT(registries)
    fun load(registries: HolderLookup.Provider, tag: Tag) = deserializeNBT(registries, tag)
}
