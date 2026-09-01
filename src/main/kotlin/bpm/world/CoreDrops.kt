package bpm.world

import net.minecraft.world.item.Item
import net.neoforged.neoforge.registries.DeferredItem

/**
 * What the Warden's pedestal can give you, and how often.
 *
 * These numbers exist twice: here, and in `data/bpm/loot_table/gameplay/core_quality*.json`, which is what
 * the game actually rolls. Loot tables are not synced to clients, so a JEI page cannot read the real one —
 * and a hard-coded copy that quietly disagrees with the drop rates is worse than no page at all, because it
 * is a lie the player has no way to check.
 *
 * `CoreDropsTest` parses the JSON and asserts this table matches it, so the two cannot drift without a
 * failing build. That is the whole reason to state them in Kotlin rather than guess in the renderer.
 */
object CoreDrops {

    class Drop(val item: DeferredItem<out Item>, val weight: Int)

    class Table(
        /** The loot table this mirrors, so the test knows what to check it against. */
        val lootTable: String,
        val headingKey: String,
        val noteKey: String,
        val drops: List<Drop>,
    ) {
        val total: Int get() = drops.sumOf { it.weight }
    }

    /** Claiming the core from the pedestal after the fight, the ordinary way. */
    val PEDESTAL = Table(
        lootTable = "core_quality",
        headingKey = "bpm.jei.warden.pedestal",
        noteKey = "bpm.jei.warden.note",
        drops = listOf(
            Drop(ContentItems.QUANTUM_CORE, 70),
            Drop(ContentItems.REFINED_QUANTUM_CORE, 25),
            Drop(ContentItems.PRISTINE_QUANTUM_CORE, 14),
            Drop(ContentItems.ENTANGLED_QUANTUM_CORE, 3),
        ),
    )

    /** The better odds for a fight finished quickly. */
    val PEDESTAL_FAST = Table(
        lootTable = "core_quality_fast",
        headingKey = "bpm.jei.warden.fast",
        noteKey = "bpm.jei.warden.note_fast",
        drops = listOf(
            Drop(ContentItems.QUANTUM_CORE, 40),
            Drop(ContentItems.REFINED_QUANTUM_CORE, 50),
            Drop(ContentItems.PRISTINE_QUANTUM_CORE, 20),
            Drop(ContentItems.ENTANGLED_QUANTUM_CORE, 5),
        ),
    )

    val all: List<Table> = listOf(PEDESTAL, PEDESTAL_FAST)
}
