package bpm.nodes

import bpm.catalog.values.FilterValue
import bpm.catalog.values.RegistryIds
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.energy.IEnergyStorage
import net.neoforged.neoforge.fluids.FluidStack
import net.neoforged.neoforge.fluids.capability.IFluidHandler
import net.neoforged.neoforge.items.IItemHandler
import net.neoforged.neoforge.items.ItemHandlerHelper

/** The capability dances, written once: simulate out, simulate in, then do it for the amount both agreed. */
object Transfer {

    fun count(h: IItemHandler, matcher: FilterValue.Matcher): Int {
        var n = 0
        for (slot in 0 until h.slots) {
            val s = h.getStackInSlot(slot)
            if (matcher.matches(s)) n += s.count
        }
        return n
    }

    fun stacks(h: IItemHandler, matcher: FilterValue.Matcher): List<ItemStack> =
        (0 until h.slots).map { h.getStackInSlot(it) }.filter { matcher.matches(it) }

    /** Move up to [max] matching items from [from] to [to]. Answers how many moved. */
    fun items(from: IItemHandler, to: IItemHandler, matcher: FilterValue.Matcher, max: Int): Int {
        var moved = 0
        for (slot in 0 until from.slots) {
            if (moved >= max) break
            val peek = from.getStackInSlot(slot)
            if (!matcher.matches(peek)) continue
            moved += moveSlot(from, slot, to, max - moved)
        }
        return moved
    }

    /** Move up to [max] items out of one slot of [from] into [to]. Answers how many moved. */
    fun moveSlot(from: IItemHandler, slot: Int, to: IItemHandler, max: Int): Int {
        if (slot < 0 || slot >= from.slots || max <= 0) return 0
        val offered = from.extractItem(slot, max, true)
        if (offered.isEmpty) return 0
        val accepted = offered.count - ItemHandlerHelper.insertItemStacked(to, offered, true).count
        if (accepted <= 0) return 0
        val taken = from.extractItem(slot, accepted, false)
        if (taken.isEmpty) return 0
        val left = ItemHandlerHelper.insertItemStacked(to, taken, false)
        // The simulation said it would fit; if a handler changed its mind, the items go back.
        if (!left.isEmpty) ItemHandlerHelper.insertItemStacked(from, left, false)
        return taken.count - left.count
    }

    /** Take up to [max] matching items out of [from] as one stack (one slot's worth). */
    fun extract(from: IItemHandler, matcher: FilterValue.Matcher, max: Int, simulate: Boolean): ItemStack {
        for (slot in 0 until from.slots) {
            if (!matcher.matches(from.getStackInSlot(slot))) continue
            val out = from.extractItem(slot, max, simulate)
            if (!out.isEmpty) return out
        }
        return ItemStack.EMPTY
    }

    fun insert(to: IItemHandler, stack: ItemStack, simulate: Boolean): ItemStack =
        if (stack.isEmpty) ItemStack.EMPTY else ItemHandlerHelper.insertItemStacked(to, stack, simulate)

    /** Move up to [maxMb] of [fluidId] (empty = whatever is first) from one tank to another. Answers the mB moved. */
    fun fluids(from: IFluidHandler, to: IFluidHandler, fluidId: String?, maxMb: Int): Int {
        val want = fluidId?.takeIf { it.isNotBlank() }
        val drained: FluidStack = if (want == null) {
            from.drain(maxMb, IFluidHandler.FluidAction.SIMULATE)
        } else {
            val kind = RegistryIds.fluid(want) ?: return 0
            from.drain(FluidStack(kind, maxMb), IFluidHandler.FluidAction.SIMULATE)
        }
        if (drained.isEmpty) return 0
        val accepted = to.fill(drained, IFluidHandler.FluidAction.SIMULATE)
        if (accepted <= 0) return 0
        val real = from.drain(drained.copyWithAmount(accepted), IFluidHandler.FluidAction.EXECUTE)
        if (real.isEmpty) return 0
        val filled = to.fill(real, IFluidHandler.FluidAction.EXECUTE)
        if (filled < real.amount) from.fill(real.copyWithAmount(real.amount - filled), IFluidHandler.FluidAction.EXECUTE)
        return filled
    }

    /** Move up to [max] FE from one store to another. Answers the FE moved. */
    fun energy(from: IEnergyStorage, to: IEnergyStorage, max: Int): Int {
        if (!from.canExtract() || !to.canReceive()) return 0
        val offered = from.extractEnergy(max, true)
        if (offered <= 0) return 0
        val accepted = to.receiveEnergy(offered, true)
        if (accepted <= 0) return 0
        val taken = from.extractEnergy(accepted, false)
        val received = to.receiveEnergy(taken, false)
        if (received < taken) from.receiveEnergy(taken - received, false)
        return received
    }
}
