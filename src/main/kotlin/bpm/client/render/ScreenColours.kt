package bpm.client.render

/**
 * The colours a bpm screen draws in — palette v4's mint-and-teal, shared by the monitor walls and the
 * on-screen panels so a widget looks the same wherever it is shown.
 *
 * A graph names a colour once (`teal`, `amber`, `#ff8800`) and both surfaces resolve it the same way; keeping
 * two copies of this table is how they would quietly drift apart. Pure, so it is unit-testable.
 */
object ScreenColours {
    const val MINT = 0xFFB8FFF0.toInt()
    const val TEAL = 0xFF4DFFD8.toInt()
    const val DIM = 0xFF9AA3B5.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** The empty part of a gauge, and the hairline round an item slot. */
    const val TRACK = 0x66102030
    const val FRAME = 0x334DFFD8

    val NAMED: Map<String, Int> = mapOf(
        "white" to WHITE, "mint" to MINT, "teal" to TEAL, "green" to 0xFFA8F04A.toInt(), "amber" to 0xFFFFB84D.toInt(),
        "red" to 0xFFF26D6D.toInt(), "grey" to DIM, "gray" to DIM, "blue" to 0xFF8AB4F8.toInt(), "orchid" to 0xFFF0A3D6.toInt(),
    )

    /** `#rrggbb`, a palette name, or [fallback]. */
    fun colourOf(spec: String, fallback: Int): Int {
        val s = spec.trim().lowercase()
        if (s.isEmpty()) return fallback
        NAMED[s]?.let { return it }
        val hex = s.removePrefix("#")
        return hex.toLongOrNull(16)?.let { (0xFF000000L or (it and 0xFFFFFF)).toInt() } ?: fallback
    }

    /** Two fifths of the way to white — the lip along the top of a gauge's fill. */
    fun lighten(argb: Int): Int {
        val r = (argb shr 16 and 0xFF)
        val g = (argb shr 8 and 0xFF)
        val b = argb and 0xFF
        return (0xFF shl 24) or ((r + (255 - r) * 2 / 5) shl 16) or ((g + (255 - g) * 2 / 5) shl 8) or (b + (255 - b) * 2 / 5)
    }

    /** ARGB as ImGui wants it: ABGR. */
    fun toImGui(argb: Int): Int {
        val a = argb ushr 24 and 0xFF
        val r = argb shr 16 and 0xFF
        val g = argb shr 8 and 0xFF
        val b = argb and 0xFF
        return (a shl 24) or (b shl 16) or (g shl 8) or r
    }
}
