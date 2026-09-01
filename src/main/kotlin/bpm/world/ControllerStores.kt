package bpm.world

/**
 * The sizes of the controller's own stores — what the reserved link `self` names for each kind of thing:
 * nine item slots ([ControllerBlockEntity.inventory]), [TANKS] fluid tanks and one energy cell. Pipes,
 * cables and other mods reach them through the block capabilities; scripts through `self`.
 *
 * The stores themselves are [bpm.platform.ports.SlotStore], [bpm.platform.ports.MultiTank] and
 * [bpm.platform.ports.EnergyCell] — mod-owned, so that nothing about a controller's contents depends on
 * a loader's base classes. They are wrapped back into capabilities where they are published.
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
