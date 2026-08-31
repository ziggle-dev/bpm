package bpm.world.assembly

import bpm.Bpm
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeInput
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.Level
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * What the pedestals around an assembler are holding, as a recipe sees it.
 *
 * The catalyst is the last index rather than a field of its own because [RecipeInput] is a flat list, and
 * every vanilla helper that walks an input expects to find everything by index. [ingredients] is only what
 * is on the pedestals; the catalyst sits in the assembler itself, and one is spent per completed job.
 */
class AssemblyInput(val ingredients: List<ItemStack>, val catalyst: ItemStack) : RecipeInput {

    override fun getItem(index: Int): ItemStack =
        if (index < ingredients.size) ingredients[index] else catalyst

    override fun size(): Int = ingredients.size + 1
}

/**
 * A fabrication: some things on pedestals, a catalyst in the machine, and a stretch of time that has to be
 * paid for in power and liquid experience the whole way through.
 *
 * The shape is deliberately not a grid. A grid recipe asks "do you own these items"; this asks "can you keep
 * a process supplied for [ticks] ticks", which is the question the rest of the mod exists to answer — see
 * `docs/DESIGN_TIERS_AND_FABRICATION.md` §4.
 *
 * [energy] and [experience] are TOTALS for the whole job, not per-tick rates: a recipe author thinks in what
 * a thing costs, and the machine divides. [requires] carries the capstone's `paired` flag and anything like
 * it later; an unknown tag makes the recipe simply never start, which is the safe way for a datapack from a
 * newer version to fail.
 */
class AssemblyRecipe(
    /** Named for the pedestals, not `ingredients`: that name is taken by [getIngredients] on the JVM. */
    val parts: NonNullList<Ingredient>,
    val catalyst: Ingredient,
    val energy: Int,
    val experience: Int,
    val ticks: Int,
    val result: ItemStack,
    val requires: List<String>,
) : Recipe<AssemblyInput> {

    /** Rounded UP, so a job is always fully paid for by the time it ends rather than a tick short. */
    val energyPerTick: Int get() = ceilDiv(energy, ticks)
    val experiencePerTick: Int get() = ceilDiv(experience, ticks)

    /** Whether this recipe needs two assemblers in different dimensions running it at once. */
    val paired: Boolean get() = PAIRED in requires

    /** True only for flags this build understands — see [requires]. */
    val understood: Boolean get() = requires.all { it in KNOWN }

    override fun matches(input: AssemblyInput, level: Level): Boolean {
        if (!understood) return false
        if (!catalyst.test(input.catalyst)) return false
        return pairUp(input.ingredients) != null
    }

    /**
     * Which pedestal satisfies which ingredient, or null when they cannot all be satisfied at once.
     *
     * Greedy would be wrong: with ingredients `[#ingots, copper_ingot]` and pedestals holding
     * `[copper_ingot, iron_ingot]`, taking the first match for `#ingots` eats the copper and the specific
     * ingredient then fails against iron, though a valid pairing existed. So this is a small matching
     * search — at most eight pedestals, so the worst case is nothing.
     *
     * Every occupied pedestal must be used: leftovers mean the player has laid out something else and we
     * should not quietly consume it.
     */
    fun pairUp(stacks: List<ItemStack>): List<Int>? {
        val held = stacks.withIndex().filter { !it.value.isEmpty }
        if (held.size != parts.size) return null
        val taken = BooleanArray(held.size)
        val chosen = IntArray(parts.size)
        return if (assign(0, held, taken, chosen)) chosen.toList() else null
    }

    private fun assign(at: Int, held: List<IndexedValue<ItemStack>>, taken: BooleanArray, chosen: IntArray): Boolean {
        if (at == parts.size) return true
        for (i in held.indices) {
            if (taken[i] || !parts[at].test(held[i].value)) continue
            taken[i] = true
            chosen[at] = held[i].index
            if (assign(at + 1, held, taken, chosen)) return true
            taken[i] = false
        }
        return false
    }

    override fun assemble(input: AssemblyInput, registries: HolderLookup.Provider): ItemStack = result.copy()

    /** Not a grid recipe; the pedestals are the shape and there is no width to speak of. */
    override fun canCraftInDimensions(width: Int, height: Int): Boolean = true

    override fun getResultItem(registries: HolderLookup.Provider): ItemStack = result

    override fun getIngredients(): NonNullList<Ingredient> = parts

    override fun getSerializer(): RecipeSerializer<*> = ModRecipes.ASSEMBLY_SERIALIZER.get()

    override fun getType(): RecipeType<*> = ModRecipes.ASSEMBLY.get()

    /** A recipe whose result the player cannot see coming is no fun; the screen and JEI both read this. */
    override fun isSpecial(): Boolean = false

    class Serializer : RecipeSerializer<AssemblyRecipe> {

        private val codec: MapCodec<AssemblyRecipe> = RecordCodecBuilder.mapCodec { it ->
            it.group(
                Ingredient.CODEC_NONEMPTY.listOf().fieldOf("ingredients").forGetter { r -> r.parts },
                Ingredient.CODEC_NONEMPTY.fieldOf("catalyst").forGetter { r -> r.catalyst },
                Codec.INT.optionalFieldOf("energy", 0).forGetter { r -> r.energy },
                Codec.INT.optionalFieldOf("experience", 0).forGetter { r -> r.experience },
                Codec.INT.optionalFieldOf("ticks", 200).forGetter { r -> r.ticks },
                ItemStack.CODEC.fieldOf("result").forGetter { r -> r.result },
                Codec.STRING.listOf().optionalFieldOf("requires", listOf()).forGetter { r -> r.requires },
            ).apply(it) { ingredients, catalyst, energy, experience, ticks, result, requires ->
                AssemblyRecipe(list(ingredients), catalyst, energy, experience, ticks.coerceAtLeast(1), result, requires)
            }
        }

        private val streamCodec: StreamCodec<RegistryFriendlyByteBuf, AssemblyRecipe> =
            StreamCodec.of({ buf, r ->
                ByteBufCodecs.collection<RegistryFriendlyByteBuf, Ingredient, MutableList<Ingredient>>({ ArrayList(it) }, Ingredient.CONTENTS_STREAM_CODEC)
                    .encode(buf, r.parts)
                Ingredient.CONTENTS_STREAM_CODEC.encode(buf, r.catalyst)
                buf.writeVarInt(r.energy)
                buf.writeVarInt(r.experience)
                buf.writeVarInt(r.ticks)
                ItemStack.STREAM_CODEC.encode(buf, r.result)
                ByteBufCodecs.collection<RegistryFriendlyByteBuf, String, MutableList<String>>({ ArrayList(it) }, ByteBufCodecs.stringUtf8(64))
                    .encode(buf, ArrayList(r.requires))
            }, { buf ->
                val ingredients = ByteBufCodecs.collection<RegistryFriendlyByteBuf, Ingredient, MutableList<Ingredient>>({ ArrayList(it) }, Ingredient.CONTENTS_STREAM_CODEC)
                    .decode(buf)
                val catalyst = Ingredient.CONTENTS_STREAM_CODEC.decode(buf)
                val energy = buf.readVarInt()
                val experience = buf.readVarInt()
                val ticks = buf.readVarInt()
                val result = ItemStack.STREAM_CODEC.decode(buf)
                val requires = ByteBufCodecs.collection<RegistryFriendlyByteBuf, String, MutableList<String>>({ ArrayList(it) }, ByteBufCodecs.stringUtf8(64))
                    .decode(buf)
                AssemblyRecipe(list(ingredients), catalyst, energy, experience, ticks, result, requires)
            })

        override fun codec(): MapCodec<AssemblyRecipe> = codec
        override fun streamCodec(): StreamCodec<RegistryFriendlyByteBuf, AssemblyRecipe> = streamCodec
    }

    companion object {
        /** The capstone's flag: two assemblers, two dimensions, one job — §4.5. */
        const val PAIRED = "paired"

        private val KNOWN = setOf(PAIRED)

        /** At most this many pedestals feed one assembler, so at most this many ingredients. */
        const val MAX_INGREDIENTS = 8

        private fun list(from: List<Ingredient>): NonNullList<Ingredient> =
            NonNullList.createWithCapacity<Ingredient>(from.size).also { it.addAll(from) }

        private fun ceilDiv(total: Int, by: Int): Int = if (by <= 0) total else (total + by - 1) / by
    }
}

/** The mod's recipe type and serializer — bpm's first, so this is also where any later one goes. */
object ModRecipes {
    val TYPES: DeferredRegister<RecipeType<*>> = DeferredRegister.create(Registries.RECIPE_TYPE, Bpm.ID)
    val SERIALIZERS: DeferredRegister<RecipeSerializer<*>> = DeferredRegister.create(Registries.RECIPE_SERIALIZER, Bpm.ID)

    val ASSEMBLY = TYPES.register("assembly") { -> object : RecipeType<AssemblyRecipe> {
        override fun toString(): String = "bpm:assembly"
    } }

    val ASSEMBLY_SERIALIZER = SERIALIZERS.register("assembly") { -> AssemblyRecipe.Serializer() }
}
