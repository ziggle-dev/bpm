package bpm.platform

import net.minecraft.core.HolderLookup
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeInput

/**
 * The parts of `Recipe` that stopped being the same question.
 *
 * 1.21.2-1.21.4 rewrote the interface around the recipe BOOK rather than around crafting. Three methods
 * that described a grid recipe were deleted -- `canCraftInDimensions`, `getResultItem`, `getIngredients`
 * -- and two that describe a recipe-book entry became mandatory: `placementInfo`, which is what the book
 * uses to lay ghost items into a grid, and `recipeBookCategory`, which is the tab it files under.
 *
 * None of the five is a question this mod's recipe wanted to answer. It is not a grid recipe; its shape
 * is a ring of pedestals and its answer to "what are your ingredients" is the same list either way. So
 * the subclass declares [inputs] and [output] and this says whichever of the five the band demands, in
 * the terms the band demands them.
 *
 * The placement is built once and kept: vanilla asks for it whenever the book redraws, and every recipe
 * of ours is immutable, so rebuilding one per call would be pure waste.
 */
abstract class PedestalRecipe<T : RecipeInput> : Recipe<T> {

    /** What has to be on the pedestals, in no particular order. */
    abstract val inputs: List<Ingredient>

    /** What comes out. Not copied -- callers that keep it must copy it themselves. */
    abstract val output: ItemStack

    /**
     * What this recipe produces for [input].
     *
     * `Recipe.assemble` lost its `HolderLookup.Provider` at 26.1 -- nothing in this mod's recipes ever
     * read it -- and a subclass cannot declare two arities, so the vanilla override lives here and
     * forwards to this.
     */
    abstract fun assembleResult(input: T): ItemStack

    //? if >=26.1 {
    /*final override fun assemble(input: T): ItemStack = assembleResult(input)

    /*
     * `showNotification` and `group` stopped being interface defaults at 26.1 and have to be answered.
     * These are the values vanilla defaulted them to, so nothing about these recipes changes: the
     * toast still appears, and they belong to no recipe group.
     */
    override fun showNotification(): Boolean = true

    override fun group(): String = ""
    *///?} else {
    final override fun assemble(input: T, registries: HolderLookup.Provider): ItemStack = assembleResult(input)
    //?}

    //? if >=1.21.2 {
    /*private val placement: net.minecraft.world.item.crafting.PlacementInfo by lazy {
        net.minecraft.world.item.crafting.PlacementInfo.create(inputs)
    }

    override fun placementInfo(): net.minecraft.world.item.crafting.PlacementInfo = placement

    /**
     * The recipe book never shows this -- an assembler is not a crafting table and its recipes are read
     * from the machine's own screen and from JEI. `CRAFTING_MISC` is the least wrong of the fixed set;
     * the field exists because the interface demands one, not because anything looks at it.
     */
    override fun recipeBookCategory(): net.minecraft.world.item.crafting.RecipeBookCategory =
        net.minecraft.world.item.crafting.RecipeBookCategories.CRAFTING_MISC
    *///?} else {
    /** Not a grid recipe; the pedestals are the shape and there is no width to speak of. */
    override fun canCraftInDimensions(width: Int, height: Int): Boolean = true

    override fun getResultItem(registries: HolderLookup.Provider): ItemStack = output

    override fun getIngredients(): net.minecraft.core.NonNullList<Ingredient> {
        val list = net.minecraft.core.NonNullList.withSize(inputs.size, Ingredient.EMPTY)
        for ((i, ingredient) in inputs.withIndex()) list[i] = ingredient
        return list
    }
    //?}
}

/**
 * A recipe serializer from its two codecs.
 *
 * `RecipeSerializer` was an interface to implement until 26.1 and is a final record now, so it is
 * CONSTRUCTED rather than extended -- which is what "this type is final, so it cannot be extended"
 * means. The two codecs are the whole of it either way.
 */
fun <R : net.minecraft.world.item.crafting.Recipe<*>> recipeSerializer(
    codec: com.mojang.serialization.MapCodec<R>,
    streamCodec: net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, R>,
): net.minecraft.world.item.crafting.RecipeSerializer<R> =
    //? if >=26.1 {
    /*net.minecraft.world.item.crafting.RecipeSerializer(codec, streamCodec)
    *///?} else {
    object : net.minecraft.world.item.crafting.RecipeSerializer<R> {
        override fun codec(): com.mojang.serialization.MapCodec<R> = codec
        override fun streamCodec(): net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, R> = streamCodec
    }
    //?}
