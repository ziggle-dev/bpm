package bpm.platform

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.BlockBehaviour.Properties

/**
 * A block that cleans up after itself when it is genuinely removed.
 *
 * `onRemove(state, level, pos, newState, moved)` became
 * `affectNeighborsAfterRemoval(state, serverLevel, pos, moved)` at 1.21.9, and two things fell out of the
 * signature because the caller now guarantees them.
 *
 * `newState` is gone: every override began with `if (!state.is(newState.block))` -- "am I really being
 * removed, or merely replaced by another of me" -- and the newer hook is only called in the first case.
 * And the level is a `ServerLevel`, so the `isClientSide` guard the older bodies carried is gone too.
 *
 * Both guards therefore live in the older arm below, and [onBlockRemoved] is called under exactly the
 * same circumstances on every version: really removed, on the server.
 */
abstract class RemovalAwareBlock(properties: Properties) : InteractiveBlock(properties) {

    /** Called once, server-side, when this block is actually gone. */
    protected abstract fun onBlockRemoved(state: BlockState, level: ServerLevel, pos: BlockPos, movedByPiston: Boolean)

    //? if >=1.21.5 {
    /*override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        onBlockRemoved(state, level, pos, movedByPiston)
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston)
    }
    *///?} else {
    @Deprecated("Deprecated in Java")
    override fun onRemove(
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        newState: BlockState,
        movedByPiston: Boolean,
    ) {
        if (!state.`is`(newState.block)) (level as? ServerLevel)?.let { onBlockRemoved(state, it, pos, movedByPiston) }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }
    //?}
}

/** The same, for a block that also carries a horizontal facing. */
abstract class RemovalAwareHorizontalBlock(properties: Properties) :
    net.minecraft.world.level.block.HorizontalDirectionalBlock(properties) {

    /**
     * The codec vanilla asks a block for from 1.20.2, or null to keep the default.
     *
     * [blockCodec] rather than an override, because the method it answers does not exist on the older
     * band at all -- and neither does `simpleCodec`, which is why [codecOf] is here rather than at the
     * call site: it is `protected static` on `BlockBehaviour`, so only a subclass can reach it.
     */
    protected abstract fun blockCodec(): com.mojang.serialization.MapCodec<*>?

    //? if >=1.20.5 {
    protected fun <B : net.minecraft.world.level.block.Block> codecOf(
        ctor: java.util.function.Function<Properties, B>,
    ): com.mojang.serialization.MapCodec<*>? = simpleCodec(ctor)

    @Suppress("UNCHECKED_CAST")
    override fun codec(): com.mojang.serialization.MapCodec<out net.minecraft.world.level.block.HorizontalDirectionalBlock> =
        requireNotNull(blockCodec()) as com.mojang.serialization.MapCodec<out net.minecraft.world.level.block.HorizontalDirectionalBlock>
    //?} else {
    /*@Suppress("UNUSED_PARAMETER")
    protected fun <B : net.minecraft.world.level.block.Block> codecOf(
        ctor: java.util.function.Function<Properties, B>,
    ): com.mojang.serialization.MapCodec<*>? = null
    *///?}

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

    protected abstract fun onBlockRemoved(state: BlockState, level: ServerLevel, pos: BlockPos, movedByPiston: Boolean)

    //? if >=1.21.5 {
    /*override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        onBlockRemoved(state, level, pos, movedByPiston)
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston)
    }
    *///?} else {
    @Deprecated("Deprecated in Java")
    override fun onRemove(
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: BlockPos,
        newState: BlockState,
        movedByPiston: Boolean,
    ) {
        if (!state.`is`(newState.block)) (level as? ServerLevel)?.let { onBlockRemoved(state, it, pos, movedByPiston) }
        super.onRemove(state, level, pos, newState, movedByPiston)
    }
    //?}
}
