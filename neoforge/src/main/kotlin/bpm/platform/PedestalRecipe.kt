package bpm.platform

import net.minecraft.core.HolderLookup
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
//? if >=1.20.5 {
import net.minecraft.world.item.crafting.RecipeInput
//?}

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
//? if >=1.20.5 {
abstract class PedestalRecipe<T : RecipeInput> : Recipe<T> {
//?} else {
/*abstract class PedestalRecipe<T : net.minecraft.world.Container> : Recipe<T> {
    /**
     * The recipe's own id, which 1.20.5 took away.
     *
     * Below that a `Recipe` carries its id and `getId()` is part of the interface; the serializer is
     * handed the id when it reads one and sets it here. A `var` rather than a constructor parameter
     * because the shared subclasses are built by codecs that know nothing about ids.
     */
    var recipeId: bpm.platform.ResourceLocation = bpm.platform.idOf("bpm", "unset")

    override fun getId(): bpm.platform.ResourceLocation = recipeId
*///?}

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
    *///?} elif >=1.20.5 {
    final override fun assemble(input: T, registries: HolderLookup.Provider): ItemStack = assembleResult(input)
    //?} else {
    /*// `RegistryAccess` rather than a `HolderLookup.Provider`, and the same story: nothing reads it.
    final override fun assemble(input: T, registries: net.minecraft.core.RegistryAccess): ItemStack =
        assembleResult(input)
    *///?}

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

    //? if >=1.20.5 {
    override fun getResultItem(registries: HolderLookup.Provider): ItemStack = output
    //?} else {
    /*override fun getResultItem(registries: net.minecraft.core.RegistryAccess): ItemStack = output
    *///?}

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
    streamCodec: bpm.platform.net.PayloadCodec<R>,
): net.minecraft.world.item.crafting.RecipeSerializer<R> =
    //? if >=26.1 {
    /*@Suppress("UNCHECKED_CAST")
    net.minecraft.world.item.crafting.RecipeSerializer(
        codec,
        streamCodec as net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, R>,
    )
    *///?} elif >=1.20.5 {
    object : net.minecraft.world.item.crafting.RecipeSerializer<R> {
        override fun codec(): com.mojang.serialization.MapCodec<R> = codec
        @Suppress("UNCHECKED_CAST")
        override fun streamCodec(): net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, R> =
            streamCodec as net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, R>
    }
    //?} else {
    /*/*
     * Below 1.20.5 a serializer is three methods and no codecs -- but the MapCodec it is given still
     * works, because a `MapCodec` is DataFixerUpper's and predates all of this. So JSON goes through it
     * with `JsonOps` and the network form through `NbtOps` plus `writeNbt`, rather than hand-writing a
     * parser and a byte layout that would only have to agree with themselves.
     *
     * The id is handed in by vanilla on both reads and is stamped onto the recipe, which is where
     * `getId()` gets its answer.
     */
    object : net.minecraft.world.item.crafting.RecipeSerializer<R> {
        override fun fromJson(id: bpm.platform.ResourceLocation, json: com.google.gson.JsonObject): R {
            val recipe = codec.codec().parse(com.mojang.serialization.JsonOps.INSTANCE, json)
                .getOrThrow(false) { error -> throw IllegalStateException("bad recipe $id: $error") }
            stampId(recipe, id)
            return recipe
        }

        override fun fromNetwork(
            id: bpm.platform.ResourceLocation,
            buf: net.minecraft.network.FriendlyByteBuf,
        ): R? {
            val tag = buf.readNbt() ?: return null
            val recipe = codec.codec().parse(net.minecraft.nbt.NbtOps.INSTANCE, tag)
                .getOrThrow(false) { error -> throw IllegalStateException("bad recipe $id: $error") }
            stampId(recipe, id)
            return recipe
        }

        override fun toNetwork(buf: net.minecraft.network.FriendlyByteBuf, recipe: R) {
            val encoded = codec.codec().encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, recipe)
                .getOrThrow(false) { error -> throw IllegalStateException("cannot send recipe: $error") }
            buf.writeNbt(encoded as net.minecraft.nbt.CompoundTag)
        }

        private fun stampId(recipe: R, id: bpm.platform.ResourceLocation) {
            (recipe as? PedestalRecipe<*>)?.recipeId = id
        }
    }
    *///?}


/*
 * The pieces a recipe's wire form is built from.
 *
 * Same approach the payloads take: write the bytes by hand over `FriendlyByteBuf` so the layout is
 * band-neutral, and put only the two pieces that are NOT -- an ingredient and a stack -- behind a seam.
 * `ByteBufCodecs` and `Ingredient.CONTENTS_STREAM_CODEC` are 1.20.5 types; `toNetwork`/`fromNetwork` are
 * what came before, and both write the same thing.
 */
//? if >=1.20.5 {
internal fun writeIngredient(buf: net.minecraft.network.FriendlyByteBuf, ingredient: net.minecraft.world.item.crafting.Ingredient) {
    @Suppress("UNCHECKED_CAST")
    net.minecraft.world.item.crafting.Ingredient.CONTENTS_STREAM_CODEC.encode(
        buf as net.minecraft.network.RegistryFriendlyByteBuf, ingredient,
    )
}

internal fun readIngredient(buf: net.minecraft.network.FriendlyByteBuf): net.minecraft.world.item.crafting.Ingredient =
    net.minecraft.world.item.crafting.Ingredient.CONTENTS_STREAM_CODEC.decode(
        buf as net.minecraft.network.RegistryFriendlyByteBuf,
    )

internal fun writeStack(buf: net.minecraft.network.FriendlyByteBuf, stack: net.minecraft.world.item.ItemStack) {
    net.minecraft.world.item.ItemStack.STREAM_CODEC.encode(
        buf as net.minecraft.network.RegistryFriendlyByteBuf, stack,
    )
}

internal fun readStack(buf: net.minecraft.network.FriendlyByteBuf): net.minecraft.world.item.ItemStack =
    net.minecraft.world.item.ItemStack.STREAM_CODEC.decode(
        buf as net.minecraft.network.RegistryFriendlyByteBuf,
    )
//?} else {
/*internal fun writeIngredient(buf: net.minecraft.network.FriendlyByteBuf, ingredient: net.minecraft.world.item.crafting.Ingredient) {
    ingredient.toNetwork(buf)
}

internal fun readIngredient(buf: net.minecraft.network.FriendlyByteBuf): net.minecraft.world.item.crafting.Ingredient =
    net.minecraft.world.item.crafting.Ingredient.fromNetwork(buf)

internal fun writeStack(buf: net.minecraft.network.FriendlyByteBuf, stack: net.minecraft.world.item.ItemStack) {
    buf.writeItem(stack)
}

internal fun readStack(buf: net.minecraft.network.FriendlyByteBuf): net.minecraft.world.item.ItemStack =
    buf.readItem()
*///?}

/**
 * A recipe's wire form, from a write and a read.
 *
 * The same two functions [bpm.platform.net.payloadCodec] takes, and the same underlying type -- but its
 * own builder, because a payload's is bounded on `BpmPayload` and a recipe is not one. Relaxing that
 * bound instead would put two functions with the same erasure in one file.
 */
fun <R : Any> recipeWire(
    write: (net.minecraft.network.FriendlyByteBuf, R) -> Unit,
    read: (net.minecraft.network.FriendlyByteBuf) -> R,
): bpm.platform.net.PayloadCodec<R> =
    //? if >=1.20.5 {
    net.minecraft.network.codec.StreamCodec.of({ buf, v -> write(buf, v) }, { buf -> read(buf) })
    //?} else {
    /*bpm.platform.net.PayloadCodec(write, read)
    *///?}
