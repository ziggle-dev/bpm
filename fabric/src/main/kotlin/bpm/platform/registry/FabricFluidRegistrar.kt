package bpm.platform.registry

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributeHandler
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.item.Item
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FluidState

/**
 * A [FluidSpec] turned into the pair of `FlowingFluid` subclasses Fabric expects.
 *
 * NeoForge builds this for you: `BaseFlowingFluid.Properties` takes the spec's values and produces a
 * source and a flowing fluid. Vanilla has no such helper — `WaterFluid` and `LavaFluid` are each written
 * out by hand — so this writes one out, once, driven by the spec.
 *
 * The sensory half of the spec goes somewhere else entirely. NeoForge keeps the name and the bucket
 * sounds on `FluidType`; Fabric keeps them on `FluidVariantAttributes`, a registry the transfer API
 * consults. Same three facts, registered in a different place, which is exactly why [FluidSpec] carries
 * data rather than either loader's object.
 */
object FabricFluidRegistrar : FluidRegistrar {

    private val fluids: Registrar<Fluid> by lazy { Registrars.of(Registries.FLUID, bpm.Bpm.ID) }

    override fun register(
        spec: FluidSpec,
        bucket: () -> RegistryRef<out Item>,
        block: () -> RegistryRef<out LiquidBlock>,
    ): FluidPair {
        lateinit var pair: FluidPair

        // The three suppliers each fluid needs to answer questions about the others. Lazy for the same
        // reason as on NeoForge: the bucket, the block and the fluid all name each other.
        val ctx = object : SpecContext {
            override val spec = spec
            override val source: Fluid get() = pair.source.get()
            override val flowing: Fluid get() = pair.flowing.get()
            override val bucketItem: Item get() = bucket().get()
            override val liquidBlock: LiquidBlock get() = block().get()
        }

        pair = FluidPair(
            fluids.register(spec.name) { SpecSource(ctx) },
            fluids.register("${spec.name}_flowing") { SpecFlowing(ctx) },
        )

        // The name and the bucket sounds, which on this loader live on the transfer API rather than on
        // the fluid. Registered against both members of the pair: a variant of either should answer.
        //
        // DEFERRED, and it has to be. Naming a fluid here means calling `pair.source.get()`, and on this
        // loader nothing is registered until installAll runs — so doing it now would throw "asked for
        // before registration ran". [installAttributes] is called by the entry point once registration
        // has happened.
        val handler = object : FluidVariantAttributeHandler {
            override fun getName(variant: FluidVariant): Component = Component.translatable(spec.descriptionId)
            // Optionals, because a handler is allowed to say "no opinion" and fall back to vanilla's.
            override fun getFillSound(variant: FluidVariant): java.util.Optional<SoundEvent> =
                java.util.Optional.of(spec.fillSound)

            override fun getEmptySound(variant: FluidVariant): java.util.Optional<SoundEvent> =
                java.util.Optional.of(spec.emptySound)
            override fun getViscosity(variant: FluidVariant, level: Level?): Int = spec.viscosity
            override fun isLighterThanAir(variant: FluidVariant): Boolean = spec.density < 0
        }
        pendingAttributes += {
            FluidVariantAttributes.register(pair.source.get(), handler)
            FluidVariantAttributes.register(pair.flowing.get(), handler)
        }

        return pair
    }

    private val pendingAttributes = ArrayList<() -> Unit>()

    /** Called from the Fabric entry point, after `Registrars.installAll()` has bound every reference. */
    fun installAttributes() {
        for (block in pendingAttributes) block()
        pendingAttributes.clear()
    }
}

/** What both halves of a fluid need to know, resolved late so the three can name each other. */
private interface SpecContext {
    val spec: FluidSpec
    val source: Fluid
    val flowing: Fluid
    val bucketItem: Item
    val liquidBlock: LiquidBlock
}

/**
 * The shared body of the pair.
 *
 * Every value here comes off the spec, so a second fluid would need no new class — which is the point of
 * describing a fluid rather than building one.
 */
private abstract class SpecFluid(protected val ctx: SpecContext) : FlowingFluid() {
    override fun getFlowing(): Fluid = ctx.flowing
    override fun getSource(): Fluid = ctx.source
    override fun getBucket(): Item = ctx.bucketItem
    /*
     * The level this is asked about became a `ServerLevel` at 1.21.2 -- a parameter TYPE change, so the
     * older spelling stops overriding anything rather than failing loudly at the call. Neither body
     * looks at it: whether this fluid makes new sources is a property of the spec it was declared from.
     */
    //? if >=1.21.2 {
    /*override fun canConvertToSource(level: net.minecraft.server.level.ServerLevel): Boolean = ctx.spec.canConvertToSource
    *///?} else {
    override fun canConvertToSource(level: Level): Boolean = ctx.spec.canConvertToSource
    //?}
    override fun getSlopeFindDistance(level: LevelReader): Int = ctx.spec.slopeFindDistance
    override fun getDropOff(level: LevelReader): Int = ctx.spec.levelDecreasePerBlock
    override fun getTickDelay(level: LevelReader): Int = ctx.spec.tickRate
    override fun getExplosionResistance(): Float = 100f
    override fun isSame(fluid: Fluid): Boolean = fluid === ctx.source || fluid === ctx.flowing

    override fun beforeDestroyingBlock(level: LevelAccessor, pos: BlockPos, state: BlockState) {
        val be = if (state.hasBlockEntity()) level.getBlockEntity(pos) else null
        net.minecraft.world.level.block.Block.dropResources(state, level, pos, be)
    }

    override fun canBeReplacedWith(
        state: FluidState,
        level: BlockGetter,
        pos: BlockPos,
        fluid: Fluid,
        direction: Direction,
    ): Boolean = direction == Direction.DOWN && !isSame(fluid)

    override fun createLegacyBlock(state: FluidState): BlockState =
        ctx.liquidBlock.defaultBlockState().setValue(LiquidBlock.LEVEL, getLegacyLevel(state))
}

private class SpecSource(ctx: SpecContext) : SpecFluid(ctx) {
    override fun getAmount(state: FluidState): Int = 8
    override fun isSource(state: FluidState): Boolean = true
}

private class SpecFlowing(ctx: SpecContext) : SpecFluid(ctx) {
    override fun createFluidStateDefinition(builder: net.minecraft.world.level.block.state.StateDefinition.Builder<Fluid, FluidState>) {
        super.createFluidStateDefinition(builder)
        builder.add(LEVEL)
    }

    override fun getAmount(state: FluidState): Int = state.getValue(LEVEL)
    override fun isSource(state: FluidState): Boolean = false
}
