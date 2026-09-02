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
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.function.Consumer

/**
 * Client-side wiring, registered explicitly from the mod entry so that nothing here depends on how a
 * language provider scans annotations. Only ever loaded on the client — see `Bpm`.
 */
object BpmClient {
    /** The panel readout, drawn last so it sits over everything else on the HUD. */
    private val HUD_PANELS = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(bpm.Bpm.ID, "panels")

    fun init(modBus: IEventBus) {
        bpm.platform.events.NeoClientEventBridge.install(modBus, NeoForge.EVENT_BUS)
        BpmEvents.clientSetup.listen {
            net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(bpm.world.ModFluids.EXPERIENCE.get(), net.minecraft.client.renderer.RenderType.translucent())
            net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(bpm.world.ModFluids.EXPERIENCE_FLOWING.get(), net.minecraft.client.renderer.RenderType.translucent())
            GeoRenderers.installMolang()
            /*
             * Ponder scenes exist only where Create's Ponder does, which today is 1.21.1. The build
             * excludes the `bpm.client.ponder` package from every other node, so this call has to go with it --
             * an excluded source file is not a missing dependency, it is a file that is not there.
             */
            //? if <1.21.2 {
            bpm.client.ponder.PonderCompat.install()
            //?}
            Bpm.LOGGER.info("bpm client ready (smoke frames: {})", SmokeRun.frames)
        }
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java, Consumer(bpm.platform.client.NeoRendererRegistry::onRegisterRenderers))
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterShadersEvent::class.java, Consumer(bpm.client.render.RiftShader::register))
        bpm.platform.client.RiftLooks.install(bpm.client.render.RiftShader)
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent::class.java, Consumer(bpm.platform.client.NeoKeyRegistry::onRegisterKeys))
        /*
         * The glowmask survey (GlowLayer) is per resource set: forget it when packs reload.
         *
         * `RegisterClientReloadListenersEvent` became `AddClientReloadListenersEvent` at 1.21.2, and the
         * listener now needs a NAME -- the event sorts listeners into a dependency graph rather than a
         * list, so every one has to be identifiable. Same moment, same listener.
         */
        //? if >=1.21.2 {
        /*modBus.addListener(net.neoforged.neoforge.client.event.AddClientReloadListenersEvent::class.java, Consumer { e ->
            e.addListener(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Bpm.ID, "glowmasks"),
                net.minecraft.server.packs.resources.ResourceManagerReloadListener { bpm.client.render.Glowmasks.invalidate() },
            )
        })
        *///?} else {
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent::class.java, Consumer { e ->
            e.registerReloadListener(net.minecraft.server.packs.resources.ResourceManagerReloadListener { bpm.client.render.Glowmasks.invalidate() })
        })
        //?}
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent::class.java, Consumer(bpm.platform.client.NeoHudRegistry::onRegisterGuiLayers))
        bpm.platform.client.Hud.install(bpm.platform.client.NeoHudRegistry)
        bpm.client.render.LinkerHud.install()
        bpm.platform.client.Hud.onTop(HUD_PANELS, bpm.client.mc.HudOverlay::render)
        bpm.platform.client.ClientRenderers.install(bpm.platform.client.NeoRendererRegistry)
        bpm.platform.client.FluidVisuals.install(bpm.platform.client.NeoFluidAppearance)
        GeoRenderers.registerRenderers()
        bpm.platform.client.ClientKeys.install(bpm.platform.client.NeoKeyRegistry)
        Keys.register()
        modBus.addListener(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent::class.java, Consumer { event ->
            event.registerFluidType(
                bpm.platform.registry.NeoFluidRegistrar.looks(bpm.world.ModFluids.SPEC),
                bpm.platform.registry.NeoFluidRegistrar.type(bpm.world.ModFluids.SPEC.name).get(),
            )
        })
        bpm.client.ClientBehaviour.install()
    }
}
