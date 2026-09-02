package bpm.platform.registry

import bpm.platform.idOf

import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import bpm.platform.ResourceLocation
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import bpm.platform.keyId

/**
 * Fabric has no `DeferredRegister`: you call `Registry.register` and it happens.
 *
 * That difference is the whole reason [PlatformRegistries.installAll] exists. The mod declares its
 * blocks, items and everything else at class-initialisation time, long before any registry is open, so
 * the declarations are held as closures here and run in [installAll] — in the order they were made.
 * That order is load-bearing on this loader and not on the other: `ModBlocks.EXPERIENCE` builds a
 * `LiquidBlock` from a fluid, so the fluid must already be registered when its factory runs. NeoForge
 * would not care; here it is the difference between working and a null.
 *
 * Entity attributes go last, after every registration, because they name entity types that have to exist
 * first.
 */
class FabricRegistries : PlatformRegistries {

    private val pending = ArrayList<() -> Unit>()
    private val attributeBlocks = ArrayList<(AttributeSink) -> Unit>()

    private fun defer(block: () -> Unit) {
        pending += block
    }

    override fun blocks(namespace: String): BlockRegistrar = FabricBlockRegistrar(namespace, ::defer)

    override fun items(namespace: String): ItemRegistrar = FabricItemRegistrar(namespace, ::defer)

    override fun components(namespace: String): ComponentRegistrar = FabricComponentRegistrar(namespace, ::defer)

    override fun <T : Any> of(key: ResourceKey<Registry<T>>, namespace: String): Registrar<T> =
        FabricRegistrar(registryFor(key), namespace, ::defer)

    override fun entityAttributes(block: (AttributeSink) -> Unit) {
        attributeBlocks += block
    }

    override fun installAll() {
        for (block in pending) block()
        pending.clear()

        @Suppress("UNCHECKED_CAST")
        val sink = AttributeSink { type, attributes ->
            FabricDefaultAttributeRegistry.register(type.get() as EntityType<out LivingEntity>, attributes)
        }
        for (block in attributeBlocks) block(sink)
        attributeBlocks.clear()
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        /*
         * Through `valueOf`, not `REGISTRY.get(...)`, and the difference is not cosmetic.
         *
         * `Registry.get(ResourceLocation)` returned the value until 1.21.2 and returns an
         * `Optional<Holder.Reference<T>>` after it. Written as `get(...) as? Registry<T>` that is a legal
         * cast of an Optional to a Registry, which is to say ALWAYS NULL -- it compiles on both versions
         * and silently fails on one. This looked like a missing registry ("no registry minecraft:fluid")
         * and was a missing unwrap; the safe cast is what hid it.
         */
        fun <T : Any> registryFor(key: ResourceKey<Registry<T>>): Registry<T> =
            bpm.platform.valueOf(BuiltInRegistries.REGISTRY, key.keyId()) as? Registry<T>
                ?: error("no registry ${key.keyId()} — a loader-specific registry cannot be asked for here")
    }
}

/**
 * A reference that is empty until [FabricRegistries.installAll] fills it.
 *
 * Same contract as the NeoForge one, and the same warning applies with more force: calling [get] during
 * class initialisation gets you the error below rather than a value, because on this loader nothing has
 * been registered at that point.
 */
private class FabricRef<T : Any>(override val id: ResourceLocation) : RegistryRef<T> {
    var reference: Holder.Reference<T>? = null

    override fun get(): T = (reference ?: notYet()).value()

    override fun holder(): Holder<T> = reference ?: notYet()

    private fun notYet(): Nothing =
        error("$id was asked for before registration ran; see PlatformRegistries.installAll")
}

private open class FabricRegistrar<T : Any>(
    private val registry: Registry<T>,
    protected val namespace: String,
    protected val defer: (() -> Unit) -> Unit,
) : Registrar<T> {

    override fun <R : T> register(name: String, factory: () -> R): RegistryRef<R> =
        registerInto(registry, name, factory)

    @Suppress("UNCHECKED_CAST")
    protected fun <V : Any, R : V> registerInto(into: Registry<V>, name: String, factory: () -> R): RegistryRef<R> {
        val id = idOf(namespace, name)
        val ref = FabricRef<R>(id)
        defer { ref.reference = Registry.registerForHolder(into, id, factory()) as Holder.Reference<R> }
        return ref
    }
}

private class FabricBlockRegistrar(namespace: String, defer: (() -> Unit) -> Unit) :
    FabricRegistrar<Block>(BuiltInRegistries.BLOCK, namespace, defer), BlockRegistrar {

    override fun <B : Block> registerBlock(
        name: String,
        factory: (BlockBehaviour.Properties) -> B,
        props: BlockBehaviour.Properties,
    ): RegistryRef<B> = registerInto(BuiltInRegistries.BLOCK, name) {
        factory(blockProps(props, idOf(namespace, name)))
    }

    override fun registerSimpleBlock(name: String, props: BlockBehaviour.Properties): RegistryRef<Block> =
        registerInto(BuiltInRegistries.BLOCK, name) {
            Block(blockProps(props, idOf(namespace, name)))
        }
}

private class FabricItemRegistrar(namespace: String, defer: (() -> Unit) -> Unit) :
    FabricRegistrar<Item>(BuiltInRegistries.ITEM, namespace, defer), ItemRegistrar {

    override fun <I : Item> registerItem(
        name: String,
        factory: (Item.Properties) -> I,
        props: Item.Properties,
    ): RegistryRef<I> = registerInto(BuiltInRegistries.ITEM, name) {
        factory(itemProps(props, idOf(namespace, name)))
    }

    /**
     * The block item takes the block's own path, which is what makes `bpm:controller` the item id for
     * `bpm:controller` the block. Deferred twice over: the block must be registered before `block.get()`
     * can answer, and this closure only runs during installAll, after it has been.
     */
    override fun registerSimpleBlockItem(block: RegistryRef<out Block>): RegistryRef<BlockItem> =
        registerInto(BuiltInRegistries.ITEM, block.id.path) {
            BlockItem(block.get(), itemProps(Item.Properties(), idOf(namespace, block.id.path)))
        }
}

private class FabricComponentRegistrar(private val namespace: String, private val defer: (() -> Unit) -> Unit) :
    ComponentRegistrar {

    //? if >=1.20.5 {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> registerComponentType(
        name: String,
        configure: (ComponentBuilder<T>) -> ComponentBuilder<T>,
    ): RegistryRef<ComponentKey<T>> {
        val id = idOf(namespace, name)
        val ref = FabricRef<ComponentKey<T>>(id)
        defer {
            val type = configure(DataComponentType.builder()).build()
            ref.reference = Registry.registerForHolder(BuiltInRegistries.DATA_COMPONENT_TYPE, id, type)
                as Holder.Reference<ComponentKey<T>>
        }
        return ref
    }
    //?} else {
    /*/**
     * Below 1.20.5 there is no registry to register with: a component is a key in the stack's tag, so
     * the "registration" is simply building the key. Nothing is deferred, because nothing depends on
     * registry order -- the key is a name and a codec and is complete the moment it is asked for.
     */
    override fun <T : Any> registerComponentType(
        name: String,
        configure: (ComponentBuilder<T>) -> ComponentBuilder<T>,
    ): RegistryRef<ComponentKey<T>> {
        val id = idOf(namespace, name)
        val built = configure(ComponentBuilder())
        val codec = built.codec ?: error("component $id was registered without a persistent codec")
        return bpm.platform.registry.ReadyRef(ComponentKey(id.toString(), codec))
    }
    *///?}
}
