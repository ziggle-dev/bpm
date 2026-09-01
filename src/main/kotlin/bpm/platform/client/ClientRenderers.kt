package bpm.platform.client

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.world.item.Item

/**
 * Giving an item its own renderer.
 *
 * NeoForge asks the item itself, through `IClientItemExtensions#getCustomRenderer` — which is why four
 * otherwise entirely vanilla item classes each carried an `initializeClient` override naming a client
 * class. Fabric asks a registry instead (`BuiltinItemRendererRegistry`), and from 1.21.4 vanilla wants a
 * `SpecialModelRenderer` declared in a client-item JSON.
 *
 * All three are "here is an item, here is how to draw it", so that is what this says. The consequence is
 * that the item classes go back to being plain items and the client wiring lives in one client-side
 * table, which is where a reader would look for it anyway.
 */
interface ItemRendererRegistry {
    fun register(item: Item, renderer: () -> BlockEntityWithoutLevelRenderer)
}

/**
 * Where block-entity and entity renderers are declared.
 *
 * Registration, not rendering: what a renderer DOES with a PoseStack is the part that changes with the
 * Minecraft version, and none of that is here. Saying "this block entity draws with that renderer" is
 * stable, and every loader has a place to say it — NeoForge an event, Fabric two registries.
 */
interface RendererSink {
    fun <T : net.minecraft.world.level.block.entity.BlockEntity> blockEntity(
        type: net.minecraft.world.level.block.entity.BlockEntityType<out T>,
        renderer: (net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context) -> net.minecraft.client.renderer.blockentity.BlockEntityRenderer<T>,
    )

    fun <T : net.minecraft.world.entity.Entity> entity(
        type: net.minecraft.world.entity.EntityType<out T>,
        renderer: (net.minecraft.client.renderer.entity.EntityRendererProvider.Context) -> net.minecraft.client.renderer.entity.EntityRenderer<T>,
    )
}

interface RendererRegistry {
    /** [block] may be called later, when this loader is ready to hear it. */
    fun renderers(block: (RendererSink) -> Unit)
}

object ClientRenderers {
    private lateinit var items: ItemRendererRegistry
    private lateinit var renderers: RendererRegistry

    fun install(items: ItemRendererRegistry, renderers: RendererRegistry) {
        this.items = items
        this.renderers = renderers
    }

    fun item(item: Item, renderer: () -> BlockEntityWithoutLevelRenderer) = items.register(item, renderer)

    fun renderers(block: (RendererSink) -> Unit) = renderers.renderers(block)
}
