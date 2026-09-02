package bpm.client.render

import bpm.platform.idOf

import bpm.Bpm
import bpm.chamber.ChamberDimension
import bpm.client.mc.LinkRenameScreen
import bpm.world.ControllerBlockEntity
import bpm.world.LinkerItem
import bpm.world.items.WardenVisorItem
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import bpm.platform.client.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LevelRenderer
import bpm.platform.client.FULL_BRIGHT
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import bpm.platform.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector2f
import org.joml.Vector4f
import java.util.function.Consumer
import bpm.platform.client.drawText

/**
 * What a player holding the Quantum Linker sees: the bound controller's links outlined in the world with a
 * line to each, the looked-at face marked green (linkable, in range) or red (too far), and a line under the
 * crosshair naming the controller and what the next use would do. With the Warden's Visor on, the lines
 * show through walls.
 */
object LinkerHud {
    private val LAYER = idOf(Bpm.ID, "linker")
    private val TEAL = floatArrayOf(0.30f, 1.00f, 0.85f)
    private val GREEN = floatArrayOf(0.35f, 0.95f, 0.45f)
    private val RED = floatArrayOf(0.95f, 0.35f, 0.35f)
    private val AMBER = floatArrayOf(1.00f, 0.72f, 0.30f)

    /** Presence links are orchid, as they are in the panel — a person reads apart from a chest at a glance. */
    private val ORCHID = floatArrayOf(0.79f, 0.37f, 0.65f)
    /** The linker outlines are two pixels wide, which reads at arm.s length without shouting. */
    private const val WIDTH = 2f

    private const val LABEL_RANGE = 48.0

    fun install() {
        bpm.platform.client.Hud.aboveCrosshair(LAYER, ::drawHud)
        bpm.platform.events.BpmEvents.worldRenderTranslucent.listen(::renderWorld)
        bpm.platform.events.BpmEvents.useItemPressed.listen(::onInteract)
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

    /**
     * Ctrl + use on a link opens the rename box in place of the wand's own use.
     *
     * Returns false to say "handled" — the bridge cancels the interaction AND suppresses the swing, so
     * the arm does not wave for a use that never reached the server.
     */
    private fun onInteract(player: net.minecraft.world.entity.player.Player): Boolean {
        if (!bpm.platform.client.ctrlHeld()) return true
        val mc = Minecraft.getInstance()
        if (linkerIn(player) == null || ChamberDimension.isChamber(player.level())) return true
        val be = controller(player) ?: return true
        val link = aimedLink(mc, player, be) ?: return true
        LinkRenameScreen.open(be.blockPos, link, be.links.names)
        return false
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

    private fun renderWorld(event: bpm.platform.events.WorldRender) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        view = View(Matrix4f(event.projection), Matrix4f(event.modelView), event.eye)
        val be = controller(player) ?: return
        val cam = event.eye
        val pose = event.pose
        val throughWalls = WardenVisorItem.worn(player)
        val range = LinkerItem.reach(be, player)
        // Where things are THIS FRAME, not this tick. A player's model is drawn interpolated, so anything
        // hung on them has to be too or it judders a tick behind the head it belongs to.
        val partial = event.delta.partial(true)

        // One pass for everything: blend, cull and depth are the render type's business now, and the
        // width rides on the vertices from 1.21.9. See [bpm.platform.client.LinePass].
        bpm.platform.client.worldLines(throughWalls, WIDTH) { lines ->
        pose.pushPose()
        pose.translate(-cam.x, -cam.y, -cam.z)

        val centre = Vec3.atCenterOf(be.blockPos)
        // A presence link has no block to outline: it follows the person, so draw the thread to where they
        // actually are and box them, not the patch of ground they were last seen on.
        for (link in be.links.presence) {
            val who = tethered(player, link) ?: continue
            // The interpolated position, for the same reason the label uses it: the outline has to sit on
            // the body being drawn, not the one the last tick left behind.
            val at = who.getPosition(partial)
            val bounds = who.boundingBox.move(at.x - who.x, at.y - who.y, at.z - who.z)
            lines.box(pose, bounds.inflate(0.08), ORCHID, 0.9f)
            lines.line(pose, centre, at.add(0.0, who.bbHeight * 0.5, 0.0), ORCHID, 0.6f)
        }
        for (link in be.links.blocks) {
            // A link whose block is gone is drawn amber: aim at it and sneak-use to unlink.
            val colour = if (player.level().getBlockState(link.pos).isAir) AMBER else TEAL
            lines.box(pose, AABB(link.pos).inflate(0.02), colour, 0.9f)
            link.side?.let { face ->
                val fb = AABB(link.pos)
                val f = faceBox(fb, face).inflate(0.03)
                lines.box(pose, f, colour, 1f)
            }
            lines.line(pose, centre, Vec3.atCenterOf(link.pos), colour, 0.6f)
        }
        // The looked-at block: green when the next use links it, red when it is out of reach.
        val hit = mc.hitResult as? BlockHitResult
        if (hit != null && hit.type == HitResult.Type.BLOCK && hit.blockPos != be.blockPos) {
            val ok = hit.blockPos.closerThan(be.blockPos, range)
            lines.box(pose, faceBox(AABB(hit.blockPos), hit.direction).inflate(0.01), if (ok) GREEN else RED, 1f)
        } else {
            LinkerItem.linkAhead(player.level(), player, be)?.let { ahead ->
                lines.box(pose, AABB(ahead.pos).inflate(0.05), if (player.isShiftKeyDown) RED else AMBER, 1f)
            }
        }
        pose.popPose()
        }
        labels(mc, player, be, cam, pose, partial)
    }

    /** Each link's name (and face) as a sign over its block — amber when the block is gone. */
    private fun labels(mc: Minecraft, player: Player, be: ControllerBlockEntity, cam: Vec3, pose: PoseStack, partial: Float) {
        val draw = bpm.platform.client.immediateWorldDraw()
        val font = mc.font
        val orientation = bpm.platform.client.cameraRotation()
        val bg = (mc.options.getBackgroundOpacity(0.25f) * 255).toInt() shl 24
        for (link in be.links.presence) {
            val who = tethered(player, link) ?: continue
            val at = who.getPosition(partial)
            if (at.distanceToSqr(cam) > LABEL_RANGE * LABEL_RANGE) continue
            pose.pushPose()
            pose.translate(at.x - cam.x, at.y + who.bbHeight + 0.5 - cam.y, at.z - cam.z)
            pose.mulPose(orientation)
            pose.scale(-0.025f, -0.025f, 0.025f)
            val x = -font.width(link.name) / 2f
            val glyphs = net.minecraft.network.chat.Component.literal(link.name).visualOrderText
            draw.text(pose, glyphs, x, 0f, 0x20FFFFFF, false, Font.DisplayMode.SEE_THROUGH, bg, FULL_BRIGHT)
            draw.text(pose, glyphs, x, 0f, 0xFFF0A3D6.toInt(), false, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT)
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
            val x = -font.width(text) / 2f
            val glyphs = net.minecraft.network.chat.Component.literal(text).visualOrderText
            draw.text(pose, glyphs, x, 0f, 0x20FFFFFF, false, Font.DisplayMode.SEE_THROUGH, bg, FULL_BRIGHT)
            draw.text(pose, glyphs, x, 0f, if (gone) 0xFFFFB84D.toInt() else 0xFF4DFFD8.toInt(), false, Font.DisplayMode.NORMAL, 0, FULL_BRIGHT)
            pose.popPose()
        }
        draw.flush()
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

    // ---- the crosshair line -----------------------------------------------------------------------------

    private fun drawHud(g: GuiGraphics, delta: DeltaTracker) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (bpm.platform.client.hudHidden()) return
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
            g.drawText(font, "linker · specials $charges / ${LinkerItem.MAX_CHARGES} · $track", x, y1, if (charges == 0) 0xF26D6D else 0x4DFFD8, true)
            g.drawText(font, "use: pulse · sneak + click: special · pedestal: recharge", x, y2, 0x9AA3B5, true)
            return
        }
        val be = controller(player)
        if (be == null) {
            val bound = LinkerItem.selectedPos(stack)
            g.drawText(font, if (bound == null) "linker · sneak-use a controller to bind" else "linker · controller ${bound.toShortString()} out of sight", x, y1, 0x9AA3B5, true)
            return
        }
        val range = LinkerItem.reach(be, player)
        g.drawText(font, "controller ${be.blockPos.toShortString()} · ${be.links.all.size}/${be.links.capacity} links · reach ${bpm.world.CoreTier.rangeText(range)}", x, y1, 0x4DFFD8, true)
        val hit = mc.hitResult as? BlockHitResult
        if (hit == null || hit.type != HitResult.Type.BLOCK) {
            val ahead = LinkerItem.linkAhead(player.level(), player, be) ?: return
            g.drawText(font, "'${ahead.name}' — its block is gone · sneak-use to unlink · ctrl-use to rename", x, y2, 0xFFB84D, true)
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
        g.drawText(font, text, x, y2, if (existing != null) 0xFFD27A else 0xE6E9F2, true)
    }
}
