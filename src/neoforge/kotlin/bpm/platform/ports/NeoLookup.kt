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


/**
 * NeoForge collects capability providers in `RegisterCapabilitiesEvent`, which is not open while the mod
 * is wiring itself up — so the declarations are held and replayed when it fires.
 */
object NeoPortProviders : PortProviders {

    private val pending = ArrayList<(PortSink) -> Unit>()

    override fun providers(block: (PortSink) -> Unit) {
        pending += block
    }

    fun onRegisterCapabilities(event: net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent) {
        val sink = object : PortSink {
            override fun <T : net.minecraft.world.level.block.entity.BlockEntity> items(
                type: net.minecraft.world.level.block.entity.BlockEntityType<T>,
                port: (T, Direction?) -> ItemPort?,
            ) = event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, type) { be, side ->
                port(be, side)?.let(::PortHandler)
            }

            override fun <T : net.minecraft.world.level.block.entity.BlockEntity> fluids(
                type: net.minecraft.world.level.block.entity.BlockEntityType<T>,
                port: (T, Direction?) -> FluidPort?,
            ) = event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, type) { be, side ->
                port(be, side)?.let(::PortFluidHandler)
            }

            override fun <T : net.minecraft.world.level.block.entity.BlockEntity> energy(
                type: net.minecraft.world.level.block.entity.BlockEntityType<T>,
                port: (T, Direction?) -> EnergyPort?,
            ) = event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, type) { be, side ->
                port(be, side)?.let(::PortStorage)
            }
        }
        for (block in pending) block(sink)
    }
}
