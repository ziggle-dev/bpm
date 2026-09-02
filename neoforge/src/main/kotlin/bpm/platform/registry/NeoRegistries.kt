package bpm.platform.registry

import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.core.component.DataComponentType
import net.minecraft.resources.ResourceKey
import bpm.platform.ResourceLocation
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister

/**
 * NeoForge's `DeferredRegister`, behind the mod's own names.
 *
 * Everything created here is remembered so that [installAll] can hand each one to the mod bus. The bus
 * is supplied once, at [install], rather than threaded through every call — a registrar is created at
 * class-initialisation time, long before anything has a bus to give it.
 */
class NeoRegistries(private val bus: IEventBus) : PlatformRegistries {

    private val pending = ArrayList<DeferredRegister<*>>()

    private fun <T : Any> remember(reg: DeferredRegister<T>): DeferredRegister<T> = reg.also { pending += it }

    override fun blocks(namespace: String): BlockRegistrar = NeoBlockRegistrar(remember(DeferredRegister.createBlocks(namespace)) as DeferredRegister.Blocks)

    override fun items(namespace: String): ItemRegistrar = NeoItemRegistrar(remember(DeferredRegister.createItems(namespace)) as DeferredRegister.Items)

    override fun components(namespace: String): ComponentRegistrar =
        NeoComponentRegistrar(remember(DeferredRegister.createDataComponents(net.minecraft.core.registries.Registries.DATA_COMPONENT_TYPE, namespace)) as DeferredRegister.DataComponents)

    override fun <T : Any> of(key: ResourceKey<Registry<T>>, namespace: String): Registrar<T> =
        NeoRegistrar(remember(DeferredRegister.create(key, namespace)))

    @Suppress("UNCHECKED_CAST")
    override fun entityAttributes(block: (AttributeSink) -> Unit) {
        bus.addListener(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent::class.java) { event ->
            block(AttributeSink { type, attributes ->
                event.put(type.get() as net.minecraft.world.entity.EntityType<out net.minecraft.world.entity.LivingEntity>, attributes.build())
            })
        }
    }

    override fun installAll() {
        for (reg in pending) reg.register(bus)
        pending.clear()
    }
}

/** A `DeferredHolder` wearing the mod's interface. */
private class NeoRef<T : Any>(private val holder: DeferredHolder<*, T>) : RegistryRef<T> {
    override val id: ResourceLocation get() = holder.id
    override fun get(): T = holder.get()

    @Suppress("UNCHECKED_CAST")
    override fun holder(): Holder<T> = holder as Holder<T>
}

private open class NeoRegistrar<T : Any>(protected val reg: DeferredRegister<T>) : Registrar<T> {
    override fun <R : T> register(name: String, factory: () -> R): RegistryRef<R> = NeoRef(reg.register(name) { _ -> factory() })
}

private class NeoBlockRegistrar(private val blocks: DeferredRegister.Blocks) : NeoRegistrar<Block>(blocks), BlockRegistrar {
    /*
     * The properties are handed over as a SUPPLIER from 26.1 rather than as themselves.
     *
     * Deferred registration defers the properties too now, so a block's settings are built when the
     * block is, not when it is declared. The seam's callers pass a built `Properties` on every band and
     * this wraps it, which keeps them from having to care -- and is honest, because by the time these
     * are called the properties really have already been built.
     */
    override fun <B : Block> registerBlock(name: String, factory: (BlockBehaviour.Properties) -> B, props: BlockBehaviour.Properties): RegistryRef<B> =
        //? if >=26.1 {
        /*NeoRef(blocks.registerBlock(name, factory, java.util.function.Supplier { props }))
        *///?} else {
        NeoRef(blocks.registerBlock(name, factory, props))
        //?}

    override fun registerSimpleBlock(name: String, props: BlockBehaviour.Properties): RegistryRef<Block> =
        //? if >=26.1 {
        /*NeoRef(blocks.registerSimpleBlock(name, java.util.function.Supplier { props }))
        *///?} else {
        NeoRef(blocks.registerSimpleBlock(name, props))
        //?}
}

private class NeoItemRegistrar(private val items: DeferredRegister.Items) : NeoRegistrar<Item>(items), ItemRegistrar {
    override fun <I : Item> registerItem(name: String, factory: (Item.Properties) -> I, props: Item.Properties): RegistryRef<I> =
        //? if >=26.1 {
        /*NeoRef(items.registerItem(name, factory, java.util.function.Supplier { props }))
        *///?} else {
        NeoRef(items.registerItem(name, factory, props))
        //?}

    override fun registerSimpleBlockItem(block: RegistryRef<out Block>): RegistryRef<BlockItem> =
        NeoRef(items.registerSimpleBlockItem(block.id.path) { block.get() })
}

private class NeoComponentRegistrar(private val components: DeferredRegister.DataComponents) : ComponentRegistrar {
    //? if >=1.20.5 {
    override fun <T : Any> registerComponentType(
        name: String,
        configure: (ComponentBuilder<T>) -> ComponentBuilder<T>,
    ): RegistryRef<ComponentKey<T>> = NeoRef(components.registerComponentType(name) { b -> configure(b) })
    //?} else {
    /*/** See the note on the Fabric registrar: below 1.20.5 the key IS the registration. */
    override fun <T : Any> registerComponentType(
        name: String,
        configure: (ComponentBuilder<T>) -> ComponentBuilder<T>,
    ): RegistryRef<ComponentKey<T>> {
        val built = configure(ComponentBuilder())
        val codec = built.codec ?: error("component $name was registered without a persistent codec")
        return bpm.platform.registry.ReadyRef(ComponentKey(namespace + ":" + name, codec))
    }
    *///?}
}
