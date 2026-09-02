package bpm.catalog.values

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import dev.ziggle.vscript.vm.StructValue
import net.minecraft.advancements.critereon.ItemPredicate
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import java.util.Collections

/**
 * A `Filter` record: which stacks a verb should touch.
 *
 * Two layers, and the second is the whole game's own:
 *
 *  - **Fields for the common questions**, each ignored when empty: `item` (this item), `tag` (in this tag),
 *    `enchant` + `level` (carries this enchantment at least this strong), `component` (has this data
 *    component at all), `name` (display name contains this, case-insensitive), `damaged` (is / is not
 *    damaged).
 *  - **`predicate`** — a vanilla item predicate as JSON, the same thing an advancement or a loot table
 *    writes: `{"items": "#c:ores", "predicates": {"minecraft:enchantments": [{"enchantments": "minecraft:silk_touch"}], "minecraft:custom_data": {"my_mod": {"tier": 3}}}}`.
 *    Every sub-predicate the game has — enchantments, stored enchantments, damage, potion contents, trims,
 *    custom NBT, written books, containers — comes with it, and mods that add their own arrive too.
 *
 * `min` is how many must be there before `has` and `waitFor` say yes (0 = any); `max` is a cap for verbs
 * that move a quantity (0 = none). A filter is compiled into a [Matcher] once per verb call, so a predicate
 * is parsed once, not once per slot.
 */
object FilterValue {
    const val TYPE = "Filter"
    val FIELDS = listOf("item", "tag", "min", "max", "enchant", "level", "component", "name", "damaged", "predicate", "any", "all", "not")

    fun of(
        item: String? = null, tag: String? = null, min: Int = 0, max: Int = 0,
        enchant: String? = null, level: Int = 0, component: String? = null, name: String? = null,
        damaged: Boolean? = null, predicate: String? = null,
        any: List<StructValue>? = null, all: List<StructValue>? = null, not: StructValue? = null,
    ): StructValue = StructValue(
        TYPE, FIELDS,
        arrayOf(item, tag, min, max, enchant, level, component, name, damaged, predicate, any?.let { ArrayList<Any?>(it) }, all?.let { ArrayList<Any?>(it) }, not),
    )

    /** Admits what any of [filters] admit. `min`/`max` come from the first. */
    fun anyOf(filters: List<StructValue>): StructValue = of(min = min(filters.firstOrNull()), max = max(filters.firstOrNull()), any = filters)

    /** Admits only what all of [filters] admit. `min`/`max` come from the first. */
    fun allOf(filters: List<StructValue>): StructValue = of(min = min(filters.firstOrNull()), max = max(filters.firstOrNull()), all = filters)

    /** Admits what [filter] refuses (an empty filter refuses nothing, so `not` of it admits nothing). */
    fun not(filter: StructValue?): StructValue = of(not = filter ?: of())

    fun record(value: Any?): StructValue? = (value as? StructValue)?.takeIf { it.type == TYPE }

    fun min(filter: StructValue?): Int = filter?.let { BlockPosValue.int(it.get("min")) } ?: 0
    fun max(filter: StructValue?): Int = filter?.let { BlockPosValue.int(it.get("max")) } ?: 0

    /** Everything matches, and nothing is asked of it. */
    val ANY: Matcher = Matcher { !it.isEmpty }

    fun interface Matcher {
        fun matches(stack: ItemStack): Boolean
    }

    /** Compile a filter against the registries the stacks live in. Null or an empty record is [ANY]. */
    fun matcher(filter: StructValue?, registries: RegistryAccess?): Matcher {
        if (filter == null) return ANY
        val checks = ArrayList<(ItemStack) -> Boolean>()

        text(filter, "item")?.let { id -> checks += { s -> RegistryIds.of(s.item) == id } }
        text(filter, "tag")?.let { id ->
            val key: TagKey<Item> = RegistryIds.itemTag(id) ?: return NONE
            checks += { s -> s.`is`(key) }
        }
        text(filter, "enchant")?.let { id ->
            val holder = enchantment(id, registries) ?: return NONE
            val min = BlockPosValue.int(filter.get("level")).coerceAtLeast(1)
            checks += { s -> s.enchantments.getLevel(holder) >= min }
        }
        text(filter, "component")?.let { id ->
            val type = componentType(id) ?: return NONE
            checks += { s -> s.has(type) }
        }
        text(filter, "name")?.let { needle ->
            val n = needle.lowercase()
            checks += { s -> s.hoverName.string.lowercase().contains(n) }
        }
        (filter.get("damaged") as? Boolean)?.let { want -> checks += { s -> s.isDamaged == want } }
        subFilters(filter, "any")?.let { subs ->
            val ms = subs.map { matcher(it, registries) }
            checks += { s -> ms.any { m -> m.matches(s) } }
        }
        subFilters(filter, "all")?.let { subs ->
            val ms = subs.map { matcher(it, registries) }
            checks += { s -> ms.all { m -> m.matches(s) } }
        }
        record(filter.get("not"))?.let { sub ->
            val m = matcher(sub, registries)
            checks += { s -> !m.matches(s) }
        }
        text(filter, "predicate")?.let { json ->
            val p = predicate(json, registries) ?: return NONE
            checks += { s -> p.test(s) }
        }
        if (checks.isEmpty()) return ANY
        return Matcher { s -> !s.isEmpty && checks.all { it(s) } }
    }

    /** A filter naming something that does not exist matches nothing, rather than everything. */
    private val NONE: Matcher = Matcher { false }

    /**
     * The same filter, asked of a BLOCK: `item` is the block's id (or its item form's), `tag` a block tag
     * (`c:ores`, `minecraft:logs`) or the item tag of its item form, `name` the block's name, and `any` /
     * `all` / `not` compose as for stacks. The questions only a stack can answer — enchantment, component,
     * damaged, a predicate, the counts — do not apply and are ignored. Air never matches.
     */
    fun interface BlockMatcher {
        fun matches(state: net.minecraft.world.level.block.state.BlockState): Boolean
    }

    /** Any block but air. */
    val ANY_BLOCK: BlockMatcher = BlockMatcher { !it.isAir }

    private val NONE_BLOCK: BlockMatcher = BlockMatcher { false }

    fun blockMatcher(filter: StructValue?, registries: RegistryAccess?): BlockMatcher {
        if (filter == null) return ANY_BLOCK
        val checks = ArrayList<(net.minecraft.world.level.block.state.BlockState) -> Boolean>()
        text(filter, "item")?.let { id ->
            checks += { s -> RegistryIds.of(s.block) == id || RegistryIds.of(s.block.asItem()) == id }
        }
        text(filter, "tag")?.let { id ->
            val rl = ResourceLocation.tryParse(id.removePrefix("#").trim()) ?: return NONE_BLOCK
            val blockTag = TagKey.create(Registries.BLOCK, rl)
            val itemTag = RegistryIds.itemTag(id)
            checks += { s -> s.`is`(blockTag) || (itemTag != null && ItemStack(s.block.asItem()).`is`(itemTag)) }
        }
        text(filter, "name")?.let { needle ->
            val n = needle.lowercase()
            checks += { s -> s.block.name.string.lowercase().contains(n) }
        }
        subFilters(filter, "any")?.let { subs ->
            val ms = subs.map { blockMatcher(it, registries) }
            checks += { s -> ms.any { m -> m.matches(s) } }
        }
        subFilters(filter, "all")?.let { subs ->
            val ms = subs.map { blockMatcher(it, registries) }
            checks += { s -> ms.all { m -> m.matches(s) } }
        }
        record(filter.get("not"))?.let { sub ->
            val m = blockMatcher(sub, registries)
            checks += { s -> !m.matches(s) }
        }
        if (checks.isEmpty()) return ANY_BLOCK
        return BlockMatcher { s -> !s.isAir && checks.all { it(s) } }
    }

    private fun text(filter: StructValue, field: String): String? = filter.get(field)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun subFilters(filter: StructValue, field: String): List<StructValue>? =
        (filter.get(field) as? List<*>)?.mapNotNull { record(it) }?.takeIf { it.isNotEmpty() }

    fun enchantment(id: String, registries: RegistryAccess?): Holder<Enchantment>? {
        val rl = ResourceLocation.tryParse(id.trim()) ?: return null
        val access = registries ?: return null
        return bpm.platform.holderOrNull(access, Registries.ENCHANTMENT, ResourceKey.create(Registries.ENCHANTMENT, rl))
    }

    fun componentType(id: String): DataComponentType<*>? =
        ResourceLocation.tryParse(id.trim())?.let { bpm.platform.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE, it) }

    /** A parsed vanilla item predicate, cached by its text. Null when the JSON is not one. */
    fun predicate(json: String, registries: RegistryAccess?): ItemPredicate? {
        predicates[json]?.let { return it }
        val element: JsonElement = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return null
        val ops = if (registries != null) RegistryOps.create(JsonOps.INSTANCE, registries) else JsonOps.INSTANCE
        val parsed = runCatching { ItemPredicate.CODEC.parse(ops, element).result().orElse(null) }.getOrNull() ?: return null
        if (predicates.size > 256) predicates.clear()
        predicates[json] = parsed
        return parsed
    }

    private val predicates: MutableMap<String, ItemPredicate> = Collections.synchronizedMap(HashMap())
}

/** A `Slot` record: a slot number and what it holds — for the verbs that work slot by slot. */
object SlotValue {
    const val TYPE = "Slot"
    val FIELDS = listOf("slot", "stack")

    fun of(slot: Int, stack: ItemStack): StructValue = StructValue(TYPE, FIELDS, arrayOf<Any?>(slot, ItemStackValue.record(stack)))
}
