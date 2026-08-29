package bpm.catalog.values

import bpm.world.devices.Widget
import io.osrsx.vscript.vm.StructValue

/**
 * The `Widget` record as the graph sees it — a plain data record with every field a [Widget] has, so a
 * script can build one with Make (or the `monitor.*` constructors) and hand a list of them to
 * `monitor.show`. Field order is the record's; [toWidget] and [of] are the two directions.
 */
object WidgetValue {
    const val TYPE = "Widget"
    val FIELDS = listOf("kind", "text", "label", "value", "max", "item", "fluid", "colour", "size", "align", "unit", "span")

    fun of(w: Widget): StructValue = StructValue(
        TYPE, FIELDS,
        arrayOf<Any?>(w.kind, w.text, w.label, w.value, w.max, if (w.item.isEmpty) null else ItemStackValue.record(w.item), w.fluid, w.colour, w.size.toLong(), w.align, w.unit, w.span.toLong()),
    )

    fun record(value: Any?): StructValue? = (value as? StructValue)?.takeIf { it.type == TYPE }

    fun toWidget(value: Any?): Widget? {
        val r = record(value) ?: return null
        fun str(f: String) = r.get(f)?.toString().orEmpty()
        fun num(f: String) = (r.get(f) as? Number)?.toDouble() ?: 0.0
        val kind = str("kind").ifEmpty { Widget.TEXT }.let { k -> Widget.KINDS.firstOrNull { it.equals(k, ignoreCase = true) } ?: Widget.TEXT }
        return Widget(
            kind = kind,
            text = str("text"),
            label = str("label"),
            value = num("value"),
            max = num("max"),
            item = r.get("item")?.let { ItemStackValue.stack(it) } ?: net.minecraft.world.item.ItemStack.EMPTY,
            fluid = str("fluid"),
            colour = str("colour"),
            size = ((r.get("size") as? Number)?.toInt() ?: 1).coerceIn(1, 4),
            align = str("align").ifEmpty { "Left" },
            unit = str("unit"),
            span = ((r.get("span") as? Number)?.toInt() ?: 1).coerceIn(0, 8),
        )
    }
}
