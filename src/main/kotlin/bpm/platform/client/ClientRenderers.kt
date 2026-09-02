package bpm.platform.client

/*
 * Item renderers used to be here, as an `ItemRendererSink` this mod owned.
 *
 * They are not any more, and the reason is worth recording. The three routes looked irreconcilable —
 * NeoForge asks the item through `IClientItemExtensions#getCustomRenderer`, Fabric asks
 * `BuiltinItemRendererRegistry`, and from 1.21.4 vanilla deleted `BlockEntityWithoutLevelRenderer`
 * outright in favour of a `SpecialModelRenderer` named from a client-item JSON. Three mechanisms, one of
 * which does not even take a renderer object.
 *
 * But every item this mod draws that way is a GeckoLib item, and GeckoLib already reconciles all three:
 * `GeoRenderProvider` is its own answer, present under that exact name on both loaders and on both sides
 * of the 1.21.4 break, and it is what GeckoLib's NeoForge bridge and its 1.21.4 special-renderer mixin
 * each consume. A seam here would have been a second abstraction over the first, and would have had to
 * follow the `BlockEntityWithoutLevelRenderer` deletion for nothing.
 *
 * So the mod says which renderer an item wants in one client-side table — see
 * [bpm.client.render.BpmItemRenderers] — and GeckoLib carries it the rest of the way. Deleted, not
 * ported, which is the better outcome of the two.
 */

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
        renderer: (net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context) -> BlockEntityRendererOf<T>,
    )

    fun <T : net.minecraft.world.entity.Entity> entity(
        type: net.minecraft.world.entity.EntityType<out T>,
        renderer: (net.minecraft.client.renderer.entity.EntityRendererProvider.Context) -> EntityRendererOf<T>,
    )
}

interface RendererRegistry {
    /** [block] may be called later, when this loader is ready to hear it. */
    fun renderers(block: (RendererSink) -> Unit)
}

object ClientRenderers {
    private lateinit var renderers: RendererRegistry

    fun install(renderers: RendererRegistry) {
        this.renderers = renderers
    }

    fun renderers(block: (RendererSink) -> Unit) = renderers.renderers(block)
}

/**
 * Registering a key binding.
 *
 * NeoForge fires an event and offers a `KeyConflictContext`; Fabric has `KeyBindingHelper` and no
 * conflict context at all. The context is not modelled here for that reason — the binding this mod owns
 * is a modifier key that already refuses to fire while a screen is open, which is what the context was
 * buying.
 */
interface KeyRegistry {
    /** [block] may be called later, when this loader is ready to hear it. */
    fun keys(block: ((net.minecraft.client.KeyMapping) -> Unit) -> Unit)
}

object ClientKeys {
    private lateinit var backend: KeyRegistry

    fun install(impl: KeyRegistry) {
        backend = impl
    }

    fun register(block: ((net.minecraft.client.KeyMapping) -> Unit) -> Unit) = backend.keys(block)
}

/**
 * Something drawn over the game, under any screen that happens to be open.
 *
 * Deliberately shaped as vanilla's own HUD layer — a `GuiGraphics` and a `DeltaTracker` — because that
 * is the shape both loaders and every band from 1.21.1 to 26.2 agree on. `GuiGraphics` is not abstracted
 * here on purpose: the handful of methods this mod calls on it survive the whole ladder, and wrapping it
 * would cost a translation layer to buy nothing.
 */
fun interface HudLayer {
    fun draw(g: net.minecraft.client.gui.GuiGraphics, delta: net.minecraft.client.DeltaTracker)
}

/**
 * Where a HUD layer is declared.
 *
 * The mod used to draw its HUD two different ways: the linker's overlay through NeoForge's
 * `RegisterGuiLayersEvent`, and the panel readout through `RenderGuiEvent.Post`. Two mechanisms for one
 * job is a maintenance cost at one version and a porting cost at seven — and `RenderGuiEvent` has no
 * Fabric analogue at all, while a layer registration maps straight onto `HudRenderCallback`.
 *
 * So there is one mechanism now, with two positions, which is the only distinction the two callers
 * actually needed: the linker's world-space labels want to sit just above the crosshair, and the panel
 * readout wants to be last.
 */
interface HudRegistry {
    fun aboveCrosshair(id: bpm.platform.ResourceLocation, layer: HudLayer)
    fun onTop(id: bpm.platform.ResourceLocation, layer: HudLayer)
}

object Hud {
    private lateinit var backend: HudRegistry

    fun install(impl: HudRegistry) {
        backend = impl
    }

    fun aboveCrosshair(id: bpm.platform.ResourceLocation, layer: HudLayer) = backend.aboveCrosshair(id, layer)

    fun onTop(id: bpm.platform.ResourceLocation, layer: HudLayer) = backend.onTop(id, layer)
}
