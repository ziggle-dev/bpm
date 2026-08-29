package bpm.world.devices

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * One thing on a monitor's screen. [kind] says which of the fields matter:
 *
 * - `Text` — [text], in [colour], [size] times the normal height, [align]ed left / center / right
 * - `Item` — [item], with [label] beside it (the item's name when empty); [value] > 0 is the count shown on
 *   the icon (a total across an inventory), else the stack's own
 * - `Fluid` — a bar of [fluid] (a registry id, drawn with its own look), [value] of [max] mB, with [label]
 * - `Energy` — a teal bar, [value] of [max] FE, with [label]
 * - `Bar` — a bar in [colour], [value] of [max] [unit], with [label]
 *
 * Widgets flow across the screen in columns (two on a wide wall) in the order given, each taking [span]
 * columns — 0 for the whole row, a heading; what does not fit is not drawn. A plain data record on
 * the graph side (`Widget`), so a script can build one with Make or the `monitor.*` constructors.
 */
data class Widget(
    val kind: String,
    val text: String = "",
    val label: String = "",
    val value: Double = 0.0,
    val max: Double = 0.0,
    val item: ItemStack = ItemStack.EMPTY,
    val fluid: String = "",
    val colour: String = "",
    val size: Int = 1,
    val align: String = "Left",
    val unit: String = "",
    val span: Int = 1,
) {
    /** Whether [other] shows exactly this — [ItemStack] compares by identity, so this is spelled out. */
    fun sameAs(other: Widget): Boolean =
        kind == other.kind && text == other.text && label == other.label && value == other.value && max == other.max &&
            fluid == other.fluid && colour == other.colour && size == other.size && align == other.align && unit == other.unit && span == other.span &&
            ItemStack.matches(item, other.item)

    fun save(registries: HolderLookup.Provider): CompoundTag = CompoundTag().also { t ->
        t.putString("kind", kind)
        if (text.isNotEmpty()) t.putString("text", text)
        if (label.isNotEmpty()) t.putString("label", label)
        if (value != 0.0) t.putDouble("value", value)
        if (max != 0.0) t.putDouble("max", max)
        if (!item.isEmpty) t.put("item", item.save(registries))
        if (fluid.isNotEmpty()) t.putString("fluid", fluid)
        if (colour.isNotEmpty()) t.putString("colour", colour)
        if (size != 1) t.putInt("size", size)
        if (align != "Left") t.putString("align", align)
        if (unit.isNotEmpty()) t.putString("unit", unit)
        if (span != 1) t.putInt("span", span)
    }

    companion object {
        const val TEXT = "Text"
        const val ITEM = "Item"
        const val FLUID = "Fluid"
        const val ENERGY = "Energy"
        const val BAR = "Bar"
        val KINDS = listOf(TEXT, ITEM, FLUID, ENERGY, BAR)

        /** At most this many on one screen: a wall is finite, and a runaway list must not be a packet. */
        const val MAX_WIDGETS = 64

        fun load(t: CompoundTag, registries: HolderLookup.Provider): Widget = Widget(
            kind = t.getString("kind").ifEmpty { TEXT },
            text = t.getString("text"),
            label = t.getString("label"),
            value = t.getDouble("value"),
            max = t.getDouble("max"),
            item = if (t.contains("item")) ItemStack.parseOptional(registries, t.getCompound("item")) else ItemStack.EMPTY,
            fluid = t.getString("fluid"),
            colour = t.getString("colour"),
            size = if (t.contains("size")) t.getInt("size").coerceIn(1, 4) else 1,
            align = t.getString("align").ifEmpty { "Left" },
            unit = t.getString("unit"),
            span = if (t.contains("span")) t.getInt("span").coerceIn(0, 8) else 1,
        )

        fun saveAll(list: List<Widget>, registries: HolderLookup.Provider): ListTag = ListTag().also { l -> list.forEach { l.add(it.save(registries)) } }

        fun loadAll(tag: ListTag, registries: HolderLookup.Provider): List<Widget> =
            (0 until tag.size).mapNotNull { i -> (tag[i] as? CompoundTag)?.let { load(it, registries) } }
    }
}

/**
 * A wall of monitors: tiles of one facing that touch join into one screen. Its **origin** is the tile in
 * the viewer's top-left corner — the one whose `up` and `left` edges are outer — and that is the tile whose
 * block entity holds the screen's content and whose renderer draws it across the whole wall. Anything a
 * script does to a tile is done to its wall's origin.
 */
object MonitorWall {
    /** No wall is measured past this many tiles in either direction. */
    const val MAX = 16

    fun joins(level: BlockGetter, a: BlockState, pos: BlockPos): Boolean {
        val b = level.getBlockState(pos)
        return b.block is MonitorBlock && a.block is MonitorBlock && b.getValue(MonitorBlock.FACING) == a.getValue(MonitorBlock.FACING)
    }

    /** The origin of the wall the monitor at [pos] is part of ([pos] itself when it is alone). */
    fun originOf(level: BlockGetter, pos: BlockPos): BlockPos {
        val state = level.getBlockState(pos)
        if (state.block !is MonitorBlock) return pos
        val left = state.getValue(MonitorBlock.FACING).clockWise
        var p = pos
        var n = 0
        while (n++ < MAX && joins(level, state, p.above())) p = p.above()
        n = 0
        while (n++ < MAX && joins(level, state, p.relative(left))) p = p.relative(left)
        return p
    }

    fun isOrigin(level: BlockGetter, pos: BlockPos): Boolean = originOf(level, pos) == pos

    /** Tiles across (toward the viewer's right) and down from [origin]. */
    fun sizeOf(level: BlockGetter, origin: BlockPos): Pair<Int, Int> {
        val state = level.getBlockState(origin)
        if (state.block !is MonitorBlock) return 1 to 1
        val right = state.getValue(MonitorBlock.FACING).counterClockWise
        var w = 1
        while (w < MAX && joins(level, state, origin.relative(right, w))) w++
        var h = 1
        while (h < MAX && joins(level, state, origin.below(h))) h++
        return w to h
    }

    /** Every tile of the wall whose origin is [origin]. */
    fun tiles(level: BlockGetter, origin: BlockPos): List<BlockPos> {
        val state = level.getBlockState(origin)
        if (state.block !is MonitorBlock) return listOf(origin)
        val right = state.getValue(MonitorBlock.FACING).counterClockWise
        val (w, h) = sizeOf(level, origin)
        val out = ArrayList<BlockPos>()
        for (dy in 0 until h) for (dx in 0 until w) out += origin.relative(right, dx).below(dy)
        return out
    }

    /** Lights every tile of the wall, or darkens it. */
    fun setOn(level: Level, origin: BlockPos, on: Boolean) {
        for (p in tiles(level, origin)) {
            val s = level.getBlockState(p)
            if (s.block is MonitorBlock && s.getValue(MonitorBlock.ON) != on) level.setBlock(p, s.setValue(MonitorBlock.ON, on), Block.UPDATE_ALL)
        }
    }
}

/** The list of widgets as NBT, for the block entity's synced fields. */
internal fun CompoundTag.putWidgets(key: String, widgets: List<Widget>, registries: HolderLookup.Provider) {
    if (widgets.isNotEmpty()) put(key, Widget.saveAll(widgets, registries))
}

internal fun CompoundTag.getWidgets(key: String, registries: HolderLookup.Provider): List<Widget> =
    if (contains(key)) Widget.loadAll(getList(key, Tag.TAG_COMPOUND.toInt()), registries) else emptyList()
