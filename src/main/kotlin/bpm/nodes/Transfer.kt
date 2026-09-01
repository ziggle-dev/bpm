package bpm.nodes

import bpm.catalog.values.FilterValue
import bpm.catalog.values.RegistryIds
import net.minecraft.world.item.ItemStack
import bpm.platform.ports.EnergyPort
import bpm.platform.ports.Droplets
import bpm.platform.ports.FluidPort
import bpm.platform.ports.FluidVolume
import bpm.platform.ports.ItemPort
import bpm.platform.ports.insertStacked

/** The capability dances, written once: simulate out, simulate in, then do it for the amount both agreed. */
object Transfer {

    fun count(h: ItemPort, matcher: FilterValue.Matcher): Int {
        var n = 0
        for (slot in 0 until h.slots) {
            val s = h.stackIn(slot)
            if (matcher.matches(s)) n += s.count
        }
        return n
    }

    fun stacks(h: ItemPort, matcher: FilterValue.Matcher): List<ItemStack> =
        (0 until h.slots).map { h.stackIn(it) }.filter { matcher.matches(it) }

    /**
     * What a move actually did: how many items, and one of the stacks that really went.
     *
     * The count alone used to be the whole answer, so the effect had to guess what to draw and guessed from
     * the first stack that *matched* — which is not the same thing as the first that moved. On a player whose
     * tether sits in an early slot, an unfiltered move drew the tether streaming into a chest while the tether
     * stayed exactly where it was. Report what happened instead of predicting it.
     */
    data class Moved(val count: Int, val sample: ItemStack = ItemStack.EMPTY) {

        /** The registry id to show for this move, or empty when nothing moved. */
        val name: String get() = if (sample.isEmpty) "" else RegistryIds.of(sample.item)

        companion object {
            val NOTHING = Moved(0)
        }
    }

    /** Move up to [max] matching items from [from] to [to]. */
    fun items(from: ItemPort, to: ItemPort, matcher: FilterValue.Matcher, max: Int): Moved {
        var moved = 0
        var sample = ItemStack.EMPTY
        for (slot in 0 until from.slots) {
            if (moved >= max) break
            val peek = from.stackIn(slot)
            if (!matcher.matches(peek)) continue
            val step = moveSlot(from, slot, to, max - moved)
            moved += step.count
            if (sample.isEmpty) sample = step.sample
        }
        return Moved(moved, sample)
    }

    /** Move up to [max] items out of one slot of [from] into [to]. Answers how many moved. */
    fun moveSlot(from: ItemPort, slot: Int, to: ItemPort, max: Int): Moved {
        if (slot < 0 || slot >= from.slots || max <= 0) return Moved.NOTHING
        val offered = from.extract(slot, max, true)
        if (offered.isEmpty) return Moved.NOTHING
        val accepted = offered.count - to.insertStacked(offered, true).count
        if (accepted <= 0) return Moved.NOTHING
        val taken = from.extract(slot, accepted, false)
        if (taken.isEmpty) return Moved.NOTHING
        // What it is, before insertion is allowed to touch the stack.
        val what = taken.copy()
        val left = to.insertStacked(taken, false)
        // The simulation said it would fit; if a handler changed its mind, the items go back.
        if (!left.isEmpty) from.insertStacked(left, false)
        val count = what.count - left.count
        return if (count <= 0) Moved.NOTHING else Moved(count, what)
    }

    /** Take up to [max] matching items out of [from] as one stack (one slot's worth). */
    fun extract(from: ItemPort, matcher: FilterValue.Matcher, max: Int, simulate: Boolean): ItemStack {
        for (slot in 0 until from.slots) {
            if (!matcher.matches(from.stackIn(slot))) continue
            val out = from.extract(slot, max, simulate)
            if (!out.isEmpty) return out
        }
        return ItemStack.EMPTY
    }

    fun insert(to: ItemPort, stack: ItemStack, simulate: Boolean): ItemStack =
        if (stack.isEmpty) ItemStack.EMPTY else to.insertStacked(stack, simulate)

    /** Move up to [maxMb] of [fluidId] (empty = whatever is first) from one tank to another. Answers the mB moved. */
    fun fluids(from: FluidPort, to: FluidPort, fluidId: String?, maxMb: Int): Int {
        val want = fluidId?.takeIf { it.isNotBlank() }
        val cap = Droplets.ofMb(maxMb)
        val drained: FluidVolume = if (want == null) {
            from.drain(cap, simulate = true)
        } else {
            val kind = RegistryIds.fluid(want) ?: return 0
            from.drain(FluidVolume(kind, cap), simulate = true)
        }
        if (drained.isEmpty) return 0
        val accepted = to.fill(drained, simulate = true)
        if (accepted <= 0L) return 0
        val real = from.drain(drained.withDroplets(accepted), simulate = false)
        if (real.isEmpty) return 0
        val filled = to.fill(real, simulate = false)
        // The simulation said it would fit; if a tank changed its mind, the fluid goes back.
        if (filled < real.droplets) from.fill(real.withDroplets(real.droplets - filled), simulate = false)
        // Droplets throughout, millibuckets only here, where the scripting contract is.
        return Droplets.toMb(filled)
    }

    /** Move up to [max] FE from one store to another. Answers the FE moved. */
    fun energy(from: EnergyPort, to: EnergyPort, max: Long): Long {
        if (!from.canExtract() || !to.canReceive()) return 0L
        val offered = from.extract(max, true)
        if (offered <= 0L) return 0L
        val accepted = to.receive(offered, true)
        if (accepted <= 0L) return 0L
        val taken = from.extract(accepted, false)
        val received = to.receive(taken, false)
        // The store changed its mind between the simulation and the deed; put back what it would not take.
        if (received < taken) from.receive(taken - received, false)
        return received
    }
}
