package bpm.client.fx

import kotlin.math.cos
import kotlin.math.exp

/**
 * One quantum rift, client-side only: the state behind a world-space portal. It tears open, idles pulling
 * things in ([inward]) or pushing them out, swells on each [pulse], and [close]s — after which it is [done]
 * and dropped.
 *
 * **No model and no GeckoLib.** This used to be a `GeoAnimatable` driving `quantum_rift.geo.json` through
 * five animations. The geometry is gone: a rift is a hole, and every attempt to build one out of cuboids
 * produced a funnel that looked like plumbing from the side. What is left is a lifecycle — how open, how
 * fast, how bright — that [bpm.client.render.RiftRenderer] hands to a shader on a single billboarded quad.
 * That also retires a whole class of bug: no bone traversal, no render layers, no `isReRender` contract to
 * get wrong, and no angle the thing can be viewed from that it was not designed for.
 */
class Rift(val inward: Boolean) {

    /** How fast the throat turns — scaled by how much the transfer is actually moving. */
    var flowSpeed: Double = 1.0

    /** So two rifts opened on the same tick do not breathe in step. */
    val phase: Float = (Math.random() * Math.PI * 2).toFloat()

    var age = 0
        private set
    var closing = false
        private set
    private var closedAt = -1
    private var pulseAt = -100

    val done: Boolean get() = closing && age - closedAt >= CLOSE_TICKS

    fun tick() {
        age++
    }

    fun pulse() {
        if (!closing) pulseAt = age
    }

    fun close() {
        if (closing) return
        closing = true
        closedAt = age
    }

    /**
     * 0 → 1 as it tears open, 1 while it runs, back to 0 as it closes; plus a short swell on each pulse.
     *
     * Drives both the quad's size and its alpha, so a rift grows into existence and shrinks out of it
     * rather than popping. Values above 1 during a pulse are wanted — the shader clamps the brightness but
     * the size does not, so a big transfer visibly flexes the mouth.
     */
    fun openness(partialTick: Float): Float {
        val t = age + partialTick
        val opened = (t / OPEN_TICKS).coerceIn(0f, 1f)
        val shut = if (!closing) 1f else 1f - ((t - closedAt) / CLOSE_TICKS).coerceIn(0f, 1f)
        val since = t - pulseAt

        // **A rift is only as open as the traffic through it.**
        //
        // Openness used to depend on the Transfer object existing, and a Transfer lives until the server
        // says END — so a graph looping `move; wait 20` held one tear open across every gap, because from
        // the client's side the stream never ended. Short waits hid it: the batches came faster than the
        // eye could separate, so a permanently-open tear read as a busy one. At `wait 20` the gap became
        // visible and the tear was plainly just sitting there.
        //
        // Tying it to the last batch instead makes the behaviour fall out for any interval, with nothing to
        // tune per graph: hold for a beat, then fall shut. A `wait 10` never leaves the hold and stays open
        // continuously; a `wait 20` closes in the gap and tears itself open again on the next batch.
        val alive = 1f - ((since - HOLD_TICKS) / FADE_TICKS).coerceIn(0f, 1f)

        val swell = if (since in 0f..PULSE_TICKS) 0.22f * (1f - since / PULSE_TICKS) else 0f
        return ease(opened) * ease(shut) * ease(alive) + swell * alive
    }

    /**
     * The kick from the last batch, ringing down: how the tear reacts to something going through it.
     *
     * A damped oscillation rather than a fade, which is what makes it feel like a membrane rather than a
     * light being turned up — it overshoots, comes back past rest, and settles. Unsigned: WHICH WAY it
     * throws is the travel vector the renderer applies it along, so a mouth letting things out lurches
     * after them and one taking things in gets knocked back by them, from the same curve.
     */
    fun recoil(partialTick: Float): Float {
        val x = (age + partialTick - pulseAt) / RING_TICKS
        if (x < 0f || x > 1f) return 0f
        return exp(-4.5f * x) * cos(9.2f * x)
    }

    /** Smoothstep, so the tear does not start and stop with a corner. */
    private fun ease(x: Float): Float = x * x * (3f - 2f * x)

    companion object {
        const val OPEN_TICKS = 5f
        const val CLOSE_TICKS = 8
        private const val PULSE_TICKS = 6f

        /** How long after a batch the tear stays fully open, before it starts falling shut. */
        private const val HOLD_TICKS = 11f

        /** …and how long it takes to shut once it starts. Together these set the interval that reads as continuous. */
        private const val FADE_TICKS = 8f

        /** How long the kick takes to ring out. */
        private const val RING_TICKS = 14f

        /** How far the ring throws the tear along the travel vector, in blocks. */
        const val RECOIL_TRAVEL = 0.22f

        /** …and how much of it also goes into the tear's size. */
        const val RECOIL_SIZE = 0.16f
    }
}
