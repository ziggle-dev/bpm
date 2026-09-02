package bpm.platform

import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack

/**
 * What using an item, or using an item on a block, answers — spelled the same way on every version.
 *
 * 1.21.2 folded three types into one. `InteractionResultHolder<ItemStack>`, which `Item.use` returned and
 * which carried the stack back, became a plain `InteractionResult`; so did `ItemInteractionResult`, which
 * `BlockBehaviour.useItemOn` returned. The stack is no longer part of the answer and neither is the
 * distinction between the two calls.
 *
 * That is a signature change in forty-odd places and a behaviour change in none, which makes it exactly
 * the wrong thing to spend forty-odd `//? if` directives on. Two aliases and two small objects keep every
 * call site version-independent, and this file is the only place that knows the difference.
 *
 * **It lives in `neoforge/src` rather than the shared tree because only a branch's own source is
 * processed for directives.** The call sites stay in the shared tree and never mention a version. When
 * Fabric gains a second version it needs a copy of this file; that duplication is the price of the loader
 * being the branch, and it is one small file rather than the whole tree.
 *
 * The helpers still take the stack on the newer versions and ignore it. Dropping the argument would mean
 * editing every call site twice — once now, once for the older branch — and an unused argument is cheaper
 * than a divergence.
 */
//? if >=1.21.2 {
/*typealias UseResult = InteractionResult
typealias BlockUseResult = InteractionResult

object Use {
    fun pass(stack: ItemStack): UseResult = InteractionResult.PASS
    fun success(stack: ItemStack): UseResult = InteractionResult.SUCCESS
    fun fail(stack: ItemStack): UseResult = InteractionResult.FAIL
    fun consume(stack: ItemStack): UseResult = InteractionResult.CONSUME
    fun sided(stack: ItemStack, isClientSide: Boolean): UseResult =
        if (isClientSide) InteractionResult.SUCCESS else InteractionResult.CONSUME
}

object BlockUse {
    val SUCCESS: BlockUseResult = InteractionResult.SUCCESS
    val CONSUME: BlockUseResult = InteractionResult.CONSUME
    val FAIL: BlockUseResult = InteractionResult.FAIL

    /**
     * "Not mine — let the block have it."
     *
     * `TRY_WITH_EMPTY_HAND`, emphatically NOT `PASS`, and the difference is the whole behaviour. When the
     * three result types merged at 1.21.2 the fall-through got its own value: both game modes gate it on
     * `interactionresult instanceof InteractionResult.TryEmptyHandInteraction` before they will call
     * `useWithoutItem`. `PASS` means "nothing happened" and stops there.
     *
     * Mapping this to `PASS` compiles, type-checks and silently severs every block whose `useItemOn`
     * declines in favour of its own empty-hand handler — which is how the chamber pedestal stopped
     * responding to a click at all. Vanilla's own `CakeBlock` returns `TRY_WITH_EMPTY_HAND` exactly where
     * it returned `PASS_TO_DEFAULT_BLOCK_INTERACTION` before.
     */
    val PASS_TO_BLOCK: BlockUseResult = InteractionResult.TRY_WITH_EMPTY_HAND

    fun sidedSuccess(isClientSide: Boolean): BlockUseResult = InteractionResult.SUCCESS
}

/**
 * A plain `InteractionResult`, for `useWithoutItem` and friends, which never took the other two types.
 * `sidedSuccess` went away with them at 1.21.2 — the newer answer is simply SUCCESS.
 */
object Interact {
    fun sided(isClientSide: Boolean): InteractionResult = InteractionResult.SUCCESS
}
*///?} elif >=1.20.5 {
typealias UseResult = net.minecraft.world.InteractionResultHolder<ItemStack>
typealias BlockUseResult = net.minecraft.world.ItemInteractionResult

object Use {
    fun pass(stack: ItemStack): UseResult = net.minecraft.world.InteractionResultHolder.pass(stack)
    fun success(stack: ItemStack): UseResult = net.minecraft.world.InteractionResultHolder.success(stack)
    fun fail(stack: ItemStack): UseResult = net.minecraft.world.InteractionResultHolder.fail(stack)
    fun consume(stack: ItemStack): UseResult = net.minecraft.world.InteractionResultHolder.consume(stack)
    fun sided(stack: ItemStack, isClientSide: Boolean): UseResult =
        net.minecraft.world.InteractionResultHolder.sidedSuccess(stack, isClientSide)
}

object BlockUse {
    val SUCCESS: BlockUseResult = net.minecraft.world.ItemInteractionResult.SUCCESS
    val CONSUME: BlockUseResult = net.minecraft.world.ItemInteractionResult.CONSUME
    val FAIL: BlockUseResult = net.minecraft.world.ItemInteractionResult.FAIL

    val PASS_TO_BLOCK: BlockUseResult = net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION

    fun sidedSuccess(isClientSide: Boolean): BlockUseResult =
        net.minecraft.world.ItemInteractionResult.sidedSuccess(isClientSide)
}

object Interact {
    fun sided(isClientSide: Boolean): InteractionResult = InteractionResult.sidedSuccess(isClientSide)
}
//?} else {
/*typealias UseResult = net.minecraft.world.InteractionResultHolder<ItemStack>

/*
 * `ItemInteractionResult` did not exist before 1.20.5: using an item on a block answered a plain
 * `InteractionResult`, the same type everything else answered.
 *
 * PASS_TO_BLOCK is the one that needs a thought rather than a rename. Its whole meaning is "I did not
 * handle this -- run the block's own interaction", and that is exactly what PASS meant on this band,
 * because there was no separate item-on-block result to pass out of.
 */
typealias BlockUseResult = net.minecraft.world.InteractionResult

object Use {
    fun pass(stack: ItemStack): UseResult = net.minecraft.world.InteractionResultHolder.pass(stack)
    fun success(stack: ItemStack): UseResult = net.minecraft.world.InteractionResultHolder.success(stack)
    fun fail(stack: ItemStack): UseResult = net.minecraft.world.InteractionResultHolder.fail(stack)
    fun consume(stack: ItemStack): UseResult = net.minecraft.world.InteractionResultHolder.consume(stack)
    fun sided(stack: ItemStack, isClientSide: Boolean): UseResult =
        net.minecraft.world.InteractionResultHolder.sidedSuccess(stack, isClientSide)
}

object BlockUse {
    val SUCCESS: BlockUseResult = net.minecraft.world.InteractionResult.SUCCESS
    val CONSUME: BlockUseResult = net.minecraft.world.InteractionResult.CONSUME
    val FAIL: BlockUseResult = net.minecraft.world.InteractionResult.FAIL

    val PASS_TO_BLOCK: BlockUseResult = net.minecraft.world.InteractionResult.PASS

    fun sidedSuccess(isClientSide: Boolean): BlockUseResult =
        net.minecraft.world.InteractionResult.sidedSuccess(isClientSide)
}
object Interact {
    fun sided(isClientSide: Boolean): InteractionResult = InteractionResult.sidedSuccess(isClientSide)
}
*///?}
