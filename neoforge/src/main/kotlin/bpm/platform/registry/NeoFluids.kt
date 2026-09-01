package bpm.platform.registry

import bpm.Bpm
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.material.Fluid
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
import net.neoforged.neoforge.common.SoundActions
import net.neoforged.neoforge.fluids.BaseFlowingFluid
import net.neoforged.neoforge.fluids.FluidType
import net.neoforged.neoforge.registries.NeoForgeRegistries

/**
 * A [FluidSpec] turned into NeoForge's three objects: a fluid type, a source and a flowing fluid.
 *
 * The properties object is built lazily on purpose, and this is the one subtlety worth knowing. The
 * bucket item and the liquid block each name the fluid in their own registration factory, and the fluid
 * names both of them here — so if the properties were built eagerly, whichever of the three was declared
 * first would ask for a value nothing had produced. Deferring until the first factory actually runs lets
 * all three be declared in any order.
 */
object NeoFluidRegistrar : FluidRegistrar {

    // Lazy, so that merely naming this object to install it does not reach for Registrars before the
    // registries seam itself has been wired. Bpm's init wires every seam before using any of them, and an
    // object whose construction quietly breaks that rule would fail at startup with a lateinit error
    // pointing at the wrong seam.
    private val types: Registrar<FluidType> by lazy { Registrars.of(NeoForgeRegistries.Keys.FLUID_TYPES, Bpm.ID) }
    private val fluids: Registrar<Fluid> by lazy { Registrars.of(Registries.FLUID, Bpm.ID) }

    private val declared = HashMap<String, RegistryRef<FluidType>>()

    override fun register(
        spec: FluidSpec,
        bucket: () -> RegistryRef<out Item>,
        block: () -> RegistryRef<out LiquidBlock>,
    ): FluidPair {
        val type = types.register(spec.name) { ->
            FluidType(
                FluidType.Properties.create()
                    .descriptionId(spec.descriptionId)
                    .lightLevel(spec.lightLevel)
                    .density(spec.density)
                    .viscosity(spec.viscosity)
                    .rarity(spec.rarity)
                    .canDrown(spec.canDrown)
                    .canConvertToSource(spec.canConvertToSource)
                    .sound(SoundActions.BUCKET_FILL, spec.fillSound)
                    .sound(SoundActions.BUCKET_EMPTY, spec.emptySound),
            )
        }
        declared[spec.name] = type

        lateinit var pair: FluidPair
        val properties by lazy {
            BaseFlowingFluid.Properties(type, pair.source, pair.flowing)
                .bucket(java.util.function.Supplier { bucket().get() })
                .block(java.util.function.Supplier { block().get() })
                .tickRate(spec.tickRate)
                .slopeFindDistance(spec.slopeFindDistance)
                .levelDecreasePerBlock(spec.levelDecreasePerBlock)
        }
        pair = FluidPair(
            fluids.register(spec.name) { -> BaseFlowingFluid.Source(properties) },
            fluids.register("${spec.name}_flowing") { -> BaseFlowingFluid.Flowing(properties) },
        )
        return pair
    }

    /** The fluid type for a declared spec, which only the client extensions registration needs. */
    fun type(name: String): RegistryRef<FluidType> = declared.getValue(name)

    /**
     * NeoForge asks a fluid how it looks through client extensions on its type. Everything answered here
     * comes off the spec, so the same four facts serve Fabric's render handler unchanged.
     */
    fun looks(spec: FluidSpec): IClientFluidTypeExtensions = object : IClientFluidTypeExtensions {
        override fun getTintColor(): Int = spec.tint
        override fun getStillTexture() = spec.stillTexture
        override fun getFlowingTexture() = spec.flowingTexture
        override fun getOverlayTexture() = spec.overlayTexture
    }
}
