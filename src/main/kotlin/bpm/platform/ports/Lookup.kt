package bpm.platform.ports

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player

/**
 * Asking a block, or a person, what they offer.
 *
 * Finding a store is as loader-specific as implementing one. NeoForge answers through
 * `BlockCapabilityCache`, which is worth keeping on that loader — it watches the position and
 * invalidates itself, so a link does not re-look-up every tick. Fabric's transfer API caches
 * differently. Both hide behind [PortCache].
 */
interface PortCache {
    val items: ItemPort?
    val fluids: FluidPort?
    val energy: EnergyPort?
}

interface PlatformPorts {
    /** A cache pinned to one (level, position, side) — what a resolved link holds for its lifetime. */
    fun cache(level: ServerLevel, pos: BlockPos, side: Direction?): PortCache

    /**
     * A player's own slots.
     *
     * The default is a [ContainerPort] over the vanilla inventory, which is 41 slots in exactly the order
     * NeoForge's `PlayerInvWrapper` uses — 0–35 the pack, 36–39 armour, 40 the offhand. That means this
     * needs no loader support at all, and `Capabilities.ItemHandler.ENTITY`, which Fabric's transfer API
     * has no answer for, stops being a problem rather than needing one solved.
     *
     * NeoForge still overrides it to prefer the capability, because that is what carries other mods'
     * inventory extensions: a backpack mod's slots should stay reachable through a tether.
     */
    fun playerInventory(player: Player): ItemPort? = ContainerPort(player.inventory)
}

/**
 * Saying what a block entity of ours offers, which is the other half of [PlatformPorts].
 *
 * [PortCache] asks a block in the world what it has; this declares what OUR blocks have, so that a pipe
 * from any other mod finds them. NeoForge collects these in `RegisterCapabilitiesEvent`, Fabric through
 * `ItemStorage.SIDED.registerForBlockEntity` and its two siblings. Both are "for this block entity type,
 * here is the port, possibly depending on which side you asked from", so that is what this says.
 *
 * The side is nullable because both loaders allow a sideless query, and several of our blocks do not care
 * which face you arrive at. Returning null means "not from there" — that is how the assembler is fed from
 * underneath only.
 */
interface PortSink {
    fun <T : net.minecraft.world.level.block.entity.BlockEntity> items(
        type: net.minecraft.world.level.block.entity.BlockEntityType<T>,
        port: (T, Direction?) -> ItemPort?,
    )

    fun <T : net.minecraft.world.level.block.entity.BlockEntity> fluids(
        type: net.minecraft.world.level.block.entity.BlockEntityType<T>,
        port: (T, Direction?) -> FluidPort?,
    )

    fun <T : net.minecraft.world.level.block.entity.BlockEntity> energy(
        type: net.minecraft.world.level.block.entity.BlockEntityType<T>,
        port: (T, Direction?) -> EnergyPort?,
    )
}

interface PortProviders {
    /**
     * [block] may be called later, when this loader is ready to hear it.
     *
     * Deferred, and it has to be: the block names its block entity types through the deferred registers,
     * so running it during mod construction resolves holders nothing has bound yet.
     */
    fun providers(block: (PortSink) -> Unit)
}

object Ports {
    private lateinit var backend: PlatformPorts
    private lateinit var providers: PortProviders

    fun install(impl: PlatformPorts, providers: PortProviders) {
        backend = impl
        this.providers = providers
    }

    fun providers(block: (PortSink) -> Unit) = providers.providers(block)

    fun cache(level: ServerLevel, pos: BlockPos, side: Direction?): PortCache = backend.cache(level, pos, side)

    fun playerInventory(player: Player): ItemPort? = backend.playerInventory(player)
}
