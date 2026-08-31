package bpm.world.devices

import bpm.client.render.MonitorLayout
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/**
 * Where a click on a monitor's glass lands, in the same screen pixels the renderer draws in.
 *
 * The wall is 32 screen pixels to a block with a 4 px bezel round the outside (`MonitorScreenRenderer`), the
 * origin tile is the viewer's top-left, `facing.counterClockWise` runs across and `below` runs down
 * ([MonitorWall]). Everything here is that, inverted — and it is deliberately pure arithmetic with the world
 * lookups hoisted out, so the mapping can be tested rather than squinted at in game.
 */
object MonitorHit {
    /** Screen pixels per block, and the bezel the glass is inset by — [MonitorScreenRenderer]'s own numbers. */
    const val TILE = 32
    const val BEZEL = 4

    /** A point on the glass, in screen pixels from the top-left of the visible area. */
    data class Point(val x: Int, val y: Int)

    /**
     * The screen pixel a click landed on.
     *
     * @param tileAcross how many tiles right of the origin the clicked tile is
     * @param tileDown how many tiles below the origin it is
     * @param across where in that tile, 0 at its left edge to 1 at its right
     * @param down where in that tile, 0 at its top to 1 at its bottom
     */
    fun point(tileAcross: Int, tileDown: Int, across: Double, down: Double): Point = Point(
        ((tileAcross + across) * TILE - BEZEL).toInt(),
        ((tileDown + down) * TILE - BEZEL).toInt(),
    )

    /** The visible size of a wall [w] by [h] tiles, in screen pixels — the box the layout is placed in. */
    fun screenSize(w: Int, h: Int): Pair<Int, Int> = (TILE * w - 2 * BEZEL) to (TILE * h - 2 * BEZEL)

    /**
     * Which widget covers [at], or null for a miss. Re-runs the renderer's own layout, so what answers a
     * click is exactly what was drawn.
     *
     * @param pressableOnly ignore everything a person cannot press, so clicking a label or a gauge falls
     *   through to whatever the block would otherwise have done
     */
    fun widgetAt(widgets: List<Widget>, w: Int, h: Int, at: Point, pressableOnly: Boolean = true): Found? {
        val (width, height) = screenSize(w, h)
        if (at.x < 0 || at.y < 0 || at.x >= width || at.y >= height) return null
        val cells = widgets.map { MonitorLayout.Cell(Widget.heightOf(it), it.span) }
        for (p in MonitorLayout.place(cells, width, height)) {
            if (at.x < p.x || at.x >= p.x + p.w || at.y < p.y || at.y >= p.y + p.h) continue
            val widget = widgets[p.index]
            if (pressableOnly && !Widget.isPressable(widget.kind)) return null
            val along = if (p.w <= 1) 0.0 else ((at.x - p.x).toDouble() / (p.w - 1)).coerceIn(0.0, 1.0)
            return Found(widget, along)
        }
        return null
    }

    /**
     * A widget under the cursor, and how far along it the cursor is, 0 at its left edge to 1 at its right.
     *
     * [along] is what a slider is set from — the same number whether the click came from a wall or a panel,
     * so one rule decides what dragging means.
     */
    data class Found(val widget: Widget, val along: Double)

    /**
     * Where in a tile a world point sits, as two fractions from its top-left as the viewer sees it.
     *
     * [facing] is the way the screen looks, so `counterClockWise` is the viewer's right. Y is inverted
     * because the world counts up and a screen counts down.
     */
    fun inTile(facing: Direction, tile: BlockPos, x: Double, y: Double, z: Double): Pair<Double, Double> {
        val fx = (x - tile.x).coerceIn(0.0, 1.0)
        val fy = (y - tile.y).coerceIn(0.0, 1.0)
        val fz = (z - tile.z).coerceIn(0.0, 1.0)
        val across = when (facing.counterClockWise) {
            Direction.EAST -> fx
            Direction.WEST -> 1.0 - fx
            Direction.SOUTH -> fz
            else -> 1.0 - fz
        }
        return across to (1.0 - fy)
    }

    /** How many tiles right of [origin], and how many below it, the tile at [pos] is. */
    fun tileOffset(facing: Direction, origin: BlockPos, pos: BlockPos): Pair<Int, Int> {
        val right = facing.counterClockWise
        val dx = pos.x - origin.x
        val dz = pos.z - origin.z
        val across = dx * right.stepX + dz * right.stepZ
        return across to (origin.y - pos.y)
    }
}
