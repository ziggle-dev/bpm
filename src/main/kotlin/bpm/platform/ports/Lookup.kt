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

object Ports {
    private lateinit var backend: PlatformPorts

    fun install(impl: PlatformPorts) {
        backend = impl
    }

    fun cache(level: ServerLevel, pos: BlockPos, side: Direction?): PortCache = backend.cache(level, pos, side)

    fun playerInventory(player: Player): ItemPort? = backend.playerInventory(player)
}
