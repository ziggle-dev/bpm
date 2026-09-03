package bpm.client

import bpm.Bpm
import bpm.client.mc.SmokeRun
import bpm.client.mc.WorkbenchSession
import bpm.platform.events.BpmEvents
import bpm.client.net.ClientNet
import bpm.client.render.GeoRenderers
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.commands.Commands
import java.util.function.Consumer

//? if >=1.20.2 {
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge

private typealias RenderersEvent = net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers
private typealias ShadersEvent = net.neoforged.neoforge.client.event.RegisterShadersEvent
private typealias KeyMappingsEvent = net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
private typealias ReloadListenersEvent = net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
private typealias HudEvent = net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
//?} else {
/*import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.client.event.RegisterClientCommandsEvent
import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.common.MinecraftForge as NeoForge

private typealias RenderersEvent = net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers
private typealias ShadersEvent = net.minecraftforge.client.event.RegisterShadersEvent
private typealias KeyMappingsEvent = net.minecraftforge.client.event.RegisterKeyMappingsEvent
private typealias ReloadListenersEvent = net.minecraftforge.client.event.RegisterClientReloadListenersEvent
private typealias HudEvent = net.minecraftforge.client.event.RegisterGuiOverlaysEvent
*///?}

/** See the note on the same helper in `NeoEventBridge`: the two buses name an event differently. */
//? if >=1.20.2 {
private fun <T : net.neoforged.bus.api.Event> IEventBus.on(type: Class<T>, handler: Consumer<T>) = addListener(type, handler)
//?} else {
/*private fun <T : net.minecraftforge.eventbus.api.Event> IEventBus.on(type: Class<T>, handler: Consumer<T>) =
    addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false, type, handler)
*///?}

/**
 * Client-side wiring, registered explicitly from the mod entry so that nothing here depends on how a
 * language provider scans annotations. Only ever loaded on the client — see `Bpm`.
 */
object BpmClient {
    /** The panel readout, drawn last so it sits over everything else on the HUD. */
    private val HUD_PANELS = bpm.platform.idOf(bpm.Bpm.ID, "panels")

    fun init(modBus: IEventBus) {
        bpm.platform.events.NeoClientEventBridge.install(modBus, NeoForge.EVENT_BUS)
        BpmEvents.clientSetup.listen {
            bpm.platform.client.drawFluidTranslucent(bpm.world.ModFluids.EXPERIENCE.get())
            bpm.platform.client.drawFluidTranslucent(bpm.world.ModFluids.EXPERIENCE_FLOWING.get())
            GeoRenderers.installMolang()
            /*
             * Ponder scenes exist only where Create's Ponder does, which today is 1.21.1. The build
             * excludes the `bpm.client.ponder` package from every other node, so this call has to go with it --
             * an excluded source file is not a missing dependency, it is a file that is not there.
             */
            //? if >=1.21 <1.21.2 {
            bpm.client.ponder.PonderCompat.install()
            //?}
            Bpm.LOGGER.info("bpm client ready (smoke frames: {})", SmokeRun.frames)
        }
        modBus.on(RenderersEvent::class.java, Consumer(bpm.platform.client.NeoRendererRegistry::onRegisterRenderers))
        //? if >=1.21.9 {
        /*modBus.on(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent::class.java, Consumer { e ->
            bpm.client.render.RiftShader.register(e)
            // The mod's own flat-colour and translucent pipelines, which the beams and the monitor screen draw with.
            for (pipeline in bpm.platform.client.BpmPipelines.all) e.registerPipeline(pipeline)
        })
        *///?} elif >=1.21.5 {
        /*// Nothing to register on this band. Core shaders are gone -- the rift is two RenderPipelines --
        // and RegisterRenderPipelinesEvent does not exist yet, because NeoForge added it with the 1.21.9
        // render-type rework. The pipelines compile on first use instead, which is what the rift and the
        // beams already rely on for their whole life on Fabric.
        *///?} else {
        modBus.on(ShadersEvent::class.java, Consumer(bpm.client.render.RiftShader::register))
        //?}
        bpm.platform.client.RiftLooks.install(bpm.client.render.RiftShader)
        modBus.on(KeyMappingsEvent::class.java, Consumer(bpm.platform.client.NeoKeyRegistry::onRegisterKeys))
        /*
         * The glowmask survey (GlowLayer) is per resource set: forget it when packs reload.
         *
         * `RegisterClientReloadListenersEvent` became `AddClientReloadListenersEvent` at 1.21.2, and the
         * listener now needs a NAME -- the event sorts listeners into a dependency graph rather than a
         * list, so every one has to be identifiable. Same moment, same listener.
         */
        //? if >=1.21.2 {
        /*modBus.on(net.neoforged.neoforge.client.event.AddClientReloadListenersEvent::class.java, Consumer { e ->
            e.addListener(
                bpm.platform.idOf(Bpm.ID, "glowmasks"),
                net.minecraft.server.packs.resources.ResourceManagerReloadListener { bpm.client.render.Glowmasks.invalidate() },
            )
        })
        *///?} else {
        modBus.on(ReloadListenersEvent::class.java, Consumer { e ->
            e.registerReloadListener(net.minecraft.server.packs.resources.ResourceManagerReloadListener { bpm.client.render.Glowmasks.invalidate() })
        })
        //?}
        modBus.on(HudEvent::class.java, Consumer(bpm.platform.client.NeoHudRegistry::onRegisterGuiLayers))
        bpm.platform.client.Hud.install(bpm.platform.client.NeoHudRegistry)
        bpm.client.render.LinkerHud.install()
        bpm.platform.client.Hud.onTop(HUD_PANELS, bpm.client.mc.HudOverlay::render)
        bpm.platform.client.ClientRenderers.install(bpm.platform.client.NeoRendererRegistry)
        bpm.platform.client.FluidVisuals.install(bpm.platform.client.NeoFluidAppearance)
        GeoRenderers.registerRenderers()
        bpm.platform.client.ClientKeys.install(bpm.platform.client.NeoKeyRegistry)
        Keys.register()
        //? if >=26.1 {
        /*modBus.on(net.neoforged.neoforge.client.event.RegisterFluidModelsEvent::class.java, Consumer { event ->
            bpm.platform.registry.NeoFluidRegistrar.models(
                event,
                bpm.world.ModFluids.SPEC,
                bpm.world.ModFluids.EXPERIENCE.get(),
                bpm.world.ModFluids.EXPERIENCE_FLOWING.get(),
            )
        })
        *///?} elif >=1.20.2 {
        modBus.on(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent::class.java, Consumer { event ->
            event.registerFluidType(
                bpm.platform.registry.NeoFluidRegistrar.looks(bpm.world.ModFluids.SPEC),
                bpm.platform.registry.NeoFluidRegistrar.type(bpm.world.ModFluids.SPEC.name).get(),
            )
        })
        //?} else {
        /*// Nothing to register: the fluid type answers `initializeClient` itself on this band.
        *///?}
        bpm.client.ClientBehaviour.install()
    }
}
