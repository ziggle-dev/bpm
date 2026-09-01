package bpm.platform.client

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.world.item.Item
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent

/**
 * NeoForge's client item extensions.
 *
 * Registrations are collected as they arrive and replayed when the event fires, because the event is
 * not open when the mod is wiring itself up. The renderer is built lazily inside the extension, as it
 * was before: constructing a GeckoLib renderer during mod init is too early.
 */
object NeoClientRenderers : ItemRendererRegistry {

    private val pending = ArrayList<Pair<Item, () -> BlockEntityWithoutLevelRenderer>>()

    override fun register(item: Item, renderer: () -> BlockEntityWithoutLevelRenderer) {
        pending += item to renderer
    }

    fun onRegisterExtensions(event: RegisterClientExtensionsEvent) {
        for ((item, factory) in pending) {
            event.registerItem(
                object : IClientItemExtensions {
                    private val renderer by lazy { factory() }
                    override fun getCustomRenderer(): BlockEntityWithoutLevelRenderer = renderer
                },
                item,
            )
        }
    }
}
