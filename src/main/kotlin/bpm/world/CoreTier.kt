package bpm.world

import bpm.BpmConfig
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import java.util.function.Consumer

/**
 * The quality of the core a controller was built around (`docs/DESIGN_TIERS_AND_FABRICATION.md`): every tier
 * crafts the same controller, and the tier rides along as a data component — into the block when placed, back
 * into the item when broken.
 *
 * A tier buys **reach and breadth, and nothing else**: how far the controller links, and how many links it may
 * hold. It used to buy tick time as well, which made server lag a progression currency and a server's worst
 * case depend on which cores its players happened to hold; every controller now gets the same share of the
 * tick, whatever it was built around (see `RuntimeManager`).
 *
 * The fields here are the DEFAULTS. What a tier actually reaches and holds is the server's to say, which is
 * why every one of them is read back through [BpmConfig] on each ask rather than stored.
 */
enum class CoreTier(
    val key: String,
    val defaultRangeFactor: Double,
    val defaultMaxLinks: Int,
    val defaultMaxPlayerLinks: Int,
    val defaultUnlimited: Boolean = false,
) {
    STABLE("stable", 1.0, 8, 1),
    REFINED("refined", 2.0, 16, 1),
    PRISTINE("pristine", 4.0, 32, 2),

    /** The Entangled Core: a pair that stays correlated across a gate — the first tier that reaches the chamber. */
    ENTANGLED("entangled", 8.0, 64, 4),

    /** The Coherent Core: a signal that never decoheres never weakens with distance, so this one has no horizon. */
    COHERENT("coherent", 16.0, 128, 8, defaultUnlimited = true);

    val label: String get() = key.replaceFirstChar { it.uppercase() }

    /** How far this tier links, in blocks — [Double.POSITIVE_INFINITY] when it has no horizon at all. */
    val linkRange: Double get() = BpmConfig.rangeOf(this)

    /** How many links a controller of this tier may hold. */
    val maxLinks: Int get() = BpmConfig.maxLinksOf(this)

    /** How many of those links may be people — see `docs/DESIGN_PLAYER_LINK.md`. */
    val maxPlayerLinks: Int get() = BpmConfig.maxPlayerLinksOf(this)

    val unlimitedRange: Boolean get() = linkRange.isInfinite()

    /** The reach as a person reads it. */
    val rangeText: String get() = rangeText(linkRange)

    companion object {
        /** `∞` for a tier with no horizon, the number of blocks otherwise — never `2147483647`. */
        fun rangeText(range: Double): String = if (range.isInfinite()) "∞" else range.toInt().toString()

        fun byKey(key: String?): CoreTier = entries.firstOrNull { it.key == key } ?: STABLE

        fun of(item: Item): CoreTier? = when (item) {
            ContentItems.QUANTUM_CORE.get() -> STABLE
            ContentItems.REFINED_QUANTUM_CORE.get() -> REFINED
            ContentItems.PRISTINE_QUANTUM_CORE.get() -> PRISTINE
            ContentItems.ENTANGLED_QUANTUM_CORE.get() -> ENTANGLED
            ContentItems.COHERENT_QUANTUM_CORE.get() -> COHERENT
            else -> null
        }

        fun of(stack: ItemStack): CoreTier = byKey(stack.get(ModComponents.CORE_TIER.get()))
    }
}

/** Stamps the core's tier onto a controller as it leaves the crafting grid. */
object CoreTiers {
    fun install(bus: IEventBus) {
        bus.addListener(PlayerEvent.ItemCraftedEvent::class.java, Consumer(::onCrafted))
    }

    private fun onCrafted(event: PlayerEvent.ItemCraftedEvent) {
        val result = event.crafting
        if (!result.`is`(ModItems.CONTROLLER.get())) return
        val inv = event.inventory
        var tier: CoreTier? = null
        for (i in 0 until inv.containerSize) {
            val t = CoreTier.of(inv.getItem(i).item) ?: continue
            if (tier == null || t.ordinal > tier.ordinal) tier = t
        }
        result.set(ModComponents.CORE_TIER.get(), (tier ?: CoreTier.STABLE).key)
    }
}
