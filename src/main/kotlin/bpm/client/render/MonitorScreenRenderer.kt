package bpm.client.render

import bpm.platform.client.setUv
import bpm.platform.client.setLight
import bpm.platform.client.setOverlay
import bpm.platform.client.setNormal

import bpm.platform.client.addVertex
import bpm.platform.client.setColor

import bpm.world.devices.MonitorBlockEntity
import bpm.world.devices.MonitorWall
import bpm.world.devices.Widget
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import bpm.platform.client.FULL_BRIGHT
import bpm.platform.client.WorldDraw
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import bpm.platform.ResourceLocation
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.ItemDisplayContext
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

    /** The name the screen's flat quads are cached under. */
    private const val QUADS = "bpm_monitor_screen"
    private const val TILE = 32
    private const val BEZEL = 4
    private const val LINE = 9
    private const val ICON = 16
    private const val GAUGE = 20
    private const val BAR_TEXT = 0.7f
    private const val MIN_LABEL = 0.6f

    /**
     * Where the stack count sits, in screen units toward the viewer.
     *
     * Past the item's near face (about -0.82 for a model 0.64 deep drawn at -0.5), with the white glyph a
     * further 0.1 in front of its shadow. At 1/32 of a block per unit that separation is a tenth of a
     * millimetre — enough to order them, far too little to look detached.
     */
    private const val COUNT_Z = -1.0f

    private const val MINT = ScreenColours.MINT
    private const val TEAL = ScreenColours.TEAL
    private const val DIM = ScreenColours.DIM
    private const val TRACK = ScreenColours.TRACK
    private const val FRAME = ScreenColours.FRAME

    /** Behind a button, and the nub of a toggle: dark enough to read as a control on lit glass. */
    private const val PRESS_BG = 0xCC0B1A22.toInt()

    fun draw(be: MonitorBlockEntity, pose: PoseStack, draw: WorldDraw) {
        val level = be.level ?: return
        // A screen that is off shows nothing. `on` used only to pick the light level, so a monitor switched
        // off still drew its whole contents, merely dimmer — which is not what "off" means to anyone looking
        // at it, and left stale numbers legible on a wall someone had deliberately darkened.
        if (!be.on || be.widgets.isEmpty() || !MonitorWall.isOrigin(level, be.blockPos)) return
        val (w, h) = MonitorWall.sizeOf(level, be.blockPos)
        val width = TILE * w - 2 * BEZEL
        val height = TILE * h - 2 * BEZEL
        // Always lit: nothing draws at all unless the screen is on, so there is no dim case left to carry
        // a block light through for.
        val light = FULL_BRIGHT
        val mc = Minecraft.getInstance()

        pose.pushPose()
        pose.translate(0.5, 0.5, 0.5)
        pose.mulPose(Axis.YP.rotationDegrees(yawOf(be.facing)))
        // The viewer's top-left corner of the glass, just in front of it: left is +X here, top +Y.
        pose.translate(0.5 - 2.0 / 16, 0.5 - 2.0 / 16, 3.0 / 16 - 0.004)
        pose.scale(-PX, -PX, PX)

        val cells = be.widgets.map { MonitorLayout.Cell(heightOf(it), it.span) }
        for (p in MonitorLayout.place(cells, width, height)) {
            drawWidget(mc, be.widgets[p.index], p.x.toFloat(), p.y.toFloat(), p.w.toFloat(), pose, draw, light)
        }
        pose.popPose()
    }

    private fun heightOf(w: Widget): Int = Widget.heightOf(w)

    private fun drawWidget(mc: Minecraft, w: Widget, x: Float, y: Float, width: Float, pose: PoseStack, draw: WorldDraw, light: Int) {
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
                text(font, shown, 0f, 0f, colour, pose, draw, light)
                pose.popPose()
            }
            Widget.ITEM -> {
                // a faint slot outline: an empty slot still reads as a slot, a full one is not sat on a dark hole
                frame(pose, draw, x, y, x + ICON, y + ICON, FRAME)
                if (!w.item.isEmpty) {
                    pose.pushPose()
                    // The GUI's own way of putting an item in a 16 px box, then flattened onto the glass.
                    pose.translate(x + ICON / 2f, y + ICON / 2f, -0.5f)
                    pose.scale(16f, -16f, -16f * 0.04f)
                    draw.item(pose, w.item, ItemDisplayContext.GUI, light, OverlayTexture.NO_OVERLAY)
                    pose.popPose()
                    val shown = if (w.value > 0) w.value else w.item.count.toDouble()
                    if (shown != 1.0) {
                        val n = MonitorFormat.short(shown)
                        val s = if (n.length > 3) 0.6f else 0.75f
                        val tx = x + ICON - font.width(n) * s
                        val ty = y + ICON - LINE * s + 1f
                        // In FRONT of the whole item, not just its centre. An item is a model with depth: it
                        // is drawn at z -0.5 and scaled 0.64 deep, so its near face reaches about -0.82 and
                        // the count at -0.6 sat inside it and was partly swallowed.
                        //
                        // The shadow is also drawn by hand rather than by the font. `drawInBatch`'s own
                        // shadow is COPLANAR with the glyph and separated only by draw order, which under
                        // this screen's mirrored transform put the dark copy in front of the white one.
                        // Two draws at two depths cannot get that wrong.
                        layer(font, n, tx + s, ty + s, s, shadowOf(0xFFFFFFFF.toInt()), COUNT_Z, pose, draw, light)
                        layer(font, n, tx, ty, s, 0xFFFFFFFF.toInt(), COUNT_Z - 0.1f, pose, draw, light)
                    }
                }
                val label = w.label.ifEmpty { if (w.item.isEmpty) "—" else w.item.hoverName.string }
                fitted(font, label, width - ICON - 3, x + ICON + 3, y + (ICON - LINE) / 2f + 1, MINT, pose, draw, light)
            }
            Widget.BUTTON, Widget.TOGGLE -> {
                // These used to fall through the `when` and draw nothing at all, leaving a gap where a
                // control should be. A wall can now carry them, and `MonitorHit` answers clicks on them.
                val h = Widget.PRESSABLE.toFloat()
                val tint = colourOf(w.colour, TEAL)
                if (w.kind == Widget.BUTTON) {
                    quad(pose, draw, x, y, x + width, y + h, PRESS_BG)
                    frame(pose, draw, x, y, x + width, y + h, tint)
                    val label = clip(font, w.label, width - 4)
                    text(font, label, x + (width - font.width(label)) / 2f, y + (h - LINE) / 2f + 1f, MINT, pose, draw, light)
                } else {
                    val on = w.value >= 0.5
                    val knobW = 18f
                    fitted(font, w.label, width - knobW - 4, x, y + (h - LINE) / 2f + 1f, MINT, pose, draw, light)
                    val kx = x + width - knobW
                    quad(pose, draw, kx, y + 1f, kx + knobW, y + h - 1f, if (on) tint else TRACK)
                    frame(pose, draw, kx, y + 1f, kx + knobW, y + h - 1f, FRAME)
                    // The nub sits at whichever end the switch is thrown to.
                    val nub = h - 4f
                    val nx = if (on) kx + knobW - nub - 1f else kx + 1f
                    quad(pose, draw, nx, y + 2f, nx + nub, y + h - 2f, PRESS_BG)
                }
            }
            Widget.SLIDER -> {
                // A gauge you can move: the same bar the readouts use, plus a nub to say so.
                val h = Widget.GAUGE.toFloat()
                val tint = colourOf(w.colour, TEAL)
                val frac = if (w.max > 0.0) (w.value / w.max).coerceIn(0.0, 1.0).toFloat() else 0f
                val label = w.label
                val shown = MonitorFormat.full(w.value) + if (w.unit.isNotEmpty()) " " + w.unit else ""
                val sw = font.width(shown)
                text(font, clip(font, label, width - sw - 4), x, y, MINT, pose, draw, light)
                text(font, shown, x + width - sw, y, DIM, pose, draw, light)
                val by = y + LINE + 1f
                val bh = h - LINE - 2f
                quad(pose, draw, x, by, x + width, by + bh, TRACK)
                if (frac > 0f) quad(pose, draw, x, by, x + width * frac, by + bh, tint)
                val nub = 3f
                val nx = (x + width * frac).coerceIn(x, x + width - nub)
                quad(pose, draw, nx, by - 1f, nx + nub, by + bh + 1f, MINT)
            }
            Widget.FIELD -> {
                val h = Widget.PRESSABLE.toFloat()
                quad(pose, draw, x, y, x + width, y + h, PRESS_BG)
                frame(pose, draw, x, y, x + width, y + h, FRAME)
                val shown = w.text.ifEmpty { w.label.ifEmpty { "…" } }
                val colour = if (w.text.isEmpty()) DIM else MINT
                text(font, clip(font, shown, width - 4), x + 2f, y + (h - LINE) / 2f + 1f, colour, pose, draw, light)
            }
            Widget.FLUID, Widget.ENERGY, Widget.BAR -> {
                val fluid = if (w.kind == Widget.FLUID) ResourceLocation.tryParse(w.fluid)?.let { BuiltInRegistries.FLUID.getOptional(it).orElse(null) } else null
                val label = w.label.ifEmpty { fluid?.let { bpm.platform.world.Fluids.displayName(it).string } ?: if (w.kind == Widget.ENERGY) "Energy" else "" }
                val full = MonitorFormat.ratio(w.value, w.max, w.unit)
                val short = MonitorFormat.shortRatio(w.value, w.max, w.unit)
                val percent = MonitorFormat.percent(w.value, w.max)
                // the header carries as much of the numbers as fits beside the label; the bar carries the rest
                val lw = font.width(label)
                val header = listOf(full, short, percent).firstOrNull { lw + 4 + font.width(it) <= width } ?: percent
                val hw = font.width(header)
                text(font, clip(font, label, width - hw - 4), x, y, MINT, pose, draw, light)
                text(font, header, x + width - hw, y, DIM, pose, draw, light)
                val by = y + LINE + 1
                val bh = GAUGE - LINE - 2f
                val fill = if (w.max > 0.0) (w.value / w.max).coerceIn(0.0, 1.0).toFloat() else 0f
                quad(pose, draw, x, by, x + width, by + bh, TRACK)
                if (fill > 0f) {
                    val fx = x + width * fill
                    val colour = if (w.kind == Widget.ENERGY) TEAL else colourOf(w.colour, TEAL)
                    if (fluid != null) fluidQuad(mc, pose, draw, fluid, x, by, fx, by + bh, light)
                    else quad(pose, draw, x, by, fx, by + bh, colour)
                    // a lighter lip along the top of the fill, so the bar reads as a bar and not a stripe
                    quad(pose, draw, x, by, fx, by + 1f, lighten(if (fluid != null) (0xFF000000.toInt() or fluidTint(fluid)) else colour))
                }
                val inBar = if (header === full) percent else if (header === short) percent else short
                val iw = font.width(inBar) * BAR_TEXT
                if (iw <= width - 4) {
                    // Two draws at two depths, for the reason the stack count gives: the font's own shadow is
                    // coplanar with its glyph, and this screen's mirrored transform is enough to invert them.
                    val bx = x + (width - iw) / 2f
                    val byy = by + (bh - LINE * BAR_TEXT) / 2f + 0.5f
                    layer(font, inBar, bx + BAR_TEXT, byy + BAR_TEXT, BAR_TEXT, shadowOf(0xFFFFFFFF.toInt()), -0.6f, pose, draw, light)
                    layer(font, inBar, bx, byy, BAR_TEXT, 0xFFFFFFFF.toInt(), -0.7f, pose, draw, light)
                }
            }
        }
    }

    /** One layer of scaled text at its own depth, so a shadow can never climb over its glyph. */
    private fun layer(
        font: Font,
        n: String,
        x: Float,
        y: Float,
        scale: Float,
        colour: Int,
        z: Float,
        pose: PoseStack,
        draw: WorldDraw,
        light: Int,
    ) {
        pose.pushPose()
        pose.translate(x, y, z)
        pose.scale(scale, scale, 1f)
        draw.text(pose, chars(n), 0f, 0f, colour, false, Font.DisplayMode.NORMAL, 0, light)
        pose.popPose()
    }

    /** A quarter-brightness copy, which is what the game's own text shadow is. */
    private fun shadowOf(argb: Int): Int = (argb and 0xFF000000.toInt()) or ((argb and 0xFCFCFC) shr 2)

    private fun text(font: Font, s: String, x: Float, y: Float, colour: Int, pose: PoseStack, draw: WorldDraw, light: Int) {
        draw.text(pose, chars(s), x, y, colour, false, Font.DisplayMode.NORMAL, 0, light)
    }

    /** A plain string as the sequence the draw handle takes. The screen has no styled text. */
    private fun chars(s: String): net.minecraft.util.FormattedCharSequence =
        net.minecraft.util.FormattedCharSequence.forward(s, net.minecraft.network.chat.Style.EMPTY)

    /** Text that shrinks (to [MIN_LABEL]) before it is clipped, so a label a few px too long keeps its words. */
    private fun fitted(font: Font, s: String, width: Float, x: Float, y: Float, colour: Int, pose: PoseStack, draw: WorldDraw, light: Int) {
        val tw = font.width(s).toFloat()
        val scale = if (tw <= width) 1f else (width / tw).coerceAtLeast(MIN_LABEL)
        val shown = if (tw * scale <= width) s else clip(font, s, width / scale)
        pose.pushPose()
        pose.translate(x, y + (LINE - LINE * scale) / 2f, 0f)
        pose.scale(scale, scale, 1f)
        text(font, shown, 0f, 0f, colour, pose, draw, light)
        pose.popPose()
    }

    private fun frame(pose: PoseStack, draw: WorldDraw, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int) {
        quad(pose, draw, x0, y0, x1, y0 + 1f, argb)
        quad(pose, draw, x0, y1 - 1f, x1, y1, argb)
        quad(pose, draw, x0, y0 + 1f, x0 + 1f, y1 - 1f, argb)
        quad(pose, draw, x1 - 1f, y0 + 1f, x1, y1 - 1f, argb)
    }

    private fun clip(font: Font, s: String, width: Float): String {
        if (font.width(s) <= width) return s
        var keep = s.length
        while (keep > 1 && font.width(s.substring(0, keep) + "…") > width) keep--
        return s.substring(0, keep) + "…"
    }

    /**
     * A flat coloured quad on the glass.
     *
     * `RenderType.gui()` used to serve for this. It does not survive to 1.21.9 -- the GUI became its own
     * renderer with its own state -- and it was never the right name for something drawn in the world
     * anyway. [bpm.platform.client.translucentQuads] is the same thing said as an effect: untextured,
     * translucent, both faces (the screen's transform is mirrored, so winding is not to be trusted).
     */
    private fun quad(pose: PoseStack, draw: WorldDraw, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int) {
        draw.into(pose, bpm.platform.client.translucentQuads(QUADS)) { p, b ->
            val m = p.pose()
            b.addVertex(m, x0, y0, 0f).setColor(argb)
            b.addVertex(m, x0, y1, 0f).setColor(argb)
            b.addVertex(m, x1, y1, 0f).setColor(argb)
            b.addVertex(m, x1, y0, 0f).setColor(argb)
        }
    }

    private fun fluidQuad(mc: Minecraft, pose: PoseStack, draw: WorldDraw, fluid: net.minecraft.world.level.material.Fluid, x0: Float, y0: Float, x1: Float, y1: Float, light: Int) {
        val look = bpm.platform.client.FluidVisuals.of(fluid)
        val sprite = bpm.platform.client.blockSprite(look.still)
        val tint = look.tint
        val r = (tint shr 16 and 0xFF) / 255f
        val g = (tint shr 8 and 0xFF) / 255f
        val bl = (tint and 0xFF) / 255f
        val u1 = sprite.u0 + (sprite.u1 - sprite.u0) * ((x1 - x0) / 16f).coerceIn(0.05f, 1f)
        val v1 = sprite.v0 + (sprite.v1 - sprite.v0) * ((y1 - y0) / 16f).coerceIn(0.05f, 1f)
        draw.into(pose, bpm.platform.client.translucentCull(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)) { p, b ->
            val m = p.pose()
            fun v(x: Float, y: Float, u: Float, vv: Float) {
                b.addVertex(m, x, y, -0.01f).setColor(r, g, bl, 1f).setUv(u, vv).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0f, 0f, -1f)
            }
            v(x0, y0, sprite.u0, sprite.v0)
            v(x0, y1, sprite.u0, v1)
            v(x1, y1, u1, v1)
            v(x1, y0, u1, sprite.v0)
        }
    }

    private fun fluidTint(fluid: net.minecraft.world.level.material.Fluid): Int = bpm.platform.client.FluidVisuals.of(fluid).tint and 0xFFFFFF

    private fun lighten(argb: Int): Int = ScreenColours.lighten(argb)

    private fun colourOf(spec: String, fallback: Int): Int = ScreenColours.colourOf(spec, fallback)

    /** The yaw that turns the model's -Z onto [facing]. */
    private fun yawOf(facing: Direction): Float {
        val n = bpm.platform.unitVector(facing)
        return Math.toDegrees(atan2(-n.x.toDouble(), -n.z.toDouble())).toFloat()
    }
}
