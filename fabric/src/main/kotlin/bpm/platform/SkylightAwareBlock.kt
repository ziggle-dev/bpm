package bpm.platform

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour.Properties
import net.minecraft.world.level.block.state.BlockState

/**
 * A `Block` whose skylight answer is version-independent.
 *
 * This is the pattern for the one kind of version change an alias cannot absorb: an override whose
 * parameter LIST changed, not just a parameter's type. `propagatesSkylightDown` took
 * `(BlockState, BlockGetter, BlockPos)` until 1.21.2 and takes `(BlockState)` after it, and a file
 * compiled by both versions can only declare one of those.
 *
 * So the signature lives here, in a branch, where it can be switched — and it immediately delegates to
 * [propagatesSkylight], which the shared subclass overrides and which never changes. The version-specific
 * part is three lines and carries no logic; the logic stays shared, where it belongs.
 *
 * Ten lines per method, per loader. Worth knowing as the price of an arity change before a band with
 * several of them.
 */
abstract class SkylightAwareBlock(properties: Properties) : Block(properties) {
    /** Whether skylight passes through. Overridden by shared code; called by whichever signature exists. */
    protected abstract fun propagatesSkylight(state: BlockState): Boolean

    //? if >=1.21.2 {
    /*override fun propagatesSkylightDown(state: BlockState): Boolean = propagatesSkylight(state)
    *///?} else {
    override fun propagatesSkylightDown(
        state: BlockState,
        level: net.minecraft.world.level.BlockGetter,
        pos: net.minecraft.core.BlockPos,
    ): Boolean = propagatesSkylight(state)
    //?}
}
