package bpm.platform.events

/*
 * The same client events under the package name they had before 1.20.2 -- see the note in
 * [NeoEventBridge]. Most differ by package alone.
 *
 * `ClientTickEvent` and `RenderFrameEvent` are the exception, and they are ABSENT rather than moved:
 * 1.20.1 has neither class. Both are phases of `TickEvent`, which is what the legacy arm imports and
 * why the registrations below branch.
 */
//? if >=1.20.2 {
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RenderFrameEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.client.event.ViewportEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
//?} else {
/*import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.event.RegisterClientCommandsEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.client.event.ViewportEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
*///?}
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

    //? if >=1.21.6 {
    /*/** The field of view NeoForge last computed, in degrees. Only read to rebuild the projection. */
    private var fov: Float = 70f
    *///?}

    fun install(modBus: IEventBus, gameBus: IEventBus) {
        modBus.addListener(FMLClientSetupEvent::class.java, Consumer { e ->
            // enqueueWork because some of what runs here touches the game's own state.
            e.enqueueWork { BpmEvents.clientSetup.fire(Unit) }
        })

        //? if >=1.21.6 {
        /*// The end of the frame, after the GUI has been painted -- see bpm.platform.client.deferGuiDraw.
        /*
         * One class with a phase on 1.20.1, separate classes without one from 1.20.2. Checking the phase
         * is not optional on the older arm: `TickEvent` fires at BOTH ends of every tick, so an
         * unchecked listener would draw the deferred GUI twice a frame.
         */
        //? if >=1.20.2 {
        gameBus.addListener(RenderFrameEvent.Post::class.java, Consumer { bpm.platform.client.drawDeferredGui() })
        //?} else {
        /*gameBus.addListener(TickEvent.RenderTickEvent::class.java, Consumer { e ->
            if (e.phase == TickEvent.Phase.END) bpm.platform.client.drawDeferredGui()
        })
        *///?}
        *///?}
        //? if >=1.20.2 {
        gameBus.addListener(ClientTickEvent.Pre::class.java, Consumer { BpmEvents.clientTickStart.fire(Unit) })
        gameBus.addListener(ClientTickEvent.Post::class.java, Consumer { BpmEvents.clientTickEnd.fire(Unit) })
        //?} else {
        /*gameBus.addListener(TickEvent.ClientTickEvent::class.java, Consumer { e ->
            when (e.phase) {
                TickEvent.Phase.START -> BpmEvents.clientTickStart.fire(Unit)
                TickEvent.Phase.END -> BpmEvents.clientTickEnd.fire(Unit)
                else -> Unit
            }
        })
        *///?}
        gameBus.addListener(ClientPlayerNetworkEvent.LoggingOut::class.java, Consumer { BpmEvents.clientDisconnect.fire(Unit) })
        gameBus.addListener(ScreenEvent.Init.Post::class.java, Consumer { BpmEvents.screenOpened.fire(it.screen) })
        gameBus.addListener(PlayerInteractEvent.LeftClickEmpty::class.java, Consumer { BpmEvents.leftClickEmpty.fire(it.entity) })
        gameBus.addListener(RegisterClientCommandsEvent::class.java, Consumer { e ->
            BpmEvents.registerClientCommands.fire(CommandRegistration(e.dispatcher, e.buildContext))
        })

        /*
         * The translucent stage of the level render.
         *
         * 1.21.9 split the one event with a `stage` field into a subclass per stage, and took three of its
         * accessors with it: there is no camera (the level render state carries one), no partial tick (the
         * game's own DeltaTracker is the same value), and no projection matrix at all -- the projection
         * lives in a GPU buffer now and is never handed back as a Matrix4f.
         *
         * The projection is rebuilt from the field of view instead, which NeoForge still reports through
         * `ViewportEvent.ComputeFov` every frame. That is exact for what this mod does with it: the only
         * consumer projects a world point onto the window, and x/w and y/w depend on the field of view and
         * the aspect ratio alone -- the near and far planes cancel.
         */
        //? if >=1.21.9 {
        /*gameBus.addListener(ViewportEvent.ComputeFov::class.java, Consumer { fov = it.fov.toFloat() })

        gameBus.addListener(RenderLevelStageEvent.AfterTranslucentBlocks::class.java, Consumer { e ->
            val pose = e.poseStack ?: return@Consumer
            val mc = net.minecraft.client.Minecraft.getInstance()
            val window = mc.window
            val projection = org.joml.Matrix4f().perspective(
                Math.toRadians(fov.toDouble()).toFloat(),
                window.width.toFloat() / window.height.toFloat().coerceAtLeast(1f),
                0.05f,
                1024f,
            )
            BpmEvents.worldRenderTranslucent.fire(
                WorldRender(
                    pose,
                    e.levelRenderState.cameraRenderState.pos,
                    projection,
                    // Read-only at 26.1. Copied rather than cast: the seam hands this to effect
                    // code that is free to multiply into it, and writing through a view the game
                    // still owns would corrupt the frame rather than fail.
                    org.joml.Matrix4f(e.modelViewMatrix),
                    bpm.platform.client.FrameDelta(mc.deltaTracker),
                )
            )
        })
        *///?} elif >=1.21.6 {
        /*// The stage field and the projection matrix both went at 1.21.6, one version before the level
        // render state arrived. So this band names the subclass like the one above it, and rebuilds the
        // projection from the field of view the same way -- but the camera and the partial tick are still
        // on the event itself, which is what makes it a third arm rather than either neighbour.
        gameBus.addListener(ViewportEvent.ComputeFov::class.java, Consumer { fov = it.fov.toFloat() })

        gameBus.addListener(RenderLevelStageEvent.AfterTranslucentBlocks::class.java, Consumer { e ->
            val window = net.minecraft.client.Minecraft.getInstance().window
            val projection = org.joml.Matrix4f().perspective(
                Math.toRadians(fov.toDouble()).toFloat(),
                window.width.toFloat() / window.height.toFloat().coerceAtLeast(1f),
                0.05f,
                1024f,
            )
            BpmEvents.worldRenderTranslucent.fire(
                WorldRender(e.poseStack, e.camera.position, projection, e.modelViewMatrix, e.partialTick)
            )
        })
        *///?} else {
        gameBus.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent::class.java, Consumer { e ->
            if (e.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return@Consumer
            BpmEvents.worldRenderTranslucent.fire(
                WorldRender(e.poseStack, e.camera.position, e.projectionMatrix, e.modelViewMatrix, e.partialTick)
            )
        })
        //?}

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
