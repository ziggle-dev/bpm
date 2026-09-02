package bpm.platform

/*
 * A recipe's result, held as whatever the band can hold BEFORE item components are bound.
 *
 * From 26.1 an item's default components are data: built from a loaded registry access and bound onto
 * the item afterwards. Recipes parse before that, so constructing an `ItemStack` while reading one
 * throws "Components not bound yet" -- and reading the datapack is precisely when a recipe is read.
 *
 * The first attempt at this decoded an `ItemStackTemplate` and immediately called `create()`, which is
 * the same mistake wearing a different hat: it moved the stack construction inside the codec instead of
 * out of it. A template has to be KEPT as a template and turned into a stack later, which is what this
 * exists to do -- the recipe holds one of these, and the stack is made on first use, by which time the
 * components are bound.
 *
 * Below 26.1 there is nothing to defer and this is a wrapper around the stack itself.
 */

//? if >=26.1 {
/*class RecipeResult(val template: net.minecraft.world.item.ItemStackTemplate) {

    /** The stack this describes. Safe once the registries have loaded; not before. */
    fun create(): net.minecraft.world.item.ItemStack = template.create()

    companion object {
        fun of(stack: net.minecraft.world.item.ItemStack) =
            RecipeResult(net.minecraft.world.item.ItemStackTemplate.fromStack(stack))
    }
}

val RECIPE_RESULT_CODEC: com.mojang.serialization.Codec<RecipeResult> =
    net.minecraft.world.item.ItemStackTemplate.CODEC.xmap({ RecipeResult(it) }, { it.template })
*///?} else {
class RecipeResult(private val stack: net.minecraft.world.item.ItemStack) {

    fun create(): net.minecraft.world.item.ItemStack = stack

    companion object {
        fun of(stack: net.minecraft.world.item.ItemStack) = RecipeResult(stack)
    }
}

val RECIPE_RESULT_CODEC: com.mojang.serialization.Codec<RecipeResult> =
    net.minecraft.world.item.ItemStack.CODEC.xmap({ RecipeResult(it) }, { it.create() })
//?}
