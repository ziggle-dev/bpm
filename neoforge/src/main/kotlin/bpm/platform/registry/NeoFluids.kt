package bpm.platform.registry

import bpm.Bpm
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.material.Fluid
/*
 * The same three types under two package names, and one of them under two NAMES: what NeoForge calls
 * `BaseFlowingFluid` was Forge's `ForgeFlowingFluid`, which is why this is an alias rather than an
 * import -- the nested `Source`, `Flowing` and `Properties` are reached through it either way.
 */
//? if >=1.20.2 {
//? if <26.1 {
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
//?}
import net.neoforged.neoforge.common.SoundActions
import net.neoforged.neoforge.fluids.FluidType

private typealias FlowingFluidProperties = net.neoforged.neoforge.fluids.BaseFlowingFluid.Properties
private typealias FlowingFluidSource = net.neoforged.neoforge.fluids.BaseFlowingFluid.Source
private typealias FlowingFluidFlowing = net.neoforged.neoforge.fluids.BaseFlowingFluid.Flowing
private val FLUID_TYPES = net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.FLUID_TYPES
//?} else {
/*import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions
import net.minecraftforge.common.SoundActions
import net.minecraftforge.fluids.FluidType

private typealias FlowingFluidProperties = net.minecraftforge.fluids.ForgeFlowingFluid.Properties
private typealias FlowingFluidSource = net.minecraftforge.fluids.ForgeFlowingFluid.Source
private typealias FlowingFluidFlowing = net.minecraftforge.fluids.ForgeFlowingFluid.Flowing
private val FLUID_TYPES = net.minecraftforge.registries.ForgeRegistries.Keys.FLUID_TYPES
*///?}

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
    private val types: Registrar<FluidType> by lazy { Registrars.of(FLUID_TYPES, Bpm.ID) }
    private val fluids: Registrar<Fluid> by lazy { Registrars.of(Registries.FLUID, Bpm.ID) }

    private val declared = HashMap<String, RegistryRef<FluidType>>()

    override fun register(
        spec: FluidSpec,
        bucket: () -> RegistryRef<out Item>,
        block: () -> RegistryRef<out LiquidBlock>,
    ): FluidPair {
        val type = types.register(spec.name) { ->
            fluidTypeOf(
                spec,
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
            FlowingFluidProperties(type, pair.source, pair.flowing)
                .bucket(java.util.function.Supplier { bucket().get() })
                .block(java.util.function.Supplier { block().get() })
                .tickRate(spec.tickRate)
                .slopeFindDistance(spec.slopeFindDistance)
                .levelDecreasePerBlock(spec.levelDecreasePerBlock)
        }
        pair = FluidPair(
            fluids.register(spec.name) { -> FlowingFluidSource(properties) },
            fluids.register("${spec.name}_flowing") { -> FlowingFluidFlowing(properties) },
        )
        return pair
    }

    /** The fluid type for a declared spec, which only the client extensions registration needs. */
    fun type(name: String): RegistryRef<FluidType> = declared.getValue(name)

    /*
     * How a fluid looks stopped being something a fluid is ASKED and became something it HAS.
     *
     * Until 26.1 NeoForge put the four facts on client extensions of the fluid type, and vanilla asked
     * for them while drawing. At 26.1 they became a baked `FluidModel` held in the model manager, so they
     * are registered once during model loading and read back from there. The same four facts either way,
     * which is why [FluidSpec] carries data rather than either loader's object.
     *
     * The chunk layer is NOT among them any more, and does not need to be: `FluidModel.Unbaked.bake`
     * derives it from the sprites' own transparency. That is why `drawFluidTranslucent` has no 26.1 arm
     * -- the experience fluid's texture has alpha, so it lands in the translucent layer by itself.
     */
    //? if >=26.1 {
    /*/** Register the spec's model. Called from the client entry point on `RegisterFluidModelsEvent`. */
    fun models(event: net.neoforged.neoforge.client.event.RegisterFluidModelsEvent, spec: FluidSpec, still: Fluid, flowing: Fluid) {
        val overlay = spec.overlayTexture ?: spec.stillTexture
        event.register(
            net.minecraft.client.renderer.block.FluidModel.Unbaked(
                net.minecraft.client.resources.model.sprite.Material(spec.stillTexture),
                net.minecraft.client.resources.model.sprite.Material(spec.flowingTexture),
                net.minecraft.client.resources.model.sprite.Material(overlay),
                net.minecraft.client.color.block.BlockTintSource { spec.tint },
            ),
            still,
            flowing,
        )
    }
    *///?} else {
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
    //?}
}

/**
 * The fluid type, made with its appearance attached where the band has nowhere else to put it.
 *
 * From 1.20.2 a mod registers client extensions for a fluid type in an EVENT, so the type itself is
 * plain. On 1.20.1 there is no such event: `FluidType.initializeClient` is how a type hands over its
 * client extensions, so the type has to be a subclass that answers it.
 */
//? if >=1.20.2 {
@Suppress("UNUSED_PARAMETER")
private fun fluidTypeOf(spec: FluidSpec, properties: FluidType.Properties): FluidType = FluidType(properties)
//?} else {
/*private fun fluidTypeOf(spec: FluidSpec, properties: FluidType.Properties): FluidType =
    object : FluidType(properties) {
        override fun initializeClient(consumer: java.util.function.Consumer<IClientFluidTypeExtensions>) {
            consumer.accept(NeoFluidRegistrar.looks(spec))
        }
    }
*///?}
