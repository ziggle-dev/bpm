package bpm.platform.client

/*
 * NeoForge's client item extensions used to be bridged here, to give four items their GeckoLib
 * renderers. They are not any more: GeckoLib does that bridging itself, from `GeoRenderProvider`, and
 * on 1.21.4 there is no `getCustomRenderer` to bridge to. See `bpm.platform.client.ClientRenderers`.
 */

/** NeoForge keeps a fluid's appearance on its client fluid-type extensions. */
object NeoFluidAppearance : FluidAppearance {
    override fun of(fluid: net.minecraft.world.level.material.Fluid): FluidLook {
        val ext = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid)
        val still = ext.stillTexture
        return FluidLook(still, ext.flowingTexture ?: still, ext.tintColor)
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
                renderer: (net.minecraft.client.renderer.entity.EntityRendererProvider.Context) -> EntityRendererOf<T>,
            ) = event.registerEntityRenderer(type) { ctx -> renderer(ctx) }
        }
        for (block in pending) block(sink)
    }
}

/** NeoForge registers key bindings during `RegisterKeyMappingsEvent`. */
object NeoKeyRegistry : KeyRegistry {
    private val pending = ArrayList<((net.minecraft.client.KeyMapping) -> Unit) -> Unit>()

    override fun keys(block: ((net.minecraft.client.KeyMapping) -> Unit) -> Unit) {
        pending += block
    }

    fun onRegisterKeys(event: net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent) {
        for (block in pending) block { mapping -> event.register(mapping) }
    }
}

/**
 * NeoForge's GUI layers.
 *
 * `registerAboveAll` is what the panel readout used to get from a `RenderGuiEvent.Post` listener. The two
 * are not quite the same object but they are the same moment — both hang off `Gui.render`, after the
 * vanilla layers — so the readout draws where it always did, and now through the same mechanism as the
 * linker overlay rather than a second one.
 */
object NeoHudRegistry : HudRegistry {
    private val pending = ArrayList<(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent) -> Unit>()

    override fun aboveCrosshair(id: bpm.platform.ResourceLocation, layer: HudLayer) {
        pending += { e ->
            e.registerAbove(net.neoforged.neoforge.client.gui.VanillaGuiLayers.CROSSHAIR, id, wrap(layer))
        }
    }

    override fun onTop(id: bpm.platform.ResourceLocation, layer: HudLayer) {
        pending += { e -> e.registerAboveAll(id, wrap(layer)) }
    }

    private fun wrap(layer: HudLayer) =
        net.minecraft.client.gui.LayeredDraw.Layer { g, delta -> layer.draw(g, delta) }

    fun onRegisterGuiLayers(event: net.neoforged.neoforge.client.event.RegisterGuiLayersEvent) {
        for (block in pending) block(event)
    }
}
