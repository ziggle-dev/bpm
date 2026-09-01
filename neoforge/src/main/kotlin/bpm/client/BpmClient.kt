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
            bpm.client.ponder.PonderCompat.install()
            Bpm.LOGGER.info("bpm client ready (smoke frames: {})", SmokeRun.frames)
        }
        modBus.addListener(EntityRenderersEvent.RegisterRenderers::class.java, Consumer(bpm.platform.client.NeoRendererRegistry::onRegisterRenderers))
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterShadersEvent::class.java, Consumer(bpm.client.render.RiftShader::register))
        bpm.platform.client.RiftLooks.install(bpm.client.render.RiftShader)
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent::class.java, Consumer(bpm.platform.client.NeoKeyRegistry::onRegisterKeys))
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent::class.java, Consumer { e ->
            // The glowmask survey (GlowLayer) is per resource set: forget it when packs reload.
            e.registerReloadListener(net.minecraft.server.packs.resources.ResourceManagerReloadListener { bpm.client.render.Glowmasks.invalidate() })
        })
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent::class.java, Consumer(bpm.platform.client.NeoHudRegistry::onRegisterGuiLayers))
        bpm.platform.client.Hud.install(bpm.platform.client.NeoHudRegistry)
        bpm.client.render.LinkerHud.install()
        bpm.platform.client.Hud.onTop(HUD_PANELS, bpm.client.mc.HudOverlay::render)
        bpm.platform.client.ClientRenderers.install(bpm.platform.client.NeoClientRenderers, bpm.platform.client.NeoRendererRegistry)
        bpm.platform.client.FluidVisuals.install(bpm.platform.client.NeoFluidAppearance)
        bpm.client.render.BpmItemRenderers.install()
        GeoRenderers.registerRenderers()
        bpm.platform.client.ClientKeys.install(bpm.platform.client.NeoKeyRegistry)
        Keys.register()
        modBus.addListener(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent::class.java, Consumer { event ->
            event.registerFluidType(
                bpm.platform.registry.NeoFluidRegistrar.looks(bpm.world.ModFluids.SPEC),
                bpm.platform.registry.NeoFluidRegistrar.type(bpm.world.ModFluids.SPEC.name).get(),
            )
            bpm.platform.client.NeoClientRenderers.onRegisterExtensions(event)
        })
        BpmEvents.screenOpened.listen(::onScreenOpened)
        BpmEvents.leftClickEmpty.listen { player ->
            if (player.isShiftKeyDown && bpm.chamber.ChamberDimension.isChamber(player.level())) {
                bpm.world.LinkerItem.handWith(player)?.let { ClientNet.sendLinkerTrack(it) }
            }
        }
        BpmEvents.registerClientCommands.listen(::onRegisterClientCommands)
        BpmEvents.clientTickEnd.listen { ClientNet.tick() }
        BpmEvents.clientTickStart.listen { Keys.tick() }
        BpmEvents.rawKey.listen(Keys::onKey)
        BpmEvents.clientTickEnd.listen { bpm.client.fx.EffectManager.tick() }
        BpmEvents.clientDisconnect.listen {
            ClientNet.reset(); WorkbenchSession.reset(); bpm.client.mc.BlockPreviewRenderer.clear(); bpm.client.mc.HudOverlay.reset(); Keys.reset()
        }

        BpmEvents.worldRenderTranslucent.listen(bpm.client.fx.EffectManager::render)
    }

    private fun onScreenOpened(screen: net.minecraft.client.gui.screens.Screen) {
        if (screen is TitleScreen && SmokeRun.claim()) {
            // Not from inside the init of the screen being replaced: next tick.
            Minecraft.getInstance().tell { SmokeRun.start() }
        }
    }

    private fun onRegisterClientCommands(event: bpm.platform.events.CommandRegistration) {
        event.dispatcher.register(
            Commands.literal("bpm")
                .then(
                    Commands.literal("editor").executes {
                        ClientHooks.openWorkbench(null)
                        1
                    },
                )
                .then(
                    // For "the pictures are blank in my modpack": says whether anything was asked for,
                    // whether it drew, and what went wrong if it threw.
                    Commands.literal("previews").executes { ctx ->
                        ctx.source.sendSuccess({ net.minecraft.network.chat.Component.literal(bpm.client.mc.BlockPreviewRenderer.diagnostics()) }, false)
                        1
                    },
                )
                // Both rift looks are built; this picks which one draws, live, with no restart.
                .then(
                    Commands.literal("rift").then(
                        Commands.argument("style", com.mojang.brigadier.arguments.StringArgumentType.word())
                            .suggests { _, builder ->
                                bpm.client.render.RiftStyle.entries.forEach { builder.suggest(it.name.lowercase()) }
                                builder.buildFuture()
                            }
                            .executes { ctx ->
                                val name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "style")
                                val picked = bpm.client.render.RiftStyle.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                                if (picked == null) {
                                    ctx.source.sendFailure(net.minecraft.network.chat.Component.literal("unknown rift style '$name' (cube, tear)"))
                                    0
                                } else {
                                    bpm.client.render.RiftRenderer.style = picked
                                    ctx.source.sendSuccess({ net.minecraft.network.chat.Component.literal("rift style: ${picked.name.lowercase()}") }, false)
                                    1
                                }
                            },
                    ),
                ),
        )
    }
}


