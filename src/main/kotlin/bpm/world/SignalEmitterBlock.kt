package bpm.world

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.IntegerProperty

/**
 * The Signal Emitter: a block that puts out whatever redstone level a controller last told it to, on every
 * face, strongly — so a controller powers things wherever a link reaches, not only beside itself. Passive: no
 * block entity, no tick; the level lives in the block state, and `redstone.emitAt(link, level)` sets it.
 */
class SignalEmitterBlock(properties: Properties) : Block(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(POWER, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(POWER)
    }

    override fun isSignalSource(state: BlockState): Boolean = true
    override fun getSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int = state.getValue(POWER)
    override fun getDirectSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int = state.getValue(POWER)

    companion object {
        val POWER: IntegerProperty = BlockStateProperties.POWER

        /** The level the emitter at [pos] puts out, or null when the block there is not one. */
        fun levelAt(level: Level, pos: BlockPos): Int? =
            level.getBlockState(pos).takeIf { it.`is`(ContentBlocks.SIGNAL_EMITTER.get()) }?.getValue(POWER)

        /** Sets the level the emitter at [pos] puts out (0–15); false when the block there is not one. */
        fun emit(level: Level, pos: BlockPos, strength: Int): Boolean {
            val state = level.getBlockState(pos)
            if (!state.`is`(ContentBlocks.SIGNAL_EMITTER.get())) return false
            val s = strength.coerceIn(0, 15)
            if (state.getValue(POWER) != s) level.setBlock(pos, state.setValue(POWER, s), Block.UPDATE_ALL)
            return true
        }
    }
}
