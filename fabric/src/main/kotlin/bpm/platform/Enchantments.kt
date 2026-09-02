package bpm.platform

import net.minecraft.world.item.ItemStack

/**
 * The enchantments on a stack, as ids and levels.
 *
 * From 1.20.5 these are an `ItemEnchantments` component holding registry HOLDERS; below it they are an
 * NBT list read through `EnchantmentHelper`, keyed by the enchantment itself. Neither shape is worth
 * exposing to the nodes, which only ever want to name each enchantment and say how strong it is -- so
 * the seam answers that directly and both bands build the same list.
 *
 * The id is the registry key where there is one, and the object's own `toString` otherwise, which is
 * what an unregistered enchantment from another mod would fall back to on either band.
 */
//? if >=1.20.5 {
fun enchantmentsOf(stack: ItemStack): List<Pair<String, Int>> {
    val enchantments = stack.enchantments
    return enchantments.keySet().map { holder ->
        val id = holder.unwrapKey().map { it.keyId().toString() }.orElse(holder.toString())
        id to enchantments.getLevel(holder)
    }
}
//?} else {
/*fun enchantmentsOf(stack: ItemStack): List<Pair<String, Int>> =
    net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(stack).map { (enchantment, level) ->
        val key = net.minecraft.core.registries.BuiltInRegistries.ENCHANTMENT.getKey(enchantment)
        (key?.toString() ?: enchantment.toString()) to level
    }
*///?}
