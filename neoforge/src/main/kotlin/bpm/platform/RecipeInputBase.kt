package bpm.platform

import net.minecraft.world.item.ItemStack

/**
 * What a recipe is asked about, under whichever type this band has for it.
 *
 * 1.20.5 introduced `RecipeInput`: two methods, `getItem(index)` and `size()`, and nothing else. Before
 * it a recipe took a `Container` -- a real inventory, with nine further methods about mutation and
 * ownership that a recipe never calls but an implementor must still answer.
 *
 * So this is an intermediate base rather than an alias, the same arrangement `OffScreenAware` and
 * `MoveControlBase` use where a supertype's shape changed between bands. Both arms expose exactly
 * `getItem` and `size` to the subclass, so the shared `AssemblyInput` reads identically on either.
 *
 * The `Container` half is deliberately INERT. A recipe input is a read-only view of what is on the
 * pedestals -- removing from it or marking it changed would be writing to a snapshot -- so the mutators
 * do nothing and `stillValid` answers true.
 */
//? if >=1.20.5 {
abstract class RecipeInputBase : net.minecraft.world.item.crafting.RecipeInput {

    abstract override fun getItem(index: Int): ItemStack

    abstract override fun size(): Int
}
//?} else {
/*abstract class RecipeInputBase : net.minecraft.world.Container {

    /** `Container` declares this too, so the subclass overrides the same name on both bands. */
    abstract override fun getItem(index: Int): ItemStack

    /** `Container` spells this `getContainerSize`; the subclass keeps saying `size`. */
    abstract fun size(): Int

    final override fun getContainerSize(): Int = size()

    final override fun isEmpty(): Boolean = (0 until size()).all { getItem(it).isEmpty }

    // A read-only view: nothing below this line changes anything, and nothing asks it to.
    final override fun removeItem(index: Int, count: Int): ItemStack = ItemStack.EMPTY

    final override fun removeItemNoUpdate(index: Int): ItemStack = ItemStack.EMPTY

    final override fun setItem(index: Int, stack: ItemStack) = Unit

    final override fun setChanged() = Unit

    final override fun stillValid(player: net.minecraft.world.entity.player.Player): Boolean = true

    final override fun clearContent() = Unit
}
*///?}
