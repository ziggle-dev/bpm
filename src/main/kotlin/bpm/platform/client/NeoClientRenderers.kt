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

/** NeoForge keeps a fluid's appearance on its client fluid-type extensions. */
object NeoFluidAppearance : FluidAppearance {
    override fun of(fluid: net.minecraft.world.level.material.Fluid): FluidLook {
        val ext = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid)
        return FluidLook(ext.stillTexture, ext.tintColor)
    }
}

/** NeoForge declares renderers during `EntityRenderersEvent.RegisterRenderers`. */
object NeoRendererRegistry : RendererRegistry {
    private val pending = ArrayList<(RendererSink) -> Unit>()

    override fun renderers(block: (RendererSink) -> Unit) {
        pending += block
    }

    fun onRegisterRenderers(event: net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers) {
        val sink = object : RendererSink {
            override fun <T : net.minecraft.world.level.block.entity.BlockEntity> blockEntity(
                type: net.minecraft.world.level.block.entity.BlockEntityType<out T>,
                renderer: (net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context) -> net.minecraft.client.renderer.blockentity.BlockEntityRenderer<T>,
            ) = event.registerBlockEntityRenderer(type) { ctx -> renderer(ctx) }

            override fun <T : net.minecraft.world.entity.Entity> entity(
                type: net.minecraft.world.entity.EntityType<out T>,
                renderer: (net.minecraft.client.renderer.entity.EntityRendererProvider.Context) -> net.minecraft.client.renderer.entity.EntityRenderer<T>,
            ) = event.registerEntityRenderer(type) { ctx -> renderer(ctx) }
        }
        for (block in pending) block(sink)
    }
}
