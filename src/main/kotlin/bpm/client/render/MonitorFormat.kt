package bpm.client.render

import kotlin.math.roundToInt

/** Numbers as a screen shows them: full with separators, short (`1.3k`, `12k`, `1.2M`), or a percentage. Pure. */
object MonitorFormat {
    /** `360,000`, `12`, `2.5`. */
    fun full(d: Double): String = when {
        d >= 1000 -> String.format("%,d", d.toLong())
        d == Math.floor(d) -> d.toLong().toString()
        else -> String.format("%.1f", d)
    }

    /** At most four characters before the suffix: `999`, `1.3k`, `12k`, `360k`, `1.2M`, `4G`. */
    fun short(d: Double): String {
        if (d < 1000) return full(d)
        val units = listOf("k" to 1e3, "M" to 1e6, "G" to 1e9, "T" to 1e12)
        var (suffix, div) = units[0]
        for (u in units) if (d >= u.second) { suffix = u.first; div = u.second }
        val v = d / div
        return if (v < 10) String.format("%.1f", v).removeSuffix(".0") + suffix else "${v.toLong()}$suffix"
    }

    fun percent(value: Double, max: Double): String =
        if (max <= 0.0) "0%" else "${((value / max).coerceIn(0.0, 1.0) * 100).roundToInt()}%"

    /** `value / max unit`, in full. */
    fun ratio(value: Double, max: Double, unit: String): String = "${full(value)} / ${full(max)}${if (unit.isNotEmpty()) " $unit" else ""}"

    /** `value/max unit`, short. */
    fun shortRatio(value: Double, max: Double, unit: String): String = "${short(value)}/${short(max)}${if (unit.isNotEmpty()) " $unit" else ""}"
}
