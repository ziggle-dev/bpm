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
abstract class RemovalAwareBlock(properties: Properties) : net.minecraft.world.level.block.Block(properties) {

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
