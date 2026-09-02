package bpm.platform.registry

import bpm.platform.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.Item
import net.minecraft.world.item.Rarity
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.material.FlowingFluid

/**
 * A fluid, described rather than built.
 *
 * This is the widest gap between the two loaders in the whole mod. NeoForge models a fluid as a
 * `FluidType` carrying its physical and sensory properties, plus a `BaseFlowingFluid` source/flowing
 * pair built from a properties object. Fabric has no fluid-type concept at all: the same facts are split
 * between overrides on a `FlowingFluid` subclass, an attribute handler the transfer API consults, and a
 * client-side render handler.
 *
 * There is no shared API to hide behind, so this hides behind data instead. Every field is a decision
 * about liquid experience, not a NeoForge type, and each loader is free to put it wherever that loader
 * keeps it. That also makes the file readable as what it is — a description of a fluid — which the
 * builder chain it replaces was only incidentally.
 *
 * The textures and tint ride along for the same reason. Both loaders need exactly these four facts to
 * draw a fluid and neither can derive them, so a fluid that did not carry its own appearance would need
 * a second declaration somewhere on the client saying the other half.
 *
 * Defaults are vanilla water's, so a spec only states what it changes.
 */
data class FluidSpec(
    val name: String,
    val descriptionId: String,
    val stillTexture: ResourceLocation,
    val flowingTexture: ResourceLocation,
    val overlayTexture: ResourceLocation? = null,
    /** ARGB, as both loaders want it. */
    val tint: Int = -1,
    val lightLevel: Int = 0,
    val density: Int = 1000,
    val viscosity: Int = 1000,
    val rarity: Rarity = Rarity.COMMON,
    val canDrown: Boolean = true,
    val canConvertToSource: Boolean = false,
    val fillSound: SoundEvent = SoundEvents.BUCKET_FILL,
    val emptySound: SoundEvent = SoundEvents.BUCKET_EMPTY,
    val tickRate: Int = 5,
    val slopeFindDistance: Int = 4,
    val levelDecreasePerBlock: Int = 1,
)

/**
 * The pair every flowing fluid comes in.
 *
 * Both are needed: a tank holds the source, and the block in the world is the flowing one at every level
 * but full. The mod refers to the source almost everywhere, which is why that one is named first.
 *
 * Typed as `FlowingFluid` rather than `Fluid` because `LiquidBlock` demands one, and because both loaders
 * build these on a `FlowingFluid` subclass anyway — saying so here spares every caller a cast.
 */
class FluidPair(val source: RegistryRef<out FlowingFluid>, val flowing: RegistryRef<out FlowingFluid>)

interface FluidRegistrar {
    /**
     * [bucket] and [block] are suppliers OF references, not references, and both layers of laziness earn
     * their keep.
     *
     * They are references because the three are circular: the bucket item and the liquid block each name
     * the fluid in their own factory, and the fluid names both of them here. Nobody may be asked for a
     * value until all three are declared.
     *
     * They are suppliers of references because merely naming `ModItems.EXPERIENCE_BUCKET` initialises
     * `ModItems`, which queues every item this mod has. Declaring the fluid would then queue the items and
     * blocks ahead of it — and fluids have to be queued first, for a loader whose registries resolve
     * eagerly rather than holding the factory until an event fires. On NeoForge that ordering is not
     * load-bearing and the inversion would have gone unnoticed.
     */
    fun register(spec: FluidSpec, bucket: () -> RegistryRef<out Item>, block: () -> RegistryRef<out LiquidBlock>): FluidPair
}
