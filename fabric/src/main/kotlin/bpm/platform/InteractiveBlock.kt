package bpm.platform

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour.Properties
import net.minecraft.world.level.block.state.BlockState

/**
 * A block that answers a click, without naming the method the game calls to ask.
 *
 * 1.20.5 SPLIT `use` in two. Before it, one `use(state, level, pos, player, hand, hit)` handled both a
 * click with an item and a click with an empty hand, and told them apart by looking in the hand itself.
 * After it there are two methods, `useItemOn` and `useWithoutItem`, and the game decides which to call.
 *
 * That is a signature change in seven blocks and a behaviour change in none, so the two hooks below are
 * what the shared blocks write and this file is the only place that knows which method the game calls.
 *
 * **This is a deliberate copy of `neoforge/src/main/kotlin/bpm/platform/InteractiveBlock.kt`** -- see the
 * note in `Interactions.kt`: only a branch's own source is processed for directives, and the branches
 * here are the loaders, so a version-switched base that the shared tree extends exists once per loader.
 */
abstract class InteractiveBlock(properties: Properties) : Block(properties) {

    /**
     * Using this block with an item in hand.
     *
     * Answer [BlockUse.PASS_TO_BLOCK] to decline and let the empty-hand path run instead -- which on the
     * older band is the same call continuing, and on the newer one is `useWithoutItem`.
     */
    protected open fun onUseItem(
        stack: net.minecraft.world.item.ItemStack,
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        player: net.minecraft.world.entity.player.Player,
        hand: net.minecraft.world.InteractionHand,
        hit: net.minecraft.world.phys.BlockHitResult,
    ): BlockUseResult = BlockUse.PASS_TO_BLOCK

    /** Using this block with nothing in hand, or after [onUseItem] declined. */
    protected open fun onUseEmpty(
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        player: net.minecraft.world.entity.player.Player,
        hit: net.minecraft.world.phys.BlockHitResult,
    ): net.minecraft.world.InteractionResult = net.minecraft.world.InteractionResult.PASS

    //? if >=1.20.5 {
    override fun useItemOn(
        stack: net.minecraft.world.item.ItemStack,
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        player: net.minecraft.world.entity.player.Player,
        hand: net.minecraft.world.InteractionHand,
        hit: net.minecraft.world.phys.BlockHitResult,
    ): BlockUseResult = onUseItem(stack, state, level, pos, player, hand, hit)

    override fun useWithoutItem(
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        player: net.minecraft.world.entity.player.Player,
        hit: net.minecraft.world.phys.BlockHitResult,
    ): net.minecraft.world.InteractionResult = onUseEmpty(state, level, pos, player, hit)
    //?} else {
    /*/**
     * One method did both jobs here, and the fall-through is a second dispatch rather than a second call.
     *
     * `PASS` is what `PASS_TO_BLOCK` maps to on this band, so a decline reads the same way it does above:
     * the empty-hand handler runs next. A block that answers anything else has handled the click.
     */
    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        player: net.minecraft.world.entity.player.Player,
        hand: net.minecraft.world.InteractionHand,
        hit: net.minecraft.world.phys.BlockHitResult,
    ): net.minecraft.world.InteractionResult {
        val stack = player.getItemInHand(hand)
        if (!stack.isEmpty) {
            val answered = onUseItem(stack, state, level, pos, player, hand, hit)
            if (answered != net.minecraft.world.InteractionResult.PASS) return answered
        }
        return onUseEmpty(state, level, pos, player, hit)
    }
    *///?}
}
