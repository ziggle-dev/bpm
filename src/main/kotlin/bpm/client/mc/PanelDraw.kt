package bpm.client.mc

import bpm.client.render.MonitorFormat
import bpm.client.render.MonitorLayout
import bpm.client.render.ScreenColours
import bpm.world.devices.Widget
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.core.BlockPos

/**
 * How a panel is drawn: the game's own font and item renderer, on the monitor's dark scanline glass.
 *
 * **Not ImGui.** ImGui rasterises its font into an atlas at one size, so a scaled-up panel could only ever be
 * a blown-up bitmap — which is exactly what blurred. Minecraft's font is drawn at whatever scale the pose
 * stack is at, items go through `renderItem` rather than an off-screen copy of one, and the result is the
 * same picture a monitor shows because it is the same font, the same [MonitorLayout], the same
 * [MonitorFormat] and the same [ScreenColours].
 *
 * Dropping ImGui here also drops its hazard: [HudOverlay] no longer has to open a frame inside the HUD, so
 * there is no longer any way for it to collide with the workbench's.
 *
 * Layout is done in unscaled monitor pixels and the whole panel is scaled by the pose stack, so geometry and
 * text move together and a panel at scale 2 is twice the size rather than twice the blur.
 */
object PanelDraw {

    /**
     * A widget the mouse is over, which panel it belongs to, and how far along it the cursor is (0 to 1) —
     * the same number a monitor's hit-test answers, so a slider means the same on glass and on screen.
     */
    class Hit(val controller: BlockPos, val widget: Widget, val along: Double)

    /** The monitor's metrics, in unscaled panel pixels — `MonitorScreenRenderer` uses the same numbers. */
    private const val LINE = 9
    private const val ICON = 16
    private const val GAUGE = 20
    private const val PRESSABLE = 14
    private const val PAD = 4
    private const val TITLE = 3
    private const val BAR_TEXT = 0.7f

    /** The glass, its edge, and the 2 px scanline period the monitor's own texture is drawn on. */
    private const val GLASS = 0xE00B1A22.toInt()
    private const val EDGE = 0x66194F4A
    private const val SCANLINE = 0x22000000
    private const val SCAN_PERIOD = 2
    private const val BTN = 0xFF16323F.toInt()
    private const val BTN_HOT = 0xFF20505F.toInt()

    /**
     * Draw every panel where its anchor puts it, in GUI-scaled pixels.
     *
     * [mouseX]/[mouseY] are GUI-scaled too; pass -1 for a surface nothing can be pressed on, and the returned
     * hit is then always null.
     */
    fun drawAll(
        g: GuiGraphics,
        panels: Collection<HudOverlay.Panel>,
        screenW: Int,
        screenH: Int,
        mouseX: Int,
        mouseY: Int,
    ): Hit? {
        val font = Minecraft.getInstance().font
        var hit: Hit? = null
        // Panels sharing an anchor stack down it rather than piling on one another.
        val used = HashMap<String, Int>()
        for (p in panels) {
            val s = p.scale.coerceIn(0.5f, 4f)
            val inner = p.width
            val cells = p.widgets.map { MonitorLayout.Cell(heightOf(it), it.span) }
            // Tall enough for everything: a panel is not a wall, it has no bottom edge to fall off.
            val placed = MonitorLayout.place(cells, inner, Int.MAX_VALUE / 4)
            val bodyH = (placed.maxOfOrNull { it.y + it.h } ?: 0) + MonitorLayout.MARGIN

            val boxW = inner + PAD * 2
            val boxH = bodyH + PAD * 2 + TITLE
            val stacked = used.getOrDefault(p.anchor, 0)
            val (px, py) = place(p.anchor, p.offsetX, p.offsetY + stacked, (boxW * s).toInt(), (boxH * s).toInt(), screenW, screenH)
            used[p.anchor] = stacked + (boxH * s).toInt() + 6

            bpm.platform.client.guiScaled(g, px.toFloat(), py.toFloat(), s) {
                glass(g, boxW, boxH)

                // The mouse, brought into the panel's own unscaled space, so hit-testing needs no scale maths.
                val lmx = if (mouseX < 0) Int.MIN_VALUE else ((mouseX - px) / s).toInt()
                val lmy = if (mouseY < 0) Int.MIN_VALUE else ((mouseY - py) / s).toInt()

                for (cell in placed) {
                    val found = drawWidget(
                        g, font, p, p.widgets[cell.index],
                        PAD + cell.x, PAD + TITLE + cell.y, cell.w, cell.h, lmx, lmy,
                    )
                    if (found != null) hit = found
                }
            }
        }
        return hit
    }

    /** The dark glass, its faint teal edge, and the scanlines — the monitor's `_on` texture, drawn. */
    private fun glass(g: GuiGraphics, w: Int, h: Int) {
        g.fill(0, 0, w, h, GLASS)
        var y = 0
        while (y < h) {
            g.fill(0, y, w, y + 1, SCANLINE)
            y += SCAN_PERIOD
        }
        g.fill(0, 0, w, 1, EDGE)
        g.fill(0, h - 1, w, h, EDGE)
        g.fill(0, 0, 1, h, EDGE)
        g.fill(w - 1, 0, w, h, EDGE)
        // The accent hairline, so it is obvious which machine is talking to you.
        g.fill(2, 1, w - 2, 2, ScreenColours.TEAL)
    }

    /** The wall's own heights — shared, so a panel and a monitor lay the same widgets out identically. */
    private fun heightOf(w: Widget): Int = Widget.heightOf(w)

    private fun place(anchor: String, ox: Int, oy: Int, w: Int, h: Int, sw: Int, sh: Int): Pair<Int, Int> {
        val x = when (anchor) {
            "TopLeft", "Left", "BottomLeft" -> ox
            "Top", "Center", "Bottom" -> (sw - w) / 2 + ox
            else -> sw - w - ox
        }
        val y = when (anchor) {
            "TopLeft", "Top", "TopRight" -> oy
            "Left", "Center", "Right" -> (sh - h) / 2 + oy
            else -> sh - h - oy
        }
        return x to y
    }

    private fun drawWidget(
        g: GuiGraphics,
        font: net.minecraft.client.gui.Font,
        p: HudOverlay.Panel,
        w: Widget,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        mx: Int,
        my: Int,
    ): Hit? {
        when (w.kind) {
            Widget.TEXT -> {
                val colour = ScreenColours.colourOf(w.colour, ScreenColours.MINT)
                val size = w.size.coerceIn(1, 4)
                val shown = clip(font, w.text, width / size)
                val tw = font.width(shown) * size
                val tx = when (w.align.lowercase()) {
                    "center", "centre" -> x + (width - tw) / 2
                    "right" -> x + width - tw
                    else -> x
                }
                if (size == 1) {
                    g.drawString(font, shown, tx, y, colour, false)
                } else {
                    bpm.platform.client.guiScaled(g, tx.toFloat(), y.toFloat(), size.toFloat()) {
                        g.drawString(font, shown, 0, 0, colour, false)
                    }
                }
            }

            Widget.ITEM -> {
                // A faint slot outline: an empty slot still reads as a slot, a full one is not sat on a hole.
                frame(g, x, y, x + ICON, y + ICON, ScreenColours.FRAME)
                if (!w.item.isEmpty) {
                    // The game's own item renderer, in GUI space — no off-screen copy, so it is never stale.
                    g.renderItem(w.item, x, y)
                    val count = if (w.value > 0) w.value else w.item.count.toDouble()
                    val label = if (count == 1.0) "" else MonitorFormat.short(count)
                    g.renderItemDecorations(font, w.item, x, y, label)
                }
                val label = w.label.ifEmpty { if (w.item.isEmpty) "—" else w.item.hoverName.string }
                g.drawString(font, clip(font, label, width - ICON - 3), x + ICON + 3, y + (ICON - LINE) / 2, ScreenColours.MINT, false)
            }

            Widget.FLUID, Widget.ENERGY, Widget.BAR -> {
                val fluidId = w.fluid.takeIf { it.isNotEmpty() && w.kind == Widget.FLUID }
                val label = w.label.ifEmpty {
                    fluidId?.let { FluidLookProvider.labelOf(it) } ?: if (w.kind == Widget.ENERGY) "Energy" else ""
                }
                // The header carries as much of the numbers as fits beside the label; the bar carries the rest.
                val full = MonitorFormat.ratio(w.value, w.max, w.unit)
                val short = MonitorFormat.shortRatio(w.value, w.max, w.unit)
                val percent = MonitorFormat.percent(w.value, w.max)
                val lw = font.width(label)
                val header = listOf(full, short, percent).firstOrNull { lw + 4 + font.width(it) <= width } ?: percent
                val hw = font.width(header)
                g.drawString(font, clip(font, label, width - hw - 4), x, y, ScreenColours.MINT, false)
                g.drawString(font, header, x + width - hw, y, ScreenColours.DIM, false)

                val by = y + LINE + 1
                val bh = height - LINE - 2
                val fill = if (w.max > 0.0) (w.value / w.max).coerceIn(0.0, 1.0) else 0.0
                g.fill(x, by, x + width, by + bh, ScreenColours.TRACK)
                if (fill > 0.0) {
                    val colour = when {
                        fluidId != null -> FluidLookProvider.colour(fluidId) ?: ScreenColours.TEAL
                        w.kind == Widget.ENERGY -> ScreenColours.TEAL
                        else -> ScreenColours.colourOf(w.colour, ScreenColours.TEAL)
                    }
                    val fx = x + (width * fill).toInt()
                    g.fill(x, by, fx, by + bh, colour)
                    // A lighter lip along the top, so the bar reads as a bar and not a stripe.
                    g.fill(x, by, fx, by + 1, ScreenColours.lighten(colour))
                }
                // Whatever the header could not carry goes in the bar, as the monitor does it.
                val inBar = if (header === full || header === short) percent else short
                val iw = (font.width(inBar) * BAR_TEXT).toInt()
                if (iw <= width - 4) {
                    bpm.platform.client.guiScaled(g, x + (width - iw) / 2f, by + (bh - LINE * BAR_TEXT) / 2f, BAR_TEXT) {
                        g.drawString(font, inBar, 0, 0, ScreenColours.WHITE, true)
                    }
                }
            }

            Widget.SLIDER -> {
                val over = hovering(mx, my, x, y, width, height)
                val tint = ScreenColours.colourOf(w.colour, ScreenColours.TEAL)
                val frac = if (w.max > 0.0) (w.value / w.max).coerceIn(0.0, 1.0).toFloat() else 0f
                val shown = MonitorFormat.full(w.value) + if (w.unit.isNotEmpty()) " " + w.unit else ""
                val sw = font.width(shown)
                g.drawString(font, clip(font, w.label, width - sw - 4), x, y, ScreenColours.MINT, false)
                g.drawString(font, shown, x + width - sw, y, ScreenColours.DIM, false)
                val by = y + LINE + 1
                val bh = height - LINE - 2
                g.fill(x, by, x + width, by + bh, ScreenColours.TRACK)
                if (frac > 0f) g.fill(x, by, x + (width * frac).toInt(), by + bh, tint)
                val nx = (x + (width * frac).toInt()).coerceIn(x, x + width - 3)
                g.fill(nx, by - 1, nx + 3, by + bh + 1, if (over) ScreenColours.WHITE else ScreenColours.MINT)
                if (over) return Hit(p.controller, w, along(mx, x, width))
            }

            Widget.FIELD -> {
                val over = hovering(mx, my, x, y, width, height)
                g.fill(x, y, x + width, y + height, if (over) BTN_HOT else BTN)
                frame(g, x, y, x + width, y + height, ScreenColours.FRAME)
                val shown = w.text.ifEmpty { w.label.ifEmpty { "…" } }
                val colour = if (w.text.isEmpty()) ScreenColours.DIM else ScreenColours.MINT
                g.drawString(font, clip(font, shown, width - 4), x + 2, y + (height - LINE) / 2 + 1, colour, false)
                if (over) return Hit(p.controller, w, along(mx, x, width))
            }

            Widget.BUTTON -> {
                val over = hovering(mx, my, x, y, width, height)
                val tint = ScreenColours.colourOf(w.colour, ScreenColours.TEAL)
                g.fill(x, y, x + width, y + height, if (over) BTN_HOT else BTN)
                frame(g, x, y, x + width, y + height, tint)
                val label = clip(font, w.label, width - 4)
                g.drawString(font, label, x + (width - font.width(label)) / 2, y + (height - LINE) / 2 + 1, ScreenColours.MINT, false)
                if (over) return Hit(p.controller, w, along(mx, x, width))
            }

            Widget.TOGGLE -> {
                val over = hovering(mx, my, x, y, width, height)
                val on = w.value >= 0.5
                if (over) g.fill(x, y, x + width, y + height, BTN)
                val knobW = 18
                g.drawString(font, clip(font, w.label, width - knobW - 6), x + 2, y + (height - LINE) / 2 + 1, ScreenColours.MINT, false)
                val kx = x + width - knobW - 2
                val ky = y + 2
                val kh = height - 4
                g.fill(kx, ky, kx + knobW, ky + kh, if (on) ScreenColours.colourOf(w.colour, ScreenColours.TEAL) else BTN)
                frame(g, kx, ky, kx + knobW, ky + kh, ScreenColours.FRAME)
                val dotX = if (on) kx + knobW - kh else kx
                g.fill(dotX + 1, ky + 1, dotX + kh - 1, ky + kh - 1, GLASS)
                if (over) return Hit(p.controller, w, along(mx, x, width))
            }
        }
        return null
    }

    private fun frame(g: GuiGraphics, x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
        g.fill(x0, y0, x1, y0 + 1, argb)
        g.fill(x0, y1 - 1, x1, y1, argb)
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, argb)
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, argb)
    }

    /** How far along a widget the cursor is, 0 at its left edge to 1 at its right. */
    private fun along(mx: Int, x: Int, w: Int): Double {
        if (w <= 1) return 0.0
        return ((mx - x).toDouble() / (w - 1)).coerceIn(0.0, 1.0)
    }

    private fun hovering(mx: Int, my: Int, x: Int, y: Int, w: Int, h: Int): Boolean =
        mx != Int.MIN_VALUE && mx >= x && mx < x + w && my >= y && my < y + h

    /** Text cut with an ellipsis rather than spilling out of its cell, exactly as a monitor clips it. */
    private fun clip(font: net.minecraft.client.gui.Font, s: String, width: Int): String {
        if (s.isEmpty() || font.width(s) <= width) return s
        var keep = s.length
        while (keep > 1 && font.width(s.substring(0, keep) + "…") > width) keep--
        return s.substring(0, keep) + "…"
    }
}
