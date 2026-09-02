package bpm.catalog.values

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import dev.ziggle.vscript.vm.StructValue
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import bpm.platform.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.material.Fluid
import bpm.platform.ports.FluidVolume
import java.util.UUID

/*
 * How Minecraft's values cross into the language and back.
 *
 * The rule (see docs/DESIGN_DOMAIN_RUNTIME.md §5): anything a script holds is either a DATA record — a
 * `StructValue` of primitives, so `==` is structural, it prints, and it survives `writeJson` — or a nominal
 * string (a registry id), or an opaque handle that says whether it still exists. No live Minecraft object
 * ever sits in a register except an immutable `BlockState`.
 */

/** A `BlockPos` record: three ints, stored in a document as `"x,y,z"`. */
object BlockPosValue {
    const val TYPE = "BlockPos"
    val FIELDS = listOf("x", "y", "z")

    fun of(x: Int, y: Int, z: Int): StructValue = StructValue(TYPE, FIELDS, arrayOf<Any?>(x, y, z))

    fun of(pos: BlockPos): StructValue = of(pos.x, pos.y, pos.z)

    /** The record, from any of the forms a position arrives in; null for nothing or nonsense. */
    fun record(value: Any?): StructValue? = when (value) {
        null -> null
        is StructValue -> value.takeIf { it.type == TYPE }
        is BlockPos -> of(value)
        is List<*> -> if (value.size >= 3) of(int(value[0]), int(value[1]), int(value[2])) else null
        else -> parts(value.toString())?.let { of(it[0], it[1], it[2]) }
    }

    fun toBlockPos(value: Any?): BlockPos? = record(value)?.let { BlockPos(int(it.get("x")), int(it.get("y")), int(it.get("z"))) }

    /** The document spelling of a record — `"x,y,z"` — or null for something that is not one. */
    fun written(value: Any?): String? = record(value)?.let { "${int(it.get("x"))},${int(it.get("y"))},${int(it.get("z"))}" }

    private fun parts(text: String): IntArray? {
        val p = text.split(',').map { it.trim().toIntOrNull() ?: return null }
        return if (p.size == 3) p.toIntArray() else null
    }

    internal fun int(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: 0
        else -> 0
    }
}

/** Registry ids as the language sees them: `"minecraft:coal"`. */
object RegistryIds {
    fun item(id: String): Item? = ResourceLocation.tryParse(id.trim())?.let { bpm.platform.valueOf(BuiltInRegistries.ITEM, it) }

    fun block(id: String): Block? = ResourceLocation.tryParse(id.trim())?.let { bpm.platform.valueOf(BuiltInRegistries.BLOCK, it) }

    fun fluid(id: String): Fluid? = ResourceLocation.tryParse(id.trim())?.let { bpm.platform.valueOf(BuiltInRegistries.FLUID, it) }

    fun of(item: Item): String = BuiltInRegistries.ITEM.getKey(item).toString()
    fun of(block: Block): String = BuiltInRegistries.BLOCK.getKey(block).toString()
    fun of(fluid: Fluid): String = BuiltInRegistries.FLUID.getKey(fluid).toString()

    /** An item tag, spelled without the `#`. */
    fun itemTag(id: String): TagKey<Item>? = ResourceLocation.tryParse(id.trim().removePrefix("#"))?.let { TagKey.create(Registries.ITEM, it) }

    /** Whatever the host handed over, as an item id: an `Item`, a stack, a holder, or already a string. */
    fun itemId(value: Any?): String = when (value) {
        null -> ""
        is Item -> of(value)
        is ItemStack -> if (value.isEmpty) "" else of(value.item)
        is net.minecraft.core.Holder<*> -> (value.value() as? Item)?.let(::of) ?: value.toString()
        else -> value.toString()
    }
}

/** An `ItemStack` record: `item`, `count`, `components` (the data component patch as JSON text, or null). */
object ItemStackValue {
    const val TYPE = "ItemStack"
    val FIELDS = listOf("item", "count", "components")

    fun of(item: String, count: Int, components: String?): StructValue =
        StructValue(TYPE, FIELDS, arrayOf<Any?>(item, count, components))

    /** A snapshot of a live stack. An empty stack is null, which is what "nothing there" should be. */
    fun record(value: Any?): StructValue? = when (value) {
        null -> null
        is StructValue -> value.takeIf { it.type == TYPE }
        is ItemStack -> if (value.isEmpty) null else of(RegistryIds.of(value.item), value.count, componentsText(value))
        else -> null
    }

    /** A live stack rebuilt from a record — for the verbs that insert. Empty for a record naming no item. */
    fun stack(value: Any?): ItemStack {
        val r = record(value) ?: return ItemStack.EMPTY
        val item = RegistryIds.item(r.get("item")?.toString().orEmpty()) ?: return ItemStack.EMPTY
        val count = BlockPosValue.int(r.get("count")).coerceAtLeast(1)
        val patch = r.get("components")?.toString()?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { DataComponentPatch.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text)).result().orElse(null) }.getOrNull()
        } ?: DataComponentPatch.EMPTY
        return ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(item), count, patch)
    }

    private fun componentsText(stack: ItemStack): String? {
        val patch = stack.componentsPatch
        if (patch.isEmpty) return null
        return runCatching { DataComponentPatch.CODEC.encodeStart(JsonOps.INSTANCE, patch).result().orElse(null)?.toString() }.getOrNull()
    }
}

/**
 * A `FluidStack` record: `fluid` and `amount` in millibuckets.
 *
 * The name and the unit are both public surface. `FluidStack` is what a graph declares and what is written
 * into saved documents, and `amount` has always meant millibuckets — a literal `1000` in someone's graph
 * means a bucket. Neither may change because the type behind it did: internally fluid is counted in
 * droplets ([FluidVolume]), and the rounding to millibuckets happens here and nowhere else.
 */
object FluidStackValue {
    const val TYPE = "FluidStack"
    val FIELDS = listOf("fluid", "amount")

    fun of(fluid: String, amount: Int): StructValue = StructValue(TYPE, FIELDS, arrayOf<Any?>(fluid, amount))

    fun record(value: Any?): StructValue? = when (value) {
        null -> null
        is StructValue -> value.takeIf { it.type == TYPE }
        is FluidVolume -> if (value.isEmpty) null else of(RegistryIds.of(value.fluid), value.mb)
        else -> null
    }

    fun volume(value: Any?): FluidVolume {
        val r = record(value) ?: return FluidVolume.EMPTY
        val fluid = RegistryIds.fluid(r.get("fluid")?.toString().orEmpty()) ?: return FluidVolume.EMPTY
        return FluidVolume.ofMb(fluid, BlockPosValue.int(r.get("amount")).coerceAtLeast(1))
    }
}

/**
 * A reference to one live thing in the world — by identity, never by object.
 *
 * An entity can die, unload or change dimension between one tick and the next, so a script holds this and
 * asks the host to resolve it each time; `exists` is the field to branch on. `equals` is by uuid, which is
 * what `==` on two handles should mean.
 */
data class EntityHandle(val uuid: UUID, val dimension: ResourceKey<Level>) {
    override fun toString(): String = "Entity($uuid)"

    companion object {
        fun of(entity: Entity): EntityHandle = EntityHandle(entity.uuid, entity.level().dimension())
    }
}
