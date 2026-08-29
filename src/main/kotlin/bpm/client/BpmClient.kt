package bpm.client

import bpm.Bpm
import bpm.client.mc.SmokeRun
import bpm.client.mc.WorkbenchSession
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
    fun init(modBus: IEventBus) {
        modBus.addListener(FMLClientSetupEvent::class.java, Consumer {
            it.enqueueWork {
                net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(bpm.world.ModFluids.EXPERIENCE.get(), net.minecraft.client.renderer.RenderType.translucent())
                net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(bpm.world.ModFluids.EXPERIENCE_FLOWING.get(), net.minecraft.client.renderer.RenderType.translucent())
            }
            GeoRenderers.installMolang()
            Bpm.LOGGER.info("bpm client ready (smoke frames: {})", SmokeRun.frames)
        })
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java, Consumer(GeoRenderers::registerRenderers))
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent::class.java, Consumer { e ->
            // The glowmask survey (GlowLayer) is per resource set: forget it when packs reload.
            e.registerReloadListener(net.minecraft.server.packs.resources.ResourceManagerReloadListener { bpm.client.render.Glowmasks.invalidate() })
        })
        bpm.client.render.LinkerHud.install(modBus)
        modBus.addListener(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent::class.java, Consumer { event ->
            event.registerFluidType(ExperienceFluidLook, bpm.world.ModFluids.EXPERIENCE_TYPE.get())
        })
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post::class.java, Consumer(::onScreenInit))
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickEmpty::class.java, Consumer { event ->
            val player = event.entity
            if (player.isShiftKeyDown && bpm.chamber.ChamberDimension.isChamber(player.level())) {
                bpm.world.LinkerItem.handWith(player)?.let { ClientNet.sendLinkerTrack(it) }
            }
        })
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent::class.java, Consumer(::onRegisterClientCommands))
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java, Consumer { ClientNet.tick() })
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java, Consumer { bpm.client.fx.EffectManager.tick() })
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent::class.java, Consumer(bpm.client.fx.EffectManager::render))
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java, Consumer { ClientNet.reset(); WorkbenchSession.reset(); bpm.client.mc.BlockPreviewRenderer.clear() })
    }

    private fun onScreenInit(event: ScreenEvent.Init.Post) {
        if (event.screen is TitleScreen && SmokeRun.claim()) {
            // Not from inside the init of the screen being replaced: next tick.
            Minecraft.getInstance().tell { SmokeRun.start() }
        }
    }

    private fun onRegisterClientCommands(event: RegisterClientCommandsEvent) {
        event.dispatcher.register(
            Commands.literal("bpm").then(
                Commands.literal("editor").executes {
                    ClientHooks.openWorkbench(null)
                    1
                },
            ),
        )
    }
}

/** Liquid experience looks like water dyed the green of an orb, and glows a little. */
private object ExperienceFluidLook : net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions {
    private val STILL = net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/water_still")
    private val FLOW = net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/water_flow")
    private val OVERLAY = net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/water_overlay")

    override fun getTintColor(): Int = 0xFFA8F04A.toInt()
    override fun getStillTexture(): net.minecraft.resources.ResourceLocation = STILL
    override fun getFlowingTexture(): net.minecraft.resources.ResourceLocation = FLOW
    override fun getOverlayTexture(): net.minecraft.resources.ResourceLocation = OVERLAY
}
