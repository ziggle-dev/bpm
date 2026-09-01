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
 * Lifecycle and input only. Renderer, shader, GUI-layer and render-stage registration stay in the client
 * code that uses them, because those change with the Minecraft version at least as much as with the
 * loader — putting them behind a hook now would be inventing an abstraction before knowing its shape.
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

        gameBus.addListener(InputEvent.Key::class.java, Consumer { e ->
            // InputEvent.Key cannot be cancelled, so a listener saying no cannot stop the press reaching
            // the game here. Keys clears the mapping's own down-state instead; see Keys.onKey.
            BpmEvents.rawKey.fire(RawKey(e.key, e.scanCode, e.action, e.modifiers))
        })
    }
}
