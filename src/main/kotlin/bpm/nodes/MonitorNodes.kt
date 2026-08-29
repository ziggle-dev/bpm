package bpm.nodes

import bpm.catalog.McVs
import bpm.catalog.values.ItemStackValue
import bpm.catalog.values.WidgetValue
import bpm.world.devices.MonitorBlockEntity
import bpm.world.devices.MonitorWall
import bpm.world.devices.Widget
import io.osrsx.vscript.nodes.Contribution
import io.osrsx.vscript.nodes.library
import io.osrsx.vscript.vm.StructValue

/**
 * `monitor.*` — writing to a linked Quantum Monitor. A screen shows a list of [Widget]s, top to bottom:
 * text, an item, a fluid bar, an energy bar, a plain bar. The constructors here make one each; `show` hands a
 * list to a wall (any tile of it — the content lands on the wall's origin and the whole wall lights up);
 * `clear` darkens it. Build the list every tick from live values and the screen is live.
 */
object MonitorNodes {
    private fun ControllerHost.monitor(link: String): MonitorBlockEntity? =
        this.link(link)?.takeIf { it.loaded }?.let { level.getBlockEntity(it.link.pos) as? MonitorBlockEntity }

    /** The origin tile's block entity for whatever tile [link] names. */
    private fun ControllerHost.origin(link: String): MonitorBlockEntity? {
        val any = monitor(link) ?: return null
        return level.getBlockEntity(MonitorWall.originOf(level, any.blockPos)) as? MonitorBlockEntity
    }

    fun contribution(host: ControllerHost): Contribution = library("monitor", "Monitors") {
        func("text") {
            title("Text Widget")
            doc("A line of text for a monitor: in a colour (`#rrggbb`, or white / mint / teal / green / amber / red / grey / blue / orchid — mint when empty), at a size (1 is normal, 2 double), aligned Left, Center or Right.")
            val text = param("Text", McVs.string, "what it says")
            val colour = param("Colour", McVs.string, "a colour name or #rrggbb; empty for the screen's mint", default = "")
            val size = param("Size", McVs.int, "1 to 4 times the normal height", default = 1L)
            val align = param("Align", McVs.string, "Left, Center or Right", default = "Left")
            val span = param("Span", McVs.int, "columns it takes; 0 for the whole row (a heading)", default = 0L)
            result("Widget", McVs.widget)
            query { WidgetValue.of(Widget(Widget.TEXT, text = text(), colour = colour(), size = size().toInt().coerceIn(1, 4), align = align(), span = span().toInt().coerceIn(0, 8))) }
        }
        func("item") {
            title("Item Widget")
            doc("An item on a monitor: its icon with a count on it (Count when given — a total across an inventory — else the stack's own), and a label beside it (its name when the label is empty). An empty stack shows as an empty slot.")
            val stack = param("Stack", McVs.itemStack.orNull(), "which stack; its count is shown")
            val label = param("Label", McVs.string, "text beside it; empty for the item's name", default = "")
            val count = param("Count", McVs.int, "the number shown on the icon; 0 for the stack's own count", default = 0L)
            val span = param("Span", McVs.int, "columns it takes; 0 for the whole row", default = 1L)
            result("Widget", McVs.widget)
            query { WidgetValue.of(Widget(Widget.ITEM, item = ItemStackValue.stack(stack()), label = label(), value = count().toDouble().coerceAtLeast(0.0), span = span().toInt().coerceIn(0, 8))) }
        }
        func("fluid") {
            title("Fluid Widget")
            doc("A fluid gauge on a monitor: a bar filled Amount of Capacity millibuckets in the fluid's own look, with a label (the fluid's name when empty) and the numbers.")
            val fluid = param("Fluid", McVs.fluid.orNull(), "which fluid, by id; empty for an empty gauge")
            val amount = param("Amount", McVs.int, "millibuckets held", default = 0L)
            val capacity = param("Capacity", McVs.int, "millibuckets in all", default = 1000L)
            val label = param("Label", McVs.string, "text on the gauge; empty for the fluid's name", default = "")
            val span = param("Span", McVs.int, "columns it takes; 0 for the whole row", default = 1L)
            result("Widget", McVs.widget)
            query { WidgetValue.of(Widget(Widget.FLUID, fluid = fluid().orEmpty(), value = amount().toDouble(), max = capacity().toDouble(), label = label(), unit = "mB", span = span().toInt().coerceIn(0, 8))) }
        }
        func("energy") {
            title("Energy Widget")
            doc("An energy gauge on a monitor: a teal bar filled Stored of Capacity FE, with a label and the numbers.")
            val stored = param("Stored", McVs.int, "FE held", default = 0L)
            val capacity = param("Capacity", McVs.int, "FE in all", default = 1L)
            val label = param("Label", McVs.string, "text on the gauge", default = "Energy")
            val span = param("Span", McVs.int, "columns it takes; 0 for the whole row", default = 1L)
            result("Widget", McVs.widget)
            query { WidgetValue.of(Widget(Widget.ENERGY, value = stored().toDouble(), max = capacity().toDouble(), label = label(), unit = "FE", span = span().toInt().coerceIn(0, 8))) }
        }
        func("bar") {
            title("Bar Widget")
            doc("A plain gauge on a monitor: a bar filled Value of Max in a colour, with a label and the numbers (with a unit, when given). Progress, a count against a cap, anything.")
            val label = param("Label", McVs.string, "text on the bar")
            val value = param("Value", McVs.float, "how much", default = 0.0)
            val max = param("Max", McVs.float, "of how much", default = 100.0)
            val colour = param("Colour", McVs.string, "a colour name or #rrggbb; empty for teal", default = "")
            val unit = param("Unit", McVs.string, "shown after the numbers", default = "")
            val span = param("Span", McVs.int, "columns it takes; 0 for the whole row", default = 1L)
            result("Widget", McVs.widget)
            query { WidgetValue.of(Widget(Widget.BAR, label = label(), value = value(), max = max(), colour = colour(), unit = unit(), span = span().toInt().coerceIn(0, 8))) }
        }
        func("show") {
            title("Show On Monitor")
            doc("Put a list of widgets on a linked monitor — any tile of a wall; the whole wall lights up and shows them top to bottom, replacing what was there. Answers false when the link is not a monitor.")
            val link = param("Link", McVs.link, "which monitor")
            val widgets = param("Widgets", McVs.widget.list(), "what to show, top to bottom")
            result("Ok", McVs.bool)
            command {
                val origin = host.origin(link()) ?: return@command false
                origin.show(widgets().mapNotNull { WidgetValue.toWidget(it) })
                MonitorWall.setOn(host.level, origin.blockPos, true)
                true
            }
        }
        func("clear") {
            title("Clear Monitor")
            doc("Take everything off a linked monitor's wall and darken it. Answers false when the link is not a monitor.")
            val link = param("Link", McVs.link, "which monitor")
            result("Ok", McVs.bool)
            command {
                val origin = host.origin(link()) ?: return@command false
                origin.clear()
                MonitorWall.setOn(host.level, origin.blockPos, false)
                true
            }
        }
        func("size") {
            title("Monitor Size")
            doc("How big the wall a linked monitor belongs to is, in tiles across and down — to fit what is shown. 0 by 0 when the link is not a monitor.")
            val link = param("Link", McVs.link, "which monitor")
            val width = result("Width", McVs.int)
            val height = result("Height", McVs.int)
            query {
                width set 0L
                height set 0L
                val origin = host.origin(link()) ?: return@query null
                val (w, h) = MonitorWall.sizeOf(host.level, origin.blockPos)
                width set w.toLong()
                height set h.toLong()
                null
            }
        }
        func("isOn") {
            title("Monitor Is On")
            doc("Whether a linked monitor is lit.")
            val link = param("Link", McVs.link, "which monitor")
            result("On", McVs.bool)
            query { host.monitor(link())?.on ?: false }
        }
    }

    @Suppress("unused")
    private fun widgetOf(v: Any?): StructValue? = WidgetValue.record(v)
}
