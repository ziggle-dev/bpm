package bpm.platform.ports

/*
 * The capability system was REPLACED at 1.20.2, not renamed.
 *
 * NeoForge's is a lookup: `Capabilities.ItemHandler.BLOCK` is a `BlockCapability`, asked of a level and
 * a position, cached by a `BlockCapabilityCache`, and a block entity's provider is registered against
 * its type in `RegisterCapabilitiesEvent`. MinecraftForge 1.20.1's is a query on the OBJECT:
 * `getCapability(cap, side)` answers a `LazyOptional`, and a provider is ATTACHED to an object when
 * `AttachCapabilitiesEvent` fires for it -- there is no per-type registration and no cache type.
 *
 * So the whole file is switched. What the two arms share is the seam they answer to: a `PortCache` that
 * yields the three ports, and a `PortSink` that takes a block entity type and a factory.
 */
//? if >=1.20.2 {
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
//?} else {
/*import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.capabilities.ICapabilityProvider
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.event.AttachCapabilitiesEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.IEventBus

/** Forge's capability queries, asked of the block entity at a position. */
object NeoPorts : PlatformPorts {

    override fun cache(level: ServerLevel, pos: BlockPos, side: Direction?): PortCache = NeoPortCache(level, pos, side)

    override fun playerInventory(player: Player): ItemPort? =
        player.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null)?.let(::HandlerPort)
}

/**
 * There is no cache type on this band, so the "cache" is the position it was made for.
 *
 * A `LazyOptional` is invalidated by whoever handed it out, and holding one across ticks means holding
 * a stale one when the block is replaced. Looking the block entity up each time is what the older API
 * expects and is a hash lookup in the chunk -- the cost the newer `BlockCapabilityCache` removes.
 */
private class NeoPortCache(
    private val level: ServerLevel,
    private val pos: BlockPos,
    private val side: Direction?,
) : PortCache {

    private fun <T> look(cap: Capability<T>): T? =
        level.getBlockEntity(pos)?.getCapability(cap, side)?.resolve()?.orElse(null)

    override val items: ItemPort? get() = look(ForgeCapabilities.ITEM_HANDLER)?.let(::HandlerPort)
    override val fluids: FluidPort? get() = look(ForgeCapabilities.FLUID_HANDLER)?.let(::HandlerFluidPort)
    override val energy: EnergyPort? get() = look(ForgeCapabilities.ENERGY)?.let(::StoragePort)
}

/**
 * Providers, attached rather than registered.
 *
 * The declarations are collected exactly as they are on the newer band; what differs is where they go.
 * `AttachCapabilitiesEvent<BlockEntity>` fires once per block entity as it is built, and the provider
 * added there answers for that one object -- so the tables below are keyed by block entity TYPE and
 * consulted when the event names one of ours.
 */
object NeoPortProviders : PortProviders {

    private val pending = ArrayList<(PortSink) -> Unit>()
    private val itemPorts = HashMap<BlockEntityType<*>, (BlockEntity, Direction?) -> ItemPort?>()
    private val fluidPorts = HashMap<BlockEntityType<*>, (BlockEntity, Direction?) -> FluidPort?>()
    private val energyPorts = HashMap<BlockEntityType<*>, (BlockEntity, Direction?) -> EnergyPort?>()

    override fun providers(block: (PortSink) -> Unit) {
        pending += block
    }

    /** Fill the tables and start listening. Called from the entry point. */
    @Suppress("UNCHECKED_CAST")
    fun install(gameBus: IEventBus) {
        val sink = object : PortSink {
            override fun <T : BlockEntity> items(type: BlockEntityType<T>, port: (T, Direction?) -> ItemPort?) {
                itemPorts[type] = { be, side -> port(be as T, side) }
            }

            override fun <T : BlockEntity> fluids(type: BlockEntityType<T>, port: (T, Direction?) -> FluidPort?) {
                fluidPorts[type] = { be, side -> port(be as T, side) }
            }

            override fun <T : BlockEntity> energy(type: BlockEntityType<T>, port: (T, Direction?) -> EnergyPort?) {
                energyPorts[type] = { be, side -> port(be as T, side) }
            }
        }
        for (block in pending) block(sink)
        pending.clear()

        gameBus.addGenericListener(
            BlockEntity::class.java,
            EventPriority.NORMAL,
            false,
            AttachCapabilitiesEvent::class.java as Class<AttachCapabilitiesEvent<BlockEntity>>,
        ) { event ->
            val be = event.`object`
            val type = be.type
            if (type !in itemPorts && type !in fluidPorts && type !in energyPorts) return@addGenericListener
            event.addCapability(bpm.platform.idOf(bpm.Bpm.ID, "ports"), Attached(be))
        }
    }

    /**
     * One provider per block entity, answering all three capabilities.
     *
     * The `LazyOptional`s are made per side on first ask and kept, because Forge callers hold on to the
     * one they were given and listen for its invalidation. They are all invalidated together when the
     * block entity goes: `event.addListener` is fired by the dispatcher when the object is invalidated.
     */
    private class Attached(private val be: BlockEntity) : ICapabilityProvider {

        private val made = HashMap<Pair<Capability<*>, Direction?>, LazyOptional<*>> ()

        @Suppress("UNCHECKED_CAST")
        override fun <T> getCapability(cap: Capability<T>, side: Direction?): LazyOptional<T> {
            val existing = made[cap to side]
            if (existing != null) return existing as LazyOptional<T>
            val answer: LazyOptional<*> = when (cap) {
                ForgeCapabilities.ITEM_HANDLER ->
                    itemPorts[be.type]?.invoke(be, side)?.let { LazyOptional.of { PortHandler(it) } } ?: LazyOptional.empty<Any>()
                ForgeCapabilities.FLUID_HANDLER ->
                    fluidPorts[be.type]?.invoke(be, side)?.let { LazyOptional.of { PortFluidHandler(it) } } ?: LazyOptional.empty<Any>()
                ForgeCapabilities.ENERGY ->
                    energyPorts[be.type]?.invoke(be, side)?.let { LazyOptional.of { PortStorage(it) } } ?: LazyOptional.empty<Any>()
                else -> LazyOptional.empty<Any>()
            }
            made[cap to side] = answer
            return answer as LazyOptional<T>
        }
    }
}
*///?}
