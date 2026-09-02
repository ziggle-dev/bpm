package bpm.platform

import net.minecraft.world.item.ItemStack

/**
 * The extra data carried alongside a fluid or an item, under whichever form this band has.
 *
 * From 1.20.5 that is a `DataComponentPatch`: a set of component overrides, with a codec. Below it there
 * are no components at all and the same job is done by an NBT tag.
 *
 * The two are not the same shape, and this seam does not pretend otherwise -- it exposes only what the
 * mod actually does with one: hold it, compare it, and round-trip it through text so a catalogue value
 * can name a fluid with data attached.
 *
 * The TEXT FORM therefore differs by band, and deliberately: JSON of a component patch above 1.20.5, and
 * SNBT below it. Both are the natural spelling of the thing on their own version, and a catalogue value
 * written on one band was never going to be portable to the other anyway -- the components it names do
 * not exist there.
 */

//? if >=1.20.5 {
typealias ComponentPatch = net.minecraft.core.component.DataComponentPatch

/** Nothing attached. */
fun emptyPatch(): ComponentPatch = net.minecraft.core.component.DataComponentPatch.EMPTY

/** Read a patch from its text form, or null when the text is not one. */
fun patchFromText(text: String): ComponentPatch? = runCatching {
    net.minecraft.core.component.DataComponentPatch.CODEC
        .parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(text))
        .result().orElse(null)
}.getOrNull()

/** Write a patch to its text form, or null when it cannot be written. */
fun patchToText(patch: ComponentPatch): String? = runCatching {
    net.minecraft.core.component.DataComponentPatch.CODEC
        .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, patch)
        .result().orElse(null)?.toString()
}.getOrNull()
//?} else {
/*typealias ComponentPatch = net.minecraft.nbt.CompoundTag

fun emptyPatch(): ComponentPatch = net.minecraft.nbt.CompoundTag()

/** SNBT on this band -- see the note above on why the text form differs. */
fun patchFromText(text: String): ComponentPatch? =
    runCatching { net.minecraft.nbt.TagParser.parseTag(text) }.getOrNull()

fun patchToText(patch: ComponentPatch): String? = runCatching { patch.toString() }.getOrNull()
*///?}

//? if >=1.20.5 {
/** A stack of [item] with [patch] attached. */
fun stackWith(item: net.minecraft.world.item.Item, count: Int, patch: ComponentPatch): net.minecraft.world.item.ItemStack =
    net.minecraft.world.item.ItemStack(
        net.minecraft.core.registries.BuiltInRegistries.ITEM.wrapAsHolder(item), count, patch,
    )

/** What is attached to [stack], if anything. */
fun patchOf(stack: net.minecraft.world.item.ItemStack): ComponentPatch = stack.componentsPatch

/** Whether [patch] carries nothing. */
fun patchIsEmpty(patch: ComponentPatch): Boolean = patch.isEmpty
//?} else {
/*fun stackWith(item: net.minecraft.world.item.Item, count: Int, patch: ComponentPatch): net.minecraft.world.item.ItemStack {
    val stack = net.minecraft.world.item.ItemStack(item, count)
    if (!patch.isEmpty) stack.tag = patch.copy()
    return stack
}

fun patchOf(stack: net.minecraft.world.item.ItemStack): ComponentPatch = stack.tag ?: emptyPatch()

fun patchIsEmpty(patch: ComponentPatch): Boolean = patch.isEmpty
*///?}

/**
 * Two stacks holding the same item with the same data attached.
 *
 * The data moved from an NBT tag to a component map at 1.20.5 and the comparison was renamed with it,
 * but it answers the same question: would these two merge into one stack.
 */
//? if >=1.20.5 {
fun sameItemAndData(a: ItemStack, b: ItemStack): Boolean = ItemStack.isSameItemSameComponents(a, b)
//?} else {
/*fun sameItemAndData(a: ItemStack, b: ItemStack): Boolean = ItemStack.isSameItemSameTags(a, b)
*///?}

/** How many of [item] fit in one stack. The default moved into the components at 1.20.5. */
//? if >=1.20.5 {
fun maxStackSize(item: net.minecraft.world.item.Item): Int = item.defaultMaxStackSize
//?} else {
/*fun maxStackSize(item: net.minecraft.world.item.Item): Int = item.maxStackSize
*///?}
