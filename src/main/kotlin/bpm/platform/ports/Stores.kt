package bpm.platform.ports

import bpm.platform.compoundAt
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import bpm.platform.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import bpm.platform.intOr
import bpm.platform.longOr
import bpm.platform.stringOr
import bpm.platform.compoundOr

/**
 * The stores a controller owns, written against the mod's own ports.
 *
 * These were NeoForge's `ItemStackHandler`, `FluidTank` and `EnergyStorage` — base classes that do not
 * exist on Fabric, and whose NBT shapes are theirs rather than ours. Owning them outright is a few
 * hundred lines and removes the last thing in `bpm/world` that a loader had to supply.
 *
 * They are still published to other mods as capabilities: `PortHandler`, `PortFluidHandler` and
 * `PortStorage` wrap them on the way out, so a pipe or a cable sees exactly what it saw before.
 */

/** A slotted item store that tells its owner when it changed. */
open class SlotStore(private val size: Int, private val onChange: () -> Unit = {}) : ItemPort {
    protected val stacks: Array<ItemStack> = Array(size) { ItemStack.EMPTY }

    override val slots: Int get() = size

    override fun stackIn(slot: Int): ItemStack = if (slot in 0 until size) stacks[slot] else ItemStack.EMPTY

    override fun setStackIn(slot: Int, stack: ItemStack): Boolean {
        if (slot !in 0 until size) return false
        stacks[slot] = stack
        onChange()
        return true
    }

    override fun insert(slot: Int, stack: ItemStack, simulate: Boolean): ItemStack {
        if (stack.isEmpty || slot !in 0 until size) return stack
        val existing = stacks[slot]
        val limit = minOf(slotLimit(slot), stack.maxStackSize)
        val room = if (existing.isEmpty) limit else {
            if (!ItemStack.isSameItemSameComponents(existing, stack)) return stack
            limit - existing.count
        }
        if (room <= 0) return stack
        val moved = minOf(room, stack.count)
        if (!simulate) {
            stacks[slot] = if (existing.isEmpty) stack.copyWithCount(moved) else existing.copyWithCount(existing.count + moved)
            onChange()
        }
        return if (moved >= stack.count) ItemStack.EMPTY else stack.copyWithCount(stack.count - moved)
    }

    override fun extract(slot: Int, amount: Int, simulate: Boolean): ItemStack {
        if (amount <= 0 || slot !in 0 until size) return ItemStack.EMPTY
        val existing = stacks[slot]
        if (existing.isEmpty) return ItemStack.EMPTY
        val taken = minOf(amount, existing.count)
        if (simulate) return existing.copyWithCount(taken)
        val out = existing.copyWithCount(taken)
        stacks[slot] = if (taken >= existing.count) ItemStack.EMPTY else existing.copyWithCount(existing.count - taken)
        onChange()
        return out
    }

    fun clear() {
        for (i in stacks.indices) stacks[i] = ItemStack.EMPTY
        onChange()
    }

    fun save(registries: HolderLookup.Provider): ListTag = ListTag().also { list ->
        for ((i, stack) in stacks.withIndex()) {
            if (stack.isEmpty) continue
            list.add(CompoundTag().also { t ->
                t.putInt("slot", i)
                t.put("item", bpm.platform.writeStack(registries, stack))
            })
        }
    }

    fun load(registries: HolderLookup.Provider, list: ListTag) {
        for (i in stacks.indices) stacks[i] = ItemStack.EMPTY
        for (i in 0 until list.size) {
            val t = list.compoundAt(i)
            val slot = t.intOr("slot", 0)
            if (slot !in 0 until size) continue
            stacks[slot] = bpm.platform.readStack(registries, t.compoundOr("item"))
        }
    }
}

/** One tank holding one kind of fluid. Amounts are droplets — see [Droplets]. */
class Tank(val capacity: Long) {
    var held: FluidVolume = FluidVolume.EMPTY
        private set

    val isEmpty: Boolean get() = held.isEmpty

    fun accepts(volume: FluidVolume): Boolean = held.isEmpty || held.sameKindAs(volume)

    fun fill(volume: FluidVolume, simulate: Boolean): Long {
        if (volume.isEmpty || !accepts(volume)) return 0L
        val room = capacity - held.droplets
        if (room <= 0L) return 0L
        val moved = minOf(room, volume.droplets)
        if (!simulate) held = volume.withDroplets(held.droplets + moved)
        return moved
    }

    fun drain(maxDroplets: Long, simulate: Boolean): FluidVolume {
        if (held.isEmpty || maxDroplets <= 0L) return FluidVolume.EMPTY
        val taken = minOf(maxDroplets, held.droplets)
        val out = held.withDroplets(taken)
        if (!simulate) held = if (taken >= held.droplets) FluidVolume.EMPTY else held.withDroplets(held.droplets - taken)
        return out
    }

    fun set(volume: FluidVolume) {
        held = volume
    }

    fun save(): CompoundTag = CompoundTag().also { t ->
        if (held.isEmpty) return@also
        t.putString("fluid", BuiltInRegistries.FLUID.getKey(held.fluid).toString())
        t.putLong("droplets", held.droplets)
    }

    fun load(t: CompoundTag) {
        val id = t.stringOr("fluid", "").takeIf { it.isNotBlank() } ?: run { held = FluidVolume.EMPTY; return }
        val fluid: Fluid = ResourceLocation.tryParse(id)?.let { bpm.platform.valueOf(BuiltInRegistries.FLUID, it) } ?: Fluids.EMPTY
        held = if (fluid == Fluids.EMPTY) FluidVolume.EMPTY else FluidVolume(fluid, t.longOr("droplets", 0L))
    }
}

/**
 * Several tanks behind one port: filling finds one that already holds the fluid (or an empty one),
 * draining by kind finds the tank holding it, draining by amount takes from the first that has anything.
 */
class MultiTank(count: Int, capacity: Long, private val onChange: () -> Unit = {}) : FluidPort {
    private val list: List<Tank> = List(count) { Tank(capacity) }

    override val tanks: Int get() = list.size
    override fun inTank(tank: Int): FluidVolume = list.getOrNull(tank)?.held ?: FluidVolume.EMPTY
    override fun tankCapacity(tank: Int): Long = if (tank in list.indices) list[tank].capacity else 0L
    override fun isValid(tank: Int, volume: FluidVolume): Boolean = tank in list.indices

    /** How much of [volume]'s kind is held across every tank, in droplets. */
    fun amountOf(volume: FluidVolume): Long = list.filter { !it.isEmpty && it.held.sameKindAs(volume) }.sumOf { it.held.droplets }

    override fun fill(volume: FluidVolume, simulate: Boolean): Long {
        if (volume.isEmpty) return 0L
        var left = volume.droplets
        val order = list.filter { !it.isEmpty && it.held.sameKindAs(volume) } + list.filter { it.isEmpty }
        for (t in order) {
            if (left <= 0L) break
            left -= t.fill(volume.withDroplets(left), simulate)
        }
        val filled = volume.droplets - left
        if (filled > 0L && !simulate) onChange()
        return filled
    }

    override fun drain(volume: FluidVolume, simulate: Boolean): FluidVolume {
        if (volume.isEmpty) return FluidVolume.EMPTY
        var left = volume.droplets
        var out = FluidVolume.EMPTY
        for (t in list) {
            if (left <= 0L) break
            if (t.isEmpty || !t.held.sameKindAs(volume)) continue
            val got = t.drain(left, simulate)
            if (got.isEmpty) continue
            out = if (out.isEmpty) got else out.withDroplets(out.droplets + got.droplets)
            left -= got.droplets
        }
        if (!out.isEmpty && !simulate) onChange()
        return out
    }

    override fun drain(maxDroplets: Long, simulate: Boolean): FluidVolume {
        val first = list.firstOrNull { !it.isEmpty } ?: return FluidVolume.EMPTY
        return drain(first.held.withDroplets(maxDroplets), simulate)
    }

    fun clear() {
        list.forEach { it.set(FluidVolume.EMPTY) }
        onChange()
    }

    fun save(): ListTag = ListTag().also { l -> for (t in list) l.add(t.save()) }

    fun load(l: ListTag) {
        list.forEach { it.set(FluidVolume.EMPTY) }
        for (i in 0 until minOf(l.size, list.size)) list[i].load(l.compoundAt(i))
    }
}

/** An energy cell with a rate limit, and the setter the NBT and `/bpm energy` need. */
class EnergyCell(
    override val capacity: Long,
    private val rate: Long,
    private val onChange: () -> Unit = {},
) : EnergyPort {

    override var stored: Long = 0L
        private set

    override fun canReceive(): Boolean = true
    override fun canExtract(): Boolean = true

    override fun receive(amount: Long, simulate: Boolean): Long {
        val moved = minOf(minOf(amount, rate), capacity - stored).coerceAtLeast(0L)
        if (moved > 0L && !simulate) {
            stored += moved
            onChange()
        }
        return moved
    }

    override fun extract(amount: Long, simulate: Boolean): Long {
        val moved = minOf(minOf(amount, rate), stored).coerceAtLeast(0L)
        if (moved > 0L && !simulate) {
            stored -= moved
            onChange()
        }
        return moved
    }

    fun set(value: Long) {
        stored = value.coerceIn(0L, capacity)
        onChange()
    }

    fun save(): Tag = CompoundTag().also { it.putLong("energy", stored) }

    fun load(tag: Tag) {
        stored = ((tag as? CompoundTag)?.longOr("energy", 0L) ?: 0L).coerceIn(0L, capacity)
    }
}
