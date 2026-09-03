package bpm.platform.events

import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
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

        //? if >=26.1 {
        /*// Same moment as the arm below, one package over: `rendering.v1.world` became `rendering.v1.level`
        // and `WorldRenderEvents` became `LevelRenderEvents`, matching the game's own rename of world to
        // level. `matrices()` is `poseStack()` here.
        //
        // The projection comes back for free on this band. 1.21.9 hid it in a GPU uniform and left the arm
        // below to rebuild it from the camera's near plane; 26.1 puts the real `projectionMatrix` on
        // `CameraRenderState`, along with the camera position, so the reconstruction is not just
        // unnecessary here, it would be less accurate than what is being handed over.
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.END_MAIN.register { ctx ->
            val pose = ctx.poseStack()
            val camera = ctx.levelState().cameraRenderState
            BpmEvents.worldRenderTranslucent.fire(
                WorldRender(
                    pose,
                    camera.pos,
                    org.joml.Matrix4f(camera.projectionMatrix),
                    org.joml.Matrix4f(pose.last().pose()),
                    bpm.platform.client.FrameDelta(Minecraft.getInstance().deltaTracker),
                ),
            )
        }
        *///?} elif >=1.21.9 <26.1 {
        /*// `AFTER_TRANSLUCENT` is gone with the rest of the old world-render callbacks; `END_MAIN` is the
        // same moment under the new names -- the end of the main pass, after translucent terrain, which is
        // what NeoForge still calls AfterTranslucentBlocks.
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.END_MAIN.register { ctx ->
            val mc = Minecraft.getInstance()
            val pose = ctx.matrices()
            val camera = mc.gameRenderer.mainCamera
            BpmEvents.worldRenderTranslucent.fire(
                WorldRender(
                    pose,
                    camera.position(),
                    projectionOf(mc, camera),
                    org.joml.Matrix4f(pose.last().pose()),
                    bpm.platform.client.FrameDelta(mc.deltaTracker),
                ),
            )
        }
        *///?} else {
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_TRANSLUCENT.register { ctx ->
            BpmEvents.worldRenderTranslucent.fire(
                WorldRender(
                    ctx.matrixStack()!!,
                    ctx.camera().position,
                    ctx.projectionMatrix(),
                    org.joml.Matrix4f(ctx.matrixStack()!!.last().pose()),
                    //? if >=1.20.5 {
                    bpm.platform.client.FrameDelta(ctx.tickCounter()),
                    //?} else {
                    /*// A bare float rather than a DeltaTracker; the seam holds either.
                    bpm.platform.client.FrameDelta(ctx.tickDelta()),
                    *///?}
                ),
            )
        }
        //?}

        ClientLifecycleEvents.CLIENT_STARTED.register { _: Minecraft -> }
    }

    //? if >=1.21.9 <26.1 {
    /*/**
     * The frame's projection, recovered from the camera.
     *
     * One band only: 26.1 hands the matrix back on `CameraRenderState` and this is not compiled there.
     *
     * From 1.21.9 the projection lives in a GPU uniform buffer and is never handed back as a matrix, and
     * Fabric's render context does not carry one either. The camera's near plane does carry it: the
     * plane's half-extents over its distance from the eye ARE the tangent of the field of view and the
     * aspect ratio, which is the whole of a perspective projection. Deriving it is exact, where reading
     * the FOV setting would miss every modifier the game applies for sprinting, speed and the spyglass.
     *
     * Only one thing consumes this -- LinkerHud projecting a world point onto the window -- and there x/w
     * and y/w depend on the field of view and the aspect ratio alone, so the near and far planes cancel.
     */
    private fun projectionOf(mc: Minecraft, camera: net.minecraft.client.Camera): org.joml.Matrix4f {
        val plane = camera.nearPlane
        val topLeft = plane.topLeft
        val halfHeight = topLeft.subtract(plane.bottomLeft).length() / 2.0
        val halfWidth = plane.topRight.subtract(topLeft).length() / 2.0
        val near = topLeft.add(plane.bottomRight).scale(0.5).length()
        return org.joml.Matrix4f().perspective(
            (2.0 * kotlin.math.atan2(halfHeight, near)).toFloat(),
            (halfWidth / halfHeight).toFloat(),
            near.toFloat(),
            mc.gameRenderer.depthFar,
        )
    }
    *///?}
}
