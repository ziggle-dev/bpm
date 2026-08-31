package bpm.client.render

import bpm.Bpm
import bpm.chamber.ChamberDimension
import bpm.client.mc.LinkRenameScreen
import bpm.world.ControllerBlockEntity
import bpm.world.LinkerItem
import bpm.world.items.WardenVisorItem
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.LayeredDraw
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.common.NeoForge
import org.joml.Matrix4f
import org.joml.Vector2f
import org.joml.Vector4f
import java.util.function.Consumer

/**
 * What a player holding the Quantum Linker sees: the bound controller's links outlined in the world with a
 * line to each, the looked-at face marked green (linkable, in range) or red (too far), and a line under the
 * crosshair naming the controller and what the next use would do. With the Warden's Visor on, the lines
 * show through walls.
 */
object LinkerHud {
    private val LAYER = ResourceLocation.fromNamespaceAndPath(Bpm.ID, "linker")
    private val TEAL = floatArrayOf(0.30f, 1.00f, 0.85f)
    private val GREEN = floatArrayOf(0.35f, 0.95f, 0.45f)
    private val RED = floatArrayOf(0.95f, 0.35f, 0.35f)
    private val AMBER = floatArrayOf(1.00f, 0.72f, 0.30f)

    /** Presence links are orchid, as they are in the panel — a person reads apart from a chest at a glance. */
    private val ORCHID = floatArrayOf(0.79f, 0.37f, 0.65f)
    private const val LABEL_RANGE = 48.0

    fun install(modBus: IEventBus) {
        modBus.addListener(RegisterGuiLayersEvent::class.java, Consumer { it.registerAbove(VanillaGuiLayers.CROSSHAIR, LAYER, LayeredDraw.Layer(::drawHud)) })
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent::class.java, Consumer(::renderWorld))
        NeoForge.EVENT_BUS.addListener(InputEvent.InteractionKeyMappingTriggered::class.java, Consumer(::onInteract))
    }

    private fun linkerIn(player: Player): net.minecraft.world.item.ItemStack? =
        listOf(player.mainHandItem, player.offhandItem).firstOrNull { it.item is LinkerItem }

    private fun controller(player: Player): ControllerBlockEntity? {
        val stack = linkerIn(player) ?: return null
        val pos = LinkerItem.selectedPos(stack) ?: return null
        return player.level().getBlockEntity(pos) as? ControllerBlockEntity
    }

    /** The link under the crosshair — a face of a linked block, or a link whose block is gone along the line of sight. */
    private fun aimedLink(mc: Minecraft, player: Player, be: ControllerBlockEntity): bpm.world.Link? {
        val hit = mc.hitResult as? BlockHitResult
        return if (hit != null && hit.type == HitResult.Type.BLOCK) {
            be.links.at(hit.blockPos, hit.direction) ?: be.links.at(hit.blockPos, null) ?: be.links.all.firstOrNull { it.pos == hit.blockPos }
        } else {
            LinkerItem.linkAhead(player.level(), player, be)
        }
    }

    /** Ctrl + use on a link opens the rename box in place of the wand's own use. */
    private fun onInteract(event: InputEvent.InteractionKeyMappingTriggered) {
        if (!event.isUseItem || !Screen.hasControlDown()) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (linkerIn(player) == null || ChamberDimension.isChamber(player.level())) return
        val be = controller(player) ?: return
        val link = aimedLink(mc, player, be) ?: return
        event.isCanceled = true
        event.setSwingHand(false)
        LinkRenameScreen.open(be.blockPos, link, be.links.names)
    }

    // ---- the frame's camera, for hanging a screen box over a world point ------------------------------

    private class View(val projection: Matrix4f, val modelView: Matrix4f, val eye: Vec3)

    private var view: View? = null

    /** Where [point] falls on the window (screen units), or null when it is behind the camera or no frame has been drawn yet. */
    fun screenPointOf(point: Vec3): Vector2f? {
        val v = view ?: return null
        val clip = Vector4f((point.x - v.eye.x).toFloat(), (point.y - v.eye.y).toFloat(), (point.z - v.eye.z).toFloat(), 1f)
            .mul(v.modelView).mul(v.projection)
        if (clip.w <= 1e-4f) return null
        val w = Minecraft.getInstance().window
        return Vector2f((clip.x / clip.w * 0.5f + 0.5f) * w.screenWidth, (0.5f - clip.y / clip.w * 0.5f) * w.screenHeight)
    }

    // ---- the world ------------------------------------------------------------------------------------

    private fun renderWorld(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        view = View(Matrix4f(event.projectionMatrix), Matrix4f(event.modelViewMatrix), event.camera.position)
        val be = controller(player) ?: return
        val cam = event.camera.position
        val pose = event.poseStack
        val throughWalls = WardenVisorItem.worn(player)
        val range = LinkerItem.reach(be, player)

        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.lineWidth(2f)
        if (throughWalls) RenderSystem.disableDepthTest() else RenderSystem.enableDepthTest()
        RenderSystem.disableCull()
        val builder = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL)
        pose.pushPose()
        pose.translate(-cam.x, -cam.y, -cam.z)

        val centre = Vec3.atCenterOf(be.blockPos)
        // A presence link has no block to outline: it follows the person, so draw the thread to where they
        // actually are and box them, not the patch of ground they were last seen on.
        for (link in be.links.presence) {
            val who = tethered(player, link) ?: continue
            box(pose, builder, who.boundingBox.inflate(0.08), ORCHID, 0.9f)
            line(pose, builder, centre, who.position().add(0.0, who.bbHeight * 0.5, 0.0), ORCHID, 0.6f)
        }
        for (link in be.links.blocks) {
            // A link whose block is gone is drawn amber: aim at it and sneak-use to unlink.
            val colour = if (player.level().getBlockState(link.pos).isAir) AMBER else TEAL
            box(pose, builder, AABB(link.pos).inflate(0.02), colour, 0.9f)
            link.side?.let { face ->
                val fb = AABB(link.pos)
                val f = faceBox(fb, face).inflate(0.03)
                box(pose, builder, f, colour, 1f)
            }
            line(pose, builder, centre, Vec3.atCenterOf(link.pos), colour, 0.6f)
        }
        // The looked-at block: green when the next use links it, red when it is out of reach.
        val hit = mc.hitResult as? BlockHitResult
        if (hit != null && hit.type == HitResult.Type.BLOCK && hit.blockPos != be.blockPos) {
            val ok = hit.blockPos.closerThan(be.blockPos, range)
            box(pose, builder, faceBox(AABB(hit.blockPos), hit.direction).inflate(0.01), if (ok) GREEN else RED, 1f)
        } else {
            LinkerItem.linkAhead(player.level(), player, be)?.let { ahead ->
                box(pose, builder, AABB(ahead.pos).inflate(0.05), if (player.isShiftKeyDown) RED else AMBER, 1f)
            }
        }
        pose.popPose()
        // Nothing to draw (no links, nothing aimed at) leaves the builder empty; `buildOrThrow` would throw on that.
        builder.build()?.let { BufferUploader.drawWithShader(it) }
        RenderSystem.enableCull()
        RenderSystem.enableDepthTest()
        RenderSystem.lineWidth(1f)
        labels(mc, player, be, cam, pose)
    }

    /** Each link's name (and face) as a sign over its block — amber when the block is gone. */
    private fun labels(mc: Minecraft, player: Player, be: ControllerBlockEntity, cam: Vec3, pose: PoseStack) {
        val buffers = mc.renderBuffers().bufferSource()
        val font = mc.font
        val orientation = mc.entityRenderDispatcher.cameraOrientation()
        val bg = (mc.options.getBackgroundOpacity(0.25f) * 255).toInt() shl 24
        for (link in be.links.presence) {
            val who = tethered(player, link) ?: continue
            if (who.position().distanceToSqr(cam) > LABEL_RANGE * LABEL_RANGE) continue
            pose.pushPose()
            pose.translate(who.x - cam.x, who.y + who.bbHeight + 0.5 - cam.y, who.z - cam.z)
            pose.mulPose(orientation)
            pose.scale(-0.025f, -0.025f, 0.025f)
            val m = pose.last().pose()
            val x = -font.width(link.name) / 2f
            font.drawInBatch(link.name, x, 0f, 0x20FFFFFF, false, m, buffers, Font.DisplayMode.SEE_THROUGH, bg, LightTexture.FULL_BRIGHT)
            font.drawInBatch(link.name, x, 0f, 0xFFF0A3D6.toInt(), false, m, buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT)
            pose.popPose()
        }
        for (link in be.links.blocks) {
            val p = link.pos
            if (p.distToCenterSqr(cam) > LABEL_RANGE * LABEL_RANGE) continue
            val gone = player.level().getBlockState(p).isAir
            val text = link.name + (link.side?.let { " · " + it.name.lowercase() } ?: "")
            pose.pushPose()
            pose.translate(p.x + 0.5 - cam.x, p.y + 1.3 - cam.y, p.z + 0.5 - cam.z)
            pose.mulPose(orientation)
            pose.scale(-0.025f, -0.025f, 0.025f)
            val m = pose.last().pose()
            val x = -font.width(text) / 2f
            font.drawInBatch(text, x, 0f, 0x20FFFFFF, false, m, buffers, Font.DisplayMode.SEE_THROUGH, bg, LightTexture.FULL_BRIGHT)
            font.drawInBatch(text, x, 0f, if (gone) 0xFFFFB84D.toInt() else 0xFF4DFFD8.toInt(), false, m, buffers, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT)
            pose.popPose()
        }
        buffers.endBatch()
    }

    /** The player a presence link points at, when this client can see them. */
    private fun tethered(viewer: Player, link: bpm.world.Link): Player? {
        val uuid = link.player ?: return null
        return viewer.level().players().firstOrNull { it.uuid == uuid }
    }

    private fun faceBox(b: AABB, face: net.minecraft.core.Direction): AABB = when (face) {
        net.minecraft.core.Direction.UP -> AABB(b.minX, b.maxY, b.minZ, b.maxX, b.maxY, b.maxZ)
        net.minecraft.core.Direction.DOWN -> AABB(b.minX, b.minY, b.minZ, b.maxX, b.minY, b.maxZ)
        net.minecraft.core.Direction.NORTH -> AABB(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.minZ)
        net.minecraft.core.Direction.SOUTH -> AABB(b.minX, b.minY, b.maxZ, b.maxX, b.maxY, b.maxZ)
        net.minecraft.core.Direction.WEST -> AABB(b.minX, b.minY, b.minZ, b.minX, b.maxY, b.maxZ)
        net.minecraft.core.Direction.EAST -> AABB(b.maxX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)
    }

    private fun box(pose: PoseStack, builder: com.mojang.blaze3d.vertex.VertexConsumer, box: AABB, rgb: FloatArray, a: Float) {
        LevelRenderer.renderLineBox(pose, builder, box, rgb[0], rgb[1], rgb[2], a)
    }

    private fun line(pose: PoseStack, builder: com.mojang.blaze3d.vertex.VertexConsumer, from: Vec3, to: Vec3, rgb: FloatArray, a: Float) {
        val m = pose.last()
        val d = to.subtract(from).normalize()
        builder.addVertex(m, from.x.toFloat(), from.y.toFloat(), from.z.toFloat()).setColor(rgb[0], rgb[1], rgb[2], a).setNormal(m, d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
        builder.addVertex(m, to.x.toFloat(), to.y.toFloat(), to.z.toFloat()).setColor(rgb[0], rgb[1], rgb[2], a).setNormal(m, d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
    }

    // ---- the crosshair line -----------------------------------------------------------------------------

    private fun drawHud(g: GuiGraphics, delta: DeltaTracker) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (mc.options.hideGui) return
        val stack = linkerIn(player) ?: return
        val font = mc.font
        // Bottom-left, clear of the crosshair and the hotbar: two short lines.
        val x = 8
        val y1 = g.guiHeight() - 30
        val y2 = g.guiHeight() - 19
        if (bpm.chamber.ChamberDimension.isChamber(player.level())) {
            val linker = stack.item as LinkerItem
            val charges = linker.charges(stack)
            val ready = stack.getOrDefault(bpm.world.ModComponents.TRACK_READY_AT.get(), 0L)
            val now = player.level().gameTime
            val track = if (now >= ready) "special ready" else "special in ${(ready - now) / 20 + 1} s"
            g.drawString(font, "linker · specials $charges / ${LinkerItem.MAX_CHARGES} · $track", x, y1, if (charges == 0) 0xF26D6D else 0x4DFFD8, true)
            g.drawString(font, "use: pulse · sneak + click: special · pedestal: recharge", x, y2, 0x9AA3B5, true)
            return
        }
        val be = controller(player)
        if (be == null) {
            val bound = LinkerItem.selectedPos(stack)
            g.drawString(font, if (bound == null) "linker · sneak-use a controller to bind" else "linker · controller ${bound.toShortString()} out of sight", x, y1, 0x9AA3B5, true)
            return
        }
        val range = LinkerItem.reach(be, player)
        g.drawString(font, "controller ${be.blockPos.toShortString()} · ${be.links.all.size}/${be.links.capacity} links · reach ${bpm.world.CoreTier.rangeText(range)}", x, y1, 0x4DFFD8, true)
        val hit = mc.hitResult as? BlockHitResult
        if (hit == null || hit.type != HitResult.Type.BLOCK) {
            val ahead = LinkerItem.linkAhead(player.level(), player, be) ?: return
            g.drawString(font, "'${ahead.name}' — its block is gone · sneak-use to unlink · ctrl-use to rename", x, y2, 0xFFB84D, true)
            return
        }
        if (hit.blockPos == be.blockPos) return
        val pos = hit.blockPos
        val existing = be.links.at(pos, hit.direction) ?: be.links.at(pos, null)
        val name = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).block).path
        val text = when {
            !pos.closerThan(be.blockPos, range) -> "$name — too far (${pos.distManhattan(be.blockPos)} blocks)"
            existing != null -> "'${existing.name}' — sneak-use to unlink · ctrl-use to rename"
            player.isShiftKeyDown -> "$name — nothing linked here"
            else -> "use to link '${be.links.previewName(name)}' (${hit.direction.name.lowercase()} face)"
        }
        g.drawString(font, text, x, y2, if (existing != null) 0xFFD27A else 0xE6E9F2, true)
    }
}
