package bpm.compat.jei

import bpm.Bpm
import bpm.world.DeviceBlocks
import bpm.world.assembly.AssemblyRecipe
import bpm.world.assembly.ModRecipes
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.constants.VanillaTypes
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.drawable.IDrawable
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.category.IRecipeCategory
import mezz.jei.api.registration.IRecipeCatalystRegistration
import mezz.jei.api.registration.IRecipeCategoryRegistration
import mezz.jei.api.registration.IRecipeRegistration
import net.minecraft.client.Minecraft
import bpm.platform.client.GuiGraphics
import net.minecraft.network.chat.Component
import bpm.platform.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeHolder
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The assembler's recipes, drawn as the build actually looks.
 *
 * A list of ingredients would be a lie about this machine: what a player needs to know is that the things go
 * on *pedestals around it*, that the catalyst goes *in* it and is not consumed, and that the job has to be
 * kept fed for a length of time. So the category draws a plan view — the machine in the middle, one pedestal
 * slot per ingredient in a ring around it — and states the two running costs per tick as well as in total,
 * because per-tick is the number that decides whether your setup can actually sustain it.
 */
class AssemblyCategory(gui: IGuiHelper) : IRecipeCategory<RecipeHolder<AssemblyRecipe>> {

    private val icon: IDrawable = gui.createDrawableItemStack(ItemStack(DeviceBlocks.QUANTUM_ASSEMBLER.get()))
    private val slot: IDrawable = gui.getSlotDrawable()

    override fun getRecipeType(): RecipeType<RecipeHolder<AssemblyRecipe>> = TYPE
    override fun getTitle(): Component = Component.translatable("bpm.jei.assembly")
    override fun getIcon(): IDrawable = icon
    override fun getWidth(): Int = WIDTH
    override fun getHeight(): Int = HEIGHT

    /**
     * Ingredients ring the machine, the catalyst sits under it, the result is off to the right.
     *
     * The ring is the point: position within it never matters to the recipe — any pedestal may hold any
     * ingredient — and a ring is the clearest way to say "these go around it, in no particular order".
     */
    override fun setRecipe(builder: IRecipeLayoutBuilder, holder: RecipeHolder<AssemblyRecipe>, focuses: IFocusGroup) {
        val recipe = holder.value()
        val parts = recipe.parts
        for ((i, ingredient) in parts.withIndex()) {
            val (x, y) = ringSlot(i, parts.size)
            builder.addSlot(RecipeIngredientRole.INPUT, x, y).addIngredients(ingredient)
        }
        // INPUT, not CATALYST: JEI's catalyst role means "present but not consumed", and one is spent per job.
        builder.addSlot(RecipeIngredientRole.INPUT, CATALYST_X, CATALYST_Y).addIngredients(recipe.catalyst)
        builder.addSlot(RecipeIngredientRole.OUTPUT, RESULT_X, RESULT_Y).addItemStack(recipe.result)
    }

    override fun draw(holder: RecipeHolder<AssemblyRecipe>, slots: IRecipeSlotsView, g: GuiGraphics, mouseX: Double, mouseY: Double) {
        val recipe = holder.value()
        val font = Minecraft.getInstance().font
        val parts = recipe.parts

        // The pedestal each ingredient sits on, drawn under its slot so the ring reads as a build.
        for (i in parts.indices) {
            val (x, y) = ringSlot(i, parts.size)
            slot.draw(g, x - 1, y - 1)
        }
        slot.draw(g, CATALYST_X - 1, CATALYST_Y - 1)
        slot.draw(g, RESULT_X - 1, RESULT_Y - 1)

        // The machine itself in the middle of its own ring: a picture says "these go around THIS" in a way
        // the word "Assembler" did not, and it leaves the ring's interior free of text to collide with.
        icon.draw(g, CENTRE_X - 8, CENTRE_Y - 8)
        g.drawString(font, ARROW, ARROW_X, RESULT_Y + 4, HEADING, false)

        // Named against the slot, because the ring already says what a pedestal is and nothing else says
        // what this one is; where it goes is spelled out in the block below.
        val label = Component.translatable("bpm.jei.assembly.catalyst")
        g.drawString(font, label, CATALYST_X + 8 - font.width(label) / 2, CATALYST_Y + 19, NOTE, false)

        val seconds = recipe.ticks / 20.0
        val lines = ArrayList<Component>(5)
        lines += Component.translatable("bpm.jei.assembly.pedestals", parts.size)
        lines += Component.translatable("bpm.jei.assembly.time", trim(seconds), recipe.ticks)
        // The RATE, not the total. Feeding it more than this is as much a fault as feeding it less, so the
        // number that has to be obeyed is the one shown; the total is just rate x ticks and would only
        // compete with it for the reader's attention.
        lines += Component.translatable("bpm.jei.assembly.energy", commas(recipe.energyPerTick))
        lines += Component.translatable("bpm.jei.assembly.experience", commas(recipe.experiencePerTick))
        lines += Component.translatable("bpm.jei.assembly.where")
        var y = COST_Y
        for (line in lines) {
            g.drawString(font, line, 2, y, BODY, false)
            y += LINE
        }
        if (recipe.paired) g.drawString(font, Component.translatable("bpm.jei.assembly.paired"), 2, y, WARN, false)
    }

    companion object {
        val TYPE: RecipeType<RecipeHolder<AssemblyRecipe>> =
            RecipeType.createFromVanilla(ModRecipes.ASSEMBLY.get())

        // Sized to the longest line the lang file can produce at scale 1, with the text block BELOW the
        // diagram rather than beside it — the first cut overran both edges because the strings were written
        // before the box was measured.
        private const val WIDTH = 168
        private const val HEIGHT = 148

        private const val CENTRE_X = 46
        private const val CENTRE_Y = 40
        private const val RING_R = 27.0

        private const val CATALYST_X = 118
        private const val CATALYST_Y = 8
        private const val ARROW_X = 98
        private const val RESULT_X = 118
        private const val RESULT_Y = 40

        private const val COST_Y = 82
        private const val LINE = 10

        private const val HEADING = 0xFF4DFFD8.toInt()
        private const val BODY = 0xFF404040.toInt()
        private const val NOTE = 0xFF6A6A6A.toInt()
        private const val WARN = 0xFFC95FA5.toInt()

        private val ARROW = Component.literal("→")

        /**
         * Slot [i] of [n] on the ring, starting at the top and going clockwise.
         *
         * Below four ingredients a full circle looks arbitrary, so small counts still spread over the whole
         * ring rather than bunching — the shape says "around the machine" either way.
         */
        fun ringSlot(i: Int, n: Int): Pair<Int, Int> {
            val angle = -Math.PI / 2 + (2 * Math.PI * i) / n.coerceAtLeast(1)
            val x = CENTRE_X + (cos(angle) * RING_R).roundToInt() - 8
            val y = CENTRE_Y + (sin(angle) * RING_R).roundToInt() - 8
            return x to y
        }

        private fun commas(n: Int): String = "%,d".format(n)

        private fun trim(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
    }
}

/**
 * bpm's JEI plugin.
 *
 * Only the assembler needs one: everything else the mod makes comes out of a vanilla crafting grid, which JEI
 * already understands.
 */
@JeiPlugin
class BpmJeiPlugin : IModPlugin {

    override fun getPluginUid(): ResourceLocation = ResourceLocation.fromNamespaceAndPath(Bpm.ID, "jei")

    override fun registerCategories(registration: IRecipeCategoryRegistration) {
        registration.addRecipeCategories(
            AssemblyCategory(registration.jeiHelpers.guiHelper),
            WardenLootCategory(registration.jeiHelpers.guiHelper),
        )
    }

    override fun registerRecipes(registration: IRecipeRegistration) {
        val manager = Minecraft.getInstance().level?.recipeManager ?: return
        val recipes = manager.getAllRecipesFor(ModRecipes.ASSEMBLY.get())
        registration.addRecipes(AssemblyCategory.TYPE, recipes.toList())
        // Not recipes at all, but the only honest answer to "where does a Pristine Core come from".
        registration.addRecipes(WardenLootCategory.TYPE, bpm.world.CoreDrops.all)
    }

    /** Clicking the machine in JEI shows what it makes; so does clicking a pedestal, since that is the build. */
    override fun registerRecipeCatalysts(registration: IRecipeCatalystRegistration) {
        registration.addRecipeCatalyst(VanillaTypes.ITEM_STACK, ItemStack(DeviceBlocks.QUANTUM_ASSEMBLER.get()), AssemblyCategory.TYPE)
        registration.addRecipeCatalyst(VanillaTypes.ITEM_STACK, ItemStack(DeviceBlocks.CORE_PEDESTAL.get()), AssemblyCategory.TYPE)
        // The altar is where you claim it, so it is the thing to click to find out what you might get.
        registration.addRecipeCatalyst(VanillaTypes.ITEM_STACK, ItemStack(DeviceBlocks.CORE_PEDESTAL.get()), WardenLootCategory.TYPE)
    }
}
