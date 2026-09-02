package bpm.platform.ports

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.capabilities.BlockCapabilityCache
import net.neoforged.neoforge.capabilities.Capabilities

/**
 * The capability names, which moved with the API they name.
 *
 * `Capabilities.ItemHandler` is `Capabilities.Item` from 1.21.11, `FluidHandler` is `Fluid`, and
 * `EnergyStorage` is `Energy` -- the names lost the "handler" suffix when the handler type became a
 * `ResourceHandler` shared by all three. The lookups are otherwise identical, which is why only the
 * constant differs here and the caches, the sink and the providers are shared.
 */
//? if >=1.21.9 {
/*private val ITEM_BLOCK get() = Capabilities.Item.BLOCK
private val ITEM_ENTITY get() = Capabilities.Item.ENTITY
private val FLUID_BLOCK get() = Capabilities.Fluid.BLOCK
private val ENERGY_BLOCK get() = Capabilities.Energy.BLOCK
*///?} else {
private val ITEM_BLOCK get() = Capabilities.ItemHandler.BLOCK
private val ITEM_ENTITY get() = Capabilities.ItemHandler.ENTITY
private val FLUID_BLOCK get() = Capabilities.FluidHandler.BLOCK
private val ENERGY_BLOCK get() = Capabilities.EnergyStorage.BLOCK
//?}

/** NeoForge's capability lookups, including the caches that make a per-tick link cheap. */
object NeoPorts : PlatformPorts {

    override fun cache(level: ServerLevel, pos: BlockPos, side: Direction?): PortCache = NeoPortCache(level, pos, side)

    /**
     * The entity capability rather than the vanilla container, because on this loader that is what
     * carries other mods' inventory extensions — a backpack mod's slots stay reachable through a tether.
     */
    override fun playerInventory(player: Player): ItemPort? =
        player.getCapability(ITEM_ENTITY)?.let(::HandlerPort)
}

private class NeoPortCache(level: ServerLevel, pos: BlockPos, side: Direction?) : PortCache {
    private val itemCache by lazy { BlockCapabilityCache.create(ITEM_BLOCK, level, pos, side) }
    private val fluidCache by lazy { BlockCapabilityCache.create(FLUID_BLOCK, level, pos, side) }
    private val energyCache by lazy { BlockCapabilityCache.create(ENERGY_BLOCK, level, pos, side) }

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
            ) = event.registerBlockEntity(ITEM_BLOCK, type) { be, side ->
                port(be, side)?.let(::PortHandler)
            }

            override fun <T : net.minecraft.world.level.block.entity.BlockEntity> fluids(
                type: net.minecraft.world.level.block.entity.BlockEntityType<T>,
                port: (T, Direction?) -> FluidPort?,
            ) = event.registerBlockEntity(FLUID_BLOCK, type) { be, side ->
                port(be, side)?.let(::PortFluidHandler)
            }

            override fun <T : net.minecraft.world.level.block.entity.BlockEntity> energy(
                type: net.minecraft.world.level.block.entity.BlockEntityType<T>,
                port: (T, Direction?) -> EnergyPort?,
            ) = event.registerBlockEntity(ENERGY_BLOCK, type) { be, side ->
                port(be, side)?.let(::PortStorage)
            }
        }
        for (block in pending) block(sink)
    }
}
