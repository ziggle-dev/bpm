package bpm.world

import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import java.util.function.Consumer

/**
 * The quality of the core a controller was built around (§7.1 of the mechanics design): every tier crafts
 * the same controller, and the tier rides along as a data component — into the block when placed, back
 * into the item when broken — deciding how far it links and how much of the tick it may use.
 */
enum class CoreTier(val key: String, val linkRange: Double, val budgetMultiplier: Double, val neverThrottled: Boolean) {
    STABLE("stable", 32.0, 1.0, false),
    REFINED("refined", 40.0, 1.25, false),
    PRISTINE("pristine", 48.0, 1.5, true);

    val label: String get() = key.replaceFirstChar { it.uppercase() }

    companion object {
        fun byKey(key: String?): CoreTier = entries.firstOrNull { it.key == key } ?: STABLE

        fun of(item: Item): CoreTier? = when (item) {
            ContentItems.QUANTUM_CORE.get() -> STABLE
            ContentItems.REFINED_QUANTUM_CORE.get() -> REFINED
            ContentItems.PRISTINE_QUANTUM_CORE.get() -> PRISTINE
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
