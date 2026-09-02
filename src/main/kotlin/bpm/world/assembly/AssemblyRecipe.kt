package bpm.world.assembly

import bpm.platform.registry.BlockRegistrar
import bpm.platform.registry.ComponentRegistrar
import bpm.platform.registry.ItemRegistrar
import bpm.platform.registry.Registrar
import bpm.platform.registry.RegistryRef
import bpm.platform.registry.Registrars
import bpm.Bpm
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.HolderLookup
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.Level

/**
 * What the pedestals around an assembler are holding, as a recipe sees it.
 *
 * The catalyst is the last index rather than a field of its own because [RecipeInput] is a flat list, and
 * every vanilla helper that walks an input expects to find everything by index. [ingredients] is only what
 * is on the pedestals; the catalyst sits in the assembler itself, and one is spent per completed job.
 */
class AssemblyInput(val ingredients: List<ItemStack>, val catalyst: ItemStack) : bpm.platform.RecipeInputBase() {

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
    /**
     * What comes out, as the band can hold it before item components are bound -- see
     * [bpm.platform.RecipeResult]. The stack is made from it on first use, by which time they are.
     */
    val resultSpec: bpm.platform.RecipeResult,
    val requires: List<String>,
) : bpm.platform.PedestalRecipe<AssemblyInput>() {

    /** Made once, on first use rather than while the datapack is being read. */
    val result: ItemStack by lazy { resultSpec.create() }

    override val inputs: List<Ingredient> get() = parts

    override val output: ItemStack get() = result

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

    override fun assembleResult(input: AssemblyInput): ItemStack = result.copy()

    override fun getSerializer(): RecipeSerializer<out Recipe<AssemblyInput>> = ModRecipes.ASSEMBLY_SERIALIZER.get()

    override fun getType(): RecipeType<out Recipe<AssemblyInput>> = ModRecipes.ASSEMBLY.get()

    /** A recipe whose result the player cannot see coming is no fun; the screen and JEI both read this. */
    override fun isSpecial(): Boolean = false

    /**
     * The two codecs, and nothing else.
     *
     * An object rather than a `RecipeSerializer` subclass, because from 26.1 a serializer is a final
     * record built from exactly these two things -- see `bpm.platform.recipeSerializer`.
     */
    object Serializer {

        val codec: MapCodec<AssemblyRecipe> = RecordCodecBuilder.mapCodec { it ->
            it.group(
                bpm.platform.INGREDIENT_CODEC.listOf().fieldOf("ingredients").forGetter { r -> r.parts },
                bpm.platform.INGREDIENT_CODEC.fieldOf("catalyst").forGetter { r -> r.catalyst },
                Codec.INT.optionalFieldOf("energy", 0).forGetter { r -> r.energy },
                Codec.INT.optionalFieldOf("experience", 0).forGetter { r -> r.experience },
                Codec.INT.optionalFieldOf("ticks", 200).forGetter { r -> r.ticks },
                bpm.platform.RECIPE_RESULT_CODEC.fieldOf("result").forGetter { r -> r.resultSpec },
                Codec.STRING.listOf().optionalFieldOf("requires", listOf()).forGetter { r -> r.requires },
            ).apply(it) { ingredients, catalyst, energy, experience, ticks, result, requires ->
                AssemblyRecipe(list(ingredients), catalyst, energy, experience, ticks.coerceAtLeast(1), result, requires)
            }
        }

        /**
         * The wire form, written by hand.
         *
         * `ByteBufCodecs` arrived in 1.20.5 and its `collection` helper with it, so the lists are looped
         * here instead: a count and then the members, which is what the helper does anyway. The two
         * pieces that genuinely differ between bands -- an ingredient and a stack -- go through
         * `bpm.platform.writeIngredient`/`writeStack`, and the layout is otherwise identical on every
         * version. Same arrangement as `bpm.net.Payloads`, and for the same reason.
         *
         * The 64-character cap on a requirement name is kept: it bounded a decode before and still does.
         */
        val streamCodec: bpm.platform.net.PayloadCodec<AssemblyRecipe> =
            bpm.platform.recipeWire<AssemblyRecipe>({ buf, r ->
                buf.writeVarInt(r.parts.size)
                for (part in r.parts) bpm.platform.writeIngredient(buf, part)
                bpm.platform.writeIngredient(buf, r.catalyst)
                buf.writeVarInt(r.energy)
                buf.writeVarInt(r.experience)
                buf.writeVarInt(r.ticks)
                bpm.platform.writeStack(buf, r.result)
                buf.writeVarInt(r.requires.size)
                for (name in r.requires) buf.writeUtf(name, 64)
            }, { buf ->
                val ingredients = ArrayList<Ingredient>()
                repeat(buf.readVarInt()) { ingredients += bpm.platform.readIngredient(buf) }
                val catalyst = bpm.platform.readIngredient(buf)
                val energy = buf.readVarInt()
                val experience = buf.readVarInt()
                val ticks = buf.readVarInt()
                val result = bpm.platform.RecipeResult.of(bpm.platform.readStack(buf))
                val requires = ArrayList<String>()
                repeat(buf.readVarInt()) { requires += buf.readUtf(64) }
                AssemblyRecipe(list(ingredients), catalyst, energy, experience, ticks, result, requires)
            })

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
    val TYPES: Registrar<RecipeType<*>> = Registrars.of(Registries.RECIPE_TYPE, Bpm.ID)
    val SERIALIZERS: Registrar<RecipeSerializer<*>> = Registrars.of(Registries.RECIPE_SERIALIZER, Bpm.ID)

    val ASSEMBLY = TYPES.register("assembly") { -> object : RecipeType<AssemblyRecipe> {
        override fun toString(): String = "bpm:assembly"
    } }

    val ASSEMBLY_SERIALIZER = SERIALIZERS.register("assembly") { ->
        bpm.platform.recipeSerializer(AssemblyRecipe.Serializer.codec, AssemblyRecipe.Serializer.streamCodec)
    }
}
