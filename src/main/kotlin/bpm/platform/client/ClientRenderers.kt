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

object ClientRenderers {
    private lateinit var backend: ItemRendererRegistry

    fun install(impl: ItemRendererRegistry) {
        backend = impl
    }

    fun item(item: Item, renderer: () -> BlockEntityWithoutLevelRenderer) = backend.register(item, renderer)
}
