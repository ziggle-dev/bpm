package bpm.platform.events

import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import java.util.function.Consumer

/**
 * NeoForge's client buses, wired to [BpmEvents].
 *
 * Lifecycle, input, and the one moment in the frame the mod draws the world at.
 *
 * Renderer and shader registration stay in the client code that uses them, because those change with the
 * Minecraft version at least as much as with the loader and their shape is not yet known. The
 * render-stage hook is here because its shape IS known: two callers, both wanting the same stage, both
 * asking the same five questions of it. GUI layers went the same way, into `Hud` — see `HudRegistry`.
 */
object NeoClientEventBridge {

    fun install(modBus: IEventBus, gameBus: IEventBus) {
        modBus.addListener(FMLClientSetupEvent::class.java, Consumer { e ->
            // enqueueWork because some of what runs here touches the game's own state.
            e.enqueueWork { BpmEvents.clientSetup.fire(Unit) }
        })

        gameBus.addListener(ClientTickEvent.Pre::class.java, Consumer { BpmEvents.clientTickStart.fire(Unit) })
        gameBus.addListener(ClientTickEvent.Post::class.java, Consumer { BpmEvents.clientTickEnd.fire(Unit) })
        gameBus.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java, Consumer { BpmEvents.clientDisconnect.fire(Unit) })
        gameBus.addListener(ScreenEvent.Init.Post::class.java, Consumer { BpmEvents.screenOpened.fire(it.screen) })
        gameBus.addListener(PlayerInteractEvent.LeftClickEmpty::class.java, Consumer { BpmEvents.leftClickEmpty.fire(it.entity) })
        gameBus.addListener(RegisterClientCommandsEvent::class.java, Consumer { e ->
            BpmEvents.registerClientCommands.fire(CommandRegistration(e.dispatcher, e.buildContext))
        })

        gameBus.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent::class.java, Consumer { e ->
            if (e.stage != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return@Consumer
            BpmEvents.worldRenderTranslucent.fire(
                WorldRender(e.poseStack, e.camera.position, e.projectionMatrix, e.modelViewMatrix, e.partialTick)
            )
        })

        gameBus.addListener(InputEvent.InteractionKeyMappingTriggered::class.java, Consumer { e ->
            if (!e.isUseItem) return@Consumer
            val player = net.minecraft.client.Minecraft.getInstance().player ?: return@Consumer
            if (!BpmEvents.useItemPressed.fire(player)) {
                e.isCanceled = true
                // Cancelling alone still swings the arm, which reads as "the wand did something" when it
                // did not. The veto means "something else is handling this press", so neither happens.
                e.setSwingHand(false)
            }
        })

        gameBus.addListener(InputEvent.Key::class.java, Consumer { e ->
            // InputEvent.Key cannot be cancelled, so a listener saying no cannot stop the press reaching
            // the game here. Keys clears the mapping's own down-state instead; see Keys.onKey.
            BpmEvents.rawKey.fire(RawKey(e.key, e.scanCode, e.action, e.modifiers))
        })
    }
}
