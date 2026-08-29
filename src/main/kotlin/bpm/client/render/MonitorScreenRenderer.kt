package bpm.client.render

import bpm.world.devices.MonitorBlockEntity
import bpm.world.devices.MonitorWall
import bpm.world.devices.Widget
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.ItemDisplayContext
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions
import kotlin.math.atan2

/**
 * Draws a wall's widgets on its screen. Called for the wall's ORIGIN tile only; it measures the wall and
 * lays the widgets over the whole visible area — the tiles' glass less a 2 px bezel on the outer edges — in
 * screen pixels, 32 to a block (a 1 x 1 monitor is 24 x 24 of them, a 5 x 2 wall 152 x 56), in the columns
 * [MonitorLayout] finds room for. Text is the game's font, items the item renderer flattened onto the glass
 * over a dark slot, gauges filled quads (a fluid's in its own texture). What does not fit is not drawn.
 */
object MonitorScreenRenderer {
    /** One screen pixel, in blocks. */
    private const val PX = 1f / 32f
    private const val TILE = 32
    private const val BEZEL = 4
    private const val LINE = 9
    private const val ICON = 16
    private const val GAUGE = 20
    private const val BAR_TEXT = 0.7f
    private const val MIN_LABEL = 0.6f

    private const val MINT = 0xFFB8FFF0.toInt()
    private const val TEAL = 0xFF4DFFD8.toInt()
    private const val DIM = 0xFF9AA3B5.toInt()
    private const val TRACK = 0x66102030
    private const val FRAME = 0x334DFFD8

    private val NAMED = mapOf(
        "white" to 0xFFFFFFFF.toInt(), "mint" to MINT, "teal" to TEAL, "green" to 0xFFA8F04A.toInt(), "amber" to 0xFFFFB84D.toInt(),
        "red" to 0xFFF26D6D.toInt(), "grey" to DIM, "gray" to DIM, "blue" to 0xFF8AB4F8.toInt(), "orchid" to 0xFFF0A3D6.toInt(),
    )

    fun draw(be: MonitorBlockEntity, pose: PoseStack, buffers: MultiBufferSource, packedLight: Int) {
        val level = be.level ?: return
        if (be.widgets.isEmpty() || !MonitorWall.isOrigin(level, be.blockPos)) return
        val (w, h) = MonitorWall.sizeOf(level, be.blockPos)
        val width = TILE * w - 2 * BEZEL
        val height = TILE * h - 2 * BEZEL
        val light = if (be.on) LightTexture.FULL_BRIGHT else packedLight
        val mc = Minecraft.getInstance()

        pose.pushPose()
        pose.translate(0.5, 0.5, 0.5)
        pose.mulPose(Axis.YP.rotationDegrees(yawOf(be.facing)))
        // The viewer's top-left corner of the glass, just in front of it: left is +X here, top +Y.
        pose.translate(0.5 - 2.0 / 16, 0.5 - 2.0 / 16, 3.0 / 16 - 0.004)
        pose.scale(-PX, -PX, PX)

        val cells = be.widgets.map { MonitorLayout.Cell(heightOf(it), it.span) }
        for (p in MonitorLayout.place(cells, width, height)) {
            drawWidget(mc, be.widgets[p.index], p.x.toFloat(), p.y.toFloat(), p.w.toFloat(), pose, buffers, light)
        }
        pose.popPose()
    }

    private fun heightOf(w: Widget): Int = when (w.kind) {
        Widget.TEXT -> LINE * w.size
        Widget.ITEM -> ICON
        else -> GAUGE
    }

    private fun drawWidget(mc: Minecraft, w: Widget, x: Float, y: Float, width: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val font = mc.font
        when (w.kind) {
            Widget.TEXT -> {
                val colour = colourOf(w.colour, MINT)
                val s = w.size.toFloat()
                val shown = clip(font, w.text, width / s)
                val tw = font.width(shown) * s
                val tx = when (w.align.lowercase()) {
                    "center", "centre" -> x + (width - tw) / 2f
                    "right" -> x + width - tw
                    else -> x
                }
                pose.pushPose()
                pose.translate(tx, y, 0f)
                pose.scale(s, s, 1f)
                text(font, shown, 0f, 0f, colour, pose, buffers, light)
                pose.popPose()
            }
            Widget.ITEM -> {
                // a faint slot outline: an empty slot still reads as a slot, a full one is not sat on a dark hole
                frame(pose, buffers, x, y, x + ICON, y + ICON, FRAME)
                if (!w.item.isEmpty) {
                    pose.pushPose()
                    // The GUI's own way of putting an item in a 16 px box, then flattened onto the glass.
                    pose.translate(x + ICON / 2f, y + ICON / 2f, -0.5f)
                    pose.scale(16f, -16f, -16f * 0.04f)
                    mc.itemRenderer.renderStatic(w.item, ItemDisplayContext.GUI, light, OverlayTexture.NO_OVERLAY, pose, buffers, mc.level, 0)
                    pose.popPose()
                    val shown = if (w.value > 0) w.value else w.item.count.toDouble()
                    if (shown != 1.0) {
                        val n = MonitorFormat.short(shown)
                        val s = if (n.length > 3) 0.6f else 0.75f
                        pose.pushPose()
                        pose.translate(x + ICON - font.width(n) * s, y + ICON - LINE * s + 1f, -0.6f)
                        pose.scale(s, s, 1f)
                        font.drawInBatch(n, 0f, 0f, 0xFFFFFFFF.toInt(), true, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light)
                        pose.popPose()
                    }
                }
                val label = w.label.ifEmpty { if (w.item.isEmpty) "—" else w.item.hoverName.string }
                fitted(font, label, width - ICON - 3, x + ICON + 3, y + (ICON - LINE) / 2f + 1, MINT, pose, buffers, light)
            }
            Widget.FLUID, Widget.ENERGY, Widget.BAR -> {
                val fluid = if (w.kind == Widget.FLUID) ResourceLocation.tryParse(w.fluid)?.let { BuiltInRegistries.FLUID.getOptional(it).orElse(null) } else null
                val label = w.label.ifEmpty { fluid?.let { it.fluidType.description.string } ?: if (w.kind == Widget.ENERGY) "Energy" else "" }
                val full = MonitorFormat.ratio(w.value, w.max, w.unit)
                val short = MonitorFormat.shortRatio(w.value, w.max, w.unit)
                val percent = MonitorFormat.percent(w.value, w.max)
                // the header carries as much of the numbers as fits beside the label; the bar carries the rest
                val lw = font.width(label)
                val header = listOf(full, short, percent).firstOrNull { lw + 4 + font.width(it) <= width } ?: percent
                val hw = font.width(header)
                text(font, clip(font, label, width - hw - 4), x, y, MINT, pose, buffers, light)
                text(font, header, x + width - hw, y, DIM, pose, buffers, light)
                val by = y + LINE + 1
                val bh = GAUGE - LINE - 2f
                val fill = if (w.max > 0.0) (w.value / w.max).coerceIn(0.0, 1.0).toFloat() else 0f
                quad(pose, buffers, x, by, x + width, by + bh, TRACK)
                if (fill > 0f) {
                    val fx = x + width * fill
                    val colour = if (w.kind == Widget.ENERGY) TEAL else colourOf(w.colour, TEAL)
                    if (fluid != null) fluidQuad(mc, pose, buffers, fluid, x, by, fx, by + bh, light)
                    else quad(pose, buffers, x, by, fx, by + bh, colour)
                    // a lighter lip along the top of the fill, so the bar reads as a bar and not a stripe
                    quad(pose, buffers, x, by, fx, by + 1f, lighten(if (fluid != null) (0xFF000000.toInt() or fluidTint(fluid)) else colour))
                }
                val inBar = if (header === full) percent else if (header === short) percent else short
                val iw = font.width(inBar) * BAR_TEXT
                if (iw <= width - 4) {
                    pose.pushPose()
                    pose.translate(x + (width - iw) / 2f, by + (bh - LINE * BAR_TEXT) / 2f + 0.5f, -0.6f)
                    pose.scale(BAR_TEXT, BAR_TEXT, 1f)
                    font.drawInBatch(inBar, 0f, 0f, 0xFFFFFFFF.toInt(), true, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light)
                    pose.popPose()
                }
            }
        }
    }

    private fun text(font: Font, s: String, x: Float, y: Float, colour: Int, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        font.drawInBatch(s, x, y, colour, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light)
    }

    /** Text that shrinks (to [MIN_LABEL]) before it is clipped, so a label a few px too long keeps its words. */
    private fun fitted(font: Font, s: String, width: Float, x: Float, y: Float, colour: Int, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val tw = font.width(s).toFloat()
        val scale = if (tw <= width) 1f else (width / tw).coerceAtLeast(MIN_LABEL)
        val shown = if (tw * scale <= width) s else clip(font, s, width / scale)
        pose.pushPose()
        pose.translate(x, y + (LINE - LINE * scale) / 2f, 0f)
        pose.scale(scale, scale, 1f)
        text(font, shown, 0f, 0f, colour, pose, buffers, light)
        pose.popPose()
    }

    private fun frame(pose: PoseStack, buffers: MultiBufferSource, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int) {
        quad(pose, buffers, x0, y0, x1, y0 + 1f, argb)
        quad(pose, buffers, x0, y1 - 1f, x1, y1, argb)
        quad(pose, buffers, x0, y0 + 1f, x0 + 1f, y1 - 1f, argb)
        quad(pose, buffers, x1 - 1f, y0 + 1f, x1, y1 - 1f, argb)
    }

    private fun clip(font: Font, s: String, width: Float): String {
        if (font.width(s) <= width) return s
        var keep = s.length
        while (keep > 1 && font.width(s.substring(0, keep) + "…") > width) keep--
        return s.substring(0, keep) + "…"
    }

    private fun quad(pose: PoseStack, buffers: MultiBufferSource, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int) {
        val m = pose.last().pose()
        val b = buffers.getBuffer(RenderType.gui())
        b.addVertex(m, x0, y0, 0f).setColor(argb)
        b.addVertex(m, x0, y1, 0f).setColor(argb)
        b.addVertex(m, x1, y1, 0f).setColor(argb)
        b.addVertex(m, x1, y0, 0f).setColor(argb)
    }

    private fun fluidQuad(mc: Minecraft, pose: PoseStack, buffers: MultiBufferSource, fluid: net.minecraft.world.level.material.Fluid, x0: Float, y0: Float, x1: Float, y1: Float, light: Int) {
        val ext = IClientFluidTypeExtensions.of(fluid)
        val sprite = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(ext.stillTexture)
        val tint = ext.tintColor
        val r = (tint shr 16 and 0xFF) / 255f
        val g = (tint shr 8 and 0xFF) / 255f
        val bl = (tint and 0xFF) / 255f
        val m = pose.last().pose()
        val b = buffers.getBuffer(RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS))
        val u1 = sprite.u0 + (sprite.u1 - sprite.u0) * ((x1 - x0) / 16f).coerceIn(0.05f, 1f)
        val v1 = sprite.v0 + (sprite.v1 - sprite.v0) * ((y1 - y0) / 16f).coerceIn(0.05f, 1f)
        fun v(x: Float, y: Float, u: Float, vv: Float) {
            b.addVertex(m, x, y, -0.01f).setColor(r, g, bl, 1f).setUv(u, vv).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose.last(), 0f, 0f, -1f)
        }
        v(x0, y0, sprite.u0, sprite.v0)
        v(x0, y1, sprite.u0, v1)
        v(x1, y1, u1, v1)
        v(x1, y0, u1, sprite.v0)
    }

    private fun fluidTint(fluid: net.minecraft.world.level.material.Fluid): Int = IClientFluidTypeExtensions.of(fluid).tintColor and 0xFFFFFF

    private fun lighten(argb: Int): Int {
        val r = (argb shr 16 and 0xFF)
        val g = (argb shr 8 and 0xFF)
        val b = argb and 0xFF
        return (0xFF shl 24) or ((r + (255 - r) * 2 / 5) shl 16) or ((g + (255 - g) * 2 / 5) shl 8) or (b + (255 - b) * 2 / 5)
    }

    /** `#rrggbb`, a palette name, or [fallback]. */
    private fun colourOf(spec: String, fallback: Int): Int {
        val s = spec.trim().lowercase()
        if (s.isEmpty()) return fallback
        NAMED[s]?.let { return it }
        val hex = s.removePrefix("#")
        return hex.toLongOrNull(16)?.let { (0xFF000000L or (it and 0xFFFFFF)).toInt() } ?: fallback
    }

    /** The yaw that turns the model's -Z onto [facing]. */
    private fun yawOf(facing: Direction): Float {
        val n = facing.normal
        return Math.toDegrees(atan2(-n.x.toDouble(), -n.z.toDouble())).toFloat()
    }
}
