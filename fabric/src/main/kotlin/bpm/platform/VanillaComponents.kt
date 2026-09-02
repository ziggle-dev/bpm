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
fun enchantmentLevel(stack: ItemStack, holder: net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>): Int =
    stack.enchantments.getLevel(holder)

/** The ids of every one of the GAME's components on [stack]. */
fun vanillaComponentIds(stack: ItemStack): List<String> =
    stack.components.keySet().map {
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(it).toString()
    }
//?} else {
/*fun enchantmentLevel(stack: ItemStack, holder: net.minecraft.world.item.enchantment.Enchantment): Int =
    net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(holder, stack)

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
