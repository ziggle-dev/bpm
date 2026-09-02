package bpm.world

import bpm.platform.registry.FluidPair
import bpm.platform.registry.FluidSpec
import bpm.platform.registry.FluidRegistry
import bpm.platform.registry.RegistryRef
import bpm.platform.ResourceLocation
import net.minecraft.world.item.Rarity
import net.minecraft.world.level.material.FlowingFluid

/**
 * Liquid experience — what `xp.vacuum` turns orbs into and `xp.drop` turns back. A real fluid, so it sits in
 * the controller's tanks, moves through `fluids.move` into any other mod's tank, and can be bucketed.
 * Twenty millibuckets per point ([ControllerStores.XP_MB_PER_POINT]).
 *
 * It looks like water dyed the green of an orb, and glows a little.
 */
object ModFluids {

    val SPEC = FluidSpec(
        name = "experience",
        descriptionId = "fluid.bpm.experience",
        stillTexture = ResourceLocation.withDefaultNamespace("block/water_still"),
        flowingTexture = ResourceLocation.withDefaultNamespace("block/water_flow"),
        overlayTexture = ResourceLocation.withDefaultNamespace("block/water_overlay"),
        tint = 0xFFA8F04A.toInt(),
        lightLevel = 10,
        density = 1600,
        viscosity = 2500,
        rarity = Rarity.UNCOMMON,
        canDrown = false,
        canConvertToSource = false,
        tickRate = 10,
        slopeFindDistance = 3,
        levelDecreasePerBlock = 2,
    )

    private val pair: FluidPair by lazy { FluidRegistry.register(SPEC, { ModItems.EXPERIENCE_BUCKET }, { ModBlocks.EXPERIENCE }) }

    val EXPERIENCE: RegistryRef<out FlowingFluid> get() = pair.source
    val EXPERIENCE_FLOWING: RegistryRef<out FlowingFluid> get() = pair.flowing

    /** Touched to declare the fluid, the way every other registry object here is; see `BpmRegistries.install`. */
    fun declare() {
        pair
    }

    const val ID = "bpm:experience"
}
