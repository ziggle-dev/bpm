package bpm.world.devices

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The click-to-pixel mapping, tested rather than eyeballed: it is arithmetic against constants two other
 * files also use, and getting it subtly wrong makes a wall answer the wrong button, which is the kind of bug
 * that looks like anything but a coordinate error.
 */
class MonitorHitTest {

    @Test
    fun `the top-left of the origin tile's glass is the origin of the screen`() {
        // The glass starts one bezel inside the block, so the very corner of the block is off-screen.
        assertEquals(MonitorHit.Point(-4, -4), MonitorHit.point(0, 0, 0.0, 0.0))
        // A bezel in, and the screen begins.
        assertEquals(MonitorHit.Point(0, 0), MonitorHit.point(0, 0, 4.0 / 32, 4.0 / 32))
    }

    @Test
    fun `a tile further along the wall is a whole tile further across the screen`() {
        val a = MonitorHit.point(0, 0, 0.5, 0.5)
        val b = MonitorHit.point(1, 0, 0.5, 0.5)
        val c = MonitorHit.point(0, 1, 0.5, 0.5)
        assertEquals(MonitorHit.TILE, b.x - a.x)
        assertEquals(a.y, b.y)
        assertEquals(MonitorHit.TILE, c.y - a.y)
        assertEquals(a.x, c.x)
    }

    @Test
    fun `a wall's visible size loses one bezel at each edge, not each tile`() {
        assertEquals(24 to 24, MonitorHit.screenSize(1, 1))
        assertEquals(152 to 56, MonitorHit.screenSize(5, 2))
    }

    @Test
    fun `the viewer's right runs the right way for every facing`() {
        val tile = BlockPos(0, 0, 0)
        // Facing north, the viewer stands to the north looking south; their right is west, so a click at
        // high x is at the LEFT of the screen.
        val (northAcross, _) = MonitorHit.inTile(Direction.NORTH, tile, 0.9, 0.5, 0.5)
        assertEquals(0.1, northAcross, 1e-6)
        // Facing south, it is the other way about.
        val (southAcross, _) = MonitorHit.inTile(Direction.SOUTH, tile, 0.9, 0.5, 0.5)
        assertEquals(0.9, southAcross, 1e-6)
    }

    @Test
    fun `the world counts up and a screen counts down`() {
        val (_, top) = MonitorHit.inTile(Direction.NORTH, BlockPos(0, 0, 0), 0.5, 0.95, 0.5)
        val (_, bottom) = MonitorHit.inTile(Direction.NORTH, BlockPos(0, 0, 0), 0.5, 0.05, 0.5)
        assertEquals(0.05, top, 1e-6)
        assertEquals(0.95, bottom, 1e-6)
    }

    @Test
    fun `a tile is placed by how far it is along the wall, in the facing's own frame`() {
        val origin = BlockPos(10, 70, 10)
        // Facing south: the viewer's right is east, so +x is across and -y is down.
        assertEquals(2 to 0, MonitorHit.tileOffset(Direction.SOUTH, origin, BlockPos(12, 70, 10)))
        assertEquals(0 to 3, MonitorHit.tileOffset(Direction.SOUTH, origin, BlockPos(10, 67, 10)))
        // Facing north reverses across.
        assertEquals(2 to 0, MonitorHit.tileOffset(Direction.NORTH, origin, BlockPos(8, 70, 10)))
    }

    @Test
    fun `a click finds the widget under it, and only a pressable one`() {
        val text = Widget(Widget.TEXT, text = "Power", span = 0)
        val button = Widget(Widget.BUTTON, label = "Run", id = "run", span = 0)
        val widgets = listOf(text, button)
        // A 5x2 wall: 152x56 px, one row each because both span the full width.
        val onText = MonitorHit.Point(20, 2)
        val onButton = MonitorHit.Point(20, 2 + Widget.heightOf(text) + MonitorLayoutGap)
        assertNull(MonitorHit.widgetAt(widgets, 5, 2, onText), "a label answered a click")
        assertSame(button, MonitorHit.widgetAt(widgets, 5, 2, onButton)?.widget)
        // Off the glass entirely.
        assertNull(MonitorHit.widgetAt(widgets, 5, 2, MonitorHit.Point(-1, 10)))
        assertNull(MonitorHit.widgetAt(widgets, 5, 2, MonitorHit.Point(10, 999)))
    }

    @Test
    fun `how far along a widget the cursor is runs 0 at its left edge to 1 at its right`() {
        val slider = Widget(Widget.SLIDER, id = "vol", max = 100.0, span = 0)
        val widgets = listOf(slider)
        val (width, _) = MonitorHit.screenSize(5, 2)
        val left = MonitorHit.widgetAt(widgets, 5, 2, MonitorHit.Point(MonitorLayoutMargin, 4))
        val right = MonitorHit.widgetAt(widgets, 5, 2, MonitorHit.Point(width - MonitorLayoutMargin - 1, 4))
        assertEquals(0.0, left!!.along, 1e-6)
        assertEquals(1.0, right!!.along, 1e-6)
    }

    /** The layout's own margin and gap, so the test moves by exactly what the renderer does. */
    private val MonitorLayoutMargin get() = bpm.client.render.MonitorLayout.MARGIN


    private val MonitorLayoutGap get() = bpm.client.render.MonitorLayout.GAP
}
