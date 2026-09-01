package bpm.platform.registry

import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.core.component.DataComponentType
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import java.util.function.Supplier

/**
 * Registering things, without naming whose registry system does it.
 *
 * NeoForge defers: you hand it a factory and it calls you back during the right registry event.
 * Fabric registers eagerly, the moment you ask. Both work; what does not work is code written against
 * one of them running on the other, because `DeferredRegister` simply does not exist over there.
 *
 * The method names deliberately match NeoForge's, so that the forty-odd call sites read the same after
 * the change as before — the only difference at a call site is the type of the thing it is assigned to.
 * That is not flattery of NeoForge, it is that a rename with no semantic content should look like one.
 */

/**
 * A handle to something registered. Deferred or not, the rule is the same: **do not call [get] during
 * class initialisation**, only once registration has run.
 */
interface RegistryRef<T> : Supplier<T> {
    val id: ResourceLocation
    override fun get(): T
    fun holder(): Holder<T>
}

interface Registrar<T> {
    fun <R : T> register(name: String, factory: () -> R): RegistryRef<R>
}

interface BlockRegistrar : Registrar<Block> {
    fun <B : Block> registerBlock(name: String, factory: (BlockBehaviour.Properties) -> B, props: BlockBehaviour.Properties): RegistryRef<B>
    fun registerSimpleBlock(name: String, props: BlockBehaviour.Properties): RegistryRef<Block>
}

interface ItemRegistrar : Registrar<Item> {
    fun <I : Item> registerItem(name: String, factory: (Item.Properties) -> I, props: Item.Properties = Item.Properties()): RegistryRef<I>
    fun registerSimpleBlockItem(block: RegistryRef<out Block>): RegistryRef<BlockItem>
}

interface ComponentRegistrar {
    fun <T> registerComponentType(
        name: String,
        build: (DataComponentType.Builder<T>) -> DataComponentType.Builder<T>,
    ): RegistryRef<DataComponentType<T>>
}

interface PlatformRegistries {
    fun blocks(namespace: String): BlockRegistrar
    fun items(namespace: String): ItemRegistrar
    fun components(namespace: String): ComponentRegistrar
    fun <T> of(key: ResourceKey<Registry<T>>, namespace: String): Registrar<T>

    /**
     * Realise everything that has been asked for, in the order it was asked for.
     *
     * On NeoForge this hands each `DeferredRegister` to the mod bus. On Fabric it will do the actual
     * registering — which is why [bpm.world.BpmRegistries.install] now names fluids before blocks and
     * items: those two build a `LiquidBlock` and a `BucketItem` from a fluid at construction, and an
     * eager registry would call that factory before the fluid existed.
     */
    fun installAll()

    /**
     * Attributes for entity types, which every loader hangs off its own hook rather than off the
     * registry: NeoForge fires `EntityAttributeCreationEvent`, Fabric has `FabricDefaultAttributeRegistry`.
     *
     * [block] is handed a sink and may be called later, whenever this loader is ready to hear it.
     */
    fun entityAttributes(block: (AttributeSink) -> Unit)
}

/** Where entity attributes go. Deliberately not a map: on NeoForge these arrive during an event. */
fun interface AttributeSink {
    fun put(
        type: RegistryRef<out net.minecraft.world.entity.EntityType<*>>,
        attributes: net.minecraft.world.entity.ai.attributes.AttributeSupplier,
    )
}

/** The installed registries. See [bpm.platform.net.Net] for why this is a `lateinit` and not a lookup. */
object Registrars {
    private lateinit var backend: PlatformRegistries

    fun install(impl: PlatformRegistries) {
        backend = impl
    }

    fun blocks(namespace: String): BlockRegistrar = backend.blocks(namespace)
    fun items(namespace: String): ItemRegistrar = backend.items(namespace)
    fun components(namespace: String): ComponentRegistrar = backend.components(namespace)
    fun <T> of(key: ResourceKey<Registry<T>>, namespace: String): Registrar<T> = backend.of(key, namespace)
    fun installAll() = backend.installAll()

    fun entityAttributes(block: (AttributeSink) -> Unit) = backend.entityAttributes(block)
}

/**
 * The installed fluid registrar.
 *
 * Its own holder rather than a method on [Registrars] because fluids are the one registry where the
 * loaders disagree about the *shape* of the thing being registered, not merely about the mechanism for
 * registering it — see [FluidSpec]. Named `FluidRegistry` and not `Fluids` because
 * `bpm.platform.world.Fluids` is already the seam for what a fluid DOES in the world; this one is only
 * about bringing one into existence.
 */
object FluidRegistry {
    private lateinit var backend: FluidRegistrar

    fun install(impl: FluidRegistrar) {
        backend = impl
    }

    fun register(
        spec: FluidSpec,
        bucket: () -> RegistryRef<out net.minecraft.world.item.Item>,
        block: () -> RegistryRef<out net.minecraft.world.level.block.LiquidBlock>,
    ): FluidPair = backend.register(spec, bucket, block)
}
