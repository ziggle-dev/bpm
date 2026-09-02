package bpm.platform

import net.minecraft.world.item.ItemStack

/**
 * One of the GAME's own components on a stack, as JSON.
 *
 * Distinct from [bpm.platform.registry.ComponentKey], which is about this mod's components: these are
 * vanilla's -- `minecraft:custom_data`, `minecraft:enchantments` and the rest -- looked up by id and
 * encoded through whatever codec they carry.
 *
 * Below 1.20.5 there are none. Not "they are spelled differently": the concept does not exist, and the
 * same information lives in the stack's tag under names the game never standardised. So the seam answers
 * NOTHING there, and the `component` node reports no value rather than inventing a mapping that would be
 * wrong for every component a pack actually asks about.
 */
//? if >=1.20.5 {
@Suppress("UNCHECKED_CAST")
fun vanillaComponentJson(
    registries: net.minecraft.core.HolderLookup.Provider,
    stack: ItemStack,
    type: Any?,
): String? {
    // Any? on both arms so the shared caller needs no version-specific type in its own signature.
    type as? net.minecraft.core.component.DataComponentType<*> ?: return null
    val value = stack.get(type) ?: return null
    val codec = (type as net.minecraft.core.component.DataComponentType<Any>).codecOrThrow()
    val ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, registries)
    return codec.encodeStart(ops, value).result().orElse(null)?.toString()
}
//?} else {
/*@Suppress("UNUSED_PARAMETER")
fun vanillaComponentJson(
    registries: net.minecraft.core.HolderLookup.Provider,
    stack: ItemStack,
    type: Any?,
): String? = null
*///?}

//? if >=1.20.5 {
/** The level of one enchantment on [stack], or 0 when it has none. */
@Suppress("UNCHECKED_CAST")
fun enchantmentLevel(stack: ItemStack, enchantment: Any?): Int {
    val holder = enchantment as? net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> ?: return 0
    return stack.enchantments.getLevel(holder)
}

/** The ids of every one of the GAME's components on [stack]. */
fun vanillaComponentIds(stack: ItemStack): List<String> =
    stack.components.keySet().map {
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(it).toString()
    }
//?} else {
/*fun enchantmentLevel(stack: ItemStack, enchantment: Any?): Int {
    val e = enchantment as? net.minecraft.world.item.enchantment.Enchantment ?: return 0
    return net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(e, stack)
}

/**
 * Empty, because a stack carries no components on this band.
 *
 * Answering the tag's keys instead would be a different question wearing the same name: those are one
 * mod's private field names, not the game's component ids, and a pack that matched on them would break
 * the moment it moved to a version that has both.
 */
@Suppress("UNUSED_PARAMETER")
fun vanillaComponentIds(stack: ItemStack): List<String> = emptyList()
*///?}

//? if >=1.20.5 {
/** Look up one of the GAME's component types by id, or null when there is no such thing. */
fun vanillaComponentType(id: String): Any? {
    val key = net.minecraft.resources.ResourceLocation.tryParse(id.trim()) ?: return null
    return net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.get(key)
}

/** Whether [stack] carries the component [type] answered by [vanillaComponentType]. */
@Suppress("UNCHECKED_CAST")
fun hasVanillaComponent(stack: ItemStack, type: Any?): Boolean {
    val component = type as? net.minecraft.core.component.DataComponentType<*> ?: return false
    return stack.has(component)
}
//?} else {
/*/**
 * Nothing to look up: the game has no components on this band.
 *
 * A filter naming one therefore matches nothing rather than everything, which is the safe direction --
 * a pack that filters on `minecraft:custom_data` should pass no items here, not every item.
 */
@Suppress("UNUSED_PARAMETER")
fun vanillaComponentType(id: String): Any? = null

@Suppress("UNUSED_PARAMETER")
fun hasVanillaComponent(stack: ItemStack, type: Any?): Boolean = false
*///?}

//? if >=1.20.5 {
/** Parse a vanilla item predicate from JSON text, or null when the text is not one. */
fun parseItemPredicate(json: String, registries: net.minecraft.core.RegistryAccess?): bpm.platform.ItemPredicate? {
    val element = runCatching { com.google.gson.JsonParser.parseString(json) }.getOrNull() ?: return null
    val ops = if (registries != null) {
        net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, registries)
    } else {
        com.mojang.serialization.JsonOps.INSTANCE
    }
    return runCatching {
        bpm.platform.ItemPredicate.CODEC.parse(ops, element).result().orElse(null)
    }.getOrNull()
}

/** Whether [stack] satisfies [predicate]. */
fun testItemPredicate(predicate: bpm.platform.ItemPredicate, stack: ItemStack): Boolean = predicate.test(stack)
//?} else {
/*/**
 * Below 1.20.5 the predicate is built by a hand-written `fromJson` rather than a codec, and it has no
 * registry ops to be given -- so `registries` is accepted and ignored, which keeps the shared caller
 * from having to know that.
 */
@Suppress("UNUSED_PARAMETER")
fun parseItemPredicate(json: String, registries: net.minecraft.core.RegistryAccess?): bpm.platform.ItemPredicate? {
    val element = runCatching { com.google.gson.JsonParser.parseString(json) }.getOrNull() ?: return null
    return runCatching { bpm.platform.ItemPredicate.fromJson(element) }.getOrNull()
}

/** `matches` is what `test` was called here. */
fun testItemPredicate(predicate: bpm.platform.ItemPredicate, stack: ItemStack): Boolean = predicate.matches(stack)
*///?}
