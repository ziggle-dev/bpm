package bpm.platform

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour.Properties
import net.minecraft.world.level.block.state.BlockState

/**
 * A `Block` that reacts to a neighbour's shape without naming the callback's parameter list.
 *
 * `updateShape` did not merely gain arguments at 1.21.2 -- it was reordered and re-typed: six
 * parameters became eight, the `LevelAccessor` split into a `LevelReader` and a `ScheduledTickAccess`,
 * a `RandomSource` arrived, and `direction` and `neighborState` swapped places around the position.
 * A shared file cannot declare both, so the signature lives here.
 *
 * The three facts the only caller in this mod actually wants -- its own state, which side changed, and
 * what is there now -- are the same on both, so [onNeighborShape] is what the shared subclass writes.
 */
abstract class ShapeAwareBlock(properties: Properties) : Block(properties) {

    /** The new state for [state] given that the block on [direction] is now [neighborState]. */
    protected abstract fun onNeighborShape(state: BlockState, direction: Direction, neighborState: BlockState): BlockState

    //? if >=1.21.2 {
    /*override fun updateShape(
        state: BlockState,
        level: net.minecraft.world.level.LevelReader,
        ticks: net.minecraft.world.level.ScheduledTickAccess,
        pos: BlockPos,
        direction: Direction,
        neighborPos: BlockPos,
        neighborState: BlockState,
        random: net.minecraft.util.RandomSource,
    ): BlockState = onNeighborShape(state, direction, neighborState)
    *///?} else {
    override fun updateShape(
        state: BlockState,
        direction: Direction,
        neighborState: BlockState,
        level: net.minecraft.world.level.LevelAccessor,
        pos: BlockPos,
        neighborPos: BlockPos,
    ): BlockState = onNeighborShape(state, direction, neighborState)
    //?}
}
