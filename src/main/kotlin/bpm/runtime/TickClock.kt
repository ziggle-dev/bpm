package bpm.runtime

import io.osrsx.vscript.host.Clock

/**
 * Game time for the VM: [ticks] server ticks, 50 ms each, advanced by the [RuntimeManager] once per tick.
 *
 * Every script sees the same clock and it only moves between ticks, so `delay(ms: 50)` is exactly one tick,
 * a run that lags does not lose time it never had, and the phase deadlines vscript keeps (quiesce, drain) are
 * measured in game time rather than wall time. `localDate`/`hourOfDay` keep a wall clock of their own.
 */
class TickClock : Clock {
    @Volatile
    var ticks: Long = 0

    override fun nowMs(): Long = ticks * MS_PER_TICK

    companion object {
        const val MS_PER_TICK = 50L
    }
}
