package bpm.platform.ports

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.capabilities.BlockCapabilityCache
import net.neoforged.neoforge.capabilities.Capabilities

/** NeoForge's capability lookups, including the caches that make a per-tick link cheap. */
object NeoPorts : PlatformPorts {

    override fun cache(level: ServerLevel, pos: BlockPos, side: Direction?): PortCache = NeoPortCache(level, pos, side)

    /**
     * The entity capability rather than the vanilla container, because on this loader that is what
     * carries other mods' inventory extensions — a backpack mod's slots stay reachable through a tether.
     */
    override fun playerInventory(player: Player): ItemPort? =
        player.getCapability(Capabilities.ItemHandler.ENTITY)?.let(::HandlerPort)
}

private class NeoPortCache(level: ServerLevel, pos: BlockPos, side: Direction?) : PortCache {
    private val itemCache by lazy { BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, level, pos, side) }
    private val fluidCache by lazy { BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, level, pos, side) }
    private val energyCache by lazy { BlockCapabilityCache.create(Capabilities.EnergyStorage.BLOCK, level, pos, side) }

    override val items: ItemPort? get() = itemCache.capability?.let(::HandlerPort)
    override val fluids: FluidPort? get() = fluidCache.capability?.let(::HandlerFluidPort)
    override val energy: EnergyPort? get() = energyCache.capability?.let(::StoragePort)
}
