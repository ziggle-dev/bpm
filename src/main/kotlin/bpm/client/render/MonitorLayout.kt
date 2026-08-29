package bpm.client.render

import kotlin.math.max
import kotlin.math.min

/**
 * Where each widget goes on a screen, in screen pixels. The screen is split into as many columns of at
 * least [MIN_COLUMN] px as fit (one on a single tile, two on a 5-wide wall); widgets flow left to right,
 * each taking its `span` columns (0 = the whole row, the default for text — a heading), wrapping when the
 * row is full, and each row is as tall as its tallest widget. What does not fit down the screen is left out.
 * Pure arithmetic, so it is unit-tested without a client.
 */
object MonitorLayout {
    const val MIN_COLUMN = 72
    const val GAP = 2
    const val MARGIN = 2

    /** A widget's needs: how tall it is, how many columns it wants (0 = a full row). */
    data class Cell(val height: Int, val span: Int)

    /** A widget's place: the index of its cell and its rectangle. */
    data class Placed(val index: Int, val x: Int, val y: Int, val w: Int, val h: Int)

    fun columns(innerWidth: Int): Int = max(1, (innerWidth + GAP) / (MIN_COLUMN + GAP))

    fun place(cells: List<Cell>, width: Int, height: Int): List<Placed> {
        val inner = width - 2 * MARGIN
        val cols = columns(inner)
        val colW = (inner - (cols - 1) * GAP) / cols
        val out = ArrayList<Placed>()
        var y = MARGIN
        var col = 0
        var rowH = 0
        for ((i, c) in cells.withIndex()) {
            val span = if (c.span <= 0) cols else min(c.span, cols)
            if (col > 0 && col + span > cols) {
                y += rowH + GAP
                col = 0
                rowH = 0
            }
            if (y + c.height > height - MARGIN) break
            out += Placed(i, MARGIN + col * (colW + GAP), y, colW * span + GAP * (span - 1), c.height)
            col += span
            rowH = max(rowH, c.height)
            if (col >= cols) {
                y += rowH + GAP
                col = 0
                rowH = 0
            }
        }
        return out
    }
}
