package bpm.compat.jei

import bpm.platform.idOf

import bpm.Bpm
import bpm.world.CoreDrops
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.drawable.IDrawable
import mezz.jei.api.gui.ingredient.IRecipeSlotsView
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.RecipeIngredientRole
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.recipe.category.IRecipeCategory
import net.minecraft.client.Minecraft
import bpm.platform.client.GuiGraphics
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.network.chat.Component
import bpm.platform.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import bpm.platform.client.drawText

/**
 * Where the cores come from, with the thing that drops them stood in the middle of it.
 *
 * Every core below the Coherent one is boss RNG, and a recipe book that only ever shows recipes cannot say
 * that — a player looking up a Pristine Core in JEI would find nothing at all and reasonably conclude the
 * item is unobtainable. So this is a category whose "recipe" is a drop table, and whose icon and centrepiece
 * is the Quantum Warden itself: the answer to "where does this come from" should look like the answer.
 */
class WardenLootCategory(gui: IGuiHelper) : IRecipeCategory<CoreDrops.Table> {

    private val icon: IDrawable = gui.createDrawableItemStack(ItemStack(bpm.world.ContentItems.WARDEN_VISOR.get()))
    private val slot: IDrawable = gui.getSlotDrawable()

    /**
     * One Warden, kept for the life of the category.
     *
     * Built lazily and from the client's own level because an entity needs one, and thrown away as null if
     * anything about that fails — a missing decoration must never take the whole JEI page with it.
     */
    private var warden: LivingEntity? = null
    private var tried = false

    override fun getRecipeType(): RecipeType<CoreDrops.Table> = TYPE
    override fun getTitle(): Component = Component.translatable("bpm.jei.warden")
    override fun getIcon(): IDrawable = icon
    override fun getWidth(): Int = WIDTH

    override fun setRecipe(builder: IRecipeLayoutBuilder, table: CoreDrops.Table, focuses: IFocusGroup) {
        for ((i, drop) in table.drops.withIndex()) {
            builder.addSlot(RecipeIngredientRole.OUTPUT, COLUMN_X, TOP + i * ROW)
                .addItemStack(ItemStack(drop.item.get()))
        }
    }

    override fun draw(table: CoreDrops.Table, slots: IRecipeSlotsView, g: GuiGraphics, mouseX: Double, mouseY: Double) {
        val font = Minecraft.getInstance().font
        g.drawText(font, Component.translatable(table.headingKey), 4, 4, HEADING, false)

        for ((i, drop) in table.drops.withIndex()) {
            val y = TOP + i * ROW
            slot.draw(g, COLUMN_X - 1, y - 1)
            val chance = "%.1f%%".format(100.0 * drop.weight / table.total)
            g.drawText(font, chance, COLUMN_X + 22, y + 5, BODY, false)
        }

        drawWarden(g)

        // BELOW the rows, not at a fixed offset from the bottom. Pinning it to the bottom meant the note and
        // the last drop wrote over each other the moment a table gained a fourth entry, which is exactly
        // what happened when the Entangled Core joined the roll.
        val note = Component.translatable(table.noteKey)
        var y = TOP + table.drops.size * ROW + 4
        for (line in font.split(note, WIDTH - 8)) {
            g.drawText(font, line, 4, y, DIM, false)
            y += 10
        }
    }

    /** Tall enough for the longest table plus its note, worked out rather than guessed at. */
    override fun getHeight(): Int = heightFor(CoreDrops.all.maxOf { it.drops.size })

    /**
     * The boss itself, or its visor if it cannot be built.
     *
     * An entity needs a level, and a JEI page can be drawn in situations where making one fails. The
     * fallback matters more than it looks: a silent failure left this column simply BLANK, which reads as a
     * broken page rather than as a missing decoration, and told me nothing about which path had run.
     */
    private fun drawWarden(g: GuiGraphics) {
        val entity = warden ?: run {
            if (!tried) {
                tried = true
                warden = runCatching {
                    bpm.world.entity.ModEntities.WARDEN.get().create(Minecraft.getInstance().level!!) as? LivingEntity
                }.onFailure { Bpm.LOGGER.warn("bpm jei: could not build a Warden to draw, showing its visor instead", it) }
                    .getOrNull()
            }
            warden
        } ?: run {
            g.pose().pushPose()
            g.pose().translate(ENTITY_X.toFloat(), ENTITY_TOP.toFloat(), 0f)
            g.pose().scale(2f, 2f, 1f)
            g.renderItem(ItemStack(bpm.world.ContentItems.WARDEN_VISOR.get()), 0, 8)
            g.pose().popPose()
            return
        }
        runCatching {
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                g, ENTITY_X, ENTITY_TOP, ENTITY_X + ENTITY_W, ENTITY_TOP + ENTITY_H, ENTITY_SCALE, 0.0f, 0f, 0f, entity,
            )
        }.onFailure {
            warden = null
            Bpm.LOGGER.warn("bpm jei: drawing the Warden failed, falling back to its visor", it)
        }
    }

    companion object {
        val TYPE: RecipeType<CoreDrops.Table> =
            RecipeType.create(Bpm.ID, "warden_loot", CoreDrops.Table::class.java)

        private const val WIDTH = 162

        private const val ENTITY_X = 8
        private const val ENTITY_TOP = 16
        private const val ENTITY_W = 56
        private const val ENTITY_H = 64
        private const val ENTITY_SCALE = 24

        /** Heading, then one row per drop, then two lines of note, then a little air. */
        private fun heightFor(rows: Int): Int = TOP + rows * ROW + 4 + NOTE_LINES * 10 + 6
        private const val NOTE_LINES = 2

        private const val COLUMN_X = 96
        private const val TOP = 20
        private const val ROW = 20

        private const val HEADING = 0xFF4DFFD8.toInt()
        private const val BODY = 0xFF404040.toInt()
        private const val DIM = 0xFF6A6A6A.toInt()

        fun typeOf(): ResourceLocation = idOf(Bpm.ID, "warden_loot")
    }
}
