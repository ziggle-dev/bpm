package bpm.world

import bpm.platform.registry.BlockRegistrar
import bpm.platform.registry.ComponentRegistrar
import bpm.platform.registry.ItemRegistrar
import bpm.platform.registry.Registrar
import bpm.platform.registry.RegistryRef
import bpm.platform.registry.Registrars
import bpm.Bpm
import net.minecraft.core.registries.Registries
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.Rarity
import net.minecraft.world.level.material.Fluid
import net.neoforged.neoforge.common.SoundActions
import net.neoforged.neoforge.fluids.BaseFlowingFluid
import net.neoforged.neoforge.fluids.FluidType
import net.neoforged.neoforge.registries.NeoForgeRegistries

/**
 * Liquid experience — what `xp.vacuum` turns orbs into and `xp.drop` turns back. A real fluid, so it sits in
 * the controller's tanks, moves through `fluids.move` into any other mod's tank, and can be bucketed.
 * Twenty millibuckets per point ([ControllerStores.XP_MB_PER_POINT]).
 */
object ModFluids {
    val TYPES: Registrar<FluidType> = Registrars.of(NeoForgeRegistries.Keys.FLUID_TYPES, Bpm.ID)
    val REG: Registrar<Fluid> = Registrars.of(Registries.FLUID, Bpm.ID)

    val EXPERIENCE_TYPE: RegistryRef<FluidType> = TYPES.register("experience") { ->
        FluidType(
            FluidType.Properties.create()
                .descriptionId("fluid.bpm.experience")
                .lightLevel(10)
                .density(1600)
                .viscosity(2500)
                .rarity(Rarity.UNCOMMON)
                .canDrown(false)
                .canConvertToSource(false)
                .sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL)
                .sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY),
        )
    }

    private val properties: BaseFlowingFluid.Properties by lazy {
        BaseFlowingFluid.Properties(EXPERIENCE_TYPE, EXPERIENCE, EXPERIENCE_FLOWING)
            .bucket(ModItems.EXPERIENCE_BUCKET)
            .block(ModBlocks.EXPERIENCE)
            .tickRate(10)
            .slopeFindDistance(3)
            .levelDecreasePerBlock(2)
    }

    val EXPERIENCE: RegistryRef<BaseFlowingFluid.Source> = REG.register("experience") { -> BaseFlowingFluid.Source(properties) }
    val EXPERIENCE_FLOWING: RegistryRef<BaseFlowingFluid.Flowing> = REG.register("experience_flowing") { -> BaseFlowingFluid.Flowing(properties) }

    const val ID = "bpm:experience"
}
