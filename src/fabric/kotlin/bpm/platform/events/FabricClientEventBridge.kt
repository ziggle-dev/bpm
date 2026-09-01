package bpm.platform.events

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.Minecraft

/**
 * Fabric's client callbacks, wired to [BpmEvents].
 *
 * `WorldRenderEvents.AFTER_TRANSLUCENT` is the same moment NeoForge's
 * `RenderLevelStageEvent.AFTER_TRANSLUCENT_BLOCKS` names — and here it is one callback rather than a
 * dozen stages to filter, which is exactly why the seam names the stage in the hook rather than in a
 * payload field.
 *
 * The context carries the matrices directly, so nothing has to be reconstructed.
 *
 * Not wired, and each needs a mixin rather than being an oversight:
 * - `clientSetup`: fired from the entry point instead, which is the same moment on this loader.
 * - `screenOpened`: no callback; wants `Minecraft#setScreen`.
 * - `leftClickEmpty`: no callback; wants `Minecraft#startAttack`.
 * - `rawKey`: no callback; wants `KeyboardHandler#keyPress`.
 * - `useItemPressed`: no callback; wants `Minecraft#startUseItem`.
 */
@Environment(EnvType.CLIENT)
object FabricClientEventBridge {

    fun install() {
        ClientTickEvents.START_CLIENT_TICK.register { BpmEvents.clientTickStart.fire(Unit) }
        ClientTickEvents.END_CLIENT_TICK.register { BpmEvents.clientTickEnd.fire(Unit) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> BpmEvents.clientDisconnect.fire(Unit) }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, registry ->
            // The client dispatcher is typed on FabricClientCommandSource, but the seam and every command
            // this mod registers are written against the server's CommandSourceStack. Only server-side
            // commands exist today, so nothing is lost by not bridging this one yet.
            // FABRIC TODO: bridge if a client-only command is ever added.
        }

        WorldRenderEvents.AFTER_TRANSLUCENT.register { ctx ->
            BpmEvents.worldRenderTranslucent.fire(
                WorldRender(
                    ctx.matrixStack()!!,
                    ctx.camera().position,
                    ctx.projectionMatrix(),
                    org.joml.Matrix4f(ctx.matrixStack()!!.last().pose()),
                    ctx.tickCounter(),
                ),
            )
        }

        ClientLifecycleEvents.CLIENT_STARTED.register { _: Minecraft -> }
    }
}
