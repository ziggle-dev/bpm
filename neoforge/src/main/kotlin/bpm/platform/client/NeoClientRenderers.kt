package bpm.platform.client

/*
 * NeoForge's client item extensions used to be bridged here, to give four items their GeckoLib
 * renderers. They are not any more: GeckoLib does that bridging itself, from `GeoRenderProvider`, and
 * on 1.21.4 there is no `getCustomRenderer` to bridge to. See `bpm.platform.client.ClientRenderers`.
 */

/*
 * Reading a fluid's appearance back, which has to work for ANY fluid -- a tank panel shows whatever the
 * tank holds, not only this mod's own.
 *
 * Until 26.1 that is the client fluid-type extension. From 26.1 it is the baked model in the model
 * manager, which is where every fluid's sprites and tint now live, this mod's included. The sprite knows
 * its own texture name, which is what the callers want: they look it up in the block atlas, and one of
 * them opens the PNG.
 */
/*
 * The two registration events that are only a rename, named once.
 *
 * `EntityRenderersEvent.RegisterRenderers` and `RegisterKeyMappingsEvent` are the same events with the
 * same methods on both sides of 1.20.2. The HUD one below is NOT -- see [NeoHudRegistry].
 */
//? if >=1.20.2 {
private typealias RenderersEvent = net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers
private typealias KeyMappingsEvent = net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
//?} else {
/*private typealias RenderersEvent = net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers
private typealias KeyMappingsEvent = net.minecraftforge.client.event.RegisterKeyMappingsEvent
*///?}

object NeoFluidAppearance : FluidAppearance {
    //? if >=26.1 {
    /*override fun of(fluid: net.minecraft.world.level.material.Fluid): FluidLook {
        val state = fluid.defaultFluidState()
        val model = net.minecraft.client.Minecraft.getInstance().modelManager.getFluidStateModelSet().get(state)
        val still = model.stillMaterial().sprite().contents().name()
        val flowing = model.flowingMaterial().sprite().contents().name()
        // Optional: a fluid without a tint source is drawn at its texture's own colours, which is
        // what -1 means to every caller of this.
        val tint = model.tintSource()?.color(state.createLegacyBlock()) ?: -1
        return FluidLook(still, flowing, tint)
    }
    *///?} else {
    override fun of(fluid: net.minecraft.world.level.material.Fluid): FluidLook {
        //? if >=1.20.2 {
        val ext = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid)
        //?} else {
        /*val ext = net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid)
        *///?}
        val still = ext.stillTexture
        return FluidLook(still, ext.flowingTexture ?: still, ext.tintColor)
    }
    //?}
}

/** NeoForge declares renderers during `EntityRenderersEvent.RegisterRenderers`. */
object NeoRendererRegistry : RendererRegistry {
    private val pending = ArrayList<(RendererSink) -> Unit>()

    override fun renderers(block: (RendererSink) -> Unit) {
        pending += block
    }

    fun onRegisterRenderers(event: RenderersEvent) {
        val sink = object : RendererSink {
            override fun <T : net.minecraft.world.level.block.entity.BlockEntity> blockEntity(
                type: net.minecraft.world.level.block.entity.BlockEntityType<out T>,
                renderer: (net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context) -> BlockEntityRendererOf<T>,
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

    fun onRegisterKeys(event: KeyMappingsEvent) {
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
/**
 * The HUD, which is the one client registry that is not a rename.
 *
 * From 1.20.2 the HUD is a stack of LAYERS and a mod inserts one relative to another by id. On 1.20.1
 * it is a list of OVERLAYS registered by name, positioned relative to a vanilla overlay, and each is
 * handed the `ForgeGui` as well as the graphics and the frame time. The mod draws the same two things
 * either way, so only the wrapper and the event differ.
 */
//? if >=1.20.2 {
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

    /**
     * `LayeredDraw` was deleted with the GUI rewrite and NeoForge's `GuiLayer` took its place. Same two
     * arguments, same moment; only the name of the thing being handed over changed.
     */
    //? if >=1.21.6 {
    /*private fun wrap(layer: HudLayer) =
        net.neoforged.neoforge.client.gui.GuiLayer { g, delta -> layer.draw(g, bpm.platform.client.FrameDelta(delta)) }
    *///?} else {
    private fun wrap(layer: HudLayer) =
        net.minecraft.client.gui.LayeredDraw.Layer { g, delta -> layer.draw(g, bpm.platform.client.FrameDelta(delta)) }
    //?}

    fun onRegisterGuiLayers(event: net.neoforged.neoforge.client.event.RegisterGuiLayersEvent) {
        for (block in pending) block(event)
    }
}
//?} else {
/*object NeoHudRegistry : HudRegistry {
    private val pending = ArrayList<(net.minecraftforge.client.event.RegisterGuiOverlaysEvent) -> Unit>()

    override fun aboveCrosshair(id: bpm.platform.ResourceLocation, layer: HudLayer) {
        pending += { e ->
            e.registerAbove(net.minecraftforge.client.gui.overlay.VanillaGuiOverlay.CROSSHAIR.id(), id.path, wrap(layer))
        }
    }

    override fun onTop(id: bpm.platform.ResourceLocation, layer: HudLayer) {
        pending += { e -> e.registerAboveAll(id.path, wrap(layer)) }
    }

    /** The overlay is handed the gui and the screen size as well; neither is anything the mod draws by. */
    private fun wrap(layer: HudLayer) =
        net.minecraftforge.client.gui.overlay.IGuiOverlay { _, graphics, partialTick, _, _ ->
            layer.draw(graphics, bpm.platform.client.FrameDelta(partialTick))
        }

    fun onRegisterGuiLayers(event: net.minecraftforge.client.event.RegisterGuiOverlaysEvent) {
        for (block in pending) block(event)
    }
}
*///?}
