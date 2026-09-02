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

    /**
     * Something is standing in this block, on the server.
     *
     * `entityInside` gained an `InsideBlockEffectApplier` and a `firstTick` flag at 1.21.9 -- the applier
     * so a block can queue an effect rather than apply it inline, and the flag so it can tell the first
     * tick of contact from the rest. Neither is wanted here, and the older bodies' `isClientSide` guard
     * is folded in so this is called under the same circumstances on every band.
     */
    protected open fun insideBlock(
        state: BlockState,
        level: net.minecraft.server.level.ServerLevel,
        pos: net.minecraft.core.BlockPos,
        entity: net.minecraft.world.entity.Entity,
    ) {}

    //? if >=1.21.9 {
    /*override fun entityInside(
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: net.minecraft.core.BlockPos,
        entity: net.minecraft.world.entity.Entity,
        effects: net.minecraft.world.entity.InsideBlockEffectApplier,
        firstTick: Boolean,
    ) {
        super.entityInside(state, level, pos, entity, effects, firstTick)
        (level as? net.minecraft.server.level.ServerLevel)?.let { insideBlock(state, it, pos, entity) }
    }
    *///?} else {
    override fun entityInside(
        state: BlockState,
        level: net.minecraft.world.level.Level,
        pos: net.minecraft.core.BlockPos,
        entity: net.minecraft.world.entity.Entity,
    ) {
        super.entityInside(state, level, pos, entity)
        (level as? net.minecraft.server.level.ServerLevel)?.let { insideBlock(state, it, pos, entity) }
    }
    //?}
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
