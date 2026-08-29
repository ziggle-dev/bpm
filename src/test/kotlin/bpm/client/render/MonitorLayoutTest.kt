package bpm.client.render

import bpm.client.render.MonitorLayout.Cell
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MonitorLayoutTest {
    private val title = Cell(9, 0)
    private val item = Cell(16, 1)
    private val gauge = Cell(16, 1)

    @Test
    fun aSingleTileIsOneColumnAndStacks() {
        assertEquals(1, MonitorLayout.columns(24 - 4))
        val placed = MonitorLayout.place(listOf(title, title, item), 24, 24)
        assertEquals(listOf(0, 1), placed.map { it.index }, "two lines fit a 24 px tile; the item under them does not")
        assertEquals(2, placed[0].y)
        assertEquals(13, placed[1].y)
        assertEquals(20, placed[0].w)
    }

    @Test
    fun aFiveByTwoWallHasTwoColumnsAndAHeadingSpansBoth() {
        val placed = MonitorLayout.place(listOf(title, item, item, gauge, item), 152, 56)
        assertEquals(5, placed.size)
        assertEquals(148, placed[0].w, "the heading takes the whole row")
        assertEquals(placed[1].y, placed[2].y, "two items share a row")
        assertEquals(2, placed[1].x)
        assertEquals(2 + 73 + 2, placed[2].x)
        assertEquals(13, placed[1].y)
        assertEquals(31, placed[3].y, "the third row starts under the tallest of the second")
        assertEquals(placed[3].y, placed[4].y)
    }

    @Test
    fun aFullRowWidgetWrapsAPartialRow() {
        val placed = MonitorLayout.place(listOf(item, title, item), 152, 56)
        assertEquals(2, placed[0].y)
        assertEquals(20, placed[1].y, "the heading starts a new row after the lone item")
        assertEquals(31, placed[2].y)
    }

    @Test
    fun aSpanWiderThanTheScreenIsClampedAndTheOverflowIsLeftOut() {
        val placed = MonitorLayout.place(listOf(Cell(16, 5), gauge, gauge, gauge), 152, 40)
        assertEquals(148, placed[0].w)
        assertEquals(listOf(0, 1, 2), placed.map { it.index }, "the fourth gauge would need a third row")
    }
}
